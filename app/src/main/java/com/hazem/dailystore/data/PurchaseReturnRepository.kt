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

data class PurchaseReturnItemInput(val originalPurchaseItemId: String, val quantity: BigDecimal)
data class SupplierRefundAllocationInput(val cashAccountId: String, val amountMinor: Long)
data class PurchaseReturnableItem(
    val id: String, val productName: String, val purchasedQuantity: BigDecimal,
    val returnedQuantity: BigDecimal, val availableQuantity: BigDecimal,
    val unitCostUsdCents: Long, val tracksInventory: Boolean
)
data class PurchaseReturnInvoiceOption(
    val id: String, val invoiceNumber: String, val date: String,
    val supplierName: String, val totalUsdCents: Long
)
data class PurchaseReturnPreview(
    val totalUsdCents: Long, val totalSypMinor: Long,
    val supplierCreditReductionSypMinor: Long, val supplierCashRefundSypMinor: Long
)
data class PurchaseReturnResult(
    val returnId: String, val returnNumber: String, val totalUsdCents: Long,
    val supplierCreditReductionSypMinor: Long, val supplierCashRefundSypMinor: Long,
    val replayed: Boolean
)

/** Supplier returns reduce the original payable first; any excess is received into a selected account. */
class PurchaseReturnRepository(context: Context) {
    private val context = context.applicationContext
    private val database = AppDatabase.getInstance(context)
    private data class Invoice(
        val id: String, val number: String, val supplierId: String, val supplierName: String,
        val warehouseId: String, val status: String, val total: Long, val subtotal: Long,
        val discount: Long, val rate: Long
    )
    private data class Item(
        val id: String, val productId: String, val quantity: BigDecimal, val unitCost: Long,
        val lineTotal: Long, val tracks: Boolean, val decimals: Int
    )
    private data class Layer(val id: String, val quantity: BigDecimal, val unitCost: Long?)
    private data class Plan(val item: Item, val quantity: BigDecimal, val value: Long, val layers: List<Layer>)

    fun listReturnableInvoices(): List<PurchaseReturnInvoiceOption> {
        val result = mutableListOf<PurchaseReturnInvoiceOption>()
        database.readableDatabase.rawQuery(
            """SELECT i.id,i.invoice_number,i.invoice_date,s.name,i.total_usd_cents
               FROM purchase_invoices i JOIN suppliers s ON s.id=i.supplier_id
               WHERE i.status IN ('POSTED','PARTIALLY_PAID','PAID')
               ORDER BY i.invoice_date DESC,i.created_at DESC""".trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) {
                val id=c.getString(0)
                if (listReturnableItems(id).any { it.availableQuantity.signum()>0 })
                    result.add(PurchaseReturnInvoiceOption(id,c.getString(1),c.getString(2),c.getString(3),c.getLong(4)))
            }
        }
        return result
    }

    fun listReturnableItems(invoiceId: String): List<PurchaseReturnableItem> {
        val db=database.readableDatabase
        val invoice=loadInvoice(db,invoiceId)
        return loadItems(db,invoice).map { item ->
            val returned=returnedQuantity(db,item.id)
            val layerQty=if(item.tracks) sourceLayerQuantity(db,invoice,item.id) else item.quantity
            PurchaseReturnableItem(item.id,productName(db,item.productId),item.quantity,returned,
                layerQty.min(item.quantity.subtract(returned).max(BigDecimal.ZERO)),
                item.unitCost,item.tracks)
        }
    }

    fun previewPurchaseReturn(invoiceId: String, items: List<PurchaseReturnItemInput>): PurchaseReturnPreview {
        require(items.isNotEmpty()) { "حدد الأصناف المرتجعة" }
        require(items.map { it.originalPurchaseItemId }.distinct().size==items.size) { "لا تكرر بند الفاتورة" }
        val db=database.readableDatabase
        val invoice=loadInvoice(db,invoiceId)
        require(invoice.status in setOf("POSTED","PARTIALLY_PAID","PAID")) { "لا يمكن إرجاع فاتورة غير معتمدة" }
        require(invoice.rate>0L) { "فاتورة الشراء بلا سعر صرف محفوظ" }
        val originals=loadItems(db,invoice)
        val plans=items.map { input ->
            val item=originals.firstOrNull { it.id==input.originalPurchaseItemId }
                ?: throw IllegalArgumentException("الصنف لا ينتمي إلى فاتورة الشراء")
            planItem(db,invoice,item,input.quantity)
        }
        return calculate(db,invoice,plans)
    }

    fun postPurchaseReturn(
        actorUserId: String, invoiceId: String, operationId: String, returnDate: String,
        reason: String, items: List<PurchaseReturnItemInput>,
        refundAllocations: List<SupplierRefundAllocationInput>,
        occurredAt: String? = null, syncOrigin: Boolean = false, operatingDate: String? = null
    ): PurchaseReturnResult {
        require(actorUserId.isNotBlank() && invoiceId.isNotBlank() && operationId.isNotBlank())
        require(returnDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "تاريخ المرتجع غير صالح" }
        require(reason.isNotBlank() && items.isNotEmpty()) { "سبب المرتجع والأصناف مطلوبان" }
        require(items.all { it.originalPurchaseItemId.isNotBlank() && it.quantity.signum()>0 })
        require(items.map { it.originalPurchaseItemId }.distinct().size==items.size) { "اجمع كل صنف في سطر واحد" }
        require(refundAllocations.all { it.cashAccountId.isNotBlank() && it.amountMinor>0L })
        require(refundAllocations.map { it.cashAccountId }.distinct().size==refundAllocations.size)

        val payload=buildString {
            append(invoiceId).append('|').append(returnDate).append('|').append(reason.trim())
            items.sortedBy { it.originalPurchaseItemId }.forEach {
                append('|').append(it.originalPurchaseItemId).append(':').append(it.quantity.stripTrailingZeros().toPlainString())
            }
            refundAllocations.sortedBy { it.cashAccountId }.forEach {
                append('|').append(it.cashAccountId).append(':').append(it.amountMinor)
            }
        }
        val payloadHash=hash(payload)
        val db=database.writableDatabase
        db.beginTransaction()
        try {
            val localRole=db.rawQuery("SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'",null)
                .use { c -> if(c.moveToFirst()) c.getString(0) else null }
            if(localRole=="SECONDARY") {
                PermissionPolicy.requireAssigned(db,actorUserId,PermissionPolicy.RETURNS_PROCESS)
                val preview=previewPurchaseReturn(invoiceId,items)
                val priorPayload=db.rawQuery("SELECT payload_json FROM sync_operation_envelopes WHERE operation_id=?",arrayOf(operationId))
                    .use { c -> if(c.moveToFirst()) org.json.JSONObject(c.getString(0)) else null }
                val eventTime=occurredAt ?: priorPayload?.optString("occurred_at")
                    ?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")) } ?: timestamp()
                val movementDate=operatingDate ?: priorPayload?.optString("operating_date")
                    ?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: StoreTime.operatingDate(db,eventTime)
                val payload=org.json.JSONObject().put("original_invoice_id",invoiceId)
                    .put("return_date",returnDate).put("reason",reason.trim())
                    .put("occurred_at",eventTime).put("operating_date",movementDate)
                    .put("items",org.json.JSONArray().apply { items.forEach { put(org.json.JSONObject()
                        .put("original_purchase_item_id",it.originalPurchaseItemId)
                        .put("quantity",it.quantity.stripTrailingZeros().toPlainString())) } })
                    .put("refund_allocations",org.json.JSONArray().apply { refundAllocations.forEach { put(org.json.JSONObject()
                        .put("cash_account_id",it.cashAccountId).put("amount_minor",it.amountMinor)
                        .put("cash_account_type",db.rawQuery("SELECT account_type FROM cash_accounts WHERE id=?",arrayOf(it.cashAccountId)).use { c -> require(c.moveToFirst());c.getString(0) })
                        .put("cash_account_currency_code",db.rawQuery("SELECT currency_code FROM cash_accounts WHERE id=?",arrayOf(it.cashAccountId)).use { c -> require(c.moveToFirst());c.getString(0) })) } })
                SyncProtocolRepository(context).enqueueSecondaryCommand(
                    db,actorUserId,operationId,"POST_PURCHASE_RETURN","PURCHASE_RETURN",
                    "pending-return-$operationId",payload.toString())
                db.setTransactionSuccessful()
                return PurchaseReturnResult("pending-return-$operationId","معلق",preview.totalUsdCents,
                    preview.supplierCreditReductionSypMinor,preview.supplierCashRefundSypMinor,false)
            }
            PermissionPolicy.require(db,actorUserId,PermissionPolicy.RETURNS_PROCESS)
            findOperation(db,operationId)?.let { old ->
                require(old.first=="POST_PURCHASE_RETURN" && old.second==payloadHash) { "معرّف العملية مستخدم ببيانات مختلفة" }
                val result=loadResult(db,old.third ?: error("مرجع المرتجع مفقود"),true)
                db.setTransactionSuccessful()
                return result
            }
            val invoice=loadInvoice(db,invoiceId)
            require(invoice.status in setOf("POSTED","PARTIALLY_PAID","PAID")) { "لا يمكن إرجاع فاتورة غير معتمدة أو ملغاة" }
            require(invoice.rate>0L) { "فاتورة الشراء بلا سعر صرف محفوظ" }
            val originals=loadItems(db,invoice)
            val plans=items.map { input ->
                val item=originals.firstOrNull { it.id==input.originalPurchaseItemId }
                    ?: throw IllegalArgumentException("الصنف لا ينتمي إلى فاتورة الشراء")
                planItem(db,invoice,item,input.quantity)
            }
            val preview=calculate(db,invoice,plans)
            validateRefunds(db,invoice,preview.supplierCashRefundSypMinor,refundAllocations)
            val now=occurredAt ?: timestamp()
            val returnId=UUID.randomUUID().toString()
            val number=nextNumber(db,returnDate)
            db.insertOrThrow("returns",null,ContentValues().apply {
                put("id",returnId); put("return_number",number); put("original_invoice_type","PURCHASE")
                put("original_invoice_id",invoice.id); put("party_type","SUPPLIER"); put("party_id",invoice.supplierId)
                put("return_date",returnDate); put("total_usd_cents",preview.totalUsdCents)
                put("reason",reason.trim()); put("status","POSTED"); put("created_by",actorUserId)
                put("created_at",now); put("operation_id",operationId); put("posted_at",now)
                put("cash_refund_usd_cents",0L); put("credit_reduction_usd_cents",0L)
                putNull("returned_cogs_usd_cents"); put("exchange_rate_syp_per_usd_milli",invoice.rate)
                put("supplier_credit_reduction_syp_minor",preview.supplierCreditReductionSypMinor)
                put("supplier_cash_refund_syp_minor",preview.supplierCashRefundSypMinor)
            })
            plans.forEach { plan ->
                val returnItemId=UUID.randomUUID().toString()
                db.insertOrThrow("return_items",null,ContentValues().apply {
                    put("id",returnItemId); put("return_id",returnId); put("product_id",plan.item.productId)
                    put("quantity",plan.quantity.toDouble()); put("unit_price_usd_cents",plan.item.unitCost)
                    put("line_total_usd_cents",plan.value); put("original_purchase_item_id",plan.item.id)
                    putNull("original_sales_item_id"); putNull("returned_cogs_usd_cents")
                })
                plan.layers.forEachIndexed { index, layer ->
                    postStockReturn(db,actorUserId,returnId,returnItemId,operationId,index,invoice,plan.item,layer,now)
                }
            }
            refundAllocations.forEachIndexed { index, allocation ->
                postRefund(db,actorUserId,returnId,operationId,index,invoice.rate,operatingDate ?: returnDate,now,allocation)
            }
            db.insertOrThrow("audit_log",null,ContentValues().apply {
                put("id",UUID.randomUUID().toString()); put("user_id",actorUserId)
                put("action","PURCHASE_RETURN_POSTED"); put("entity_type","PURCHASE_RETURN"); put("entity_id",returnId)
                put("after_json","{\"original_invoice_id\":\""+invoice.id+"\",\"total_usd_cents\":"+preview.totalUsdCents+",\"supplier_credit_reduction_syp_minor\":"+preview.supplierCreditReductionSypMinor+",\"supplier_cash_refund_syp_minor\":"+preview.supplierCashRefundSypMinor+"}")
                put("created_at",now)
            })
            db.insertOrThrow("operation_registry",null,ContentValues().apply {
                put("operation_id",operationId); put("operation_type","POST_PURCHASE_RETURN")
                put("payload_hash",payloadHash); put("status","COMPLETED"); put("result_reference",returnId)
                put("created_at",now); put("completed_at",now)
            })
            if(!syncOrigin) {
                SyncProtocolRepository(context).enqueueLocalOperation(
                    db, actorUserId, operationId, "POST_PURCHASE_RETURN", "PURCHASE_RETURN", returnId, "CREATE",
                    org.json.JSONObject().put("original_invoice_id", invoice.id)
                        .put("return_id", returnId).put("return_number", number).put("return_date", returnDate)
                        .put("reason", reason.trim()).put("total_usd_cents", preview.totalUsdCents)
                        .put("supplier_credit_reduction_syp_minor", preview.supplierCreditReductionSypMinor)
                        .put("supplier_cash_refund_syp_minor", preview.supplierCashRefundSypMinor).toString()
                )
            }
            db.setTransactionSuccessful()
            return PurchaseReturnResult(returnId,number,preview.totalUsdCents,
                preview.supplierCreditReductionSypMinor,preview.supplierCashRefundSypMinor,false)
        } finally { db.endTransaction(); runCatching { SyncRelaySession.flushPending() } }
    }

    private fun calculate(db: SQLiteDatabase, invoice: Invoice, plans: List<Plan>): PurchaseReturnPreview {
        val total=plans.fold(0L) { sum,p -> Math.addExact(sum,p.value) }
        val prior=db.rawQuery(
            """SELECT COALESCE(SUM(total_usd_cents),0),
                      COALESCE(SUM(supplier_credit_reduction_syp_minor),0),
                      COALESCE(SUM(supplier_cash_refund_syp_minor),0)
               FROM returns WHERE original_invoice_type='PURCHASE' AND original_invoice_id=? AND status='POSTED'""".trimIndent(),
            arrayOf(invoice.id)
        ).use { c -> c.moveToFirst(); Triple(c.getLong(0),c.getLong(1),c.getLong(2)) }
        val cumulativeSyp=CurrencyConverter.usdCentsToSyp(Math.addExact(prior.first,total),invoice.rate)
        val priorSyp=Math.addExact(prior.second,prior.third)
        val currentSyp=Math.subtractExact(cumulativeSyp,priorSyp)
        require(currentSyp>=0L) { "تعذر مطابقة المرتجعات السابقة مع سعر صرف الفاتورة" }
        val totalSyp=CurrencyConverter.usdCentsToSyp(invoice.total,invoice.rate)
        val outstanding=(totalSyp-paidSyp(db,invoice.id,invoice.rate)-prior.second).coerceAtLeast(0L)
        val credit=minOf(currentSyp,outstanding)
        return PurchaseReturnPreview(total,currentSyp,credit,currentSyp-credit)
    }

    private fun planItem(db: SQLiteDatabase, invoice: Invoice, item: Item, quantity: BigDecimal): Plan {
        require(quantity.signum()>0 && quantity.stripTrailingZeros().scale().coerceAtLeast(0)<=item.decimals) {
            "كمية المرتجع أو دقتها غير صالحة"
        }
        val previous=db.rawQuery(
            """SELECT COALESCE(SUM(ri.quantity),0),COALESCE(SUM(ri.line_total_usd_cents),0)
               FROM return_items ri JOIN returns r ON r.id=ri.return_id
               WHERE ri.original_purchase_item_id=? AND r.status='POSTED'""".trimIndent(),arrayOf(item.id)
        ).use { c -> c.moveToFirst(); BigDecimal.valueOf(c.getDouble(0)) to c.getLong(1) }
        require(quantity<=item.quantity.subtract(previous.first)) { "الكمية تتجاوز المتبقي من بند الشراء" }
        val siblings=mutableListOf<Pair<String,Long>>()
        db.rawQuery("SELECT id,line_total_usd_cents FROM purchase_items WHERE purchase_invoice_id=? ORDER BY rowid",
            arrayOf(invoice.id)).use { c -> while(c.moveToNext()) siblings.add(c.getString(0) to c.getLong(1)) }
        val subtotal=siblings.fold(0L) { sum,p -> Math.addExact(sum,p.second) }
        require(subtotal==invoice.subtotal) { "إجمالي بنود فاتورة الشراء غير متطابق" }
        var assigned=0L; var discount=0L
        siblings.forEachIndexed { index,p ->
            val share=when {
                invoice.discount==0L || subtotal==0L -> 0L
                index==siblings.lastIndex -> invoice.discount-assigned
                else -> BigDecimal.valueOf(invoice.discount).multiply(BigDecimal.valueOf(p.second))
                    .divide(BigDecimal.valueOf(subtotal),0,RoundingMode.DOWN).longValueExact()
                    .also { assigned=Math.addExact(assigned,it) }
            }
            if(p.first==item.id) discount=share
        }
        require(discount<=item.lineTotal)
        val netLine=item.lineTotal-discount
        val cumulative=BigDecimal.valueOf(netLine).multiply(previous.first.add(quantity))
            .divide(item.quantity,0,RoundingMode.HALF_UP).longValueExact()
        val value=Math.subtractExact(cumulative,previous.second)
        if(!item.tracks) return Plan(item,quantity,value,emptyList())

        val layers=mutableListOf<Layer>()
        db.rawQuery(
            """SELECT id,remaining_quantity,unit_cost_usd_cents FROM inventory_cost_layers
               WHERE source_item_id=? AND source_type='PURCHASE_INVOICE' AND source_id=?
                 AND product_id=? AND warehouse_id=? AND remaining_quantity>0 ORDER BY received_at,rowid""".trimIndent(),
            arrayOf(item.id,invoice.id,item.productId,invoice.warehouseId)
        ).use { c -> while(c.moveToNext()) layers.add(Layer(c.getString(0),BigDecimal.valueOf(c.getDouble(1)),
            if(c.isNull(2)) null else c.getLong(2))) }
        val available=layers.fold(BigDecimal.ZERO) { sum,l -> sum.add(l.quantity) }
        require(available>=quantity) { "طبقة شراء الصنف الأصلية لا تحتوي كمية كافية؛ لا يمكن خصم دفعات شراء أخرى" }
        val balance=db.rawQuery("SELECT quantity FROM inventory_balances WHERE product_id=? AND warehouse_id=?",
            arrayOf(item.productId,invoice.warehouseId)).use { c -> if(c.moveToFirst()) BigDecimal.valueOf(c.getDouble(0)) else BigDecimal.ZERO }
        require(balance>=quantity) { "المخزون الحالي لا يغطي كمية المرتجع" }
        var left=quantity
        val selected=mutableListOf<Layer>()
        layers.forEach { l -> if(left.signum()>0) { val take=left.min(l.quantity); selected.add(l.copy(quantity=take)); left=left.subtract(take) } }
        check(left.signum()==0)
        return Plan(item,quantity,value,selected)
    }

    private fun validateRefunds(db: SQLiteDatabase, invoice: Invoice, target: Long, allocations: List<SupplierRefundAllocationInput>) {
        if(target==0L) { require(allocations.isEmpty()) { "لا يوجد مبلغ نقدي مسترد من المورد" }; return }
        require(allocations.isNotEmpty()) { "حدد الحساب الذي سيستقبل المبلغ المسترد" }
        var sum=0L
        allocations.forEach { a ->
            val currency=db.rawQuery("SELECT currency_code,is_active FROM cash_accounts WHERE id=?",arrayOf(a.cashAccountId)).use { c ->
                require(c.moveToFirst() && c.getInt(1)==1) { "حساب الاسترداد غير موجود أو موقوف" }; c.getString(0)
            }
            require(currency=="USD" || currency=="SYP") { "عملة الحساب غير مدعومة" }
            val equivalent=if(currency=="SYP") a.amountMinor else CurrencyConverter.usdCentsToSyp(a.amountMinor,invoice.rate)
            sum=Math.addExact(sum,equivalent)
        }
        require(sum==target) { "توزيع المبلغ المسترد لا يساوي القيمة المستحقة وفق سعر صرف الفاتورة" }
    }

    private fun postStockReturn(
        db: SQLiteDatabase, actor: String, returnId: String, returnItemId: String,
        operationId: String, index: Int, invoice: Invoice, item: Item, layer: Layer, now: String
    ) {
        db.execSQL("UPDATE inventory_cost_layers SET remaining_quantity=remaining_quantity-? WHERE id=? AND remaining_quantity>=?",
            arrayOf(layer.quantity.toDouble(),layer.id,layer.quantity.toDouble()))
        require(db.rawQuery("SELECT changes()",null).use { it.moveToFirst() && it.getInt(0)==1 }) {
            "تعذر خصم الكمية من طبقة الشراء الأصلية"
        }
        db.execSQL("UPDATE inventory_balances SET quantity=quantity-?,updated_at=? WHERE product_id=? AND warehouse_id=? AND quantity>=?",
            arrayOf(layer.quantity.toDouble(),now,item.productId,invoice.warehouseId,layer.quantity.toDouble()))
        require(db.rawQuery("SELECT changes()",null).use { it.moveToFirst() && it.getInt(0)==1 }) {
            "رصيد المخزون غير كافٍ لإتمام المرتجع"
        }
        val movement=UUID.randomUUID().toString()
        db.insertOrThrow("inventory_movements",null,ContentValues().apply {
            put("id",movement); put("product_id",item.productId); put("warehouse_id",invoice.warehouseId)
            put("movement_type","PURCHASE_RETURN"); put("quantity_delta",-layer.quantity.toDouble())
            put("unit_cost_usd_cents",layer.unitCost ?: 0L); put("source_type","PURCHASE_RETURN")
            put("source_id",returnId); put("operation_id",operationId+":STOCK:"+returnItemId+":"+index)
            put("note","مرتجع من بند الشراء الأصلي "+item.id); put("created_by",actor); put("created_at",now)
        })
        val cost=layer.unitCost?.let { layer.quantity.multiply(BigDecimal.valueOf(it)).setScale(0,RoundingMode.HALF_UP).longValueExact() }
        db.insertOrThrow("supplier_return_cost_allocations",null,ContentValues().apply {
            put("id",UUID.randomUUID().toString()); put("return_item_id",returnItemId)
            put("source_cost_layer_id",layer.id); put("inventory_movement_id",movement)
            put("quantity",layer.quantity.toDouble())
            if(layer.unitCost==null) putNull("unit_cost_usd_cents") else put("unit_cost_usd_cents",layer.unitCost)
            if(cost==null) putNull("cost_usd_cents") else put("cost_usd_cents",cost)
            put("created_at",now)
        })
    }

    private fun postRefund(
        db: SQLiteDatabase, actor: String, returnId: String, operationId: String,
        index: Int, rate: Long, date: String, now: String, allocation: SupplierRefundAllocationInput
    ) {
        val currency=db.rawQuery("SELECT currency_code FROM cash_accounts WHERE id=? AND is_active=1",
            arrayOf(allocation.cashAccountId)).use { c -> require(c.moveToFirst()); c.getString(0) }
        val equivalent=if(currency=="SYP") allocation.amountMinor else CurrencyConverter.usdCentsToSyp(allocation.amountMinor,rate)
        require(equivalent>0L)
        val cashId=UUID.randomUUID().toString()
        val op=operationId+":SUPPLIER_REFUND:"+index
        db.insertOrThrow("cash_transactions",null,ContentValues().apply {
            put("id",cashId); put("account_id",allocation.cashAccountId); put("direction","IN")
            put("amount_minor",allocation.amountMinor); put("currency_code",currency)
            put("exchange_rate_syp_per_usd_milli",rate); put("transaction_type","PURCHASE_RETURN_REFUND")
            put("source_type","PURCHASE_RETURN"); put("source_id",returnId); put("operation_id",op)
            put("description","مبلغ مسترد من المورد عن المرتجع "+returnId)
            put("created_by",actor); put("operating_date",date); put("created_at",now)
        })
        db.insertOrThrow("supplier_return_refund_allocations",null,ContentValues().apply {
            put("id",UUID.randomUUID().toString()); put("return_id",returnId)
            put("cash_account_id",allocation.cashAccountId); put("cash_transaction_id",cashId)
            put("operation_id",op); put("amount_minor",allocation.amountMinor); put("currency_code",currency)
            put("exchange_rate_syp_per_usd_milli",rate); put("refund_equivalent_syp_minor",equivalent)
            put("created_at",now)
        })
    }

    private fun loadInvoice(db: SQLiteDatabase,id: String): Invoice =
        db.rawQuery("""SELECT i.id,i.invoice_number,i.supplier_id,s.name,i.warehouse_id,i.status,
            i.total_usd_cents,i.subtotal_usd_cents,i.discount_usd_cents,i.exchange_rate_syp_per_usd_milli
            FROM purchase_invoices i JOIN suppliers s ON s.id=i.supplier_id WHERE i.id=?""".trimIndent(),arrayOf(id)).use { c ->
            require(c.moveToFirst()) { "فاتورة الشراء أو المورد غير موجود" }
            Invoice(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),
                c.getLong(6),c.getLong(7),c.getLong(8),c.getLong(9))
        }

    private fun loadItems(db: SQLiteDatabase,invoice: Invoice): List<Item> {
        val items=mutableListOf<Item>()
        db.rawQuery("""SELECT pi.id,pi.product_id,pi.quantity,pi.unit_cost_usd_cents,pi.line_total_usd_cents,
            p.track_inventory,u.decimal_places FROM purchase_items pi JOIN products p ON p.id=pi.product_id
            JOIN units u ON u.id=p.unit_id WHERE pi.purchase_invoice_id=? ORDER BY pi.rowid""".trimIndent(),
            arrayOf(invoice.id)).use { c -> while(c.moveToNext()) items.add(Item(c.getString(0),c.getString(1),
                BigDecimal.valueOf(c.getDouble(2)),c.getLong(3),c.getLong(4),c.getInt(5)==1,c.getInt(6))) }
        require(items.isNotEmpty() && items.sumOf { it.lineTotal }==invoice.subtotal) { "بنود فاتورة الشراء غير متطابقة" }
        return items
    }

    private fun returnedQuantity(db: SQLiteDatabase,id: String): BigDecimal =
        db.rawQuery("""SELECT COALESCE(SUM(ri.quantity),0) FROM return_items ri JOIN returns r ON r.id=ri.return_id
            WHERE ri.original_purchase_item_id=? AND r.status='POSTED'""".trimIndent(),arrayOf(id)).use {
            it.moveToFirst(); BigDecimal.valueOf(it.getDouble(0))
        }

    private fun sourceLayerQuantity(db: SQLiteDatabase,invoice: Invoice,itemId: String): BigDecimal =
        db.rawQuery("""SELECT COALESCE(SUM(remaining_quantity),0) FROM inventory_cost_layers
            WHERE source_item_id=? AND source_id=? AND source_type='PURCHASE_INVOICE' AND warehouse_id=?""".trimIndent(),
            arrayOf(itemId,invoice.id,invoice.warehouseId)).use { it.moveToFirst(); BigDecimal.valueOf(it.getDouble(0)) }

    private fun paidSyp(db: SQLiteDatabase,id: String,rate: Long): Long {
        var sum=0L
        db.rawQuery("SELECT amount_minor,currency_code FROM invoice_payments WHERE invoice_type='PURCHASE' AND invoice_id=?",
            arrayOf(id)).use { c -> while(c.moveToNext()) sum=Math.addExact(sum,when(c.getString(1)) {
                "SYP" -> c.getLong(0); "USD" -> CurrencyConverter.usdCentsToSyp(c.getLong(0),rate)
                else -> error("عملة دفعة المورد غير مدعومة")
            }) }
        return sum
    }

    private fun productName(db: SQLiteDatabase,id: String): String =
        db.rawQuery("SELECT name FROM products WHERE id=?",arrayOf(id)).use { require(it.moveToFirst()); it.getString(0) }

    private fun findOperation(db: SQLiteDatabase,id: String): Triple<String,String,String?>? =
        db.rawQuery("SELECT operation_type,payload_hash,result_reference FROM operation_registry WHERE operation_id=?",
            arrayOf(id)).use { c -> if(c.moveToFirst()) Triple(c.getString(0),c.getString(1),if(c.isNull(2)) null else c.getString(2)) else null }

    private fun loadResult(db: SQLiteDatabase,id: String,replayed: Boolean): PurchaseReturnResult =
        db.rawQuery("""SELECT return_number,total_usd_cents,supplier_credit_reduction_syp_minor,
            supplier_cash_refund_syp_minor FROM returns WHERE id=? AND original_invoice_type='PURCHASE' AND status='POSTED'""".trimIndent(),
            arrayOf(id)).use { c -> require(c.moveToFirst()); PurchaseReturnResult(id,c.getString(0),c.getLong(1),c.getLong(2),c.getLong(3),replayed) }

    private fun nextNumber(db: SQLiteDatabase,date: String): String {
        val count=db.rawQuery("SELECT COUNT(*) FROM returns WHERE original_invoice_type='PURCHASE' AND return_date=?",
            arrayOf(date)).use { it.moveToFirst(); it.getInt(0)+1 }
        return "PR-"+date.replace("-","")+"-"+count.toString().padStart(4,'0')
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US)
        .apply { timeZone=TimeZone.getTimeZone("UTC") }.format(Date())
}
