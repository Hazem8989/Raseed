package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class InventoryAdjustmentResult(
    val adjustmentId: String,
    val movementId: String,
    val productId: String,
    val warehouseId: String,
    val adjustmentType: String,
    val quantity: BigDecimal,
    val quantityBefore: BigDecimal,
    val quantityAfter: BigDecimal,
    val unitCostUsdCents: Long?,
    val costUsdCents: Long?,
    val reason: String,
    val replayed: Boolean
)

/**
 * Posts stocktake corrections as atomic inventory movements and FIFO layer changes.
 * Positive corrections require an explicit cost; negative corrections consume the
 * oldest layers without creating fake sales or sale-item COGS allocations.
 */
class InventoryAdjustmentRepository(context: Context) {
    private val context = context.applicationContext
    private val database = AppDatabase.getInstance(context)

    fun postAdjustment(
        actorUserId: String,
        productId: String,
        quantityDelta: BigDecimal,
        reason: String,
        operationId: String,
        unitCostUsdCents: Long? = null,
        warehouseId: String? = null,
        occurredAt: String? = null, syncOrigin: Boolean = false
    ): InventoryAdjustmentResult {
        require(actorUserId.isNotBlank() && productId.isNotBlank() && operationId.isNotBlank()) {
            "بيانات تسوية المخزون غير مكتملة"
        }
        require(quantityDelta.signum() != 0) { "فرق الكمية يجب ألا يساوي صفراً" }
        val cleanReason = reason.trim()
        require(cleanReason.isNotEmpty()) { "سبب التسوية مطلوب" }
        val increase = quantityDelta.signum() > 0
        if (increase) {
            require(unitCostUsdCents != null && unitCostUsdCents > 0L) {
                "يجب تحديد تكلفة شراء موجبة للكمية المضافة حتى لا يتشوه رأس مال المخزون"
            }
        } else {
            require(unitCostUsdCents == null) {
                "تكلفة الكمية المنقوصة تُحسب تلقائياً وفق FIFO ولا تُدخل يدوياً"
            }
        }
        val absoluteQuantity = quantityDelta.abs()
        val payloadHash = hash(
            "$productId|$warehouseId|${quantityDelta.stripTrailingZeros().toPlainString()}|" +
                "${unitCostUsdCents ?: ""}|$cleanReason"
        )
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val localRole=db.rawQuery("SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'",null)
                .use { c -> if(c.moveToFirst()) c.getString(0) else null }
            if(localRole=="SECONDARY") {
                PermissionPolicy.requireAssigned(db,actorUserId,PermissionPolicy.INVENTORY_ADJUST)
                val resolvedWarehouse=warehouseId?.takeIf { it.isNotBlank() } ?: db.rawQuery(
                    "SELECT id FROM warehouses WHERE is_active=1 ORDER BY is_default DESC,created_at,id LIMIT 1",null
                ).use { c -> require(c.moveToFirst()) { "لا يوجد مستودع نشط" }; c.getString(0) }
                val before=db.rawQuery("SELECT quantity FROM inventory_balances WHERE product_id=? AND warehouse_id=?",
                    arrayOf(productId,resolvedWarehouse)).use { c -> if(c.moveToFirst()) BigDecimal.valueOf(c.getDouble(0)) else BigDecimal.ZERO }
                val after=before.add(quantityDelta)
                require(after.signum()>=0) { "لا يمكن أن تؤدي التسوية إلى مخزون سالب على النسخة المحلية" }
                val priorPayload=db.rawQuery("SELECT payload_json FROM sync_operation_envelopes WHERE operation_id=?",arrayOf(operationId))
                    .use { c -> if(c.moveToFirst()) org.json.JSONObject(c.getString(0)) else null }
                val eventTime=occurredAt ?: priorPayload?.optString("occurred_at")
                    ?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")) } ?: timestamp()
                val movementDate=priorPayload?.optString("operating_date")
                    ?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: StoreTime.operatingDate(db,eventTime)
                val payload=org.json.JSONObject().put("product_id",productId).put("warehouse_id",resolvedWarehouse)
                    .put("quantity_delta",quantityDelta.stripTrailingZeros().toPlainString())
                    .put("unit_cost_usd_cents",unitCostUsdCents ?: org.json.JSONObject.NULL)
                    .put("reason",cleanReason).put("occurred_at",eventTime)
                    .put("operating_date",movementDate)
                val pendingId="pending-adjustment-$operationId"
                SyncProtocolRepository(context).enqueueSecondaryCommand(
                    db,actorUserId,operationId,"POST_INVENTORY_ADJUSTMENT","INVENTORY_ADJUSTMENT",pendingId,payload.toString())
                db.setTransactionSuccessful()
                val provisionalCost=if(increase) BigDecimal.valueOf(unitCostUsdCents!!).multiply(absoluteQuantity)
                    .setScale(0,RoundingMode.HALF_UP).longValueExact() else null
                return InventoryAdjustmentResult(pendingId,"pending-movement-$operationId",productId,resolvedWarehouse,
                    if(increase) "INCREASE" else "DECREASE",absoluteQuantity,before,after,unitCostUsdCents,
                    provisionalCost,cleanReason,false)
            }
            PermissionPolicy.require(db, actorUserId, PermissionPolicy.INVENTORY_ADJUST)
            val existing = db.rawQuery(
                "SELECT operation_type,payload_hash,result_reference FROM operation_registry WHERE operation_id=?",
                arrayOf(operationId)
            ).use { c ->
                if (c.moveToFirst()) Triple(
                    c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getString(2)
                ) else null
            }
            if (existing != null) {
                require(existing.first == "POST_INVENTORY_ADJUSTMENT" && existing.second == payloadHash) {
                    "معرّف العملية مستخدم لتسوية مختلفة أو بيانات مختلفة"
                }
                val result = existing.third?.let { loadResult(db, it, true) }
                    ?: error("سجل العملية لا يحتوي مرجع التسوية")
                db.setTransactionSuccessful()
                return result
            }

            val product = db.rawQuery(
                """SELECT p.track_inventory,p.is_active,u.decimal_places
                   FROM products p JOIN units u ON u.id=p.unit_id WHERE p.id=?""".trimIndent(),
                arrayOf(productId)
            ).use { c ->
                require(c.moveToFirst()) { "الصنف غير موجود" }
                require(c.getInt(0) == 1) { "هذا الصنف لا يتتبع المخزون ولا يقبل تسوية كمية" }
                require(c.getInt(1) == 1) { "لا يمكن تسوية صنف موقوف" }
                c.getInt(2)
            }
            val precision = absoluteQuantity.stripTrailingZeros().scale().coerceAtLeast(0)
            require(precision <= product) { "دقة الكمية تتجاوز دقة وحدة قياس الصنف" }

            val resolvedWarehouse = warehouseId?.takeIf { it.isNotBlank() } ?: db.rawQuery(
                "SELECT id FROM warehouses WHERE is_active=1 ORDER BY is_default DESC,created_at,id LIMIT 1",
                null
            ).use { c ->
                require(c.moveToFirst()) { "لا يوجد مستودع نشط لتسجيل التسوية" }
                c.getString(0)
            }
            require(db.rawQuery(
                "SELECT 1 FROM warehouses WHERE id=? AND is_active=1",
                arrayOf(resolvedWarehouse)
            ).use { it.moveToFirst() }) { "المستودع غير موجود أو موقوف" }

            val before = db.rawQuery(
                "SELECT quantity FROM inventory_balances WHERE product_id=? AND warehouse_id=?",
                arrayOf(productId, resolvedWarehouse)
            ).use { c -> if (c.moveToFirst()) BigDecimal.valueOf(c.getDouble(0)) else BigDecimal.ZERO }
            val after = before.add(quantityDelta)
            require(after.signum() >= 0) {
                "لا يمكن أن تؤدي التسوية إلى مخزون سالب. الرصيد الحالي: $before"
            }

            val adjustmentId = UUID.randomUUID().toString()
            val movementId = UUID.randomUUID().toString()
            val now = occurredAt ?: timestamp()
            val type = if (increase) "INCREASE" else "DECREASE"
            val movementType = if (increase) "ADJUSTMENT_IN" else "ADJUSTMENT_OUT"
            val cost = if (increase) {
                BigDecimal.valueOf(unitCostUsdCents!!)
                    .multiply(absoluteQuantity)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact()
            } else {
                FifoCosting.consumeForSale(
                    db, adjustmentId, null, productId, resolvedWarehouse,
                    absoluteQuantity.toDouble(), now, recordSaleAllocation = false
                )
            }

            db.insertOrThrow("inventory_movements", null, ContentValues().apply {
                put("id", movementId); put("product_id", productId)
                put("warehouse_id", resolvedWarehouse); put("movement_type", movementType)
                put("quantity_delta", quantityDelta.toDouble())
                put("unit_cost_usd_cents", unitCostUsdCents ?: 0L)
                put("source_type", "INVENTORY_ADJUSTMENT"); put("source_id", adjustmentId)
                put("operation_id", operationId); put("note", cleanReason)
                put("created_by", actorUserId); put("created_at", now)
            })
            if (increase) {
                FifoCosting.addLayer(
                    db, movementId, productId, resolvedWarehouse, absoluteQuantity.toDouble(),
                    unitCostUsdCents!!, "INVENTORY_ADJUSTMENT", adjustmentId, now
                )
            }

            val balanceValues = ContentValues().apply {
                put("quantity", after.toDouble()); put("updated_at", now)
            }
            val changed = db.update(
                "inventory_balances", balanceValues,
                "product_id=? AND warehouse_id=?", arrayOf(productId, resolvedWarehouse)
            )
            if (changed == 0) {
                db.insertOrThrow("inventory_balances", null, ContentValues().apply {
                    put("product_id", productId); put("warehouse_id", resolvedWarehouse)
                    put("quantity", after.toDouble()); put("updated_at", now)
                })
            } else require(changed == 1) { "تعذر تحديث رصيد المخزون" }

            db.insertOrThrow("inventory_adjustments", null, ContentValues().apply {
                put("id", adjustmentId); put("operation_id", operationId)
                put("product_id", productId); put("warehouse_id", resolvedWarehouse)
                put("inventory_movement_id", movementId); put("adjustment_type", type)
                put("quantity", absoluteQuantity.toDouble())
                put("quantity_before", before.toDouble()); put("quantity_after", after.toDouble())
                if (unitCostUsdCents == null) putNull("unit_cost_usd_cents")
                else put("unit_cost_usd_cents", unitCostUsdCents)
                if (cost == null) putNull("cost_usd_cents") else put("cost_usd_cents", cost)
                put("reason", cleanReason); put("created_by", actorUserId); put("created_at", now)
            })
            db.insertOrThrow("operation_registry", null, ContentValues().apply {
                put("operation_id", operationId); put("operation_type", "POST_INVENTORY_ADJUSTMENT")
                put("payload_hash", payloadHash); put("status", "COMPLETED")
                put("result_reference", adjustmentId); put("created_at", now); put("completed_at", now)
            })
            db.insertOrThrow("audit_log", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString()); put("user_id", actorUserId)
                put("action", "INVENTORY_ADJUSTMENT_POSTED")
                put("entity_type", "INVENTORY_ADJUSTMENT"); put("entity_id", adjustmentId)
                put("after_json", org.json.JSONObject().put("product_id", productId)
                    .put("warehouse_id", resolvedWarehouse).put("type", type)
                    .put("quantity", absoluteQuantity.toPlainString())
                    .put("quantity_before", before.toPlainString())
                    .put("quantity_after", after.toPlainString())
                    .put("cost_usd_cents", cost).put("reason", cleanReason).toString())
                put("created_at", now)
            })
            if(!syncOrigin) {
                SyncProtocolRepository(context).enqueueLocalOperation(
                    db, actorUserId, operationId, "POST_INVENTORY_ADJUSTMENT", "INVENTORY_ADJUSTMENT",
                    adjustmentId, "CREATE",
                    org.json.JSONObject().put("adjustment_id", adjustmentId).put("movement_id", movementId)
                        .put("product_id", productId).put("warehouse_id", resolvedWarehouse)
                        .put("adjustment_type", type).put("quantity", absoluteQuantity.toPlainString())
                        .put("quantity_before", before.toPlainString()).put("quantity_after", after.toPlainString())
                        .put("unit_cost_usd_cents", unitCostUsdCents ?: org.json.JSONObject.NULL)
                        .put("cost_usd_cents", cost ?: org.json.JSONObject.NULL)
                        .put("reason", cleanReason).put("created_at", now).toString()
                )
            }
            db.setTransactionSuccessful()
            return InventoryAdjustmentResult(
                adjustmentId, movementId, productId, resolvedWarehouse, type,
                absoluteQuantity, before, after, unitCostUsdCents, cost, cleanReason, false
            )
        } finally { db.endTransaction(); runCatching { SyncRelaySession.flushPending() } }
    }

    fun listRecent(limit: Int = 100): List<InventoryAdjustmentResult> {
        require(limit in 1..500) { "عدد السجلات المطلوب غير صالح" }
        val result = mutableListOf<InventoryAdjustmentResult>()
        database.readableDatabase.rawQuery(
            """SELECT id,inventory_movement_id,product_id,warehouse_id,adjustment_type,
                      quantity,quantity_before,quantity_after,unit_cost_usd_cents,
                      cost_usd_cents,reason
               FROM inventory_adjustments ORDER BY created_at DESC,id DESC LIMIT ?""".trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) result.add(InventoryAdjustmentResult(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                BigDecimal.valueOf(c.getDouble(5)), BigDecimal.valueOf(c.getDouble(6)),
                BigDecimal.valueOf(c.getDouble(7)), if (c.isNull(8)) null else c.getLong(8),
                if (c.isNull(9)) null else c.getLong(9), c.getString(10), false
            ))
        }
        return result
    }

    private fun loadResult(db: SQLiteDatabase, id: String, replayed: Boolean): InventoryAdjustmentResult =
        db.rawQuery(
            """SELECT id,inventory_movement_id,product_id,warehouse_id,adjustment_type,
                      quantity,quantity_before,quantity_after,unit_cost_usd_cents,
                      cost_usd_cents,reason
               FROM inventory_adjustments WHERE id=?""".trimIndent(), arrayOf(id)
        ).use { c ->
            require(c.moveToFirst()) { "تسوية المخزون السابقة غير موجودة" }
            InventoryAdjustmentResult(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                BigDecimal.valueOf(c.getDouble(5)), BigDecimal.valueOf(c.getDouble(6)),
                BigDecimal.valueOf(c.getDouble(7)), if (c.isNull(8)) null else c.getLong(8),
                if (c.isNull(9)) null else c.getLong(9), c.getString(10), replayed
            )
        }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
