package com.hazem.dailystore.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase as AndroidSQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/**
 * Isolated proof that SQLCipher's supported export path preserves the existing
 * SQLite schema and data. It does not open, rename, or modify AppDatabase.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationInstrumentedTest {

    @Test
    fun exportsPlainSqliteSchemaDataTriggersAndVersionIntoEncryptedDatabase() {
        System.loadLibrary("sqlcipher")
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val source = context.getDatabasePath("migration-source-$suffix.db")
        val encrypted = context.getDatabasePath("migration-encrypted-$suffix.db")
        val metadataName = "migration-key-$suffix.bin"
        val alias = "tds_migration_test_$suffix"
        val vault = DatabaseKeyVault(context, metadataName, alias)
        var key: ByteArray? = null
        var sourceCipherConnection: CipherSQLiteDatabase? = null
        var encryptedConnection: CipherSQLiteDatabase? = null

        try {
            val databaseDirectory = source.parentFile
                ?: error("Application database directory is unavailable")
            check(databaseDirectory.isDirectory || databaseDirectory.mkdirs()) {
                "Unable to create application database directory: ${databaseDirectory.absolutePath}"
            }
            check(databaseDirectory.canWrite()) {
                "Application database directory is not writable: ${databaseDirectory.absolutePath}"
            }
            check(!encrypted.exists()) {
                "Unique encrypted migration destination unexpectedly already exists"
            }
            // SQLCipher's Android VFS may fail to create a brand-new ATTACH target
            // even when its parent directory is writable. Pre-create the empty
            // file so ATTACH opens an existing app-private file instead.
            check(encrypted.createNewFile()) {
                "Unable to pre-create encrypted migration destination: ${encrypted.absolutePath}"
            }

            val plain = AndroidSQLiteDatabase.openOrCreateDatabase(source.absolutePath, null)
            try {
                plain.execSQL("PRAGMA foreign_keys=ON")
                plain.execSQL("CREATE TABLE app_settings(setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL)")
                plain.execSQL("CREATE TABLE products(id TEXT PRIMARY KEY, name TEXT NOT NULL, sold_qty INTEGER NOT NULL DEFAULT 0)")
                plain.execSQL("CREATE TABLE sales_invoices(id TEXT PRIMARY KEY, total_minor INTEGER NOT NULL)")
                plain.execSQL("""CREATE TABLE sales_items(
                    id TEXT PRIMARY KEY,
                    invoice_id TEXT NOT NULL REFERENCES sales_invoices(id),
                    product_id TEXT NOT NULL REFERENCES products(id),
                    qty INTEGER NOT NULL CHECK(qty > 0)
                )""")
                plain.execSQL("CREATE INDEX idx_sales_items_invoice ON sales_items(invoice_id)")
                plain.execSQL("""CREATE TRIGGER trg_sales_item_updates_product
                    AFTER INSERT ON sales_items
                    BEGIN
                      UPDATE products SET sold_qty=sold_qty+NEW.qty WHERE id=NEW.product_id;
                    END""")
                plain.execSQL("INSERT INTO app_settings VALUES('base_currency','USD')")
                plain.execSQL("INSERT INTO products(id,name,sold_qty) VALUES('p1','Test item',0)")
                plain.execSQL("INSERT INTO sales_invoices VALUES('i1',1250)")
                plain.execSQL("INSERT INTO sales_items VALUES('si1','i1','p1',3)")
                plain.version = 33
            } finally {
                plain.close()
            }

            key = vault.getOrCreateDatabaseKey(databaseExists = false)
            sourceCipherConnection = CipherSQLiteDatabase.openDatabase(
                source.absolutePath,
                null,
                CipherSQLiteDatabase.OPEN_READWRITE
            )
            val hexKey = key.joinToString("") { "%02x".format(it) }
            val escapedEncryptedPath = encrypted.absolutePath.replace("'", "''")
            // Use the same passphrase representation for ATTACH and reopen.
            // x'...' tells SQLCipher to treat the bytes as a raw cipher key,
            // while openDatabase(ByteArray) treats its bytes as a passphrase
            // and derives the cipher key. Mixing the two produces an unreadable
            // destination even though sqlcipher_export itself completes.
            val sqlCipherPassphrase = hexKey.toByteArray(Charsets.US_ASCII)
            val escapedPassphrase = hexKey.replace("'", "''")
            sourceCipherConnection.execSQL(
                "ATTACH DATABASE '$escapedEncryptedPath' AS encrypted KEY '$escapedPassphrase'"
            )
            sourceCipherConnection.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { cursor ->
                assertTrue("sqlcipher_export must return a result row", cursor.moveToFirst())
            }
            sourceCipherConnection.execSQL("PRAGMA encrypted.user_version=33")
            sourceCipherConnection.execSQL("DETACH DATABASE encrypted")
            sourceCipherConnection.close()
            sourceCipherConnection = null

            val header = ByteArray(16)
            encrypted.inputStream().use { input ->
                var offset = 0
                while (offset < header.size) {
                    val count = input.read(header, offset, header.size - offset)
                    check(count > 0) { "Encrypted database ended before header was read" }
                    offset += count
                }
            }
            assertFalse(
                "Exported database must not retain the plaintext SQLite header",
                header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
            )

            var plaintextRejected = false
            try {
                AndroidSQLiteDatabase.openDatabase(
                    encrypted.absolutePath,
                    null,
                    AndroidSQLiteDatabase.OPEN_READONLY
                ).use { database ->
                    // SELECT 1 is a constant expression and can succeed without
                    // reading the database file or parsing its schema. Force the
                    // SQLite engine to read sqlite_master so an encrypted header
                    // must be rejected by the plaintext SQLite pager.
                    database.rawQuery(
                        "SELECT name FROM sqlite_master LIMIT 1",
                        null
                    ).use { cursor ->
                        cursor.moveToFirst()
                    }
                }
            } catch (_: SQLiteException) {
                plaintextRejected = true
            }
            assertTrue(
                "A standard SQLite connection must fail when reading the encrypted database schema",
                plaintextRejected
            )

            encryptedConnection = CipherSQLiteDatabase.openDatabase(
                encrypted.absolutePath,
                sqlCipherPassphrase,
                null,
                CipherSQLiteDatabase.OPEN_READWRITE,
                null,
                null
            )
            assertEquals(33, encryptedConnection.version)
            encryptedConnection.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
            }
            encryptedConnection.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                assertFalse("Foreign-key check must be empty", cursor.moveToFirst())
            }
            encryptedConnection.rawQuery(
                "SELECT p.sold_qty, i.total_minor, s.setting_value FROM products p " +
                    "JOIN sales_invoices i ON i.id='i1' " +
                    "JOIN app_settings s ON s.setting_key='base_currency' WHERE p.id='p1'",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
                assertEquals(1250, cursor.getInt(1))
                assertEquals("USD", cursor.getString(2))
            }
            encryptedConnection.rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='trg_sales_item_updates_product'",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            encryptedConnection.rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_sales_items_invoice'",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            encryptedConnection.execSQL("INSERT INTO sales_items VALUES('si2','i1','p1',2)")
            encryptedConnection.rawQuery(
                "SELECT sold_qty FROM products WHERE id='p1'",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Copied trigger must still execute", 5, cursor.getInt(0))
            }
        } finally {
            sourceCipherConnection?.close()
            encryptedConnection?.close()
            key?.fill(0)
            source.delete()
            encrypted.delete()
            File(source.path + "-wal").delete()
            File(source.path + "-shm").delete()
            File(encrypted.path + "-wal").delete()
            File(encrypted.path + "-shm").delete()
            File(context.noBackupFilesDir, metadataName).delete()
            File(context.noBackupFilesDir, "$metadataName.bak").delete()
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            }
        }
    }
}
