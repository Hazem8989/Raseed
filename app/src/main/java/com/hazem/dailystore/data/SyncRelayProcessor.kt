package com.hazem.dailystore.data

import android.content.Context
import org.json.JSONObject

/**
 * Connects durable protocol state and domain application to the opaque relay.
 *
 * A relay "forwarded" notice is deliberately not a business acknowledgement.
 * Outbox rows remain pending until a signed, encrypted APPLICATION_ACK is
 * verified and committed by SyncProtocolRepository.
 */
class SyncRelayProcessor(
    private val repository: SyncProtocolRepository,
    private val domainApplier: SyncDomainApplier,
    private val sendFrame: (operationId: String, recipientDeviceId: String, frameJson: String) -> Boolean,
    private val listener: Listener = object : Listener {}
) {
    interface Listener {
        fun onOperationApplied(operationId: String) {}
        fun onApplicationAckAccepted(operationId: String, duplicate: Boolean) {}
        fun onRetryRequired(operationId: String, reason: String) {}
        fun onProtocolError(operationId: String?, reason: String) {}
    }

    /** Called only after the relay's authenticated ready handshake. */
    fun onRelayReady() {
        val role = repository.localDeviceRole()
            ?: throw IllegalStateException("دور جهاز المزامنة غير مهيأ")
        val peers = when (role) {
            "PRIMARY" -> repository.activeSecondaryDeviceIds()
            "SECONDARY" -> listOf(repository.authorityDeviceId()
                ?: throw IllegalStateException("الجهاز الأساسي غير محدد"))
            else -> throw IllegalStateException("دور جهاز المزامنة غير معروف")
        }
        peers.forEach { peerId ->
            // Rebuild ACK frames from durable APPLIED inbox rows after reconnect.
            repository.appliedOperationsAwaitingAckForDevice(peerId).forEach { operationId ->
                val ack = repository.createEncryptedApplicationAck(peerId, operationId)
                if (!sendFrame(operationId, peerId, ack)) {
                    listener.onRetryRequired(operationId, "تعذر إرسال إقرار التطبيق؛ سيعاد بناؤه عند الاتصال التالي")
                }
            }
            // Outbox remains durable until the peer's application ACK is accepted.
            repository.pendingOutboxForDevice(peerId).forEach { item ->
                val frame = repository.encryptedAuthenticatedOutbox(item.operationId, peerId)
                if (!sendFrame(item.operationId, peerId, frame)) {
                    listener.onRetryRequired(item.operationId, "تعذر إرسال العملية؛ بقيت محفوظة في صندوق الإرسال")
                }
            }
        }
    }

    /** Processes a relay-delivered encrypted peer frame. */
    fun onEncryptedFrame(fromDeviceId: String, frameId: String, encryptedFrameJson: String) {
        try {
            require(frameId.isNotBlank() && frameId.length <= 80)
            val frame = JSONObject(encryptedFrameJson)
            require(frame.optString("operation_id") == frameId &&
                frame.optString("sender_device_id") == fromDeviceId &&
                frame.optString("recipient_device_id") == repository.localDeviceId()) {
                "إطار الوسيط لا يطابق هوية الطرفين أو معرّف العملية"
            }

            if (frame.optString("frame_type") == "APPLICATION_ACK") {
                val disposition = repository.acceptEncryptedApplicationAck(encryptedFrameJson)
                listener.onApplicationAckAccepted(
                    frameId, disposition == SyncApplicationAckDisposition.DUPLICATE
                )
                return
            }

            val role = repository.localDeviceRole()
            val envelope = when (role) {
                "PRIMARY" -> repository.verifyEncryptedIncomingFromSecondary(encryptedFrameJson)
                "SECONDARY" -> repository.verifyEncryptedIncomingFromPrimary(encryptedFrameJson)
                else -> throw IllegalStateException("دور جهاز المزامنة غير مهيأ")
            }
            require(envelope.operationId == frameId && envelope.originDeviceId == fromDeviceId) {
                "هوية العملية المفكوكة لا تطابق إطار النقل"
            }
            val encodedEnvelope = SyncEnvelopeSecurity.encode(envelope)
            val disposition = when (role) {
                "PRIMARY" -> repository.recordIncomingFromSecondary(encodedEnvelope)
                "SECONDARY" -> repository.recordIncomingFromPrimary(encodedEnvelope)
                else -> error("دور جهاز المزامنة غير صالح")
            }
            require(disposition != SyncInboxDisposition.CONFLICT) {
                "تعارضت بيانات العملية الواردة مع عملية محفوظة بالمعرّف نفسه"
            }

            when (repository.incomingOperationStatus(frameId)) {
                "RECEIVED" -> {
                    val result = domainApplier.applyReceivedOperation(frameId)
                    require(result.applied) {
                        result.reason ?: "لم يكتمل تطبيق العملية على قاعدة البيانات"
                    }
                    listener.onOperationApplied(frameId)
                }
                "APPLIED" -> Unit // duplicate delivery: re-issue the same durable ACK
                "REJECTED" -> throw IllegalArgumentException("سبق رفض العملية؛ لن يصدر إقرار تطبيق")
                else -> throw IllegalStateException("تعذر تحديد حالة العملية الواردة")
            }

            val ack = repository.createEncryptedApplicationAck(fromDeviceId, frameId)
            if (!sendFrame(frameId, fromDeviceId, ack)) {
                listener.onRetryRequired(frameId, "تم تطبيق العملية، لكن تعذر إرسال الإقرار؛ سيعاد إرساله عند الاتصال التالي")
            }
        } catch (error: Exception) {
            listener.onProtocolError(frameId, error.message ?: "فشل معالجة إطار المزامنة")
        }
    }

    /** Relay forwarding is transport telemetry only and never changes outbox state. */
    fun onRelayDelivery(frameId: String, forwarded: Boolean, retryable: Boolean) {
        if (!forwarded || retryable) {
            listener.onRetryRequired(
                frameId,
                if (retryable) "الجهاز المقابل غير متصل؛ العملية باقية في صندوق الإرسال"
                else "لم يؤكد الوسيط تمرير الرسالة"
            )
        }
    }
}

/**
 * Lifecycle wrapper that binds SyncRelayWebSocketClient callbacks to the
 * durable processor. The endpoint must be a deployed WSS Worker URL.
 */
class SyncRelayRuntime(
    context: Context,
    relayBaseUrl: String,
    private val repository: SyncProtocolRepository,
    private val listener: SyncRelayProcessor.Listener = object : SyncRelayProcessor.Listener {}
) : SyncRelayWebSocketClient.Listener {
    private lateinit var client: SyncRelayWebSocketClient
    private val processor = SyncRelayProcessor(
        repository,
        SyncDomainApplier(context.applicationContext),
        sendFrame = { operationId, recipientId, frame ->
            if (::client.isInitialized) client.sendEncryptedFrame(operationId, recipientId, frame) else false
        },
        listener = listener
    )

    init {
        client = SyncRelayWebSocketClient(
            relayBaseUrl,
            repository.storeId(),
            repository.localDeviceId(),
            this
        )
    }

    fun connect() = client.connect()

    fun disconnect() = client.disconnect()

    val isConnected: Boolean get() = client.isReady

    override fun onReady() {
        runCatching { processor.onRelayReady() }
            .onFailure { listener.onProtocolError(null, it.message ?: "فشل بدء مزامنة صندوق الإرسال") }
    }

    override fun onEncryptedFrame(fromDeviceId: String, frameId: String, encryptedFrameJson: String) {
        processor.onEncryptedFrame(fromDeviceId, frameId, encryptedFrameJson)
    }

    override fun onDeliveryStatus(frameId: String, deliveredToSocket: Boolean, retryable: Boolean) {
        processor.onRelayDelivery(frameId, deliveredToSocket, retryable)
    }

    override fun onRelayError(code: String) {
        listener.onProtocolError(null, "خطأ وسيط المزامنة: $code")
    }

    override fun onDisconnected(cause: Throwable?) {
        if (cause != null) {
            listener.onRetryRequired("", cause.message ?: "انقطع اتصال وسيط المزامنة")
        }
    }
}
