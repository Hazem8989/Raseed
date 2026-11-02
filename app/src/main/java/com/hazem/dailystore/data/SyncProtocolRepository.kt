package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import android.util.Base64

data class SyncPairingInvitation(val invitationId: String, val token: String, val expiresAt: String)
data class SyncEnrollment(val deviceId: String, val credential: String)
data class SyncOutboxItem(
    val operationId: String, val sequence: Long, val operationType: String,
    val entityType: String, val entityId: String, val payloadJson: String,
    val payloadSha256: String, val createdAt: String, val retryCount: Int
)
enum class SyncInboxDisposition { RECEIVED, DUPLICATE, CONFLICT }

/**
 * Durable local-first synchronization protocol primitives.
 *
 * This class intentionally does not open sockets, declare data synchronized, or
 * apply remote financial operations. Transport authentication/encryption and
 * domain-specific replay must be completed before any network listener is enabled.
 */
class SyncProtocolRepository(private val context: Context) {
    private val database = AppDatabase.getInstance(context)
    private val random = SecureRandom()

    fun configureAsPrimary(actorUserId: String, displayName: String) {
        val cleanName = displayName.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 80) { "اسم الجهاز مطلوب وبحد أقصى 80 حرفاً" }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            PermissionPolicy.require(db, actorUserId, PermissionPolicy.SYNC_MANAGE)
            val deviceId = SyncDeviceIdentity.getOrCreate(context)
            val existingAuthority = state(db, "authority_device_id")
            require(existingAuthority == null || existingAuthority == deviceId) {
                "يوجد جهاز أساسي آخر؛ لا يمكن نقل سلطة الاعتماد تلقائياً"
            }
            val now = timestamp()
            db.insertWithOnConflict("sync_devices", null, ContentValues().apply {
                put("device_id", deviceId)
                put("display_name", cleanName)
                put("device_role", "PRIMARY")
                put("status", "ACTIVE")
                put("enrolled_by", actorUserId)
                put("created_at", now)
                put("last_seen_at", now)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.update("sync_devices", ContentValues().apply {
                put("display_name", cleanName); put("last_seen_at", now)
                put("status", "ACTIVE"); putNull("revoked_at")
            }, "device_id=?", arrayOf(deviceId))
            putState(db, "authority_device_id", deviceId, now)
            if (state(db, "next_sequence") == null) putState(db, "next_sequence", "1", now)
            audit(db, actorUserId, "SYNC_PRIMARY_CONFIGURED", deviceId,
                null, JSONObject().put("display_name", cleanName).toString(), now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun createPairingInvitation(actorUserId: String): SyncPairingInvitation {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            requirePrimaryManager(db, actorUserId)
            val token = randomToken()
            val now = timestamp()
            val expires = timestamp(System.currentTimeMillis() + INVITATION_TTL_MS)
            val id = UUID.randomUUID().toString()
            db.delete("sync_pairing_invitations", "expires_at<=? OR consumed_at IS NOT NULL", arrayOf(now))
            db.insertOrThrow("sync_pairing_invitations", null, ContentValues().apply {
                put("invitation_id", id)
                put("invitation_hash", sha256(token))
                put("created_by", actorUserId)
                put("expires_at", expires)
                put("created_at", now)
            })
            audit(db, actorUserId, "SYNC_PAIRING_INVITATION_CREATED", id, null,
                JSONObject().put("expires_at", expires).toString(), now)
            db.setTransactionSuccessful()
            return SyncPairingInvitation(id, token, expires)
        } finally { db.endTransaction() }
    }

    /** Called by the primary after a future authenticated pairing handshake. */
    fun enrollSecondaryDevice(
        actorUserId: String, invitationToken: String, remoteDeviceId: String, displayName: String
    ): SyncEnrollment {
        val cleanName = displayName.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 80) { "اسم الجهاز غير صالح" }
        require(runCatching { UUID.fromString(remoteDeviceId) }.isSuccess) { "معرّف الجهاز غير صالح" }
        require(remoteDeviceId != SyncDeviceIdentity.getOrCreate(context)) { "لا يمكن تسجيل الجهاز الأساسي كجهاز ثانوي" }
        require(invitationToken.length in 32..128) { "رمز الاقتران غير صالح" }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            requirePrimaryManager(db, actorUserId)
            val now = timestamp()
            val invitation = db.rawQuery(
                """SELECT invitation_id FROM sync_pairing_invitations
                   WHERE invitation_hash=? AND consumed_at IS NULL AND expires_at>?""".trimIndent(),
                arrayOf(sha256(invitationToken), now)
            ).use { if (it.moveToFirst()) it.getString(0) else null }
                ?: throw IllegalArgumentException("رمز الاقتران غير صالح أو منتهي الصلاحية أو مستخدم سابقاً")
            val credential = randomToken()
            val existing = db.rawQuery(
                "SELECT status FROM sync_devices WHERE device_id=?", arrayOf(remoteDeviceId)
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            require(existing == null || existing == "REVOKED") { "هذا الجهاز مسجل بالفعل" }
            db.insertWithOnConflict("sync_devices", null, ContentValues().apply {
                put("device_id", remoteDeviceId)
                put("display_name", cleanName)
                put("device_role", "SECONDARY")
                put("credential_hash", sha256(credential))
                put("status", "ACTIVE")
                put("enrolled_by", actorUserId)
                put("created_at", now)
                put("last_seen_at", now)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.update("sync_devices", ContentValues().apply {
                put("display_name", cleanName); put("device_role", "SECONDARY")
                put("credential_hash", sha256(credential)); put("status", "ACTIVE")
                put("enrolled_by", actorUserId); put("last_seen_at", now); putNull("revoked_at")
            }, "device_id=?", arrayOf(remoteDeviceId))
            db.update("sync_pairing_invitations", ContentValues().apply { put("consumed_at", now) },
                "invitation_id=?", arrayOf(invitation))
            db.insertWithOnConflict("sync_cursors", null, ContentValues().apply {
                put("device_id", remoteDeviceId); put("last_sequence", 0L); put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            audit(db, actorUserId, "SYNC_DEVICE_ENROLLED", remoteDeviceId, null,
                JSONObject().put("display_name", cleanName).put("device_role", "SECONDARY").toString(), now)
            db.setTransactionSuccessful()
            return SyncEnrollment(remoteDeviceId, credential)
        } finally { db.endTransaction() }
    }

    fun authenticateDevice(deviceId: String, credential: String): Boolean {
        if (deviceId.isBlank() || credential.isBlank()) return false
        val stored = database.readableDatabase.rawQuery(
            "SELECT credential_hash FROM sync_devices WHERE device_id=? AND status='ACTIVE' AND device_role='SECONDARY'",
            arrayOf(deviceId)
        ).use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
        return MessageDigest.isEqual(stored.toByteArray(Charsets.US_ASCII), sha256(credential).toByteArray(Charsets.US_ASCII))
    }

    fun revokeDevice(actorUserId: String, remoteDeviceId: String) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            requirePrimaryManager(db, actorUserId)
            require(remoteDeviceId != SyncDeviceIdentity.getOrCreate(context)) {
                "لا يمكن إلغاء الجهاز الأساسي من هذه الشاشة"
            }
            val before = db.rawQuery(
                "SELECT display_name,status FROM sync_devices WHERE device_id=? AND device_role='SECONDARY'",
                arrayOf(remoteDeviceId)
            ).use {
                require(it.moveToFirst()) { "الجهاز غير موجود" }
                JSONObject().put("display_name", it.getString(0)).put("status", it.getString(1)).toString()
            }
            val now = timestamp()
            db.update("sync_devices", ContentValues().apply {
                put("status", "REVOKED"); put("revoked_at", now)
            }, "device_id=?", arrayOf(remoteDeviceId))
            audit(db, actorUserId, "SYNC_DEVICE_REVOKED", remoteDeviceId, before,
                JSONObject().put("status", "REVOKED").toString(), now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * Must be invoked inside the same SQLite transaction as the business posting.
     * It only records an outbox item when this installation is the configured primary.
     */
    fun enqueueLocalOperation(
        db: SQLiteDatabase, actorUserId: String, operationId: String,
        operationType: String, entityType: String, entityId: String,
        changeKind: String, payloadJson: String
    ): Long? {
        require(db.inTransaction()) { "يجب تسجيل حدث المزامنة ضمن معاملة العملية الأصلية" }
        require(operationId.isNotBlank() && operationType.isNotBlank() &&
            entityType.isNotBlank() && entityId.isNotBlank()) { "بيانات حدث المزامنة غير مكتملة" }
        require(changeKind in setOf("CREATE", "UPDATE", "DELETE")) { "نوع تغيير المزامنة غير صالح" }
        require(payloadJson.length in 2..MAX_PAYLOAD_CHARS) { "حجم بيانات المزامنة غير صالح" }
        JSONObject(payloadJson)
        val deviceId = SyncDeviceIdentity.getOrCreate(context)
        if (state(db, "authority_device_id") != deviceId) return null
        val activePrimary = db.rawQuery(
            "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role='PRIMARY' AND status='ACTIVE'",
            arrayOf(deviceId)
        ).use { it.moveToFirst() }
        check(activePrimary) { "هوية الجهاز الأساسي غير مفعلة" }
        val payloadHash = sha256(payloadJson)
        val existing = db.rawQuery(
            "SELECT sequence_number,payload_sha256,operation_type,entity_type,entity_id FROM sync_operation_envelopes WHERE operation_id=?",
            arrayOf(operationId)
        ).use { c ->
            if (c.moveToFirst()) {
                require(c.getString(1) == payloadHash && c.getString(2) == operationType &&
                    c.getString(3) == entityType && c.getString(4) == entityId) {
                    "أعيد استخدام معرّف العملية بمحتوى مزامنة مختلف"
                }
                c.getLong(0)
            } else null
        }
        if (existing != null) return existing
        val now = timestamp()
        val next = (state(db, "next_sequence") ?: "1").toLong()
        check(next > 0L && next < Long.MAX_VALUE) { "تسلسل المزامنة غير صالح" }
        putState(db, "next_sequence", (next + 1L).toString(), now)
        db.insertOrThrow("sync_operation_envelopes", null, ContentValues().apply {
            put("operation_id", operationId); put("sequence_number", next)
            put("origin_device_id", deviceId); put("actor_user_id", actorUserId)
            put("operation_type", operationType); put("entity_type", entityType); put("entity_id", entityId)
            put("payload_json", payloadJson); put("payload_sha256", payloadHash); put("created_at", now)
        })
        db.insertOrThrow("sync_queue", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("operation_id", operationId)
            put("entity_type", entityType); put("entity_id", entityId); put("operation_type", changeKind)
            put("payload_json", payloadJson); put("status", "PENDING"); put("retry_count", 0)
            put("created_at", now); put("updated_at", now)
        })
        db.update("sync_devices", ContentValues().apply { put("last_seen_at", now) },
            "device_id=?", arrayOf(deviceId))
        return next
    }

    fun enqueueInvoicePosting(
        db: SQLiteDatabase, actorUserId: String, operationId: String,
        invoiceType: String, invoiceId: String, cashAccountId: String? = null
    ): Long? {
        require(invoiceType == "SALE" || invoiceType == "PURCHASE")
        val table = if (invoiceType == "SALE") "sales_invoices" else "purchase_invoices"
        val itemTable = if (invoiceType == "SALE") "sales_items" else "purchase_items"
        val fk = if (invoiceType == "SALE") "sales_invoice_id" else "purchase_invoice_id"
        val header = db.rawQuery(
            """SELECT id,invoice_number,${if (invoiceType == "SALE") "customer_id,sale_type" else "supplier_id,'PURCHASE'"},
                      warehouse_id,invoice_date,status,subtotal_usd_cents,discount_usd_cents,
                      total_usd_cents,exchange_rate_syp_per_usd_milli,notes,created_by,
                      ${if (invoiceType == "SALE") "posted_at,posted_operating_date" else "updated_at,invoice_date"}
               FROM $table WHERE id=?""".trimIndent(), arrayOf(invoiceId)
        ).use { c ->
            require(c.moveToFirst()) { "الفاتورة غير موجودة عند تجهيز حدث المزامنة" }
            JSONObject().apply {
                put("id", c.getString(0)); put("invoice_number", c.getString(1))
                if (c.isNull(2)) put("party_id", JSONObject.NULL) else put("party_id", c.getString(2))
                put("sale_type", c.getString(3)); put("warehouse_id", c.getString(4))
                put("invoice_date", c.getString(5)); put("status", c.getString(6))
                put("subtotal_usd_cents", c.getLong(7)); put("discount_usd_cents", c.getLong(8))
                put("total_usd_cents", c.getLong(9)); put("exchange_rate_syp_per_usd_milli", c.getLong(10))
                if (c.isNull(11)) put("notes", JSONObject.NULL) else put("notes", c.getString(11))
                if (c.isNull(12)) put("created_by", JSONObject.NULL) else put("created_by", c.getString(12))
                put("posted_at", c.getString(13)); put("posted_operating_date", c.getString(14))
            }
        }
        val items = org.json.JSONArray()
        db.rawQuery(
            """SELECT id,product_id,quantity,${if (invoiceType == "SALE") "unit_price_usd_cents,unit_cost_usd_cents" else "unit_cost_usd_cents,unit_cost_usd_cents"},line_total_usd_cents
               FROM $itemTable WHERE $fk=? ORDER BY id""".trimIndent(), arrayOf(invoiceId)
        ).use { c ->
            while (c.moveToNext()) items.put(JSONObject().apply {
                put("id", c.getString(0)); put("product_id", c.getString(1))
                put("quantity", c.getDouble(2)); put("unit_price_or_cost_usd_cents", c.getLong(3))
                put("unit_cost_usd_cents", c.getLong(4)); put("line_total_usd_cents", c.getLong(5))
            })
        }
        header.put("items", items)
        header.put("cash_account_id", cashAccountId ?: JSONObject.NULL)
        if (invoiceType == "SALE" && cashAccountId != null) {
            val receipts = org.json.JSONArray()
            db.rawQuery(
                """SELECT id,amount_minor,currency_code,exchange_rate_syp_per_usd_milli
                   FROM cash_transactions WHERE source_type='SALES_INVOICE' AND source_id=?
                     AND transaction_type='SALE_RECEIPT' ORDER BY created_at,id""".trimIndent(),
                arrayOf(invoiceId)
            ).use { c -> while (c.moveToNext()) receipts.put(JSONObject().apply {
                put("cash_transaction_id", c.getString(0)); put("amount_minor", c.getLong(1))
                put("currency_code", c.getString(2))
                if (c.isNull(3)) put("exchange_rate_syp_per_usd_milli", JSONObject.NULL)
                else put("exchange_rate_syp_per_usd_milli", c.getLong(3))
            }) }
            header.put("cash_receipts", receipts)
        }
        return enqueueLocalOperation(
            db, actorUserId, operationId, "POST_${invoiceType}_INVOICE",
            "${invoiceType}_INVOICE", invoiceId, "CREATE", header.toString()
        )
    }

    fun pendingOutbox(limit: Int = 100): List<SyncOutboxItem> {
        require(limit in 1..500)
        val items = mutableListOf<SyncOutboxItem>()
        database.readableDatabase.rawQuery(
            """SELECT e.operation_id,e.sequence_number,e.operation_type,e.entity_type,e.entity_id,
                      e.payload_json,e.payload_sha256,e.created_at,q.retry_count
               FROM sync_operation_envelopes e JOIN sync_queue q ON q.operation_id=e.operation_id
               WHERE q.status IN ('PENDING','FAILED') ORDER BY e.sequence_number LIMIT ?""".trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) items.add(SyncOutboxItem(
                c.getString(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5), c.getString(6), c.getString(7), c.getInt(8)
            ))
        }
        return items
    }

    fun hasConfiguredPrimary(): Boolean =
        state(database.readableDatabase, "authority_device_id") == SyncDeviceIdentity.getOrCreate(context)

    private fun requirePrimaryManager(db: SQLiteDatabase, actorUserId: String) {
        PermissionPolicy.require(db, actorUserId, PermissionPolicy.SYNC_MANAGE)
        check(state(db, "authority_device_id") == SyncDeviceIdentity.getOrCreate(context)) {
            "يجب إعداد هذا الهاتف كالجهاز الأساسي أولاً"
        }
    }

    private fun state(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT state_value FROM sync_protocol_state WHERE state_key=?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    private fun putState(db: SQLiteDatabase, key: String, value: String, now: String) {
        db.insertWithOnConflict("sync_protocol_state", null, ContentValues().apply {
            put("state_key", key); put("state_value", value); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun audit(db: SQLiteDatabase, actor: String, action: String, entityId: String,
                      before: String?, after: String?, now: String) {
        db.insertOrThrow("audit_log", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("user_id", actor); put("action", action)
            put("entity_type", "SYNC_DEVICE"); put("entity_id", entityId)
            if (before != null) put("before_json", before)
            if (after != null) put("after_json", after)
            put("device_id", SyncDeviceIdentity.getOrCreate(context)); put("created_at", now)
        })
    }

    private fun randomToken(): String = ByteArray(32).also(random::nextBytes)
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun timestamp(atMillis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(atMillis))

    companion object {
        private const val INVITATION_TTL_MS = 10 * 60 * 1000L
        private const val MAX_PAYLOAD_CHARS = 128 * 1024
    }
}
