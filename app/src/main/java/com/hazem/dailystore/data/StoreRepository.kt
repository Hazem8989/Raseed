package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
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
                Triple("كرتونة", "كرتونة", 0)
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

    fun listProducts(): List<StoreProduct> {
        val result = mutableListOf<StoreProduct>()
        helper.readableDatabase.rawQuery(
            """SELECT p.id,p.name,p.barcode,u.name,u.symbol,c.name,
                      p.purchase_cost_usd_cents,p.sale_price_usd_cents,p.track_inventory,
                      COALESCE((SELECT SUM(ib.quantity) FROM inventory_balances ib
                                WHERE ib.product_id=p.id),0)
               FROM products p JOIN units u ON u.id=p.unit_id
               LEFT JOIN categories c ON c.id=p.category_id
               WHERE p.is_active=1 ORDER BY p.name COLLATE NOCASE""".trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) result.add(
                StoreProduct(c.getString(0),c.getString(1),if(c.isNull(2)) null else c.getString(2),
                    c.getString(3),c.getString(4),if(c.isNull(5)) null else c.getString(5),
                    c.getLong(6),c.getLong(7),c.getInt(8)==1,c.getDouble(9))
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
