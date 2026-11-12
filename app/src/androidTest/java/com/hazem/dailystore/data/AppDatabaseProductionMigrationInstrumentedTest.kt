package com.hazem.dailystore.data

import android.content.Context
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
 * Exercises the real AppDatabase schema through the production legacy migration
 * coordinator. This is intentionally isolated by database name and key-vault
 * metadata so it never touches the installed application's live database.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseProductionMigrationInstrumentedTest {

    @Test
    fun migratesActualAppDatabaseV33SchemaAndPreservesSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val databaseName = "production-migration-$suffix.db"
        val metadataName = "production-migration-key-$suffix.bin"
        val keyAlias = "tds_prod_migration_$suffix"
        val liveDatabase = context.getDatabasePath(databaseName)
        val vault = DatabaseKeyVault(context, metadataName, keyAlias)
        var helper: AppDatabase? = null
        var key: ByteArray? = null
        var encrypted: CipherSQLiteDatabase? = null

        try {
            helper = AppDatabase(context, databaseName)
            val legacy = helper.writableDatabase
            assertEquals(AppDatabase.DATABASE_VERSION, legacy.version)

            // Keep explicit business settings in the source so the test proves
            // both the real schema and real persisted rows survive the export.
            legacy.execSQL(
                """INSERT OR REPLACE INTO app_settings(setting_key, setting_value, updated_at)
                   VALUES('production_migration_probe', 'preserved', '2026-11-13T00:00:00Z')"""
            )

            val coordinator = SqlCipherLegacyMigrationCoordinator(
                context,
                journalFileName = "production-migration-$suffix.journal"
            )
            val result = coordinator.migrate(liveDatabase, vault) {
                helper?.checkpointAndCloseForBackup()
            }

            assertEquals(
                SqlCipherLegacyMigrationCoordinator.MigrationResult.MIGRATED,
                result
            )
            assertTrue("Encrypted database must remain at the live path", liveDatabase.isFile)
            assertFalse(
                "The installed live file must no longer have the plaintext SQLite header",
                liveDatabase.inputStream().use { input ->
                    val header = ByteArray(16)
                    input.read(header) == header.size &&
                        header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII))
                }
            )

            key = vault.getImportedDatabaseKeyForRestoreRecovery()
                ?: error("Migration did not persist a recoverable SQLCipher key")
            SqlCipherDatabaseMigrator.validateEncryptedDatabase(liveDatabase, key!!)

            val passphrase = key!!.joinToString("") { "%02x".format(it) }
                .toByteArray(Charsets.US_ASCII)
            encrypted = CipherSQLiteDatabase.openDatabase(
                liveDatabase.absolutePath,
                passphrase,
                null,
                CipherSQLiteDatabase.OPEN_READONLY,
                null,
                null
            )
            passphrase.fill(0)

            assertEquals(AppDatabase.DATABASE_VERSION, encrypted!!.version)
            encrypted!!.rawQuery(
                "SELECT setting_value FROM app_settings WHERE setting_key='production_migration_probe'",
                null
            ).use { cursor ->
                assertTrue("The production settings row must survive migration", cursor.moveToFirst())
                assertEquals("preserved", cursor.getString(0))
            }
            encrypted!!.rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("The real production schema must be present", cursor.getInt(0) >= 20)
            }
            encrypted!!.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                assertFalse("Production schema must have no foreign-key violations", cursor.moveToFirst())
            }
        } finally {
            encrypted?.close()
            key?.fill(0)
            helper?.close()
            liveDatabase.delete()
            File(liveDatabase.path + "-wal").delete()
            File(liveDatabase.path + "-shm").delete()
            File(liveDatabase.path + ".sqlcipher-migration-staging").delete()
            File(liveDatabase.path + ".sqlite-migration-rollback").delete()
            File(context.noBackupFilesDir, "production-migration-$suffix.journal").delete()
            File(context.noBackupFilesDir, "production-migration-$suffix.journal.bak").delete()
            File(context.noBackupFilesDir, metadataName).delete()
            File(context.noBackupFilesDir, "$metadataName.bak").delete()
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
            }
        }
    }
}
