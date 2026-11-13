package com.hazem.dailystore.data

import android.content.Context
import com.hazem.dailystore.data.AppSqliteDatabase as SQLiteDatabase
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Caller-owned verified SQLCipher staging result. Close it after the restore
 * coordinator has consumed the file; the in-memory key is zeroed on close.
 */
internal class StagedSqlCipherBackup(
    val databaseFile: File,
    val databaseKey: ByteArray
) : AutoCloseable {
    override fun close() {
        databaseKey.fill(0)
        databaseFile.delete()
    }
}

/**
 * Password-encrypted, authenticated, single-file database backup.
 *
 * The database is checkpointed and closed before it is copied so committed
 * WAL transactions are included. Restore decrypts and validates a staging
 * database before replacing the live file, and retains a rollback copy until
 * the restored database has opened and passed integrity checks.
 */
class BackupRestoreManager(private val context: Context) {
    /**
     * Prepares either a historical TDSBACK1 plaintext backup or a TDSBKUP1
     * SQLCipher backup for the encrypted restore transaction.
     *
     * This method deliberately stages and validates only. It never replaces
     * AppDatabase: production installation must be performed by the SQLCipher
     * restore coordinator after AppDatabase has been migrated.
     */
    internal fun stageBackupForSqlCipherRestore(
        actorUserId: String,
        password: CharArray,
        source: InputStream,
        keyVault: DatabaseKeyVault
    ): StagedSqlCipherBackup {
        requireBackupPermission(actorUserId)
        validatePassword(password)
        val directory = context.getDatabasePath(AppDatabase.DATABASE_NAME).parentFile
            ?: throw IllegalStateException("مجلد قاعدة البيانات غير متاح")
        check(directory.isDirectory || directory.mkdirs()) { "تعذر إنشاء مجلد قاعدة البيانات" }
        val suffix = System.nanoTime()
        val staging = File(directory, "${AppDatabase.DATABASE_NAME}.restore-sqlcipher-$suffix.db")
        val legacyPlaintext = File(context.cacheDir, "tds-legacy-restore-$suffix.db")
        val prefix = ByteArray(8)
        DataInputStream(source).readFully(prefix)
        val replay = java.io.SequenceInputStream(prefix.inputStream(), source)
        var key: ByteArray? = null
        try {
            when {
                prefix.contentEquals(SQLCIPHER_BACKUP_MAGIC) -> {
                    key = SqlCipherBackupRestorePipeline().stageRestore(replay, password, staging)
                }
                prefix.contentEquals(LEGACY_BACKUP_MAGIC) -> {
                    decryptToFile(replay, legacyPlaintext, password)
                    validateDatabase(legacyPlaintext)
                    key = keyVault.getOrCreateMigrationKey(legacyPlaintext)
                    SqlCipherBackupRestorePipeline().stageLegacyPlaintextBackup(
                        legacyPlaintext,
                        staging,
                        key
                    )
                }
                else -> throw IllegalArgumentException("صيغة النسخة الاحتياطية غير معروفة")
            }
            return StagedSqlCipherBackup(staging, key!!).also { key = null }
        } catch (error: Exception) {
            staging.delete()
            key?.fill(0)
            throw error
        } finally {
            legacyPlaintext.delete()
        }
    }
    fun createBackup(actorUserId: String, password: CharArray, destination: OutputStream, onProgress: ((String) -> Unit)? = null) {
        requireBackupPermission(actorUserId)
        validatePassword(password)
        if (AppDatabase.isPlaintextBackendForTests()) {
            createPlaintextFixtureBackup(actorUserId, password, destination, onProgress)
            return
        }
        onProgress?.invoke("إغلاق قاعدة البيانات وتجهيز لقطة آمنة...")
        synchronized(LOCK) {
            val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            val keyVault = DatabaseKeyVault(context)
            var databaseKey: ByteArray? = null
            try {
                databaseKey = SqlCipherDatabaseRuntime.getProductionDatabaseKey(keyVault, live)
                AppDatabase.getInstance(context).checkpointAndCloseForBackup()
                onProgress?.invoke("التحقق من قاعدة SQLCipher وتجهيز النسخة...")
                SqlCipherBackupRestorePipeline().createBackup(
                    live,
                    databaseKey,
                    password,
                    destination
                )
                destination.flush()
                onProgress?.invoke("اكتمل إنشاء النسخة الاحتياطية المشفّرة.")
            } finally {
                databaseKey?.fill(0)
            }
        }
    }

    fun restoreBackup(actorUserId: String, password: CharArray, source: InputStream, onProgress: ((String) -> Unit)? = null) {
        requireBackupPermission(actorUserId)
        validatePassword(password)
        if (AppDatabase.isPlaintextBackendForTests()) {
            restorePlaintextFixtureBackup(actorUserId, password, source, onProgress)
            return
        }
        onProgress?.invoke("فك تشفير النسخة والتحقق من سلامتها...")
        synchronized(LOCK) {
            val keyVault = DatabaseKeyVault(context)
            val staged = stageBackupForSqlCipherRestore(actorUserId, password, source, keyVault)
            try {
                val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
                onProgress?.invoke("حفظ القاعدة الحالية ونقطة التراجع...")
                SqlCipherSameDeviceRestoreCoordinator(context).commit(
                    stagingDatabase = staged.databaseFile,
                    liveDatabase = live,
                    replacementKey = staged.databaseKey,
                    keyVault = keyVault,
                    closeLiveDatabase = {
                        AppDatabase.getInstance(context).checkpointAndCloseForBackup()
                    }
                )

                // The old helper retains the old passphrase even after close.
                // Discard it so the next access constructs a helper with the
                // newly committed key from Android Keystore.
                AppDatabase.resetInstanceAfterEncryptedRestore()
                onProgress?.invoke("التحقق من فتح قاعدة البيانات المستعادة...")
                val restored = AppDatabase.getInstance(context).writableDatabase
                validateOpenDatabase(restored)
                onProgress?.invoke("اكتملت الاستعادة والتحقق من قاعدة البيانات.")
            } finally {
                staged.close()
            }
        }
    }

    /** Legacy TDSBACK1 fixture path retained only for Robolectric tests. */
    private fun createPlaintextFixtureBackup(
        actorUserId: String,
        password: CharArray,
        destination: OutputStream,
        onProgress: ((String) -> Unit)?
    ) {
        requireBackupPermission(actorUserId)
        synchronized(LOCK) {
            val snapshot = File(context.cacheDir, "tds-test-backup-snapshot-${System.nanoTime()}.db")
            val encrypted = File(context.cacheDir, "tds-test-backup-encrypted-${System.nanoTime()}.bin")
            try {
                AppDatabase.getInstance(context).checkpointAndCloseForBackup()
                val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
                check(live.isFile && live.length() > 0L) { "قاعدة البيانات غير موجودة أو فارغة" }
                check(live.length() <= MAX_DATABASE_BYTES) { "حجم قاعدة البيانات يتجاوز الحد المدعوم للنسخ الاحتياطي" }
                onProgress?.invoke("نسخ قاعدة الاختبار إلى ملف مؤقت...")
                copyFile(live, snapshot)
                encryptSnapshot(snapshot, encrypted, password)
                FileInputStream(encrypted).use { it.copyTo(destination) }
                destination.flush()
            } finally {
                snapshot.delete()
                encrypted.delete()
            }
        }
    }

    /** Legacy TDSBACK1 restore fixture; production restores use SQLCipher coordinator. */
    private fun restorePlaintextFixtureBackup(
        actorUserId: String,
        password: CharArray,
        source: InputStream,
        onProgress: ((String) -> Unit)?
    ) {
        requireBackupPermission(actorUserId)
        synchronized(LOCK) {
            val staged = File(context.getDatabasePath(AppDatabase.DATABASE_NAME).parentFile,
                "the_daily_shop.test-restore-${System.nanoTime()}.db")
            val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            val rollback = File(live.parentFile, "${live.name}.restore-rollback")
            var liveWasMoved = false
            try {
                decryptToFile(source, staged, password)
                validateDatabase(staged)
                AppDatabase.getInstance(context).checkpointAndCloseForBackup()
                deleteSidecars(live)
                if (rollback.exists() && !rollback.delete()) {
                    throw IllegalStateException("تعذر إزالة ملف الاستعادة المؤقت السابق")
                }
                if (live.exists()) {
                    check(live.renameTo(rollback)) { "تعذر حفظ قاعدة الاختبار الحالية للتراجع" }
                    liveWasMoved = true
                }
                check(staged.renameTo(live)) { "تعذر وضع قاعدة الاختبار المستعادة" }
                try {
                    val restored = AppDatabase.getInstance(context).writableDatabase
                    validateOpenDatabase(restored)
                    rollback.delete()
                } catch (failure: Exception) {
                    AppDatabase.getInstance(context).close()
                    live.delete()
                    deleteSidecars(live)
                    if (liveWasMoved && rollback.exists()) {
                        check(rollback.renameTo(live)) { "تعذر استعادة قاعدة الاختبار السابقة" }
                    }
                    AppDatabase.getInstance(context).writableDatabase
                    throw IllegalStateException("فشلت استعادة قاعدة الاختبار؛ أُعيدت القاعدة السابقة", failure)
                }
            } catch (error: Exception) {
                if (liveWasMoved && rollback.exists() && !live.exists()) {
                    check(rollback.renameTo(live)) { "تعذر التراجع عن استعادة قاعدة الاختبار" }
                }
                throw error
            } finally {
                staged.delete()
                if (rollback.exists() && live.exists()) rollback.delete()
            }
            onProgress?.invoke("اكتملت استعادة قاعدة الاختبار.")
        }
    }

    /** Recovers a restore interrupted after the previous live database was moved aside. */
    fun recoverInterruptedRestore() {
        synchronized(LOCK) {
            val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
            val rollback = File(live.parentFile, "${live.name}.restore-rollback")
            if (rollback.exists()) {
                AppDatabase.getInstance(context).close()
                val liveIsValid = live.isFile && runCatching {
                    validateDatabase(live)
                    val restored = AppDatabase.getInstance(context).writableDatabase
                    validateOpenDatabase(restored)
                }.isSuccess
                if (liveIsValid) {
                    check(rollback.delete() || !rollback.exists()) {
                        "تعذر تنظيف نسخة التراجع بعد التحقق من قاعدة البيانات الحالية"
                    }
                } else {
                    AppDatabase.getInstance(context).close()
                    val rollbackIsValid = runCatching { validateDatabase(rollback) }.isSuccess
                    check(rollbackIsValid) {
                        "تعذر التعافي: قاعدة البيانات الحالية ونسخة التراجع غير سليمتين؛ احتفظ بالملفات ولا تحذفها"
                    }
                    deleteSidecars(live)
                    if (live.exists()) check(live.delete()) { "تعذر إزالة قاعدة البيانات غير السليمة" }
                    check(rollback.renameTo(live)) { "تعذر استعادة قاعدة البيانات السابقة بعد انقطاع التطبيق" }
                }
            }
            val stagePrefix = live.name.removeSuffix(".db") + ".restore-"
            live.parentFile?.listFiles()?.filter { it.name.startsWith(stagePrefix) && it.extension == "db" }
                ?.forEach { it.delete() }
        }
    }

    private fun requireBackupPermission(actorUserId: String) {
        PermissionPolicy.require(
            AppDatabase.getInstance(context).readableDatabase,
            actorUserId,
            PermissionPolicy.BACKUP_MANAGE
        )
    }

    private fun encryptSnapshot(snapshot: File, target: File, password: CharArray) {
        val salt = ByteArray(SALT_BYTES).also(SECURE_RANDOM::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SECURE_RANDOM::nextBytes)
        val headerBytes = buildHeader(salt, iv)
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv)
        cipher.updateAAD(headerBytes)
        FileOutputStream(target).use { fileOut ->
            val buffered = BufferedOutputStream(fileOut)
            buffered.write(headerBytes)
            val nonClosing = object : FilterOutputStream(buffered) {
                override fun close() {
                    flush()
                }
            }
            CipherOutputStream(nonClosing, cipher).use { encryptedOut ->
                FileInputStream(snapshot).use { input -> input.copyTo(encryptedOut) }
            }
            buffered.flush()
            fileOut.fd.sync()
        }
    }

    private fun decryptToFile(source: InputStream, target: File, password: CharArray) {
        val data = DataInputStream(BufferedInputStream(source))
        val header = ByteArray(HEADER_BYTES)
        data.readFully(header)
        val parsed = parseHeader(header)
        val cipher = cipher(Cipher.DECRYPT_MODE, password, parsed.salt, parsed.iv, parsed.iterations)
        cipher.updateAAD(header)
        try {
            FileOutputStream(target).use { fileOut ->
                val output = BufferedOutputStream(fileOut)
                CipherInputStream(data, cipher).use { decrypted ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = decrypted.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_DATABASE_BYTES) { "حجم قاعدة البيانات أكبر من الحد المسموح" }
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
                fileOut.fd.sync()
            }
        } catch (error: Exception) {
            target.delete()
            throw IllegalArgumentException(
                "تعذر فك تشفير النسخة. تحقق من كلمة المرور وسلامة الملف",
                error
            )
        }
        require(target.isFile && target.length() > 0L) { "ملف النسخة الاحتياطية فارغ" }
    }

    private fun buildHeader(salt: ByteArray, iv: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeInt(PBKDF2_ITERATIONS)
                out.write(salt)
                out.write(iv)
            }
        }.toByteArray()

    private data class ParsedHeader(val salt: ByteArray, val iv: ByteArray, val iterations: Int)

    private fun parseHeader(header: ByteArray): ParsedHeader {
        val input = DataInputStream(header.inputStream())
        val magic = ByteArray(MAGIC.size).also(input::readFully)
        require(magic.contentEquals(MAGIC)) { "الملف ليس نسخة احتياطية معروفة من The Daily Shop" }
        require(input.readInt() == FORMAT_VERSION) { "إصدار ملف النسخة الاحتياطية غير مدعوم" }
        val iterations = input.readInt()
        require(iterations in MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS) {
            "إعدادات حماية النسخة غير صالحة"
        }
        val salt = ByteArray(SALT_BYTES).also(input::readFully)
        val iv = ByteArray(IV_BYTES).also(input::readFully)
        return ParsedHeader(salt, iv, iterations)
    }

    private fun cipher(
        mode: Int, password: CharArray, salt: ByteArray, iv: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS
    ): Cipher {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        val keyBytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun validateDatabase(file: File) {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val version = db.version
            require(version in 1..AppDatabase.DATABASE_VERSION) {
                "إصدار قاعدة البيانات داخل النسخة غير مدعوم"
            }
            validateOpenDatabase(db)
        } finally {
            db.close()
        }
    }

    private fun validateOpenDatabase(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            require(cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                "فشل فحص سلامة قاعدة البيانات"
            }
        }
        db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            require(!cursor.moveToFirst()) { "تحتوي قاعدة البيانات على علاقات غير سليمة" }
        }
        listOf("users", "products", "app_settings", "sales_invoices").forEach { table ->
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table)
            ).use { cursor ->
                require(cursor.moveToFirst()) { "النسخة لا تحتوي على جدول أساسي: $table" }
            }
        }
    }

    private fun validatePassword(password: CharArray) {
        require(password.size >= MIN_PASSWORD_LENGTH) {
            "كلمة مرور النسخة الاحتياطية يجب ألا تقل عن $MIN_PASSWORD_LENGTH أحرف"
        }
    }

    private fun deleteSidecars(database: File) {
        File(database.parentFile, database.name + "-wal").delete()
        File(database.parentFile, database.name + "-shm").delete()
        File(database.parentFile, database.name + "-journal").delete()
    }

    private fun copyFile(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    companion object {
        private val LEGACY_BACKUP_MAGIC = byteArrayOf(0x54, 0x44, 0x53, 0x42, 0x41, 0x43, 0x4B, 0x31)
        private val SQLCIPHER_BACKUP_MAGIC = byteArrayOf(0x54, 0x44, 0x53, 0x42, 0x4B, 0x55, 0x50, 0x31)
        private val LOCK = Any()
        private val SECURE_RANDOM = SecureRandom()
        private val MAGIC = byteArrayOf(0x54, 0x44, 0x53, 0x42, 0x41, 0x43, 0x4B, 0x31) // TDSBACK1
        private const val FORMAT_VERSION = 1
        private const val PBKDF2_ITERATIONS = 310_000
        private const val MIN_PBKDF2_ITERATIONS = 200_000
        private const val MAX_PBKDF2_ITERATIONS = 500_000
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val HEADER_BYTES = 8 + 4 + 4 + SALT_BYTES + IV_BYTES
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val MIN_PASSWORD_LENGTH = 10
        private const val MAX_DATABASE_BYTES = 512L * 1024L * 1024L
    }
}
