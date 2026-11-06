package com.hazem.dailystore.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Versioned, authenticated envelope for recovering a SQLCipher database key
 * from a password-protected backup on a different Android device.
 *
 * The envelope contains no plaintext database key. The caller must separately
 * encrypt/authenticate the database payload and store this envelope alongside
 * it in the backup container.
 */
internal object BackupKeyEnvelope {
    private val random = SecureRandom()

    private val magic = byteArrayOf(
        0x54, 0x44, 0x53, 0x4B, 0x45, 0x59, 0x30, 0x31 // TDSKEY01
    )

    private const val VERSION = 1
    private const val ITERATIONS = 310_000
    private const val MIN_ITERATIONS = 200_000
    private const val MAX_ITERATIONS = 500_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val TAG_BITS = 128
    private const val MIN_PASSWORD_CHARS = 10
    private const val HEADER_BYTES = 8 + 4 + 4 + SALT_BYTES + IV_BYTES
    private const val ENVELOPE_BYTES = HEADER_BYTES + KEY_BYTES + TAG_BITS / 8

    /** Wraps exactly one 256-bit database key. The caller retains ownership of key. */
    fun wrap(databaseKey: ByteArray, password: CharArray): ByteArray {
        require(databaseKey.size == KEY_BYTES) { "SQLCipher key must be 256 bits" }
        validatePassword(password)

        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = header(salt, iv, ITERATIONS)
        val derived = derive(password, salt, ITERATIONS)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            header + cipher.doFinal(databaseKey)
        } finally {
            derived.fill(0)
        }
    }

    /** Unwraps the database key. Authentication failure never returns partial key material. */
    fun unwrap(envelope: ByteArray, password: CharArray): ByteArray {
        validatePassword(password)
        require(envelope.size == ENVELOPE_BYTES) { "Invalid database-key envelope size" }

        val input = DataInputStream(ByteArrayInputStream(envelope))
        val foundMagic = ByteArray(magic.size).also(input::readFully)
        require(foundMagic.contentEquals(magic)) { "Unknown database-key envelope" }
        require(input.readInt() == VERSION) { "Unsupported database-key envelope version" }
        val iterations = input.readInt()
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Invalid key-derivation parameters" }
        val salt = ByteArray(SALT_BYTES).also(input::readFully)
        val iv = ByteArray(IV_BYTES).also(input::readFully)
        val header = envelope.copyOfRange(0, HEADER_BYTES)
        val encryptedKey = envelope.copyOfRange(HEADER_BYTES, envelope.size)
        val derived = derive(password, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            cipher.doFinal(encryptedKey).also {
                require(it.size == KEY_BYTES) { "Invalid recovered SQLCipher key size" }
            }
        } catch (error: Exception) {
            throw IllegalArgumentException("Backup password is incorrect or key envelope is damaged", error)
        } finally {
            derived.fill(0)
        }
    }

    private fun header(salt: ByteArray, iv: ByteArray, iterations: Int): ByteArray =
        ByteArrayOutputStream(HEADER_BYTES).also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(magic)
                out.writeInt(VERSION)
                out.writeInt(iterations)
                out.write(salt)
                out.write(iv)
            }
        }.toByteArray()

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun validatePassword(password: CharArray) {
        require(password.size >= MIN_PASSWORD_CHARS) {
            "Backup password must contain at least $MIN_PASSWORD_CHARS characters"
        }
    }
}
