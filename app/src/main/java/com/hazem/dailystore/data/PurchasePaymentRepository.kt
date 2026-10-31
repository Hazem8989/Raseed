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

data class PurchaseInvoicePaymentSummary(
    val id: String, val invoiceNumber: String, val date: String, val invoiceStatus: String,
    val paymentStatus: String, val totalUsdCents: Long, val paidUsdCents: Long,
    val outstandingUsdCents: Long, val supplierId: String?, val supplierName: String?
)
data class SupplierFinancialSummary(
    val id: String, val name: String, val phone: String?, val address: String?,
    val totalPurchaseUsdCents: Long, val paidUsdCents: Long,
    val outstandingUsdCents: Long, val unpaidInvoiceCount: Int
)
data class PurchasePaymentRecord(
    val id: String, val amountMinor: Long, val currencyCode: String, val accountName: String,
    val accountType: String, val createdAt: String, val note: String?,
    val cashTransactionId: String, val cashAccountId: String
)
data class PurchasePaymentResult(
    val paymentId: String, val paidUsdCents: Long, val outstandingUsdCents: Long,
    val paymentStatus: String, val replayed: Boolean
)

/** Posts supplier payments atomically with their cash-account outflow. */
class PurchasePaymentRepository(context: Context) {
    private val database = AppDatabase.getInstance(context)

    fun listPurchaseInvoices(supplierId: String? = null): List<PurchaseInvoicePaymentSummary> {
        val result = mutableListOf<PurchaseInvoicePaymentSummary>()
        val selection = if (supplierId == null) "" else "WHERE i.supplier_id=?"
        val args = if (supplierId == null) null else arrayOf(supplierId)
        database.readableDatabase.rawQuery(
            """SELECT i.id,i.invoice_number,i.invoice_date,i.status,i.total_usd_cents,
                      i.supplier_id,s.name
               FROM purchase_invoices i LEFT JOIN suppliers s ON s.id=i.supplier_id
               $selection ORDER BY i.invoice_date DESC,i.created_at DESC""".trimIndent(), args
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val invoiceStatus = c.getString(3)
                val total = c.getLong(4)
                val paid = paidUsdCents(database.readableDatabase, id)
                val outstanding = (total - paid).coerceAtLeast(0L)
                val paymentStatus = when {
                    invoiceStatus == "CANCELLED" -> "CANCELLED"
                    invoiceStatus == "DRAFT" -> "DRAFT"
                    outstanding == 0L -> "PAID"
                    paid > 0L -> "PARTIALLY_PAID"
                    else -> "UNPAID"
                }
                result.add(PurchaseInvoicePaymentSummary(
                    id, c.getString(1), c.getString(2), invoiceStatus, paymentStatus,
                    total, paid, outstanding, if (c.isNull(5)) null else c.getString(5),
                    if (c.isNull(6)) null else c.getString(6)
                ))
            }
        }
        return result
    }

    fun listSupplierSummaries(): List<SupplierFinancialSummary> {
        val result = mutableListOf<SupplierFinancialSummary>()
        database.readableDatabase.rawQuery(
            "SELECT id,name,phone,address FROM suppliers ORDER BY name COLLATE NOCASE", null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val invoices = listPurchaseInvoices(id).filter {
                    it.invoiceStatus != "DRAFT" && it.invoiceStatus != "CANCELLED"
                }
                result.add(SupplierFinancialSummary(
                    id, c.getString(1), if (c.isNull(2)) null else c.getString(2),
                    if (c.isNull(3)) null else c.getString(3),
                    invoices.sumOf { it.totalUsdCents }, invoices.sumOf { it.paidUsdCents },
                    invoices.sumOf { it.outstandingUsdCents },
                    invoices.count { it.outstandingUsdCents > 0L }
                ))
            }
        }
        return result
    }

    fun listPayments(invoiceId: String): List<PurchasePaymentRecord> {
        val result = mutableListOf<PurchasePaymentRecord>()
        database.readableDatabase.rawQuery(
            """SELECT p.id,p.amount_minor,p.currency_code,a.name,a.account_type,
                      p.created_at,t.description,t.id,t.account_id
               FROM invoice_payments p
               JOIN cash_transactions t ON t.id=p.cash_transaction_id
               JOIN cash_accounts a ON a.id=t.account_id
               WHERE p.invoice_type='PURCHASE' AND p.invoice_id=?
               ORDER BY p.created_at DESC""".trimIndent(), arrayOf(invoiceId)
        ).use { c ->
            while (c.moveToNext()) result.add(PurchasePaymentRecord(
                c.getString(0), c.getLong(1), c.getString(2), c.getString(3),
                c.getString(4), c.getString(5), if (c.isNull(6)) null else c.getString(6),
                c.getString(7), c.getString(8)
            ))
        }
        return result
    }

    fun addPayment(
        invoiceId: String, operationId: String, cashAccountId: String,
        amountMinor: Long, note: String? = null
    ): PurchasePaymentResult {
        require(invoiceId.isNotBlank() && operationId.isNotBlank() && cashAccountId.isNotBlank()) {
            "بيانات الدفعة غير مكتملة"
        }
        require(amountMinor > 0L) { "مبلغ الدفعة يجب أن يكون أكبر من صفر" }
        val db = database.writableDatabase
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        val payloadHash = hash("$invoiceId|$cashAccountId|$amountMinor|${cleanNote.orEmpty()}")
        db.beginTransaction()
        try {
            findOperation(db, operationId)?.let { existing ->
                require(existing.type == "PURCHASE_PAYMENT" && existing.payloadHash == payloadHash) {
                    "معرّف العملية مستخدم لعملية أو بيانات مختلفة"
                }
                val paymentId = existing.reference ?: error("سجل العملية لا يحتوي مرجع الدفعة")
                val current = invoicePaymentState(db, invoiceId)
                db.setTransactionSuccessful()
                return PurchasePaymentResult(paymentId, current.first, current.second, current.third, true)
            }

            val invoice = db.rawQuery(
                "SELECT status,total_usd_cents,supplier_id,invoice_number FROM purchase_invoices WHERE id=?",
                arrayOf(invoiceId)
            ).use {
                require(it.moveToFirst()) { "فاتورة الشراء غير موجودة" }
                InvoiceForPayment(it.getString(0), it.getLong(1),
                    if (it.isNull(2)) null else it.getString(2), it.getString(3))
            }
            require(invoice.status == "POSTED" || invoice.status == "PARTIALLY_PAID") {
                "لا يمكن دفع فاتورة غير معتمدة أو ملغاة"
            }
            require(invoice.supplierId != null) { "يجب تحديد المورد في الفاتورة قبل تسجيل الدفعة" }

            val account = db.rawQuery(
                "SELECT name,account_type,currency_code,opening_balance_minor,is_active FROM cash_accounts WHERE id=?",
                arrayOf(cashAccountId)
            ).use {
                require(it.moveToFirst()) { "الحساب المالي غير موجود" }
                require(it.getInt(4) == 1) { "لا يمكن الدفع من حساب موقوف" }
                AccountForPayment(it.getString(0), it.getString(1), it.getString(2), it.getLong(3))
            }
            require(account.currencyCode == "USD" || account.currencyCode == "SYP") {
                "عملة الحساب غير مدعومة"
            }
            val rate = currentRate(db)
            val usdEquivalent = when (account.currencyCode) {
                "USD" -> amountMinor
                "SYP" -> {
                    require(rate > 0L) { "يجب تسجيل سعر الصرف قبل الدفع بالليرة السورية" }
                    CurrencyConverter.sypToUsdCents(amountMinor, rate)
                }
                else -> error("عملة الحساب غير مدعومة")
            }
            require(usdEquivalent > 0L) { "مبلغ الدفعة صغير جداً ولا يقابل قيمة بالدولار" }

            val currentState = invoicePaymentState(db, invoiceId)
            require(usdEquivalent <= currentState.second) {
                "مبلغ الدفعة يتجاوز المتبقي على الفاتورة"
            }
            val currentAccountBalance = accountBalance(db, cashAccountId, account.openingBalanceMinor)
            require(currentAccountBalance >= amountMinor) {
                "رصيد الحساب غير كافٍ. الرصيد المتاح: $currentAccountBalance ${account.currencyCode}"
            }

            val now = timestamp()
            val paymentId = UUID.randomUUID().toString()
            val cashTransactionId = UUID.randomUUID().toString()
            val cash = ContentValues().apply {
                put("id", cashTransactionId); put("account_id", cashAccountId)
                put("direction", "OUT"); put("amount_minor", amountMinor)
                put("currency_code", account.currencyCode)
                if (rate > 0L) put("exchange_rate_syp_per_usd_milli", rate)
                else putNull("exchange_rate_syp_per_usd_milli")
                put("transaction_type", "PURCHASE_PAYMENT")
                put("source_type", "PURCHASE_INVOICE"); put("source_id", invoiceId)
                put("operation_id", operationId)
                put("description", cleanNote ?: "دفعة للمورد عن الفاتورة ${invoice.invoiceNumber}")
                put("created_at", now)
            }
            db.insertOrThrow("cash_transactions", null, cash)

            val payment = ContentValues().apply {
                put("id", paymentId); put("invoice_type", "PURCHASE")
                put("invoice_id", invoiceId); put("cash_transaction_id", cashTransactionId)
                put("amount_minor", amountMinor); put("currency_code", account.currencyCode)
                if (rate > 0L) put("exchange_rate_syp_per_usd_milli", rate)
                else putNull("exchange_rate_syp_per_usd_milli")
                put("created_at", now)
            }
            db.insertOrThrow("invoice_payments", null, payment)

            val newPaid = Math.addExact(currentState.first, usdEquivalent)
            val newOutstanding = (invoice.totalUsdCents - newPaid).coerceAtLeast(0L)
            val newStatus = if (newOutstanding == 0L) "PAID" else "PARTIALLY_PAID"
            db.execSQL(
                "UPDATE purchase_invoices SET status=?,updated_at=? WHERE id=? AND status IN ('POSTED','PARTIALLY_PAID')",
                arrayOf(newStatus, now, invoiceId)
            )
            require(db.rawQuery("SELECT changes()", null).use { it.moveToFirst() && it.getInt(0) == 1 }) {
                "تعذر تحديث حالة الفاتورة"
            }
            registerOperation(db, operationId, "PURCHASE_PAYMENT", payloadHash, paymentId, now)
            db.setTransactionSuccessful()
            return PurchasePaymentResult(paymentId, newPaid, newOutstanding, newStatus, false)
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Corrects an existing supplier payment in place, with the cash ledger and invoice state
     * updated atomically. Currency and its original FX snapshot are immutable.
     */
    fun editPayment(
        actorRole: String, actorUserId: String, paymentId: String,
        newCashAccountId: String, newAmountMinor: Long, newNote: String?
    ): PurchasePaymentResult {
        require(actorRole == "ADMIN") { "تعديل دفعات الموردين متاح للمدير فقط" }
        require(actorUserId.isNotBlank()) { "جلسة المدير غير صالحة" }
        require(paymentId.isNotBlank() && newCashAccountId.isNotBlank()) { "بيانات تعديل الدفعة غير مكتملة" }
        require(newAmountMinor > 0L) { "مبلغ الدفعة يجب أن يكون أكبر من صفر" }
        val cleanNote = newNote?.trim()?.takeIf { it.isNotEmpty() }
        val db = database.writableDatabase
        val managerExists = db.rawQuery(
            "SELECT 1 FROM users WHERE id=? AND role_key='ADMIN' AND is_active=1", arrayOf(actorUserId)
        ).use { it.moveToFirst() }
        require(managerExists) { "يجب تسجيل الدخول بحساب مدير نشط لتعديل دفعات الموردين" }
        db.beginTransaction()
        try {
            val old = db.rawQuery(
                """SELECT p.invoice_id,p.amount_minor,p.currency_code,p.exchange_rate_syp_per_usd_milli,
                          p.cash_transaction_id,t.account_id,t.description,i.total_usd_cents,i.status,i.invoice_number
                   FROM invoice_payments p
                   JOIN cash_transactions t ON t.id=p.cash_transaction_id
                   JOIN purchase_invoices i ON i.id=p.invoice_id
                   WHERE p.id=? AND p.invoice_type='PURCHASE'""".trimIndent(),
                arrayOf(paymentId)
            ).use {
                require(it.moveToFirst()) { "دفعة المورد غير موجودة" }
                ExistingSupplierPayment(
                    it.getString(0),it.getLong(1),it.getString(2),if(it.isNull(3)) null else it.getLong(3),
                    it.getString(4),it.getString(5),if(it.isNull(6)) null else it.getString(6),
                    it.getLong(7),it.getString(8),it.getString(9)
                )
            }
            require(old.invoiceStatus in setOf("POSTED","PARTIALLY_PAID","PAID")) {
                "لا يمكن تعديل دفعة فاتورة غير معتمدة أو ملغاة"
            }
            val target = db.rawQuery(
                "SELECT name,currency_code,is_active,opening_balance_minor FROM cash_accounts WHERE id=?",
                arrayOf(newCashAccountId)
            ).use {
                require(it.moveToFirst()) { "الحساب المالي غير موجود" }
                require(it.getInt(2) == 1) { "لا يمكن نقل الدفعة إلى حساب موقوف" }
                SupplierPaymentAccount(it.getString(0),it.getString(1),it.getLong(3))
            }
            require(target.currencyCode == old.currencyCode) {
                "يجب اختيار حساب بنفس عملة الدفعة؛ لا يمكن تغيير العملة التاريخية أثناء التصحيح"
            }
            val rate = old.exchangeRateMilli ?: 0L
            val newUsdEquivalent = when (old.currencyCode) {
                "USD" -> newAmountMinor
                "SYP" -> {
                    require(rate > 0L) { "دفعة الليرة لا تحتوي سعر صرف محفوظاً ولا يمكن تعديلها آلياً" }
                    CurrencyConverter.sypToUsdCents(newAmountMinor, rate)
                }
                else -> error("عملة دفعة غير مدعومة")
            }
            require(newUsdEquivalent > 0L) { "المبلغ الجديد صغير جداً" }

            val paidBefore = paidUsdCents(db, old.invoiceId)
            val oldUsdEquivalent = when (old.currencyCode) {
                "USD" -> old.amountMinor
                "SYP" -> {
                    require(rate > 0L) { "دفعة الليرة لا تحتوي سعر صرف محفوظاً" }
                    CurrencyConverter.sypToUsdCents(old.amountMinor, rate)
                }
                else -> error("عملة دفعة غير مدعومة")
            }
            val paidWithoutThis = (paidBefore - oldUsdEquivalent).coerceAtLeast(0L)
            require(newUsdEquivalent <= (old.invoiceTotalUsdCents - paidWithoutThis).coerceAtLeast(0L)) {
                "المبلغ المعدل يتجاوز قيمة الفاتورة بعد احتساب الدفعات الأخرى"
            }

            val oldBalance = accountBalance(db, old.cashAccountId)
            if (old.cashAccountId == newCashAccountId) {
                require(Math.addExact(oldBalance, old.amountMinor) >= newAmountMinor) {
                    "الرصيد غير كافٍ بعد تعديل مبلغ الدفعة"
                }
            } else {
                require(accountBalance(db, newCashAccountId) >= newAmountMinor) {
                    "رصيد الحساب الجديد غير كافٍ لتسجيل الدفعة المصححة"
                }
            }

            val beforeJson = "{\"amount_minor\":${old.amountMinor},\"currency\":${jsonString(old.currencyCode)}," +
                "\"account_id\":${jsonString(old.cashAccountId)},\"note\":${jsonString(old.note.orEmpty())}}"
            val afterJson = "{\"amount_minor\":$newAmountMinor,\"currency\":${jsonString(old.currencyCode)}," +
                "\"account_id\":${jsonString(newCashAccountId)},\"note\":${jsonString(cleanNote.orEmpty())}}"
            val now = timestamp()
            val description = cleanNote ?: "دفعة مصححة للمورد عن الفاتورة ${old.invoiceNumber}"
            val transactionValues = ContentValues().apply {
                put("account_id",newCashAccountId); put("amount_minor",newAmountMinor)
                put("description",description)
            }
            require(db.update("cash_transactions",transactionValues,"id=?",arrayOf(old.cashTransactionId)) == 1) {
                "تعذر تعديل حركة الصندوق المرتبطة بالدفعة"
            }
            val paymentValues = ContentValues().apply { put("amount_minor",newAmountMinor) }
            require(db.update("invoice_payments",paymentValues,"id=?",arrayOf(paymentId)) == 1) {
                "تعذر تعديل سجل دفعة الفاتورة"
            }
            val paidAfter = Math.addExact(paidWithoutThis,newUsdEquivalent)
            val outstanding = (old.invoiceTotalUsdCents-paidAfter).coerceAtLeast(0L)
            val status = if (outstanding == 0L) "PAID" else if (paidAfter > 0L) "PARTIALLY_PAID" else "POSTED"
            db.execSQL("UPDATE purchase_invoices SET status=?,updated_at=? WHERE id=?",
                arrayOf(status,now,old.invoiceId))
            val audit = ContentValues().apply {
                put("id",UUID.randomUUID().toString()); put("user_id",actorUserId)
                put("action","CORRECT"); put("entity_type","PURCHASE_PAYMENT"); put("entity_id",paymentId)
                put("before_json",beforeJson); put("after_json",afterJson); put("created_at",now)
            }
            db.insertOrThrow("audit_log",null,audit)
            db.setTransactionSuccessful()
            return PurchasePaymentResult(paymentId,paidAfter,outstanding,
                if(outstanding==0L) "PAID" else "PARTIALLY_PAID",false)
        } finally { db.endTransaction() }
    }

    private data class ExistingSupplierPayment(
        val invoiceId:String,val amountMinor:Long,val currencyCode:String,val exchangeRateMilli:Long?,
        val cashTransactionId:String,val cashAccountId:String,val note:String?,val invoiceTotalUsdCents:Long,
        val invoiceStatus:String,val invoiceNumber:String
    )
    private data class SupplierPaymentAccount(
        val name:String,val currencyCode:String,val openingBalanceMinor:Long
    )

    private fun accountBalance(db: SQLiteDatabase, accountId: String): Long {
        val opening = db.rawQuery("SELECT opening_balance_minor FROM cash_accounts WHERE id=?",
            arrayOf(accountId)).use { require(it.moveToFirst()); it.getLong(0) }
        val movement = db.rawQuery(
            """SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_minor
                       WHEN direction='OUT' THEN -amount_minor ELSE 0 END),0)
               FROM cash_transactions WHERE account_id=?""".trimIndent(),arrayOf(accountId)
        ).use { if(it.moveToFirst()) it.getLong(0) else 0L }
        return Math.addExact(opening,movement)
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""

    private fun paidUsdCents(db: SQLiteDatabase, invoiceId: String): Long {
        var total = 0L
        db.rawQuery(
            """SELECT amount_minor,currency_code,exchange_rate_syp_per_usd_milli
               FROM invoice_payments WHERE invoice_type='PURCHASE' AND invoice_id=?""".trimIndent(),
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
                    else -> error("عملة دفعة غير مدعومة: $currency")
                }
                total = Math.addExact(total, equivalent)
            }
        }
        return total
    }

    private fun invoicePaymentState(db: SQLiteDatabase, invoiceId: String): Triple<Long, Long, String> {
        val total = db.rawQuery(
            "SELECT total_usd_cents FROM purchase_invoices WHERE id=?", arrayOf(invoiceId)
        ).use { require(it.moveToFirst()) { "فاتورة الشراء غير موجودة" }; it.getLong(0) }
        val paid = paidUsdCents(db, invoiceId)
        val outstanding = (total - paid).coerceAtLeast(0L)
        val status = when {
            outstanding == 0L -> "PAID"
            paid > 0L -> "PARTIALLY_PAID"
            else -> "UNPAID"
        }
        return Triple(paid, outstanding, status)
    }

    private fun accountBalance(db: SQLiteDatabase, id: String, opening: Long): Long {
        val movement = db.rawQuery(
            """SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_minor
                       WHEN direction='OUT' THEN -amount_minor ELSE 0 END),0)
               FROM cash_transactions WHERE account_id=?""".trimIndent(), arrayOf(id)
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        return Math.addExact(opening, movement)
    }

    private fun currentRate(db: SQLiteDatabase): Long =
        db.rawQuery(
            "SELECT rate_syp_per_usd_milli FROM exchange_rates ORDER BY effective_at DESC,created_at DESC LIMIT 1",
            null
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private data class InvoiceForPayment(
        val status: String, val totalUsdCents: Long, val supplierId: String?, val invoiceNumber: String
    )
    private data class AccountForPayment(
        val name: String, val accountType: String, val currencyCode: String, val openingBalanceMinor: Long
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

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
