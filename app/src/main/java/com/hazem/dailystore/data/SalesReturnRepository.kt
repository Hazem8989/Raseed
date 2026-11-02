package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class SalesReturnItemInput(val originalSalesItemId: String, val quantity: BigDecimal)
data class SalesRefundAllocationInput(val cashAccountId: String, val amountMinor: Long)
data class SalesReturnResult(
    val returnId: String, val returnNumber: String, val totalUsdCents: Long,
    val creditReductionUsdCents: Long, val cashRefundUsdCents: Long,
    val returnedCogsUsdCents: Long?, val replayed: Boolean
)

data class SalesReturnInvoiceOption(
    val id: String, val number: String, val date: String, val saleType: String,
    val customerName: String?, val totalUsdCents: Long
)
data class SalesReturnableItem(
    val id: String, val productName: String, val soldQuantity: BigDecimal,
    val returnedQuantity: BigDecimal, val availableQuantity: BigDecimal,
    val unitPriceUsdCents: Long, val tracksInventory: Boolean
)
data class SalesReturnPreview(
    val totalUsdCents: Long, val creditReductionUsdCents: Long,
    val cashRefundUsdCents: Long, val cashRefundSypMinor: Long
)

/** Atomic customer sales returns. Purchase returns use a separate supplier-credit workflow. */
class SalesReturnRepository(context: Context) {
    private val database = AppDatabase.getInstance(context)

    private data class OriginalInvoice(
        val id: String, val number: String, val saleType: String, val customerId: String?,
        val warehouseId: String, val status: String, val totalUsdCents: Long,
        val subtotalUsdCents: Long, val discountUsdCents: Long, val rateMilli: Long
    )
    private data class OriginalItem(
        val id: String, val productId: String, val quantity: BigDecimal,
        val unitPriceUsdCents: Long, val lineTotalUsdCents: Long,
        val tracksInventory: Boolean, val decimalPlaces: Int
    )
    private data class CostAllocationPlan(
        val sourceAllocationId: String?, val quantity: BigDecimal,
        val unitCostUsdCents: Long?, val costUsdCents: Long?
    )
    private data class ItemPlan(
        val original: OriginalItem, val quantity: BigDecimal,
        val returnValueUsdCents: Long, val returnedCogsUsdCents: Long?,
        val costAllocations: List<CostAllocationPlan>
    )

    fun listReturnableInvoices(): List<SalesReturnInvoiceOption> {
        val result = mutableListOf<SalesReturnInvoiceOption>()
        database.readableDatabase.rawQuery(
            """SELECT i.id,i.invoice_number,i.invoice_date,i.sale_type,c.name,i.total_usd_cents
               FROM sales_invoices i LEFT JOIN customers c ON c.id=i.customer_id
               WHERE i.status IN ('POSTED','PARTIALLY_PAID','PAID')
               ORDER BY i.invoice_date DESC,i.created_at DESC""".trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                if (listReturnableItems(id).any { it.availableQuantity.signum() > 0 }) {
                    result.add(SalesReturnInvoiceOption(
                        id,c.getString(1),c.getString(2),c.getString(3),
                        if (c.isNull(4)) null else c.getString(4),c.getLong(5)
                    ))
                }
            }
        }
        return result
    }

    fun listReturnableItems(invoiceId: String): List<SalesReturnableItem> {
        require(invoiceId.isNotBlank()) { "معرّف الفاتورة مطلوب" }
        val db = database.readableDatabase
        val invoice = loadOriginalInvoice(db, invoiceId)
        val originals = loadOriginalItems(db, invoice)
        return originals.map { item ->
            val returned = db.rawQuery(
                """SELECT COALESCE(SUM(ri.quantity),0)
                   FROM return_items ri JOIN returns r ON r.id=ri.return_id
                   WHERE ri.original_sales_item_id=? AND r.status='POSTED'""".trimIndent(),
                arrayOf(item.id)
            ).use { c -> c.moveToFirst(); BigDecimal.valueOf(c.getDouble(0)) }
            SalesReturnableItem(
                item.id, productName(db, item.productId), item.quantity, returned,
                item.quantity.subtract(returned).max(BigDecimal.ZERO),
                item.unitPriceUsdCents, item.tracksInventory
            )
        }
    }

    fun previewSalesReturn(invoiceId: String, items: List<SalesReturnItemInput>): SalesReturnPreview {
        require(invoiceId.isNotBlank() && items.isNotEmpty()) { "حدد الفاتورة والأصناف المرتجعة" }
        require(items.map { it.originalSalesItemId }.distinct().size == items.size) {
            "لا تكرر بند الفاتورة نفسه"
        }
        val db = database.readableDatabase
        val invoice = loadOriginalInvoice(db, invoiceId)
        require(invoice.status in setOf("POSTED", "PARTIALLY_PAID", "PAID")) {
            "لا يمكن معاينة مرتجع لفاتورة غير معتمدة"
        }
        require(invoice.rateMilli > 0L) { "الفاتورة الأصلية بلا سعر صرف محفوظ" }
        val originals = loadOriginalItems(db, invoice)
        val plans = items.map { input ->
            val original = originals.firstOrNull { it.id == input.originalSalesItemId }
                ?: throw IllegalArgumentException("أحد الأصناف لا ينتمي إلى الفاتورة الأصلية")
            buildItemPlan(db, original, input.quantity, invoice)
        }
        val total = plans.fold(0L) { sum, plan -> Math.addExact(sum, plan.returnValueUsdCents) }
        val paid = paidUsdCents(db, invoiceId)
        val priorCredit = db.rawQuery(
            """SELECT COALESCE(SUM(credit_reduction_usd_cents),0)
               FROM returns WHERE original_invoice_type='SALE'
                 AND original_invoice_id=? AND status='POSTED'""".trimIndent(),
            arrayOf(invoiceId)
        ).use { c -> c.moveToFirst(); c.getLong(0) }
        val outstanding = (invoice.totalUsdCents - paid - priorCredit).coerceAtLeast(0L)
        val credit = minOf(total, outstanding)
        val refund = Math.subtractExact(total, credit)
        return SalesReturnPreview(
            total, credit, refund, CurrencyConverter.usdCentsToSyp(refund, invoice.rateMilli)
        )
    }

    fun postSalesReturn(
        actorUserId: String, originalInvoiceId: String, operationId: String,
        returnDate: String, reason: String, items: List<SalesReturnItemInput>,
        refundAllocations: List<SalesRefundAllocationInput>
    ): SalesReturnResult {
        require(originalInvoiceId.isNotBlank() && operationId.isNotBlank()) { "بيانات المرتجع غير مكتملة" }
        require(returnDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "تاريخ المرتجع غير صالح" }
        require(reason.isNotBlank()) { "سبب المرتجع مطلوب" }
        require(items.isNotEmpty()) { "أضف صنفاً واحداً على الأقل إلى المرتجع" }
        require(items.all { it.originalSalesItemId.isNotBlank() && it.quantity.signum() > 0 }) {
            "تحقق من أصناف وكميات المرتجع"
        }
        require(items.map { it.originalSalesItemId }.distinct().size == items.size) {
            "لا تكرر بند الفاتورة نفسه؛ اجمع كميته في سطر واحد"
        }
        require(refundAllocations.all { it.cashAccountId.isNotBlank() && it.amountMinor > 0L }) {
            "توزيع رد المبلغ غير صالح"
        }
        require(refundAllocations.map { it.cashAccountId }.distinct().size == refundAllocations.size) {
            "اجمع المبالغ المخصصة للحساب نفسه في سطر واحد"
        }

        val payloadHash = hash(buildString {
            append(originalInvoiceId).append('|').append(returnDate).append('|').append(reason.trim())
            items.sortedBy { it.originalSalesItemId }.forEach {
                append('|').append(it.originalSalesItemId).append(':')
                    .append(it.quantity.stripTrailingZeros().toPlainString())
            }
            refundAllocations.sortedBy { it.cashAccountId }.forEach {
                append('|').append(it.cashAccountId).append(':').append(it.amountMinor)
            }
        })
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            PermissionPolicy.require(db, actorUserId, PermissionPolicy.RETURNS_PROCESS)
            val existing = db.rawQuery(
                "SELECT operation_type,payload_hash,result_reference FROM operation_registry WHERE operation_id=?",
                arrayOf(operationId)
            ).use { c ->
                if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getString(2))
                else null
            }
            if (existing != null) {
                require(existing.first == "POST_SALES_RETURN" && existing.second == payloadHash) {
                    "معرّف العملية مستخدم لمرتجع أو بيانات مختلفة"
                }
                val result = existing.third?.let { loadResult(db, it, true) }
                    ?: error("سجل العملية لا يحتوي مرجع المرتجع")
                db.setTransactionSuccessful()
                return result
            }

            val invoice = loadOriginalInvoice(db, originalInvoiceId)
            require(invoice.status in setOf("POSTED", "PARTIALLY_PAID", "PAID")) {
                "لا يمكن إرجاع أصناف من فاتورة غير معتمدة أو ملغاة"
            }
            require(invoice.rateMilli > 0L) {
                "الفاتورة الأصلية بلا سعر صرف محفوظ؛ لا يمكن رد قيمتها بأمان"
            }
            val originals = loadOriginalItems(db, invoice)
            val itemPlans = items.map { input ->
                val original = originals.firstOrNull { it.id == input.originalSalesItemId }
                    ?: throw IllegalArgumentException("أحد الأصناف لا ينتمي إلى الفاتورة الأصلية")
                buildItemPlan(db, original, input.quantity, invoice)
            }
            val totalReturn = itemPlans.fold(0L) { sum, plan ->
                Math.addExact(sum, plan.returnValueUsdCents)
            }
            val paidUsd = paidUsdCents(db, originalInvoiceId)
            val priorCredit = db.rawQuery(
                """SELECT COALESCE(SUM(credit_reduction_usd_cents),0)
                   FROM returns WHERE original_invoice_type='SALE'
                     AND original_invoice_id=? AND status='POSTED'""".trimIndent(),
                arrayOf(originalInvoiceId)
            ).use { c -> c.moveToFirst(); c.getLong(0) }
            val outstanding = (invoice.totalUsdCents - paidUsd - priorCredit).coerceAtLeast(0L)
            val creditReduction = minOf(totalReturn, outstanding)
            val cashRefund = Math.subtractExact(totalReturn, creditReduction)
            validateRefundAllocations(db, invoice, cashRefund, refundAllocations)

            val returnId = UUID.randomUUID().toString()
            val now = timestamp()
            val number = nextReturnNumber(db, returnDate)
            val allCogsKnown = itemPlans.all { it.returnedCogsUsdCents != null }
            val totalCogs = if (allCogsKnown) itemPlans.fold(0L) { sum, plan ->
                Math.addExact(sum, plan.returnedCogsUsdCents ?: 0L)
            } else null
            db.insertOrThrow("returns", null, ContentValues().apply {
                put("id", returnId); put("return_number", number)
                put("original_invoice_type", "SALE"); put("original_invoice_id", originalInvoiceId)
                if (invoice.customerId == null) { putNull("party_type"); putNull("party_id") }
                else { put("party_type", "CUSTOMER"); put("party_id", invoice.customerId) }
                put("return_date", returnDate); put("total_usd_cents", totalReturn)
                put("reason", reason.trim()); put("status", "POSTED")
                put("created_by", actorUserId); put("created_at", now)
                put("operation_id", operationId); put("posted_at", now)
                put("cash_refund_usd_cents", cashRefund)
                put("credit_reduction_usd_cents", creditReduction)
                if (totalCogs == null) putNull("returned_cogs_usd_cents")
                else put("returned_cogs_usd_cents", totalCogs)
                put("exchange_rate_syp_per_usd_milli", invoice.rateMilli)
            })

            itemPlans.forEachIndexed { index, plan ->
                val returnItemId = UUID.randomUUID().toString()
                db.insertOrThrow("return_items", null, ContentValues().apply {
                    put("id", returnItemId); put("return_id", returnId)
                    put("product_id", plan.original.productId)
                    put("quantity", plan.quantity.toDouble())
                    put("unit_price_usd_cents", plan.original.unitPriceUsdCents)
                    put("line_total_usd_cents", plan.returnValueUsdCents)
                    put("original_sales_item_id", plan.original.id)
                    if (plan.returnedCogsUsdCents == null) putNull("returned_cogs_usd_cents")
                    else put("returned_cogs_usd_cents", plan.returnedCogsUsdCents)
                })
                if (plan.original.tracksInventory) {
                    if (plan.costAllocations.isEmpty()) {
                        addReturnedStockMovement(
                            db, actorUserId, returnId, returnItemId, operationId, index,
                            plan.original.productId, invoice.warehouseId, plan.quantity, null, now
                        )
                    } else {
                        plan.costAllocations.forEachIndexed { allocationIndex, allocation ->
                            val movementId = addReturnedStockMovement(
                                db, actorUserId, returnId, returnItemId, operationId,
                                index * 1000 + allocationIndex, plan.original.productId,
                                invoice.warehouseId, allocation.quantity, allocation.unitCostUsdCents, now
                            )
                            if (allocation.sourceAllocationId != null) {
                                db.insertOrThrow("return_cost_allocations", null, ContentValues().apply {
                                    put("id", UUID.randomUUID().toString())
                                    put("return_item_id", returnItemId)
                                    put("source_sale_allocation_id", allocation.sourceAllocationId)
                                    put("inventory_movement_id", movementId)
                                    put("quantity", allocation.quantity.toDouble())
                                    if (allocation.unitCostUsdCents == null) putNull("unit_cost_usd_cents")
                                    else put("unit_cost_usd_cents", allocation.unitCostUsdCents)
                                    if (allocation.costUsdCents == null) putNull("cost_usd_cents")
                                    else put("cost_usd_cents", allocation.costUsdCents)
                                    put("created_at", now)
                                })
                            }
                        }
                    }
                }
            }

            refundAllocations.forEachIndexed { index, allocation ->
                postRefundAllocation(db, actorUserId, returnId, operationId, index,
                    invoice.rateMilli, returnDate, now, allocation)
            }
            db.insertOrThrow("audit_log", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString()); put("user_id", actorUserId)
                put("action", "SALE_RETURN_POSTED"); put("entity_type", "RETURN")
                put("entity_id", returnId)
                put("after_json", """{"original_invoice_id":"$originalInvoiceId","total_usd_cents":$totalReturn,"credit_reduction_usd_cents":$creditReduction,"cash_refund_usd_cents":$cashRefund}""")
                put("created_at", now)
            })
            db.insertOrThrow("operation_registry", null, ContentValues().apply {
                put("operation_id", operationId); put("operation_type", "POST_SALES_RETURN")
                put("payload_hash", payloadHash); put("status", "COMPLETED")
                put("result_reference", returnId); put("created_at", now); put("completed_at", now)
            })
            db.setTransactionSuccessful()
            return SalesReturnResult(returnId, number, totalReturn, creditReduction, cashRefund, totalCogs, false)
        } finally { db.endTransaction() }
    }

    private fun loadOriginalInvoice(db: SQLiteDatabase, id: String): OriginalInvoice =
        db.rawQuery(
            """SELECT id,invoice_number,sale_type,customer_id,warehouse_id,status,total_usd_cents,
                      subtotal_usd_cents,discount_usd_cents,exchange_rate_syp_per_usd_milli
               FROM sales_invoices WHERE id=?""".trimIndent(), arrayOf(id)
        ).use { c ->
            require(c.moveToFirst()) { "فاتورة البيع الأصلية غير موجودة" }
            OriginalInvoice(c.getString(0), c.getString(1), c.getString(2),
                if (c.isNull(3)) null else c.getString(3), c.getString(4), c.getString(5),
                c.getLong(6), c.getLong(7), c.getLong(8), c.getLong(9))
        }

    private fun loadOriginalItems(db: SQLiteDatabase, invoice: OriginalInvoice): List<OriginalItem> {
        val result = mutableListOf<OriginalItem>()
        db.rawQuery(
            """SELECT si.id,si.product_id,si.quantity,si.unit_price_usd_cents,si.line_total_usd_cents,
                      p.track_inventory,u.decimal_places
               FROM sales_items si JOIN products p ON p.id=si.product_id
               JOIN units u ON u.id=p.unit_id
               WHERE si.sales_invoice_id=? ORDER BY si.rowid""".trimIndent(), arrayOf(invoice.id)
        ).use { c ->
            while (c.moveToNext()) result.add(OriginalItem(
                c.getString(0), c.getString(1), BigDecimal.valueOf(c.getDouble(2)),
                c.getLong(3), c.getLong(4), c.getInt(5) == 1, c.getInt(6)
            ))
        }
        require(result.isNotEmpty()) { "الفاتورة الأصلية لا تحتوي بنوداً" }
        val subtotal = result.fold(0L) { sum, item -> Math.addExact(sum, item.lineTotalUsdCents) }
        require(subtotal == invoice.subtotalUsdCents) {
            "إجمالي بنود الفاتورة لا يطابق رأس الفاتورة؛ أوقف المرتجع للمراجعة"
        }
        return result
    }

    private fun buildItemPlan(
        db: SQLiteDatabase, original: OriginalItem, quantity: BigDecimal, invoice: OriginalInvoice
    ): ItemPlan {
        require(quantity.signum() > 0) { "كمية المرتجع يجب أن تكون موجبة" }
        val precision = quantity.stripTrailingZeros().scale().coerceAtLeast(0)
        require(precision <= original.decimalPlaces) { "دقة كمية المرتجع غير صالحة" }
        val previous = db.rawQuery(
            """SELECT COALESCE(SUM(ri.quantity),0),COALESCE(SUM(ri.line_total_usd_cents),0)
               FROM return_items ri JOIN returns r ON r.id=ri.return_id
               WHERE ri.original_sales_item_id=? AND r.status='POSTED'""".trimIndent(),
            arrayOf(original.id)
        ).use { c -> c.moveToFirst(); BigDecimal.valueOf(c.getDouble(0)) to c.getLong(1) }
        val remaining = original.quantity.subtract(previous.first)
        require(remaining.signum() > 0 && quantity <= remaining) {
            "الكمية المطلوبة تتجاوز الكمية المتبقية القابلة للإرجاع لهذا الصنف"
        }

        val siblings = mutableListOf<Pair<String, Long>>()
        db.rawQuery("SELECT id,line_total_usd_cents FROM sales_items WHERE sales_invoice_id=? ORDER BY rowid",
            arrayOf(invoice.id)).use { c -> while (c.moveToNext()) siblings.add(c.getString(0) to c.getLong(1)) }
        val subtotal = siblings.fold(0L) { sum, item -> Math.addExact(sum, item.second) }
        var discountAssigned = 0L
        var originalDiscount = 0L
        siblings.forEachIndexed { index, item ->
            val share = when {
                invoice.discountUsdCents == 0L || subtotal == 0L -> 0L
                index == siblings.lastIndex -> invoice.discountUsdCents - discountAssigned
                else -> BigDecimal.valueOf(invoice.discountUsdCents)
                    .multiply(BigDecimal.valueOf(item.second))
                    .divide(BigDecimal.valueOf(subtotal), 0, RoundingMode.DOWN)
                    .longValueExact().also { discountAssigned = Math.addExact(discountAssigned, it) }
            }
            if (item.first == original.id) originalDiscount = share
        }
        require(originalDiscount <= original.lineTotalUsdCents) { "خصم الفاتورة غير قابل للتوزيع بأمان" }
        val netLine = original.lineTotalUsdCents - originalDiscount
        val cumulativeQuantity = previous.first.add(quantity)
        val cumulativeValue = BigDecimal.valueOf(netLine).multiply(cumulativeQuantity)
            .divide(original.quantity, 0, RoundingMode.HALF_UP).longValueExact()
        val returnValue = Math.subtractExact(cumulativeValue, previous.second)
        require(returnValue >= 0L) { "تعذر حساب قيمة المرتجع" }

        if (!original.tracksInventory) return ItemPlan(original, quantity, returnValue, 0L, emptyList())
        val costs = planReturnedCosts(db, original.id, quantity)
        val known = costs.isNotEmpty() && costs.all { it.costUsdCents != null }
        val cogs = if (known) costs.fold(0L) { sum, item -> Math.addExact(sum, item.costUsdCents!!) } else null
        return ItemPlan(original, quantity, returnValue, cogs, costs)
    }

    private fun planReturnedCosts(
        db: SQLiteDatabase, salesItemId: String, requested: BigDecimal
    ): List<CostAllocationPlan> {
        data class Source(
            val id: String, val quantity: BigDecimal, val unitCost: Long?, val totalCost: Long?,
            val returnedQuantity: BigDecimal, val returnedCost: Long
        )
        val sources = mutableListOf<Source>()
        db.rawQuery(
            """SELECT a.id,a.quantity,a.unit_cost_usd_cents,a.cost_usd_cents,
                      COALESCE(SUM(r.quantity),0),COALESCE(SUM(r.cost_usd_cents),0)
               FROM sales_item_cost_allocations a
               LEFT JOIN return_cost_allocations r ON r.source_sale_allocation_id=a.id
               WHERE a.sales_item_id=? GROUP BY a.id ORDER BY a.rowid""".trimIndent(),
            arrayOf(salesItemId)
        ).use { c ->
            while (c.moveToNext()) sources.add(Source(
                c.getString(0), BigDecimal.valueOf(c.getDouble(1)),
                if (c.isNull(2)) null else c.getLong(2),
                if (c.isNull(3)) null else c.getLong(3),
                BigDecimal.valueOf(c.getDouble(4)), c.getLong(5)
            ))
        }
        if (sources.isEmpty()) return emptyList()
        val available = sources.fold(BigDecimal.ZERO) { sum, item ->
            sum.add(item.quantity.subtract(item.returnedQuantity))
        }
        require(available >= requested) { "تخصيصات FIFO الأصلية لا تغطي كمية المرتجع؛ أوقف العملية للمطابقة" }
        var left = requested
        val result = mutableListOf<CostAllocationPlan>()
        sources.forEach { source ->
            if (left.signum() <= 0) return@forEach
            val availableHere = source.quantity.subtract(source.returnedQuantity)
            if (availableHere.signum() <= 0) return@forEach
            val take = left.min(availableHere)
            val cumulative = source.returnedQuantity.add(take)
            val cost = source.totalCost?.let {
                val target = BigDecimal.valueOf(it).multiply(cumulative)
                    .divide(source.quantity, 0, RoundingMode.HALF_UP).longValueExact()
                Math.subtractExact(target, source.returnedCost)
            }
            result.add(CostAllocationPlan(source.id, take, source.unitCost, cost))
            left = left.subtract(take)
        }
        check(left.signum() == 0) { "تعذر توزيع تكلفة المرتجع على تخصيصات FIFO الأصلية" }
        return result
    }

    private fun validateRefundAllocations(
        db: SQLiteDatabase, invoice: OriginalInvoice, refundUsd: Long,
        allocations: List<SalesRefundAllocationInput>
    ) {
        val targetSyp = CurrencyConverter.usdCentsToSyp(refundUsd, invoice.rateMilli)
        if (refundUsd == 0L) {
            require(allocations.isEmpty()) { "لا يوجد مبلغ نقدي لرده؛ أزل توزيعات رد المبلغ" }
            return
        }
        require(allocations.isNotEmpty()) { "حدد الحسابات التي سيُرد منها المبلغ للزبون" }
        var allocatedSyp = 0L
        allocations.forEach { allocation ->
            val account = db.rawQuery(
                "SELECT currency_code,opening_balance_minor,is_active FROM cash_accounts WHERE id=?",
                arrayOf(allocation.cashAccountId)
            ).use { c ->
                require(c.moveToFirst()) { "حساب رد المبلغ غير موجود" }
                require(c.getInt(2) == 1) { "لا يمكن رد المبلغ من حساب موقوف" }
                c.getString(0) to c.getLong(1)
            }
            require(account.first == "USD" || account.first == "SYP") { "عملة حساب رد المبلغ غير مدعومة" }
            val role = db.rawQuery("SELECT role_key FROM system_cash_account_roles WHERE account_id=?",
                arrayOf(allocation.cashAccountId)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            require(role != "SUPPLIER_ROLLOVER") { "لا يمكن استخدام صندوق النقد المدور للموردين لرد مبالغ العملاء" }
            require(accountBalance(db, allocation.cashAccountId, account.second) >= allocation.amountMinor) {
                "الرصيد غير كافٍ في حساب " + allocation.cashAccountId + " لرد المبلغ"
            }
            val equivalent = if (account.first == "SYP") allocation.amountMinor
                else CurrencyConverter.usdCentsToSyp(allocation.amountMinor, invoice.rateMilli)
            allocatedSyp = Math.addExact(allocatedSyp, equivalent)
        }
        require(allocatedSyp == targetSyp) {
            "مجموع توزيعات رد المبلغ لا يساوي المبلغ المستحق وفق سعر صرف الفاتورة الأصلية"
        }
    }

    private fun addReturnedStockMovement(
        db: SQLiteDatabase, actor: String, returnId: String, returnItemId: String,
        operationId: String, movementIndex: Int, productId: String, warehouseId: String,
        quantity: BigDecimal, unitCost: Long?, now: String
    ): String {
        val movementId = UUID.randomUUID().toString()
        val op = "$operationId:STOCK:$returnItemId:$movementIndex"
        db.execSQL("UPDATE inventory_balances SET quantity=quantity+?,updated_at=? WHERE product_id=? AND warehouse_id=?",
            arrayOf(quantity.toDouble(), now, productId, warehouseId))
        val exists = db.rawQuery("SELECT 1 FROM inventory_balances WHERE product_id=? AND warehouse_id=?",
            arrayOf(productId, warehouseId)).use { it.moveToFirst() }
        if (!exists) db.insertOrThrow("inventory_balances", null, ContentValues().apply {
            put("product_id", productId); put("warehouse_id", warehouseId)
            put("quantity", quantity.toDouble()); put("updated_at", now)
        })
        db.insertOrThrow("inventory_movements", null, ContentValues().apply {
            put("id", movementId); put("product_id", productId); put("warehouse_id", warehouseId)
            put("movement_type", "SALE_RETURN"); put("quantity_delta", quantity.toDouble())
            put("unit_cost_usd_cents", unitCost ?: 0L)
            put("source_type", "SALE_RETURN"); put("source_id", returnId)
            put("operation_id", op); put("note", "إرجاع صنف من المرتجع " + returnId)
            put("created_by", actor); put("created_at", now)
        })
        FifoCosting.addLayer(db, movementId, productId, warehouseId, quantity.toDouble(),
            unitCost ?: 0L, "SALE_RETURN", returnId, now)
        return movementId
    }

    private fun postRefundAllocation(
        db: SQLiteDatabase, actor: String, returnId: String, operationId: String,
        index: Int, rate: Long, returnDate: String, now: String,
        allocation: SalesRefundAllocationInput
    ) {
        val currency = db.rawQuery("SELECT currency_code FROM cash_accounts WHERE id=? AND is_active=1",
            arrayOf(allocation.cashAccountId)).use { c -> require(c.moveToFirst()); c.getString(0) }
        val equivalentSyp = if (currency == "SYP") allocation.amountMinor
            else CurrencyConverter.usdCentsToSyp(allocation.amountMinor, rate)
        val cashId = UUID.randomUUID().toString()
        val op = "$operationId:REFUND:$index"
        db.insertOrThrow("cash_transactions", null, ContentValues().apply {
            put("id", cashId); put("account_id", allocation.cashAccountId)
            put("direction", "OUT"); put("amount_minor", allocation.amountMinor)
            put("currency_code", currency); put("exchange_rate_syp_per_usd_milli", rate)
            put("transaction_type", "SALE_RETURN_REFUND"); put("source_type", "RETURN")
            put("source_id", returnId); put("operation_id", op)
            put("description", "رد مبلغ مرتجع مبيعات " + returnId)
            put("created_by", actor); put("operating_date", returnDate); put("created_at", now)
        })
        db.insertOrThrow("return_refund_allocations", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("return_id", returnId)
            put("cash_account_id", allocation.cashAccountId); put("amount_minor", allocation.amountMinor)
            put("currency_code", currency); put("company_fee_minor", 0L)
            put("exchange_rate_syp_per_usd_milli", rate); put("created_at", now)
            put("operation_id", op); put("cash_transaction_id", cashId)
            put("refund_equivalent_syp_minor", equivalentSyp)
        })
    }

    private fun accountBalance(db: SQLiteDatabase, accountId: String, opening: Long): Long {
        val moves = db.rawQuery(
            """SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_minor
                       WHEN direction='OUT' THEN -amount_minor ELSE 0 END),0)
               FROM cash_transactions WHERE account_id=?""".trimIndent(), arrayOf(accountId)
        ).use { c -> c.moveToFirst(); c.getLong(0) }
        return Math.addExact(opening, moves)
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
                val usd = when (c.getString(1)) {
                    "USD" -> amount
                    "SYP" -> {
                        require(rate > 0L) { "دفعة ليرة قديمة بلا سعر صرف محفوظ" }
                        CurrencyConverter.sypToUsdCents(amount, rate)
                    }
                    else -> error("عملة دفعة غير مدعومة")
                }
                total = Math.addExact(total, usd)
            }
        }
        return total
    }

    private fun loadResult(db: SQLiteDatabase, id: String, replayed: Boolean): SalesReturnResult =
        db.rawQuery(
            """SELECT return_number,total_usd_cents,credit_reduction_usd_cents,
                      cash_refund_usd_cents,returned_cogs_usd_cents
               FROM returns WHERE id=? AND status='POSTED'""".trimIndent(), arrayOf(id)
        ).use { c ->
            require(c.moveToFirst()) { "المرتجع السابق غير موجود" }
            SalesReturnResult(id, c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3),
                if (c.isNull(4)) null else c.getLong(4), replayed)
        }

    private fun nextReturnNumber(db: SQLiteDatabase, date: String): String {
        val count = db.rawQuery("SELECT COUNT(*) FROM returns WHERE return_date=?", arrayOf(date))
            .use { c -> c.moveToFirst(); c.getInt(0) + 1 }
        return "SR-" + date.replace("-", "") + "-" + count.toString().padStart(4, '0')
    }

    private fun productName(db: SQLiteDatabase, productId: String): String =
        db.rawQuery("SELECT name FROM products WHERE id=?", arrayOf(productId)).use { c ->
            require(c.moveToFirst()) { "الصنف غير موجود" }
            c.getString(0)
        }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
