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


    /**
     * Central dispatcher used by the sync runtime after an authenticated inbox receipt.
     * Unsupported operations fail closed and remain unapplied for a later implementation.
     */
    fun applyReceivedOperation(operationId: String): ApplyResult {
        require(operationId.isNotBlank()) { "معرّف الأمر مطلوب" }
        val payloadJson = database.readableDatabase.rawQuery(
            "SELECT payload_json FROM sync_inbox WHERE operation_id=?",
            arrayOf(operationId)
        ).use { cursor ->
            require(cursor.moveToFirst()) { "الأمر غير موجود في صندوق الوارد" }
            cursor.getString(0)
        }
        return when (JSONObject(payloadJson).optString("business_operation")) {
            "UPDATE_STORE_TIME_ZONE" -> applyReceivedStoreTimeZone(operationId)
            "CREATE_CATEGORY" -> applyReceivedCategory(operationId)
            "SYNC_TRANSPORT_PROBE" -> applyReceivedTransportProbe(operationId)
            "SET_EXCHANGE_RATE" -> applyReceivedExchangeRate(operationId)
            "POST_PURCHASE_INVOICE", "POST_SALES_INVOICE" -> applyReceivedInvoice(operationId)
            "POST_OPERATING_EXPENSE" -> applyReceivedOperatingExpense(operationId)
            "POST_SUPPLIER_PAYMENT" -> applyReceivedSupplierPayment(operationId)
            "COLLECT_CUSTOMER_PAYMENT" -> applyReceivedCustomerCollection(operationId)
            "POST_SALES_RETURN" -> applyReceivedSalesReturn(operationId)
            "POST_PURCHASE_RETURN" -> applyReceivedPurchaseReturn(operationId)
            "POST_INVENTORY_ADJUSTMENT" -> applyReceivedInventoryAdjustment(operationId)
            "EXECUTE_SERVICE_TRANSFER" -> applyReceivedServiceTransfer(operationId)
            "SET_SUPPLIER_ROLLOVER" -> applyReceivedSupplierRollover(operationId)
            else -> throw IllegalArgumentException(
                "نوع العملية الواردة غير مدعوم بعد؛ بقي الأمر محفوظاً دون تطبيق"
            )
        }
    }

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

    /** Applies an authoritative exchange-rate snapshot from the primary on a secondary. */
    fun applyReceivedExchangeRate(operationId: String): ApplyResult = synchronized(APPLY_LOCK) {
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
            "تطبيق سعر الصرف الوارد مسموح على جهاز ثانوي مرتبط فقط"
        }
        db.beginTransaction()
        try {
            val row = db.rawQuery(
                """SELECT origin_device_id,operation_type,entity_type,entity_id,payload_json,status,
                          sequence_number,origin_created_at
                   FROM sync_inbox WHERE operation_id=?""".trimIndent(),
                arrayOf(operationId)
            ).use { c ->
                require(c.moveToFirst()) { "سعر الصرف غير موجود في صندوق الوارد" }
                Array(8) { i -> c.getString(i) }
            }
            require(row[0] == authority && row[1] == "CREATE" && row[2] == "EXCHANGE_RATE") {
                "مصدر أو نوع سعر الصرف الوارد غير صالح"
            }
            if (row[5] == "APPLIED") {
                db.setTransactionSuccessful()
                return@synchronized ApplyResult(operationId, true)
            }
            require(row[5] == "RECEIVED") { "حالة سعر الصرف لا تسمح بالتطبيق" }
            val currentSequence = row[6].toLong()
            val earlierPending = db.rawQuery(
                """SELECT COUNT(*) FROM sync_inbox
                   WHERE entity_type='EXCHANGE_RATE' AND status='RECEIVED' AND sequence_number<?""".trimIndent(),
                arrayOf(currentSequence.toString())
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            require(earlierPending == 0) { "يجب تطبيق أسعار الصرف حسب ترتيب صدورها من الجهاز الأساسي" }

            val payload = JSONObject(row[4])
            require(payload.optString("business_operation") == "SET_EXCHANGE_RATE" &&
                payload.optString("id") == row[3] && payload.optString("currency_code") == "USD") {
                "بيانات سعر الصرف الواردة لا تطابق الكيان"
            }
            val rate = payload.optLong("rate_syp_per_usd_milli", 0L)
            val effectiveAt = payload.optString("effective_at")
            val createdAt = payload.optString("created_at").takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")) }
                ?: row[7]
            require(rate > 0L && effectiveAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")) &&
                createdAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*"))) {
                "قيمة أو تاريخ سعر الصرف الوارد غير صالح"
            }
            val existing = db.rawQuery(
                "SELECT currency_code,rate_syp_per_usd_milli,effective_at,created_at FROM exchange_rates WHERE id=?",
                arrayOf(row[3])
            ).use { c ->
                if (!c.moveToFirst()) null else arrayOf(c.getString(0), c.getLong(1).toString(), c.getString(2), c.getString(3))
            }
            if (existing == null) {
                db.insertOrThrow("exchange_rates", null, ContentValues().apply {
                    put("id", row[3])
                    put("currency_code", "USD")
                    put("rate_syp_per_usd_milli", rate)
                    put("effective_at", effectiveAt)
                    putNull("created_by")
                    put("created_at", createdAt)
                })
            } else {
                require(existing[0] == "USD" && existing[1] == rate.toString() &&
                    existing[2] == effectiveAt && existing[3] == createdAt) {
                    "معرّف سعر الصرف موجود مسبقاً ببيانات مختلفة"
                }
            }
            db.update("sync_inbox", ContentValues().apply {
                put("status", "APPLIED")
                put("applied_at", timestamp())
                putNull("rejection_reason")
            }, "operation_id=? AND status='RECEIVED'", arrayOf(operationId)).also {
                require(it == 1) { "تعذر تثبيت تطبيق سعر الصرف" }
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
    /** Applies a secondary operating expense and its cash outflow atomically on the primary. */
    fun applyReceivedOperatingExpense(operationId: String): ApplyResult = synchronized(APPLY_LOCK) {
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
            "اعتماد المصروفات الواردة مسموح على الجهاز الأساسي المعتمد فقط"
        }

        db.beginTransaction()
        try {
            val command = db.rawQuery(
                """SELECT i.origin_device_id,i.entity_type,i.entity_id,i.payload_json,i.status,
                          m.primary_user_id,i.payload_sha256,i.origin_created_at
                   FROM sync_inbox i
                   JOIN sync_actor_mappings m ON m.device_id=i.origin_device_id AND m.is_active=1
                   JOIN users u ON u.id=m.primary_user_id
                   JOIN sync_devices d ON d.device_id=i.origin_device_id
                   WHERE i.operation_id=? AND d.device_role='SECONDARY'
                     AND d.status='ACTIVE' AND u.is_active=1 AND u.must_change_password=0
                   ORDER BY m.mapped_at DESC LIMIT 1""".trimIndent(),
                arrayOf(operationId)
            ).use { c ->
                require(c.moveToFirst()) { "أمر المصروف غير موجود أو الجهاز/المستخدم غير مفعل أو غير مربوط" }
                arrayOf(c.getString(0),c.getString(1),c.getString(2),c.getString(3),
                    c.getString(4),c.getString(5),c.getString(6),c.getString(7))
            }
            if (command[4] == "APPLIED") {
                db.setTransactionSuccessful()
                return@synchronized ApplyResult(operationId, true)
            }
            require(command[4] == "RECEIVED") { "حالة أمر المصروف لا تسمح بالتطبيق" }
            require(command[1] == "OPERATING_EXPENSE") { "نوع كيان المصروف الوارد غير صالح" }
            val payload = JSONObject(command[3])
            require(payload.optString("business_operation") == "POST_OPERATING_EXPENSE") {
                "نوع العملية الواردة ليس مصروفاً تشغيلياً"
            }
            val actorUserId = command[5]
            PermissionPolicy.require(db, actorUserId, PermissionPolicy.EXPENSES_MANAGE)

            val expenseId = payload.getString("expense_id")
            require(expenseId.isNotBlank() && command[2] == expenseId) {
                "معرّف المصروف لا يطابق كيان الظرف"
            }
            val existing = db.rawQuery(
                "SELECT operation_type,payload_hash,result_reference FROM operation_registry WHERE operation_id=?",
                arrayOf(operationId)
            ).use { c ->
                if (c.moveToFirst()) arrayOf(c.getString(0),c.getString(1),
                    if (c.isNull(2)) "" else c.getString(2)) else null
            }
            if (existing != null) {
                require(existing[0] == "POST_OPERATING_EXPENSE" &&
                    existing[1] == command[6] && existing[2].isNotBlank()) {
                    "تعارض في سجل المصروف أو إعادة استخدام معرّف العملية"
                }
                db.update("sync_inbox", ContentValues().apply {
                    put("status","APPLIED"); put("applied_at",timestamp()); putNull("rejection_reason")
                }, "operation_id=? AND status='RECEIVED'", arrayOf(operationId))
                db.setTransactionSuccessful()
                return@synchronized ApplyResult(operationId, true)
            }

            val categoryId = payload.getString("category_id")
            require(db.rawQuery(
                "SELECT 1 FROM expense_categories WHERE id=? AND is_active=1",
                arrayOf(categoryId)
            ).use { it.moveToFirst() }) { "تصنيف المصروف غير موجود أو موقوف على الجهاز الأساسي" }

            val remoteAccountId = payload.getString("cash_account_id")
            val remoteType = payload.getString("cash_account_type")
            val remoteCurrency = payload.getString("cash_account_currency_code")
            val currency = payload.getString("currency_code")
            require(remoteType in setOf("CASH_BOX","WALLET") &&
                remoteCurrency in setOf("USD","SYP") && currency == remoteCurrency) {
                "نوع أو عملة حساب المصروف الوارد غير مدعومين"
            }
            val mappedAccount = db.rawQuery(
                """SELECT m.primary_cash_account_id,a.account_type,a.currency_code,a.opening_balance_minor
                   FROM sync_cash_account_mappings m JOIN cash_accounts a
                     ON a.id=m.primary_cash_account_id
                   WHERE m.device_id=? AND m.remote_cash_account_id=? AND m.is_active=1
                     AND a.is_active=1""".trimIndent(),
                arrayOf(command[0],remoteAccountId)
            ).use { c ->
                require(c.moveToFirst()) { "حساب المصروف على الجهاز الثانوي غير مربوط أو موقوف" }
                require(c.getString(1)==remoteType && c.getString(2)==remoteCurrency) {
                    "نوع أو عملة حساب المصروف المرتبط لا يطابق الحساب الأصلي"
                }
                c.getString(0) to c.getLong(3)
            }
            require(!db.rawQuery(
                "SELECT 1 FROM system_cash_account_roles WHERE role_key='SUPPLIER_ROLLOVER' AND account_id=?",
                arrayOf(mappedAccount.first)
            ).use { it.moveToFirst() }) { "لا يمكن تسجيل مصروف من مخصص دفعات الموردين اليومي" }

            val amount = payload.getLong("amount_minor")
            val rate = payload.optLong("exchange_rate_syp_per_usd_milli",0L)
            val amountUsd = payload.getLong("amount_usd_cents")
            require(amount>0L && rate>=0L && amountUsd>=0L) { "مبلغ أو سعر صرف المصروف غير صالح" }
            if (currency=="SYP") require(rate>0L) { "مصروف الليرة لا يحتوي سعر صرف محفوظاً صالحاً" }
            val calculatedUsd = if(currency=="USD") amount else CurrencyConverter.sypToUsdCents(amount,rate)
            require(calculatedUsd==amountUsd) { "القيمة الدولارية للمصروف لا تطابق سعر الصرف المسجل" }

            val expenseDate = payload.getString("expense_date")
            val operatingDate = payload.getString("operating_date")
            val occurredAt = payload.optString("occurred_at").ifBlank { command[7] }
            require(expenseDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                operatingDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                occurredAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*"))) {
                "تاريخ أو وقت المصروف الأصلي غير صالح"
            }
            val reference = if(payload.isNull("reference")) null else payload.getString("reference").take(200)
            val notes = if(payload.isNull("notes")) null else payload.getString("notes").take(1000)
            val opening = mappedAccount.second
            val movements = db.rawQuery(
                """SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_minor
                           WHEN direction='OUT' THEN -amount_minor ELSE 0 END),0)
                   FROM cash_transactions WHERE account_id=?""".trimIndent(),
                arrayOf(mappedAccount.first)
            ).use { c -> c.moveToFirst(); c.getLong(0) }
            require(Math.addExact(opening,movements)>=amount) {
                "رصيد الحساب الأساسي غير كافٍ لاعتماد المصروف الوارد"
            }

            val primaryExpenseId = "sync-expense-$operationId"
            val primaryCashId = "sync-cash-$operationId"
            val description = "مصروف تشغيلي: " + (reference ?: notes ?: categoryId)
            db.insertOrThrow("cash_transactions",null,ContentValues().apply {
                put("id",primaryCashId); put("account_id",mappedAccount.first); put("direction","OUT")
                put("amount_minor",amount); put("currency_code",currency)
                if(rate>0L) put("exchange_rate_syp_per_usd_milli",rate)
                else putNull("exchange_rate_syp_per_usd_milli")
                put("transaction_type","OPERATING_EXPENSE"); put("source_type","OPERATING_EXPENSE")
                put("source_id",primaryExpenseId); put("operation_id",operationId+":CASH")
                put("description",description); put("created_by",actorUserId)
                put("created_at",occurredAt); put("operating_date",operatingDate)
            })
            db.insertOrThrow("operating_expenses",null,ContentValues().apply {
                put("id",primaryExpenseId); put("category_id",categoryId)
                put("cash_account_id",mappedAccount.first); put("cash_transaction_id",primaryCashId)
                put("amount_minor",amount); put("currency_code",currency)
                if(rate>0L) put("exchange_rate_syp_per_usd_milli",rate)
                else putNull("exchange_rate_syp_per_usd_milli")
                put("amount_usd_cents",amountUsd); put("expense_date",expenseDate)
                if(reference==null) putNull("reference") else put("reference",reference)
                if(notes==null) putNull("notes") else put("notes",notes)
                put("created_by",actorUserId); put("operation_id",operationId)
                put("created_at",occurredAt)
            })
            db.insertOrThrow("operation_registry",null,ContentValues().apply {
                put("operation_id",operationId); put("operation_type","POST_OPERATING_EXPENSE")
                put("payload_hash",command[6]); put("status","COMPLETED")
                put("result_reference",primaryExpenseId); put("created_at",timestamp())
                put("completed_at",timestamp())
            })
            db.insertOrThrow("audit_log",null,ContentValues().apply {
                put("id",UUID.randomUUID().toString()); put("user_id",actorUserId)
                put("action","OPERATING_EXPENSE_POSTED"); put("entity_type","OPERATING_EXPENSE")
                put("entity_id",primaryExpenseId)
                put("after_json",JSONObject().put("category_id",categoryId)
                    .put("cash_account_id",mappedAccount.first).put("amount_minor",amount)
                    .put("currency_code",currency).put("amount_usd_cents",amountUsd)
                    .put("expense_date",expenseDate).put("reference",reference).put("notes",notes).toString())
                put("created_at",timestamp())
            })
            db.update("sync_inbox",ContentValues().apply {
                put("status","APPLIED"); put("applied_at",timestamp()); putNull("rejection_reason")
            },"operation_id=? AND status='RECEIVED'",arrayOf(operationId)).also {
                require(it==1) { "تعذر تثبيت حالة اعتماد المصروف الوارد" }
            }
            db.setTransactionSuccessful()
            ApplyResult(operationId,true)
        } finally {
            db.endTransaction()
        }
    }

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



    /** Applies a secondary's daily supplier-cash allocation on the primary only. */
    fun applyReceivedSupplierRollover(operationId: String): ApplyResult = synchronized(APPLY_LOCK) {
        require(operationId.isNotBlank()) { "معرّف العملية مطلوب" }
        val db = database.writableDatabase
        val localId = SyncDeviceIdentity.getOrCreate(appContext)
        val role = db.rawQuery("SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'", null).use { if(it.moveToFirst()) it.getString(0) else null }
        val authority = db.rawQuery("SELECT state_value FROM sync_protocol_state WHERE state_key='authority_device_id'", null).use { if(it.moveToFirst()) it.getString(0) else null }
        require(role == "PRIMARY" && authority == localId) { "اعتماد النقد المدور الوارد مسموح على الجهاز الأساسي فقط" }
        db.beginTransaction()
        try {
            val row = db.rawQuery(
                """SELECT i.origin_device_id,i.entity_type,i.entity_id,i.payload_json,i.status,m.primary_user_id,i.payload_sha256
                   FROM sync_inbox i JOIN sync_actor_mappings m ON m.device_id=i.origin_device_id AND m.is_active=1
                   JOIN users u ON u.id=m.primary_user_id JOIN sync_devices d ON d.device_id=i.origin_device_id
                   WHERE i.operation_id=? AND d.device_role='SECONDARY' AND d.status='ACTIVE'
                     AND u.is_active=1 AND u.must_change_password=0 ORDER BY m.mapped_at DESC LIMIT 1""".trimIndent(),
                arrayOf(operationId)
            ).use { c ->
                require(c.moveToFirst()) { "أمر تخصيص النقد المدور غير موجود أو الجهاز/المستخدم غير مفعل أو غير مربوط" }
                Array(7) { i -> c.getString(i) }
            }
            if (row[4] == "APPLIED") { db.setTransactionSuccessful(); return@synchronized ApplyResult(operationId, true) }
            require(row[4] == "RECEIVED" && row[1] == "DAILY_SUPPLIER_ROLLOVER") { "نوع أو حالة أمر النقد المدور غير صالح" }
            val payload = JSONObject(row[3]!!)
            require(payload.optString("business_operation") == "SET_SUPPLIER_ROLLOVER") { "نوع العملية الواردة غير صالح" }
            val actor = row[5]!!
            PermissionPolicy.require(db, actor, PermissionPolicy.DAILY_CASH_MANAGE)
            val existing = db.rawQuery("SELECT operation_type,payload_hash,result_reference FROM operation_registry WHERE operation_id=?", arrayOf(operationId)).use {
                if(it.moveToFirst()) arrayOf(it.getString(0),it.getString(1),if(it.isNull(2)) "" else it.getString(2)) else null
            }
            if(existing != null) {
                require(existing[0] == "SET_SUPPLIER_ROLLOVER" && existing[1] == row[6] && existing[2].isNotBlank()) { "تعارض في سجل النقد المدور" }
                db.update("sync_inbox", ContentValues().apply { put("status","APPLIED"); put("applied_at",timestamp()); putNull("rejection_reason") }, "operation_id=? AND status='RECEIVED'", arrayOf(operationId))
                db.setTransactionSuccessful()
                return@synchronized ApplyResult(operationId, true)
            }
            val remoteAccount = payload.getString("cash_account_id")
            require(payload.getString("cash_account_type") == "CASH_BOX" && payload.getString("cash_account_currency_code") == "SYP") { "حساب النقد المدور يجب أن يكون صندوقاً بالليرة السورية" }
            val mappedAccount = db.rawQuery(
                """SELECT m.primary_cash_account_id,a.account_type,a.currency_code,a.is_active
                   FROM sync_cash_account_mappings m JOIN cash_accounts a ON a.id=m.primary_cash_account_id
                   WHERE m.device_id=? AND m.remote_cash_account_id=? AND m.is_active=1""".trimIndent(),
                arrayOf(row[0], remoteAccount)
            ).use { c ->
                require(c.moveToFirst() && c.getInt(3)==1) { "صندوق الجهاز الثانوي غير مربوط أو موقوف" }
                require(c.getString(1)=="CASH_BOX" && c.getString(2)=="SYP") { "الصندوق المرتبط لا يطابق نوع أو عملة الصندوق الأصلي" }
                c.getString(0)
            }
            val occurredAt = payload.getString("occurred_at")
            val operatingDate = payload.getString("operating_date")
            require(occurredAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")) && operatingDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "تاريخ تخصيص النقد المدور غير صالح" }
            val amount = payload.getLong("amount_minor")
            require(amount >= 0L) { "مبلغ النقد المدور غير صالح" }
            val note = if(payload.isNull("note")) null else payload.getString("note").take(500)
            DailyBalanceRepository(appContext).setSupplierRollover(actor,mappedAccount,amount,note,operationId,occurredAt,operatingDate,true)
            db.insertOrThrow("operation_registry", null, ContentValues().apply {
                put("operation_id",operationId); put("operation_type","SET_SUPPLIER_ROLLOVER"); put("payload_hash",row[6])
                put("status","COMPLETED"); put("result_reference",operatingDate); put("created_at",timestamp()); put("completed_at",timestamp())
            })
            db.update("sync_inbox", ContentValues().apply { put("status","APPLIED"); put("applied_at",timestamp()); putNull("rejection_reason") },
                "operation_id=? AND status='RECEIVED'", arrayOf(operationId)).also { require(it==1) { "تعذر تثبيت حالة تخصيص النقد المدور" } }
            db.setTransactionSuccessful()
            ApplyResult(operationId,true)
        } finally { db.endTransaction() }
    }

    /** Applies an authenticated service-wallet transfer on the primary exactly once. */
    fun applyReceivedServiceTransfer(operationId: String): ApplyResult = synchronized(APPLY_LOCK) {
        require(operationId.isNotBlank()) { "معرّف التحويل مطلوب" }
        val db = database.writableDatabase
        val localId = SyncDeviceIdentity.getOrCreate(appContext)
        val role = db.rawQuery("SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'", null).use { if (it.moveToFirst()) it.getString(0) else null }
        val authority = db.rawQuery("SELECT state_value FROM sync_protocol_state WHERE state_key='authority_device_id'", null).use { if (it.moveToFirst()) it.getString(0) else null }
        require(role == "PRIMARY" && authority == localId) { "اعتماد تحويل الرصيد الوارد مسموح على الجهاز الأساسي فقط" }
        db.beginTransaction()
        try {
            val row = db.rawQuery(
                """SELECT i.origin_device_id,i.entity_type,i.entity_id,i.payload_json,i.status,m.primary_user_id,i.payload_sha256
                   FROM sync_inbox i JOIN sync_actor_mappings m ON m.device_id=i.origin_device_id AND m.is_active=1
                   JOIN users u ON u.id=m.primary_user_id JOIN sync_devices d ON d.device_id=i.origin_device_id
                   WHERE i.operation_id=? AND d.device_role='SECONDARY' AND d.status='ACTIVE'
                     AND u.is_active=1 AND u.must_change_password=0 ORDER BY m.mapped_at DESC LIMIT 1""".trimIndent(),
                arrayOf(operationId)
            ).use { c -> require(c.moveToFirst()) { "أمر تحويل الرصيد غير موجود أو الجهاز/المستخدم غير مفعل أو غير مربوط" }; Array(7) { i -> c.getString(i) } }
            if (row[4] == "APPLIED") { db.setTransactionSuccessful(); return@synchronized ApplyResult(operationId, true) }
            require(row[4] == "RECEIVED" && row[1] == "SERVICE_TRANSFER") { "حالة أو نوع أمر تحويل الرصيد غير صالح" }
            val payload = JSONObject(row[3]!!)
            require(payload.optString("business_operation") == "EXECUTE_SERVICE_TRANSFER") { "نوع العملية الواردة ليس تحويلاً للخدمات" }
            val actor = row[5]!!
            PermissionPolicy.require(db, actor, PermissionPolicy.SERVICE_TRANSFERS_EXECUTE)
            val existing = db.rawQuery("SELECT operation_type,payload_hash,result_reference FROM operation_registry WHERE operation_id=?",arrayOf(operationId))
                .use { c -> if (c.moveToFirst()) arrayOf(c.getString(0),c.getString(1),if(c.isNull(2)) "" else c.getString(2)) else null }
            if (existing != null) {
                require(existing[0] == "EXECUTE_SERVICE_TRANSFER" && existing[1] == row[6] && existing[2].isNotBlank()) { "تعارض في سجل تحويل الرصيد أو إعادة استخدام معرّف العملية" }
                db.update("sync_inbox",ContentValues().apply{put("status","APPLIED");put("applied_at",timestamp());putNull("rejection_reason")},"operation_id=? AND status='RECEIVED'",arrayOf(operationId))
                db.setTransactionSuccessful()
                return@synchronized ApplyResult(operationId,true)
            }
            val remoteAccount=payload.getString("cash_account_id")
            val remoteType=payload.getString("cash_account_type")
            val remoteCurrency=payload.getString("cash_account_currency_code")
            require(remoteType in setOf("CASH_BOX","WALLET") && remoteCurrency=="SYP") { "نوع أو عملة حساب قبض التحويل غير مدعومين" }
            val mappedAccount=db.rawQuery(
                """SELECT m.primary_cash_account_id,a.account_type,a.currency_code,a.is_active
                   FROM sync_cash_account_mappings m JOIN cash_accounts a ON a.id=m.primary_cash_account_id
                   WHERE m.device_id=? AND m.remote_cash_account_id=? AND m.is_active=1""".trimIndent(),arrayOf(row[0],remoteAccount)
            ).use { c -> require(c.moveToFirst() && c.getInt(3)==1) { "حساب قبض الجهاز الثانوي غير مربوط أو موقوف" }
                require(c.getString(1)==remoteType && c.getString(2)==remoteCurrency) { "نوع أو عملة حساب القبض المرتبط لا يطابق الحساب الأصلي" }; c.getString(0) }
            val occurredAt=payload.getString("occurred_at")
            val operatingDate=payload.getString("operating_date")
            require(occurredAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")) && operatingDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "تاريخ أو وقت التحويل الأصلي غير صالح" }
            val serviceType=payload.getString("service_type")
            val expectedKey=if(serviceType==ServiceTransferRepository.SHAM_CASH_TRANSFER) ServiceTransferRepository.SHAM_CASH else ServiceTransferRepository.SYRIATEL_SHARED
            require(payload.getString("service_key")==expectedKey) { "محفظة الخدمة لا تطابق نوع التحويل" }
            val delivered=payload.getLong("delivered_minor")
            val providerFee=payload.getLong("provider_fee_minor")
            require(payload.getLong("profit_minor")>=0L && payload.getLong("customer_charge_minor")>0L) { "قيم ربح أو قبض التحويل غير صالحة" }
            val reference=if(payload.isNull("reference")) null else payload.getString("reference").take(200)
            val note=if(payload.isNull("note")) null else payload.getString("note").take(500)
            val result=ServiceTransferRepository(appContext).executeTransfer(actor,serviceType,delivered,providerFee,mappedAccount,operationId,reference,note,occurredAt,operatingDate,true)
            require(result.profitMinor==payload.getLong("profit_minor") && result.customerChargeMinor==payload.getLong("customer_charge_minor")) { "حساب الربح أو المبلغ المقبوض لا يطابق سياسة التحويل المعتمدة على الجهاز الأساسي" }
            db.insertOrThrow("operation_registry",null,ContentValues().apply{
                put("operation_id",operationId);put("operation_type","EXECUTE_SERVICE_TRANSFER");put("payload_hash",row[6]);put("status","COMPLETED")
                put("result_reference",result.transferId);put("created_at",timestamp());put("completed_at",timestamp())
            })
            db.update("sync_inbox",ContentValues().apply{put("status","APPLIED");put("applied_at",timestamp());putNull("rejection_reason")},
                "operation_id=? AND status='RECEIVED'",arrayOf(operationId)).also{require(it==1){"تعذر تثبيت حالة تطبيق تحويل الرصيد"}}
            db.setTransactionSuccessful()
            ApplyResult(operationId,true)
        } finally { db.endTransaction() }
    }

    fun applyReceivedSalesReturn(operationId: String): ApplyResult =
        applyReceivedReturn(operationId, "POST_SALES_RETURN", "SALES_RETURN")

    fun applyReceivedPurchaseReturn(operationId: String): ApplyResult =
        applyReceivedReturn(operationId, "POST_PURCHASE_RETURN", "PURCHASE_RETURN")

    private fun applyReceivedReturn(operationId: String, businessOperation: String, entityType: String): ApplyResult =
        synchronized(APPLY_LOCK) {
            require(operationId.isNotBlank()) { "معرّف الأمر مطلوب" }
            val db=database.writableDatabase
            val localId=SyncDeviceIdentity.getOrCreate(appContext)
            val role=db.rawQuery("SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'",null)
                .use { c -> if(c.moveToFirst()) c.getString(0) else null }
            val authority=db.rawQuery("SELECT state_value FROM sync_protocol_state WHERE state_key='authority_device_id'",null)
                .use { c -> if(c.moveToFirst()) c.getString(0) else null }
            require(role=="PRIMARY" && authority==localId) { "تطبيق المرتجعات الواردة مسموح على الجهاز الأساسي فقط" }
            db.beginTransaction()
            try {
                val row=db.rawQuery(
                    """SELECT i.origin_device_id,i.entity_type,i.entity_id,i.payload_json,i.status,m.primary_user_id
                       FROM sync_inbox i JOIN sync_actor_mappings m ON m.device_id=i.origin_device_id AND m.is_active=1
                       JOIN users u ON u.id=m.primary_user_id JOIN sync_devices d ON d.device_id=i.origin_device_id
                       WHERE i.operation_id=? AND d.device_role='SECONDARY' AND d.status='ACTIVE'
                         AND u.is_active=1 AND u.must_change_password=0
                       ORDER BY m.mapped_at DESC LIMIT 1""".trimIndent(),arrayOf(operationId)
                ).use { c ->
                    require(c.moveToFirst()) { "أمر المرتجع غير موجود أو الجهاز/المستخدم غير مربوط أو موقوف" }
                    arrayOf(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5))
                }
                if(row[4]=="APPLIED") { db.setTransactionSuccessful(); return@synchronized ApplyResult(operationId,true) }
                require(row[4]=="RECEIVED" && row[1]==entityType && row[2]=="pending-return-$operationId") {
                    "نوع أو حالة كيان المرتجع الوارد غير صالحة"
                }
                val payload=JSONObject(row[3])
                require(payload.optString("business_operation")==businessOperation) { "نوع عملية المرتجع الوارد غير صالح" }
                PermissionPolicy.require(db,row[5],PermissionPolicy.RETURNS_PROCESS)
                val invoiceId=payload.getString("original_invoice_id")
                val returnDate=payload.getString("return_date")
                val reason=payload.getString("reason")
                val occurredAt=payload.getString("occurred_at")
                val operatingDate=payload.getString("operating_date")
                require(invoiceId.isNotBlank() && returnDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                    occurredAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")) &&
                    operatingDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "تاريخ أو بيانات المرتجع غير صالحة" }
                val itemsJson=payload.getJSONArray("items")
                require(itemsJson.length()>0) { "المرتجع الوارد بلا أصناف" }
                val allocationsJson=payload.getJSONArray("refund_allocations")
                val mapped=mutableListOf<Pair<String,Long>>()
                for(i in 0 until allocationsJson.length()) {
                    val item=allocationsJson.getJSONObject(i)
                    val remoteId=item.getString("cash_account_id")
                    val remoteType=item.getString("cash_account_type")
                    val remoteCurrency=item.getString("cash_account_currency_code")
                    val amount=item.getLong("amount_minor")
                    require(remoteId.isNotBlank() && remoteType in setOf("CASH_BOX","WALLET") &&
                        remoteCurrency in setOf("USD","SYP") && amount>0L) { "حساب رد المبلغ غير صالح" }
                    val primaryAccount=db.rawQuery(
                        """SELECT m.primary_cash_account_id,a.account_type,a.currency_code
                           FROM sync_cash_account_mappings m JOIN cash_accounts a ON a.id=m.primary_cash_account_id
                           WHERE m.device_id=? AND m.remote_cash_account_id=? AND m.is_active=1 AND a.is_active=1""".trimIndent(),
                        arrayOf(row[0],remoteId)
                    ).use { c ->
                        require(c.moveToFirst()) { "حساب رد المبلغ غير مربوط أو موقوف" }
                        require(c.getString(1)==remoteType && c.getString(2)==remoteCurrency) {
                            "نوع أو عملة حساب رد المبلغ المرتبط غير متطابقين"
                        }
                        c.getString(0)
                    }
                    mapped.add(primaryAccount to amount)
                }
                if(businessOperation=="POST_SALES_RETURN") {
                    val inputs=(0 until itemsJson.length()).map { i ->
                        val item=itemsJson.getJSONObject(i)
                        SalesReturnItemInput(item.getString("original_sales_item_id"),BigDecimal(item.getString("quantity")))
                    }
                    SalesReturnRepository(appContext).postSalesReturn(
                        row[5],invoiceId,operationId,returnDate,reason,inputs,
                        mapped.map { SalesRefundAllocationInput(it.first,it.second) },occurredAt,true,operatingDate
                    )
                    resultReference(db,operationId,"POST_SALES_RETURN")
                } else {
                    val inputs=(0 until itemsJson.length()).map { i ->
                        val item=itemsJson.getJSONObject(i)
                        PurchaseReturnItemInput(item.getString("original_purchase_item_id"),BigDecimal(item.getString("quantity")))
                    }
                    PurchaseReturnRepository(appContext).postPurchaseReturn(
                        row[5],invoiceId,operationId,returnDate,reason,inputs,
                        mapped.map { SupplierRefundAllocationInput(it.first,it.second) },occurredAt,true,operatingDate
                    )
                    resultReference(db,operationId,"POST_PURCHASE_RETURN")
                }.also { require(!it.isNullOrBlank()) { "لم يُنشأ سجل المرتجع على الجهاز الأساسي" } }
                db.update("sync_inbox",ContentValues().apply {
                    put("status","APPLIED");put("applied_at",timestamp());putNull("rejection_reason")
                },"operation_id=? AND status='RECEIVED'",arrayOf(operationId)).also {
                    require(it==1) { "تعذر تثبيت تطبيق المرتجع الوارد" }
                }
                db.setTransactionSuccessful()
                ApplyResult(operationId,true)
            } finally { db.endTransaction() }
        }

    fun applyReceivedInventoryAdjustment(operationId: String): ApplyResult = synchronized(APPLY_LOCK) {
        require(operationId.isNotBlank()) { "معرّف الأمر مطلوب" }
        val db=database.writableDatabase
        val localId=SyncDeviceIdentity.getOrCreate(appContext)
        val role=db.rawQuery("SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'",null)
            .use { c -> if(c.moveToFirst()) c.getString(0) else null }
        val authority=db.rawQuery("SELECT state_value FROM sync_protocol_state WHERE state_key='authority_device_id'",null)
            .use { c -> if(c.moveToFirst()) c.getString(0) else null }
        require(role=="PRIMARY" && authority==localId) { "تطبيق تسوية المخزون الواردة مسموح على الجهاز الأساسي فقط" }
        db.beginTransaction()
        try {
            val row=db.rawQuery(
                """SELECT i.origin_device_id,i.entity_type,i.entity_id,i.payload_json,i.status,m.primary_user_id
                   FROM sync_inbox i JOIN sync_actor_mappings m ON m.device_id=i.origin_device_id AND m.is_active=1
                   JOIN users u ON u.id=m.primary_user_id JOIN sync_devices d ON d.device_id=i.origin_device_id
                   WHERE i.operation_id=? AND d.device_role='SECONDARY' AND d.status='ACTIVE'
                     AND u.is_active=1 AND u.must_change_password=0
                   ORDER BY m.mapped_at DESC LIMIT 1""".trimIndent(),arrayOf(operationId)
            ).use { c ->
                require(c.moveToFirst()) { "أمر تسوية المخزون غير موجود أو الجهاز/المستخدم غير مربوط" }
                arrayOf(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5))
            }
            if(row[4]=="APPLIED") { db.setTransactionSuccessful(); return@synchronized ApplyResult(operationId,true) }
            require(row[4]=="RECEIVED" && row[1]=="INVENTORY_ADJUSTMENT" &&
                row[2]=="pending-adjustment-$operationId") { "نوع أو حالة تسوية المخزون الواردة غير صالحة" }
            val payload=JSONObject(row[3])
            require(payload.optString("business_operation")=="POST_INVENTORY_ADJUSTMENT") {
                "نوع عملية تسوية المخزون الواردة غير صالح"
            }
            PermissionPolicy.require(db,row[5],PermissionPolicy.INVENTORY_ADJUST)
            val occurredAt=payload.getString("occurred_at")
            val operatingDate=payload.getString("operating_date")
            require(occurredAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")) &&
                operatingDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "تاريخ حركة التسوية غير صالح" }
            val delta=BigDecimal(payload.getString("quantity_delta"))
            require(delta.signum()!=0) { "فرق كمية التسوية لا يمكن أن يكون صفراً" }
            val cost=if(payload.isNull("unit_cost_usd_cents")) null else payload.getLong("unit_cost_usd_cents")
            InventoryAdjustmentRepository(appContext).postAdjustment(
                row[5],payload.getString("product_id"),delta,payload.getString("reason"),
                operationId,cost,payload.getString("warehouse_id"),occurredAt,true
            )
            db.update("sync_inbox",ContentValues().apply {
                put("status","APPLIED");put("applied_at",timestamp());putNull("rejection_reason")
            },"operation_id=? AND status='RECEIVED'",arrayOf(operationId)).also {
                require(it==1) { "تعذر تثبيت تطبيق تسوية المخزون" }
            }
            db.setTransactionSuccessful()
            ApplyResult(operationId,true)
        } finally { db.endTransaction() }
    }

    /** Accepts a signed transport probe on the primary without changing business ledgers. */
    /** Applies a primary-owned category create on a secondary without echoing it back. */
    fun applyReceivedCategory(operationId: String): ApplyResult = synchronized(APPLY_LOCK) {
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
            "تطبيق التصنيفات الواردة مسموح على جهاز ثانوي مرتبط فقط"
        }
        db.beginTransaction()
        try {
            val row = db.rawQuery(
                """SELECT origin_device_id,operation_type,entity_type,entity_id,payload_json,status,
                          origin_created_at
                   FROM sync_inbox WHERE operation_id=?""".trimIndent(), arrayOf(operationId)
            ).use { c ->
                require(c.moveToFirst()) { "التصنيف غير موجود في صندوق الوارد" }
                Array(7) { c.getString(it) }
            }
            require(row[0] == authority && row[1] == "CREATE" && row[2] == "CATEGORY") {
                "مصدر أو نوع التصنيف الوارد غير صالح"
            }
            if (row[5] == "APPLIED") {
                db.setTransactionSuccessful()
                return@synchronized ApplyResult(operationId, true)
            }
            require(row[5] == "RECEIVED") { "حالة التصنيف لا تسمح بالتطبيق" }
            val payload = JSONObject(row[4])
            require(payload.optString("business_operation") == "CREATE_CATEGORY") {
                "نوع عملية التصنيف الواردة غير صالح"
            }
            val name = payload.optString("name").trim()
            val payloadCreatedAt = payload.optString("created_at")
            val createdAt = payloadCreatedAt.takeIf {
                it.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*"))
            } ?: row[6]
            require(name.isNotEmpty() && name.length <= 80 &&
                row[3] == name.lowercase(java.util.Locale.ROOT) &&
                createdAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*"))) {
                "اسم التصنيف أو معرّفه أو وقت إنشائه الأصلي غير صالح"
            }
            val existing = db.rawQuery(
                "SELECT id,name FROM categories WHERE lower(trim(name))=? LIMIT 1",
                arrayOf(name.lowercase(java.util.Locale.ROOT))
            ).use { c -> if (c.moveToFirst()) c.getString(1) else null }
            if (existing == null) {
                db.insertOrThrow("categories", null, ContentValues().apply {
                    put("name", name)
                    put("created_at", createdAt)
                })
            } else {
                require(existing.equals(name, ignoreCase = true)) {
                    "اسم التصنيف موجود محلياً بصيغة متعارضة"
                }
            }
            db.update("sync_inbox", ContentValues().apply {
                put("status", "APPLIED")
                put("applied_at", timestamp())
                putNull("rejection_reason")
            }, "operation_id=? AND status='RECEIVED'", arrayOf(operationId)).also {
                require(it == 1) { "تعذر تثبيت تطبيق التصنيف" }
            }
            db.setTransactionSuccessful()
            ApplyResult(operationId, true)
        } finally { db.endTransaction() }
    }

    fun applyReceivedTransportProbe(operationId: String): ApplyResult = synchronized(APPLY_LOCK) {
        require(operationId.isNotBlank()) { "معرّف اختبار النقل مطلوب" }
        val db = database.writableDatabase
        val localId = SyncDeviceIdentity.getOrCreate(appContext)
        val role = db.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'", null
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        val authority = db.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='authority_device_id'", null
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        require(role == "PRIMARY" && authority == localId) {
            "اختبار النقل الوارد يُعتمد على الجهاز الأساسي فقط"
        }
        db.beginTransaction()
        try {
            val row = db.rawQuery(
                """SELECT i.origin_device_id,i.operation_type,i.entity_type,i.entity_id,
                          i.payload_json,i.status,d.status
                   FROM sync_inbox i JOIN sync_devices d ON d.device_id=i.origin_device_id
                   WHERE i.operation_id=?""".trimIndent(), arrayOf(operationId)
            ).use { c ->
                require(c.moveToFirst()) { "اختبار النقل غير موجود في صندوق الوارد" }
                Array(7) { c.getString(it) }
            }
            require(row[6] == "ACTIVE" && row[1] == "CREATE" &&
                row[2] == "SYNC_TRANSPORT_PROBE" && row[3] == operationId) {
                "مصدر أو نوع اختبار النقل غير صالح"
            }
            if (row[5] == "APPLIED") {
                db.setTransactionSuccessful()
                return@synchronized ApplyResult(operationId, true)
            }
            require(row[5] == "RECEIVED") { "حالة اختبار النقل لا تسمح بالتطبيق" }
            val payload = JSONObject(row[4])
            require(payload.optString("business_operation") == "SYNC_TRANSPORT_PROBE" &&
                payload.optString("probe_id") == operationId &&
                runCatching { UUID.fromString(payload.optString("nonce")) }.isSuccess &&
                payload.optString("created_at").matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*"))) {
                "محتوى اختبار النقل غير صالح"
            }
            db.update("sync_inbox", ContentValues().apply {
                put("status", "APPLIED")
                put("applied_at", timestamp())
                putNull("rejection_reason")
            }, "operation_id=? AND status='RECEIVED'", arrayOf(operationId)).also {
                require(it == 1) { "تعذر تثبيت نتيجة اختبار النقل" }
            }
            db.setTransactionSuccessful()
            ApplyResult(operationId, true)
        } finally { db.endTransaction() }
    }

    private fun resultReference(db: android.database.sqlite.SQLiteDatabase, operationId: String, type: String): String? =
        db.rawQuery("SELECT result_reference FROM operation_registry WHERE operation_id=? AND operation_type=?",
            arrayOf(operationId,type)).use { c -> if(c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

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
