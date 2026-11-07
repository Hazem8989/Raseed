package com.hazem.dailystore.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Same-device SQLCipher replacement transaction.
 *
 * The old database is retained until the new database has passed SQLCipher
 * integrity and foreign-key checks. The old wrapped-key metadata is stored
 * separately as authenticated ciphertext; the raw database key is never
 * persisted in the journal or rollback files.
 */
internal class SqlCipherSameDeviceRestoreCoordinator(
    context: Context,
    journalFileName: String = DEFAULT_JOURNAL_NAME,
    private val afterPhase: (SqlCipherSameDeviceRestoreJournal.Phase) -> Unit = {}
) {
    enum class RecoveryResult { NOTHING_TO_RECOVER, RESTORE_COMPLETED, RESTORE_ROLLED_BACK }

    private val appContext = context.applicationContext
    private val journalFile = File(appContext.noBackupFilesDir, journalFileName)
    private val journal = SqlCipherSameDeviceRestoreJournal(journalFile)

    init {
        require(journalFileName.isNotBlank() && File(journalFileName).name == journalFileName)
    }

    @Synchronized
    fun commit(
        stagingDatabase: File,
        liveDatabase: File,
        replacementKey: ByteArray,
        keyVault: DatabaseKeyVault,
        closeLiveDatabase: () -> Unit
    ) {
        require(replacementKey.size == DATABASE_KEY_BYTES)
        validateDatabasePair(stagingDatabase, liveDatabase)
        require(stagingDatabase.isFile && stagingDatabase.length() > 0L)
        require(liveDatabase.isFile && liveDatabase.length() > 0L) {
            "لا توجد قاعدة حالية صالحة لحفظها كنقطة تراجع"
        }

        val rollback = File(liveDatabase.parentFile, liveDatabase.name + ".sqlcipher-rollback")
        val oldKey = keyVault.getImportedDatabaseKeyForRestoreRecovery()
            ?: throw IllegalStateException("مفتاح قاعدة البيانات الحالية غير موجود")
        val transactionId = UUID.randomUUID().toString().replace("-", "")
        val keySnapshot = File(appContext.noBackupFilesDir, "tds-same-device-key-$transactionId.bin")
        val keySnapshotAtomic = AtomicFile(keySnapshot)
        try {
            check(!rollback.exists() && !File(rollback.path + "-wal").exists() &&
                !File(rollback.path + "-shm").exists() && !File(rollback.path + "-journal").exists()) {
                "توجد نقطة تراجع سابقة؛ يجب التعافي منها أولاً"
            }
            SqlCipherDatabaseMigrator.validateEncryptedDatabase(liveDatabase, oldKey)
            SqlCipherDatabaseMigrator.validateEncryptedDatabase(stagingDatabase, replacementKey)

            closeLiveDatabase()
            ensureClosedDatabaseHasNoPendingWal(liveDatabase)
            ensureClosedDatabaseHasNoPendingWal(stagingDatabase)

            writeAtomic(keySnapshotAtomic, keyVault.snapshotWrappedKeyMetadata())
            journal.begin(stagingDatabase, liveDatabase, rollback, keySnapshot)
            afterPhase(SqlCipherSameDeviceRestoreJournal.Phase.PREPARED)

            check(liveDatabase.renameTo(rollback)) { "تعذر حفظ قاعدة البيانات الحالية للتراجع" }
            journal.advance(
                SqlCipherSameDeviceRestoreJournal.Phase.PREPARED,
                SqlCipherSameDeviceRestoreJournal.Phase.OLD_DATABASE_BACKED_UP
            )
            afterPhase(SqlCipherSameDeviceRestoreJournal.Phase.OLD_DATABASE_BACKED_UP)

            keyVault.replaceDatabaseKeyForRestore(replacementKey, oldKey)
            journal.advance(
                SqlCipherSameDeviceRestoreJournal.Phase.OLD_DATABASE_BACKED_UP,
                SqlCipherSameDeviceRestoreJournal.Phase.KEY_REPLACED
            )
            afterPhase(SqlCipherSameDeviceRestoreJournal.Phase.KEY_REPLACED)

            check(stagingDatabase.renameTo(liveDatabase)) {
                "تم تبديل المفتاح لكن تعذر وضع القاعدة الجديدة؛ يمكن استكمال التراجع بأمان"
            }
            journal.advance(
                SqlCipherSameDeviceRestoreJournal.Phase.KEY_REPLACED,
                SqlCipherSameDeviceRestoreJournal.Phase.DATABASE_SWAPPED
            )
            afterPhase(SqlCipherSameDeviceRestoreJournal.Phase.DATABASE_SWAPPED)

            SqlCipherDatabaseMigrator.validateEncryptedDatabase(liveDatabase, replacementKey)
            journal.advance(
                SqlCipherSameDeviceRestoreJournal.Phase.DATABASE_SWAPPED,
                SqlCipherSameDeviceRestoreJournal.Phase.VERIFIED
            )
            afterPhase(SqlCipherSameDeviceRestoreJournal.Phase.VERIFIED)

            finalizeCommittedRestore(liveDatabase, rollback, keySnapshotAtomic, keySnapshot)
        } finally {
            oldKey.fill(0)
        }
    }

    /** Call during startup before opening AppDatabase whenever a journal exists. */
    @Synchronized
    fun recover(keyVault: DatabaseKeyVault): RecoveryResult {
        val record = journal.read() ?: return RecoveryResult.NOTHING_TO_RECOVER
        val directory = appContext.getDatabasePath(record.liveName).parentFile
            ?: throw IllegalStateException("مجلد قاعدة البيانات غير متاح")
        val staging = resolveDatabaseChild(directory, record.stagingName)
        val live = resolveDatabaseChild(directory, record.liveName)
        val rollback = resolveDatabaseChild(directory, record.rollbackName)
        val snapshot = resolvePrivateChild(record.keySnapshotName)
        validateDatabasePair(staging, live)
        require(rollback.canonicalFile.parentFile == live.canonicalFile.parentFile)

        val currentKey = keyVault.getImportedDatabaseKeyForRestoreRecovery()
            ?: throw IllegalStateException("مفتاح قاعدة البيانات مفقود؛ أوقف التعافي واحتفظ بالملفات")
        try {
            val isNewLiveValid = live.isFile && runCatching {
                SqlCipherDatabaseMigrator.validateEncryptedDatabase(live, currentKey)
            }.isSuccess

            // If the new file was swapped into place and validates with the current
            // key, complete the commit. A remaining staging file means rollback had
            // already restored the old live file before an interruption.
            if (record.phase.ordinal >= SqlCipherSameDeviceRestoreJournal.Phase.KEY_REPLACED.ordinal &&
                isNewLiveValid && !staging.exists()) {
                finalizeCommittedRestore(live, rollback, AtomicFile(snapshot), snapshot)
                return RecoveryResult.RESTORE_COMPLETED
            }

            // All other states roll back. Restore the old wrapped-key ciphertext
            // first, then recover the old database file and validate it with that key.
            require(snapshot.isFile) { "نسخة المفتاح القديمة مفقودة؛ لا يمكن التراجع بأمان" }
            AtomicFile(snapshot).openRead().use { keyVault.restoreWrappedKeyMetadata(it.readBytes()) }
            val oldKey = keyVault.getImportedDatabaseKeyForRestoreRecovery()
                ?: throw IllegalStateException("تعذر استعادة مفتاح قاعدة البيانات السابق")
            try {
                if (rollback.exists()) {
                    if (live.exists()) {
                        val liveIsOld = runCatching {
                            SqlCipherDatabaseMigrator.validateEncryptedDatabase(live, oldKey)
                        }.isSuccess
                        if (!liveIsOld) {
                            check(!staging.exists()) {
                                "ملفا الاستعادة الحالي والمؤقت موجودان؛ أُوقف التراجع دون حذف أي منهما"
                            }
                            check(live.renameTo(staging)) {
                                "تعذر عزل القاعدة الجديدة قبل استعادة القاعدة السابقة"
                            }
                        }
                    }
                    if (!live.exists()) {
                        check(rollback.renameTo(live)) { "تعذر إعادة قاعدة البيانات السابقة" }
                    }
                }
                check(live.isFile) { "قاعدة البيانات السابقة غير موجودة بعد التراجع" }
                SqlCipherDatabaseMigrator.validateEncryptedDatabase(live, oldKey)
            } finally {
                oldKey.fill(0)
            }
            if (staging.exists()) check(staging.delete()) { "تعذر تنظيف ملف التجهيز بعد التراجع" }
            check(!rollback.exists()) { "بقي ملف التراجع؛ لن يتم حذف سجل العملية" }
            AtomicFile(snapshot).delete()
            journal.clear()
            return RecoveryResult.RESTORE_ROLLED_BACK
        } finally {
            currentKey.fill(0)
        }
    }

    private fun finalizeCommittedRestore(
        live: File,
        rollback: File,
        snapshotAtomic: AtomicFile,
        snapshot: File
    ) {
        check(live.isFile && live.length() > 0L)
        if (rollback.exists()) check(rollback.delete()) {
            "تم اعتماد القاعدة الجديدة لكن تعذر تنظيف نسخة التراجع؛ سيُستكمل التنظيف لاحقاً"
        }
        File(rollback.path + "-wal").delete()
        File(rollback.path + "-shm").delete()
        File(rollback.path + "-journal").delete()
        snapshotAtomic.delete()
        if (snapshot.exists()) snapshot.delete()
        journal.clear()
    }

    private fun writeAtomic(file: AtomicFile, bytes: ByteArray) {
        var stream: FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
            file.finishWrite(stream)
            stream = null
        } catch (error: Exception) {
            stream?.let(file::failWrite)
            throw error
        } finally {
            bytes.fill(0)
        }
    }

    private fun ensureClosedDatabaseHasNoPendingWal(database: File) {
        val wal = File(database.path + "-wal")
        check(!wal.exists() || wal.length() == 0L) {
            "يوجد WAL غير مفرغ؛ أُوقفت الاستعادة لحماية المعاملات"
        }
        listOf(wal, File(database.path + "-shm"), File(database.path + "-journal")).forEach {
            if (it.exists()) check(it.delete()) { "تعذر تنظيف ملف جانبي بعد إغلاق قاعدة البيانات" }
        }
    }

    private fun validateDatabasePair(staging: File, live: File) {
        require(staging.canonicalFile.parentFile != null &&
            staging.canonicalFile.parentFile == live.canonicalFile.parentFile &&
            staging.canonicalFile != live.canonicalFile) {
            "يجب أن تكون القاعدة المؤقتة والحالية في مجلد قواعد البيانات نفسه"
        }
    }

    private fun resolveDatabaseChild(directory: File, name: String): File {
        require(name.isNotBlank() && File(name).name == name && name != "." && name != "..")
        return File(directory, name).also {
            require(it.canonicalFile.parentFile == directory.canonicalFile)
        }
    }

    private fun resolvePrivateChild(name: String): File {
        require(name.isNotBlank() && File(name).name == name && name != "." && name != "..")
        return File(appContext.noBackupFilesDir, name).also {
            require(it.canonicalFile.parentFile == appContext.noBackupFilesDir.canonicalFile)
        }
    }

    companion object {
        private const val DATABASE_KEY_BYTES = 32
        private const val DEFAULT_JOURNAL_NAME = "the_daily_shop_same_device_restore.journal"
    }
}
