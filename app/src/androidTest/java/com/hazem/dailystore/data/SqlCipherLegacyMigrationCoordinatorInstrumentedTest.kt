package com.hazem.dailystore.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase as AndroidSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqlCipherLegacyMigrationCoordinatorInstrumentedTest {

    @Test
    fun migrationInstallsValidatedCipherDatabaseAndRetainsAllRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val live = createLegacyDatabase(context, suffix)
        val vault = newVault(context, suffix)
        val coordinator = SqlCipherLegacyMigrationCoordinator(context, "migration-$suffix.journal")

        val result = coordinator.migrate(live, vault) { /* fixture database is already closed */ }

        assertEquals(SqlCipherLegacyMigrationCoordinator.MigrationResult.MIGRATED, result)
        assertTrue(live.isFile)
        assertFalse(File(live.path + ".sqlcipher-migration-staging").exists())
        assertFalse(File(live.path + ".sqlite-migration-rollback").exists())
        val key = vault.getImportedDatabaseKeyForRestoreRecovery()!!
        try {
            SqlCipherDatabaseMigrator.validateEncryptedDatabase(live, key, sourceVersion = 33)
            val passphrase = key.toHexPassphrase()
            val encrypted = CipherSQLiteDatabase.openDatabase(
                live.absolutePath, passphrase, null,
                CipherSQLiteDatabase.OPEN_READONLY, null, null
            )
            try {
                encrypted.rawQuery(
                    "SELECT payload FROM migration_probe WHERE id=1", null
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("preserve-original-row", cursor.getString(0))
                    assertFalse(cursor.moveToNext())
                }
            } finally {
                encrypted.close()
                passphrase.fill(0)
            }
        } finally {
            key.fill(0)
            cleanup(context, live, suffix)
        }
    }

    @Test
    fun everySwapInterruptionRecoversToEitherVerifiedCipherOrOriginalSqlite() {
        val checkpoints = SqlCipherLegacyMigrationCoordinator.Checkpoint.values()
        checkpoints.forEachIndexed { index, interruptedAt ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
            val suffix = UUID.randomUUID().toString().replace("-", "")
            val live = createLegacyDatabase(context, suffix)
            val vault = newVault(context, suffix)
            val journalName = "migration-crash-$suffix.journal"
            val crashingCoordinator = SqlCipherLegacyMigrationCoordinator(
                context,
                journalName
            ) { checkpoint ->
                if (checkpoint == interruptedAt) throw SimulatedProcessInterruption()
            }

            try {
                var interrupted = false
                try {
                    crashingCoordinator.migrate(live, vault) { /* fixture is closed */ }
                } catch (_: SimulatedProcessInterruption) {
                    interrupted = true
                }
                assertTrue("Expected simulated interruption at $interruptedAt", interrupted)

                val recovery = SqlCipherLegacyMigrationCoordinator(context, journalName)
                    .recover(live, vault)

                val shouldCommit = interruptedAt.ordinal >=
                    SqlCipherLegacyMigrationCoordinator.Checkpoint.ENCRYPTED_DATABASE_RENAMED.ordinal
                if (shouldCommit) {
                    assertEquals(
                        "Recovery should complete a fully installed encrypted database at $interruptedAt",
                        SqlCipherLegacyMigrationCoordinator.RecoveryResult.MIGRATION_COMPLETED,
                        recovery
                    )
                    val key = vault.getImportedDatabaseKeyForRestoreRecovery()!!
                    try {
                        SqlCipherDatabaseMigrator.validateEncryptedDatabase(live, key, sourceVersion = 33)
                    } finally {
                        key.fill(0)
                    }
                } else {
                    val expected = if (interruptedAt ==
                        SqlCipherLegacyMigrationCoordinator.Checkpoint.ENCRYPTED_STAGE_VERIFIED
                    ) {
                        SqlCipherLegacyMigrationCoordinator.RecoveryResult.NOTHING_TO_RECOVER
                    } else {
                        SqlCipherLegacyMigrationCoordinator.RecoveryResult.MIGRATION_ROLLED_BACK
                    }
                    assertEquals("Recovery should preserve original SQLite at $interruptedAt", expected, recovery)
                    assertTrue("Original SQLite file must be restored at $interruptedAt", hasSqliteHeader(live))
                    val plain = AndroidSQLiteDatabase.openDatabase(live.absolutePath, null, AndroidSQLiteDatabase.OPEN_READONLY)
                    try {
                        plain.rawQuery("SELECT payload FROM migration_probe WHERE id=1", null).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            assertEquals("preserve-original-row", cursor.getString(0))
                        }
                    } finally {
                        plain.close()
                    }
                }

                assertFalse(File(live.path + ".sqlcipher-migration-staging").exists())
                assertFalse(File(live.path + ".sqlite-migration-rollback").exists())
            } finally {
                cleanup(context, live, suffix)
                File(context.noBackupFilesDir, journalName).delete()
                File(context.noBackupFilesDir, "$journalName.bak").delete()
            }
        }
    }

    @Test
    fun invalidOrphanFilesWithoutJournalFailClosedAndArePreserved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val live = createLegacyDatabase(context, suffix)
        val vault = newVault(context, suffix)
        val orphan = File(live.path + ".sqlcipher-migration-staging")
        orphan.writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            val coordinator = SqlCipherLegacyMigrationCoordinator(context, "migration-orphan-$suffix.journal")
            var rejected = false
            try {
                coordinator.recover(live, vault)
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue("Unknown orphan staging data must fail closed", rejected)
            assertTrue("Original SQLite must remain untouched", hasSqliteHeader(live))
            assertTrue("Unrecognized orphan must be preserved for diagnosis", orphan.isFile)
        } finally {
            cleanup(context, live, suffix)
        }
    }

    private fun createLegacyDatabase(context: Context, suffix: String): File {
        val file = context.getDatabasePath("legacy-migration-$suffix.db")
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val database = AndroidSQLiteDatabase.openOrCreateDatabase(file.absolutePath, null)
        try {
            database.execSQL("CREATE TABLE migration_probe(id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
            database.execSQL("INSERT INTO migration_probe(id,payload) VALUES(1,'preserve-original-row')")
            database.version = 33
        } finally {
            database.close()
        }
        return file
    }

    private fun newVault(context: Context, suffix: String) =
        DatabaseKeyVault(
            context,
            metadataFileName = "migration-key-$suffix.bin",
            keyAlias = "migration-key-$suffix"
        )

    private fun hasSqliteHeader(file: File): Boolean {
        if (!file.isFile || file.length() < 16L) return false
        return file.inputStream().use { input ->
            val header = ByteArray(16)
            input.read(header) == 16 &&
                header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
        }
    }

    private fun ByteArray.toHexPassphrase(): ByteArray =
        joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)

    private fun cleanup(context: Context, live: File, suffix: String) {
        listOf(
            live,
            File(live.path + "-wal"),
            File(live.path + "-shm"),
            File(live.path + "-journal"),
            File(live.path + ".sqlcipher-migration-staging"),
            File(live.path + ".sqlite-migration-rollback"),
            File(context.noBackupFilesDir, "migration-key-$suffix.bin"),
            File(context.noBackupFilesDir, "migration-key-$suffix.bin.bak")
        ).forEach { it.delete() }
    }

    private class SimulatedProcessInterruption : RuntimeException()
}
