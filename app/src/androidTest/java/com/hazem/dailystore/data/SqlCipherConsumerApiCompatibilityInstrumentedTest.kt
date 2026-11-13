package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteStatement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Compatibility gate for the Android SQLite API surface used by repositories.
 *
 * This deliberately exercises SQLCipher's Android-compatible Cursor,
 * ContentValues, transaction, statement, and CRUD APIs before the shared
 * AppSqliteDatabase seam is switched away from framework SQLite.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherConsumerApiCompatibilityInstrumentedTest {

    @Test
    fun sqlCipherSupportsRepositoryDatabaseOperationsWithoutChangingDataSemantics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val name = "sqlcipher-consumer-api-$suffix.db"
        val file = context.getDatabasePath(name)
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val helper = CompatibilityHelper(context, name, key)

        try {
            val db = helper.writableDatabase
            db.setForeignKeyConstraintsEnabled(true)

            val values = ContentValues().apply {
                put("external_id", "probe-1")
                put("amount_minor", 1250L)
            }
            assertTrue(db.insertOrThrow("consumer_probe", null, values) > 0L)

            db.beginTransaction()
            try {
                val statement: SQLiteStatement =
                    db.compileStatement("UPDATE consumer_probe SET amount_minor=? WHERE external_id=?")
                statement.bindLong(1, 1750L)
                statement.bindString(2, "probe-1")
                assertEquals(1, statement.executeUpdateDelete())
                statement.close()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            val cursor: Cursor = db.rawQuery(
                "SELECT external_id, amount_minor FROM consumer_probe WHERE external_id=?",
                arrayOf("probe-1")
            )
            cursor.use {
                assertTrue(it.moveToFirst())
                assertEquals("probe-1", it.getString(0))
                assertEquals(1750L, it.getLong(1))
                assertFalse(it.moveToNext())
            }

            val updateValues = ContentValues().apply { put("amount_minor", 2000L) }
            assertEquals(
                1,
                db.update("consumer_probe", updateValues, "external_id=?", arrayOf("probe-1"))
            )
            assertEquals(
                1,
                db.delete("consumer_probe", "external_id=?", arrayOf("probe-1"))
            )
        } finally {
            helper.close()
            key.fill(0)
            context.deleteDatabase(name)
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }

    private class CompatibilityHelper(
        context: Context,
        name: String,
        key: ByteArray
    ) : SqlCipherOpenHelperBase(context, name, key, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE consumer_probe (
                    external_id TEXT PRIMARY KEY NOT NULL,
                    amount_minor INTEGER NOT NULL CHECK(amount_minor >= 0)
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
