package com.hazem.dailystore.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Versioned container for a SQLCipher database snapshot and its password-wrapped
 * database key. The database payload remains SQLCipher-encrypted; this container
 * never writes plaintext key material.
 *
 * Restoring extracts the encrypted database bytes and returns the recovered key.
 * The caller must open the staged database with SQLCipher and run integrity checks
 * before importing the key into the destination device's DatabaseKeyVault.
 */
internal object SqlCipherBackupContainer {
    private val magic = byteArrayOf(
        0x54, 0x44, 0x53, 0x42, 0x4B, 0x55, 0x50, 0x31 // TDSBKUP1
    )

    private const val VERSION = 1
    private const val ENVELOPE_BYTES = 92
    private const val HEADER_BYTES = 8 + 4 + 4 + 8
    private const val MAX_DATABASE_BYTES = 512L * 1024L * 1024L

    /** Writes a length-delimited container; [databaseSnapshot] must already be SQLCipher encrypted. */
    fun write(
        databaseSnapshot: File,
        databaseKey: ByteArray,
        password: CharArray,
        destination: OutputStream
    ) {
        require(databaseSnapshot.isFile && databaseSnapshot.length() in 1..MAX_DATABASE_BYTES) {
            "Invalid SQLCipher database snapshot"
        }
        require(databaseKey.size == 32) { "SQLCipher key must be 256 bits" }
        val envelope = BackupKeyEnvelope.wrap(databaseKey, password)
        require(envelope.size == ENVELOPE_BYTES) { "Unexpected key-envelope size" }

        val output = DataOutputStream(destination)
        output.write(magic)
        output.writeInt(VERSION)
        output.writeInt(envelope.size)
        output.writeLong(databaseSnapshot.length())
        output.write(envelope)
        databaseSnapshot.inputStream().use { input -> input.copyTo(output) }
        output.flush()
    }

    /**
     * Extracts an encrypted snapshot and returns its recovered 256-bit key.
     * On every failure the partial target is deleted. The caller owns and must
     * clear the returned key after importing/opening the database.
     */
    fun extract(
        source: InputStream,
        target: File,
        password: CharArray
    ): ByteArray {
        require(!target.exists() || target.isFile) { "Restore target is not a regular file" }
        var recoveredKey: ByteArray? = null
        try {
            val input = DataInputStream(source)
            val foundMagic = ByteArray(magic.size).also(input::readFully)
            require(foundMagic.contentEquals(magic)) { "Unknown backup container" }
            require(input.readInt() == VERSION) { "Unsupported backup container version" }
            val envelopeLength = input.readInt()
            require(envelopeLength == ENVELOPE_BYTES) { "Invalid key-envelope length" }
            val databaseLength = input.readLong()
            require(databaseLength in 1..MAX_DATABASE_BYTES) { "Invalid database payload length" }

            val envelope = ByteArray(envelopeLength).also(input::readFully)
            recoveredKey = BackupKeyEnvelope.unwrap(envelope, password)

            FileOutputStream(target, false).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = databaseLength
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count < 0) throw EOFException("Truncated SQLCipher database payload")
                    output.write(buffer, 0, count)
                    remaining -= count.toLong()
                }
                output.flush()
                output.fd.sync()
            }
            require(input.read() == -1) { "Unexpected trailing data in backup container" }
            require(target.isFile && target.length() == databaseLength) {
                "Extracted SQLCipher database size mismatch"
            }
            return recoveredKey.also { recoveredKey = null }
        } catch (error: Exception) {
            target.delete()
            recoveredKey?.fill(0)
            throw IllegalArgumentException("Backup container is invalid or cannot be recovered", error)
        }
    }
}
