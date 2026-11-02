package com.hazem.dailystore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncEnvelopeSecurityTest {
    private val secret = ByteArray(32) { (it + 1).toByte() }

    private fun create(payload: String = """{"z":3,"a":{"y":2,"x":1}}""") =
        SyncEnvelopeSecurity.create(
            "store-01", "op-001", 1L, "device-primary", "admin-01",
            "CREATE", "SALES_INVOICE", "invoice-01", 0L, payload,
            "2026-11-02T19:00:00Z", secret
        )

    @Test fun canonicalHashIgnoresJsonObjectKeyOrder() {
        val first = create()
        val second = create("""{"a":{"x":1,"y":2},"z":3}""")
        assertEquals(first.payloadJson, second.payloadJson)
        assertEquals(first.payloadSha256, second.payloadSha256)
        assertEquals(first.authenticationTagHex, second.authenticationTagHex)
    }

    @Test fun hmacAndPayloadSurviveEncodeDecodeRoundTrip() {
        val signed = create()
        assertTrue(SyncEnvelopeSecurity.verify(signed, secret))
        val decoded = SyncEnvelopeSecurity.decode(SyncEnvelopeSecurity.encode(signed))
        assertEquals(signed, decoded)
        assertTrue(SyncEnvelopeSecurity.verify(decoded, secret))
    }

    @Test fun rejectsPayloadTamperingAndWrongCredential() {
        val signed = create()
        assertFalse(SyncEnvelopeSecurity.verify(signed.copy(payloadJson = """{"amount":999}"""), secret))
        assertFalse(SyncEnvelopeSecurity.verify(signed, ByteArray(32) { 99 }))
    }

    @Test fun rejectsSensitiveEntityTypesAndShortSecrets() {
        try {
            SyncEnvelopeSecurity.create(
                "store-01", "op-2", 2L, "device-primary", "admin-01",
                "UPDATE", "USERS", "user-02", 0L, """{"password_hash":"secret"}""",
                "2026-11-02T19:00:00Z", secret
            )
            fail("User credentials must never be synchronized as a business entity")
        } catch (_: IllegalArgumentException) { }
        try {
            SyncEnvelopeSecurity.create(
                "store-01", "op-3", 3L, "device-primary", "admin-01",
                "UPDATE", "PRODUCT", "product-01", 0L, """{"name":"Item"}""",
                "2026-11-02T19:00:00Z", ByteArray(16)
            )
            fail("Short secrets must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun rejectsNonCanonicalPayloadAndAlteredMetadata() {
        val signed = create()
        assertFalse(SyncEnvelopeSecurity.verify(
            signed.copy(payloadJson = """{"a":{"x":1,"y":2},"z":3}"""), secret
        ))
        assertFalse(SyncEnvelopeSecurity.verify(signed.copy(sequenceNumber = 2L), secret))
        assertFalse(SyncEnvelopeSecurity.verify(signed.copy(entityType = "USERS"), secret))
    }
}
