package com.hazem.dailystore.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the secondary-device credential encrypted by a non-exportable Android Keystore key.
 * The plaintext credential is returned only to the active sync caller and is never written
 * to ordinary preferences or the SQLite database.
 */
class SyncCredentialVault(context: Context, private val testKeyProvider: (() -> SecretKey)? = null) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun save(deviceId: String, credential: ByteArray) {
        require(deviceId.isNotBlank() && credential.size >= 32)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(deviceId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(credential)
        val packed = cipher.iv + encrypted
        check(preferences.edit().putString(keyFor(deviceId), Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "تعذر حفظ بيانات اعتماد المزامنة بشكل آمن"
        }
    }

    fun load(deviceId: String): ByteArray? {
        if (deviceId.isBlank()) return null
        val encoded = preferences.getString(keyFor(deviceId), null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > GCM_IV_BYTES + GCM_TAG_BYTES)
            val iv = packed.copyOfRange(0, GCM_IV_BYTES)
            val encrypted = packed.copyOfRange(GCM_IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(deviceId.toByteArray(Charsets.UTF_8))
            cipher.doFinal(encrypted).also { require(it.size >= 32) }
        }.getOrNull()
    }

    fun remove(deviceId: String) {
        if (deviceId.isBlank()) return
        check(preferences.edit().remove(keyFor(deviceId)).commit()) {
            "تعذر حذف بيانات اعتماد الجهاز"
        }
    }

    private fun encryptionKey(): SecretKey {
        testKeyProvider?.let { return it() }
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
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

    private fun keyFor(deviceId: String): String = "credential_" +
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(deviceId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val PREFERENCES = "the_daily_shop_sync_credentials"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "the_daily_shop_sync_credential_aes_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    }
}
