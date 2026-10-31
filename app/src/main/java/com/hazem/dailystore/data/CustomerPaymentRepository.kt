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
    val outstandingUsdCents: Long, val originalRateMilli: Long
)

data class CustomerFinancialSummary(
    val id: String, val name: String, val phone: String?,
    val totalCreditUsdCents: Long, val paidUsdCents: Long,
    val outstandingUsdCents: Long, val unpaidInvoiceCount: Int
)

data class CustomerPaymentRecord(
    val id: String, val amountMinor: Long, val currencyCode: String,
    val accountName: String, val createdAt: String, val note: String?
)

data class CustomerPaymentResult(
    val paymentId: String, val paidUsdCents: Long,
    val outstandingUsdCents: Long, val paymentStatus: String,
    val replayed: Boolean
)

/**
 * Receives customer payments against one explicitly selected posted CREDIT sale invoice.
 * Each receipt is linked to its cash-account inflow and invoice in one SQLite transaction.
 */
class CustomerPaymentRepository(context: Context) {
    private val database = AppDatabase.getInstance(context)

    fun listCustomerSummaries(): List<CustomerFinancialSummary> {
        val result = mutableListOf<CustomerFinancialSummary>()
        database.readableDatabase.rawQuery(
            "SELECT id,name,phone FROM customers WHERE is_active=1 ORDER BY name COLLATE NOCASE",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val customerId = c.getString(0)
                val invoices = listInvoices(customerId)
                val eligible = invoices.filter { it.invoiceStatus != "DRAFT" && it.invoiceStatus != "CANCELLED" }
                result.add(CustomerFinancialSummary(
                    customerId, c.getString(1), if (c.isNull(2)) null else c.getString(2),
                    eligible.sumOf { it.totalUsdCents }, eligible.sumOf { it.paidUsdCents },
                    eligible.sumOf { it.outstandingUsdCents },
                    eligible.count { it.outstandingUsdCents > 0L }
                ))
            }
        }
        return result
    }

    fun listInvoices(customerId: String): List<CustomerInvoicePaymentSummary> {
        require(customerId.isNotBlank()) { "معرّف الزبون مطلوب" }
        val result = mutableListOf<CustomerInvoicePaymentSummary>()
        database.readableDatabase.rawQuery(
            """SELECT id,invoice_number,invoice_date,status,total_usd_cents,
                      exchange_rate_syp_per_usd_milli
               FROM sales_invoices
               WHERE customer_id=? AND sale_type='CREDIT'
               ORDER BY invoice_date DESC,created_at DESC""".trimIndent(),
            arrayOf(customerId)
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val status = c.getString(3)
                val total = c.getLong(4)
                val paid = paidUsdCents(database.readableDatabase, id)
                val outstanding = (total - paid).coerceAtLeast(0L)
                val paymentStatus = when {
                    status == "CANCELLED" -> "CANCELLED"
                    status == "DRAFT" -> "DRAFT"
                    outstanding == 0L -> "PAID"
                    paid > 0L -> "PARTIALLY_PAID"
                    else -> "UNPAID"
                }
                result.add(CustomerInvoicePaymentSummary(
                    id, c.getString(1), c.getString(2), status, paymentStatus,
                    total, paid, outstanding, c.getLong(5)
                ))
            }
        }
        return result
    }

    fun listPayments(invoiceId: String): List<CustomerPaymentRecord> {
        require(invoiceId.isNotBlank()) { "معرّف الفاتورة مطلوب" }
        val result = mutableListOf<CustomerPaymentRecord>()
        database.readableDatabase.rawQuery(
            """SELECT p.id,p.amount_minor,p.currency_code,a.name,p.created_at,t.description
               FROM invoice_payments p
               JOIN cash_transactions t ON t.id=p.cash_transaction_id
               JOIN cash_accounts a ON a.id=t.account_id
               WHERE p.invoice_type='SALE' AND p.invoice_id=?
               ORDER BY p.created_at DESC""".trimIndent(),
            arrayOf(invoiceId)
        ).use { c ->
            while (c.moveToNext()) result.add(CustomerPaymentRecord(
                c.getString(0), c.getLong(1), c.getString(2), c.getString(3),
                c.getString(4), if (c.isNull(5)) null else c.getString(5)
            ))
        }
        return result
    }

    fun addPayment(
        invoiceId: String, operationId: String, cashAccountId: String,
        amountMinor: Long, note: String? = null
    ): CustomerPaymentResult {
        require(invoiceId.isNotBlank() && operationId.isNotBlank() && cashAccountId.isNotBlank()) {
            "بيانات التحصيل غير مكتملة"
        }
        require(amountMinor > 0L) { "مبلغ التحصيل يجب أن يكون أكبر من صفر" }
        val db = database.writableDatabase
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        val payloadHash = hash(invoiceId + "|" + cashAccountId + "|" + amountMinor + "|" + cleanNote.orEmpty())
        db.beginTransaction()
        try {
            findOperation(db, operationId)?.let { existing ->
                require(existing.type == "CUSTOMER_PAYMENT" && existing.payloadHash == payloadHash) {
                    "معرّف العملية مستخدم لعملية أو بيانات مختلفة"
                }
                val paymentId = existing.reference ?: error("سجل العملية لا يحتوي مرجع التحصيل")
                val state = invoicePaymentState(db, invoiceId)
                db.setTransactionSuccessful()
                return CustomerPaymentResult(paymentId, state.first, state.second, state.third, true)
            }

            val invoice = db.rawQuery(
                """SELECT status,sale_type,total_usd_cents,customer_id,invoice_number
                   FROM sales_invoices WHERE id=?""".trimIndent(),
                arrayOf(invoiceId)
            ).use {
                require(it.moveToFirst()) { "فاتورة البيع غير موجودة" }
                CustomerInvoiceForPayment(
                    it.getString(0), it.getString(1), it.getLong(2),
                    if (it.isNull(3)) null else it.getString(3), it.getString(4)
                )
            }
            require(invoice.saleType == "CREDIT" && invoice.customerId != null) {
                "التحصيل متاح لفواتير البيع الآجل المحددة لزبون فقط"
            }
            require(invoice.status == "POSTED" || invoice.status == "PARTIALLY_PAID") {
                "لا يمكن تحصيل فاتورة غير معتمدة أو مدفوعة أو ملغاة"
            }

            val account = db.rawQuery(
                "SELECT name,currency_code,is_active FROM cash_accounts WHERE id=?",
                arrayOf(cashAccountId)
            ).use {
                require(it.moveToFirst()) { "الحساب المالي غير موجود" }
                require(it.getInt(2) == 1) { "لا يمكن التحصيل في حساب موقوف" }
                CustomerAccountForPayment(it.getString(0), it.getString(1))
            }
            require(account.currencyCode == "USD" || account.currencyCode == "SYP") {
                "عملة الحساب غير مدعومة"
            }
            val rate = currentRate(db)
            val usdEquivalent = when (account.currencyCode) {
                "USD" -> amountMinor
                "SYP" -> {
                    require(rate > 0L) { "يجب تسجيل سعر الصرف قبل التحصيل بالليرة السورية" }
                    CurrencyConverter.sypToUsdCents(amountMinor, rate)
                }
                else -> error("عملة حساب غير مدعومة")
            }
            require(usdEquivalent > 0L) { "مبلغ التحصيل صغير جداً ولا يقابل قيمة بالدولار" }

            val currentState = invoicePaymentState(db, invoiceId)
            require(currentState.second > 0L) { "الفاتورة مسددة بالكامل" }
            require(usdEquivalent <= currentState.second) {
                "مبلغ التحصيل يتجاوز المتبقي على الفاتورة"
            }

            val now = timestamp()
            val paymentId = UUID.randomUUID().toString()
            val cashTransactionId = UUID.randomUUID().toString()
            val cash = ContentValues().apply {
                put("id", cashTransactionId)
                put("account_id", cashAccountId)
                put("direction", "IN")
                put("amount_minor", amountMinor)
                put("currency_code", account.currencyCode)
                if (rate > 0L) put("exchange_rate_syp_per_usd_milli", rate)
                else putNull("exchange_rate_syp_per_usd_milli")
                put("transaction_type", "CUSTOMER_PAYMENT")
                put("source_type", "SALES_INVOICE")
                put("source_id", invoiceId)
                put("operation_id", operationId)
                put("description", cleanNote ?: "تحصيل من زبون عن الفاتورة " + invoice.invoiceNumber)
                put("created_at", now)
            }
            db.insertOrThrow("cash_transactions", null, cash)

            val payment = ContentValues().apply {
                put("id", paymentId)
                put("invoice_type", "SALE")
                put("invoice_id", invoiceId)
                put("cash_transaction_id", cashTransactionId)
                put("amount_minor", amountMinor)
                put("currency_code", account.currencyCode)
                if (rate > 0L) put("exchange_rate_syp_per_usd_milli", rate)
                else putNull("exchange_rate_syp_per_usd_milli")
                put("created_at", now)
            }
            db.insertOrThrow("invoice_payments", null, payment)

            val newPaid = Math.addExact(currentState.first, usdEquivalent)
            val newOutstanding = (invoice.totalUsdCents - newPaid).coerceAtLeast(0L)
            val newStatus = if (newOutstanding == 0L) "PAID" else "PARTIALLY_PAID"
            db.execSQL(
                "UPDATE sales_invoices SET status=?,updated_at=? WHERE id=? AND status IN ('POSTED','PARTIALLY_PAID')",
                arrayOf(newStatus, now, invoiceId)
            )
            require(db.rawQuery("SELECT changes()", null).use { it.moveToFirst() && it.getInt(0) == 1 }) {
                "تعذر تحديث حالة فاتورة البيع"
            }
            registerOperation(db, operationId, "CUSTOMER_PAYMENT", payloadHash, paymentId, now)
            db.setTransactionSuccessful()
            return CustomerPaymentResult(paymentId, newPaid, newOutstanding, newStatus, false)
        } finally {
            db.endTransaction()
        }
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
                val currency = c.getString(1)
                val rate = if (c.isNull(2)) 0L else c.getLong(2)
                val equivalent = when (currency) {
                    "USD" -> amount
                    "SYP" -> {
                        require(rate > 0L) { "دفعة ليرة قديمة بلا سعر صرف محفوظ" }
                        CurrencyConverter.sypToUsdCents(amount, rate)
                    }
                    else -> error("عملة دفعة غير مدعومة: " + currency)
                }
                total = Math.addExact(total, equivalent)
            }
        }
        return total
    }

    private fun invoicePaymentState(db: SQLiteDatabase, invoiceId: String): Triple<Long, Long, String> {
        val total = db.rawQuery(
            "SELECT total_usd_cents FROM sales_invoices WHERE id=?", arrayOf(invoiceId)
        ).use { require(it.moveToFirst()) { "فاتورة البيع غير موجودة" }; it.getLong(0) }
        val paid = paidUsdCents(db, invoiceId)
        val outstanding = (total - paid).coerceAtLeast(0L)
        val status = when {
            outstanding == 0L -> "PAID"
            paid > 0L -> "PARTIALLY_PAID"
            else -> "UNPAID"
        }
        return Triple(paid, outstanding, status)
    }

    private fun currentRate(db: SQLiteDatabase): Long =
        db.rawQuery(
            "SELECT rate_syp_per_usd_milli FROM exchange_rates ORDER BY effective_at DESC,created_at DESC LIMIT 1",
            null
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private data class CustomerInvoiceForPayment(
        val status: String, val saleType: String, val totalUsdCents: Long,
        val customerId: String?, val invoiceNumber: String
    )
    private data class CustomerAccountForPayment(val name: String, val currencyCode: String)
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

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
