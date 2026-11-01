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
    private val password = "ShopBackup#2026".toCharArray()

    @Before
    fun setUp() {
        AppDatabase.resetForTests(context)
        AppDatabase.getInstance(context).writableDatabase
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
        manager.createBackup(password, bytes)
        assertTrue(bytes.size() > 100)

        AppDatabase.getInstance(context).writableDatabase.execSQL(
            "UPDATE app_settings SET setting_value='changed-after-backup' WHERE setting_key='base_currency'"
        )
        manager.restoreBackup(password, ByteArrayInputStream(bytes.toByteArray()))

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
        manager.createBackup(password, bytes)

        var failed = false
        try {
            manager.restoreBackup("WrongPassword#1".toCharArray(), ByteArrayInputStream(bytes.toByteArray()))
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
        manager.createBackup(password, bytes)
        val damaged = bytes.toByteArray()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 0x40).toByte()

        var failed = false
        try {
            manager.restoreBackup(password, ByteArrayInputStream(damaged))
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
    fun rejectsShortPasswordsBeforeCreatingBackup() {
        var failed = false
        try {
            manager.createBackup("short".toCharArray(), ByteArrayOutputStream())
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
