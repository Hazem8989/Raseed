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

            // getDatabasePath() only resolves the path; it does not create the
            // app-private databases directory or the database file. Create both
            // explicitly before SQLCipher opens the brand-new source database.
            val databaseDirectory = source.parentFile
                ?: error("Application database directory is unavailable")
            check(databaseDirectory.isDirectory || databaseDirectory.mkdirs()) {
                "Unable to create application database directory: ${databaseDirectory.absolutePath}"
            }
            check(databaseDirectory.canWrite()) {
                "Application database directory is not writable: ${databaseDirectory.absolutePath}"
            }
            check(source.createNewFile()) {
                "Unable to pre-create SQLCipher source database: ${source.absolutePath}"
            }

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
            wrongPassword.fill('\u0000')
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


    @Test
    fun pipelineRoundTripsAndRejectsWrongPasswordAndTamperedPayload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val source = context.getDatabasePath("pipeline-source-$suffix.db")
        val staged = context.getDatabasePath("pipeline-stage-$suffix.db")
        val restored = context.getDatabasePath("pipeline-restored-$suffix.db")
        val metadata = "pipeline-key-$suffix.bin"
        val alias = "tds_pipeline_$suffix"
        val key = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val password = "Pipeline-Backup-2026".toCharArray()
        val wrongPassword = "Wrong-Pipeline-Password".toCharArray()
        val pipeline = SqlCipherBackupRestorePipeline()
        var db: SQLiteDatabase? = null
        var recovered: ByteArray? = null
        try {
            val parent = source.parentFile ?: error("Database directory unavailable")
            check(parent.isDirectory || parent.mkdirs())
            check(source.createNewFile())
            db = openEncrypted(source, key)
            db.execSQL("CREATE TABLE ledger(id TEXT PRIMARY KEY, amount_minor INTEGER NOT NULL)")
            db.execSQL("INSERT INTO ledger VALUES('cash-1', 12500)")
            db.version = 33
            db.close()
            db = null

            // A non-empty WAL means the caller did not complete the checkpoint/close phase.
            val pendingWal = File(source.path + "-wal")
            pendingWal.writeBytes(byteArrayOf(0x01))
            try {
                pipeline.createBackup(source, key, password, ByteArrayOutputStream())
                throw AssertionError("Backup accepted a non-empty WAL sidecar")
            } catch (_: IllegalArgumentException) {
                assertTrue(source.isFile)
            } finally {
                pendingWal.delete()
            }

            val backup = ByteArrayOutputStream()
            pipeline.createBackup(source, key, password, backup)
            val backupBytes = backup.toByteArray()
            assertTrue(backupBytes.size > source.length())

            // A wrong password must fail before a staged database is accepted.
            try {
                pipeline.stageRestore(
                    ByteArrayInputStream(backupBytes),
                    wrongPassword,
                    staged
                )
                throw AssertionError("Wrong password was accepted")
            } catch (_: IllegalArgumentException) {
                assertFalse(staged.exists())
            }

            // Authenticated SQLCipher payload corruption must also be rejected.
            val damaged = backupBytes.copyOf()
            damaged[damaged.lastIndex] = (damaged.last().toInt() xor 0x01).toByte()
            try {
                pipeline.stageRestore(
                    ByteArrayInputStream(damaged),
                    password,
                    staged
                )
                throw AssertionError("Tampered SQLCipher payload was accepted")
            } catch (_: IllegalArgumentException) {
                assertFalse(staged.exists())
            }
            try {
                pipeline.stageRestore(
                    ByteArrayInputStream(backupBytes.copyOf(backupBytes.size - 1)),
                    password,
                    staged
                )
                throw AssertionError("Truncated backup was accepted")
            } catch (_: IllegalArgumentException) {
                assertFalse(staged.exists())
            }
            try {
                pipeline.stageRestore(
                    ByteArrayInputStream(backupBytes + byteArrayOf(0x01)),
                    password,
                    staged
                )
                throw AssertionError("Backup with trailing bytes was accepted")
            } catch (_: IllegalArgumentException) {
                assertFalse(staged.exists())
            }

            recovered = pipeline.stageRestore(
                ByteArrayInputStream(backupBytes),
                password,
                staged
            )
            assertArrayEquals(key, recovered)
            val vault = DatabaseKeyVault(context, metadata, alias)
            pipeline.commitRestoreToEmptyDevice(staged, restored, recovered, vault)

            val imported = vault.getOrCreateDatabaseKey(databaseExists = true)
            try {
                assertArrayEquals(key, imported)
                db = openEncrypted(restored, imported)
                assertEquals(33, db.version)
                db.rawQuery("SELECT amount_minor FROM ledger WHERE id='cash-1'", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(12500, cursor.getLong(0))
                }
            } finally {
                imported.fill(0)
            }
        } finally {
            db?.close()
            key.fill(0)
            recovered?.fill(0)
            password.fill('\u0000')
            listOf(source, staged, restored).forEach { file ->
                file.delete()
                File(file.path + "-wal").delete()
                File(file.path + "-shm").delete()
                File(file.path + "-journal").delete()
            }
            File(context.noBackupFilesDir, metadata).delete()
            File(context.noBackupFilesDir, "$metadata.bak").delete()
            runCatching {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (store.containsAlias(alias)) store.deleteEntry(alias)
            }
        }
    }

    @Test
    fun pipelineRejectsNonEmptyDestinationAndKeyVaultRejectsDifferentKeyButAllowsRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val metadata = "pipeline-idempotent-$suffix.bin"
        val alias = "tds_pipeline_idempotent_$suffix"
        val key = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val differentKey = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val vault = DatabaseKeyVault(context, metadata, alias)
        val occupied = context.getDatabasePath("pipeline-occupied-$suffix.db")
        val staged = context.getDatabasePath("pipeline-unused-stage-$suffix.db")
        try {
            val parent = occupied.parentFile ?: error("Database directory unavailable")
            check(parent.isDirectory || parent.mkdirs())
            occupied.writeText("existing-user-data")
            staged.writeBytes(byteArrayOf(0x01))
            vault.importDatabaseKey(key, databaseExists = false)
            // Idempotent retry after an interrupted file rename is allowed.
            vault.importDatabaseKey(key, databaseExists = false)
            try {
                vault.importDatabaseKey(differentKey, databaseExists = false)
                throw AssertionError("A different key replaced the existing local key")
            } catch (_: DatabaseKeyVault.DatabaseKeyException) {
                assertTrue(occupied.readText() == "existing-user-data")
            }
            try {
                SqlCipherBackupRestorePipeline().commitRestoreToEmptyDevice(
                    staged, occupied, key, vault
                )
                throw AssertionError("Restore overwrote an existing destination")
            } catch (_: IllegalArgumentException) {
                assertTrue(occupied.readText() == "existing-user-data")
            }
            val stored = vault.getOrCreateDatabaseKey(databaseExists = false)
            try {
                assertArrayEquals(key, stored)
            } finally {
                stored.fill(0)
            }
        } finally {
            key.fill(0)
            differentKey.fill(0)
            occupied.delete()
            staged.delete()
            File(context.noBackupFilesDir, metadata).delete()
            File(context.noBackupFilesDir, "$metadata.bak").delete()
            runCatching {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (store.containsAlias(alias)) store.deleteEntry(alias)
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
