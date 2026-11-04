package com.hazem.dailystore.data

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class SyncRelayWebSocketClientTest {
    @Test fun androidOkHttpTransportProvesPrivateKeyPossessionBeforeReadyAndSendsRelayFrame() {
        val storeId = UUID.randomUUID().toString()
        val senderId = UUID.randomUUID().toString()
        val recipientId = UUID.randomUUID().toString()
        val operationId = UUID.randomUUID().toString()
        val identityGenerator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val identity = identityGenerator.generateKeyPair()
        val challenge = "A".repeat(43)
        val challengeExpiry = System.currentTimeMillis() + 20_000L

        val tlsCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(tlsCertificate).build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(tlsCertificate.certificate).build()

        val readyLatch = CountDownLatch(1)
        val messageLatch = CountDownLatch(1)
        val receivedMessage = AtomicReference<String?>()
        val authVerified = AtomicReference(false)
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send(JSONObject()
                    .put("type", "challenge")
                    .put("challenge", challenge)
                    .put("expires_at", challengeExpiry)
                    .toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONObject(text)
                if (message.optString("type") == "authenticate") {
                    val proof = JSONArray().put("THE_DAILY_SHOP_RELAY_AUTH_V1")
                        .put(storeId).put(senderId).put("ROOT")
                        .put(challenge).put(challengeExpiry).toString()
                    val signature = Base64.decode(message.getString("signature"), Base64.DEFAULT)
                    val valid = message.getString("serial") == "ROOT" &&
                        message.getString("challenge") == challenge &&
                        SyncDeviceAuthenticator.verifyBytes(
                            identity.public.encoded, proof.toByteArray(Charsets.UTF_8), signature
                        )
                    authVerified.set(valid)
                    if (valid) {
                        webSocket.send(JSONObject().put("type", "ready")
                            .put("protocol", 2).put("device_id", senderId).toString())
                    } else {
                        webSocket.send(JSONObject().put("type", "error")
                            .put("code", "AUTHENTICATION_FAILED").toString())
                    }
                } else {
                    receivedMessage.set(text)
                    messageLatch.countDown()
                }
            }
        }))
        server.start()

        val httpClient = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .build()
        val relayClient = SyncRelayWebSocketClient(
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
            httpClient,
            SyncRelayDeviceAuthentication("ROOT", identity.private)
        )

        try {
            relayClient.connect()
            assertTrue("challenge-response handshake timed out", readyLatch.await(5, TimeUnit.SECONDS))
            assertTrue("client did not prove possession of the enrolled private key", authVerified.get())
            assertTrue(relayClient.isReady)

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

            assertTrue(relayClient.sendEncryptedFrame(operationId, recipientId, encryptedFrame))
            assertTrue("relay frame was not received by the TLS WebSocket peer",
                messageLatch.await(5, TimeUnit.SECONDS))
            val relay = JSONObject(receivedMessage.get()!!)
            assertEquals("relay", relay.getString("type"))
            assertEquals(recipientId, relay.getString("to_device_id"))
            assertEquals(operationId, relay.getString("frame_id"))
            assertEquals(encryptedFrame, relay.getString("ciphertext"))
        } finally {
            relayClient.disconnect()
            server.shutdown()
        }
    }

    @Test fun rejectsPlainWebSocketAndHttpsEndpoints() {
        val listener = object : SyncRelayWebSocketClient.Listener {
            override fun onReady() {}
            override fun onEncryptedFrame(fromDeviceId: String, frameId: String, encryptedFrameJson: String) {}
            override fun onDeliveryStatus(frameId: String, deliveredToSocket: Boolean, retryable: Boolean) {}
            override fun onRelayError(code: String) {}
            override fun onDisconnected(cause: Throwable?) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncRelayWebSocketClient("ws://localhost:8787", UUID.randomUUID().toString(), UUID.randomUUID().toString(), listener)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SyncRelayWebSocketClient("https://localhost:8787", UUID.randomUUID().toString(), UUID.randomUUID().toString(), listener)
        }
    }
}
