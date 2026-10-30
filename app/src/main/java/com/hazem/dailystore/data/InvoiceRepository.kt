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

data class InvoiceLineInput(
    val productId: String,
    val quantity: BigDecimal,
    val unitPriceUsdCents: Long
)

data class InvoiceSaveResult(val invoiceId: String, val invoiceNumber: String, val replayed: Boolean)

data class InvoiceSummary(
    val id: String,
    val invoiceNumber: String,
    val date: String,
    val status: String,
    val totalUsdCents: Long,
    val partyName: String?
)

/**
 * Transaction-safe draft and posting operations for purchase and sales invoices.
 *
 * Every write is enclosed in one SQLite transaction. Posting uses operation_registry
 * as an idempotency fence so retries cannot apply stock movements twice.
 */
class InvoiceRepository(context: Context) {
    private val database = AppDatabase.getInstance(context)

    fun savePurchaseDraft(
        operationId: String,
        invoiceId: String?,
        supplierId: String?,
        invoiceDate: String,
        discountUsdCents: Long,
        notes: String?,
        lines: List<InvoiceLineInput>
    ): InvoiceSaveResult = saveDraft(
        "PURCHASE", operationId, invoiceId, supplierId, invoiceDate,
        discountUsdCents, notes, lines
    )

    fun saveSalesDraft(
        operationId: String,
        invoiceId: String?,
        customerId: String?,
        invoiceDate: String,
        discountUsdCents: Long,
        notes: String?,
        lines: List<InvoiceLineInput>
    ): InvoiceSaveResult = saveDraft(
        "SALE", operationId, invoiceId, customerId, invoiceDate,
        discountUsdCents, notes, lines
    )

    private fun saveDraft(
        type: String,
        operationId: String,
        invoiceId: String?,
        partyId: String?,
        invoiceDate: String,
        discountUsdCents: Long,
        notes: String?,
        lines: List<InvoiceLineInput>
    ): InvoiceSaveResult {
        require(operationId.isNotBlank()) { "معرّف العملية مطلوب" }
        require(invoiceDate.isNotBlank()) { "تاريخ الفاتورة مطلوب" }
        require(discountUsdCents >= 0L) { "الخصم لا يمكن أن يكون سالباً" }
        validateLines(lines)
        val db = database.writableDatabase
        val table = if (type == "PURCHASE") "purchase_invoices" else "sales_invoices"
        val itemTable = if (type == "PURCHASE") "purchase_items" else "sales_items"
        val partyColumn = if (type == "PURCHASE") "supplier_id" else "customer_id"
        val payloadHash = hashPayload(type, invoiceId, partyId, invoiceDate, discountUsdCents, notes, lines)
        db.beginTransaction()
        try {
            findOperation(db, operationId)?.let { existing ->
                require(existing.first == payloadHash) { "معرّف العملية مستخدم لبيانات مختلفة" }
                val existingId = existing.second ?: error("سجل العملية لا يحتوي مرجع الفاتورة")
                val number = invoiceNumber(db, table, existingId)
                db.setTransactionSuccessful()
                return InvoiceSaveResult(existingId, number, true)
            }
            val warehouseId = defaultWarehouseId(db)
            val now = timestamp()
            val resolvedId = invoiceId ?: UUID.randomUUID().toString()
            val current = if (invoiceId != null) invoiceStatus(db, table, invoiceId) else null
            if (invoiceId != null) require(current == "DRAFT") { "يمكن تعديل المسودات فقط" }
            val subtotal = lines.fold(0L) { sum, line -> Math.addExact(sum, lineTotal(line)) }
            require(discountUsdCents <= subtotal) { "الخصم أكبر من إجمالي الفاتورة" }
            val total = Math.subtractExact(subtotal, discountUsdCents)
            val rate = currentRate(db)
            val number = if (invoiceId == null) nextInvoiceNumber(db, table, type, invoiceDate) else invoiceNumber(db, table, invoiceId)
            val header = ContentValues().apply {
                put("id", resolvedId)
                put("invoice_number", number)
                if (partyId == null) putNull(partyColumn) else put(partyColumn, partyId)
                put("warehouse_id", warehouseId)
                put("invoice_date", invoiceDate)
                put("status", "DRAFT")
                put("subtotal_usd_cents", subtotal)
                put("discount_usd_cents", discountUsdCents)
                put("total_usd_cents", total)
                put("exchange_rate_syp_per_usd_milli", rate)
                if (notes == null) putNull("notes") else put("notes", notes)
                put("updated_at", now)
                if (invoiceId == null) put("created_at", now)
            }
            if (invoiceId == null) db.insertOrThrow(table, null, header)
            else {
                db.update(table, header, "id=? AND status='DRAFT'", arrayOf(resolvedId)).also {
                    require(it == 1) { "تعذر تحديث مسودة الفاتورة" }
                }
                db.delete(itemTable, if (type == "PURCHASE") "purchase_invoice_id=?" else "sales_invoice_id=?", arrayOf(resolvedId))
            }
            lines.forEach { line ->
                val item = ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put(if (type == "PURCHASE") "purchase_invoice_id" else "sales_invoice_id", resolvedId)
                    put("product_id", line.productId)
                    put("quantity", line.quantity.toDouble())
                    if (type == "PURCHASE") {
                        put("unit_cost_usd_cents", line.unitPriceUsdCents)
                    } else {
                        put("unit_price_usd_cents", line.unitPriceUsdCents)
                        put("unit_cost_usd_cents", productCost(db, line.productId))
                    }
                    put("line_total_usd_cents", lineTotal(line))
                }
                db.insertOrThrow(itemTable, null, item)
            }
            registerOperation(db, operationId, "SAVE_${type}_DRAFT", payloadHash, resolvedId, now)
            db.setTransactionSuccessful()
            return InvoiceSaveResult(resolvedId, number, false)
        } finally {
            db.endTransaction()
        }
    }

    fun postPurchase(invoiceId: String, operationId: String): Boolean =
        postInvoice("PURCHASE", invoiceId, operationId)

    fun postSale(invoiceId: String, operationId: String): Boolean =
        postInvoice("SALE", invoiceId, operationId)

    private fun postInvoice(type: String, invoiceId: String, operationId: String): Boolean {
        require(invoiceId.isNotBlank() && operationId.isNotBlank()) { "بيانات اعتماد الفاتورة غير مكتملة" }
        val db = database.writableDatabase
        val table = if (type == "PURCHASE") "purchase_invoices" else "sales_invoices"
        val itemTable = if (type == "PURCHASE") "purchase_items" else "sales_items"
        val invoiceForeignKey = if (type == "PURCHASE") "purchase_invoice_id" else "sales_invoice_id"
        val operationType = "POST_${type}_INVOICE"
        val payloadHash = hash("$type|$invoiceId")
        db.beginTransaction()
        try {
            findOperation(db, operationId)?.let { existing ->
                require(existing.first == payloadHash && existing.second == invoiceId) {
                    "معرّف الاعتماد مستخدم لعملية مختلفة"
                }
                return false
            }
            val status = invoiceStatus(db, table, invoiceId)
                ?: throw IllegalArgumentException("الفاتورة غير موجودة")
            if (status == "POSTED" || status == "PARTIALLY_PAID" || status == "PAID") {
                // An already-posted invoice is not posted again, even with a new retry token.
                registerOperation(db, operationId, operationType, payloadHash, invoiceId, timestamp())
                db.setTransactionSuccessful()
                return false
            }
            require(status == "DRAFT") { "لا يمكن اعتماد فاتورة بهذه الحالة: $status" }
            val warehouseId = invoiceWarehouse(db, table, invoiceId)
            val now = timestamp()
            db.rawQuery(
                "SELECT product_id,quantity,${if (type == "PURCHASE") "unit_cost_usd_cents" else "unit_cost_usd_cents"} FROM $itemTable WHERE $invoiceForeignKey=?",
                arrayOf(invoiceId)
            ).use { cursor ->
                require(cursor.count > 0) { "لا يمكن اعتماد فاتورة فارغة" }
                while (cursor.moveToNext()) {
                    val productId = cursor.getString(0)
                    val quantity = cursor.getDouble(1)
                    val unitCost = cursor.getLong(2)
                    val tracks = productTracksInventory(db, productId)
                    if (type == "SALE" && tracks) {
                        val available = stockQuantity(db, productId, warehouseId)
                        require(quantity <= available + 0.0000001) {
                            "المخزون غير كافٍ للمنتج: ${productName(db, productId)} (المتوفر: $available)"
                        }
                    }
                    if (tracks) {
                        val delta = if (type == "PURCHASE") quantity else -quantity
                        applyStockMovement(
                            db, productId, warehouseId, delta, unitCost,
                            if (type == "PURCHASE") "PURCHASE" else "SALE",
                            invoiceId, "$operationId:$productId", now
                        )
                    }
                    if (type == "PURCHASE") {
                        db.execSQL(
                            "UPDATE products SET purchase_cost_usd_cents=?,updated_at=? WHERE id=?",
                            arrayOf(unitCost, now, productId)
                        )
                    }
                }
            }
            db.execSQL("UPDATE $table SET status='POSTED',updated_at=? WHERE id=? AND status='DRAFT'", arrayOf(now, invoiceId))
            registerOperation(db, operationId, operationType, payloadHash, invoiceId, now)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun listPurchaseInvoices(): List<InvoiceSummary> = listInvoices("PURCHASE")
    fun listSalesInvoices(): List<InvoiceSummary> = listInvoices("SALE")

    private fun listInvoices(type: String): List<InvoiceSummary> {
        val table = if (type == "PURCHASE") "purchase_invoices" else "sales_invoices"
        val partyTable = if (type == "PURCHASE") "suppliers" else "customers"
        val partyColumn = if (type == "PURCHASE") "supplier_id" else "customer_id"
        val result = mutableListOf<InvoiceSummary>()
        database.readableDatabase.rawQuery(
            """SELECT i.id,i.invoice_number,i.invoice_date,i.status,i.total_usd_cents,p.name
               FROM $table i LEFT JOIN $partyTable p ON p.id=i.$partyColumn
               ORDER BY i.created_at DESC""".trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) result.add(
                InvoiceSummary(c.getString(0),c.getString(1),c.getString(2),c.getString(3),
                    c.getLong(4),if (c.isNull(5)) null else c.getString(5))
            )
        }
        return result
    }

    private fun validateLines(lines: List<InvoiceLineInput>) {
        require(lines.isNotEmpty()) { "أضف صنفاً واحداً على الأقل إلى الفاتورة" }
        lines.forEach {
            require(it.productId.isNotBlank()) { "يوجد صنف غير محدد" }
            require(it.quantity.signum() > 0) { "الكمية يجب أن تكون أكبر من صفر" }
            require(it.unitPriceUsdCents >= 0L) { "السعر لا يمكن أن يكون سالباً" }
            require(it.quantity.scale() <= 6) { "دقة الكمية أكبر من المسموح" }
        }
    }

    private fun lineTotal(line: InvoiceLineInput): Long =
        line.quantity.multiply(BigDecimal.valueOf(line.unitPriceUsdCents))
            .setScale(0, RoundingMode.HALF_UP).longValueExact()

    private fun productCost(db: SQLiteDatabase, productId: String): Long =
        db.rawQuery("SELECT purchase_cost_usd_cents FROM products WHERE id=?", arrayOf(productId)).use {
            require(it.moveToFirst()) { "المنتج غير موجود: $productId" }; it.getLong(0)
        }

    private fun productTracksInventory(db: SQLiteDatabase, productId: String): Boolean =
        db.rawQuery("SELECT track_inventory FROM products WHERE id=?", arrayOf(productId)).use {
            require(it.moveToFirst()) { "المنتج غير موجود: $productId" }; it.getInt(0) == 1
        }

    private fun productName(db: SQLiteDatabase, productId: String): String =
        db.rawQuery("SELECT name FROM products WHERE id=?", arrayOf(productId)).use {
            if (it.moveToFirst()) it.getString(0) else productId
        }

    private fun stockQuantity(db: SQLiteDatabase, productId: String, warehouseId: String): Double =
        db.rawQuery("SELECT quantity FROM inventory_balances WHERE product_id=? AND warehouse_id=?",
            arrayOf(productId, warehouseId)).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }

    private fun applyStockMovement(
        db: SQLiteDatabase, productId: String, warehouseId: String, delta: Double,
        unitCost: Long, movementType: String, invoiceId: String, movementOperationId: String, now: String
    ) {
        db.execSQL(
            """INSERT INTO inventory_balances(product_id,warehouse_id,quantity,updated_at)
               VALUES(?,?,?,?)
               ON CONFLICT(product_id,warehouse_id) DO UPDATE SET
               quantity=inventory_balances.quantity+excluded.quantity,updated_at=excluded.updated_at""",
            arrayOf(productId, warehouseId, delta, now)
        )
        val movement = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("product_id", productId)
            put("warehouse_id", warehouseId)
            put("movement_type", movementType)
            put("quantity_delta", delta)
            put("unit_cost_usd_cents", unitCost)
            put("source_type", if (movementType == "PURCHASE") "PURCHASE_INVOICE" else "SALES_INVOICE")
            put("source_id", invoiceId)
            put("operation_id", movementOperationId)
            put("created_at", now)
        }
        db.insertOrThrow("inventory_movements", null, movement)
    }

    private fun defaultWarehouseId(db: SQLiteDatabase): String =
        db.rawQuery("SELECT id FROM warehouses WHERE is_default=1 AND is_active=1 LIMIT 1", null).use {
            check(it.moveToFirst()) { "المستودع الرئيسي غير مهيأ" }; it.getString(0)
        }

    private fun currentRate(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT rate_syp_per_usd_milli FROM exchange_rates ORDER BY effective_at DESC,created_at DESC LIMIT 1", null)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun invoiceStatus(db: SQLiteDatabase, table: String, id: String): String? =
        db.rawQuery("SELECT status FROM $table WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun invoiceWarehouse(db: SQLiteDatabase, table: String, id: String): String =
        db.rawQuery("SELECT warehouse_id FROM $table WHERE id=?", arrayOf(id)).use {
            require(it.moveToFirst()) { "الفاتورة غير موجودة" }; it.getString(0)
        }

    private fun invoiceNumber(db: SQLiteDatabase, table: String, id: String): String =
        db.rawQuery("SELECT invoice_number FROM $table WHERE id=?", arrayOf(id)).use {
            require(it.moveToFirst()) { "الفاتورة غير موجودة" }; it.getString(0)
        }

    private fun nextInvoiceNumber(db: SQLiteDatabase, table: String, type: String, date: String): String {
        val prefix = if (type == "PURCHASE") "PUR" else "SAL"
        val day = date.replace("-", "").take(8)
        val stem = "$prefix-$day-"
        val next = db.rawQuery("SELECT COUNT(*) FROM $table WHERE invoice_number LIKE ?", arrayOf("$stem%"))
            .use { if (it.moveToFirst()) it.getInt(0) + 1 else 1 }
        return stem + next.toString().padStart(4, '0')
    }

    private fun findOperation(db: SQLiteDatabase, id: String): Pair<String, String?>? =
        db.rawQuery("SELECT payload_hash,result_reference FROM operation_registry WHERE operation_id=?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getString(0) to (if (it.isNull(1)) null else it.getString(1)) else null }

    private fun registerOperation(
        db: SQLiteDatabase, id: String, type: String, payloadHash: String, reference: String, now: String
    ) {
        val values = ContentValues().apply {
            put("operation_id", id)
            put("operation_type", type)
            put("payload_hash", payloadHash)
            put("status", "COMPLETED")
            put("result_reference", reference)
            put("created_at", now)
            put("completed_at", now)
        }
        db.insertOrThrow("operation_registry", null, values)
    }

    private fun hashPayload(
        type: String, invoiceId: String?, partyId: String?, date: String, discount: Long,
        notes: String?, lines: List<InvoiceLineInput>
    ): String = hash(buildString {
        append(type).append('|').append(invoiceId.orEmpty()).append('|').append(partyId.orEmpty())
            .append('|').append(date).append('|').append(discount).append('|').append(notes.orEmpty())
        lines.forEach { append('|').append(it.productId).append(':').append(it.quantity.stripTrailingZeros().toPlainString())
            .append(':').append(it.unitPriceUsdCents) }
    })

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
