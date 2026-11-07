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
