package com.hazem.dailystore.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqlCipherDatabaseRuntimeInstrumentedTest {

    @Test
    fun nativeLibraryInitializationIsIdempotentAndNewDatabaseKeyCanBeReloaded() {
        val fixture = Fixture()
        try {
            SqlCipherDatabaseRuntime.loadNativeLibrary()
            SqlCipherDatabaseRuntime.loadNativeLibrary()

            val first = SqlCipherDatabaseRuntime.getProductionDatabaseKey(
                fixture.vault,
                fixture.database
            )
            try {
                assertTrue(first.size == 32)
                fixture.database.writeBytes(byteArrayOf(1))
                val reloaded = SqlCipherDatabaseRuntime.getProductionDatabaseKey(
                    fixture.vault,
                    fixture.database
                )
                try {
                    assertArrayEquals(first, reloaded)
                } finally {
                    reloaded.fill(0)
                }
            } finally {
                first.fill(0)
            }
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun existingDatabaseWithoutVaultMetadataFailsClosedWithoutCreatingReplacementKey() {
        val fixture = Fixture()
        try {
            fixture.database.writeBytes("legacy plaintext marker".toByteArray())
            try {
                SqlCipherDatabaseRuntime.getProductionDatabaseKey(
                    fixture.vault,
                    fixture.database
                )
                fail("An existing database without key metadata must not receive a new key")
            } catch (_: DatabaseKeyVault.DatabaseKeyException) {
                // Expected: only the legacy migration coordinator may provision
                // a key while a plaintext database exists.
            }
            assertFalse(fixture.metadata.exists())
            assertTrue(fixture.database.isFile)
            assertTrue(fixture.database.readBytes().contentEquals("legacy plaintext marker".toByteArray()))
        } finally {
            fixture.cleanUp()
        }
    }

    private class Fixture {
        private val suffix = UUID.randomUUID().toString().replace("-", "")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = context.getDatabasePath("runtime-$suffix.db")
        private val metadataName = "runtime-key-$suffix.bin"
        val metadata = File(context.noBackupFilesDir, metadataName)
        private val alias = "tds_runtime_test_$suffix"
        val vault = DatabaseKeyVault(context, metadataName, alias)

        fun cleanUp() {
            listOf(
                database,
                File(database.path + "-wal"),
                File(database.path + "-shm"),
                File(database.path + "-journal"),
                metadata,
                File(metadata.path + ".bak")
            ).forEach { it.delete() }
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            }
        }
    }
}
