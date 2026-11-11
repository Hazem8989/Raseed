package com.hazem.dailystore.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqlCipherOpenHelperBaseInstrumentedTest {

    @Test
    fun baseHelperUsesVaultKeyForEncryptedCreateReopenAndRejectsWrongKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val databaseName = "sqlcipher-base-$suffix.db"
        val vaultFile = "sqlcipher-base-key-$suffix.bin"
        val alias = "tds_sqlcipher_base_$suffix"
        val vault = DatabaseKeyVault(context, vaultFile, alias)
        val databaseFile = context.getDatabasePath(databaseName)
        var key = vault.getOrCreateDatabaseKey(databaseExists = false)

        try {
            val helper = ProbeDatabase(context, databaseName, key)
            try {
                helper.writableDatabase.use { database ->
                    database.execSQL("INSERT INTO base_probe(value) VALUES('persisted')")
                    database.rawQuery("PRAGMA foreign_keys", null).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(1, cursor.getInt(0))
                    }
                }
            } finally {
                helper.close()
            }

            val encryptedBytes = databaseFile.readBytes()
            assertFalse(
                "SQLCipher file must not expose a plaintext SQLite header",
                encryptedBytes.copyOfRange(0, 16).contentEquals(
                    "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
                )
            )

            val reopened = ProbeDatabase(context, databaseName, key)
            try {
                reopened.readableDatabase.rawQuery(
                    "SELECT value FROM base_probe", null
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("persisted", cursor.getString(0))
                    assertFalse(cursor.moveToNext())
                }
            } finally {
                reopened.close()
            }

            val wrongKey = ByteArray(32) { (it + 1).toByte() }
            val wrongKeyHelper = ProbeDatabase(context, databaseName, wrongKey)
            try {
                assertThrows(RuntimeException::class.java) {
                    wrongKeyHelper.readableDatabase
                }
            } finally {
                wrongKeyHelper.close()
                wrongKey.fill(0)
            }
            assertTrue("Wrong-key open must preserve the encrypted database", databaseFile.isFile)
            assertArrayEquals(encryptedBytes, databaseFile.readBytes())
        } finally {
            key.fill(0)
            listOf(
                databaseFile,
                File(databaseFile.path + "-wal"),
                File(databaseFile.path + "-shm"),
                File(databaseFile.path + "-journal"),
                File(context.noBackupFilesDir, vaultFile),
                File(context.noBackupFilesDir, "$vaultFile.bak")
            ).forEach { it.delete() }
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            }
        }
    }

    private class ProbeDatabase(
        context: Context,
        name: String,
        key: ByteArray
    ) : SqlCipherOpenHelperBase(context, name, key, 1) {

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE base_probe(id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
