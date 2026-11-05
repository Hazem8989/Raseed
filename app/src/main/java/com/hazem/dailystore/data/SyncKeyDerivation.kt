package com.hazem.dailystore.data

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 key separation for sync credentials.
 *
 * The pairing credential is input key material only. Distinct protocol labels
 * and context values derive independent keys for envelope authentication and
 * peer-direction AES-GCM encryption.
 */
internal object SyncKeyDerivation {
    private val salt = "THE_DAILY_SHOP_SYNC_HKDF_SALT_V1".toByteArray(Charsets.UTF_8)

    fun derive(inputKeyMaterial: ByteArray, info: ByteArray, outputLength: Int = 32): ByteArray {
        require(inputKeyMaterial.size >= 32) { "Sync credential must contain at least 256 bits" }
        require(outputLength in 1..(255 * HASH_LENGTH)) { "Invalid HKDF output length" }

        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val pseudoRandomKey = extract.doFinal(inputKeyMaterial)
        try {
            val output = ByteArray(outputLength)
            var previous = ByteArray(0)
            var offset = 0
            var counter = 1
            while (offset < outputLength) {
                val expand = Mac.getInstance("HmacSHA256")
                expand.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
                expand.update(previous)
                expand.update(info)
                expand.update(counter.toByte())
                val block = expand.doFinal()
                val count = minOf(block.size, outputLength - offset)
                block.copyInto(output, offset, 0, count)
                previous.fill(0)
                previous = block
                offset += count
                counter++
            }
            previous.fill(0)
            return output
        } finally {
            pseudoRandomKey.fill(0)
        }
    }

    private const val HASH_LENGTH = 32
}
