package com.hazem.dailystore.data

import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BackupRestoreManagerTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var manager: BackupRestoreManager
    private lateinit var adminId: String
    private val password = "ShopBackup#2026".toCharArray()

    @Before
    fun setUp() {
        AppDatabase.resetForTests(context)
        AppDatabase.getInstance(context).writableDatabase
        val auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        val admin = auth.authenticate("admin", "1234")!!
        auth.changePassword(admin.id, "1234", "ManagerPass2026")
        adminId = admin.id
        manager = BackupRestoreManager(context)
    }

    @After
    fun tearDown() {
        AppDatabase.getInstance(context).close()
        password.fill('\u0000')
    }

    @Test
    fun encryptedBackupRestoresExactDatabaseAndPassesIntegrityChecks() {
        val db = AppDatabase.getInstance(context).writableDatabase
        db.execSQL("UPDATE app_settings SET setting_value='backup-marker' WHERE setting_key='base_currency'")

        val bytes = ByteArrayOutputStream()
        manager.createBackup(adminId, password, bytes)
        assertTrue(bytes.size() > 100)

        AppDatabase.getInstance(context).writableDatabase.execSQL(
            "UPDATE app_settings SET setting_value='changed-after-backup' WHERE setting_key='base_currency'"
        )
        manager.restoreBackup(adminId, password, ByteArrayInputStream(bytes.toByteArray()))

        val restored = AppDatabase.getInstance(context).readableDatabase
        restored.rawQuery(
            "SELECT setting_value FROM app_settings WHERE setting_key='base_currency'",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("backup-marker", cursor.getString(0))
        }
        restored.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
        }
    }

    @Test
    fun wrongPasswordDoesNotReplaceCurrentDatabase() {
        val db = AppDatabase.getInstance(context).writableDatabase
        db.execSQL("UPDATE app_settings SET setting_value='current-value' WHERE setting_key='base_currency'")
        val bytes = ByteArrayOutputStream()
        manager.createBackup(adminId, password, bytes)

        var failed = false
        try {
            manager.restoreBackup(adminId, "WrongPassword#1".toCharArray(), ByteArrayInputStream(bytes.toByteArray()))
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Wrong password must be rejected", failed)

        AppDatabase.getInstance(context).readableDatabase.rawQuery(
            "SELECT setting_value FROM app_settings WHERE setting_key='base_currency'",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("current-value", cursor.getString(0))
        }
    }

    @Test
    fun corruptedAuthenticationTagDoesNotReplaceCurrentDatabase() {
        val db = AppDatabase.getInstance(context).writableDatabase
        db.execSQL("UPDATE app_settings SET setting_value='safe-current' WHERE setting_key='base_currency'")
        val bytes = ByteArrayOutputStream()
        manager.createBackup(adminId, password, bytes)
        val damaged = bytes.toByteArray()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 0x40).toByte()

        var failed = false
        try {
            manager.restoreBackup(adminId, password, ByteArrayInputStream(damaged))
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Modified encrypted content must be rejected", failed)

        AppDatabase.getInstance(context).readableDatabase.rawQuery(
            "SELECT setting_value FROM app_settings WHERE setting_key='base_currency'",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("safe-current", cursor.getString(0))
        }
    }

    @Test
    fun cashierCannotCreateOrRestoreBackupsEvenIfItCallsTheRepositoryDirectly() {
        val auth = AuthRepository(context)
        val cashierId = auth.createUser("ADMIN", adminId,
            "cashierbackup", "موظف نسخ", "Cashier5678", "CASHIER")
        auth.changePassword(cashierId, "Cashier5678", "CashierPass2026")
        val bytes = ByteArrayOutputStream()
        try {
            manager.createBackup(cashierId, password, bytes)
            throw AssertionError("Cashier backup must be denied")
        } catch (_: IllegalArgumentException) { }
        val validBackup = ByteArrayOutputStream()
        manager.createBackup(adminId, password, validBackup)
        try {
            manager.restoreBackup(cashierId, password, ByteArrayInputStream(validBackup.toByteArray()))
            throw AssertionError("Cashier restore must be denied")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun recoversPreviousDatabaseWhenRestoreWasInterruptedAfterMovingTheLiveFile() {
        val db = AppDatabase.getInstance(context).writableDatabase
        db.execSQL("UPDATE app_settings SET setting_value='rollback-marker' WHERE setting_key='base_currency'")
        AppDatabase.getInstance(context).close()
        val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        val rollback = java.io.File(live.parentFile, live.name + ".restore-rollback")
        assertTrue(live.renameTo(rollback))
        val staleStage = java.io.File(live.parentFile, "the_daily_shop.restore-stale.db")
        staleStage.writeText("partial restore")

        manager.recoverInterruptedRestore()

        assertTrue(live.isFile)
        assertTrue(!rollback.exists())
        assertTrue(!staleStage.exists())
        AppDatabase.getInstance(context).readableDatabase.rawQuery(
            "SELECT setting_value FROM app_settings WHERE setting_key='base_currency'", null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("rollback-marker", cursor.getString(0))
        }
    }

    @Test
    fun recoversValidRollbackWhenTheLiveDatabaseIsCorrupted() {
        val db = AppDatabase.getInstance(context).writableDatabase
        db.execSQL("UPDATE app_settings SET setting_value='known-good' WHERE setting_key='base_currency'")
        AppDatabase.getInstance(context).close()
        val live = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        val rollback = java.io.File(live.parentFile, live.name + ".restore-rollback")
        live.copyTo(rollback, overwrite = true)
        live.writeText("corrupted live database")

        manager.recoverInterruptedRestore()

        assertTrue(live.isFile)
        assertTrue(!rollback.exists())
        AppDatabase.getInstance(context).readableDatabase.rawQuery(
            "SELECT setting_value FROM app_settings WHERE setting_key='base_currency'", null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("known-good", cursor.getString(0))
        }
    }

    @Test
    fun rejectsShortPasswordsBeforeCreatingBackup() {
        var failed = false
        try {
            manager.createBackup(adminId, "short".toCharArray(), ByteArrayOutputStream())
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
