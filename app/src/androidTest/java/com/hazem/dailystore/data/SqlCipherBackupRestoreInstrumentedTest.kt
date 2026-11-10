package com.hazem.dailystore.data

import android.content.Context
import android.util.Log
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
            tracePhase("setup_complete")
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

            tracePhase("source_vault_key_start")
            sourceKey = sourceVault.getOrCreateDatabaseKey(databaseExists = false)
            tracePhase("source_vault_key_complete")
            tracePhase("source_database_open_start")
            sourceDb = openEncrypted(source, sourceKey)
            tracePhase("source_database_open_complete")
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
            tracePhase("source_database_closed")

            val container = ByteArrayOutputStream()
            tracePhase("backup_container_write_start")
            SqlCipherBackupContainer.write(source, sourceKey, password, container)
            tracePhase("backup_container_write_complete bytes=${container.size()}")

            tracePhase("backup_container_extract_start")
            recoveredKey = SqlCipherBackupContainer.extract(
                ByteArrayInputStream(container.toByteArray()),
                staged,
                password
            )
            tracePhase("backup_container_extract_complete bytes=${staged.length()}")
            assertTrue(staged.isFile)
            assertFalse(restored.exists())

            // Validate the extracted payload with SQLCipher before importing its key.
            tracePhase("staged_database_open_start")
            stagedDb = openEncrypted(staged, recoveredKey)
            tracePhase("staged_database_open_complete")
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
            tracePhase("staged_database_validation_complete")

            // The destination vault has a distinct alias and no source-device metadata.
            tracePhase("destination_key_import_start")
            restoredVault.importDatabaseKey(recoveredKey, databaseExists = false)
            tracePhase("destination_key_import_complete")
            check(staged.renameTo(restored)) { "Could not atomically place validated restore file" }

            val importedKey = restoredVault.getOrCreateDatabaseKey(databaseExists = true)
            try {
                assertArrayEquals(recoveredKey, importedKey)
                tracePhase("restored_database_open_start")
                restoredDb = openEncrypted(restored, importedKey)
                tracePhase("restored_database_open_complete")
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
            tracePhase("restore_end_to_end_assertions_complete")
        } finally {
            tracePhase("restore_end_to_end_cleanup_start")
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


    @Test
    fun pipelineRejectsWrongPasswordBeforeStaging() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val staged = context.getDatabasePath("pipeline-wrong-password-stage-$suffix.db")
        val key = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val correctPassword = "Correct-Pipeline-Password".toCharArray()
        val wrongPassword = "Wrong-Pipeline-Password".toCharArray()
        try {
            // Reject at authenticated key-envelope verification, before touching
            // payload bytes or opening a SQLCipher database.
            val envelope = BackupKeyEnvelope.wrap(key, correctPassword)
            val backup = ByteArrayOutputStream().also { bytes ->
                java.io.DataOutputStream(bytes).use { output ->
                    output.write(byteArrayOf(
                        0x54, 0x44, 0x53, 0x42, 0x4B, 0x55, 0x50, 0x31
                    ))
                    output.writeInt(1)
                    output.writeInt(envelope.size)
                    output.writeLong(1L)
                    output.write(envelope)
                    output.writeByte(0x01)
                }
            }.toByteArray()

            try {
                SqlCipherBackupRestorePipeline().stageRestore(
                    ByteArrayInputStream(backup),
                    wrongPassword,
                    staged
                )
                throw AssertionError("Wrong password was accepted")
            } catch (_: IllegalArgumentException) {
                assertFalse(staged.exists())
            }
        } finally {
            key.fill(0)
            correctPassword.fill('\u0000')
            wrongPassword.fill('\u0000')
            staged.delete()
        }
    }

    @Test
    fun pipelineRejectsTamperedSqlCipherPayload() = withPipelineFixture { fixture ->
        val damaged = fixture.backupBytes.copyOf()
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 0x01).toByte()
        try {
            fixture.pipeline.stageRestore(
                ByteArrayInputStream(damaged),
                fixture.password,
                fixture.staged
            )
            throw AssertionError("Tampered SQLCipher payload was accepted")
        } catch (_: IllegalArgumentException) {
            assertFalse(fixture.staged.exists())
        }
    }

    @Test
    fun pipelineRejectsTruncatedBackupPayload() = withPipelineFixture { fixture ->
        try {
            fixture.pipeline.stageRestore(
                ByteArrayInputStream(fixture.backupBytes.copyOf(fixture.backupBytes.size - 1)),
                fixture.password,
                fixture.staged
            )
            throw AssertionError("Truncated backup was accepted")
        } catch (_: IllegalArgumentException) {
            assertFalse(fixture.staged.exists())
        }
    }

    @Test
    fun pipelineRejectsTrailingBackupBytes() = withPipelineFixture { fixture ->
        try {
            fixture.pipeline.stageRestore(
                ByteArrayInputStream(fixture.backupBytes + byteArrayOf(0x01)),
                fixture.password,
                fixture.staged
            )
            throw AssertionError("Backup with trailing bytes was accepted")
        } catch (_: IllegalArgumentException) {
            assertFalse(fixture.staged.exists())
        }
    }

    @Test
    fun pipelineStagesAndCommitsValidEncryptedBackup() = withPipelineFixture { fixture ->
        var recovered: ByteArray? = null
        var db: SQLiteDatabase? = null
        try {
            recovered = fixture.pipeline.stageRestore(
                ByteArrayInputStream(fixture.backupBytes),
                fixture.password,
                fixture.staged
            )
            assertArrayEquals(fixture.key, recovered)
            val vault = DatabaseKeyVault(
                fixture.context,
                fixture.metadata,
                fixture.alias
            )
            fixture.pipeline.commitRestoreToEmptyDevice(
                fixture.staged,
                fixture.restored,
                recovered,
                vault
            )
            assertTrue(fixture.restored.isFile)

            val imported = vault.getOrCreateDatabaseKey(databaseExists = true)
            try {
                assertArrayEquals(fixture.key, imported)
                db = openEncrypted(fixture.restored, imported)
                assertEquals(33, db.version)
                assertIntegrity(db)
                db.rawQuery(
                    "SELECT amount_minor FROM ledger WHERE id='cash-1'",
                    null
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(12500, cursor.getLong(0))
                }
            } finally {
                imported.fill(0)
            }
        } finally {
            db?.close()
            recovered?.fill(0)
        }
    }

    @Test
    fun pipelineRejectsBackupWhileWalIsNonEmpty() = withPipelineFixture { fixture ->
        val pendingWal = File(fixture.source.path + "-wal")
        try {
            pendingWal.writeBytes(byteArrayOf(0x01))
            try {
                fixture.pipeline.createBackup(
                    fixture.source,
                    fixture.key,
                    fixture.password,
                    ByteArrayOutputStream()
                )
                throw AssertionError("Backup accepted a non-empty WAL sidecar")
            } catch (_: IllegalArgumentException) {
                assertTrue(fixture.source.isFile)
            }
        } finally {
            pendingWal.delete()
        }
    }

    private data class PipelineFixture(
        val context: Context,
        val source: File,
        val staged: File,
        val restored: File,
        val metadata: String,
        val alias: String,
        val key: ByteArray,
        val password: CharArray,
        val backupBytes: ByteArray,
        val pipeline: SqlCipherBackupRestorePipeline
    ) {
        fun cleanup() {
            key.fill(0)
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

    private fun withPipelineFixture(block: (PipelineFixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val source = context.getDatabasePath("pipeline-source-$suffix.db")
        val staged = context.getDatabasePath("pipeline-stage-$suffix.db")
        val restored = context.getDatabasePath("pipeline-restored-$suffix.db")
        val metadata = "pipeline-key-$suffix.bin"
        val alias = "tds_pipeline_$suffix"
        val key = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val password = "Pipeline-Backup-2026".toCharArray()
        val pipeline = SqlCipherBackupRestorePipeline()
        var db: SQLiteDatabase? = null
        var fixture: PipelineFixture? = null
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

            val backup = ByteArrayOutputStream()
            pipeline.createBackup(source, key, password, backup)
            val createdFixture = PipelineFixture(
                context,
                source,
                staged,
                restored,
                metadata,
                alias,
                key,
                password,
                backup.toByteArray(),
                pipeline
            )
            fixture = createdFixture
            block(createdFixture)
        } finally {
            db?.close()
            if (fixture != null) {
                fixture.cleanup()
            } else {
                key.fill(0)
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


    @Test
    fun restoreJournalPersistsMonotonicPhasesWithoutStoringSecrets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val journalFile = File(context.noBackupFilesDir, "restore-journal-$suffix.bin")
        val staging = File(context.cacheDir, "restore-stage-$suffix.db")
        val destination = File(context.cacheDir, "restore-destination-$suffix.db")
        val passwordMarker = "must-never-appear-password-$suffix"
        val secretKeyMarker = "must-never-appear-key-$suffix"
        try {
            SqlCipherRestoreJournal(journalFile).begin(staging, destination)
            assertEquals(
                SqlCipherRestoreJournal.Phase.STAGED,
                SqlCipherRestoreJournal(journalFile).read()?.phase
            )
            val journal = SqlCipherRestoreJournal(journalFile)
            journal.advance(
                SqlCipherRestoreJournal.Phase.STAGED,
                SqlCipherRestoreJournal.Phase.KEY_IMPORTED
            )
            assertEquals(
                SqlCipherRestoreJournal.Phase.KEY_IMPORTED,
                SqlCipherRestoreJournal(journalFile).read()?.phase
            )
            journal.advance(
                SqlCipherRestoreJournal.Phase.KEY_IMPORTED,
                SqlCipherRestoreJournal.Phase.DATABASE_COMMITTED
            )
            val record = SqlCipherRestoreJournal(journalFile).read()
            assertEquals(SqlCipherRestoreJournal.Phase.DATABASE_COMMITTED, record?.phase)
            assertEquals(staging.name, record?.stagingName)
            assertEquals(destination.name, record?.destinationName)
            val journalBytes = journalFile.readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(!journalBytes.contains(passwordMarker))
            assertTrue(!journalBytes.contains(secretKeyMarker))
            journal.clear()
            assertTrue(SqlCipherRestoreJournal(journalFile).read() == null)
        } finally {
            journalFile.delete()
            File(journalFile.path + ".bak").delete()
            File(journalFile.path + ".new").delete()
            staging.delete()
            destination.delete()
        }
    }

    @Test
    fun restoreJournalRejectsSkippedOrReversedPhasesAndDifferentPaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val journalFile = File(context.noBackupFilesDir, "restore-journal-order-$suffix.bin")
        val staging = File(context.cacheDir, "restore-stage-order-$suffix.db")
        val destination = File(context.cacheDir, "restore-destination-order-$suffix.db")
        try {
            val journal = SqlCipherRestoreJournal(journalFile)
            journal.begin(staging, destination)
            try {
                journal.advance(
                    SqlCipherRestoreJournal.Phase.STAGED,
                    SqlCipherRestoreJournal.Phase.DATABASE_COMMITTED
                )
                throw AssertionError("Journal accepted a skipped phase")
            } catch (_: IllegalArgumentException) { }
            try {
                journal.advance(
                    SqlCipherRestoreJournal.Phase.KEY_IMPORTED,
                    SqlCipherRestoreJournal.Phase.DATABASE_COMMITTED
                )
                throw AssertionError("Journal accepted an unexpected current phase")
            } catch (_: IllegalStateException) { }
            try {
                journal.begin(staging, File(context.filesDir, "outside-$suffix.db"))
                throw AssertionError("Journal accepted paths in different directories")
            } catch (_: IllegalArgumentException) { }
            assertEquals(
                SqlCipherRestoreJournal.Phase.STAGED,
                journal.read()?.phase
            )
        } finally {
            journalFile.delete()
            File(journalFile.path + ".bak").delete()
            File(journalFile.path + ".new").delete()
            staging.delete()
            destination.delete()
        }
    }

    @Test
    fun restoreJournalCorruptionFailsClosedAndPreservesDatabaseFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val journalFile = File(context.noBackupFilesDir, "restore-journal-corrupt-$suffix.bin")
        val staging = File(context.cacheDir, "restore-stage-corrupt-$suffix.db")
        val destination = File(context.cacheDir, "restore-destination-corrupt-$suffix.db")
        try {
            staging.writeText("staged-encrypted-payload")
            destination.writeText("existing-database")
            SqlCipherRestoreJournal(journalFile).begin(staging, destination)
            journalFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
            try {
                SqlCipherRestoreJournal(journalFile).read()
                throw AssertionError("Corrupted journal was accepted")
            } catch (_: IllegalStateException) {
                assertEquals("staged-encrypted-payload", staging.readText())
                assertEquals("existing-database", destination.readText())
            }
        } finally {
            journalFile.delete()
            File(journalFile.path + ".bak").delete()
            File(journalFile.path + ".new").delete()
            staging.delete()
            destination.delete()
        }
    }


    @Test
    fun restoreCoordinatorWaitsForMissingKeyAndResumesAfterKeyImport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val staging = context.getDatabasePath("coordinator-stage-$suffix.db")
        val destination = context.getDatabasePath("coordinator-destination-$suffix.db")
        val journalFile = File(context.noBackupFilesDir, "coordinator-journal-$suffix.bin")
        val metadata = "coordinator-key-$suffix.bin"
        val alias = "tds_coordinator_$suffix"
        val key = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val password = "Coordinator-Resume-Password".toCharArray()
        val vault = DatabaseKeyVault(context, metadata, alias)
        var db: SQLiteDatabase? = null
        try {
            // SQLCipher openDatabase() requires the target file to exist on this
            // Android runtime. getDatabasePath() resolves the path but does not
            // create the file; create it explicitly before opening the staged DB.
            val databaseDirectory = staging.parentFile
                ?: error("Application database directory is unavailable")
            check(databaseDirectory.isDirectory || databaseDirectory.mkdirs()) {
                "Unable to create application database directory"
            }
            check(staging.createNewFile()) {
                "Unable to pre-create SQLCipher staging database: ${staging.absolutePath}"
            }
            db = openEncrypted(staging, key)
            db.execSQL("CREATE TABLE restore_probe(id TEXT PRIMARY KEY, amount INTEGER NOT NULL)")
            db.execSQL("INSERT INTO restore_probe VALUES('probe-1', 8800)")
            db.close()
            db = null
            val backup = ByteArrayOutputStream()
            SqlCipherBackupContainer.write(staging, key, password, backup)
            val journal = SqlCipherRestoreJournal(journalFile)
            journal.begin(staging, destination)
            val coordinator = SqlCipherRestoreCoordinator(journal)

            assertEquals(SqlCipherRestoreCoordinator.RecoveryResult.WAITING_FOR_KEY,
                coordinator.recover(staging.parentFile!!, vault))
            assertTrue(staging.isFile)
            assertFalse(destination.exists())

            assertEquals(SqlCipherRestoreCoordinator.RecoveryResult.RESTORE_COMPLETED,
                coordinator.resumeFromBackup(
                    ByteArrayInputStream(backup.toByteArray()),
                    password,
                    staging.parentFile!!,
                    vault
                ))
            assertFalse(staging.exists())
            assertTrue(destination.isFile)
            assertTrue(journal.read() == null)
            val imported = vault.getOrCreateDatabaseKey(databaseExists = true)
            try {
                db = openEncrypted(destination, imported)
                db.rawQuery("SELECT amount FROM restore_probe", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(8800, cursor.getLong(0))
                }
                assertIntegrity(db)
            } finally {
                imported.fill(0)
            }
        } finally {
            db?.close()
            key.fill(0)
            password.fill(Char.MIN_VALUE)
            listOf(staging, destination).forEach { file ->
                file.delete()
                File(file.path + "-wal").delete()
                File(file.path + "-shm").delete()
                File(file.path + "-journal").delete()
            }
            journalFile.delete()
            File(journalFile.path + ".bak").delete()
            File(context.noBackupFilesDir, metadata).delete()
            File(context.noBackupFilesDir, "$metadata.bak").delete()
            runCatching {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (store.containsAlias(alias)) store.deleteEntry(alias)
            }
        }
    }

    @Test
    fun sameDeviceKeyReplacementRequiresExpectedKeyAndCanRestoreWrappedRollbackSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val metadata = "same-device-key-$suffix.bin"
        val alias = "tds_same_device_$suffix"
        val vault = DatabaseKeyVault(context, metadata, alias)
        val original = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val replacement = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val wrongExpected = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        try {
            vault.importDatabaseKey(original, databaseExists = false)
            val wrappedSnapshot = vault.snapshotWrappedKeyMetadata()
            assertFalse(wrappedSnapshot.contentEquals(original))
            try {
                vault.replaceDatabaseKeyForRestore(replacement, wrongExpected)
                throw AssertionError("Key replacement accepted an unexpected current key")
            } catch (_: IllegalStateException) { }
            val stillOriginal = vault.getOrCreateDatabaseKey(databaseExists = true)
            try {
                assertArrayEquals(original, stillOriginal)
            } finally {
                stillOriginal.fill(0)
            }

            vault.replaceDatabaseKeyForRestore(replacement, original)
            val nowReplacement = vault.getOrCreateDatabaseKey(databaseExists = true)
            try {
                assertArrayEquals(replacement, nowReplacement)
            } finally {
                nowReplacement.fill(0)
            }

            vault.restoreWrappedKeyMetadata(wrappedSnapshot)
            val rolledBack = vault.getOrCreateDatabaseKey(databaseExists = true)
            try {
                assertArrayEquals(original, rolledBack)
            } finally {
                rolledBack.fill(0)
            }
        } finally {
            original.fill(0)
            replacement.fill(0)
            wrongExpected.fill(0)
            File(context.noBackupFilesDir, metadata).delete()
            File(context.noBackupFilesDir, "$metadata.bak").delete()
            runCatching {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (store.containsAlias(alias)) store.deleteEntry(alias)
            }
        }
    }

    private fun tracePhase(phase: String) {
        Log.i("SqlCipherRestoreTest", "phase=$phase elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}")
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
