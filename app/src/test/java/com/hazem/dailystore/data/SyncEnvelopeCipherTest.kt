package com.hazem.dailystore.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class SyncEnvelopeCipherTest {
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val wrongKey = SecretKeySpec(ByteArray(32) { (it + 77).toByte() }, "AES")
    private val context = SyncEnvelopeCipher.PeerContext(
        storeId = "store-01",
        senderDeviceId = "secondary-01",
        recipientDeviceId = "primary-01",
        operationId = "operation-001"
    )
    private val plaintext = """{"entity":"SALES_INVOICE","amount":1250}""".toByteArray()

    @Test fun encryptDecryptRoundTrip() {
        val frame = SyncEnvelopeCipher.encrypt(plaintext, key, context)
        assertArrayEquals(plaintext, SyncEnvelopeCipher.decrypt(frame, key, context))
    }

    @Test fun eachEncryptionUsesFreshNonce() {
        val first = SyncEnvelopeCipher.encrypt(plaintext, key, context)
        val second = SyncEnvelopeCipher.encrypt(plaintext, key, context)
        assertNotEquals(first.take(13), second.take(13))
    }

    @Test fun wrongKeyIsRejected() {
        val frame = SyncEnvelopeCipher.encrypt(plaintext, key, context)
        assertNull(SyncEnvelopeCipher.decrypt(frame, wrongKey, context))
    }

    @Test fun modifiedCiphertextIsRejected() {
        val frame = SyncEnvelopeCipher.encrypt(plaintext, key, context)
        val altered = frame.copyOf().also { it[it.lastIndex - 2] = (it[it.lastIndex - 2].toInt() xor 0x40).toByte() }
        assertNull(SyncEnvelopeCipher.decrypt(altered, key, context))
    }

    @Test fun peerOrOperationIdentityMismatchIsRejected() {
        val frame = SyncEnvelopeCipher.encrypt(plaintext, key, context)
        val wrongRecipient = context.copy(recipientDeviceId = "other-primary")
        val wrongOperation = context.copy(operationId = "operation-002")
        assertNull(SyncEnvelopeCipher.decrypt(frame, key, wrongRecipient))
        assertNull(SyncEnvelopeCipher.decrypt(frame, key, wrongOperation))
    }

    @Test fun malformedAndUnsupportedFramesAreRejected() {
        assertNull(SyncEnvelopeCipher.decrypt(byteArrayOf(1, 2, 3), key, context))
        val unsupported = SyncEnvelopeCipher.encrypt(plaintext, key, context).also { it[0] = 1 }
        assertNull(SyncEnvelopeCipher.decrypt(unsupported, key, context))
    }

    @Test fun derivedEncryptionKeyIsSeparatedByDirection() {
        val forward = SyncEnvelopeCipher.encrypt(plaintext, key, context)
        val reverseContext = context.copy(
            senderDeviceId = context.recipientDeviceId,
            recipientDeviceId = context.senderDeviceId
        )
        assertNull(SyncEnvelopeCipher.decrypt(forward, key, reverseContext))
    }

    @Test fun storeAndSenderIdentityAreAuthenticated() {
        val frame = SyncEnvelopeCipher.encrypt(plaintext, key, context)
        assertNull(SyncEnvelopeCipher.decrypt(frame, key, context.copy(storeId = "other-store")))
        assertNull(SyncEnvelopeCipher.decrypt(frame, key, context.copy(senderDeviceId = "spoofed-device")))
    }
}
