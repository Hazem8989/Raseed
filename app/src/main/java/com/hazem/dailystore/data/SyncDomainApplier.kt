package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID

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

    /** Applies a signed primary store-time-zone update on a secondary, idempotently. */
    fun applyReceivedStoreTimeZone(operationId: String): ApplyResult = synchronized(APPLY_LOCK) {
        require(operationId.isNotBlank()) { "معرّف الأمر مطلوب" }
        val db = database.writableDatabase
        val localId = SyncDeviceIdentity.getOrCreate(appContext)
        val role = db.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'", null
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val authority = db.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='authority_device_id'", null
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        require(role == "SECONDARY" && !authority.isNullOrBlank() && authority != localId) {
            "تطبيق إعداد المنطقة الزمنية الوارد مسموح على جهاز ثانوي مرتبط فقط"
        }
        db.beginTransaction()
        try {
            val row = db.rawQuery(
                """SELECT origin_device_id,operation_type,entity_type,entity_id,payload_json,status
                   FROM sync_inbox WHERE operation_id=?""".trimIndent(), arrayOf(operationId)
            ).use { c ->
                require(c.moveToFirst()) { "تحديث المنطقة الزمنية غير موجود في صندوق الوارد" }
                arrayOf(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5))
            }
            require(row[0] == authority && row[1] == "UPDATE" &&
                row[2] == "STORE_SETTINGS" && row[3] == "store_time_zone") {
                "مصدر أو نوع تحديث المنطقة الزمنية غير صالح"
            }
            if (row[5] == "APPLIED") {
                db.setTransactionSuccessful()
                return@synchronized ApplyResult(operationId, true)
            }
            require(row[5] == "RECEIVED") { "حالة تحديث المنطقة الزمنية لا تسمح بالتطبيق" }
            val currentSequence = db.rawQuery(
                "SELECT sequence_number FROM sync_inbox WHERE operation_id=?",
                arrayOf(operationId)
            ).use { c -> require(c.moveToFirst()); c.getLong(0) }
            val earlierPending = db.rawQuery(
                """SELECT COUNT(*) FROM sync_inbox
                   WHERE entity_type='STORE_SETTINGS' AND entity_id='store_time_zone'
                     AND status='RECEIVED' AND sequence_number<?""".trimIndent(),
                arrayOf(currentSequence.toString())
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            require(earlierPending == 0) {
                "يجب تطبيق تحديثات المنطقة الزمنية حسب ترتيب وصولها"
            }
            val payload = JSONObject(row[4])
            require(payload.optString("business_operation") == "UPDATE_STORE_TIME_ZONE") {
                "نوع عملية إعداد المتجر غير صالح"
            }
            val zoneId = payload.optString("time_zone")
            require(StoreTime.isValidZoneId(zoneId)) { "معرّف المنطقة الزمنية الوارد غير صالح" }
            StoreTime.setZoneId(db, zoneId, timestamp())
            db.update("sync_inbox", ContentValues().apply {
                put("status", "APPLIED")
                put("applied_at", timestamp())
                putNull("rejection_reason")
            }, "operation_id=? AND status='RECEIVED'", arrayOf(operationId)).also {
                require(it == 1) { "تعذر تثبيت تطبيق إعداد المنطقة الزمنية" }
            }
            db.setTransactionSuccessful()
            ApplyResult(operationId, true)
        } finally { db.endTransaction() }
    }

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
            ?: operatingDateForTimestamp(sourceEventAt)

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

    /**
     * Applies a customer-debt collection as a financial-only event on the primary.
     * Ledger rows and the inbox APPLIED marker commit atomically; no outbox echo is created.
     */
    /** Applies a supplier-payment command from an enrolled secondary on the primary only. */
    fun applyReceivedSupplierPayment(operationId: String): ApplyResult = synchronized(APPLY_LOCK) {
        require(operationId.isNotBlank()) { "معرّف الأمر مطلوب" }
        val db = database.writableDatabase
        val localDeviceId = SyncDeviceIdentity.getOrCreate(appContext)
        val role = db.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'", null
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val authority = db.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='authority_device_id'", null
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        require(role == "PRIMARY" && authority == localDeviceId) {
            "اعتماد دفعات الموردين الواردة مسموح على الجهاز الأساسي المعتمد فقط"
        }

        db.beginTransaction()
        try {
            val command = db.rawQuery(
                """SELECT i.origin_device_id,i.entity_type,i.entity_id,i.payload_json,i.status,
                          m.primary_user_id,i.payload_sha256,i.origin_created_at
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
                require(c.moveToFirst()) { "أمر دفعة المورد غير موجود أو الجهاز/المستخدم غير مفعل أو غير مربوط" }
                arrayOf(
                    c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getString(5), c.getString(6), c.getString(7)
                )
            }
            if (command[4] == "APPLIED") {
                db.setTransactionSuccessful()
                return@synchronized ApplyResult(operationId, true)
            }
            require(command[4] == "RECEIVED") { "حالة أمر دفعة المورد لا تسمح بالتطبيق" }
            require(command[1] == "SUPPLIER_PAYMENT") { "نوع كيان دفعة المورد الوارد غير صالح" }

            val payload = JSONObject(command[3])
            require(payload.optString("business_operation") == "POST_SUPPLIER_PAYMENT") {
                "نوع العملية الواردة ليس دفعة مورد"
            }
            val actorUserId = command[5]
            PermissionPolicy.require(db, actorUserId, PermissionPolicy.SUPPLIER_PAYMENTS_MANAGE)

            val invoiceId = payload.getString("invoice_id")
            val remotePaymentId = payload.getString("payment_id")
            require(invoiceId.isNotBlank() && remotePaymentId.isNotBlank() &&
                command[2] == remotePaymentId) {
                "معرّف دفعة المورد لا يطابق معرّف الكيان في الظرف"
            }

            val existing = db.rawQuery(
                "SELECT operation_type,payload_hash,result_reference FROM operation_registry WHERE operation_id=?",
                arrayOf(operationId)
            ).use { c ->
                if (c.moveToFirst()) arrayOf(
                    c.getString(0), c.getString(1), if (c.isNull(2)) "" else c.getString(2)
                ) else null
            }
            if (existing != null) {
                require(existing[0] == "PURCHASE_PAYMENT" &&
                    existing[1] == command[6] && existing[2].isNotBlank()) {
                    "تعارض في سجل دفعة المورد أو إعادة استخدام معرّفها"
                }
                db.update("sync_inbox", ContentValues().apply {
                    put("status", "APPLIED")
                    put("applied_at", timestamp())
                    putNull("rejection_reason")
                }, "operation_id=? AND status='RECEIVED'", arrayOf(operationId))
                db.setTransactionSuccessful()
                return@synchronized ApplyResult(operationId, true)
            }

            val invoice = db.rawQuery(
                """SELECT status,total_usd_cents,supplier_id,invoice_number,
                          exchange_rate_syp_per_usd_milli
                   FROM purchase_invoices WHERE id=?""".trimIndent(),
                arrayOf(invoiceId)
            ).use { c ->
                require(c.moveToFirst()) { "فاتورة الشراء غير موجودة على الجهاز الأساسي" }
                arrayOf(
                    c.getString(0), c.getLong(1).toString(),
                    if (c.isNull(2)) "" else c.getString(2),
                    c.getString(3), c.getLong(4).toString()
                )
            }
            require(invoice[0] == "POSTED" || invoice[0] == "PARTIALLY_PAID") {
                "لا يمكن دفع فاتورة شراء غير معتمدة أو ملغاة"
            }
            require(invoice[2].isNotBlank()) { "فاتورة الشراء لا تحتوي مورداً محدداً" }
            val invoiceRate = invoice[4].toLong()
            require(invoiceRate > 0L) { "فاتورة الشراء لا تحتوي سعر صرف محفوظاً صالحاً" }

            val remoteAccountId = payload.getString("cash_account_id")
            val remoteAccountType = payload.getString("cash_account_type")
            val remoteCurrency = payload.getString("cash_account_currency_code")
            val currency = payload.getString("currency_code")
            require(remoteAccountType in setOf("CASH_BOX", "WALLET") &&
                remoteCurrency in setOf("USD", "SYP") && currency == remoteCurrency) {
                "نوع أو عملة حساب دفع المورد غير مدعومين"
            }
            val mappedAccountId = db.rawQuery(
                """SELECT m.primary_cash_account_id,a.account_type,a.currency_code,a.opening_balance_minor
                   FROM sync_cash_account_mappings m
                   JOIN cash_accounts a ON a.id=m.primary_cash_account_id
                   WHERE m.device_id=? AND m.remote_cash_account_id=? AND m.is_active=1
                     AND a.is_active=1""".trimIndent(),
                arrayOf(command[0], remoteAccountId)
            ).use { c ->
                require(c.moveToFirst()) { "حساب دفع المورد على الجهاز الثانوي غير مربوط أو موقوف" }
                require(c.getString(1) == remoteAccountType && c.getString(2) == remoteCurrency) {
                    "نوع أو عملة حساب دفع المورد المرتبط لا يطابق الحساب الأصلي"
                }
                c.getString(0)
            }

            val amount = payload.getLong("amount_minor")
            val paymentRate = payload.optLong("payment_rate_milli", 0L)
            val suppliedSypEquivalent = payload.getLong("amount_syp_equivalent")
            require(amount > 0L && paymentRate >= 0L && suppliedSypEquivalent > 0L) {
                "مبلغ أو سعر صرف دفعة المورد غير صالح"
            }
            val calculatedSypEquivalent = when (currency) {
                "USD" -> CurrencyConverter.usdCentsToSyp(amount, invoiceRate)
                "SYP" -> amount
                else -> throw IllegalArgumentException("عملة دفعة المورد غير مدعومة")
            }
            require(calculatedSypEquivalent == suppliedSypEquivalent) {
                "المقابل السوري لدفعة المورد لا يطابق سعر صرف الفاتورة الأصلي"
            }

            val occurredAt = payload.optString("occurred_at").ifBlank { command[7] }
            val operatingDate = payload.optString("operating_date")
            require(occurredAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")) &&
                operatingDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                "تاريخ أو وقت دفعة المورد الأصلي غير صالح"
            }
            val note = if (payload.isNull("note")) null else payload.getString("note").take(500)

            val totalSyp = CurrencyConverter.usdCentsToSyp(invoice[1].toLong(), invoiceRate)
            var paidSyp = 0L
            db.rawQuery(
                """SELECT amount_minor,currency_code FROM invoice_payments
                   WHERE invoice_type='PURCHASE' AND invoice_id=?""".trimIndent(),
                arrayOf(invoiceId)
            ).use { c ->
                while (c.moveToNext()) {
                    val priorAmount = c.getLong(0)
                    val equivalent = when (c.getString(1)) {
                        "USD" -> CurrencyConverter.usdCentsToSyp(priorAmount, invoiceRate)
                        "SYP" -> priorAmount
                        else -> throw IllegalArgumentException("عملة دفعة سابقة غير مدعومة")
                    }
                    paidSyp = Math.addExact(paidSyp, equivalent)
                }
            }
            val supplierCredit = db.rawQuery(
                """SELECT COALESCE(SUM(supplier_credit_reduction_syp_minor),0)
                   FROM returns WHERE original_invoice_type='PURCHASE'
                     AND original_invoice_id=? AND status='POSTED'""".trimIndent(),
                arrayOf(invoiceId)
            ).use { c -> c.moveToFirst(); c.getLong(0) }
            val outstandingSyp = (totalSyp - paidSyp - supplierCredit).coerceAtLeast(0L)
            require(outstandingSyp > 0L && calculatedSypEquivalent <= outstandingSyp) {
                "مبلغ دفعة المورد يتجاوز المتبقي السوري الثابت على الفاتورة"
            }

            val openingBalance = db.rawQuery(
                "SELECT opening_balance_minor FROM cash_accounts WHERE id=? AND is_active=1",
                arrayOf(mappedAccountId)
            ).use { c -> require(c.moveToFirst()); c.getLong(0) }
            val movement = db.rawQuery(
                """SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_minor
                           WHEN direction='OUT' THEN -amount_minor ELSE 0 END),0)
                   FROM cash_transactions WHERE account_id=?""".trimIndent(),
                arrayOf(mappedAccountId)
            ).use { c -> c.moveToFirst(); c.getLong(0) }
            val availableBalance = Math.addExact(openingBalance, movement)
            require(availableBalance >= amount) {
                "رصيد الحساب الأساسي غير كافٍ لاعتماد دفعة المورد"
            }

            if (remoteAccountType == "CASH_BOX" && currency == "SYP") {
                val rollover = db.rawQuery(
                    "SELECT allocated_minor FROM daily_supplier_rollovers WHERE operating_date=? AND account_id=?",
                    arrayOf(operatingDate, mappedAccountId)
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                require(rollover != null) {
                    "لا يوجد تخصيص نقد مدور لهذا الصندوق في تاريخ دفعة المورد الأصلية"
                }
                val used = db.rawQuery(
                    """SELECT COALESCE(SUM(amount_minor),0) FROM cash_transactions
                       WHERE account_id=? AND direction='OUT'
                         AND transaction_type IN ('PURCHASE_PAYMENT','PERSONAL_WITHDRAWAL')
                         AND COALESCE(operating_date,substr(created_at,1,10))=?""".trimIndent(),
                    arrayOf(mappedAccountId, operatingDate)
                ).use { c -> c.moveToFirst(); c.getLong(0) }
                require(rollover - used >= amount) {
                    "المبلغ المتاح من النقد المدور لا يكفي لاعتماد دفعة المورد الواردة"
                }
            }

            val primaryPaymentId = "sync-payment-$operationId"
            val primaryCashId = "sync-cash-$operationId"
            val description = note ?: "دفعة للمورد عن الفاتورة " + invoice[3]
            db.insertOrThrow("cash_transactions", null, ContentValues().apply {
                put("id", primaryCashId)
                put("account_id", mappedAccountId)
                put("direction", "OUT")
                put("amount_minor", amount)
                put("currency_code", currency)
                if (paymentRate > 0L) put("exchange_rate_syp_per_usd_milli", paymentRate)
                else putNull("exchange_rate_syp_per_usd_milli")
                put("transaction_type", "PURCHASE_PAYMENT")
                put("source_type", "PURCHASE_INVOICE")
                put("source_id", invoiceId)
                put("operation_id", operationId)
                put("description", description)
                put("created_by", actorUserId)
                put("operating_date", operatingDate)
                put("created_at", occurredAt)
            })
            db.insertOrThrow("invoice_payments", null, ContentValues().apply {
                put("id", primaryPaymentId)
                put("invoice_type", "PURCHASE")
                put("invoice_id", invoiceId)
                put("cash_transaction_id", primaryCashId)
                put("amount_minor", amount)
                put("currency_code", currency)
                if (paymentRate > 0L) put("exchange_rate_syp_per_usd_milli", paymentRate)
                else putNull("exchange_rate_syp_per_usd_milli")
                put("created_at", occurredAt)
            })

            val newPaidSyp = Math.addExact(paidSyp, calculatedSypEquivalent)
            val newOutstandingSyp = (totalSyp - supplierCredit - newPaidSyp).coerceAtLeast(0L)
            val newStatus = if (newOutstandingSyp == 0L) "PAID" else "PARTIALLY_PAID"
            db.update("purchase_invoices", ContentValues().apply {
                put("status", newStatus)
                put("updated_at", timestamp())
            }, "id=? AND status IN ('POSTED','PARTIALLY_PAID')", arrayOf(invoiceId)).also {
                require(it == 1) { "تعذر تحديث حالة فاتورة الشراء بعد اعتماد دفعة المورد" }
            }

            db.insertOrThrow("operation_registry", null, ContentValues().apply {
                put("operation_id", operationId)
                put("operation_type", "PURCHASE_PAYMENT")
                put("payload_hash", command[6])
                put("status", "COMPLETED")
                put("result_reference", primaryPaymentId)
                put("created_at", timestamp())
                put("completed_at", timestamp())
            })
            db.update("sync_inbox", ContentValues().apply {
                put("status", "APPLIED")
                put("applied_at", timestamp())
                putNull("rejection_reason")
            }, "operation_id=? AND status='RECEIVED'", arrayOf(operationId)).also {
                require(it == 1) { "تعذر تثبيت حالة اعتماد دفعة المورد" }
            }
            db.setTransactionSuccessful()
            ApplyResult(operationId, true)
        } finally {
            db.endTransaction()
        }
    }

    fun applyReceivedCustomerCollection(operationId: String): ApplyResult = synchronized(APPLY_LOCK) {
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
            "تطبيق التحصيل الوارد مسموح على الجهاز الأساسي المعتمد فقط"
        }
        db.beginTransaction()
        try {
            val row = db.rawQuery(
                """SELECT i.origin_device_id,i.entity_type,i.entity_id,i.payload_json,i.status,
                          m.primary_user_id,i.origin_created_at,i.payload_sha256
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
                require(c.moveToFirst()) { "أمر التحصيل غير موجود أو الجهاز/المستخدم غير مفعل أو غير مربوط" }
                InboxCommand(c.getString(0),c.getString(1),c.getString(2),c.getString(3),
                    c.getString(4),c.getString(5),c.getString(6)) to c.getString(7)
            }
            val command = row.first
            val payloadHash = row.second
            if (command.status == "APPLIED") {
                db.setTransactionSuccessful()
                return@synchronized ApplyResult(operationId, true)
            }
            require(command.status == "RECEIVED") { "حالة أمر التحصيل لا تسمح بالتطبيق: ${command.status}" }
            require(command.entityType == "CUSTOMER_PAYMENT") { "نوع كيان التحصيل الوارد غير صالح" }
            val payload = JSONObject(command.payloadJson)
            require(payload.optString("business_operation") == "COLLECT_CUSTOMER_PAYMENT") {
                "نوع العملية الواردة ليس تحصيلاً لذمم الزبائن"
            }
            PermissionPolicy.require(db, command.primaryUserId, PermissionPolicy.CUSTOMERS_COLLECT)

            val invoiceId = payload.getString("invoice_id")
            val paymentId = payload.getString("payment_id")
            require(invoiceId.isNotBlank() && paymentId.isNotBlank() && command.entityId == paymentId) {
                "معرّف دفعة التحصيل لا يطابق كيان الظرف"
            }
            val existing = db.rawQuery(
                "SELECT operation_type,payload_hash,result_reference FROM operation_registry WHERE operation_id=?",
                arrayOf(operationId)
            ).use { c ->
                if (c.moveToFirst()) Triple(c.getString(0),c.getString(1),if(c.isNull(2)) null else c.getString(2))
                else null
            }
            if (existing != null) {
                require(existing.first == "CUSTOMER_CREDIT_COLLECTION" && existing.second == payloadHash &&
                    existing.third != null) { "تعارض في سجل عملية التحصيل أو إعادة استخدام معرّفها" }
                db.update("sync_inbox", ContentValues().apply {
                    put("status","APPLIED"); put("applied_at",timestamp()); putNull("rejection_reason")
                }, "operation_id=? AND status='RECEIVED'", arrayOf(operationId))
                db.setTransactionSuccessful()
                return@synchronized ApplyResult(operationId, true)
            }

            val invoice = db.rawQuery(
                """SELECT status,sale_type,total_usd_cents,customer_id,invoice_number,
                          exchange_rate_syp_per_usd_milli
                   FROM sales_invoices WHERE id=?""".trimIndent(), arrayOf(invoiceId)
            ).use { c ->
                require(c.moveToFirst()) { "فاتورة الدين غير موجودة على الجهاز الأساسي" }
                arrayOf(c.getString(0),c.getString(1),c.getLong(2).toString(),
                    if(c.isNull(3)) "" else c.getString(3),c.getString(4),c.getLong(5).toString())
            }
            require(invoice[0] == "POSTED" || invoice[0] == "PARTIALLY_PAID") {
                "الفاتورة ليست ديناً معتمداً قابلاً للتحصيل"
            }
            require(invoice[1] == "CREDIT" && invoice[3].isNotBlank()) {
                "التحصيل الوارد لا يرتبط بفاتورة آجلة وزبون صالح"
            }
            val totalUsd = invoice[2].toLong()
            val originalRate = invoice[5].toLong()
            require(originalRate > 0L) { "الفاتورة الأصلية لا تحتوي سعر صرف موثوقاً" }

            val remoteAccountId = payload.getString("cash_account_id")
            val remoteType = payload.getString("cash_account_type")
            val remoteCurrency = payload.getString("cash_account_currency_code")
            require(remoteType in setOf("CASH_BOX","WALLET") && remoteCurrency == "SYP" &&
                payload.optString("currency_code") == "SYP") {
                "نوع أو عملة حساب التحصيل الوارد غير مدعومين"
            }
            val mappedAccountId = db.rawQuery(
                """SELECT m.primary_cash_account_id,a.account_type,a.currency_code
                   FROM sync_cash_account_mappings m
                   JOIN cash_accounts a ON a.id=m.primary_cash_account_id
                   WHERE m.device_id=? AND m.remote_cash_account_id=? AND m.is_active=1
                     AND a.is_active=1""".trimIndent(),
                arrayOf(command.originDeviceId,remoteAccountId)
            ).use { c ->
                require(c.moveToFirst()) { "حساب قبض الجهاز الثانوي غير مربوط أو موقوف" }
                require(c.getString(1) == remoteType && c.getString(2) == remoteCurrency) {
                    "نوع أو عملة حساب القبض المرتبط لا يطابق الحساب الأصلي"
                }
                c.getString(0)
            }

            val occurredAt = payload.getString("occurred_at")
            val operatingDate = payload.getString("operating_date")
            require(occurredAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")) &&
                operatingDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                "تاريخ أو وقت حركة التحصيل الأصلي غير صالح"
            }
            val amount = payload.getLong("amount_minor")
            val collectionRate = payload.getLong("collection_rate_milli")
            val effectiveRate = payload.getLong("effective_rate_milli")
            val expectedReduction = payload.getLong("usd_reference_reduction_cents")
            require(amount > 0L && collectionRate > 0L && effectiveRate == maxOf(collectionRate,originalRate) &&
                expectedReduction > 0L) { "مبلغ أو سعر صرف التحصيل غير صالح" }
            val note = if (payload.isNull("note")) null else payload.getString("note").take(500)

            if (remoteType == "CASH_BOX") {
                val dailyDrawer = db.rawQuery(
                    "SELECT primary_cash_account_id FROM daily_cash_sessions WHERE operating_date=?",
                    arrayOf(operatingDate)
                ).use { c -> if(c.moveToFirst()) c.getString(0) else null }
                require(dailyDrawer == mappedAccountId) {
                    "حساب التحصيل النقدي المرتبط ليس درج اليوم الرئيسي لتاريخ الحركة"
                }
            }

            var paidUsd = 0L
            db.rawQuery(
                """SELECT amount_minor,currency_code,exchange_rate_syp_per_usd_milli
                   FROM invoice_payments WHERE invoice_type='SALE' AND invoice_id=?""".trimIndent(),
                arrayOf(invoiceId)
            ).use { c ->
                while(c.moveToNext()) {
                    val amountMinor=c.getLong(0)
                    val rate=if(c.isNull(2)) originalRate else c.getLong(2)
                    val usd=when(c.getString(1)) {
                        "USD" -> amountMinor
                        "SYP" -> { require(rate>0L) { "دفعة سابقة بلا سعر صرف موثوق" }; CurrencyConverter.sypToUsdCents(amountMinor,rate) }
                        else -> throw IllegalArgumentException("عملة دفعة سابقة غير مدعومة")
                    }
                    paidUsd=Math.addExact(paidUsd,usd)
                }
            }
            val creditReduction = db.rawQuery(
                """SELECT COALESCE(SUM(credit_reduction_usd_cents),0) FROM returns
                   WHERE original_invoice_type='SALE' AND original_invoice_id=? AND status='POSTED'""".trimIndent(),
                arrayOf(invoiceId)
            ).use { c -> c.moveToFirst(); c.getLong(0) }
            val outstanding = (totalUsd-paidUsd-creditReduction).coerceAtLeast(0L)
            require(outstanding>0L) { "الفاتورة مسددة بالكامل" }
            val outstandingSyp = CurrencyConverter.usdCentsToSyp(outstanding,effectiveRate)
            require(amount<=outstandingSyp) { "مبلغ التحصيل يتجاوز الرصيد المتبقي" }
            val calculatedReduction = CurrencyConverter.sypToUsdCents(amount,effectiveRate)
            require(calculatedReduction==expectedReduction && calculatedReduction<=outstanding) {
                "تخفيض الدين المرجعي لا يطابق المبلغ وسعر التحصيل الأصلي"
            }

            val primaryPaymentId = "sync-payment-$operationId"
            val primaryCashId = "sync-cash-$operationId"
            db.insertOrThrow("cash_transactions",null,ContentValues().apply {
                put("id",primaryCashId); put("account_id",mappedAccountId); put("direction","IN")
                put("amount_minor",amount); put("currency_code","SYP")
                put("exchange_rate_syp_per_usd_milli",collectionRate)
                put("transaction_type","CUSTOMER_CREDIT_COLLECTION")
                put("source_type","CUSTOMER_CREDIT_COLLECTION"); put("source_id",invoiceId)
                put("operation_id",operationId)
                put("description",note ?: "تحصيل دين الزبون عن الفاتورة " + invoice[4])
                put("created_by",command.primaryUserId); put("created_at",occurredAt)
                put("operating_date",operatingDate)
            })
            db.insertOrThrow("invoice_payments",null,ContentValues().apply {
                put("id",primaryPaymentId); put("invoice_type","SALE"); put("invoice_id",invoiceId)
                put("cash_transaction_id",primaryCashId); put("amount_minor",amount)
                put("currency_code","SYP"); put("exchange_rate_syp_per_usd_milli",effectiveRate)
                put("created_at",occurredAt)
            })
            val newOutstanding=(totalUsd-paidUsd-calculatedReduction-creditReduction).coerceAtLeast(0L)
            val newStatus=if(newOutstanding==0L) "PAID" else "PARTIALLY_PAID"
            db.update("sales_invoices",ContentValues().apply {
                put("status",newStatus); put("updated_at",timestamp())
            },"id=? AND status IN ('POSTED','PARTIALLY_PAID')",arrayOf(invoiceId)).also {
                require(it==1) { "تعذر تحديث حالة فاتورة الدين بعد التحصيل" }
            }
            db.insertOrThrow("audit_log",null,ContentValues().apply {
                put("id",UUID.randomUUID().toString()); put("user_id",command.primaryUserId)
                put("action","CUSTOMER_CREDIT_COLLECTION"); put("entity_type","CUSTOMER_CREDIT_INVOICE")
                put("entity_id",invoiceId)
                put("before_json",JSONObject().put("outstanding_usd_cents",outstanding).toString())
                put("after_json",JSONObject().put("payment_id",primaryPaymentId)
                    .put("cash_transaction_id",primaryCashId).put("amount_syp_minor",amount)
                    .put("collection_rate_milli",collectionRate).put("effective_rate_milli",effectiveRate)
                    .put("usd_reference_reduction_cents",calculatedReduction).toString())
                put("created_at",timestamp())
            })
            db.insertOrThrow("operation_registry",null,ContentValues().apply {
                put("operation_id",operationId); put("operation_type","CUSTOMER_CREDIT_COLLECTION")
                put("payload_hash",payloadHash); put("status","COMPLETED")
                put("result_reference",primaryPaymentId); put("created_at",timestamp())
                put("completed_at",timestamp())
            })
            db.update("sync_inbox",ContentValues().apply {
                put("status","APPLIED"); put("applied_at",timestamp()); putNull("rejection_reason")
            },"operation_id=? AND status='RECEIVED'",arrayOf(operationId)).also {
                require(it==1) { "تعذر تثبيت حالة تطبيق التحصيل" }
            }
            db.setTransactionSuccessful()
            ApplyResult(operationId,true)
        } finally { db.endTransaction() }
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

    private fun operatingDateForTimestamp(value: String): String =
        StoreTime.operatingDate(database.readableDatabase, value)

    private fun timestamp(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

    companion object {
        private val APPLY_LOCK = Any()
    }
}
