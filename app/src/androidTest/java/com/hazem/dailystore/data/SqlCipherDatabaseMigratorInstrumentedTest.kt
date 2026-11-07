package com.hazem.dailystore.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase as AndroidSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherSQLiteDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqlCipherDatabaseMigratorInstrumentedTest {

    @Test
    fun exportPreservesTypedTableContentsSequenceAndSourceBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val source = context.getDatabasePath("migration-plain-$suffix.db")
        val destination = context.getDatabasePath("migration-cipher-$suffix.db")
        val key = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val binaryValue = byteArrayOf(0, 1, 2, 0x7f, 0xff.toByte())

        try {
            val directory = source.parentFile ?: error("Database directory unavailable")
            check(directory.isDirectory || directory.mkdirs())
            val plain = AndroidSQLiteDatabase.openOrCreateDatabase(source.absolutePath, null)
            try {
                plain.execSQL("PRAGMA foreign_keys=ON")
                plain.execSQL(
                    "CREATE TABLE products(" +
                        "id TEXT PRIMARY KEY, sold_qty INTEGER NOT NULL, description TEXT, " +
                        "unit_cost REAL, payload BLOB, optional_count INTEGER)"
                )
                plain.execSQL(
                    "CREATE TABLE sales(" +
                        "id TEXT PRIMARY KEY, product_id TEXT NOT NULL REFERENCES products(id), qty INTEGER NOT NULL)"
                )
                plain.execSQL(
                    "CREATE TABLE audit_events(id INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL)"
                )
                plain.execSQL(
                    "CREATE TRIGGER trg_sales_qty AFTER INSERT ON sales " +
                        "BEGIN UPDATE products SET sold_qty=sold_qty+NEW.qty WHERE id=NEW.product_id; END"
                )
                plain.execSQL(
                    "INSERT INTO products VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>("p1", 0, null, 12.375, binaryValue, null)
                )
                plain.execSQL(
                    "INSERT INTO products VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>("p2", 7, "second row", 0.125, byteArrayOf(9, 8, 7), 42)
                )
                plain.execSQL("INSERT INTO audit_events(payload) VALUES('first')")
                plain.execSQL("INSERT INTO audit_events(payload) VALUES('deleted-but-sequence-retained')")
                plain.execSQL("DELETE FROM audit_events WHERE id=2")
                plain.version = 33
            } finally {
                plain.close()
            }
            val originalBytes = source.readBytes()

            SqlCipherDatabaseMigrator.exportPlainDatabase(source, destination, key)
            assertTrue("Migration must never modify or delete the plaintext source", source.isFile)
            assertTrue("Source bytes must remain identical", originalBytes.contentEquals(source.readBytes()))
            SqlCipherDatabaseMigrator.validateEncryptedDatabase(destination, key, sourceVersion = 33)

            val passphrase = key.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
            val encrypted = CipherSQLiteDatabase.openDatabase(
                destination.absolutePath, passphrase, null,
                CipherSQLiteDatabase.OPEN_READWRITE, null, null
            )
            try {
                assertEquals(33, encrypted.version)
                encrypted.rawQuery(
                    "SELECT description,unit_cost,payload,optional_count FROM products WHERE id='p1'",
                    null
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertNull(cursor.getString(0))
                    assertEquals(12.375, cursor.getDouble(1), 0.0000001)
                    assertArrayEquals(binaryValue, cursor.getBlob(2))
                    assertTrue(cursor.isNull(3))
                }
                encrypted.rawQuery("SELECT COUNT(*) FROM sqlite_sequence WHERE name='audit_events' AND seq=2", null)
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(1, cursor.getInt(0))
                    }
                encrypted.execSQL("INSERT INTO audit_events(payload) VALUES('after migration')")
                encrypted.rawQuery("SELECT last_insert_rowid()", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(3, cursor.getInt(0))
                }
                encrypted.execSQL("INSERT INTO sales VALUES('s1','p1',4)")
                encrypted.rawQuery("SELECT sold_qty FROM products WHERE id='p1'", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(4, cursor.getInt(0))
                }
            } finally {
                encrypted.close()
                passphrase.fill(0)
            }
        } finally {
            key.fill(0)
            source.delete()
            destination.delete()
            listOf(source, destination).forEach {
                File(it.path + "-wal").delete()
                File(it.path + "-shm").delete()
                File(it.path + "-journal").delete()
            }
        }
    }
}
