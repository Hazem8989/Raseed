package com.hazem.dailystore.data

import java.io.File

/**
 * Coordinates the durable, restart-safe portion of SQLCipher restore on a new
 * device. This remains an isolated primitive until AppDatabase and every
 * production database consumer have migrated to SQLCipher.
 *
 * The journal contains filenames and a phase only. The database key remains
 * in the device KeyVault and is never written to the journal.
 */
internal class SqlCipherRestoreCoordinator(
    private val journal: SqlCipherRestoreJournal
) {
    enum class RecoveryResult {
        NOTHING_TO_RECOVER,
        WAITING_FOR_KEY,
        RESTORE_COMPLETED
    }

    fun commitToEmptyDevice(
        stagingFile: File,
        destinationDatabase: File,
        databaseKey: ByteArray,
        keyVault: DatabaseKeyVault
    ) {
        require(databaseKey.size == DATABASE_KEY_BYTES) { "SQLCipher key must be 256 bits" }
        validateSameDirectory(stagingFile, destinationDatabase)
        require(stagingFile.isFile && stagingFile.length() > 0L) {
            "ملف الاستعادة المؤقت غير موجود"
        }
        require(!destinationDatabase.exists()) {
            "جهاز الاستعادة يحتوي على قاعدة بيانات؛ رُفض الاستبدال"
        }
        requireNoSidecars(destinationDatabase)
        SqlCipherDatabaseMigrator.validateEncryptedDatabase(stagingFile, databaseKey)

        // Persist intent before importing the key or moving the database.
        journal.begin(stagingFile, destinationDatabase)
        keyVault.importDatabaseKey(databaseKey, databaseExists = false)
        journal.advance(
            SqlCipherRestoreJournal.Phase.STAGED,
            SqlCipherRestoreJournal.Phase.KEY_IMPORTED
        )

        check(stagingFile.renameTo(destinationDatabase)) {
            "تم حفظ المفتاح المحلي لكن تعذر نقل ملف الاستعادة؛ أعد المحاولة دون حذف ملف التجهيز"
        }
        SqlCipherDatabaseMigrator.validateEncryptedDatabase(destinationDatabase, databaseKey)
        journal.advance(
            SqlCipherRestoreJournal.Phase.KEY_IMPORTED,
            SqlCipherRestoreJournal.Phase.DATABASE_COMMITTED
        )
        journal.clear()
    }


    /**
     * Resumes a restore when the process died before the recovered key reached
     * the device vault. The user must reselect the same password-protected backup.
     * The encrypted staged DB is preserved and validated before it is committed.
     */
    fun resumeFromBackup(
        source: java.io.InputStream,
        password: CharArray,
        databaseDirectory: File,
        keyVault: DatabaseKeyVault
    ): RecoveryResult {
        val record = journal.read() ?: return RecoveryResult.NOTHING_TO_RECOVER
        val destination = resolveChild(databaseDirectory, record.destinationName)
        val recoveredKey = SqlCipherBackupContainer.recoverDatabaseKey(source, password)
        try {
            if (destination.exists()) {
                val localKey = keyVault.getImportedDatabaseKeyForRestoreRecovery()
                    ?: throw IllegalStateException("قاعدة الاستعادة موجودة لكن مفتاحها المحلي مفقود؛ أوقف العملية")
                try {
                    check(java.security.MessageDigest.isEqual(localKey, recoveredKey)) {
                        "مفتاح النسخة لا يطابق المفتاح المحلي؛ رُفض الاستبدال"
                    }
                } finally {
                    localKey.fill(0)
                }
            } else {
                keyVault.importDatabaseKey(recoveredKey, databaseExists = false)
            }
            return recover(databaseDirectory, keyVault)
        } finally {
            recoveredKey.fill(0)
        }
    }

    /**
     * Reconciles only files named by the journal. If the key was not imported
     * before interruption, returns WAITING_FOR_KEY and preserves every file.
     * It never creates a replacement key and never overwrites a destination.
     */
    fun recover(
        databaseDirectory: File,
        keyVault: DatabaseKeyVault
    ): RecoveryResult {
        val record = journal.read() ?: return RecoveryResult.NOTHING_TO_RECOVER
        require(databaseDirectory.isDirectory) { "Restore database directory is unavailable" }

        val staging = resolveChild(databaseDirectory, record.stagingName)
        val destination = resolveChild(databaseDirectory, record.destinationName)
        validateSameDirectory(staging, destination)

        val key = keyVault.getImportedDatabaseKeyForRestoreRecovery()
            ?: return RecoveryResult.WAITING_FOR_KEY

        try {
            when (record.phase) {
                SqlCipherRestoreJournal.Phase.STAGED -> {
                    // Covers a crash after key import but before phase advancement.
                    check(staging.isFile && staging.length() > 0L) {
                        "ملف التجهيز مفقود؛ احتفظ بالسجل والملفات ولا تنشئ قاعدة بديلة"
                    }
                    SqlCipherDatabaseMigrator.validateEncryptedDatabase(staging, key)
                    keyVault.importDatabaseKey(key, databaseExists = false)
                    journal.advance(
                        SqlCipherRestoreJournal.Phase.STAGED,
                        SqlCipherRestoreJournal.Phase.KEY_IMPORTED
                    )
                }
                SqlCipherRestoreJournal.Phase.KEY_IMPORTED -> Unit
                SqlCipherRestoreJournal.Phase.DATABASE_COMMITTED -> {
                    check(destination.isFile && destination.length() > 0L) {
                        "قاعدة الاستعادة المعتمدة مفقودة؛ احتفظ بالملفات ولا تحذفها"
                    }
                    SqlCipherDatabaseMigrator.validateEncryptedDatabase(destination, key)
                    if (staging.exists()) check(staging.delete()) {
                        "تعذر تنظيف ملف التجهيز بعد التحقق"
                    }
                    journal.clear()
                    return RecoveryResult.RESTORE_COMPLETED
                }
            }

            if (destination.exists()) {
                // A rename may have succeeded just before process death.
                SqlCipherDatabaseMigrator.validateEncryptedDatabase(destination, key)
            } else {
                check(staging.isFile && staging.length() > 0L) {
                    "ملف التجهيز مفقود؛ احتفظ بالسجل والملفات ولا تحذفها"
                }
                requireNoSidecars(destination)
                SqlCipherDatabaseMigrator.validateEncryptedDatabase(staging, key)
                check(staging.renameTo(destination)) {
                    "تعذر إكمال نقل قاعدة الاستعادة؛ أعد المحاولة"
                }
                SqlCipherDatabaseMigrator.validateEncryptedDatabase(destination, key)
            }

            journal.advance(
                SqlCipherRestoreJournal.Phase.KEY_IMPORTED,
                SqlCipherRestoreJournal.Phase.DATABASE_COMMITTED
            )
            if (staging.exists()) check(staging.delete()) {
                "تم التحقق من القاعدة لكن تعذر تنظيف ملف التجهيز"
            }
            journal.clear()
            return RecoveryResult.RESTORE_COMPLETED
        } finally {
            key.fill(0)
        }
    }

    private fun resolveChild(directory: File, name: String): File {
        require(name.isNotBlank() && File(name).name == name && name != "." && name != "..")
        val child = File(directory, name)
        require(child.canonicalFile.parentFile == directory.canonicalFile) {
            "Restore journal path escaped the database directory"
        }
        return child
    }

    private fun validateSameDirectory(first: File, second: File) {
        require(first.canonicalFile.parentFile != null &&
            first.canonicalFile.parentFile == second.canonicalFile.parentFile) {
            "يجب أن يكون ملف التجهيز والوجهة في المجلد نفسه"
        }
        require(first.canonicalFile != second.canonicalFile)
    }

    private fun requireNoSidecars(database: File) {
        require(
            !File(database.path + "-wal").exists() &&
                !File(database.path + "-shm").exists() &&
                !File(database.path + "-journal").exists()
        ) { "توجد ملفات جانبية لقاعدة سابقة؛ رُفضت الاستعادة الآمنة" }
    }

    companion object {
        private const val DATABASE_KEY_BYTES = 32
    }
}
