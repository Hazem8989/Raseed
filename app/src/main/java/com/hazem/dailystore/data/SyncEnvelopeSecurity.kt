package com.hazem.dailystore.data

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class AuthenticatedSyncEnvelope(
    val protocolVersion: Int,
    val storeId: String,
    val operationId: String,
    val sequenceNumber: Long,
    val originDeviceId: String,
    val actorUserId: String,
    val operationType: String,
    val entityType: String,
    val entityId: String,
    val baseRevision: Long,
    val payloadJson: String,
    val payloadSha256: String,
    val createdAt: String,
    val authenticationTagHex: String
)

/**
 * Canonical payload hashing and per-device HMAC authentication.
 * Callers must obtain secrets from a protected vault and independently enforce
 * enrollment, replay, primary authority, and entity revision checks.
 */
object SyncEnvelopeSecurity {
    const val PROTOCOL_VERSION = 2
    private val allowedEntities = setOf(
        "PRODUCT", "CATEGORY", "UNIT", "CUSTOMER", "SUPPLIER", "CASH_ACCOUNT",
        "PURCHASE_INVOICE", "SALES_INVOICE", "CUSTOMER_PAYMENT",
        "SUPPLIER_PAYMENT", "SALES_RETURN", "PURCHASE_RETURN",
        "INVENTORY_ADJUSTMENT", "OPERATING_EXPENSE", "CASH_ACCOUNT_TRANSFER",
        "SERVICE_TRANSFER", "SERVICE_TRANSFER_RECEIVABLE", "SERVICE_TRANSFER_DEBT_PAYMENT", "DAILY_SUPPLIER_ROLLOVER",
        "EXCHANGE_RATE", "STORE_SETTINGS",
        // A narrowly handled control-plane probe; the primary applies it without ledger writes.
        "SYNC_TRANSPORT_PROBE"
    )

    fun create(
        storeId: String, operationId: String, sequenceNumber: Long,
        originDeviceId: String, actorUserId: String, operationType: String,
        entityType: String, entityId: String, baseRevision: Long,
        payloadJson: String, createdAt: String, deviceSecret: ByteArray
    ): AuthenticatedSyncEnvelope {
        require(deviceSecret.size >= 32) { "Device secret must contain at least 256 bits" }
        require(storeId.isNotBlank() && operationId.isNotBlank() && originDeviceId.isNotBlank())
        require(actorUserId.isNotBlank() && entityId.isNotBlank())
        require(sequenceNumber > 0L && baseRevision >= 0L)
        require(operationType in setOf("CREATE", "UPDATE", "DELETE"))
        require(entityType in allowedEntities) { "Entity type is not allowed for synchronization" }
        require(createdAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*"))) { "Invalid ISO-8601 timestamp" }
        val canonicalPayload = canonicalObject(payloadJson)
        val unsigned = AuthenticatedSyncEnvelope(
            PROTOCOL_VERSION, storeId, operationId, sequenceNumber, originDeviceId,
            actorUserId, operationType, entityType, entityId, baseRevision,
            canonicalPayload, sha256(canonicalPayload), createdAt, ""
        )
        return unsigned.copy(authenticationTagHex = hmac(unsigned, deviceSecret))
    }

    fun verify(envelope: AuthenticatedSyncEnvelope, deviceSecret: ByteArray): Boolean {
        if (deviceSecret.size < 32 || envelope.protocolVersion != PROTOCOL_VERSION) return false
        if (envelope.operationType !in setOf("CREATE", "UPDATE", "DELETE")) return false
        if (envelope.entityType !in allowedEntities) return false
        if (envelope.storeId.isBlank() || envelope.operationId.isBlank() ||
            envelope.originDeviceId.isBlank() || envelope.actorUserId.isBlank() ||
            envelope.entityId.isBlank() || envelope.sequenceNumber <= 0L ||
            envelope.baseRevision < 0L) return false
        return runCatching {
            val canonicalPayload = canonicalObject(envelope.payloadJson)
            if (canonicalPayload != envelope.payloadJson) return false
            val expectedHash = hexToBytes(sha256(canonicalPayload))
            val actualHash = hexToBytes(envelope.payloadSha256)
            if (!MessageDigest.isEqual(expectedHash, actualHash)) return false
            val expectedTag = hexToBytes(hmac(envelope.copy(authenticationTagHex = ""), deviceSecret))
            val actualTag = hexToBytes(envelope.authenticationTagHex)
            MessageDigest.isEqual(expectedTag, actualTag)
        }.getOrDefault(false)
    }

    fun encode(envelope: AuthenticatedSyncEnvelope): String {
        require(envelope.payloadJson == canonicalObject(envelope.payloadJson))
        return JSONObject().apply {
            put("protocol_version", envelope.protocolVersion)
            put("store_id", envelope.storeId)
            put("operation_id", envelope.operationId)
            put("sequence_number", envelope.sequenceNumber)
            put("origin_device_id", envelope.originDeviceId)
            put("actor_user_id", envelope.actorUserId)
            put("operation_type", envelope.operationType)
            put("entity_type", envelope.entityType)
            put("entity_id", envelope.entityId)
            put("base_revision", envelope.baseRevision)
            put("payload_json", envelope.payloadJson)
            put("payload_sha256", envelope.payloadSha256)
            put("created_at", envelope.createdAt)
            put("authentication_tag", envelope.authenticationTagHex)
        }.toString()
    }

    fun decode(encoded: String): AuthenticatedSyncEnvelope {
        val json = JSONObject(encoded)
        return AuthenticatedSyncEnvelope(
            json.getInt("protocol_version"), json.getString("store_id"),
            json.getString("operation_id"), json.getLong("sequence_number"),
            json.getString("origin_device_id"), json.getString("actor_user_id"),
            json.getString("operation_type"), json.getString("entity_type"),
            json.getString("entity_id"), json.getLong("base_revision"),
            json.getString("payload_json"), json.getString("payload_sha256"),
            json.getString("created_at"), json.getString("authentication_tag")
        )
    }

    fun canonicalObject(payload: String): String = canonical(JSONObject(payload))

    private fun canonical(value: Any?): String = when {
        value == null || value === JSONObject.NULL -> "null"
        value is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(separator = ",", prefix = "{", postfix = "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        value is JSONArray -> (0 until value.length()).joinToString(separator = ",", prefix = "[", postfix = "]") { canonical(value.get(it)) }
        value is String -> JSONObject.quote(value)
        value is Boolean -> value.toString()
        value is Number -> value.toString()
        else -> error("Unsupported JSON value")
    }

    private fun hmac(envelope: AuthenticatedSyncEnvelope, secret: ByteArray): String {
        val fields = JSONObject().apply {
            put("protocol_version", envelope.protocolVersion)
            put("store_id", envelope.storeId)
            put("operation_id", envelope.operationId)
            put("sequence_number", envelope.sequenceNumber)
            put("origin_device_id", envelope.originDeviceId)
            put("actor_user_id", envelope.actorUserId)
            put("operation_type", envelope.operationType)
            put("entity_type", envelope.entityType)
            put("entity_id", envelope.entityId)
            put("base_revision", envelope.baseRevision)
            put("payload_sha256", envelope.payloadSha256)
            put("created_at", envelope.createdAt)
        }
        val info = listOf(
            "THE_DAILY_SHOP_SYNC_ENVELOPE_HMAC_V2",
            envelope.storeId,
            envelope.originDeviceId
        ).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)
        val hmacKey = SyncKeyDerivation.derive(secret, info)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
            return bytesToHex(mac.doFinal(canonical(fields).toByteArray(StandardCharsets.UTF_8)))
        } finally {
            hmacKey.fill(0)
        }
    }

    private fun sha256(value: String): String =
        bytesToHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(value: String): ByteArray {
        require(value.length % 2 == 0 && value.matches(Regex("[0-9a-fA-F]*")))
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
