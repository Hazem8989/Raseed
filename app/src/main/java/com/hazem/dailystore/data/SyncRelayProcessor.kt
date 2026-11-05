package com.hazem.dailystore.data

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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

    private val pendingApplicationAckIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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

        // Cloudflare permits 60 inbound frames per device in a rolling 10-second
        // window. Keep headroom for reconnect races and ACKs produced while
        // processing inbound operations.
        var remaining = 50
        for (peerId in peers) {
            if (remaining <= 0) break
            val acknowledgements = repository.appliedOperationsAwaitingAckForDevice(
                peerId, limit = remaining
            )
            for (operationId in acknowledgements) {
                if (!sendApplicationAck(operationId, peerId)) {
                    listener.onRetryRequired(operationId, "تعذر إرسال إقرار التطبيق؛ بقي محفوظاً لإعادة المحاولة")
                    return
                }
                remaining--
                if (remaining <= 0) break
            }
            if (remaining > 0) {
                val pending = repository.pendingOutboxForDevice(peerId, limit = remaining)
                for (item in pending) {
                    val frame = repository.encryptedAuthenticatedOutbox(item.operationId, peerId)
                    if (!sendFrame(item.operationId, peerId, frame)) {
                        listener.onRetryRequired(item.operationId, "تعذر إرسال العملية؛ بقيت محفوظة في صندوق الإرسال")
                        return
                    }
                    remaining--
                    if (remaining <= 0) break
                }
            }
        }
    }

    private fun sendApplicationAck(operationId: String, recipientDeviceId: String): Boolean {
        val frame = repository.createEncryptedApplicationAck(recipientDeviceId, operationId)
        pendingApplicationAckIds.add(operationId)
        val sent = sendFrame(operationId, recipientDeviceId, frame)
        if (!sent) pendingApplicationAckIds.remove(operationId)
        return sent
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

            if (!sendApplicationAck(frameId, fromDeviceId)) {
                listener.onRetryRequired(frameId, "تم تطبيق العملية، لكن تعذر إرسال الإقرار؛ سيعاد إرساله عند الاتصال التالي")
            }
        } catch (error: Exception) {
            listener.onProtocolError(frameId, error.message ?: "فشل معالجة إطار المزامنة")
        }
    }

    /** Relay forwarding is transport telemetry only and never changes outbox state. */
    fun onRelayDelivery(frameId: String, forwarded: Boolean, retryable: Boolean) {
        if (forwarded && !retryable && pendingApplicationAckIds.remove(frameId)) {
            // This only records relay-level delivery pacing. The peer's outbox is
            // cleared exclusively by its verified application-level ACK.
            runCatching { repository.markApplicationAckForwarded(frameId) }
                .onFailure { listener.onProtocolError(frameId, it.message ?: "تعذر حفظ محاولة تمرير الإقرار") }
        }
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
    private val registryBaseUrl = relayBaseUrl.replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
    private val sendRateLimiter = SyncRelaySendRateLimiter()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "daily-shop-sync-retry").apply { isDaemon = true }
    }
    @Volatile private var desiredConnection = false
    private var reconnectDelaySeconds = 1L
    private var deliveryRetryDelaySeconds = 11L
    private var reconnectTask: ScheduledFuture<*>? = null
    private var deliveryRetryTask: ScheduledFuture<*>? = null

    private val processorListener = object : SyncRelayProcessor.Listener {
        override fun onOperationApplied(operationId: String) =
            listener.onOperationApplied(operationId)

        override fun onApplicationAckAccepted(operationId: String, duplicate: Boolean) {
            synchronized(this@SyncRelayRuntime) { deliveryRetryDelaySeconds = 11L }
            listener.onApplicationAckAccepted(operationId, duplicate)
        }

        override fun onRetryRequired(operationId: String, reason: String) {
            listener.onRetryRequired(operationId, reason)
            // A local send failure may not produce a relay delivery-status callback.
            // Schedule a durable outbox retry here as well, with bounded backoff.
            scheduleDeliveryRetry()
        }

        override fun onProtocolError(operationId: String?, reason: String) =
            listener.onProtocolError(operationId, reason)
    }

    private val processor = SyncRelayProcessor(
        repository,
        SyncDomainApplier(context.applicationContext),
        sendFrame = { operationId, recipientId, frame ->
            if (::client.isInitialized && client.isReady && sendRateLimiter.tryAcquire()) {
                client.sendEncryptedFrame(operationId, recipientId, frame)
            } else false
        },
        listener = processorListener
    )

    init {
        client = SyncRelayWebSocketClient(
            relayBaseUrl,
            repository.storeId(),
            repository.localDeviceId(),
            this,
            authentication = repository.relayAuthentication()
        )
    }

    @Synchronized
    fun connect() {
        if (desiredConnection) return
        desiredConnection = true
        reconnectDelaySeconds = 1L
        scheduler.execute { prepareAndConnect() }
    }

    @Synchronized
    fun disconnect() {
        desiredConnection = false
        reconnectTask?.cancel(false)
        reconnectTask = null
        deliveryRetryTask?.cancel(false)
        deliveryRetryTask = null
        client.disconnect()
    }

    val isConnected: Boolean get() = client.isReady

    /** Flush durable pending operations when a user explicitly requests a test/send. */
    fun flushPending() {
        synchronized(this) {
            if (!desiredConnection) {
                connect()
                return
            }
        }
        scheduler.execute {
            if (client.isReady) {
                runCatching { processor.onRelayReady() }
                    .onFailure { listener.onProtocolError(null, it.message ?: "تعذر إرسال العمليات المعلقة") }
            }
        }
    }

    /** Registry updates are completed before the primary opens its relay socket. */
    private fun prepareAndConnect() {
        try {
            if (repository.localDeviceRole() == "PRIMARY") {
                repository.syncDeviceRegistry(registryBaseUrl)
            }
            synchronized(this) {
                if (!desiredConnection) return
            }
            client.connect()
        } catch (error: Exception) {
            listener.onRetryRequired("", error.message ?: "تعذر تجهيز مصادقة وسيط المزامنة")
            scheduleReconnect()
        }
    }

    /** Call after enrolling or revoking a device while the relay is already connected. */
    fun refreshDeviceRegistry() {
        if (repository.localDeviceRole() != "PRIMARY") return
        scheduler.execute {
            runCatching { repository.syncDeviceRegistry(registryBaseUrl) }
                .onFailure { listener.onRetryRequired("", it.message ?: "تعذر تحديث سجل شهادات الأجهزة") }
        }
    }

    private fun scheduleReconnect() {
        synchronized(this) {
            if (!desiredConnection || reconnectTask?.isDone == false) return
            val delay = reconnectDelaySeconds
            reconnectDelaySeconds = (delay * 2L).coerceAtMost(60L)
            reconnectTask = scheduler.schedule({
                val shouldAttempt = synchronized(this@SyncRelayRuntime) {
                    reconnectTask = null
                    desiredConnection
                }
                if (shouldAttempt) {
                    prepareAndConnect()
                }
            }, delay, TimeUnit.SECONDS)
        }
    }

    /**
     * Re-sends only still-pending work. Duplicate deliveries are safe because
     * the receiver persists operation IDs and the ACK is idempotent.
     */
    private fun scheduleDeliveryRetry() {
        synchronized(this) {
            if (!desiredConnection || deliveryRetryTask?.isDone == false) return
            val delay = deliveryRetryDelaySeconds
            deliveryRetryDelaySeconds = (delay * 2L).coerceAtMost(60L)
            deliveryRetryTask = scheduler.schedule({
                val shouldFlush = synchronized(this@SyncRelayRuntime) {
                    deliveryRetryTask = null
                    desiredConnection && client.isReady
                }
                if (shouldFlush) {
                    runCatching { processor.onRelayReady() }
                        .onFailure {
                            listener.onProtocolError(null, it.message ?: "تعذرت إعادة إرسال العمليات المعلقة")
                        }
                }
            }, delay, TimeUnit.SECONDS)
        }
    }

    override fun onReady() {
        synchronized(this) { reconnectDelaySeconds = 1L }
        runCatching { processor.onRelayReady() }
            .onFailure { listener.onProtocolError(null, it.message ?: "فشل بدء مزامنة صندوق الإرسال") }
    }

    override fun onEncryptedFrame(fromDeviceId: String, frameId: String, encryptedFrameJson: String) {
        processor.onEncryptedFrame(fromDeviceId, frameId, encryptedFrameJson)
    }

    override fun onDeliveryStatus(frameId: String, deliveredToSocket: Boolean, retryable: Boolean) {
        processor.onRelayDelivery(frameId, deliveredToSocket, retryable)
        scheduleDeliveryRetry()
    }

    override fun onRelayError(code: String) {
        listener.onProtocolError(null, "خطأ وسيط المزامنة: $code")
    }

    override fun onDisconnected(cause: Throwable?) {
        if (cause != null) {
            listener.onRetryRequired("", cause.message ?: "انقطع اتصال وسيط المزامنة")
        }
        scheduleReconnect()
    }

    @Synchronized
    fun shutdown() {
        disconnect()
        scheduler.shutdownNow()
    }
}


/** Rolling-window limiter aligned with the relay's device-level 60/10s limit. */
internal class SyncRelaySendRateLimiter(
    private val maxMessages: Int = 50,
    private val windowMillis: Long = 10_000L,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private val sentAt = java.util.ArrayDeque<Long>()

    init {
        require(maxMessages in 1..59 && windowMillis > 0L)
    }

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clockMillis()
        while (sentAt.peekFirst()?.let { now - it >= windowMillis } == true) {
            sentAt.removeFirst()
        }
        if (sentAt.size >= maxMessages) return false
        sentAt.addLast(now)
        return true
    }
}
