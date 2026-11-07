package com.hazem.dailystore.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlCipherBackupContainerTest {
    private val key = ByteArray(32) { (it * 11 + 5).toByte() }
    private val password = "Store-Recovery-2026".toCharArray()

    @Test
    fun roundTripsEncryptedPayloadAndRecoversDatabaseKey() {
        val source = tempFile("sqlcipher-snapshot").apply {
            writeBytes(ByteArray(4096) { (it * 3).toByte() })
        }
        val restored = tempFile("sqlcipher-restored")
        try {
            val container = ByteArrayOutputStream()
            SqlCipherBackupContainer.write(source, key, password, container)
            val recovered = SqlCipherBackupContainer.extract(
                ByteArrayInputStream(container.toByteArray()), restored, password
            )
            try {
                assertArrayEquals(key, recovered)
                assertArrayEquals(source.readBytes(), restored.readBytes())
            } finally {
                recovered.fill(0)
            }
        } finally {
            source.delete()
            restored.delete()
        }
    }

    @Test
    fun rejectsWrongPasswordWithoutLeavingPartialDatabase() {
        val source = tempFile("sqlcipher-snapshot").apply { writeBytes(ByteArray(2048) { 7 }) }
        val restored = tempFile("sqlcipher-restored")
        try {
            val container = ByteArrayOutputStream()
            SqlCipherBackupContainer.write(source, key, password, container)
            assertThrows(IllegalArgumentException::class.java) {
                SqlCipherBackupContainer.extract(
                    ByteArrayInputStream(container.toByteArray()), restored,
                    "Wrong-Recovery-2026".toCharArray()
                )
            }
            assertFalse(restored.exists())
        } finally {
            source.delete()
            restored.delete()
        }
    }

    @Test
    fun rejectsTruncatedPayloadAndRemovesPartialFile() {
        val source = tempFile("sqlcipher-snapshot").apply { writeBytes(ByteArray(4096) { 9 }) }
        val restored = tempFile("sqlcipher-restored")
        try {
            val container = ByteArrayOutputStream()
            SqlCipherBackupContainer.write(source, key, password, container)
            val truncated = container.toByteArray().copyOf(container.size() - 17)
            assertThrows(IllegalArgumentException::class.java) {
                SqlCipherBackupContainer.extract(ByteArrayInputStream(truncated), restored, password)
            }
            assertFalse(restored.exists())
        } finally {
            source.delete()
            restored.delete()
        }
    }

    @Test
    fun rejectsTrailingBytesAndRemovesExtractedFile() {
        val source = tempFile("sqlcipher-snapshot").apply { writeBytes(ByteArray(128) { 4 }) }
        val restored = tempFile("sqlcipher-restored")
        try {
            val container = ByteArrayOutputStream()
            SqlCipherBackupContainer.write(source, key, password, container)
            val tampered = container.toByteArray() + byteArrayOf(1)
            assertThrows(IllegalArgumentException::class.java) {
                SqlCipherBackupContainer.extract(ByteArrayInputStream(tampered), restored, password)
            }
            assertFalse(restored.exists())
        } finally {
            source.delete()
            restored.delete()
        }
    }

    @Test
    fun rejectsInvalidHeaderAndPayloadLength() {
        val source = tempFile("sqlcipher-snapshot").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val container = ByteArrayOutputStream()
            SqlCipherBackupContainer.write(source, key, password, container)
            val badMagic = container.toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
            assertThrows(IllegalArgumentException::class.java) {
                SqlCipherBackupContainer.extract(ByteArrayInputStream(badMagic), tempFile("bad"), password)
            }
            val badLength = container.toByteArray().also { it[23] = 0x7f }
            assertThrows(IllegalArgumentException::class.java) {
                SqlCipherBackupContainer.extract(ByteArrayInputStream(badLength), tempFile("bad2"), password)
            }
        } finally {
            source.delete()
        }
    }

    private fun tempFile(prefix: String): File =
        Files.createTempFile(prefix, ".db").toFile().apply { delete() }
}
