package com.hazem.dailystore.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local SQLCipher key storage. This class is deliberately not wired into
 * AppDatabase until encrypted migration and backup/restore are ready.
 *
 * The random database key is wrapped by a non-exportable Android Keystore AES-GCM
 * key. Metadata lives in noBackupFilesDir and is written atomically.
 */
class DatabaseKeyVault(
    context: Context,
    metadataFileName: String = DEFAULT_METADATA_FILE,
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) {
    private val appContext = context.applicationContext
    private val metadataFile = File(appContext.noBackupFilesDir, metadataFileName)
    private val atomicMetadata = AtomicFile(metadataFile)

    init {
        require(metadataFileName.isNotBlank() && File(metadataFileName).name == metadataFileName) {
            "Invalid database-key metadata filename"
        }
        require(keyAlias.isNotBlank())
    }

    /**
     * Returns the 256-bit database key. The caller owns the returned bytes and
     * should clear them after passing them to SQLCipher.
     *
     * databaseExists must reflect the actual live database file. If that file
     * exists without recoverable key metadata, this method fails closed.
     */
    @Synchronized
    fun getOrCreateDatabaseKey(databaseExists: Boolean): ByteArray {
        if (hasMetadata()) return decryptExistingKey()

        if (databaseExists) {
            throw DatabaseKeyException(
                "قاعدة البيانات موجودة لكن بيانات مفتاح التشفير مفقودة؛ أوقف الفتح ولا تنشئ قاعدة بديلة"
            )
        }

        // An interrupted first-time setup may leave an orphaned wrapping key.
        // With no database and no metadata there is no user data to unlock.
        deleteWrappingKeyIfPresent()
        val databaseKey = ByteArray(DATABASE_KEY_BYTES).also(SECURE_RANDOM::nextBytes)
        try {
            val wrappingKey = createWrappingKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val header = metadataHeader()
            cipher.updateAAD(header)
            val encryptedKey = cipher.doFinal(databaseKey)
            writeMetadata(header, cipher.iv, encryptedKey)
            return databaseKey
        } catch (error: Exception) {
            databaseKey.fill(0)
            throw DatabaseKeyException("تعذر إنشاء مفتاح قاعدة البيانات بأمان", error)
        }
    }

    /**
     * Creates or reloads the device-local key for a verified legacy plaintext
     * database migration. Unlike getOrCreateDatabaseKey(), this explicitly
     * permits key provisioning while the legacy database file exists.
     *
     * The caller must first validate that legacyDatabase is the intended
     * plaintext SQLite file and must keep it untouched until encrypted export,
     * parity checks, and the atomic replacement have all completed.
     */
    @Synchronized
    internal fun getOrCreateMigrationKey(legacyDatabase: File): ByteArray {
        require(legacyDatabase.isFile && legacyDatabase.length() > 0L) {
            "قاعدة SQLite القديمة غير موجودة أو فارغة؛ رُفض إنشاء مفتاح ترحيل"
        }
        if (hasMetadata()) return decryptExistingKey()

        // No encrypted database can be opened without metadata. An orphaned
        // wrapping alias from an interrupted first setup is therefore replaceable.
        deleteWrappingKeyIfPresent()
        val databaseKey = ByteArray(DATABASE_KEY_BYTES).also(SECURE_RANDOM::nextBytes)
        try {
            val wrappingKey = createWrappingKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val header = metadataHeader()
            cipher.updateAAD(header)
            val encryptedKey = cipher.doFinal(databaseKey)
            writeMetadata(header, cipher.iv, encryptedKey)
            encryptedKey.fill(0)

            val verified = decryptExistingKey()
            try {
                check(java.security.MessageDigest.isEqual(verified, databaseKey)) {
                    "فشل التحقق من مفتاح ترحيل قاعدة البيانات"
                }
            } finally {
                verified.fill(0)
            }
            return databaseKey
        } catch (error: Exception) {
            databaseKey.fill(0)
            throw DatabaseKeyException("تعذر إنشاء مفتاح آمن لترحيل قاعدة SQLite القديمة", error)
        }
    }

    /**
     * Imports a recovered SQLCipher key onto a new device by wrapping it with
     * this device's non-exportable Android Keystore key.
     *
     * This is allowed only before a live database exists. If key metadata is
     * already present, only an exact same-key retry is accepted; a different or
     * unreadable key is never replaced.
     */
    @Synchronized
    fun importDatabaseKey(databaseKey: ByteArray, databaseExists: Boolean) {
        require(databaseKey.size == DATABASE_KEY_BYTES) { "SQLCipher key must be 256 bits" }
        if (databaseExists) {
            throw DatabaseKeyException("لا يمكن استيراد مفتاح إلى جهاز يحتوي على قاعدة بيانات موجودة")
        }
        if (hasMetadata()) {
            // A restore may be retried after a process interruption between key
            // import and the atomic database-file rename. Accept only the exact
            // same key; never replace a different or unreadable local key.
            val existingKey = decryptExistingKey()
            try {
                if (java.security.MessageDigest.isEqual(existingKey, databaseKey)) return
                throw DatabaseKeyException("يوجد مفتاح قاعدة بيانات محلي مختلف؛ رُفض استبداله")
            } finally {
                existingKey.fill(0)
            }
        }

        // An orphaned alias can remain after an interrupted first-time setup.
        // With no database and no metadata, it is safe to replace that alias.
        deleteWrappingKeyIfPresent()
        try {
            val wrappingKey = createWrappingKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val header = metadataHeader()
            cipher.updateAAD(header)
            val encryptedKey = cipher.doFinal(databaseKey)
            writeMetadata(header, cipher.iv, encryptedKey)
        } catch (error: Exception) {
            throw DatabaseKeyException("تعذر استيراد مفتاح قاعدة البيانات إلى هذا الجهاز", error)
        }
    }

    /**
     * Loads an already-imported key for interrupted restore recovery without
     * creating or replacing any key. Null means no local key metadata exists.
     */
    @Synchronized
    internal fun getImportedDatabaseKeyForRestoreRecovery(): ByteArray? {
        if (!hasMetadata()) return null
        return decryptExistingKey()
    }

    /**
     * Returns the existing device-wrapped key record for a same-device restore
     * rollback journal. The returned bytes contain only AES-GCM ciphertext,
     * never the raw SQLCipher key. The caller must persist them in noBackupFilesDir.
     */
    @Synchronized
    internal fun snapshotWrappedKeyMetadata(): ByteArray {
        check(hasMetadata()) { "لا توجد بيانات مفتاح محلي لحفظها" }
        val verifiedKey = decryptExistingKey()
        verifiedKey.fill(0)
        return atomicMetadata.openRead().use { it.readBytes() }.also {
            require(it.size == HEADER_BYTES + IV_BYTES + DATABASE_KEY_BYTES + GCM_TAG_BYTES)
        }
    }

    /**
     * Replaces the wrapped database key only when the currently installed key
     * matches the expected key. The Android Keystore wrapping alias is retained,
     * allowing a journaled same-device restore to roll back without key export.
     */
    @Synchronized
    internal fun replaceDatabaseKeyForRestore(
        replacementKey: ByteArray,
        expectedCurrentKey: ByteArray
    ) {
        require(replacementKey.size == DATABASE_KEY_BYTES && expectedCurrentKey.size == DATABASE_KEY_BYTES)
        val current = decryptExistingKey()
        try {
            check(java.security.MessageDigest.isEqual(current, expectedCurrentKey)) {
                "تغير مفتاح قاعدة البيانات المحلية؛ أُوقفت الاستعادة"
            }
        } finally {
            current.fill(0)
        }
        val wrappingKey = loadExistingWrappingKey()
        val header = metadataHeader()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        cipher.updateAAD(header)
        val encrypted = cipher.doFinal(replacementKey)
        writeMetadata(header, cipher.iv, encrypted)
        encrypted.fill(0)
        val verified = decryptExistingKey()
        try {
            check(java.security.MessageDigest.isEqual(verified, replacementKey)) {
                "فشل التحقق من مفتاح قاعدة البيانات بعد الاستبدال"
            }
        } finally {
            verified.fill(0)
        }
    }

    /** Restores a previously captured wrapped-key record without changing the Keystore alias. */
    @Synchronized
    internal fun restoreWrappedKeyMetadata(snapshot: ByteArray) {
        val expectedSize = HEADER_BYTES + IV_BYTES + DATABASE_KEY_BYTES + GCM_TAG_BYTES
        require(snapshot.size == expectedSize) { "Invalid wrapped-key rollback snapshot" }
        val header = snapshot.copyOfRange(0, HEADER_BYTES)
        require(header.contentEquals(metadataHeader())) { "Unsupported wrapped-key rollback snapshot" }
        val iv = snapshot.copyOfRange(HEADER_BYTES, HEADER_BYTES + IV_BYTES)
        val encrypted = snapshot.copyOfRange(HEADER_BYTES + IV_BYTES, snapshot.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadExistingWrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(header)
        val recovered = cipher.doFinal(encrypted)
        try {
            require(recovered.size == DATABASE_KEY_BYTES) { "Invalid key in rollback snapshot" }
            writeMetadata(header, iv, encrypted)
            val verified = decryptExistingKey()
            try {
                check(java.security.MessageDigest.isEqual(verified, recovered)) {
                    "Wrapped-key rollback verification failed"
                }
            } finally {
                verified.fill(0)
            }
        } finally {
            recovered.fill(0)
        }
    }

    private fun hasMetadata(): Boolean {
        val backup = File(metadataFile.path + ".bak")
        return metadataFile.exists() || backup.exists()
    }

    private fun decryptExistingKey(): ByteArray {
        try {
            val packed = atomicMetadata.openRead().use { it.readBytes() }
            val expectedSize = HEADER_BYTES + IV_BYTES + DATABASE_KEY_BYTES + GCM_TAG_BYTES
            require(packed.size == expectedSize) { "Invalid database-key metadata size" }

            val header = packed.copyOfRange(0, HEADER_BYTES)
            require(header.contentEquals(metadataHeader())) { "Unsupported database-key metadata format" }

            val iv = packed.copyOfRange(HEADER_BYTES, HEADER_BYTES + IV_BYTES)
            val encrypted = packed.copyOfRange(HEADER_BYTES + IV_BYTES, packed.size)
            val wrappingKey = loadExistingWrappingKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(header)
            return cipher.doFinal(encrypted).also {
                require(it.size == DATABASE_KEY_BYTES) { "Invalid SQLCipher key length" }
            }
        } catch (error: Exception) {
            if (error is DatabaseKeyException) throw error
            throw DatabaseKeyException(
                "تعذر استعادة مفتاح قاعدة البيانات؛ لم يتم إنشاء مفتاح بديل أو تعديل القاعدة",
                error
            )
        }
    }

    private fun writeMetadata(header: ByteArray, iv: ByteArray, encryptedKey: ByteArray) {
        var stream: FileOutputStream? = null
        try {
            stream = atomicMetadata.startWrite()
            stream.write(header)
            stream.write(iv)
            stream.write(encryptedKey)
            stream.flush()
            stream.fd.sync()
            atomicMetadata.finishWrite(stream)
            stream = null
        } catch (error: Exception) {
            stream?.let(atomicMetadata::failWrite)
            throw error
        }
    }

    private fun loadExistingWrappingKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (store.getKey(keyAlias, null) as? SecretKey)
            ?: throw DatabaseKeyException("مفتاح Android Keystore المطلوب غير موجود")
    }

    private fun createWrappingKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun deleteWrappingKeyIfPresent() {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
    }

    private fun metadataHeader(): ByteArray = MAGIC + byteArrayOf(FORMAT_VERSION)

    class DatabaseKeyException(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    companion object {
        private const val DEFAULT_METADATA_FILE = "the_daily_shop_db_key_v1.bin"
        private const val DEFAULT_KEY_ALIAS = "the_daily_shop_db_wrap_aes_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DATABASE_KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val FORMAT_VERSION: Byte = 1
        private val MAGIC = byteArrayOf(0x54, 0x44, 0x53, 0x44, 0x42, 0x4B, 0x59, 0x31)
        private val SECURE_RANDOM = SecureRandom()
        private const val HEADER_BYTES = 9
    }
}
