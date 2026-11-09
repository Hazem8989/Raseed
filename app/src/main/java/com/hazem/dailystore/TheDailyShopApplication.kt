package com.hazem.dailystore

import android.app.Application
import com.hazem.dailystore.data.BackupRestoreManager
import com.hazem.dailystore.data.DatabaseKeyVault
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
        val sameDeviceJournal = File(
            noBackupFilesDir,
            SAME_DEVICE_RESTORE_JOURNAL
        )
        val sameDeviceJournalBackup = File(sameDeviceJournal.path + ".bak")
        if (sameDeviceJournal.exists() || sameDeviceJournalBackup.exists()) {
            val vault = DatabaseKeyVault(applicationContext)
            SqlCipherSameDeviceRestoreCoordinator(applicationContext)
                .recover(vault)
        }

        // Retain the legacy SQLite recovery path until the production database
        // has been migrated. It runs only after SQLCipher restore recovery.
        BackupRestoreManager(applicationContext).recoverInterruptedRestore()
    }

    private companion object {
        const val SAME_DEVICE_RESTORE_JOURNAL =
            "the_daily_shop_same_device_restore.journal"
    }
}
