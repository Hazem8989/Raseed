package com.hazem.dailystore.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SyncKeyDerivationTest {
    private val credential = ByteArray(32) { (it + 1).toByte() }

    @Test fun sameInputsProduceSameDerivedKey() {
        val first = SyncKeyDerivation.derive(credential, "test-domain".toByteArray())
        val second = SyncKeyDerivation.derive(credential, "test-domain".toByteArray())
        assertArrayEquals(first, second)
    }

    @Test fun distinctPurposeLabelsProduceIndependentKeys() {
        val encryption = SyncKeyDerivation.derive(credential, "AES-GCM-V2".toByteArray())
        val authentication = SyncKeyDerivation.derive(credential, "ENVELOPE-HMAC-V2".toByteArray())
        assertFalse(encryption.contentEquals(authentication))
    }

    @Test fun distinctPeerDirectionsProduceIndependentKeys() {
        val forward = SyncKeyDerivation.derive(credential, "store|secondary|primary".toByteArray())
        val reverse = SyncKeyDerivation.derive(credential, "store|primary|secondary".toByteArray())
        assertFalse(forward.contentEquals(reverse))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCredentialBelow256Bits() {
        SyncKeyDerivation.derive(ByteArray(16), "test".toByteArray())
    }
}
