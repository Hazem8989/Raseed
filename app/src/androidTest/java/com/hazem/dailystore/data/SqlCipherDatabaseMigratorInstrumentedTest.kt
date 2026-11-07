package com.hazem.dailystore.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase as AndroidSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqlCipherDatabaseMigratorInstrumentedTest {

    @Test
    fun exportPreservesSourceAndMigratesSchemaDataAndTriggers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val source = context.getDatabasePath("migration-plain-$suffix.db")
        val destination = context.getDatabasePath("migration-cipher-$suffix.db")
        val key = ByteArray(32).also(java.security.SecureRandom()::nextBytes)

        try {
            val directory = source.parentFile ?: error("Database directory unavailable")
            check(directory.isDirectory || directory.mkdirs())
            val plain = AndroidSQLiteDatabase.openOrCreateDatabase(source.absolutePath, null)
            try {
                plain.execSQL("PRAGMA foreign_keys=ON")
                plain.execSQL("CREATE TABLE products(id TEXT PRIMARY KEY, sold_qty INTEGER NOT NULL)")
                plain.execSQL("CREATE TABLE sales(id TEXT PRIMARY KEY, product_id TEXT NOT NULL REFERENCES products(id), qty INTEGER NOT NULL)")
                plain.execSQL("CREATE TRIGGER trg_sales_qty AFTER INSERT ON sales BEGIN UPDATE products SET sold_qty=sold_qty+NEW.qty WHERE id=NEW.product_id; END")
                plain.execSQL("INSERT INTO products VALUES('p1',0)")
                plain.version = 33
            } finally {
                plain.close()
            }
            val originalBytes = source.readBytes()

            SqlCipherDatabaseMigrator.exportPlainDatabase(source, destination, key)
            assertTrue("Migration must never modify or delete the plaintext source", source.isFile)
            assertTrue(originalBytes.contentEquals(source.readBytes()))
            SqlCipherDatabaseMigrator.validateEncryptedDatabase(destination, key, sourceVersion = 33)

            val passphrase = key.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
            val encrypted = CipherSQLiteDatabase.openDatabase(
                destination.absolutePath, passphrase, null,
                CipherSQLiteDatabase.OPEN_READWRITE, null, null
            )
            try {
                assertEquals(33, encrypted.version)
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
