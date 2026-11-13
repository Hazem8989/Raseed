package com.hazem.dailystore.data

/**
 * Single entry point for initializing the modern SQLCipher Android runtime.
 *
 * The production AppDatabase obtains its key through this runtime before its
 * SQLCipher helper is constructed. Existing plaintext SQLite files are first
 * migrated by SqlCipherLegacyMigrationCoordinator; a missing key for an
 * existing non-plaintext database is always a hard failure.
 */
internal object SqlCipherDatabaseRuntime {
    @Volatile
    private var nativeLibraryLoaded = false

    @Synchronized
    fun loadNativeLibrary() {
        if (nativeLibraryLoaded) return
        System.loadLibrary("sqlcipher")
        nativeLibraryLoaded = true
    }

    /**
     * Returns a caller-owned 256-bit key only when the vault policy permits it.
     *
     * An existing database without recoverable key metadata is rejected by the
     * vault. In particular, this method must not be used to provision the key
     * for a legacy plaintext database; that is exclusively the migration
     * coordinator's responsibility.
     */
    fun getProductionDatabaseKey(
        keyVault: DatabaseKeyVault,
        databaseFile: java.io.File
    ): ByteArray {
        loadNativeLibrary()
        return keyVault.getOrCreateDatabaseKey(databaseExists = databaseFile.exists())
    }

    internal fun resetLoadedStateForTests() {
        // System.loadLibrary is process-scoped and cannot be unloaded. This
        // reset only permits tests to exercise the guarded call path again.
        nativeLibraryLoaded = false
    }
}
