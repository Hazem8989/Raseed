package com.hazem.dailystore.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.spec.PBEKeySpec
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Versioned, authenticated envelope for recovering a SQLCipher database key
 * from a password-protected backup on a different Android device.
 *
 * Version 2 uses PBKDF2-HMAC-SHA256 and a single 256-bit output block. Version 1
 * remains readable so backups already created by released builds stay restorable.
 * The database key is never stored in plaintext in the envelope.
 */
internal object BackupKeyEnvelope {
    private val random = SecureRandom()

    private val magic = byteArrayOf(
        0x54, 0x44, 0x53, 0x4B, 0x45, 0x59, 0x30, 0x31 // TDSKEY01
    )

    private const val LEGACY_VERSION = 1
    private const val VERSION = 2
    // OWASP's current PBKDF2-HMAC-SHA256 baseline is 600,000 iterations.
    // Keep the legacy v1 range separate so existing backups remain readable.
    private const val ITERATIONS = 600_000
    private const val LEGACY_MIN_ITERATIONS = 200_000
    private const val LEGACY_MAX_ITERATIONS = 500_000
    private const val MIN_ITERATIONS = 600_000
    private const val MAX_ITERATIONS = 1_000_000
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
        val header = header(salt, iv, ITERATIONS, VERSION)
        val derived = deriveSha256(password, salt, ITERATIONS)
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
        val version = input.readInt()
        require(version == LEGACY_VERSION || version == VERSION) {
            "Unsupported database-key envelope version"
        }
        val iterations = input.readInt()
        val validIterations = if (version == LEGACY_VERSION) {
            iterations in LEGACY_MIN_ITERATIONS..LEGACY_MAX_ITERATIONS
        } else {
            iterations in MIN_ITERATIONS..MAX_ITERATIONS
        }
        require(validIterations) { "Invalid key-derivation parameters" }
        val salt = ByteArray(SALT_BYTES).also(input::readFully)
        val iv = ByteArray(IV_BYTES).also(input::readFully)
        val header = envelope.copyOfRange(0, HEADER_BYTES)
        val encryptedKey = envelope.copyOfRange(HEADER_BYTES, envelope.size)
        val derived = if (version == LEGACY_VERSION) {
            deriveLegacySha1(password, salt, iterations)
        } else {
            deriveSha256(password, salt, iterations)
        }
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

    private fun header(
        salt: ByteArray,
        iv: ByteArray,
        iterations: Int,
        version: Int
    ): ByteArray =
        ByteArrayOutputStream(HEADER_BYTES).also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(magic)
                out.writeInt(version)
                out.writeInt(iterations)
                out.write(salt)
                out.write(iv)
            }
        }.toByteArray()

    /**
     * PBKDF2-HMAC-SHA256. SHA-256 produces exactly the 32 bytes required for the
     * AES-256 wrapping key in one PBKDF2 block (legacy SHA-1 needed two blocks).
     * Mac uses the platform HMAC provider, avoiding the slow pure-Java SHA-1
     * SecretKeyFactory path observed on the Android emulator.
     */
    private fun deriveSha256(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        // Use the platform PBKDF2 implementation. Calling Mac.doFinal() once per
        // round crosses the Android Conscrypt/JNI boundary 600,000 times; the
        // coordinator ART dump showed the instrumentation thread spending its
        // entire 20-minute window inside that call. SecretKeyFactory performs
        // the complete PBKDF2 operation in the provider and preserves the
        // PBKDF2-HMAC-SHA256 output format used by existing v2 envelopes.
        val spec = PBEKeySpec(password, salt, iterations, KEY_BYTES * 8)
        return try {
            val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
            require(derived.size == KEY_BYTES) { "Invalid PBKDF2-SHA256 output size" }
            derived
        } finally {
            spec.clearPassword()
        }
    }

    /** Reads legacy version-1 backups without changing their original KDF semantics. */
    private fun deriveLegacySha1(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
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
