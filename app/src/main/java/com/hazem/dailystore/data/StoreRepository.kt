package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class StoreUnit(val id: Long, val name: String, val symbol: String, val decimalPlaces: Int)
data class StoreCategory(val id: Long, val name: String)
data class StoreProduct(
    val id: String,
    val name: String,
    val barcode: String?,
    val sku: String?,
    val unitName: String,
    val unitSymbol: String,
    val categoryName: String?,
    val purchaseCostUsdCents: Long,
    val salePriceUsdCents: Long,
    val tracksInventory: Boolean,
    val stock: Double
)

/**
 * Local data access for the store's setup and product catalog.
 * Seeding is idempotent and deliberately does not invent an exchange rate,
 * product, or opening cash balance.
 */
class StoreRepository(context: Context) {
    private val helper = AppDatabase.getInstance(context)

    fun ensureBaseSetup() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val now = timestamp()
            val units = listOf(
                Triple("قطعة", "قطعة", 0),
                Triple("كيلوغرام", "كغ", 3),
                Triple("غرام", "غ", 0),
                Triple("لتر", "ل", 3),
                Triple("علبة", "علبة", 0),
                Triple("ربطة", "ربطة", 0),
                Triple("كرتونة", "كرتونة", 0),
                Triple("طرد", "طرد", 0)
            )
            units.forEach { (name, symbol, decimals) ->
                db.execSQL(
                    "INSERT OR IGNORE INTO units(name,symbol,decimal_places) VALUES(?,?,?)",
                    arrayOf(name, symbol, decimals)
                )
            }
            val categories = listOf("مواد غذائية", "مشروبات", "منظفات", "عناية شخصية", "أخرى")
            categories.forEach { name ->
                db.execSQL(
                    "INSERT OR IGNORE INTO categories(name,created_at) VALUES(?,?)",
                    arrayOf(name, now)
                )
            }
            db.execSQL(
                """INSERT OR IGNORE INTO warehouses(id,name,is_default,is_active,created_at)
                   VALUES('default-warehouse','المستودع الرئيسي',1,1,?)""",
                arrayOf(now)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getUnits(): List<StoreUnit> {
        val result = mutableListOf<StoreUnit>()
        helper.readableDatabase.rawQuery(
            "SELECT id,name,symbol,decimal_places FROM units ORDER BY id", null
        ).use { c ->
            while (c.moveToNext()) result.add(StoreUnit(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3)))
        }
        return result
    }

    fun getCategories(): List<StoreCategory> {
        val result = mutableListOf<StoreCategory>()
        helper.readableDatabase.rawQuery(
            "SELECT id,name FROM categories WHERE is_active=1 ORDER BY name COLLATE NOCASE", null
        ).use { c ->
            while (c.moveToNext()) result.add(StoreCategory(c.getLong(0), c.getString(1)))
        }
        return result
    }

    fun getCurrentExchangeRateMilli(): Long =
        helper.readableDatabase.rawQuery(
            "SELECT rate_syp_per_usd_milli FROM exchange_rates ORDER BY effective_at DESC, created_at DESC LIMIT 1", null
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    fun saveExchangeRate(rateSypPerUsd: BigDecimal): Long {
        require(rateSypPerUsd.signum() > 0) { "سعر الصرف يجب أن يكون أكبر من صفر" }
        val milli = rateSypPerUsd.multiply(BigDecimal.valueOf(1000))
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
        require(milli > 0L) { "سعر الصرف غير صالح" }
        val now = timestamp()
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("currency_code", "USD")
            put("rate_syp_per_usd_milli", milli)
            put("effective_at", now)
            put("created_at", now)
        }
        helper.writableDatabase.insertOrThrow("exchange_rates", null, values)
        return milli
    }

    fun addCategory(name: String): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "اسم التصنيف مطلوب" }
        val values = ContentValues().apply {
            put("name", clean)
            put("created_at", timestamp())
        }
        return helper.writableDatabase.insertOrThrow("categories", null, values)
    }

    @Throws(SQLiteConstraintException::class, IllegalArgumentException::class)
    fun saveProduct(
        id: String?,
        name: String,
        barcode: String?,
        sku: String?,
        categoryId: Long?,
        unitId: Long,
        purchaseCostUsdCents: Long,
        salePriceUsdCents: Long,
        tracksInventory: Boolean,
        openingQuantity: Double
    ): String {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "اسم المنتج مطلوب" }
        require(purchaseCostUsdCents >= 0 && salePriceUsdCents >= 0) { "السعر غير صالح" }
        require(openingQuantity >= 0.0) { "الكمية الافتتاحية غير صالحة" }
        val db = helper.writableDatabase
        val productId = id ?: UUID.randomUUID().toString()
        val now = timestamp()
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", productId)
                put("name", cleanName)
                putNullable("barcode", barcode?.trim()?.takeIf { it.isNotEmpty() })
                putNullable("sku", sku?.trim()?.takeIf { it.isNotEmpty() })
                if (categoryId == null) putNull("category_id") else put("category_id", categoryId)
                put("unit_id", unitId)
                put("purchase_cost_usd_cents", purchaseCostUsdCents)
                put("sale_price_usd_cents", salePriceUsdCents)
                put("track_inventory", if (tracksInventory) 1 else 0)
                put("updated_at", now)
                if (id == null) put("created_at", now)
            }
            val existing = id != null
            if (existing) {
                val changed = db.update("products", values, "id=?", arrayOf(productId))
                require(changed == 1) { "المنتج المطلوب تعديله غير موجود" }
            } else {
                db.insertOrThrow("products", null, values)
                if (tracksInventory && openingQuantity > 0.0) {
                    val warehouseId = db.rawQuery(
                        "SELECT id FROM warehouses WHERE is_default=1 AND is_active=1 LIMIT 1", null
                    ).use { c -> check(c.moveToFirst()) { "المستودع الرئيسي غير مهيأ" }; c.getString(0) }
                    val balance = ContentValues().apply {
                        put("product_id", productId)
                        put("warehouse_id", warehouseId)
                        put("quantity", openingQuantity)
                        put("updated_at", now)
                    }
                    db.insertOrThrow("inventory_balances", null, balance)
                    val movement = ContentValues().apply {
                        put("id", UUID.randomUUID().toString())
                        put("product_id", productId)
                        put("warehouse_id", warehouseId)
                        put("movement_type", "OPENING")
                        put("quantity_delta", openingQuantity)
                        put("unit_cost_usd_cents", purchaseCostUsdCents)
                        put("source_type", "PRODUCT_SETUP")
                        put("source_id", productId)
                        put("note", "رصيد افتتاحي عند إنشاء المنتج")
                        put("created_at", now)
                    }
                    db.insertOrThrow("inventory_movements", null, movement)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return productId
    }

    /**
     * Imports a catalog atomically. Imported stock is always zero; opening stock
     * must be entered later through a counted stock-opening operation.
     */
    fun importProducts(rows: List<ProductImportRow>, exchangeRateMilli: Long, actorUserId: String): Int {
        require(actorUserId.isNotBlank()) { "جلسة المستخدم غير صالحة لتسجيل عملية الاستيراد" }
        require(rows.isNotEmpty()) { "لا توجد أصناف للاستيراد" }
        if (rows.any { it.usesSypPrices }) {
            require(exchangeRateMilli > 0L) {
                "الملف يحتوي أسعاراً بالليرة السورية؛ سجّل سعر الصرف أولاً ولا يمكن تخمين التحويل"
            }
        }
        val normalizedNames = mutableSetOf<String>()
        val normalizedSkus = mutableSetOf<String>()
        val normalizedBarcodes = mutableSetOf<String>()
        rows.forEach { row ->
            val nameKey = row.name.trim().lowercase(Locale.ROOT)
            require(normalizedNames.add(nameKey)) { "اسم صنف مكرر داخل الملف: ${row.name} (الصف ${row.rowNumber})" }
            row.sku?.trim()?.takeIf { it.isNotEmpty() }?.let {
                require(normalizedSkus.add(it.lowercase(Locale.ROOT))) { "رمز SKU مكرر داخل الملف: $it" }
            }
            row.barcode?.trim()?.takeIf { it.isNotEmpty() }?.let {
                require(normalizedBarcodes.add(it)) { "باركود مكرر داخل الملف: $it" }
            }
            require(row.salePriceSyp != null || row.salePriceUsdCents != null) {
                "سعر البيع مفقود في الصف ${row.rowNumber}"
            }
        }

        val db = helper.writableDatabase
        val now = timestamp()
        db.beginTransaction()
        try {
            rows.forEach { row ->
                val cleanName = row.name.trim()
                val existsName = db.rawQuery(
                    "SELECT 1 FROM products WHERE name=? COLLATE NOCASE LIMIT 1",
                    arrayOf(cleanName)
                ).use { it.moveToFirst() }
                require(!existsName) { "الصنف موجود مسبقاً: $cleanName؛ أوقف الاستيراد لتجنب إنشاء تكرار" }

                val sku = row.sku?.trim()?.takeIf { it.isNotEmpty() }
                if (sku != null) {
                    val exists = db.rawQuery("SELECT 1 FROM products WHERE sku=? LIMIT 1", arrayOf(sku)).use { it.moveToFirst() }
                    require(!exists) { "رمز SKU مستخدم مسبقاً: $sku" }
                }
                val barcode = row.barcode?.trim()?.takeIf { it.isNotEmpty() }
                if (barcode != null) {
                    val exists = db.rawQuery("SELECT 1 FROM products WHERE barcode=? LIMIT 1", arrayOf(barcode)).use { it.moveToFirst() }
                    require(!exists) { "الباركود مستخدم مسبقاً: $barcode" }
                }

                val categoryName = row.category?.trim()?.takeIf { it.isNotEmpty() } ?: "غير مصنف"
                val categoryId = db.rawQuery(
                    "SELECT id FROM categories WHERE name=? COLLATE NOCASE LIMIT 1",
                    arrayOf(categoryName)
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else {
                        val categoryValues = ContentValues().apply {
                            put("name", categoryName)
                            put("created_at", now)
                        }
                        db.insertOrThrow("categories", null, categoryValues)
                    }
                }

                val unitName = row.unit?.trim()?.takeIf { it.isNotEmpty() } ?: "قطعة"
                val unitId = resolveImportUnit(db, unitName)
                val costUsd = when {
                    row.purchaseCostUsdCents != null -> row.purchaseCostUsdCents
                    row.purchaseCostSyp != null -> CurrencyConverter.sypToUsdCents(row.purchaseCostSyp, exchangeRateMilli)
                    else -> 0L
                }
                val saleUsd = when {
                    row.salePriceUsdCents != null -> row.salePriceUsdCents
                    row.salePriceSyp != null -> CurrencyConverter.sypToUsdCents(row.salePriceSyp, exchangeRateMilli)
                    else -> 0L
                }
                require(costUsd >= 0L && saleUsd > 0L) { "تكلفة أو سعر بيع غير صالح للصنف: $cleanName" }

                val product = ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    putNullable("barcode", barcode)
                    putNullable("sku", sku)
                    put("name", cleanName)
                    put("category_id", categoryId)
                    put("unit_id", unitId)
                    put("purchase_cost_usd_cents", costUsd)
                    put("sale_price_usd_cents", saleUsd)
                    put("track_inventory", 1)
                    put("created_at", now)
                    put("updated_at", now)
                }
                db.insertOrThrow("products", null, product)
                // No stock is imported. The user will enter a verified physical count later.
            }
            val batchId = UUID.randomUUID().toString()
            val sourceCurrency = if (rows.any { it.usesSypPrices }) "SYP_CONVERTED_TO_USD" else "USD_REFERENCE"
            val audit = ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("user_id", actorUserId)
                put("action", "PRODUCTS_IMPORTED")
                put("entity_type", "PRODUCT_IMPORT_BATCH")
                put("entity_id", batchId)
                put("after_json", """{"count":${rows.size},"source_currency":"$sourceCurrency","exchange_rate_syp_per_usd_milli":$exchangeRateMilli,"opening_stock":"ZERO"}""")
                put("created_at", now)
            }
            db.insertOrThrow("audit_log", null, audit)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return rows.size
    }

    private fun resolveImportUnit(db: android.database.sqlite.SQLiteDatabase, rawName: String): Long {
        val normalized = rawName.trim().lowercase(Locale.ROOT)
        val aliases = when (normalized) {
            "كغ", "kg", "كيلو", "كيلوغرام", "كيلوجرام" -> listOf("كيلوغرام", "كغ")
            "غ", "g", "غرام", "جرام" -> listOf("غرام", "غ")
            "ل", "l", "liter", "litre", "لتر" -> listOf("لتر", "ل")
            "piece", "pcs", "pc", "قطعة" -> listOf("قطعة")
            "box", "علبة" -> listOf("علبة")
            "bundle", "ربطة" -> listOf("ربطة")
            "carton", "كرتونة" -> listOf("كرتونة")
            "pack", "طرد" -> listOf("طرد")
            else -> listOf(rawName.trim())
        }
        for (candidate in aliases) {
            db.rawQuery("SELECT id FROM units WHERE name=? COLLATE NOCASE OR symbol=? COLLATE NOCASE LIMIT 1",
                arrayOf(candidate, candidate)).use { if (it.moveToFirst()) return it.getLong(0) }
        }
        error("وحدة القياس غير معروفة: $rawName؛ أضفها من إعدادات الوحدات ثم أعد الاستيراد")
    }

    /**
     * Records the first verified physical count for an imported/new item.
     * It is intentionally one-time: subsequent stock changes must be posted as
     * purchases, sales, returns or explicit stock adjustments, never overwritten.
     */
    fun recordOpeningStock(productId: String, quantity: Double, actorUserId: String, note: String?): Unit {
        require(productId.isNotBlank() && actorUserId.isNotBlank()) { "بيانات العملية غير مكتملة" }
        require(quantity > 0.0 && quantity.isFinite()) { "كمية الجرد يجب أن تكون أكبر من صفر" }
        val db = helper.writableDatabase
        val now = timestamp()
        db.beginTransaction()
        try {
            val product = db.rawQuery(
                """SELECT p.track_inventory,p.purchase_cost_usd_cents,u.decimal_places
                   FROM products p JOIN units u ON u.id=p.unit_id WHERE p.id=?""".trimIndent(),
                arrayOf(productId)
            ).use { cursor ->
                require(cursor.moveToFirst()) { "المنتج غير موجود" }
                Triple(cursor.getInt(0) == 1, cursor.getLong(1), cursor.getInt(2))
            }
            require(product.first) { "هذا المنتج لا يتتبع المخزون" }
            require(product.second > 0L) {
                "تكلفة شراء المنتج غير مسجلة؛ حدّث تكلفة الشراء أولاً حتى لا يدخل المخزون في رأس المال بقيمة مجهولة"
            }
            val precision = BigDecimal.valueOf(quantity).stripTrailingZeros().scale().coerceAtLeast(0)
            require(precision <= product.third) { "دقة الكمية تتجاوز المنازل العشرية المسموحة لوحدة القياس" }
            val priorMovement = db.rawQuery(
                "SELECT 1 FROM inventory_movements WHERE product_id=? LIMIT 1",
                arrayOf(productId)
            ).use { it.moveToFirst() }
            require(!priorMovement) {
                "يوجد سجل حركة سابق لهذا المنتج؛ لا يجوز إنشاء رصيد افتتاحي جديد فوق سجل قائم. استخدم تسوية جرد مستقلة."
            }
            val warehouseId = db.rawQuery(
                "SELECT id FROM warehouses WHERE is_default=1 AND is_active=1 LIMIT 1", null
            ).use { cursor -> require(cursor.moveToFirst()) { "المستودع الرئيسي غير مهيأ" }; cursor.getString(0) }
            val hasBalance = db.rawQuery(
                "SELECT 1 FROM inventory_balances WHERE product_id=? AND warehouse_id=?",
                arrayOf(productId, warehouseId)
            ).use { it.moveToFirst() }
            if (hasBalance) {
                db.execSQL(
                    "UPDATE inventory_balances SET quantity=?,updated_at=? WHERE product_id=? AND warehouse_id=?",
                    arrayOf(quantity, now, productId, warehouseId)
                )
            } else {
                val balance = ContentValues().apply {
                    put("product_id", productId)
                    put("warehouse_id", warehouseId)
                    put("quantity", quantity)
                    put("updated_at", now)
                }
                db.insertOrThrow("inventory_balances", null, balance)
            }
            val movement = ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("product_id", productId)
                put("warehouse_id", warehouseId)
                put("movement_type", "OPENING")
                put("quantity_delta", quantity)
                put("unit_cost_usd_cents", product.second)
                put("source_type", "STOCKTAKE_OPENING")
                put("source_id", productId)
                put("operation_id", "OPENING_STOCK:$productId")
                put("note", note?.trim()?.takeIf { it.isNotEmpty() } ?: "رصيد افتتاحي ناتج عن جرد فعلي")
                put("created_by", actorUserId)
                put("created_at", now)
            }
            db.insertOrThrow("inventory_movements", null, movement)
            val audit = ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("user_id", actorUserId)
                put("action", "OPENING_STOCK_RECORDED")
                put("entity_type", "PRODUCT")
                put("entity_id", productId)
                put("after_json", """{"quantity":$quantity,"unit_cost_usd_cents":${product.second},"valuation":"FIFO"}""")
                put("created_at", now)
            }
            db.insertOrThrow("audit_log", null, audit)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun canRecordOpeningStock(productId: String): Boolean =
        helper.readableDatabase.rawQuery(
            """SELECT 1 FROM products p
               WHERE p.id=? AND p.track_inventory=1
                 AND NOT EXISTS (SELECT 1 FROM inventory_movements m WHERE m.product_id=p.id)
            """.trimIndent(),
            arrayOf(productId)
        ).use { it.moveToFirst() }

    fun setProductActive(id: String, active: Boolean) {
        val values = ContentValues().apply {
            put("is_active", if (active) 1 else 0)
            put("updated_at", timestamp())
        }
        val changed = helper.writableDatabase.update("products", values, "id=?", arrayOf(id))
        require(changed == 1) { "المنتج المطلوب غير موجود" }
    }

    fun listProducts(): List<StoreProduct> {
        val result = mutableListOf<StoreProduct>()
        helper.readableDatabase.rawQuery(
            """SELECT p.id,p.name,p.barcode,p.sku,u.name,u.symbol,c.name,
                      p.purchase_cost_usd_cents,p.sale_price_usd_cents,p.track_inventory,
                      COALESCE((SELECT SUM(ib.quantity) FROM inventory_balances ib
                                WHERE ib.product_id=p.id),0)
               FROM products p JOIN units u ON u.id=p.unit_id
               LEFT JOIN categories c ON c.id=p.category_id
               WHERE p.is_active=1 ORDER BY p.name COLLATE NOCASE""".trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) result.add(
                StoreProduct(c.getString(0),c.getString(1),if(c.isNull(2)) null else c.getString(2),
                    if(c.isNull(3)) null else c.getString(3),c.getString(4),c.getString(5),
                    if(c.isNull(6)) null else c.getString(6),c.getLong(7),c.getLong(8),c.getInt(9)==1,c.getDouble(10))
            )
        }
        return result
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
}
