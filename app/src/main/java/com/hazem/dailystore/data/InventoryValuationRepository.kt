package com.hazem.dailystore.data

import android.content.Context
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class InventoryValuation(
    val asOfLabel: String,
    val totalCostUsdCents: Long,
    val valuedQuantity: Double,
    val unvaluedQuantity: Double,
    val unvaluedProductCount: Int,
    val isComplete: Boolean
)

data class DailyInventoryCapital(
    val current: InventoryValuation,
    val previousClose: InventoryValuation,
    val changeUsdCents: Long
)

/**
 * Reconstructs inventory cost from the immutable movement ledger using FIFO.
 * Each warehouse is valued independently. Products with any uncosted quantity,
 * negative-stock history or a current ledger/balance mismatch are excluded from
 * the confirmed capital subtotal and explicitly reported as incomplete.
 */
class InventoryValuationRepository(context: Context) {
    private val db = AppDatabase.getInstance(context).readableDatabase
    private data class Lot(var quantity: Double, val unitCostUsdCents: Long)

    fun dailyCapital(): DailyInventoryCapital {
        val now = Date()
        val todayStart = Calendar.getInstance().apply {
            time = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val yesterdayEnd = Date(todayStart.timeInMillis - 1L)
        val yesterday = valueAt(yesterdayEnd, false)
        val current = valueAt(now, true)
        return DailyInventoryCapital(
            current,
            yesterday,
            Math.subtractExact(current.totalCostUsdCents, yesterday.totalCostUsdCents)
        )
    }

    private fun valueAt(cutoff: Date, reconcileWithCurrentBalances: Boolean): InventoryValuation {
        val cutoffText = utcStamp(cutoff)
        val layers = linkedMapOf<String, MutableList<Lot>>()
        val deficits = mutableMapOf<String, Double>()
        val uncosted = mutableMapOf<String, Double>()
        val balances = mutableMapOf<String, Double>()
        val affectedProducts = mutableSetOf<String>()

        db.rawQuery(
            """SELECT p.id,ib.warehouse_id,COALESCE(SUM(ib.quantity),0)
               FROM products p LEFT JOIN inventory_balances ib ON ib.product_id=p.id
               WHERE p.track_inventory=1 GROUP BY p.id,ib.warehouse_id""".trimIndent(), null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0) + "|" +
                    if (cursor.isNull(1)) "NO_WAREHOUSE" else cursor.getString(1)
                balances[key] = cursor.getDouble(2)
            }
        }

        db.rawQuery(
            """SELECT m.product_id,m.warehouse_id,m.quantity_delta,m.unit_cost_usd_cents
               FROM inventory_movements m JOIN products p ON p.id=m.product_id
               WHERE p.track_inventory=1 AND m.created_at<=?
               ORDER BY m.created_at ASC,m.id ASC""".trimIndent(),
            arrayOf(cutoffText)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val productId = cursor.getString(0)
                val key = productId + "|" + cursor.getString(1)
                val delta = cursor.getDouble(2)
                val unitCost = cursor.getLong(3)
                val queue = layers.getOrPut(key) { mutableListOf() }

                if (delta > 0.0) {
                    var incoming = delta
                    val deficit = deficits[key] ?: 0.0
                    if (deficit > 0.0000001) {
                        val covered = minOf(incoming, deficit)
                        deficits[key] = deficit - covered
                        incoming -= covered
                        if (deficits[key]!! <= 0.0000001) deficits.remove(key)
                    }
                    if (incoming > 0.0000001) {
                        if (unitCost > 0L) queue.add(Lot(incoming, unitCost))
                        else {
                            uncosted[key] = (uncosted[key] ?: 0.0) + incoming
                            affectedProducts.add(productId)
                        }
                    }
                } else if (delta < 0.0) {
                    var outgoing = -delta
                    while (outgoing > 0.0000001 && queue.isNotEmpty()) {
                        val oldest = queue.first()
                        val consumed = minOf(outgoing, oldest.quantity)
                        oldest.quantity -= consumed
                        outgoing -= consumed
                        if (oldest.quantity <= 0.0000001) queue.removeAt(0)
                    }
                    if (outgoing > 0.0000001) {
                        deficits[key] = (deficits[key] ?: 0.0) + outgoing
                        affectedProducts.add(productId)
                    }
                }
            }
        }

        var unvaluedAmount = uncosted.values.sum() + deficits.values.sum()
        val allKeys = (layers.keys + balances.keys + uncosted.keys + deficits.keys).toSet()
        if (reconcileWithCurrentBalances) {
            for (key in allKeys) {
                val ledgerQuantity = (layers[key]?.sumOf { it.quantity } ?: 0.0) +
                    (uncosted[key] ?: 0.0) - (deficits[key] ?: 0.0)
                val actualQuantity = balances[key] ?: 0.0
                val difference = actualQuantity - ledgerQuantity
                if (kotlin.math.abs(difference) > 0.0000001) {
                    affectedProducts.add(key.substringBefore("|"))
                    unvaluedAmount += kotlin.math.abs(difference)
                }
            }
        }

        var total = BigDecimal.ZERO
        var valuedQuantity = 0.0
        for (key in allKeys) {
            val productId = key.substringBefore("|")
            if (productId in affectedProducts) continue
            for (lot in layers[key].orEmpty()) {
                if (lot.quantity <= 0.0000001) continue
                total = total.add(
                    BigDecimal.valueOf(lot.unitCostUsdCents)
                        .multiply(BigDecimal.valueOf(lot.quantity))
                )
                valuedQuantity += lot.quantity
            }
        }

        return InventoryValuation(
            asOfLabel = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(cutoff),
            totalCostUsdCents = total.setScale(0, RoundingMode.HALF_UP).longValueExact(),
            valuedQuantity = valuedQuantity,
            unvaluedQuantity = unvaluedAmount,
            unvaluedProductCount = affectedProducts.size,
            isComplete = affectedProducts.isEmpty()
        )
    }

    private fun utcStamp(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
}
