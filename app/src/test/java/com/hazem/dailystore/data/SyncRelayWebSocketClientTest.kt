package com.hazem.dailystore.data

import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class SyncRelayWebSocketClientTest {
    @Test fun androidOkHttpTransportCompletesTlsWebSocketHandshakeAndSendsRelayFrame() {
        val storeId = UUID.randomUUID().toString()
        val senderId = UUID.randomUUID().toString()
        val recipientId = UUID.randomUUID().toString()
        val operationId = UUID.randomUUID().toString()

        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverTls = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()

        val readyLatch = CountDownLatch(1)
        val messageLatch = CountDownLatch(1)
        val receivedMessage = AtomicReference<String?>()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send(
                    JSONObject()
                        .put("type", "ready")
                        .put("protocol", 1)
                        .put("device_id", senderId)
                        .toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                receivedMessage.set(text)
                messageLatch.countDown()
            }
        }))
        server.start()

        val httpClient = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .build()
        val client = SyncRelayWebSocketClient(
            server.url("/").toString().replaceFirst("https://", "wss://"),
            storeId,
            senderId,
            object : SyncRelayWebSocketClient.Listener {
                override fun onReady() { readyLatch.countDown() }
                override fun onEncryptedFrame(fromDeviceId: String, frameId: String, encryptedFrameJson: String) {}
                override fun onDeliveryStatus(frameId: String, deliveredToSocket: Boolean, retryable: Boolean) {}
                override fun onRelayError(code: String) {}
                override fun onDisconnected(cause: Throwable?) {}
            },
            httpClient
        )

        try {
            client.connect()
            assertTrue("WSS ready handshake timed out", readyLatch.await(5, TimeUnit.SECONDS))
            assertTrue(client.isReady)

            val encryptedFrame = JSONObject()
                .put("frame_version", 1)
                .put("store_id", storeId)
                .put("sender_device_id", senderId)
                .put("recipient_device_id", recipientId)
                .put("operation_id", operationId)
                .put("ciphertext", "opaque-ciphertext")
                .put("signature_algorithm", "ECDSA_P256_SHA256")
                .put("sender_key_fingerprint", "a".repeat(64))
                .put("signature", "test-signature")
                .toString()

            assertTrue(client.sendEncryptedFrame(operationId, recipientId, encryptedFrame))
            assertTrue("relay frame was not received by the TLS WebSocket peer",
                messageLatch.await(5, TimeUnit.SECONDS))
            val relay = JSONObject(receivedMessage.get()!!)
            assertEquals("relay", relay.getString("type"))
            assertEquals(recipientId, relay.getString("to_device_id"))
            assertEquals(operationId, relay.getString("frame_id"))
            assertEquals(encryptedFrame, relay.getString("ciphertext"))
        } finally {
            client.disconnect()
            server.shutdown()
        }
    }

    @Test fun rejectsUnencryptedWebSocketEndpoint() {
        val listener = object : SyncRelayWebSocketClient.Listener {
            override fun onReady() {}
            override fun onEncryptedFrame(fromDeviceId: String, frameId: String, encryptedFrameJson: String) {}
            override fun onDeliveryStatus(frameId: String, deliveredToSocket: Boolean, retryable: Boolean) {}
            override fun onRelayError(code: String) {}
            override fun onDisconnected(cause: Throwable?) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncRelayWebSocketClient(
                "ws://localhost:8787",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                listener
            )
        }
    }
}
