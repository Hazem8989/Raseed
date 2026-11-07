package com.hazem.dailystore.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID

/**
 * End-to-end backup/restore proof using a real SQLCipher database and a fresh
 * DatabaseKeyVault, simulating a different Android device. This does not modify
 * AppDatabase or the production backup flow.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherBackupRestoreInstrumentedTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadSqlCipherNativeLibrary() {
            System.loadLibrary("sqlcipher")
        }
    }

    @Test
    fun encryptedDatabaseBackupRestoresOnFreshDeviceVaultWithAllDataIntact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val sourceName = "backup-source-$suffix.db"
        val stagedName = "backup-stage-$suffix.db"
        val restoredName = "backup-restored-$suffix.db"
        val source = context.getDatabasePath(sourceName)
        val staged = context.getDatabasePath(stagedName)
        val restored = context.getDatabasePath(restoredName)
        val sourceMetadata = "backup-source-key-$suffix.bin"
        val restoredMetadata = "backup-restored-key-$suffix.bin"
        val sourceAlias = "tds_backup_source_$suffix"
        val restoredAlias = "tds_backup_restore_$suffix"
        val sourceVault = DatabaseKeyVault(context, sourceMetadata, sourceAlias)
        val restoredVault = DatabaseKeyVault(context, restoredMetadata, restoredAlias)
        val password = "Cross-Device-Recovery-2026".toCharArray()
        var sourceKey: ByteArray? = null
        var recoveredKey: ByteArray? = null
        var sourceDb: SQLiteDatabase? = null
        var stagedDb: SQLiteDatabase? = null
        var restoredDb: SQLiteDatabase? = null

        try {
            check(!source.exists() && !staged.exists() && !restored.exists())
            sourceKey = sourceVault.getOrCreateDatabaseKey(databaseExists = false)
            sourceDb = openEncrypted(source, sourceKey)
            sourceDb.execSQL("PRAGMA foreign_keys=ON")
            sourceDb.execSQL("CREATE TABLE products(id TEXT PRIMARY KEY, name TEXT NOT NULL, qty INTEGER NOT NULL)")
            sourceDb.execSQL("CREATE TABLE sales_invoices(id TEXT PRIMARY KEY, total_minor INTEGER NOT NULL)")
            sourceDb.execSQL("""CREATE TABLE sale_items(
                id TEXT PRIMARY KEY,
                invoice_id TEXT NOT NULL REFERENCES sales_invoices(id),
                product_id TEXT NOT NULL REFERENCES products(id),
                quantity INTEGER NOT NULL CHECK(quantity > 0)
            )""")
            sourceDb.execSQL("CREATE INDEX idx_sale_items_invoice ON sale_items(invoice_id)")
            sourceDb.execSQL("""CREATE TRIGGER trg_sale_item_decrements_stock
                AFTER INSERT ON sale_items
                BEGIN
                    UPDATE products SET qty=qty-NEW.quantity WHERE id=NEW.product_id;
                END""")
            sourceDb.execSQL("INSERT INTO products VALUES('p-1','Test product',20)")
            sourceDb.execSQL("INSERT INTO sales_invoices VALUES('i-1',7250)")
            sourceDb.execSQL("INSERT INTO sale_items VALUES('si-1','i-1','p-1',3)")
            sourceDb.version = 33
            sourceDb.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            sourceDb.close()
            sourceDb = null

            val container = ByteArrayOutputStream()
            SqlCipherBackupContainer.write(source, sourceKey, password, container)

            recoveredKey = SqlCipherBackupContainer.extract(
                ByteArrayInputStream(container.toByteArray()),
                staged,
                password
            )
            assertTrue(staged.isFile)
            assertFalse(restored.exists())

            // Validate the extracted payload with SQLCipher before importing its key.
            stagedDb = openEncrypted(staged, recoveredKey)
            assertEquals(33, stagedDb.version)
            assertIntegrity(stagedDb)
            stagedDb.rawQuery(
                "SELECT p.qty, i.total_minor FROM products p JOIN sales_invoices i ON i.id='i-1' WHERE p.id='p-1'",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(17, cursor.getInt(0))
                assertEquals(7250, cursor.getInt(1))
            }
            stagedDb.rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='trg_sale_item_decrements_stock'",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            stagedDb.close()
            stagedDb = null

            // The destination vault has a distinct alias and no source-device metadata.
            restoredVault.importDatabaseKey(recoveredKey, databaseExists = false)
            check(staged.renameTo(restored)) { "Could not atomically place validated restore file" }

            val importedKey = restoredVault.getOrCreateDatabaseKey(databaseExists = true)
            try {
                assertArrayEquals(recoveredKey, importedKey)
                restoredDb = openEncrypted(restored, importedKey)
                assertEquals(33, restoredDb.version)
                assertIntegrity(restoredDb)
                restoredDb.rawQuery(
                    "SELECT qty FROM products WHERE id='p-1'",
                    null
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(17, cursor.getInt(0))
                }
                // Verify the restored database remains writable and its trigger survives.
                restoredDb.execSQL("INSERT INTO sale_items VALUES('si-2','i-1','p-1',2)")
                restoredDb.rawQuery("SELECT qty FROM products WHERE id='p-1'", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(15, cursor.getInt(0))
                }
            } finally {
                importedKey.fill(0)
            }
        } finally {
            sourceDb?.close()
            stagedDb?.close()
            restoredDb?.close()
            sourceKey?.fill(0)
            recoveredKey?.fill(0)
            password.fill('\u0000')
            listOf(source, staged, restored).forEach { file ->
                file.delete()
                File(file.path + "-wal").delete()
                File(file.path + "-shm").delete()
                File(file.path + "-journal").delete()
            }
            listOf(sourceMetadata, restoredMetadata).forEach { name ->
                File(context.noBackupFilesDir, name).delete()
                File(context.noBackupFilesDir, "$name.bak").delete()
            }
            runCatching {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                listOf(sourceAlias, restoredAlias).forEach { if (store.containsAlias(it)) store.deleteEntry(it) }
            }
        }
    }

    private fun openEncrypted(file: File, rawKey: ByteArray): SQLiteDatabase {
        // Context.getDatabasePath() returns the intended path but does not create
        // the databases/ parent directory. SQLCipher openDatabase() does not create
        // missing parent directories, so create and verify it before opening.
        val parent = file.parentFile
        check(parent != null && (parent.isDirectory || parent.mkdirs())) {
            "Could not create SQLCipher database directory: ${parent?.absolutePath}"
        }
        val passphrase = rawKey.joinToString("") { "%02x".format(it) }
            .toByteArray(Charsets.US_ASCII)
        return SQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
            null
        )
    }

    private fun assertIntegrity(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
        }
        database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
    }
}
