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
                createDatabase(live, oldKey, 111L)
                createDatabase(staged, newKey, 222L)
                vault.importDatabaseKey(oldKey, databaseExists = true)

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
                context.noBackupFilesDir.listFiles()
                    ?.filter { it.name.startsWith("tds-same-device-key-") && it.name.endsWith(".bin") }
                    ?.forEach { it.delete() }
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
            createDatabase(live, oldKey, 333L)
            staged.writeText("not a SQLCipher database")
            vault.importDatabaseKey(oldKey, databaseExists = true)
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
