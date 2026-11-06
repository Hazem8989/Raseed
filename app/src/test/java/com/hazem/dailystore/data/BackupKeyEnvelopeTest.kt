package com.hazem.dailystore.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupKeyEnvelopeTest {
    private val key = ByteArray(32) { index -> (index * 7 + 3).toByte() }
    private val password = "Store-Recovery-2026".toCharArray()

    @Test
    fun wrapsAndRecoversKeyWithCorrectPassword() {
        val envelope = BackupKeyEnvelope.wrap(key, password)
        assertArrayEquals(key, BackupKeyEnvelope.unwrap(envelope, password))
        assertNotEquals(key.toList(), envelope.toList())
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
        // Version is a big-endian Int at bytes 8..11. Mutate its least
        // significant byte; changing byte 8 to zero leaves version 1 unchanged.
        envelope[11] = (envelope[11].toInt() xor 0x01).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            BackupKeyEnvelope.unwrap(envelope, password)
        }

        val invalidKdfParameters = BackupKeyEnvelope.wrap(key, password)
        // Iteration count is the big-endian Int at bytes 12..15.
        invalidKdfParameters[15] = 1
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
}
