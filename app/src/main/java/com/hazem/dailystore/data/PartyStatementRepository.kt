package com.hazem.dailystore.data

import android.content.Context
import java.util.UUID

data class PartyStatementEntry(
    val id: String,
    val date: String,
    val type: String,
    val reference: String,
    val description: String,
    val debitUsdCents: Long = 0L,
    val creditUsdCents: Long = 0L,
    val debitSypMinor: Long = 0L,
    val creditSypMinor: Long = 0L,
    val cashRefundUsdCents: Long = 0L,
    val cashRefundSypMinor: Long = 0L,
    val balanceUsdCents: Long = 0L,
    val balanceSypMinor: Long = 0L,
    val sortAt: String = date
)

data class PartyStatement(
    val partyId: String,
    val partyName: String,
    val partyType: String,
    val entries: List<PartyStatementEntry>,
    val closingBalanceUsdCents: Long,
    val closingBalanceSypMinor: Long,
    val unpricedEntryCount: Int
)

/**
 * Read-only, chronological customer/supplier statements.
 * Customer balances use the immutable USD credit reference; supplier balances use
 * the fixed SYP amount agreed on each purchase invoice. Cash refunds are displayed
 * separately from payable/receivable reductions to avoid double counting.
 */
class PartyStatementRepository(context: Context) {
    private val db = AppDatabase.getInstance(context).readableDatabase

    fun customer(customerId: String): PartyStatement {
        require(customerId.isNotBlank())
        val name = db.rawQuery("SELECT name FROM customers WHERE id=?", arrayOf(customerId)).use {
            require(it.moveToFirst()) { "العميل غير موجود" }; it.getString(0)
        }
        val rows = mutableListOf<PartyStatementEntry>()
        var unpriced = 0
        db.rawQuery(
            """SELECT id,invoice_number,invoice_date,total_usd_cents,
                      exchange_rate_syp_per_usd_milli,status,created_at
               FROM sales_invoices
               WHERE customer_id=? AND sale_type='CREDIT'
                 AND status IN ('POSTED','PARTIALLY_PAID','PAID')
               ORDER BY invoice_date,created_at,id""".trimIndent(), arrayOf(customerId)
        ).use { c ->
            while (c.moveToNext()) {
                val rate = c.getLong(4)
                if (rate <= 0L) unpriced++
                rows.add(PartyStatementEntry(
                    c.getString(0), c.getString(2), "SALE_INVOICE", c.getString(1),
                    "فاتورة بيع آجل", debitUsdCents = c.getLong(3),
                    debitSypMinor = if (rate > 0L) CurrencyConverter.usdCentsToSyp(c.getLong(3), rate) else 0L,
                    sortAt = c.getString(6)
                ))
            }
        }
        db.rawQuery(
            """SELECT p.id,p.invoice_id,p.amount_minor,p.currency_code,
                      COALESCE(p.exchange_rate_syp_per_usd_milli,t.exchange_rate_syp_per_usd_milli,0),
                      p.created_at,i.invoice_number,i.exchange_rate_syp_per_usd_milli
               FROM invoice_payments p
               JOIN cash_transactions t ON t.id=p.cash_transaction_id
               JOIN sales_invoices i ON i.id=p.invoice_id
               WHERE p.invoice_type='SALE' AND i.customer_id=?
                 AND i.sale_type='CREDIT'
               ORDER BY p.created_at,p.id""".trimIndent(), arrayOf(customerId)
        ).use { c ->
            while (c.moveToNext()) {
                val amount = c.getLong(2)
                val currency = c.getString(3)
                val rate = c.getLong(4)
                val invoiceRate = c.getLong(7)
                val usd = when (currency) {
                    "USD" -> amount
                    "SYP" -> if (rate > 0L) CurrencyConverter.sypToUsdCents(amount, rate)
                        else if (invoiceRate > 0L) CurrencyConverter.sypToUsdCents(amount, invoiceRate) else 0L
                    else -> 0L
                }
                if (usd == 0L && amount > 0L) unpriced++
                rows.add(PartyStatementEntry(
                    c.getString(0), c.getString(5).take(10), "CUSTOMER_COLLECTION",
                    c.getString(6), "تحصيل على فاتورة آجلة",
                    creditUsdCents = usd,
                    creditSypMinor = if (currency == "SYP") amount else 0L,
                    sortAt = c.getString(5)
                ))
            }
        }
        db.rawQuery(
            """SELECT id,return_number,return_date,credit_reduction_usd_cents,
                      exchange_rate_syp_per_usd_milli,created_at
               FROM returns
               WHERE party_type='CUSTOMER' AND party_id=? AND original_invoice_type='SALE'
                 AND status='POSTED' AND credit_reduction_usd_cents>0
               ORDER BY return_date,created_at,id""".trimIndent(), arrayOf(customerId)
        ).use { c ->
            while (c.moveToNext()) {
                val rate = c.getLong(4)
                val credit = c.getLong(3)
                if (rate <= 0L) unpriced++
                val refunds = refundTotals(c.getString(0))
                rows.add(PartyStatementEntry(
                    c.getString(0), c.getString(2), "SALES_RETURN",
                    c.getString(1), "تخفيض دين العميل بمرتجع",
                    creditUsdCents = credit,
                    creditSypMinor = if (rate > 0L) CurrencyConverter.usdCentsToSyp(credit, rate) else 0L,
                    cashRefundUsdCents = refunds.first,
                    cashRefundSypMinor = refunds.second,
                    sortAt = c.getString(5)
                ))
            }
        }
        return build(customerId, name, "CUSTOMER", rows, unpriced)
    }

    fun supplier(supplierId: String): PartyStatement {
        require(supplierId.isNotBlank())
        val name = db.rawQuery("SELECT name FROM suppliers WHERE id=?", arrayOf(supplierId)).use {
            require(it.moveToFirst()) { "المورد غير موجود" }; it.getString(0)
        }
        val rows = mutableListOf<PartyStatementEntry>()
        var unpriced = 0
        db.rawQuery(
            """SELECT id,invoice_number,invoice_date,total_usd_cents,
                      exchange_rate_syp_per_usd_milli,created_at
               FROM purchase_invoices
               WHERE supplier_id=? AND status IN ('POSTED','PARTIALLY_PAID','PAID')
               ORDER BY invoice_date,created_at,id""".trimIndent(), arrayOf(supplierId)
        ).use { c ->
            while (c.moveToNext()) {
                val rate = c.getLong(4)
                if (rate <= 0L) unpriced++
                rows.add(PartyStatementEntry(
                    c.getString(0), c.getString(2), "PURCHASE_INVOICE", c.getString(1),
                    "فاتورة شراء", debitUsdCents = c.getLong(3),
                    debitSypMinor = if (rate > 0L) CurrencyConverter.usdCentsToSyp(c.getLong(3), rate) else 0L,
                    sortAt = c.getString(5)
                ))
            }
        }
        db.rawQuery(
            """SELECT p.id,p.amount_minor,p.currency_code,
                      COALESCE(i.exchange_rate_syp_per_usd_milli,0),p.created_at,
                      i.invoice_number
               FROM invoice_payments p
               JOIN purchase_invoices i ON i.id=p.invoice_id
               WHERE p.invoice_type='PURCHASE' AND i.supplier_id=?
               ORDER BY p.created_at,p.id""".trimIndent(), arrayOf(supplierId)
        ).use { c ->
            while (c.moveToNext()) {
                val amount = c.getLong(1)
                val rate = c.getLong(3)
                val syp = when (c.getString(2)) {
                    "SYP" -> amount
                    "USD" -> if (rate > 0L) CurrencyConverter.usdCentsToSyp(amount, rate) else 0L
                    else -> 0L
                }
                if (syp == 0L && amount > 0L) unpriced++
                rows.add(PartyStatementEntry(
                    c.getString(0), c.getString(4).take(10), "SUPPLIER_PAYMENT",
                    c.getString(5), "دفعة للمورد",
                    creditSypMinor = syp,
                    sortAt = c.getString(4)
                ))
            }
        }
        db.rawQuery(
            """SELECT id,return_number,return_date,supplier_credit_reduction_syp_minor,
                      supplier_cash_refund_syp_minor,created_at
               FROM returns
               WHERE party_type='SUPPLIER' AND party_id=? AND original_invoice_type='PURCHASE'
                 AND status='POSTED'
               ORDER BY return_date,created_at,id""".trimIndent(), arrayOf(supplierId)
        ).use { c ->
            while (c.moveToNext()) {
                val credit = c.getLong(3)
                rows.add(PartyStatementEntry(
                    c.getString(0), c.getString(2), "PURCHASE_RETURN",
                    c.getString(1), "مرتجع مشتريات وتخفيض مستحق المورد",
                    creditSypMinor = credit,
                    cashRefundSypMinor = c.getLong(4),
                    sortAt = c.getString(5)
                ))
            }
        }
        return build(supplierId, name, "SUPPLIER", rows, unpriced)
    }

    private fun refundTotals(returnId: String): Pair<Long, Long> {
        var usd = 0L
        var syp = 0L
        db.rawQuery(
            """SELECT amount_minor,currency_code FROM return_refund_allocations
               WHERE return_id=?""".trimIndent(), arrayOf(returnId)
        ).use { c ->
            while (c.moveToNext()) when (c.getString(1)) {
                "USD" -> usd = Math.addExact(usd, c.getLong(0))
                "SYP" -> syp = Math.addExact(syp, c.getLong(0))
            }
        }
        return usd to syp
    }

    private fun build(
        id: String, name: String, type: String,
        source: List<PartyStatementEntry>, unpriced: Int
    ): PartyStatement {
        val sorted = source.sortedWith(compareBy<PartyStatementEntry> { it.sortAt }.thenBy { it.id })
        var usd = 0L
        var syp = 0L
        val entries = sorted.map { row ->
            usd = Math.addExact(usd, Math.subtractExact(row.debitUsdCents, row.creditUsdCents))
            syp = Math.addExact(syp, Math.subtractExact(row.debitSypMinor, row.creditSypMinor))
            row.copy(balanceUsdCents = usd, balanceSypMinor = syp)
        }
        return PartyStatement(id, name, type, entries, usd, syp, unpriced)
    }
}
