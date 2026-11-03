package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Applies the currently supported incoming invoice commands on the primary.
 *
 * The inbox receipt is durable before this class is called. Draft creation and
 * posting use deterministic operation IDs so a process death between those
 * steps can safely resume without posting stock or cash twice.
 *
 * Cash-sale receipt accounts must have an explicit per-device mapping on the primary.
 */
class SyncDomainApplier(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)

    data class ApplyResult(
        val operationId: String,
        val applied: Boolean,
        val reason: String? = null
    )

    fun applyReceivedInvoice(operationId: String): ApplyResult = synchronized(APPLY_LOCK) {
        require(operationId.isNotBlank()) { "معرّف الأمر مطلوب" }
        val db = database.writableDatabase
        val localDeviceId = SyncDeviceIdentity.getOrCreate(appContext)
        val primaryAuthority = db.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='authority_device_id'", null
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val localRole = db.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'", null
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        require(localRole == "PRIMARY" && primaryAuthority == localDeviceId) {
            "تطبيق الأوامر الواردة مسموح على الجهاز الأساسي المعتمد فقط"
        }
        val row = db.rawQuery(
            """SELECT i.origin_device_id,i.entity_type,i.entity_id,i.payload_json,i.status,
                      m.primary_user_id,i.origin_created_at
               FROM sync_inbox i
               JOIN sync_actor_mappings m
                 ON m.device_id=i.origin_device_id AND m.is_active=1
               JOIN users u ON u.id=m.primary_user_id
               JOIN sync_devices d ON d.device_id=i.origin_device_id
               WHERE i.operation_id=? AND d.device_role='SECONDARY'
                 AND d.status='ACTIVE' AND u.is_active=1 AND u.must_change_password=0
               ORDER BY m.mapped_at DESC LIMIT 1""".trimIndent(),
            arrayOf(operationId)
        ).use { c ->
            require(c.moveToFirst()) { "الأمر غير موجود أو الجهاز/المستخدم غير مفعل أو لم يتم ربط المستخدم" }
            InboxCommand(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                c.getString(4), c.getString(5), c.getString(6)
            )
        }
        if (row.status == "APPLIED") return@synchronized ApplyResult(operationId, true)
        require(row.status == "RECEIVED") { "حالة الأمر لا تسمح بالتطبيق: ${row.status}" }

        val payload = JSONObject(row.payloadJson)
        val businessOperation = payload.optString("business_operation")
        val requiredPermission = when (businessOperation) {
            "POST_PURCHASE_INVOICE" -> PermissionPolicy.PURCHASES_MANAGE
            "POST_SALES_INVOICE" -> PermissionPolicy.SALES_CHECKOUT
            else -> throw IllegalArgumentException("نوع الأمر غير مدعوم في مطبّق الفواتير: $businessOperation")
        }
        PermissionPolicy.require(db, row.primaryUserId, requiredPermission)

        val invoiceId = payload.getString("id")
        require(invoiceId == row.entityId) { "معرّف الفاتورة لا يطابق معرّف الكيان في الظرف" }
        val invoiceType = if (businessOperation == "POST_PURCHASE_INVOICE") "PURCHASE" else "SALE"
        val saleType = payload.optString("sale_type", "CASH")
        require(invoiceType != "SALE" || saleType == "CREDIT" || saleType == "CASH") {
            "نوع البيع الوارد غير مدعوم"
        }
        val items = payload.getJSONArray("items")
        require(items.length() in 1..500) { "عدد أصناف الفاتورة غير صالح" }
        val lines = parseLines(items)
        val partyId = if (payload.isNull("party_id")) null else payload.getString("party_id")
        val date = payload.getString("invoice_date")
        val discount = payload.getLong("discount_usd_cents")
        val notes = if (payload.isNull("notes")) null else payload.getString("notes")
        val rate = payload.getLong("exchange_rate_syp_per_usd_milli")
        require(rate > 0L) { "الفاتورة الواردة لا تحتوي سعر صرف صالحاً" }

        val sourceEventAt = payload.optString("posted_at").takeIf { it.isNotBlank() && it != "null" }
            ?: row.originCreatedAt
        require(sourceEventAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*"))) {
            "الأمر الوارد لا يحتوي وقت وقوع أصلياً صالحاً"
        }
        val sourceOperatingDate = payload.optString("posted_operating_date")
            .takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
            ?: sourceEventAt.substring(0, 10)

        var mappedCashAccountId: String? = null
        if (invoiceType == "SALE" && saleType == "CASH") {
            val remoteCashAccountId = payload.optString("cash_account_id")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: throw IllegalArgumentException("البيع النقدي الوارد لا يحدد حساب القبض الأصلي")
            val remoteAccountType = payload.optString("cash_account_type")
            val remoteCurrency = payload.optString("cash_account_currency_code")
            require(remoteAccountType in setOf("CASH_BOX", "WALLET") &&
                remoteCurrency in setOf("USD", "SYP")) {
                "بيانات حساب القبض الأصلي غير مكتملة أو غير مدعومة"
            }
            mappedCashAccountId = db.rawQuery(
                """SELECT m.primary_cash_account_id,a.account_type,a.currency_code
                   FROM sync_cash_account_mappings m
                   JOIN cash_accounts a ON a.id=m.primary_cash_account_id
                   WHERE m.device_id=? AND m.remote_cash_account_id=? AND m.is_active=1
                     AND a.is_active=1""".trimIndent(),
                arrayOf(row.originDeviceId, remoteCashAccountId)
            ).use { c ->
                require(c.moveToFirst()) { "لم يتم ربط حساب قبض الجهاز الثانوي بحساب على الجهاز الأساسي" }
                require(c.getString(1) == remoteAccountType && c.getString(2) == remoteCurrency) {
                    "نوع أو عملة حساب القبض المرتبط لا يطابق الحساب الأصلي"
                }
                c.getString(0)
            }
            val receipts = payload.optJSONArray("cash_receipts")
                ?: throw IllegalArgumentException("البيع النقدي الوارد لا يحتوي تفاصيل القبض")
            require(receipts.length() == 1) { "يجب أن يحتوي البيع النقدي على قيد قبض واحد مطابق" }
            val receipt = receipts.getJSONObject(0)
            require(receipt.optString("cash_transaction_id").isNotBlank()) {
                "معرّف قيد القبض الأصلي مفقود"
            }
            val expectedCurrency = remoteCurrency
            require(receipt.optString("currency_code") == expectedCurrency) {
                "عملة قيد القبض لا تطابق عملة الحساب الأصلي"
            }
            val expectedAmount = when (expectedCurrency) {
                "USD" -> payload.getLong("total_usd_cents")
                "SYP" -> CurrencyConverter.usdCentsToSyp(
                    payload.getLong("total_usd_cents"), rate
                )
                else -> error("عملة قبض غير مدعومة")
            }
            require(receipt.getLong("amount_minor") == expectedAmount) {
                "قيمة قيد القبض الأصلي لا تطابق إجمالي الفاتورة وسعر صرفها"
            }
            val receiptRate = if (receipt.isNull("exchange_rate_syp_per_usd_milli")) 0L
                else receipt.getLong("exchange_rate_syp_per_usd_milli")
            require(receiptRate == rate) { "سعر صرف قيد القبض لا يطابق سعر الفاتورة" }
        }

        val invoices = InvoiceRepository(appContext)
        val draftOperationId = "${operationId}:IMPORT_DRAFT"
        val postOperationId = "${operationId}:IMPORT_POST"
        val saved = if (invoiceType == "PURCHASE") {
            invoices.savePurchaseDraft(
                row.primaryUserId, draftOperationId, invoiceId, partyId,
                date, discount, notes, lines
            )
        } else {
            invoices.saveSalesDraft(
                row.primaryUserId, draftOperationId, invoiceId, partyId,
                date, discount, notes, lines, saleType
            )
        }
        require(saved.invoiceId == invoiceId) { "لم يتم حفظ الفاتورة بالمعرّف الوارد" }

        // Preserve the source invoice's exchange-rate snapshot rather than
        // silently substituting the primary device's current rate.
        val table = if (invoiceType == "PURCHASE") "purchase_invoices" else "sales_invoices"
        val changed = db.update(
            table,
            ContentValues().apply { put("exchange_rate_syp_per_usd_milli", rate) },
            "id=? AND status='DRAFT'",
            arrayOf(invoiceId)
        )
        if (changed == 0) {
            val existingRate = db.rawQuery(
                "SELECT exchange_rate_syp_per_usd_milli FROM $table WHERE id=?",
                arrayOf(invoiceId)
            ).use { c -> require(c.moveToFirst()); c.getLong(0) }
            require(existingRate == rate) { "سعر صرف الفاتورة المحلية يختلف عن السعر الوارد" }
        }

        if (invoiceType == "PURCHASE") {
            invoices.postPurchase(row.primaryUserId, invoiceId, postOperationId, sourceEventAt, sourceOperatingDate)
        } else if (saleType == "CASH") {
            invoices.postCashSale(
                row.primaryUserId, invoiceId, postOperationId,
                requireNotNull(mappedCashAccountId), sourceEventAt, sourceOperatingDate
            )
        } else {
            invoices.postSale(row.primaryUserId, invoiceId, postOperationId, sourceEventAt, sourceOperatingDate)
        }

        db.beginTransaction()
        try {
            val current = db.rawQuery(
                "SELECT status,payload_sha256 FROM sync_inbox WHERE operation_id=?",
                arrayOf(operationId)
            ).use { c ->
                require(c.moveToFirst()) { "اختفى أمر المزامنة أثناء التطبيق" }
                c.getString(0) to c.getString(1)
            }
            require(current.first == "RECEIVED") { "تغيرت حالة الأمر أثناء التطبيق" }
            db.update(
                "sync_inbox",
                ContentValues().apply {
                    put("status", "APPLIED")
                    put("applied_at", timestamp())
                    putNull("rejection_reason")
                },
                "operation_id=? AND status='RECEIVED'",
                arrayOf(operationId)
            ).also { require(it == 1) { "تعذر تثبيت حالة تطبيق الأمر" } }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ApplyResult(operationId, true)
    }

    private fun parseLines(items: JSONArray): List<InvoiceLineInput> =
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val quantity = BigDecimal(item.getString("quantity"))
            val unitPrice = item.getLong("unit_price_or_cost_usd_cents")
            require(item.getString("product_id").isNotBlank() && quantity > BigDecimal.ZERO && unitPrice >= 0L) {
                "بيانات أحد أصناف الفاتورة غير صالحة"
            }
            InvoiceLineInput(item.getString("product_id"), quantity, unitPrice)
        }

    private data class InboxCommand(
        val originDeviceId: String,
        val entityType: String,
        val entityId: String,
        val payloadJson: String,
        val status: String,
        val primaryUserId: String,
        val originCreatedAt: String
    )

    private fun timestamp(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

    companion object {
        private val APPLY_LOCK = Any()
    }
}
