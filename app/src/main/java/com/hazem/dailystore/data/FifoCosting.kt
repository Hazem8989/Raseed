package com.hazem.dailystore.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/**
 * FIFO cost-layer operations. Callers must include these operations in the same
 * SQLite transaction as the matching inventory movement and invoice posting.
 */
internal object FifoCosting {
    private data class Layer(
        val id: String,
        val remaining: BigDecimal,
        val unitCost: Long?
    )

    fun addLayer(
        db: SQLiteDatabase,
        movementId: String,
        productId: String,
        warehouseId: String,
        quantity: Double,
        unitCostUsdCents: Long,
        sourceType: String?,
        sourceId: String?,
        receivedAt: String,
        sourceItemId: String? = null
    ) {
        require(quantity > 0.0 && quantity.isFinite())
        val rate = when (sourceType) {
            "PURCHASE_INVOICE" -> sourceId?.let {
                db.rawQuery(
                    "SELECT exchange_rate_syp_per_usd_milli FROM purchase_invoices WHERE id=?",
                    arrayOf(it)
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            }
            else -> db.rawQuery(
                "SELECT rate_syp_per_usd_milli FROM exchange_rates ORDER BY effective_at DESC,created_at DESC LIMIT 1",
                null
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("product_id", productId)
            put("warehouse_id", warehouseId)
            put("source_movement_id", movementId)
            put("source_type", sourceType)
            put("source_id", sourceId)
            if (sourceItemId == null) putNull("source_item_id") else put("source_item_id", sourceItemId)
            put("received_quantity", quantity)
            put("remaining_quantity", quantity)
            if (unitCostUsdCents > 0L) put("unit_cost_usd_cents", unitCostUsdCents)
            else putNull("unit_cost_usd_cents")
            if (rate != null && rate > 0L) put("purchase_exchange_rate_syp_per_usd_milli", rate)
            else putNull("purchase_exchange_rate_syp_per_usd_milli")
            put("received_at", receivedAt)
        }
        db.insertOrThrow("inventory_cost_layers", null, values)
    }

    /**
     * Consumes the oldest layers and records exact per-layer allocations.
     * Returns null when any consumed layer has unknown cost; this is deliberately
     * different from a zero-cost sale.
     */
    fun consumeForSale(
        db: SQLiteDatabase,
        invoiceId: String,
        salesItemId: String?,
        productId: String,
        warehouseId: String,
        quantity: Double,
        createdAt: String,
        recordSaleAllocation: Boolean = true
    ): Long? {
        require(quantity > 0.0 && quantity.isFinite())
        val toConsume = BigDecimal.valueOf(quantity)
        val layers = mutableListOf<Layer>()
        db.rawQuery(
            """SELECT id,remaining_quantity,unit_cost_usd_cents
               FROM inventory_cost_layers
               WHERE product_id=? AND warehouse_id=? AND remaining_quantity>0
               ORDER BY received_at ASC,rowid ASC""".trimIndent(),
            arrayOf(productId, warehouseId)
        ).use { c ->
            while (c.moveToNext()) layers.add(
                Layer(c.getString(0), BigDecimal.valueOf(c.getDouble(1)),
                    if (c.isNull(2)) null else c.getLong(2))
            )
        }
        val available = layers.fold(BigDecimal.ZERO) { sum, layer -> sum.add(layer.remaining) }
        require(available >= toConsume) {
            "طبقات تكلفة FIFO لا تغطي الكمية المطلوبة؛ أوقف الاعتماد حتى تتم مطابقة المخزون"
        }

        data class PlannedAllocation(
            val layer: Layer,
            val consumed: BigDecimal,
            val remaining: BigDecimal,
            val exactCost: BigDecimal?
        )
        val plan = mutableListOf<PlannedAllocation>()
        var left = toConsume
        for (layer in layers) {
            if (left.signum() <= 0) break
            val consumed = left.min(layer.remaining)
            plan.add(
                PlannedAllocation(
                    layer,
                    consumed,
                    layer.remaining.subtract(consumed),
                    layer.unitCost?.let { consumed.multiply(BigDecimal.valueOf(it)) }
                )
            )
            left = left.subtract(consumed)
        }
        check(left.signum() == 0) { "تعذر استهلاك كامل الكمية من طبقات FIFO" }

        val costKnown = plan.all { it.exactCost != null }
        val exactTotal = if (costKnown) plan.fold(BigDecimal.ZERO) { sum, item ->
            sum.add(item.exactCost!!)
        } else null
        val roundedTotal = exactTotal?.setScale(0, RoundingMode.HALF_UP)?.longValueExact()
        var allocatedKnownCost = 0L

        plan.forEachIndexed { index, item ->
            val changed = db.update(
                "inventory_cost_layers",
                ContentValues().apply { put("remaining_quantity", item.remaining.toDouble()) },
                "id=? AND remaining_quantity>=?",
                arrayOf(item.layer.id, item.consumed.toString())
            )
            check(changed == 1) { "تغيرت طبقة التكلفة أثناء الاعتماد؛ أعد المحاولة" }

            val allocationCost = when {
                item.exactCost == null -> null
                roundedTotal != null && index == plan.lastIndex ->
                    Math.subtractExact(roundedTotal, allocatedKnownCost)
                roundedTotal != null -> item.exactCost.setScale(0, RoundingMode.DOWN)
                    .longValueExact().also { allocatedKnownCost = Math.addExact(allocatedKnownCost, it) }
                else -> item.exactCost.setScale(0, RoundingMode.HALF_UP).longValueExact()
            }
            if (recordSaleAllocation) {
                val allocation = ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("sales_invoice_id", invoiceId)
                    if (salesItemId == null) putNull("sales_item_id") else put("sales_item_id", salesItemId)
                    put("cost_layer_id", item.layer.id)
                    put("quantity", item.consumed.toDouble())
                    if (item.layer.unitCost == null) putNull("unit_cost_usd_cents")
                    else put("unit_cost_usd_cents", item.layer.unitCost)
                    if (allocationCost == null) putNull("cost_usd_cents")
                    else put("cost_usd_cents", allocationCost)
                    put("created_at", createdAt)
                }
                db.insertOrThrow("sales_item_cost_allocations", null, allocation)
            }
        }
        if (salesItemId != null) {
            val changed = db.update(
                "sales_items",
                ContentValues().apply {
                    if (roundedTotal == null) putNull("cogs_usd_cents")
                    else put("cogs_usd_cents", roundedTotal)
                },
                "id=? AND sales_invoice_id=?",
                arrayOf(salesItemId, invoiceId)
            )
            check(changed == 1) { "تعذر حفظ تكلفة البضاعة المباعة على بند الفاتورة" }
        }
        return roundedTotal
    }
}
