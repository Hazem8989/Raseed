package com.hazem.dailystore.data

import android.content.Context
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SqlCipherCompatibilityTest {

    companion object {
        private const val TEST_KEY = "compatibility-only-key-not-used-for-production"

        @BeforeClass
        @JvmStatic
        fun loadSqlCipherNativeLibrary() {
            System.loadLibrary("sqlcipher")
        }
    }

    @Test
    fun encryptedDatabaseCanBeReopenedAndRejectsWrongKeyWithoutDeletingSource() {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "sqlcipher-compat-${UUID.randomUUID()}.db"
        val databaseFile = context.getDatabasePath(databaseName)
        val helper = CompatibilityDatabase(context, databaseName, TEST_KEY)

        try {
            val database = helper.writableDatabase
            try {
                database.execSQL("INSERT INTO compatibility_probe(value) VALUES('persisted')")
            } finally {
                database.close()
            }
            helper.close()

            val header = ByteArray(16)
            databaseFile.inputStream().use { input ->
                var offset = 0
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    check(count > 0) { "Database file ended before its header was read" }
                    offset += count
                }
            }
            assertFalse(
                "SQLCipher database must not expose the standard SQLite file header",
                header.contentEquals("SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII))
            )

            val reopened = CompatibilityDatabase(context, databaseName, TEST_KEY)
            try {
                val cursor = reopened.readableDatabase.rawQuery(
                    "SELECT value FROM compatibility_probe",
                    null
                )
                try {
                    assertTrue(cursor.moveToFirst())
                    assertEquals("persisted", cursor.getString(0))
                } finally {
                    cursor.close()
                }
            } finally {
                reopened.close()
            }

            val encryptedBytesBeforeWrongKey = databaseFile.readBytes()
            val wrongKey = CompatibilityDatabase(context, databaseName, "wrong-test-key")
            try {
                assertThrows(RuntimeException::class.java) {
                    wrongKey.readableDatabase
                }
            } finally {
                wrongKey.close()
            }
            assertTrue("Wrong-key attempt must not delete the source database", databaseFile.exists())
            assertArrayEquals(encryptedBytesBeforeWrongKey, databaseFile.readBytes())
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
            File(databaseFile.path + "-wal").delete()
            File(databaseFile.path + "-shm").delete()
        }
    }

    private class CompatibilityDatabase(
        context: Context,
        name: String,
        passphrase: String
    ) : SQLiteOpenHelper(
        context,
        name,
        passphrase,
        null,
        1,
        0,
        DatabaseErrorHandler { _, exception ->
            throw IllegalStateException("Database could not be opened safely", exception)
        },
        null,
        false
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE compatibility_probe (value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
