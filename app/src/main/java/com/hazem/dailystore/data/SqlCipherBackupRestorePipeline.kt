package com.hazem.dailystore.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * SQLCipher-only backup pipeline, intentionally isolated from AppDatabase until
 * every production consumer has moved to SQLCipher.
 *
 * Backup creation requires a closed database and a completed WAL checkpoint.
 * Restore is restricted to an empty destination (new-device/recovery flow); it
 * validates the staged ciphertext before the key is imported or the file is moved.
 * Existing-device replacement remains the responsibility of the coordinated
 * production migration/restore transaction, not this primitive.
 */
internal class SqlCipherBackupRestorePipeline {
    fun createBackup(
        closedDatabase: File,
        databaseKey: ByteArray,
        password: CharArray,
        destination: OutputStream
    ) {
        require(closedDatabase.isFile && closedDatabase.length() > 0L) {
            "ملف قاعدة SQLCipher غير موجود أو فارغ"
        }
        require(closedDatabase.length() <= MAX_DATABASE_BYTES) {
            "حجم قاعدة البيانات يتجاوز الحد المدعوم"
        }
        val wal = File(closedDatabase.path + "-wal")
        require(!wal.exists() || wal.length() == 0L) {
            "يجب إغلاق قاعدة البيانات وإتمام WAL checkpoint قبل النسخ"
        }
        SqlCipherDatabaseMigrator.validateEncryptedDatabase(closedDatabase, databaseKey)
        SqlCipherBackupContainer.write(closedDatabase, databaseKey, password, destination)
    }

    /**
     * Extracts to a caller-owned staging file and validates it with SQLCipher.
     * The returned key belongs to the caller and must be zeroed after commit.
     */
    fun stageRestore(
        source: InputStream,
        password: CharArray,
        stagingFile: File
    ): ByteArray {
        require(!stagingFile.exists() || stagingFile.length() == 0L) {
            "ملف التجهيز موجود مسبقاً؛ رُفضت الكتابة فوقه"
        }
        val parent = stagingFile.parentFile
            ?: throw IllegalArgumentException("Restore staging file has no parent directory")
        check(parent.isDirectory || parent.mkdirs()) { "تعذر إنشاء مجلد الاستعادة المؤقت" }
        var key: ByteArray? = null
        try {
            key = SqlCipherBackupContainer.extract(source, stagingFile, password)
            SqlCipherDatabaseMigrator.validateEncryptedDatabase(stagingFile, key)
            return key.also { key = null }
        } catch (error: Exception) {
            stagingFile.delete()
            key?.fill(0)
            throw IllegalArgumentException(
                "فشل التحقق من النسخة المشفرة؛ لم يتم لمس قاعدة البيانات الحالية",
                error
            )
        }
    }

    /**
     * Commits only to a genuinely empty destination. Import is idempotent for
     * the same key, so a process interruption after key import but before rename
     * can safely retry without replacing a different device key.
     */
    fun commitRestoreToEmptyDevice(
        stagingFile: File,
        destinationDatabase: File,
        databaseKey: ByteArray,
        keyVault: DatabaseKeyVault
    ) {
        require(stagingFile.isFile && stagingFile.length() > 0L) {
            "ملف الاستعادة المؤقت غير موجود"
        }
        require(databaseKey.size == DATABASE_KEY_BYTES) { "SQLCipher key must be 256 bits" }
        require(!destinationDatabase.exists()) {
            "جهاز الاستعادة يحتوي على قاعدة بيانات؛ رُفض الاستبدال"
        }
        require(
            !File(destinationDatabase.path + "-wal").exists() &&
                !File(destinationDatabase.path + "-shm").exists() &&
                !File(destinationDatabase.path + "-journal").exists()
        ) { "توجد ملفات جانبية لقاعدة سابقة؛ رُفضت الاستعادة الآمنة" }
        val stagingParent = stagingFile.canonicalFile.parentFile
        val destinationParent = destinationDatabase.canonicalFile.parentFile
        require(stagingParent == destinationParent) {
            "يجب أن يكون ملف التجهيز والوجهة في المجلد نفسه لضمان النقل الذري"
        }
        SqlCipherDatabaseMigrator.validateEncryptedDatabase(stagingFile, databaseKey)

        // Import never overwrites an existing key. Repeating the same import is safe.
        keyVault.importDatabaseKey(databaseKey, databaseExists = false)
        check(stagingFile.renameTo(destinationDatabase)) {
            "تم حفظ المفتاح المحلي لكن تعذر نقل ملف الاستعادة؛ أعد المحاولة دون حذف ملف التجهيز"
        }
        check(!File(destinationDatabase.path + "-wal").exists()) {
            "ظهر ملف WAL غير متوقع بعد نقل قاعدة البيانات"
        }
    }

    companion object {
        private const val DATABASE_KEY_BYTES = 32
        private const val MAX_DATABASE_BYTES = 512L * 1024L * 1024L
    }
}
