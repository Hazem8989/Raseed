package com.hazem.dailystore.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SalesProfitSummary(
    val fromDate: String?,
    val throughDate: String?,
    val invoiceCount: Int,
    val revenueUsdCents: Long,
    val cogsUsdCents: Long,
    val grossProfitUsdCents: Long,
    val uncostedInvoiceCount: Int,
    val isComplete: Boolean
)

/**
 * Gross trading result from posted sales and their immutable FIFO allocations.
 * Collections on credit invoices do not create revenue a second time.
 * Expenses, returns and operating costs are intentionally not netted here.
 */
class SalesProfitRepository(context: Context) {
    private val db = AppDatabase.getInstance(context).readableDatabase

    fun today(): SalesProfitSummary = summarize(todayStamp(), todayStamp())

    fun allTime(): SalesProfitSummary = summarize(null, null)

    fun summarize(fromDate: String?, throughDate: String?): SalesProfitSummary {
        require((fromDate == null) == (throughDate == null)) { "يجب تحديد تاريخي البداية والنهاية معاً" }
        if (fromDate != null) require(fromDate <= throughDate!!) { "نطاق التاريخ غير صالح" }
        val where = StringBuilder(" WHERE status IN ('POSTED','PARTIALLY_PAID','PAID')")
        val args = mutableListOf<String>()
        if (fromDate != null) {
            where.append(" AND posted_operating_date>=? AND posted_operating_date<=?")
            args.add(fromDate)
            args.add(throughDate!!)
        }
        var count = 0
        var revenue = 0L
        var knownCogs = 0L
        var uncosted = 0
        db.rawQuery(
            "SELECT id,total_usd_cents FROM sales_invoices$where ORDER BY updated_at,id",
            args.toTypedArray()
        ).use { invoices ->
            while (invoices.moveToNext()) {
                val invoiceId = invoices.getString(0)
                count++
                revenue = Math.addExact(revenue, invoices.getLong(1))
                var lineCount = 0
                var missing = false
                db.rawQuery(
                    "SELECT cogs_usd_cents FROM sales_items WHERE sales_invoice_id=?",
                    arrayOf(invoiceId)
                ).use { items ->
                    while (items.moveToNext()) {
                        lineCount++
                        if (items.isNull(0)) missing = true
                        else knownCogs = Math.addExact(knownCogs, items.getLong(0))
                    }
                }
                if (lineCount == 0 || missing) uncosted++
            }
        }
        val complete = uncosted == 0
        return SalesProfitSummary(
            fromDate, throughDate, count, revenue, knownCogs,
            if (complete) Math.subtractExact(revenue, knownCogs) else 0L,
            uncosted, complete
        )
    }

    private fun todayStamp(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
