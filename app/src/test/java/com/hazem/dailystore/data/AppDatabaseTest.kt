package com.hazem.dailystore.data

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
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
            put("name", "قطعة")
            put("symbol", "pcs")
            put("decimal_places", 0)
        }
        val unitId = db.insertOrThrow("units", null, unit)

        val category = ContentValues().apply {
            put("name", "مواد غذائية")
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
            put("name", "المستودع الرئيسي")
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
}
