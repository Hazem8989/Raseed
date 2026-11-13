package com.hazem.dailystore

import android.app.Application
import com.hazem.dailystore.data.AppDatabase
import com.hazem.dailystore.data.BackupRestoreManager
import com.hazem.dailystore.data.DatabaseKeyVault
import com.hazem.dailystore.data.SqlCipherLegacyMigrationCoordinator
import com.hazem.dailystore.data.SqlCipherSameDeviceRestoreCoordinator
import java.io.File

/**
 * Process-wide database recovery barrier.
 *
 * Android creates the Application before activities, receivers and services.
 * Keep every database consumer behind this synchronous recovery sequence.
 * Any ambiguous journal, missing key, or failed validation aborts process
 * startup instead of allowing a database to be opened in an uncertain state.
 */
class TheDailyShopApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        recoverDatabasesBeforeAnyConsumer()
    }

    private fun recoverDatabasesBeforeAnyConsumer() {
        val migrationJournal = File(noBackupFilesDir, LEGACY_MIGRATION_JOURNAL)
        val migrationJournalBackup = File(migrationJournal.path + ".bak")
        val sameDeviceJournal = File(noBackupFilesDir, SAME_DEVICE_RESTORE_JOURNAL)
        val sameDeviceJournalBackup = File(sameDeviceJournal.path + ".bak")

        val hasMigrationRecovery = migrationJournal.exists() || migrationJournalBackup.exists()
        val hasSameDeviceRecovery = sameDeviceJournal.exists() || sameDeviceJournalBackup.exists()

        // These transactions replace the same live database and key. If both
        // journals exist, their ordering cannot be inferred safely: stop startup
        // and preserve all recovery files rather than guessing which transaction
        // owns the live database.
        check(!(hasMigrationRecovery && hasSameDeviceRecovery)) {
            "توجد عمليتا تعافٍ متداخلتان لقاعدة البيانات؛ أُوقف التشغيل لحماية الملفات"
        }

        val vault = DatabaseKeyVault(applicationContext)
        val liveDatabase = getDatabasePath(AppDatabase.DATABASE_NAME)

        // Recover an interrupted SQLite -> SQLCipher migration before any
        // database helper or repository is allowed to open the live file.
        SqlCipherLegacyMigrationCoordinator(applicationContext).recover(liveDatabase, vault)

        if (hasSameDeviceRecovery) {
            SqlCipherSameDeviceRestoreCoordinator(applicationContext).recover(vault)
        }

        // Recover interrupted legacy TDSBACK1 file swaps before the first
        // AppDatabase helper is created. The recovery routine validates either
        // a plaintext legacy file or an encrypted SQLCipher file without opening
        // a helper with a potentially stale key.
        BackupRestoreManager(applicationContext).recoverInterruptedRestore()
    }

    private companion object {
        const val LEGACY_MIGRATION_JOURNAL =
            "the_daily_shop_sqlcipher_migration.journal"
        const val SAME_DEVICE_RESTORE_JOURNAL =
            "the_daily_shop_same_device_restore.journal"
    }
}
