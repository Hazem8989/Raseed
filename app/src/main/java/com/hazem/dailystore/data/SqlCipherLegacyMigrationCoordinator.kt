package com.hazem.dailystore.data

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Crash-recoverable, same-device plaintext SQLite -> SQLCipher migration.
 *
 * This coordinator is intentionally not wired into application startup yet.
 * Callers must prevent every database consumer from opening the legacy file
 * while migrate() runs. The original file is retained as rollback until the
 * encrypted replacement has passed integrity and foreign-key validation.
 */
internal class SqlCipherLegacyMigrationCoordinator(
    context: Context,
    journalFileName: String = DEFAULT_JOURNAL_NAME,
    private val afterCheckpoint: (Checkpoint) -> Unit = {}
) {
    enum class MigrationResult { MIGRATED, ALREADY_ENCRYPTED }
    enum class RecoveryResult { NOTHING_TO_RECOVER, MIGRATION_COMPLETED, MIGRATION_ROLLED_BACK }
    enum class Checkpoint {
        ENCRYPTED_STAGE_VERIFIED,
        JOURNAL_PREPARED,
        LEGACY_DATABASE_RENAMED,
        LEGACY_BACKUP_RECORDED,
        ENCRYPTED_DATABASE_RENAMED,
        ENCRYPTED_SWAP_RECORDED,
        ENCRYPTED_DATABASE_VERIFIED,
        VERIFICATION_RECORDED
    }

    private val appContext = context.applicationContext
    private val journalFile = File(appContext.noBackupFilesDir, journalFileName)
    private val journal = MigrationJournal(journalFile)

    init {
        require(journalFileName.isNotBlank() && File(journalFileName).name == journalFileName)
    }

    @Synchronized
    fun migrate(
        legacyDatabase: File,
        keyVault: DatabaseKeyVault,
        closeLegacyDatabase: () -> Unit
    ): MigrationResult = withMigrationLock {
        require(legacyDatabase.isFile && legacyDatabase.length() > 0L) {
            "قاعدة SQLite القديمة غير موجودة أو فارغة"
        }
        recoverLocked(legacyDatabase, keyVault)

        val staging = sibling(legacyDatabase, STAGING_SUFFIX)
        val rollback = sibling(legacyDatabase, ROLLBACK_SUFFIX)
        check(!staging.exists() && !rollback.exists()) {
            "توجد ملفات ترحيل سابقة بلا سجل قابل للاستعادة؛ احتفظ بها وأوقف الترحيل"
        }

        if (!isPlainSqlite(legacyDatabase)) {
            val existingKey = keyVault.getOrCreateDatabaseKey(databaseExists = true)
            try {
                SqlCipherDatabaseMigrator.validateEncryptedDatabase(legacyDatabase, existingKey)
                return@withMigrationLock MigrationResult.ALREADY_ENCRYPTED
            } finally {
                existingKey.fill(0)
            }
        }

        val databaseKey = keyVault.getOrCreateMigrationKey(legacyDatabase)
        var journalPrepared = false
        try {
            closeLegacyDatabase()
            ensureClosedLegacyDatabaseHasNoPendingWal(legacyDatabase)

            SqlCipherDatabaseMigrator.exportPlainDatabase(legacyDatabase, staging, databaseKey)
            SqlCipherDatabaseMigrator.validateEncryptedDatabase(staging, databaseKey)
            afterCheckpoint(Checkpoint.ENCRYPTED_STAGE_VERIFIED)

            journal.begin(staging, legacyDatabase, rollback)
            journalPrepared = true
            afterCheckpoint(Checkpoint.JOURNAL_PREPARED)

            check(legacyDatabase.renameTo(rollback)) {
                "تعذر حفظ قاعدة SQLite الأصلية كنقطة تراجع"
            }
            afterCheckpoint(Checkpoint.LEGACY_DATABASE_RENAMED)
            journal.advance(MigrationJournal.Phase.PREPARED, MigrationJournal.Phase.LEGACY_BACKED_UP)
            afterCheckpoint(Checkpoint.LEGACY_BACKUP_RECORDED)

            check(staging.renameTo(legacyDatabase)) {
                "تعذر تثبيت قاعدة SQLCipher؛ ستتم استعادة القاعدة الأصلية عند التعافي"
            }
            afterCheckpoint(Checkpoint.ENCRYPTED_DATABASE_RENAMED)
            journal.advance(MigrationJournal.Phase.LEGACY_BACKED_UP, MigrationJournal.Phase.ENCRYPTED_INSTALLED)
            afterCheckpoint(Checkpoint.ENCRYPTED_SWAP_RECORDED)

            SqlCipherDatabaseMigrator.validateEncryptedDatabase(legacyDatabase, databaseKey)
            afterCheckpoint(Checkpoint.ENCRYPTED_DATABASE_VERIFIED)
            journal.advance(MigrationJournal.Phase.ENCRYPTED_INSTALLED, MigrationJournal.Phase.VERIFIED)
            afterCheckpoint(Checkpoint.VERIFICATION_RECORDED)

            finishCommittedMigration(legacyDatabase, rollback, databaseKey)
            MigrationResult.MIGRATED
        } catch (error: Exception) {
            // Before the journal exists, the live SQLite file is untouched and
            // an incomplete staging file can be discarded. After journal creation,
            // preserve every file so startup recovery can make the safe decision.
            if (!journalPrepared) staging.delete()
            throw error
        } finally {
            databaseKey.fill(0)
        }
    }

    /** Must run before AppDatabase or any other database consumer is opened. */
    @Synchronized
    fun recover(legacyDatabase: File, keyVault: DatabaseKeyVault): RecoveryResult =
        withMigrationLock { recoverLocked(legacyDatabase, keyVault) }

    private fun recoverLocked(legacyDatabase: File, keyVault: DatabaseKeyVault): RecoveryResult {
        val staging = sibling(legacyDatabase, STAGING_SUFFIX)
        val rollback = sibling(legacyDatabase, ROLLBACK_SUFFIX)
        val record = journal.read()
        if (record == null) {
            check(!staging.exists() && !rollback.exists()) {
                "توجد ملفات ترحيل يتيمة بلا سجل؛ لم يتم حذف أي ملف"
            }
            return RecoveryResult.NOTHING_TO_RECOVER
        }

        require(record.liveName == legacyDatabase.name &&
            record.stagingName == staging.name && record.rollbackName == rollback.name) {
            "سجل الترحيل لا يطابق قاعدة البيانات المطلوبة؛ أوقف التعافي"
        }

        val databaseKey = keyVault.getImportedDatabaseKeyForRestoreRecovery()
            ?: throw IllegalStateException("مفتاح الترحيل مفقود؛ احتفظ بجميع الملفات وأوقف التعافي")
        try {
            val liveIsEncrypted = legacyDatabase.isFile && runCatching {
                SqlCipherDatabaseMigrator.validateEncryptedDatabase(legacyDatabase, databaseKey)
            }.isSuccess

            if (liveIsEncrypted) {
                if (rollback.exists()) {
                    check(isPlainSqlite(rollback)) {
                        "نسخة التراجع ليست قاعدة SQLite أصلية معروفة؛ لن يتم حذفها"
                    }
                }
                if (staging.exists()) {
                    check(runCatching {
                        SqlCipherDatabaseMigrator.validateEncryptedDatabase(staging, databaseKey)
                    }.isSuccess) { "ملف التجهيز المتبقي غير صالح؛ احتفظ به وأوقف التنظيف" }
                    check(staging.delete()) { "تعذر تنظيف ملف التجهيز بعد نجاح الترحيل" }
                }
                if (rollback.exists()) check(rollback.delete()) {
                    "القاعدة المشفرة صالحة لكن تعذر حذف نسخة SQLite الاحتياطية؛ سيُستكمل التنظيف لاحقاً"
                }
                journal.clear()
                return RecoveryResult.MIGRATION_COMPLETED
            }

            if (rollback.exists()) {
                check(isPlainSqlite(rollback)) {
                    "نسخة التراجع لا تحمل ترويسة SQLite الأصلية؛ لم يتم استبدال أي ملف"
                }
                if (legacyDatabase.exists()) {
                    check(!staging.exists()) {
                        "ملف القاعدة الحالي وملف التجهيز موجودان مع نسخة التراجع؛ أوقف التعافي دون حذف"
                    }
                    check(legacyDatabase.renameTo(staging)) {
                        "تعذر عزل ملف القاعدة غير المعتمد قبل استعادة الأصل"
                    }
                }
                check(rollback.renameTo(legacyDatabase)) {
                    "تعذر إعادة قاعدة SQLite الأصلية إلى مسارها"
                }
                check(isPlainSqlite(legacyDatabase)) {
                    "استعيد ملف القاعدة لكن ترويسة SQLite غير سليمة؛ احتفظ بالملف"
                }
                if (staging.exists()) check(staging.delete()) {
                    "تمت استعادة القاعدة الأصلية لكن تعذر تنظيف ملف التجهيز"
                }
                journal.clear()
                return RecoveryResult.MIGRATION_ROLLED_BACK
            }

            // No rollback rename occurred: the original database must still be
            // live. A verified encrypted stage is disposable; an unknown stage
            // is preserved and treated as corruption rather than guessed at.
            check(legacyDatabase.isFile && isPlainSqlite(legacyDatabase)) {
                "لا توجد نسخة تراجع ولا يمكن إثبات سلامة قاعدة SQLite الأصلية؛ أوقف التعافي"
            }
            if (staging.exists()) {
                check(runCatching {
                    SqlCipherDatabaseMigrator.validateEncryptedDatabase(staging, databaseKey)
                }.isSuccess) { "ملف التجهيز غير صالح؛ احتفظ به وأوقف التعافي" }
                check(staging.delete()) { "تعذر تنظيف ملف التجهيز بعد التراجع" }
            }
            journal.clear()
            return RecoveryResult.MIGRATION_ROLLED_BACK
        } finally {
            databaseKey.fill(0)
        }
    }

    private fun finishCommittedMigration(live: File, rollback: File, databaseKey: ByteArray) {
        SqlCipherDatabaseMigrator.validateEncryptedDatabase(live, databaseKey)
        check(isPlainSqlite(rollback)) { "نسخة التراجع الأصلية غير سليمة؛ لن يتم حذفها" }
        check(rollback.delete()) { "نجح الترحيل لكن تعذر حذف نسخة التراجع؛ سيُستكمل التنظيف عند التشغيل التالي" }
        journal.clear()
    }

    private fun ensureClosedLegacyDatabaseHasNoPendingWal(database: File) {
        val wal = File(database.path + "-wal")
        check(!wal.exists() || wal.length() == 0L) {
            "يوجد WAL غير مفرغ بعد إغلاق قاعدة SQLite؛ أُوقف الترحيل لحماية المعاملات"
        }
        listOf(wal, File(database.path + "-shm"), File(database.path + "-journal")).forEach {
            if (it.exists()) check(it.delete()) { "تعذر تنظيف ملف جانبي بعد إغلاق قاعدة SQLite" }
        }
    }

    private fun isPlainSqlite(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
        return file.inputStream().use { input ->
            val header = ByteArray(SQLITE_HEADER.size)
            input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)
        }
    }

    private fun sibling(database: File, suffix: String): File =
        File(database.parentFile ?: error("Database parent directory unavailable"), database.name + suffix)

    private fun <T> withMigrationLock(block: () -> T): T = withFileLock(block)

    private fun <T> withFileLock(block: () -> T): T {
        val lockFile = File(appContext.noBackupFilesDir, LOCK_FILE_NAME)
        java.io.RandomAccessFile(lockFile, "rw").use { randomAccess ->
            randomAccess.channel.lock().use { return block() }
        }
    }

    private class MigrationJournal(file: File) {
        private val atomic = AtomicFile(file)

        enum class Phase { PREPARED, LEGACY_BACKED_UP, ENCRYPTED_INSTALLED, VERIFIED }

        data class Record(
            val phase: Phase,
            val stagingName: String,
            val liveName: String,
            val rollbackName: String
        )

        @Synchronized
        fun begin(staging: File, live: File, rollback: File) {
            check(!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) {
                "يوجد سجل ترحيل سابق؛ يجب التعافي منه أولاً"
            }
            val parent = live.canonicalFile.parentFile
            require(parent != null && staging.canonicalFile.parentFile == parent &&
                rollback.canonicalFile.parentFile == parent &&
                staging.canonicalFile != live.canonicalFile &&
                rollback.canonicalFile != live.canonicalFile &&
                staging.canonicalFile != rollback.canonicalFile)
            write(Record(Phase.PREPARED, staging.name, live.name, rollback.name))
        }

        @Synchronized
        fun advance(expected: Phase, next: Phase) {
            require(next.ordinal == expected.ordinal + 1)
            val current = read() ?: error("Migration journal is missing")
            check(current.phase == expected) { "Migration journal phase changed unexpectedly" }
            write(current.copy(phase = next))
        }

        @Synchronized
        fun read(): Record? {
            if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
            try {
                val bytes = atomic.openRead().use { it.readBytes() }
                require(bytes.size > DIGEST_BYTES)
                val payload = bytes.copyOfRange(0, bytes.size - DIGEST_BYTES)
                val digest = bytes.copyOfRange(bytes.size - DIGEST_BYTES, bytes.size)
                require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), digest))
                val input = DataInputStream(ByteArrayInputStream(payload))
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                require(magic.contentEquals(MAGIC) && input.readInt() == VERSION)
                val phase = input.readInt()
                require(phase in Phase.values().indices)
                val names = List(3) { input.readUTF() }
                require(input.read() == -1 && names.toSet().size == names.size)
                names.forEach(::validateName)
                return Record(Phase.values()[phase], names[0], names[1], names[2])
            } catch (error: Exception) {
                throw IllegalStateException(
                    "سجل ترحيل SQLCipher تالف؛ احتفظ بكل الملفات وأوقف التعافي",
                    error
                )
            }
        }

        @Synchronized
        fun clear() = atomic.delete()

        private fun write(record: Record) {
            val payload = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.write(MAGIC)
                    output.writeInt(VERSION)
                    output.writeInt(record.phase.ordinal)
                    output.writeUTF(record.stagingName)
                    output.writeUTF(record.liveName)
                    output.writeUTF(record.rollbackName)
                }
            }.toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(payload)
            var stream: FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                stream.write(payload)
                stream.write(digest)
                stream.flush()
                stream.fd.sync()
                atomic.finishWrite(stream)
                stream = null
            } catch (error: Exception) {
                stream?.let(atomic::failWrite)
                throw IllegalStateException("تعذر حفظ سجل ترحيل SQLCipher", error)
            }
        }

        private fun validateName(name: String) {
            require(name.isNotBlank() && name != "." && name != ".." &&
                File(name).name == name && !name.contains('/') && !name.contains('\\'))
        }

        companion object {
            private val MAGIC = byteArrayOf(0x54, 0x44, 0x53, 0x4D, 0x49, 0x47, 0x4A, 0x31) // TDSMIGJ1
            private const val VERSION = 1
            private const val DIGEST_BYTES = 32
        }
    }

    companion object {
        private const val DEFAULT_JOURNAL_NAME = "the_daily_shop_sqlcipher_migration.journal"
        private const val LOCK_FILE_NAME = "the_daily_shop_sqlcipher_migration.lock"
        private const val STAGING_SUFFIX = ".sqlcipher-migration-staging"
        private const val ROLLBACK_SUFFIX = ".sqlite-migration-rollback"
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
