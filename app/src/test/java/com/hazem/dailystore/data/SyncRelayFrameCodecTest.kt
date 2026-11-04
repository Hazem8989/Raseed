package com.hazem.dailystore.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SyncRelayFrameCodecTest {
    private val store = UUID.randomUUID().toString()
    private val sender = UUID.randomUUID().toString()
    private val recipient = UUID.randomUUID().toString()
    private val operation = UUID.randomUUID().toString()

    private fun frame(from: String = sender, to: String = recipient, id: String = operation): String =
        JSONObject()
            .put("frame_version", 1)
            .put("store_id", store)
            .put("sender_device_id", from)
            .put("recipient_device_id", to)
            .put("operation_id", id)
            .put("ciphertext", "opaque-ciphertext")
            .put("signature_algorithm", "ECDSA_P256_SHA256")
            .put("sender_key_fingerprint", "a".repeat(64))
            .put("signature", "signature-bytes")
            .toString()

    @Test fun outgoingRelayRequestContainsOnlyOpaqueSignedFrameAndRouting() {
        val encoded = SyncRelayFrameCodec.encodeRelayRequest(store, sender, recipient, operation, frame())
        val json = JSONObject(encoded)
        assertEquals("relay", json.getString("type"))
        assertEquals(recipient, json.getString("to_device_id"))
        assertEquals(operation, json.getString("frame_id"))
        assertEquals(frame(), json.getString("ciphertext"))
        assertFalse(json.has("payload_json"))
    }

    @Test fun rejectsUnsignedOrMisroutedOutgoingFrame() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncRelayFrameCodec.encodeRelayRequest(store, sender, recipient, operation, frame(to = sender))
        }
        val unsigned = JSONObject(frame()).put("signature", "")
        assertThrows(IllegalArgumentException::class.java) {
            SyncRelayFrameCodec.encodeRelayRequest(store, sender, recipient, operation, unsigned.toString())
        }
    }

    @Test fun acceptsReadyOnlyForThisDeviceAndProtocol() {
        val ready = JSONObject().put("type", "ready").put("protocol", 1).put("device_id", recipient)
        assertSame(SyncRelayFrameCodec.ServerMessage.Ready,
            SyncRelayFrameCodec.decodeServerMessage(ready.toString(), recipient))
        ready.put("device_id", sender)
        assertThrows(IllegalArgumentException::class.java) {
            SyncRelayFrameCodec.decodeServerMessage(ready.toString(), recipient)
        }
    }

    @Test fun inboundFrameMustMatchRelaySenderLocalRecipientAndOperation() {
        val inbound = JSONObject()
            .put("type", "relay")
            .put("from_device_id", sender)
            .put("frame_id", operation)
            .put("ciphertext", frame())
        val decoded = SyncRelayFrameCodec.decodeServerMessage(inbound.toString(), recipient)
        assertTrue(decoded is SyncRelayFrameCodec.ServerMessage.Inbound)
        inbound.put("from_device_id", UUID.randomUUID().toString())
        assertThrows(IllegalArgumentException::class.java) {
            SyncRelayFrameCodec.decodeServerMessage(inbound.toString(), recipient)
        }
    }

    @Test fun deliveryNoticeIsNotBusinessAcknowledgement() {
        val forwarded = JSONObject().put("type", "forwarded").put("frame_id", operation).put("retryable", false)
        val decoded = SyncRelayFrameCodec.decodeServerMessage(forwarded.toString(), recipient)
        assertTrue(decoded is SyncRelayFrameCodec.ServerMessage.Delivery)
        assertTrue((decoded as SyncRelayFrameCodec.ServerMessage.Delivery).forwarded)
    }

    @Test fun rejectsOversizedFramesAndInvalidIdentifiers() {
        assertThrows(IllegalArgumentException::class.java) {
            SyncRelayFrameCodec.encodeRelayRequest(store, "not-a-uuid", recipient, operation, frame())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncRelayFrameCodec.decodeServerMessage(" ".repeat(700_001), recipient)
        }
    }
}
