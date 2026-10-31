package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class CustomerInvoicePaymentSummary(
    val id: String, val invoiceNumber: String, val date: String, val invoiceStatus: String,
    val paymentStatus: String, val totalUsdCents: Long, val paidUsdCents: Long,
    val outstandingUsdCents: Long, val originalRateMilli: Long,
    val totalSypMinor: Long, val paidSypMinor: Long, val outstandingSypMinor: Long
)
data class CustomerFinancialSummary(
    val id: String, val name: String, val phone: String?,
    val totalCreditUsdCents: Long, val paidUsdCents: Long,
    val outstandingUsdCents: Long, val unpaidInvoiceCount: Int, val outstandingSypMinor: Long
)
data class CustomerPaymentRecord(
    val id: String, val amountMinor: Long, val currencyCode: String,
    val accountName: String, val accountType: String, val createdAt: String,
    val note: String?, val originalInvoiceRateMilli: Long,
    val collectionRateMilli: Long, val effectiveRateMilli: Long,
    val usdReferenceReductionCents: Long
)
data class CustomerCreditRevaluationRecord(
    val previousSypMinor: Long, val newSypMinor: Long,
    val appliedRateMilli: Long, val outstandingUsdCents: Long, val createdAt: String
)
data class CustomerPaymentResult(
    val paymentId: String, val paidUsdCents: Long, val outstandingUsdCents: Long,
    val paymentStatus: String, val replayed: Boolean, val outstandingSypMinor: Long
)

/** SYP-only customer receivable settlement. A collection never posts stock or sales. */
class CustomerPaymentRepository(context: Context) {
    private val database = AppDatabase.getInstance(context)

    fun listCustomerSummaries(): List<CustomerFinancialSummary> {
        val result = mutableListOf<CustomerFinancialSummary>()
        database.readableDatabase.rawQuery(
            "SELECT id,name,phone FROM customers WHERE is_active=1 ORDER BY name COLLATE NOCASE", null
        ).use { c ->
            while (c.moveToNext()) {
                val invoices = listInvoices(c.getString(0))
                    .filter { it.invoiceStatus != "DRAFT" && it.invoiceStatus != "CANCELLED" }
                result.add(CustomerFinancialSummary(
                    c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getString(2),
                    invoices.sumOf { it.totalUsdCents }, invoices.sumOf { it.paidUsdCents },
                    invoices.sumOf { it.outstandingUsdCents },
                    invoices.count { it.outstandingUsdCents > 0L },
                    invoices.sumOf { it.outstandingSypMinor }
                ))
            }
        }
        return result
    }

    fun listInvoices(customerId: String): List<CustomerInvoicePaymentSummary> {
        require(customerId.isNotBlank()) { "معرّف الزبون مطلوب" }
        applyRevaluations(customerId)
        val db = database.readableDatabase
        val current = currentRate(db)
        val result = mutableListOf<CustomerInvoicePaymentSummary>()
        db.rawQuery(
            """SELECT id,invoice_number,invoice_date,status,total_usd_cents,
                      exchange_rate_syp_per_usd_milli
               FROM sales_invoices
               WHERE customer_id=? AND sale_type='CREDIT'
               ORDER BY invoice_date DESC,created_at DESC""".trimIndent(), arrayOf(customerId)
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val total = c.getLong(4)
                val originalRate = c.getLong(5)
                val paid = paidUsdCents(db, id)
                val outstanding = (total - paid).coerceAtLeast(0L)
                val paidSyp = paidSypMinor(db, id, originalRate)
                val effectiveRate = if (originalRate > 0L) maxOf(current, originalRate) else 0L
                val totalSyp = if (originalRate > 0L) CurrencyConverter.usdCentsToSyp(total, originalRate) else 0L
                val outstandingSyp = if (effectiveRate > 0L)
                    CurrencyConverter.usdCentsToSyp(outstanding, effectiveRate) else 0L
                val status = when {
                    c.getString(3) == "CANCELLED" -> "CANCELLED"
                    c.getString(3) == "DRAFT" -> "DRAFT"
                    outstanding == 0L -> "PAID"
                    paid > 0L -> "PARTIALLY_PAID"
                    else -> "UNPAID"
                }
                result.add(CustomerInvoicePaymentSummary(
                    id, c.getString(1), c.getString(2), c.getString(3), status,
                    total, paid, outstanding, originalRate, totalSyp, paidSyp, outstandingSyp
                ))
            }
        }
        return result
    }

    fun listPayments(invoiceId: String): List<CustomerPaymentRecord> {
        require(invoiceId.isNotBlank()) { "معرّف الفاتورة مطلوب" }
        val result = mutableListOf<CustomerPaymentRecord>()
        database.readableDatabase.rawQuery(
            """SELECT p.id,p.amount_minor,p.currency_code,a.name,a.account_type,
                      p.created_at,t.description,COALESCE(i.exchange_rate_syp_per_usd_milli,0),
                      COALESCE(t.exchange_rate_syp_per_usd_milli,0),
                      COALESCE(p.exchange_rate_syp_per_usd_milli,0)
               FROM invoice_payments p
               JOIN cash_transactions t ON t.id=p.cash_transaction_id
               JOIN cash_accounts a ON a.id=t.account_id
               JOIN sales_invoices i ON i.id=p.invoice_id
               WHERE p.invoice_type='SALE' AND p.invoice_id=?
               ORDER BY p.created_at DESC""".trimIndent(), arrayOf(invoiceId)
        ).use { c ->
            while (c.moveToNext()) {
                val amount = c.getLong(1)
                val currency = c.getString(2)
                val effectiveRate = c.getLong(9)
                val usdReduction = if (currency == "SYP" && effectiveRate > 0L)
                    CurrencyConverter.sypToUsdCents(amount, effectiveRate) else amount
                result.add(CustomerPaymentRecord(
                    c.getString(0), amount, currency, c.getString(3), c.getString(4),
                    c.getString(5), if (c.isNull(6)) null else c.getString(6),
                    c.getLong(7), c.getLong(8), effectiveRate, usdReduction
                ))
            }
        }
        return result
    }

    fun listRevaluations(invoiceId: String): List<CustomerCreditRevaluationRecord> {
        val result = mutableListOf<CustomerCreditRevaluationRecord>()
        database.readableDatabase.rawQuery(
            """SELECT previous_syp_minor,new_syp_minor,applied_rate_syp_per_usd_milli,
                      outstanding_usd_cents,created_at
               FROM customer_credit_revaluations WHERE invoice_id=? ORDER BY created_at DESC""".trimIndent(),
            arrayOf(invoiceId)
        ).use { c ->
            while (c.moveToNext()) result.add(CustomerCreditRevaluationRecord(
                c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getString(4)
            ))
        }
        return result
    }

    fun addPayment(
        invoiceId: String, operationId: String, cashAccountId: String,
        amountSypMinor: Long, note: String? = null, actorUserId: String? = null
    ): CustomerPaymentResult {
        require(invoiceId.isNotBlank() && operationId.isNotBlank() && cashAccountId.isNotBlank()) {
            "بيانات التحصيل غير مكتملة"
        }
        require(amountSypMinor > 0L) { "مبلغ التحصيل يجب أن يكون أكبر من صفر" }
        val db = database.writableDatabase
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        val payloadHash = hash(invoiceId + "|" + cashAccountId + "|" + amountSypMinor + "|" + cleanNote.orEmpty())
        db.beginTransaction()
        try {
            val invoice = db.rawQuery(
                """SELECT status,sale_type,total_usd_cents,customer_id,invoice_number,
                          exchange_rate_syp_per_usd_milli
                   FROM sales_invoices WHERE id=?""".trimIndent(), arrayOf(invoiceId)
            ).use {
                require(it.moveToFirst()) { "فاتورة البيع غير موجودة" }
                CustomerInvoiceForPayment(
                    it.getString(0), it.getString(1), it.getLong(2),
                    if (it.isNull(3)) null else it.getString(3), it.getString(4), it.getLong(5)
                )
            }
            require(invoice.saleType == "CREDIT" && invoice.customerId != null) {
                "التحصيل متاح لفواتير البيع الآجل المحددة لزبون فقط"
            }
            findOperation(db, operationId)?.let { existing ->
                require(existing.type == "CUSTOMER_CREDIT_COLLECTION" && existing.payloadHash == payloadHash) {
                    "معرّف العملية مستخدم لعملية أو بيانات مختلفة"
                }
                val state = invoicePaymentState(db, invoiceId, invoice.totalUsdCents, invoice.originalRateMilli)
                db.setTransactionSuccessful()
                return CustomerPaymentResult(
                    existing.reference ?: error("سجل العملية لا يحتوي مرجع التحصيل"),
                    state.paidUsdCents, state.outstandingUsdCents, state.status, true, state.outstandingSypMinor
                )
            }
            require(invoice.status == "POSTED" || invoice.status == "PARTIALLY_PAID") {
                "لا يمكن تحصيل فاتورة غير معتمدة أو مدفوعة أو ملغاة"
            }
            require(invoice.originalRateMilli > 0L) {
                "هذه الفاتورة قديمة ولا تحتوي سعر صرف موثوقاً؛ لا يمكن تحصيلها آلياً قبل مراجعتها"
            }
            val collectionRate = currentRate(db)
            require(collectionRate > 0L) { "يجب تسجيل سعر الصرف قبل تحصيل دين الزبون" }
            val effectiveRate = maxOf(collectionRate, invoice.originalRateMilli)
            val account = db.rawQuery(
                "SELECT name,account_type,currency_code,is_active FROM cash_accounts WHERE id=?",
                arrayOf(cashAccountId)
            ).use {
                require(it.moveToFirst()) { "الحساب المالي غير موجود" }
                require(it.getInt(3) == 1) { "لا يمكن التحصيل في حساب موقوف" }
                CustomerAccountForPayment(it.getString(0), it.getString(1), it.getString(2))
            }
            require(account.currencyCode == "SYP") { "تحصيل ديون الزبائن متاح بالليرة السورية فقط" }
            when (account.accountType) {
                "CASH_BOX" -> {
                    val primary = db.rawQuery(
                        "SELECT primary_cash_account_id FROM daily_cash_sessions WHERE operating_date=?",
                        arrayOf(operatingDate())
                    ).use { if (it.moveToFirst()) it.getString(0) else null }
                    require(primary != null) {
                        "لم يتم تعيين الدرج النقدي الرئيسي لهذا اليوم. اطلب من المدير تعيين درج اليوم من قسم الصناديق والمحافظ."
                    }
                    require(primary == cashAccountId) {
                        "التحصيل النقدي يجب أن يذهب إلى درج اليوم الرئيسي، وليس صندوق الموردين أو الصندوق المدور."
                    }
                }
                "WALLET" -> Unit
                else -> throw IllegalArgumentException("يجب اختيار الدرج اليومي الرئيسي أو محفظة إلكترونية نشطة")
            }

            val now = timestamp()
            recordRevaluation(db, invoiceId, invoice.customerId, invoice.totalUsdCents,
                invoice.originalRateMilli, collectionRate, now)
            val state = invoicePaymentState(db, invoiceId, invoice.totalUsdCents, invoice.originalRateMilli)
            require(state.outstandingUsdCents > 0L) { "الفاتورة مسددة بالكامل" }
            require(amountSypMinor <= state.outstandingSypMinor) {
                "مبلغ التحصيل يتجاوز المتبقي المستحق بالليرة السورية"
            }
            val usdReduction = CurrencyConverter.sypToUsdCents(amountSypMinor, effectiveRate)
            require(usdReduction > 0L) { "مبلغ التحصيل صغير جداً ولا يقابل وحدة مرجعية بالدولار" }
            require(usdReduction <= state.outstandingUsdCents) {
                "مبلغ التحصيل يتجاوز المتبقي المرجعي على الفاتورة"
            }

            val paymentId = UUID.randomUUID().toString()
            val cashTransactionId = UUID.randomUUID().toString()
            val channel = if (account.accountType == "WALLET") "ELECTRONIC_WALLET" else "PHYSICAL_CASH"
            val cash = ContentValues().apply {
                put("id", cashTransactionId); put("account_id", cashAccountId); put("direction", "IN")
                put("amount_minor", amountSypMinor); put("currency_code", "SYP")
                put("exchange_rate_syp_per_usd_milli", collectionRate)
                if (actorUserId.isNullOrBlank()) putNull("created_by") else put("created_by", actorUserId)
                put("transaction_type", "CUSTOMER_CREDIT_COLLECTION")
                put("source_type", "CUSTOMER_CREDIT_COLLECTION"); put("source_id", invoiceId)
                put("operation_id", operationId)
                put("description", cleanNote ?: "تحصيل دين " + invoice.customerId +
                    " عن الفاتورة " + invoice.invoiceNumber + " • " + channel)
                put("operating_date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
                put("created_at", now)
            }
            db.insertOrThrow("cash_transactions", null, cash)
            val payment = ContentValues().apply {
                put("id", paymentId); put("invoice_type", "SALE"); put("invoice_id", invoiceId)
                put("cash_transaction_id", cashTransactionId); put("amount_minor", amountSypMinor)
                put("currency_code", "SYP")
                // This is the effective rate used to reduce the USD reference balance.
                put("exchange_rate_syp_per_usd_milli", effectiveRate)
                put("created_at", now)
            }
            db.insertOrThrow("invoice_payments", null, payment)

            val newPaid = Math.addExact(state.paidUsdCents, usdReduction)
            val newOutstanding = (invoice.totalUsdCents - newPaid).coerceAtLeast(0L)
            val newStatus = if (newOutstanding == 0L) "PAID" else "PARTIALLY_PAID"
            db.execSQL(
                "UPDATE sales_invoices SET status=?,updated_at=? WHERE id=? AND status IN ('POSTED','PARTIALLY_PAID')",
                arrayOf(newStatus, now, invoiceId)
            )
            require(db.rawQuery("SELECT changes()", null).use { it.moveToFirst() && it.getInt(0) == 1 }) {
                "تعذر تحديث حالة فاتورة البيع"
            }
            val audit = ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                if (actorUserId.isNullOrBlank()) putNull("user_id") else put("user_id", actorUserId)
                put("action", "CUSTOMER_CREDIT_COLLECTION")
                put("entity_type", "CUSTOMER_CREDIT_INVOICE")
                put("entity_id", invoiceId)
                put("before_json", "{\"outstanding_usd_cents\":" + state.outstandingUsdCents +
                    ",\"outstanding_syp_minor\":" + state.outstandingSypMinor + "}")
                put("after_json", "{\"payment_id\":\"" + paymentId + "\",\"cash_transaction_id\":\"" +
                    cashTransactionId + "\",\"amount_syp_minor\":" + amountSypMinor +
                    ",\"channel\":\"" + channel + "\",\"account_id\":\"" + cashAccountId +
                    "\",\"collection_rate_milli\":" + collectionRate +
                    ",\"invoice_rate_milli\":" + invoice.originalRateMilli +
                    ",\"effective_rate_milli\":" + effectiveRate +
                    ",\"usd_reference_reduction_cents\":" + usdReduction + "}")
                put("created_at", now)
            }
            db.insertOrThrow("audit_log", null, audit)
            registerOperation(db, operationId, "CUSTOMER_CREDIT_COLLECTION", payloadHash, paymentId, now)
            val finalState = invoicePaymentState(db, invoiceId, invoice.totalUsdCents, invoice.originalRateMilli)
            db.setTransactionSuccessful()
            return CustomerPaymentResult(paymentId, finalState.paidUsdCents, finalState.outstandingUsdCents,
                finalState.status, false, finalState.outstandingSypMinor)
        } finally {
            db.endTransaction()
        }
    }

    private fun applyRevaluations(customerId: String) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val rate = currentRate(db)
            if (rate > 0L) {
                val invoices = mutableListOf<InvoiceForRevaluation>()
                db.rawQuery(
                    """SELECT id,total_usd_cents,customer_id,exchange_rate_syp_per_usd_milli
                       FROM sales_invoices WHERE customer_id=? AND sale_type='CREDIT'
                         AND status IN ('POSTED','PARTIALLY_PAID')""".trimIndent(), arrayOf(customerId)
                ).use { c ->
                    while (c.moveToNext()) invoices.add(InvoiceForRevaluation(
                        c.getString(0), c.getLong(1), c.getString(2), c.getLong(3)
                    ))
                }
                val now = timestamp()
                invoices.forEach { item ->
                    if (item.originalRateMilli > 0L) recordRevaluation(
                        db, item.id, item.customerId, item.totalUsdCents, item.originalRateMilli, rate, now
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun recordRevaluation(
        db: SQLiteDatabase, invoiceId: String, customerId: String, totalUsdCents: Long,
        originalRate: Long, currentRate: Long, now: String
    ) {
        if (originalRate <= 0L || currentRate <= 0L) return
        val outstanding = (totalUsdCents - paidUsdCents(db, invoiceId)).coerceAtLeast(0L)
        if (outstanding == 0L) return
        val appliedRate = maxOf(originalRate, currentRate)
        val newValue = CurrencyConverter.usdCentsToSyp(outstanding, appliedRate)
        val floorValue = CurrencyConverter.usdCentsToSyp(outstanding, originalRate)
        val previous = db.rawQuery(
            """SELECT new_syp_minor FROM customer_credit_revaluations
               WHERE invoice_id=? AND outstanding_usd_cents=? ORDER BY created_at DESC LIMIT 1""".trimIndent(),
            arrayOf(invoiceId, outstanding.toString())
        ).use { if (it.moveToFirst()) it.getLong(0) else floorValue }
        if (newValue == previous) return
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("invoice_id", invoiceId)
            put("customer_id", customerId); put("outstanding_usd_cents", outstanding)
            put("previous_syp_minor", previous); put("new_syp_minor", newValue)
            put("applied_rate_syp_per_usd_milli", appliedRate)
            put("calculation_basis", "unpaid USD reference × max(current rate, original invoice rate); paid allocations excluded")
            put("operation_id", UUID.randomUUID().toString()); put("created_at", now)
        }
        db.insertOrThrow("customer_credit_revaluations", null, values)
        val audit = ContentValues().apply {
            put("id", UUID.randomUUID().toString()); putNull("user_id")
            put("action", "CUSTOMER_CREDIT_FX_REVALUATION")
            put("entity_type", "CUSTOMER_CREDIT_INVOICE"); put("entity_id", invoiceId)
            put("before_json", "{\"outstanding_usd_cents\":" + outstanding + ",\"syp_value\":" + previous + "}")
            put("after_json", "{\"outstanding_usd_cents\":" + outstanding + ",\"syp_value\":" +
                newValue + ",\"applied_rate_milli\":" + appliedRate + "}")
            put("created_at", now)
        }
        db.insertOrThrow("audit_log", null, audit)
    }

    private fun paidSypMinor(db: SQLiteDatabase, invoiceId: String, originalRate: Long): Long {
        var total = 0L
        db.rawQuery(
            """SELECT amount_minor,currency_code,exchange_rate_syp_per_usd_milli
               FROM invoice_payments WHERE invoice_type='SALE' AND invoice_id=?""".trimIndent(),
            arrayOf(invoiceId)
        ).use { c ->
            while (c.moveToNext()) {
                val amount = c.getLong(0)
                val paymentRate = if (c.isNull(2)) originalRate else c.getLong(2)
                val syp = when (c.getString(1)) {
                    "SYP" -> amount
                    "USD" -> {
                        require(paymentRate > 0L) { "دفعة قديمة بلا سعر صرف موثوق" }
                        CurrencyConverter.usdCentsToSyp(amount, paymentRate)
                    }
                    else -> error("عملة دفعة غير مدعومة")
                }
                total = Math.addExact(total, syp)
            }
        }
        return total
    }

    private fun paidUsdCents(db: SQLiteDatabase, invoiceId: String): Long {
        var total = 0L
        db.rawQuery(
            """SELECT amount_minor,currency_code,exchange_rate_syp_per_usd_milli
               FROM invoice_payments WHERE invoice_type='SALE' AND invoice_id=?""".trimIndent(),
            arrayOf(invoiceId)
        ).use { c ->
            while (c.moveToNext()) {
                val amount = c.getLong(0)
                val rate = if (c.isNull(2)) 0L else c.getLong(2)
                val equivalent = when (c.getString(1)) {
                    "USD" -> amount
                    "SYP" -> {
                        require(rate > 0L) { "دفعة ليرة قديمة بلا سعر صرف محفوظ" }
                        CurrencyConverter.sypToUsdCents(amount, rate)
                    }
                    else -> error("عملة دفعة غير مدعومة")
                }
                total = Math.addExact(total, equivalent)
            }
        }
        return total
    }

    private fun invoicePaymentState(
        db: SQLiteDatabase, invoiceId: String, totalUsdCents: Long, originalRate: Long
    ): InvoicePaymentState {
        val paid = paidUsdCents(db, invoiceId)
        val outstanding = (totalUsdCents - paid).coerceAtLeast(0L)
        val current = currentRate(db)
        val effective = if (originalRate > 0L) maxOf(current, originalRate) else 0L
        val outstandingSyp = if (effective > 0L)
            CurrencyConverter.usdCentsToSyp(outstanding, effective) else 0L
        val status = when {
            outstanding == 0L -> "PAID"
            paid > 0L -> "PARTIALLY_PAID"
            else -> "UNPAID"
        }
        return InvoicePaymentState(paid, outstanding, outstandingSyp, status)
    }

    private fun currentRate(db: SQLiteDatabase): Long =
        db.rawQuery(
            "SELECT rate_syp_per_usd_milli FROM exchange_rates ORDER BY effective_at DESC,created_at DESC LIMIT 1",
            null
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun operatingDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private data class InvoiceForRevaluation(
        val id: String, val totalUsdCents: Long, val customerId: String, val originalRateMilli: Long
    )
    private data class CustomerInvoiceForPayment(
        val status: String, val saleType: String, val totalUsdCents: Long,
        val customerId: String?, val invoiceNumber: String, val originalRateMilli: Long
    )
    private data class CustomerAccountForPayment(
        val name: String, val accountType: String, val currencyCode: String
    )
    private data class InvoicePaymentState(
        val paidUsdCents: Long, val outstandingUsdCents: Long, val outstandingSypMinor: Long, val status: String
    )
    private data class ExistingOperation(val type: String, val payloadHash: String, val reference: String?)

    private fun findOperation(db: SQLiteDatabase, id: String): ExistingOperation? =
        db.rawQuery(
            "SELECT operation_type,payload_hash,result_reference FROM operation_registry WHERE operation_id=?",
            arrayOf(id)
        ).use {
            if (it.moveToFirst()) ExistingOperation(
                it.getString(0), it.getString(1), if (it.isNull(2)) null else it.getString(2)
            ) else null
        }

    private fun registerOperation(
        db: SQLiteDatabase, id: String, type: String, payloadHash: String, reference: String, now: String
    ) {
        val values = ContentValues().apply {
            put("operation_id", id); put("operation_type", type); put("payload_hash", payloadHash)
            put("status", "COMPLETED"); put("result_reference", reference)
            put("created_at", now); put("completed_at", now)
        }
        db.insertOrThrow("operation_registry", null, values)
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
