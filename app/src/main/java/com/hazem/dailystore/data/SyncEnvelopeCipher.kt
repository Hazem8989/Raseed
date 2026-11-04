package com.hazem.dailystore.data

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated encryption for peer-to-peer sync frames.
 *
 * The caller must supply a per-store/per-peer AES key obtained from the
 * authenticated pairing flow. Identity and operation metadata are bound as
 * GCM associated data and are therefore authenticated but not encrypted.
 */
object SyncEnvelopeCipher {
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val random = SecureRandom()

    data class PeerContext(
        val storeId: String,
        val senderDeviceId: String,
        val recipientDeviceId: String,
        val operationId: String
    ) {
        init {
            require(storeId.isNotBlank())
            require(senderDeviceId.isNotBlank())
            require(recipientDeviceId.isNotBlank())
            require(senderDeviceId != recipientDeviceId)
            require(operationId.isNotBlank())
        }
    }

    /** Wire format: version(1) || nonce(12) || ciphertext || GCM tag(16). */
    fun encrypt(plaintext: ByteArray, key: SecretKey, context: PeerContext): ByteArray {
        require(plaintext.isNotEmpty())
        require(key.algorithm.equals("AES", ignoreCase = true))
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(associatedData(context))
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(1 + NONCE_BYTES + ciphertext.size)
            .put(VERSION)
            .put(nonce)
            .put(ciphertext)
            .array()
    }

    /** Returns null for malformed frames, wrong keys, or any authentication failure. */
    fun decrypt(frame: ByteArray, key: SecretKey, context: PeerContext): ByteArray? {
        if (frame.size < 1 + NONCE_BYTES + TAG_BYTES || frame[0] != VERSION) return null
        if (!key.algorithm.equals("AES", ignoreCase = true)) return null
        return runCatching {
            val nonce = frame.copyOfRange(1, 1 + NONCE_BYTES)
            val ciphertext = frame.copyOfRange(1 + NONCE_BYTES, frame.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(associatedData(context))
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }

    private fun associatedData(context: PeerContext): ByteArray {
        val fields = listOf(
            "the-daily-shop-sync",
            VERSION.toString(),
            context.storeId,
            context.senderDeviceId,
            context.recipientDeviceId,
            context.operationId
        )
        val canonical = fields.joinToString(separator = "\u0000")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    }
}
