package com.hazem.dailystore.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for the opaque Cloudflare relay.
 *
 * The relay is transport only: "forwarded" is never treated as a business ACK.
 * All business frames remain signed and encrypted by SyncProtocolRepository.
 * This class intentionally has no automatic retry or durable queue; those must
 * be driven by the persistent outbox and a verified primary application ACK.
 */
class SyncRelayWebSocketClient(
    relayBaseUrl: String,
    private val storeId: String,
    private val deviceId: String,
    private val listener: Listener,
    private val client: OkHttpClient = defaultClient()
) {
    interface Listener {
        fun onReady()
        fun onEncryptedFrame(fromDeviceId: String, frameId: String, encryptedFrameJson: String)
        fun onDeliveryStatus(frameId: String, deliveredToSocket: Boolean, retryable: Boolean)
        fun onRelayError(code: String)
        fun onDisconnected(cause: Throwable?)
    }

    private val endpoint: HttpUrl = buildEndpoint(relayBaseUrl, storeId, deviceId)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false

    @Synchronized
    fun connect() {
        check(socket == null) { "اتصال المزامنة مفتوح أو قيد الفتح بالفعل" }
        ready = false
        val request = Request.Builder().url(endpoint).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    when (val event = SyncRelayFrameCodec.decodeServerMessage(text, deviceId)) {
                        SyncRelayFrameCodec.ServerMessage.Ready -> {
                            ready = true
                            listener.onReady()
                        }
                        is SyncRelayFrameCodec.ServerMessage.Inbound -> listener.onEncryptedFrame(
                            event.fromDeviceId, event.frameId, event.encryptedFrameJson
                        )
                        is SyncRelayFrameCodec.ServerMessage.Delivery -> listener.onDeliveryStatus(
                            event.frameId, event.forwarded, event.retryable
                        )
                        is SyncRelayFrameCodec.ServerMessage.Error -> listener.onRelayError(event.code)
                    }
                } catch (error: Exception) {
                    listener.onRelayError("INVALID_SERVER_MESSAGE")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(this@SyncRelayWebSocketClient) {
                    if (socket === webSocket) socket = null
                    ready = false
                }
                listener.onDisconnected(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(this@SyncRelayWebSocketClient) {
                    if (socket === webSocket) socket = null
                    ready = false
                }
                listener.onDisconnected(null)
            }
        })
    }

    /** Returns false unless the relay has completed its ready handshake. */
    @Synchronized
    fun sendEncryptedFrame(operationId: String, recipientDeviceId: String, encryptedFrameJson: String): Boolean {
        val active = socket ?: return false
        if (!ready) return false
        val relayMessage = SyncRelayFrameCodec.encodeRelayRequest(
            storeId, deviceId, recipientDeviceId, operationId, encryptedFrameJson
        )
        return active.send(relayMessage)
    }

    @Synchronized
    fun disconnect() {
        ready = false
        val active = socket
        socket = null
        active?.close(1000, "client disconnect")
    }

    val isReady: Boolean get() = ready

    companion object {
        private const val MAX_FRAME_CHARS = 700_000

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        private fun buildEndpoint(base: String, storeId: String, deviceId: String): HttpUrl {
            requireUuid(storeId, "معرّف المتجر")
            requireUuid(deviceId, "معرّف الجهاز")
            // OkHttp's HttpUrl parser accepts http/https schemes; WebSocket upgrades
            // are performed by OkHttp when the request is created. Parse WSS as HTTPS,
            // then retain the HTTPS URL for the WebSocket request.
            val normalizedBase = when {
                base.startsWith("wss://", ignoreCase = true) -> "https://" + base.substring(6)
                else -> base
            }
            val parsed = normalizedBase.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("عنوان وسيط المزامنة غير صالح")
            require(parsed.scheme == "https") { "يجب استخدام اتصال WebSocket مشفر WSS" }
            require(parsed.username.isEmpty() && parsed.password.isEmpty() &&
                parsed.query == null && parsed.fragment == null) {
                "عنوان وسيط المزامنة يحتوي بيانات أو معاملات غير مسموحة"
            }
            require(parsed.encodedPath == "/" || parsed.encodedPath.isEmpty()) {
                "أدخل عنوان الوسيط الأساسي دون مسار إضافي"
            }
            return parsed.newBuilder()
                .addPathSegments("v1/ws/$storeId/$deviceId")
                .build()
        }

        private fun requireUuid(value: String, label: String) {
            require(runCatching { UUID.fromString(value) }.isSuccess) { "$label غير صالح" }
        }
    }
}

/** Strict codec for the relay's documented JSON protocol. */
internal object SyncRelayFrameCodec {
    private const val MAX_FRAME_CHARS = 700_000

    sealed class ServerMessage {
        object Ready : ServerMessage()
        data class Inbound(val fromDeviceId: String, val frameId: String, val encryptedFrameJson: String) : ServerMessage()
        data class Delivery(val frameId: String, val forwarded: Boolean, val retryable: Boolean) : ServerMessage()
        data class Error(val code: String) : ServerMessage()
    }

    fun encodeRelayRequest(
        storeId: String,
        senderDeviceId: String,
        recipientDeviceId: String,
        frameId: String,
        encryptedFrameJson: String
    ): String {
        requireUuid(storeId)
        requireUuid(senderDeviceId)
        requireUuid(recipientDeviceId)
        require(senderDeviceId != recipientDeviceId)
        require(frameId.isNotBlank() && frameId.length <= 80)
        require(encryptedFrameJson.length in 2..MAX_FRAME_CHARS)
        val frame = JSONObject(encryptedFrameJson)
        require(frame.optString("store_id") == storeId &&
            frame.optString("sender_device_id") == senderDeviceId &&
            frame.optString("recipient_device_id") == recipientDeviceId &&
            frame.optString("operation_id") == frameId &&
            frame.optString("signature_algorithm") == "ECDSA_P256_SHA256" &&
            frame.optString("signature").isNotBlank() &&
            frame.optString("ciphertext").isNotBlank()) {
            "إطار المزامنة المشفر لا يطابق مسار الإرسال أو يفتقد التوقيع"
        }
        return JSONObject()
            .put("type", "relay")
            .put("to_device_id", recipientDeviceId)
            .put("frame_id", frameId)
            .put("ciphertext", encryptedFrameJson)
            .toString()
    }

    fun decodeServerMessage(encoded: String, localDeviceId: String): ServerMessage {
        require(encoded.length in 2..MAX_FRAME_CHARS)
        val json = JSONObject(encoded)
        return when (json.getString("type")) {
            "ready" -> {
                require(json.getInt("protocol") == 1 && json.getString("device_id") == localDeviceId)
                ServerMessage.Ready
            }
            "relay" -> {
                val from = json.getString("from_device_id")
                val frameId = json.getString("frame_id")
                val frameJson = json.getString("ciphertext")
                requireUuid(from)
                require(from != localDeviceId && frameId.isNotBlank() && frameId.length <= 80)
                require(frameJson.length in 2..MAX_FRAME_CHARS)
                val frame = JSONObject(frameJson)
                require(frame.optString("sender_device_id") == from &&
                    frame.optString("recipient_device_id") == localDeviceId &&
                    frame.optString("operation_id") == frameId &&
                    frame.optString("signature_algorithm") == "ECDSA_P256_SHA256" &&
                    frame.optString("signature").isNotBlank() &&
                    frame.optString("ciphertext").isNotBlank()) {
                    "رسالة الوسيط لا تطابق هوية المرسل أو المستلم"
                }
                ServerMessage.Inbound(from, frameId, frameJson)
            }
            "forwarded", "not_delivered" -> {
                val frameId = json.getString("frame_id")
                require(frameId.isNotBlank() && frameId.length <= 80)
                ServerMessage.Delivery(
                    frameId,
                    json.getString("type") == "forwarded",
                    json.optBoolean("retryable", false)
                )
            }
            "error" -> ServerMessage.Error(json.optString("code", "RELAY_ERROR"))
            else -> throw IllegalArgumentException("رسالة وسيط غير معروفة")
        }
    }

    private fun requireUuid(value: String) {
        require(runCatching { UUID.fromString(value) }.isSuccess) { "معرّف UUID غير صالح" }
    }
}
