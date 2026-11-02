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
    val isComplete: Boolean,
    val returnCount: Int = 0,
    val returnUsdCents: Long = 0L,
    val returnedCogsUsdCents: Long = 0L,
    val uncostedReturnCount: Int = 0
) {
    val netRevenueUsdCents: Long get() = Math.subtractExact(revenueUsdCents, returnUsdCents)
    val netCogsUsdCents: Long get() = Math.subtractExact(cogsUsdCents, returnedCogsUsdCents)
}

/**
 * Gross trading result from posted sales and their immutable FIFO allocations.
 * Collections on credit invoices do not create revenue a second time.
 * Posted returns reduce revenue and reverse the returned portion of FIFO COGS.
 * Expenses and other operating costs are not included.
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
        val returnWhere = StringBuilder(" WHERE status='POSTED' AND original_invoice_type='SALE'")
        val returnArgs = mutableListOf<String>()
        if (fromDate != null) {
            returnWhere.append(" AND return_date>=? AND return_date<=?")
            returnArgs.add(fromDate)
            returnArgs.add(throughDate!!)
        }
        var returnCount = 0
        var returnRevenue = 0L
        var returnedCogs = 0L
        var uncostedReturns = 0
        db.rawQuery(
            "SELECT total_usd_cents,returned_cogs_usd_cents FROM returns$returnWhere ORDER BY return_date,id",
            returnArgs.toTypedArray()
        ).use { returns ->
            while (returns.moveToNext()) {
                returnCount++
                returnRevenue = Math.addExact(returnRevenue, returns.getLong(0))
                if (returns.isNull(1)) uncostedReturns++
                else returnedCogs = Math.addExact(returnedCogs, returns.getLong(1))
            }
        }
        // Returns are recognized on their own return date, even if the original sale
        // belongs to an earlier reporting period. A valid COGS reversal can therefore
        // exceed this period's sales COGS and produce a negative net COGS for the period.
        val complete = uncosted == 0 && uncostedReturns == 0
        val netRevenue = Math.subtractExact(revenue, returnRevenue)
        val netCogs = Math.subtractExact(knownCogs, returnedCogs)
        return SalesProfitSummary(
            fromDate, throughDate, count, revenue, knownCogs,
            if (complete) Math.subtractExact(netRevenue, netCogs) else 0L,
            uncosted, complete, returnCount, returnRevenue, returnedCogs, uncostedReturns
        )
    }

    private fun todayStamp(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
