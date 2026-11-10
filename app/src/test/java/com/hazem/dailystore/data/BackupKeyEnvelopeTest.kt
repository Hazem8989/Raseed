package com.hazem.dailystore.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupKeyEnvelopeTest {
    private val key = ByteArray(32) { index -> (index * 7 + 3).toByte() }
    private val password = "Store-Recovery-2026".toCharArray()

    @Test
    fun wrapsAndRecoversKeyWithCorrectPasswordUsingVersionTwoSha256() {
        val envelope = BackupKeyEnvelope.wrap(key, password)
        assertEquals(2, readInt(envelope, 8))
        assertArrayEquals(key, BackupKeyEnvelope.unwrap(envelope, password))
        assertNotEquals(key.toList(), envelope.toList())
    }

    @Test
    fun readsLegacyVersionOneSha1BackupEnvelopes() {
        val legacy = createLegacyV1Envelope(key, password)
        assertEquals(1, readInt(legacy, 8))
        assertArrayEquals(key, BackupKeyEnvelope.unwrap(legacy, password))
    }

    @Test
    fun usesFreshSaltAndIvForEachEnvelope() {
        val first = BackupKeyEnvelope.wrap(key, password)
        val second = BackupKeyEnvelope.wrap(key, password)
        assertNotEquals(first.toList(), second.toList())
        assertArrayEquals(key, BackupKeyEnvelope.unwrap(first, password))
        assertArrayEquals(key, BackupKeyEnvelope.unwrap(second, password))
    }

    @Test
    fun rejectsWrongPassword() {
        val envelope = BackupKeyEnvelope.wrap(key, password)
        assertThrows(IllegalArgumentException::class.java) {
            BackupKeyEnvelope.unwrap(envelope, "Different-Recovery-2026".toCharArray())
        }
    }

    @Test
    fun rejectsTamperedEnvelope() {
        val envelope = BackupKeyEnvelope.wrap(key, password)
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 0x40).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            BackupKeyEnvelope.unwrap(envelope, password)
        }
    }

    @Test
    fun rejectsMalformedAndUnsupportedEnvelope() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupKeyEnvelope.unwrap(byteArrayOf(1, 2, 3), password)
        }
        val envelope = BackupKeyEnvelope.wrap(key, password)
        // Version is a big-endian Int at bytes 8..11.
        envelope[11] = (envelope[11].toInt() xor 0x01).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            BackupKeyEnvelope.unwrap(envelope, password)
        }

        val invalidKdfParameters = BackupKeyEnvelope.wrap(key, password)
        // Iteration count is the big-endian Int at bytes 12..15.
        for (index in 12..15) invalidKdfParameters[index] = 0
        assertThrows(IllegalArgumentException::class.java) {
            BackupKeyEnvelope.unwrap(invalidKdfParameters, password)
        }

        val invalidMagic = BackupKeyEnvelope.wrap(key, password)
        invalidMagic[0] = (invalidMagic[0].toInt() xor 0x01).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            BackupKeyEnvelope.unwrap(invalidMagic, password)
        }
    }

    @Test
    fun rejectsNon256BitKeysAndWeakPasswords() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupKeyEnvelope.wrap(ByteArray(16), password)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupKeyEnvelope.wrap(key, "short".toCharArray())
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    /** Builds a genuine v1 fixture to protect backward restore compatibility. */
    private fun createLegacyV1Envelope(databaseKey: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(12) { (it + 11).toByte() }
        val iterations = 310_000
        val header = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(byteArrayOf(0x54, 0x44, 0x53, 0x4B, 0x45, 0x59, 0x30, 0x31))
                output.writeInt(1)
                output.writeInt(iterations)
                output.write(salt)
                output.write(iv)
            }
        }.toByteArray()
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val derived = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            header + cipher.doFinal(databaseKey)
        } finally {
            derived.fill(0)
        }
    }
}
