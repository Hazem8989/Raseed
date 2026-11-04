package com.hazem.dailystore.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class SyncDeviceAuthenticatorTest {
    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private val context = SyncDeviceAuthenticator.SigningContext(
        storeId = "store-01",
        senderDeviceId = "secondary-01",
        recipientDeviceId = "primary-01",
        operationId = "operation-01"
    )
    private val frame = """{"ciphertext":"opaque-frame"}""".toByteArray()

    @Test fun signatureVerifiesOnlyWithEnrolledPublicKey() {
        val pair = keyPair()
        val signature = SyncDeviceAuthenticator.sign(pair.private, context, frame)
        assertTrue(SyncDeviceAuthenticator.verify(pair.public.encoded, context, frame, signature))
        assertFalse(SyncDeviceAuthenticator.verify(keyPair().public.encoded, context, frame, signature))
    }

    @Test fun tamperedFrameIsRejected() {
        val pair = keyPair()
        val signature = SyncDeviceAuthenticator.sign(pair.private, context, frame)
        val changed = frame.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertFalse(SyncDeviceAuthenticator.verify(pair.public.encoded, context, changed, signature))
    }

    @Test fun signatureIsBoundToStoreSenderRecipientAndOperation() {
        val pair = keyPair()
        val signature = SyncDeviceAuthenticator.sign(pair.private, context, frame)
        assertFalse(SyncDeviceAuthenticator.verify(pair.public.encoded,
            context.copy(storeId = "other-store"), frame, signature))
        assertFalse(SyncDeviceAuthenticator.verify(pair.public.encoded,
            context.copy(senderDeviceId = "spoofed-device"), frame, signature))
        assertFalse(SyncDeviceAuthenticator.verify(pair.public.encoded,
            context.copy(recipientDeviceId = "other-primary"), frame, signature))
        assertFalse(SyncDeviceAuthenticator.verify(pair.public.encoded,
            context.copy(operationId = "operation-02"), frame, signature))
    }

    @Test fun publicKeyFingerprintIsStableAndDistinctAcrossDevices() {
        val first = keyPair()
        val second = keyPair()
        val fingerprint = SyncDeviceAuthenticator.fingerprint(first.public.encoded)
        assertTrue(fingerprint.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(fingerprint, SyncDeviceAuthenticator.fingerprint(second.public.encoded))
    }

    @Test fun malformedPublicKeyIsRejectedWithoutThrowing() {
        assertFalse(SyncDeviceAuthenticator.verify(
            byteArrayOf(1, 2, 3), context, frame, byteArrayOf(4)
        ))
    }
}
