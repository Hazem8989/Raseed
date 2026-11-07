package com.hazem.dailystore.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseKeyVaultInstrumentedTest {

    @Test
    fun createsRandom256BitKeyAndReloadsSameKeyWhenDatabaseExists() {
        val fixture = Fixture()
        try {
            val first = fixture.vault.getOrCreateDatabaseKey(databaseExists = false)
            val second = fixture.vault.getOrCreateDatabaseKey(databaseExists = true)
            try {
                assertEquals(32, first.size)
                assertArrayEquals(first, second)
                assertTrue(fixture.metadata.exists())
                assertTrue(fixture.metadata.canonicalPath.startsWith(fixture.context.noBackupFilesDir.canonicalPath))
            } finally {
                first.fill(0)
                second.fill(0)
            }
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun importsRecoveredKeyAndWrapsItWithThisDevicesKeystoreKey() {
        val fixture = Fixture()
        val recoveredKey = ByteArray(32) { (it * 13 + 2).toByte() }
        try {
            fixture.vault.importDatabaseKey(recoveredKey, databaseExists = false)
            val loaded = fixture.vault.getOrCreateDatabaseKey(databaseExists = true)
            try {
                assertArrayEquals(recoveredKey, loaded)
                assertTrue(fixture.metadata.exists())
                assertTrue(hasKeystoreAlias(fixture.alias))
            } finally {
                loaded.fill(0)
            }
        } finally {
            recoveredKey.fill(0)
            fixture.cleanUp()
        }
    }

    @Test
    fun refusesToImportWhenDatabaseAlreadyExists() {
        val fixture = Fixture()
        try {
            assertThrows(DatabaseKeyVault.DatabaseKeyException::class.java) {
                fixture.vault.importDatabaseKey(ByteArray(32), databaseExists = true)
            }
            assertFalse(fixture.metadata.exists())
            assertFalse(hasKeystoreAlias(fixture.alias))
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun refusesToReplaceAnExistingLocalKey() {
        val fixture = Fixture()
        try {
            val original = fixture.vault.getOrCreateDatabaseKey(databaseExists = false)
            val replacement = ByteArray(32) { 0x5a }
            try {
                assertThrows(DatabaseKeyVault.DatabaseKeyException::class.java) {
                    fixture.vault.importDatabaseKey(replacement, databaseExists = false)
                }
                val loaded = fixture.vault.getOrCreateDatabaseKey(databaseExists = true)
                try {
                    assertArrayEquals(original, loaded)
                } finally {
                    loaded.fill(0)
                }
            } finally {
                original.fill(0)
                replacement.fill(0)
            }
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun refusesToCreateReplacementKeyWhenDatabaseExistsButMetadataIsMissing() {
        val fixture = Fixture()
        try {
            assertThrows(DatabaseKeyVault.DatabaseKeyException::class.java) {
                fixture.vault.getOrCreateDatabaseKey(databaseExists = true)
            }
            assertFalse(fixture.metadata.exists())
            assertFalse(hasKeystoreAlias(fixture.alias))
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun corruptedMetadataFailsClosedAndIsNotRewritten() {
        val fixture = Fixture()
        try {
            val key = fixture.vault.getOrCreateDatabaseKey(databaseExists = false)
            key.fill(0)
            val corrupted = fixture.metadata.readBytes().also {
                it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte()
            }
            fixture.metadata.writeBytes(corrupted)

            assertThrows(DatabaseKeyVault.DatabaseKeyException::class.java) {
                fixture.vault.getOrCreateDatabaseKey(databaseExists = true)
            }
            assertArrayEquals(corrupted, fixture.metadata.readBytes())
            assertTrue(hasKeystoreAlias(fixture.alias))
        } finally {
            fixture.cleanUp()
        }
    }

    @Test
    fun missingKeystoreKeyFailsClosedAndPreservesWrappedMetadata() {
        val fixture = Fixture()
        try {
            val key = fixture.vault.getOrCreateDatabaseKey(databaseExists = false)
            key.fill(0)
            val wrappedMetadata = fixture.metadata.readBytes()

            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.deleteEntry(fixture.alias)

            assertThrows(DatabaseKeyVault.DatabaseKeyException::class.java) {
                fixture.vault.getOrCreateDatabaseKey(databaseExists = true)
            }
            assertArrayEquals(wrappedMetadata, fixture.metadata.readBytes())
            assertFalse(hasKeystoreAlias(fixture.alias))
        } finally {
            fixture.cleanUp()
        }
    }

    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val suffix = UUID.randomUUID().toString().replace("-", "")
        val fileName = "db-key-test-$suffix.bin"
        val alias = "tds_db_key_test_$suffix"
        val metadata = File(context.noBackupFilesDir, fileName)
        val vault = DatabaseKeyVault(context, fileName, alias)

        fun cleanUp() {
            metadata.delete()
            File(metadata.path + ".bak").delete()
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            }
        }
    }

    private fun hasKeystoreAlias(alias: String): Boolean =
        KeyStore.getInstance("AndroidKeyStore").run {
            load(null)
            containsAlias(alias)
        }
}
