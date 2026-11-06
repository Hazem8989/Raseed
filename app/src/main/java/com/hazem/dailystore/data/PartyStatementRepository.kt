package com.hazem.dailystore.data

import android.content.Context

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
    private val appContext=context.applicationContext
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
                    sortAt = "${c.getString(2)}|${c.getString(6)}"
                ))
            }
        }
        db.rawQuery(
            """SELECT p.id,p.invoice_id,p.amount_minor,p.currency_code,
                      COALESCE(p.exchange_rate_syp_per_usd_milli,t.exchange_rate_syp_per_usd_milli,0),
                      p.created_at,i.invoice_number,i.exchange_rate_syp_per_usd_milli,
                      COALESCE(t.operating_date,substr(p.created_at,1,10))
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
                val effectiveRate = if (rate > 0L) rate else invoiceRate
                val sypEquivalent = when (currency) {
                    "SYP" -> amount
                    "USD" -> if (effectiveRate > 0L)
                        CurrencyConverter.usdCentsToSyp(amount, effectiveRate) else 0L
                    else -> 0L
                }
                if ((usd == 0L || sypEquivalent == 0L) && amount > 0L) unpriced++
                rows.add(PartyStatementEntry(
                    c.getString(0), c.getString(5).take(10), "CUSTOMER_COLLECTION",
                    c.getString(6), "تحصيل على فاتورة آجلة",
                    creditUsdCents = usd,
                    creditSypMinor = sypEquivalent,
                    sortAt = "${c.getString(8)}|${c.getString(5)}"
                ))
            }
        }
        db.rawQuery(
            """SELECT id,return_number,return_date,credit_reduction_usd_cents,
                      exchange_rate_syp_per_usd_milli,created_at
               FROM returns
               WHERE party_type='CUSTOMER' AND party_id=? AND original_invoice_type='SALE'
                 AND status='POSTED'
               ORDER BY return_date,created_at,id""".trimIndent(), arrayOf(customerId)
        ).use { c ->
            while (c.moveToNext()) {
                val rate = c.getLong(4)
                val credit = c.getLong(3)
                if (credit > 0L && rate <= 0L) unpriced++
                val refunds = refundTotals(c.getString(0))
                if (credit == 0L && refunds.first == 0L && refunds.second == 0L) continue
                rows.add(PartyStatementEntry(
                    c.getString(0), c.getString(2), "SALES_RETURN",
                    c.getString(1), "تخفيض دين العميل بمرتجع",
                    creditUsdCents = credit,
                    creditSypMinor = if (rate > 0L) CurrencyConverter.usdCentsToSyp(credit, rate) else 0L,
                    cashRefundUsdCents = refunds.first,
                    cashRefundSypMinor = refunds.second,
                    sortAt = "${c.getString(2)}|${c.getString(5)}"
                ))
            }
        }
        if(SyncProtocolRepository(appContext).isConfiguredSecondary()) {
            db.rawQuery(
                """SELECT transfer_id,service_type,charge_minor,operating_date,created_at,reference
                   FROM service_transfer_receivable_mirrors
                   WHERE customer_id=? AND status!='REVERSED'
                   ORDER BY operating_date,created_at,transfer_id""".trimIndent(),arrayOf(customerId)
            ).use{c->while(c.moveToNext()){
                val serviceName=when(c.getString(1)){
                    ServiceTransferRepository.SYRIATEL_BALANCE->"سيرياتيل العادي"
                    ServiceTransferRepository.SYRIATEL_CASH->"سيرياتيل كاش"
                    else->"شام كاش"
                }
                val date=c.getString(3)
                rows.add(PartyStatementEntry(c.getString(0),date,"SERVICE_TRANSFER_CREDIT",
                    if(c.isNull(5))c.getString(0) else c.getString(5),
                    "تحويل رصيد آجل • $serviceName",debitSypMinor=c.getLong(2),
                    sortAt="$date|${c.getString(4)}"))
            }}
            db.rawQuery(
                """SELECT p.payment_id,p.transfer_id,p.amount_minor,p.operating_date,p.created_at,
                          r.service_type,COALESCE(r.reference,r.transfer_id)
                   FROM service_transfer_receivable_payment_mirrors p
                   JOIN service_transfer_receivable_mirrors r ON r.transfer_id=p.transfer_id
                   WHERE r.customer_id=?
                   ORDER BY p.operating_date,p.created_at,p.payment_id""".trimIndent(),arrayOf(customerId)
            ).use{c->while(c.moveToNext()){
                val serviceName=when(c.getString(5)){
                    ServiceTransferRepository.SYRIATEL_BALANCE->"سيرياتيل العادي"
                    ServiceTransferRepository.SYRIATEL_CASH->"سيرياتيل كاش"
                    else->"شام كاش"
                }
                val date=c.getString(3)
                rows.add(PartyStatementEntry(c.getString(0),date,"SERVICE_TRANSFER_COLLECTION",c.getString(6),
                    "تحصيل دين تحويل رصيد • $serviceName",creditSypMinor=c.getLong(2),
                    sortAt="$date|${c.getString(4)}"))
            }}
        } else {
            db.rawQuery(
                """SELECT t.id,t.service_type,t.customer_charge_minor,
                          COALESCE(t.operating_date,substr(t.created_at,1,10)),t.created_at,t.reference
                   FROM service_transfers t
                   WHERE t.customer_id=? AND t.payment_mode='CREDIT' AND t.status='POSTED'
                   ORDER BY COALESCE(t.operating_date,substr(t.created_at,1,10)),t.created_at,t.id""".trimIndent(),
                arrayOf(customerId)
            ).use { c ->
                while(c.moveToNext()) {
                    val serviceName=when(c.getString(1)) {
                        ServiceTransferRepository.SYRIATEL_BALANCE -> "سيرياتيل العادي"
                        ServiceTransferRepository.SYRIATEL_CASH -> "سيرياتيل كاش"
                        else -> "شام كاش"
                    }
                    val date=c.getString(3)
                    rows.add(PartyStatementEntry(
                        c.getString(0),date,"SERVICE_TRANSFER_CREDIT",if(c.isNull(5)) c.getString(0) else c.getString(5),
                        "تحويل رصيد آجل • $serviceName",debitSypMinor=c.getLong(2),
                        sortAt="$date|${c.getString(4)}"
                    ))
                }
            }
            db.rawQuery(
                """SELECT p.id,p.transfer_id,p.amount_minor,p.operating_date,p.created_at,
                          t.service_type,COALESCE(t.reference,t.id)
                   FROM service_transfer_receivable_payments p
                   JOIN service_transfers t ON t.id=p.transfer_id
                   WHERE t.customer_id=?
                   ORDER BY p.operating_date,p.created_at,p.id""".trimIndent(),
                arrayOf(customerId)
            ).use { c ->
                while(c.moveToNext()) {
                    val serviceName=when(c.getString(5)) {
                        ServiceTransferRepository.SYRIATEL_BALANCE -> "سيرياتيل العادي"
                        ServiceTransferRepository.SYRIATEL_CASH -> "سيرياتيل كاش"
                        else -> "شام كاش"
                    }
                    val date=c.getString(3)
                    rows.add(PartyStatementEntry(
                        c.getString(0),date,"SERVICE_TRANSFER_COLLECTION",c.getString(6),
                        "تحصيل دين تحويل رصيد • $serviceName",creditSypMinor=c.getLong(2),
                        sortAt="$date|${c.getString(4)}"
                    ))
                }
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
                    sortAt = "${c.getString(2)}|${c.getString(5)}"
                ))
            }
        }
        db.rawQuery(
            """SELECT p.id,p.amount_minor,p.currency_code,
                      COALESCE(p.exchange_rate_syp_per_usd_milli,t.exchange_rate_syp_per_usd_milli,
                               i.exchange_rate_syp_per_usd_milli,0),
                      p.created_at,i.invoice_number,
                      COALESCE(t.operating_date,substr(p.created_at,1,10))
               FROM invoice_payments p
               JOIN cash_transactions t ON t.id=p.cash_transaction_id
               JOIN purchase_invoices i ON i.id=p.invoice_id
               WHERE p.invoice_type='PURCHASE' AND i.supplier_id=?
               ORDER BY p.created_at,p.id""".trimIndent(), arrayOf(supplierId)
        ).use { c ->
            while (c.moveToNext()) {
                val amount = c.getLong(1)
                val rate = c.getLong(3)
                val currency = c.getString(2)
                val usd = when (currency) {
                    "USD" -> amount
                    "SYP" -> if (rate > 0L) CurrencyConverter.sypToUsdCents(amount, rate) else 0L
                    else -> 0L
                }
                val syp = when (currency) {
                    "SYP" -> amount
                    "USD" -> if (rate > 0L) CurrencyConverter.usdCentsToSyp(amount, rate) else 0L
                    else -> 0L
                }
                if ((usd == 0L || syp == 0L) && amount > 0L) unpriced++
                rows.add(PartyStatementEntry(
                    c.getString(0), c.getString(4).take(10), "SUPPLIER_PAYMENT",
                    c.getString(5), "دفعة للمورد",
                    creditUsdCents = usd,
                    creditSypMinor = syp,
                    sortAt = "${c.getString(6)}|${c.getString(4)}"
                ))
            }
        }
        db.rawQuery(
            """SELECT id,return_number,return_date,supplier_credit_reduction_syp_minor,
                      supplier_cash_refund_syp_minor,exchange_rate_syp_per_usd_milli,created_at
               FROM returns
               WHERE party_type='SUPPLIER' AND party_id=? AND original_invoice_type='PURCHASE'
                 AND status='POSTED'
               ORDER BY return_date,created_at,id""".trimIndent(), arrayOf(supplierId)
        ).use { c ->
            while (c.moveToNext()) {
                val credit = c.getLong(3)
                val rate = c.getLong(5)
                val creditUsd = if (credit > 0L && rate > 0L)
                    CurrencyConverter.sypToUsdCents(credit, rate) else 0L
                if (credit > 0L && rate <= 0L) unpriced++
                rows.add(PartyStatementEntry(
                    c.getString(0), c.getString(2), "PURCHASE_RETURN",
                    c.getString(1), "مرتجع مشتريات وتخفيض مستحق المورد",
                    creditUsdCents = creditUsd,
                    creditSypMinor = credit,
                    cashRefundSypMinor = c.getLong(4),
                    sortAt = "${c.getString(2)}|${c.getString(6)}"
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
