package com.hazem.dailystore.data

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import java.math.BigDecimal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppDatabaseTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = AppDatabase.getInstance(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createsCoreTablesAndDefaultCurrencySettings() {
        val db = database.writableDatabase
        val requiredTables = listOf(
            "app_settings", "users", "permissions", "role_permissions",
            "categories", "units", "products", "warehouses",
            "inventory_balances", "inventory_movements", "exchange_rates",
            "customers", "suppliers", "purchase_invoices", "purchase_items",
            "sales_invoices", "sales_items", "cash_accounts", "cash_transactions",
            "invoice_payments", "debts", "returns", "return_items",
            "return_refund_allocations", "operation_registry", "sync_queue",
            "sync_conflicts", "audit_log"
        )

        requiredTables.forEach { table ->
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table)
            ).use { cursor -> assertTrue("Missing table: $table", cursor.moveToFirst()) }
        }

        db.rawQuery(
            "SELECT setting_value FROM app_settings WHERE setting_key='base_currency'",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("USD", cursor.getString(0))
        }
        db.rawQuery(
            "SELECT setting_value FROM app_settings WHERE setting_key='store_currency'",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("SYP", cursor.getString(0))
        }
        assertTrue(db.isDatabaseIntegrityOk)
    }

    @Test
    fun storesProductAndInventoryAndEnforcesForeignKeys() {
        val db = database.writableDatabase

        val unit = ContentValues().apply {
            put("name", "قطعة اختبار FK")
            put("symbol", "pcs")
            put("decimal_places", 0)
        }
        val unitId = db.insertOrThrow("units", null, unit)

        val category = ContentValues().apply {
            put("name", "تصنيف اختبار FK")
            put("created_at", "2026-10-30T00:00:00Z")
        }
        val categoryId = db.insertOrThrow("categories", null, category)

        val product = ContentValues().apply {
            put("id", "product-1")
            put("name", "أرز")
            put("unit_id", unitId)
            put("category_id", categoryId)
            put("purchase_cost_usd_cents", 125)
            put("sale_price_usd_cents", 175)
            put("created_at", "2026-10-30T00:00:00Z")
            put("updated_at", "2026-10-30T00:00:00Z")
        }
        db.insertOrThrow("products", null, product)

        val warehouse = ContentValues().apply {
            put("id", "warehouse-1")
            put("name", "مستودع اختبار FK")
            put("created_at", "2026-10-30T00:00:00Z")
        }
        db.insertOrThrow("warehouses", null, warehouse)

        val balance = ContentValues().apply {
            put("product_id", "product-1")
            put("warehouse_id", "warehouse-1")
            put("quantity", 12.0)
            put("updated_at", "2026-10-30T00:00:00Z")
        }
        db.insertOrThrow("inventory_balances", null, balance)

        db.rawQuery(
            "SELECT quantity FROM inventory_balances WHERE product_id=? AND warehouse_id=?",
            arrayOf("product-1", "warehouse-1")
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(12.0, cursor.getDouble(0), 0.000001)
        }

        val invalidProduct = ContentValues().apply {
            put("id", "invalid-product")
            put("name", "منتج بلا وحدة")
            put("unit_id", -999)
            put("created_at", "2026-10-30T00:00:00Z")
            put("updated_at", "2026-10-30T00:00:00Z")
        }
        try {
            db.insertOrThrow("products", null, invalidProduct)
            throw AssertionError("Foreign key constraint was not enforced")
        } catch (_: SQLiteConstraintException) {
            // Expected: a product must reference an existing unit.
        }
    }

    @Test
    fun baseSetupIsIdempotentAndCreatesDefaultWarehouseWithoutInventedRate() {
        val repository = StoreRepository(RuntimeEnvironment.getApplication())
        repository.ensureBaseSetup()
        repository.ensureBaseSetup()
        val db = database.writableDatabase
        db.rawQuery(
            """SELECT COUNT(*) FROM units
               WHERE name IN ('قطعة','كيلوغرام','غرام','لتر','علبة','ربطة','كرتونة')""",
            null
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(7, c.getInt(0))
        }
        db.rawQuery("SELECT COUNT(*) FROM warehouses WHERE is_default=1 AND is_active=1", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.rawQuery("SELECT COUNT(*) FROM exchange_rates", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun savesExchangeRateAndConvertsBothCurrencies() {
        val repository = StoreRepository(RuntimeEnvironment.getApplication())
        repository.ensureBaseSetup()
        val db = database.writableDatabase
        try {
            repository.saveExchangeRate(BigDecimal("15000"))
            assertEquals(15_000_000L, repository.getCurrentExchangeRateMilli())
            assertEquals(200L, CurrencyConverter.sypToUsdCents(30_000L, repository.getCurrentExchangeRateMilli()))
            assertEquals(30_000L, CurrencyConverter.usdCentsToSyp(200L, repository.getCurrentExchangeRateMilli()))
        } finally {
            db.delete("exchange_rates", null, null)
        }
    }

    @Test
    fun savesProductAndOpeningStockAtomically() {
        val repository = StoreRepository(RuntimeEnvironment.getApplication())
        repository.ensureBaseSetup()
        val unit = repository.getUnits().first()
        val category = repository.getCategories().first()
        val id = repository.saveProduct(
            null, "منتج اختباري", "123456789", null, category.id, unit.id,
            125, 175, true, 8.0
        )
        val product = repository.listProducts().single { it.id == id }
        assertEquals("منتج اختباري", product.name)
        assertEquals(125L, product.purchaseCostUsdCents)
        assertEquals(175L, product.salePriceUsdCents)
        assertEquals(8.0, product.stock, 0.000001)
        database.readableDatabase.rawQuery(
            "SELECT movement_type,quantity_delta FROM inventory_movements WHERE product_id=?",
            arrayOf(id)
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("OPENING", c.getString(0))
            assertEquals(8.0, c.getDouble(1), 0.000001)
        }
    }
}
