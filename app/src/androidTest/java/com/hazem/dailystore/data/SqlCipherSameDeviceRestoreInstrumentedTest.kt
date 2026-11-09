package com.hazem.dailystore.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqlCipherSameDeviceRestoreInstrumentedTest {
    private class SimulatedProcessDeath : RuntimeException()

    @Test
    fun recoversSafelyAfterInterruptionAtEveryDurableAndFilesystemCheckpoint() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        System.loadLibrary("sqlcipher")

        SqlCipherSameDeviceRestoreCoordinator.Checkpoint.values().forEach { stopAt ->
            val suffix = UUID.randomUUID().toString().replace("-", "")
            val live = context.getDatabasePath("same-device-live-$suffix.db")
            val staged = context.getDatabasePath("same-device-stage-$suffix.db")
            val journalName = "same-device-journal-$suffix.bin"
            val metadataName = "same-device-key-$suffix.bin"
            val alias = "tds_same_device_test_$suffix"
            val oldKey = ByteArray(32).also(SecureRandom()::nextBytes)
            val newKey = ByteArray(32).also(SecureRandom()::nextBytes)
            val vault = DatabaseKeyVault(context, metadataName, alias)
            var liveValue = -1L
            try {
                vault.importDatabaseKey(oldKey, databaseExists = false)
                createDatabase(live, oldKey, 111L)
                createDatabase(staged, newKey, 222L)

                val coordinator = SqlCipherSameDeviceRestoreCoordinator(
                    context,
                    journalName
                ) { checkpoint ->
                    if (checkpoint == stopAt) throw SimulatedProcessDeath()
                }
                try {
                    coordinator.commit(
                        staged,
                        live,
                        newKey,
                        vault,
                        closeLiveDatabase = {}
                    )
                    throw AssertionError("Expected simulated process interruption at $stopAt")
                } catch (_: SimulatedProcessDeath) {
                    // A fresh coordinator instance simulates a process restart.
                }

                val journalRecord = SqlCipherSameDeviceRestoreJournal(
                    File(context.noBackupFilesDir, journalName)
                ).read() ?: error("Expected durable recovery journal")
                val snapshotFile = File(context.noBackupFilesDir, journalRecord.keySnapshotName)
                val recovered = SqlCipherSameDeviceRestoreCoordinator(context, journalName)
                    .recover(vault)
                val shouldCommitNew = stopAt == SqlCipherSameDeviceRestoreCoordinator.Checkpoint.NEW_DATABASE_RENAMED ||
                    stopAt == SqlCipherSameDeviceRestoreCoordinator.Checkpoint.NEW_DATABASE_SWAP_RECORDED ||
                    stopAt == SqlCipherSameDeviceRestoreCoordinator.Checkpoint.NEW_DATABASE_VERIFIED ||
                    stopAt == SqlCipherSameDeviceRestoreCoordinator.Checkpoint.VERIFICATION_RECORDED

                assertEquals(
                    if (shouldCommitNew) SqlCipherSameDeviceRestoreCoordinator.RecoveryResult.RESTORE_COMPLETED
                    else SqlCipherSameDeviceRestoreCoordinator.RecoveryResult.RESTORE_ROLLED_BACK,
                    recovered
                )
                val expectedKey = if (shouldCommitNew) newKey else oldKey
                liveValue = readProbe(live, expectedKey)
                assertEquals(if (shouldCommitNew) 222L else 111L, liveValue)
                assertFalse(File(live.path + ".sqlcipher-rollback").exists())
                assertFalse(File(context.noBackupFilesDir, journalName).exists())
                assertFalse(snapshotFile.exists())
                assertFalse(staged.exists())
            } finally {
                oldKey.fill(0)
                newKey.fill(0)
                listOf(live, staged, File(live.path + ".sqlcipher-rollback")).forEach { file ->
                    file.delete()
                    File(file.path + "-wal").delete()
                    File(file.path + "-shm").delete()
                    File(file.path + "-journal").delete()
                }
                File(context.noBackupFilesDir, metadataName).delete()
                File(context.noBackupFilesDir, "$metadataName.bak").delete()
                File(context.noBackupFilesDir, journalName).delete()
                File(context.noBackupFilesDir, "$journalName.bak").delete()
                runCatching {
                    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    if (store.containsAlias(alias)) store.deleteEntry(alias)
                }
            }
        }
    }

    @Test
    fun invalidStagedDatabaseIsRejectedWithoutChangingLiveDatabaseOrKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        System.loadLibrary("sqlcipher")
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val live = context.getDatabasePath("same-device-invalid-live-$suffix.db")
        val staged = context.getDatabasePath("same-device-invalid-stage-$suffix.db")
        val metadataName = "same-device-invalid-key-$suffix.bin"
        val alias = "tds_same_device_invalid_$suffix"
        val oldKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val newKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val vault = DatabaseKeyVault(context, metadataName, alias)
        try {
            vault.importDatabaseKey(oldKey, databaseExists = false)
            createDatabase(live, oldKey, 333L)
            staged.writeText("not a SQLCipher database")
            try {
                SqlCipherSameDeviceRestoreCoordinator(context).commit(
                    staged, live, newKey, vault, closeLiveDatabase = {}
                )
                throw AssertionError("Corrupt staged database was accepted")
            } catch (_: Exception) {
                assertEquals(333L, readProbe(live, oldKey))
            }
            val stillOld = vault.getOrCreateDatabaseKey(databaseExists = true)
            try {
                assertTrue(java.security.MessageDigest.isEqual(oldKey, stillOld))
            } finally {
                stillOld.fill(0)
            }
        } finally {
            oldKey.fill(0)
            newKey.fill(0)
            listOf(live, staged).forEach { file ->
                file.delete()
                File(file.path + "-wal").delete()
                File(file.path + "-shm").delete()
                File(file.path + "-journal").delete()
            }
            File(context.noBackupFilesDir, metadataName).delete()
            File(context.noBackupFilesDir, "$metadataName.bak").delete()
            runCatching {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (store.containsAlias(alias)) store.deleteEntry(alias)
            }
        }
    }

    @Test
    fun missingKeyDuringInterruptedRestoreFailsClosedAndPreservesEveryDatabaseFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        System.loadLibrary("sqlcipher")
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val live = context.getDatabasePath("same-device-missing-key-live-$suffix.db")
        val staged = context.getDatabasePath("same-device-missing-key-stage-$suffix.db")
        val journalName = "same-device-missing-key-journal-$suffix.bin"
        val metadataName = "same-device-missing-key-vault-$suffix.bin"
        val alias = "tds_same_device_missing_key_$suffix"
        val oldKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val newKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val vault = DatabaseKeyVault(context, metadataName, alias)
        val journalFile = File(context.noBackupFilesDir, journalName)

        try {
            vault.importDatabaseKey(oldKey, databaseExists = false)
            createDatabase(live, oldKey, 444L)
            createDatabase(staged, newKey, 555L)

            try {
                SqlCipherSameDeviceRestoreCoordinator(context, journalName) { checkpoint ->
                    if (checkpoint == SqlCipherSameDeviceRestoreCoordinator.Checkpoint.JOURNAL_PREPARED) {
                        throw SimulatedProcessDeath()
                    }
                }.commit(staged, live, newKey, vault, closeLiveDatabase = {})
                throw AssertionError("Expected interruption after durable journal creation")
            } catch (_: SimulatedProcessDeath) {
                // Continue as if Android killed the process after the journal was synced.
            }

            // Simulate lost/unavailable local key metadata. Recovery must not
            // generate a replacement key or touch either database file.
            File(context.noBackupFilesDir, metadataName).delete()
            File(context.noBackupFilesDir, "$metadataName.bak").delete()
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(alias)) store.deleteEntry(alias)

            try {
                SqlCipherSameDeviceRestoreCoordinator(context, journalName).recover(vault)
                throw AssertionError("Recovery proceeded without the required local key")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("مفتاح قاعدة البيانات مفقود"))
            }

            assertTrue("Recovery journal must be retained", journalFile.exists())
            assertTrue("Original live database must be retained", live.exists())
            assertTrue("Staged database must be retained", staged.exists())
            assertEquals(444L, readProbe(live, oldKey))
            assertEquals(555L, readProbe(staged, newKey))
        } finally {
            oldKey.fill(0)
            newKey.fill(0)
            listOf(live, staged, File(live.path + ".sqlcipher-rollback")).forEach { file ->
                file.delete()
                File(file.path + "-wal").delete()
                File(file.path + "-shm").delete()
                File(file.path + "-journal").delete()
            }
            journalFile.delete()
            File(journalFile.path + ".bak").delete()
            File(context.noBackupFilesDir, metadataName).delete()
            File(context.noBackupFilesDir, "$metadataName.bak").delete()
            runCatching {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (store.containsAlias(alias)) store.deleteEntry(alias)
            }
        }
    }

    @Test
    fun malformedRestoreJournalFailsClosedWithoutChangingLiveOrStagedDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        System.loadLibrary("sqlcipher")
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val live = context.getDatabasePath("same-device-corrupt-journal-live-$suffix.db")
        val staged = context.getDatabasePath("same-device-corrupt-journal-stage-$suffix.db")
        val journalName = "same-device-corrupt-journal-$suffix.bin"
        val metadataName = "same-device-corrupt-vault-$suffix.bin"
        val alias = "tds_same_device_corrupt_journal_$suffix"
        val oldKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val newKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val vault = DatabaseKeyVault(context, metadataName, alias)
        val journalFile = File(context.noBackupFilesDir, journalName)

        try {
            vault.importDatabaseKey(oldKey, databaseExists = false)
            createDatabase(live, oldKey, 666L)
            createDatabase(staged, newKey, 777L)
            journalFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))

            try {
                SqlCipherSameDeviceRestoreCoordinator(context, journalName).recover(vault)
                throw AssertionError("Malformed restore journal was accepted")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("سجل استعادة SQLCipher"))
            }

            assertTrue("Corrupt journal must be retained for diagnosis", journalFile.exists())
            assertTrue("Live database must be preserved", live.exists())
            assertTrue("Staged database must be preserved", staged.exists())
            assertEquals(666L, readProbe(live, oldKey))
            assertEquals(777L, readProbe(staged, newKey))
        } finally {
            oldKey.fill(0)
            newKey.fill(0)
            listOf(live, staged).forEach { file ->
                file.delete()
                File(file.path + "-wal").delete()
                File(file.path + "-shm").delete()
                File(file.path + "-journal").delete()
            }
            journalFile.delete()
            File(journalFile.path + ".bak").delete()
            File(context.noBackupFilesDir, metadataName).delete()
            File(context.noBackupFilesDir, "$metadataName.bak").delete()
            runCatching {
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (store.containsAlias(alias)) store.deleteEntry(alias)
            }
        }
    }

    private fun createDatabase(file: File, key: ByteArray, value: Long) {
        val parent = file.parentFile ?: error("Database directory unavailable")
        check(parent.isDirectory || parent.mkdirs())
        check(file.createNewFile())
        val db = openEncrypted(file, key)
        try {
            db.execSQL("CREATE TABLE restore_probe(id TEXT PRIMARY KEY, value INTEGER NOT NULL)")
            db.execSQL("INSERT INTO restore_probe VALUES('row-1', $value)")
            db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        } finally {
            db.close()
        }
    }

    private fun readProbe(file: File, key: ByteArray): Long {
        val db = openEncrypted(file, key, readOnly = true)
        try {
            db.rawQuery("SELECT value FROM restore_probe WHERE id='row-1'", null).use { cursor ->
                check(cursor.moveToFirst())
                return cursor.getLong(0)
            }
        } finally {
            db.close()
        }
    }

    private fun openEncrypted(file: File, rawKey: ByteArray, readOnly: Boolean = false): SQLiteDatabase {
        val passphrase = rawKey.joinToString("") { "%02x".format(it) }
            .toByteArray(Charsets.US_ASCII)
        return SQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase,
            null,
            if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE,
            null,
            null
        )
    }
}
