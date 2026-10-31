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
    val isComplete: Boolean
)

data class DailyInventoryCapital(
    val current: InventoryValuation,
    val previousClose: InventoryValuation,
    val changeUsdCents: Long
)

/**
 * Reconstructs on-hand inventory cost from the immutable movement ledger using FIFO.
 * A sale consumes the oldest available cost layers; a purchase or positive adjustment
 * creates a new layer at its recorded unit cost. Unmatched quantities are never silently
 * assigned the current product cost: they are reported as unvalued.
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
        val yesterday = valueAt(yesterdayEnd, reconcileWithCurrentBalances = false)
        val current = valueAt(now, reconcileWithCurrentBalances = true)
        return DailyInventoryCapital(current, yesterday,
            Math.subtractExact(current.totalCostUsdCents, yesterday.totalCostUsdCents))
    }

    private fun valueAt(cutoff: Date, reconcileWithCurrentBalances: Boolean): InventoryValuation {
        val cutoffText = utcStamp(cutoff)
        val products = linkedMapOf<String, MutableList<Lot>>()
        val deficits = mutableMapOf<String, Double>()
        val unvalued = mutableMapOf<String, Double>()
        val balances = mutableMapOf<String, Double>()
        val valuedCosts = mutableMapOf<String, BigDecimal>()

        db.rawQuery(
            """SELECT p.id, COALESCE(SUM(ib.quantity),0)
               FROM products p LEFT JOIN inventory_balances ib ON ib.product_id=p.id
               WHERE p.track_inventory=1 GROUP BY p.id""".trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) balances[c.getString(0)] = c.getDouble(1)
        }

        db.rawQuery(
            """SELECT m.product_id,m.quantity_delta,m.unit_cost_usd_cents
               FROM inventory_movements m
               JOIN products p ON p.id=m.product_id
               WHERE p.track_inventory=1 AND m.created_at<=?
               ORDER BY m.created_at ASC,m.id ASC""".trimIndent(),
            arrayOf(cutoffText)
        ).use { c ->
            while (c.moveToNext()) {
                val productId = c.getString(0)
                val delta = c.getDouble(1)
                val cost = c.getLong(2)
                val lots = products.getOrPut(productId) { mutableListOf() }
                if (delta > 0.0) {
                    var incoming = delta
                    val deficit = deficits[productId] ?: 0.0
                    if (deficit > 0.0000001) {
                        val covered = minOf(incoming, deficit)
                        deficits[productId] = deficit - covered
                        incoming -= covered
                        if (deficits[productId]!! <= 0.0000001) deficits.remove(productId)
                    }
                    if (incoming > 0.0000001) {
                        if (cost > 0L) lots.add(Lot(incoming, cost))
                        else unvalued[productId] = (unvalued[productId] ?: 0.0) + incoming
                    }
                } else if (delta < 0.0) {
                    var outgoing = -delta
                    while (outgoing > 0.0000001 && lots.isNotEmpty()) {
                        val oldest = lots.first()
                        val consumed = minOf(outgoing, oldest.quantity)
                        oldest.quantity -= consumed
                        outgoing -= consumed
                        if (oldest.quantity <= 0.0000001) lots.removeAt(0)
                    }
                    if (outgoing > 0.0000001) {
                        deficits[productId] = (deficits[productId] ?: 0.0) + outgoing
                    }
                }
            }
        }

        var total = BigDecimal.ZERO
        var valuedQty = 0.0
        var unvaluedQty = unvalued.values.sum() + deficits.values.sum()
        val productIds = (products.keys + balances.keys + unvalued.keys + deficits.keys).toSet()
        for (id in productIds) {
            val lots = products[id] ?: mutableListOf()
            var lotQuantity = lots.sumOf { it.quantity }
            if (reconcileWithCurrentBalances) {
                val actual = balances[id] ?: 0.0
                val ledgerNet = lotQuantity - (deficits[id] ?: 0.0) - (unvalued[id] ?: 0.0)
                val mismatch = actual - ledgerNet
                if (mismatch > 0.0000001) {
                    // Physical balance exceeds the supported cost ledger: keep it visible as unvalued.
                    unvaluedQty += mismatch
                } else if (mismatch < -0.0000001) {
                    // Missing outbound movement. Consume the oldest remaining layers conservatively.
                    var excess = -mismatch
                    unvaluedQty += excess
                    while (excess > 0.0000001 && lots.isNotEmpty()) {
                        val oldest = lots.first()
                        val consumed = minOf(excess, oldest.quantity)
                        oldest.quantity -= consumed
                        excess -= consumed
                        if (oldest.quantity <= 0.0000001) lots.removeAt(0)
                    }
                }
            }
            for (lot in lots) {
                if (lot.quantity <= 0.0000001) continue
                total = total.add(BigDecimal.valueOf(lot.unitCostUsdCents)
                    .multiply(BigDecimal.valueOf(lot.quantity)))
                valuedQty += lot.quantity
            }
        }
        val totalCents = total.setScale(0, RoundingMode.HALF_UP).longValueExact()
        return InventoryValuation(
            asOfLabel = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(cutoff),
            totalCostUsdCents = totalCents,
            valuedQuantity = valuedQty,
            unvaluedQuantity = unvaluedQty,
            isComplete = unvaluedQty <= 0.0000001
        )
    }

    private fun utcStamp(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
}
