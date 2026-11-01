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
        val context = RuntimeEnvironment.getApplication()
        AppDatabase.resetForTests(context)
        database = AppDatabase.getInstance(context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun managerId(): String {
        val auth = AuthRepository(RuntimeEnvironment.getApplication())
        auth.ensureInitialAdmin()
        val user = auth.authenticate("admin", "1234")!!
        auth.changePassword(user.id, "1234", "TestAdmin123")
        return user.id
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
            "return_refund_allocations", "daily_cash_sessions", "customer_credit_revaluations",
            "daily_supplier_rollovers", "wallet_daily_openings", "cash_account_transfers",
            "service_wallet_account_links", "system_cash_account_roles", "operation_registry", "sync_queue",
            "sync_conflicts", "audit_log", "inventory_cost_layers", "sales_item_cost_allocations"
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
    fun upgradesVersionSevenDatabaseWithoutLosingExistingUserOrSettings() {
        val context = RuntimeEnvironment.getApplication()
        val db = database.writableDatabase
        val originalUser = ContentValues().apply {
            put("id", "legacy-admin")
            put("username", "legacy-admin")
            put("display_name", "مدير قديم")
            put("password_hash", "preserved-hash")
            put("password_salt", "preserved-salt")
            put("role_key", "ADMIN")
            put("is_active", 1)
            put("must_change_password", 0)
            put("created_at", "2026-10-01T08:00:00Z")
            put("updated_at", "2026-10-01T08:00:00Z")
        }
        db.insertOrThrow("users", null, originalUser)
        // Recreate the actual v7 users schema before lowering user_version.
        // The current onCreate schema is v8, so changing only user_version would
        // incorrectly leave the v8 column in place and make ALTER TABLE fail.
        db.execSQL("ALTER TABLE users DROP COLUMN must_change_password")
        db.execSQL("DROP INDEX idx_sales_posted_operating_date")
        db.execSQL("DROP INDEX idx_sales_posted_at")
        db.execSQL("ALTER TABLE sales_invoices DROP COLUMN posted_operating_date")
        db.execSQL("ALTER TABLE sales_invoices DROP COLUMN posted_at")
        db.execSQL("DROP TABLE sales_item_cost_allocations")
        db.execSQL("DROP TABLE inventory_cost_layers")
        db.execSQL("ALTER TABLE sales_items DROP COLUMN cogs_usd_cents")
        db.version = 7
        database.close()

        // Re-open the same on-disk database as an older installed version.
        // Do not delete it: this specifically exercises the real 7 -> 8 migration.
        AppDatabase.resetForTests(context, deleteExisting = false)
        database = AppDatabase.getInstance(context)
        val upgraded = database.writableDatabase

        assertEquals(11, upgraded.version)
        upgraded.rawQuery(
            """SELECT username,display_name,password_hash,password_salt,role_key,is_active,
                      must_change_password,created_at,updated_at
               FROM users WHERE id=?""",
            arrayOf("legacy-admin")
        ).use { cursor ->
            assertTrue("Legacy user must survive migration", cursor.moveToFirst())
            assertEquals("legacy-admin", cursor.getString(0))
            assertEquals("مدير قديم", cursor.getString(1))
            assertEquals("preserved-hash", cursor.getString(2))
            assertEquals("preserved-salt", cursor.getString(3))
            assertEquals("ADMIN", cursor.getString(4))
            assertEquals(1, cursor.getInt(5))
            assertEquals(1, cursor.getInt(6))
            assertEquals("2026-10-01T08:00:00Z", cursor.getString(7))
            assertEquals("2026-10-01T08:00:00Z", cursor.getString(8))
        }
        upgraded.rawQuery(
            "SELECT setting_value FROM app_settings WHERE setting_key='base_currency'",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("USD", cursor.getString(0))
        }
        upgraded.rawQuery("PRAGMA table_info(sales_items)", null).use { c ->
            var hasCogs = false
            while (c.moveToNext()) if (c.getString(1) == "cogs_usd_cents") hasCogs = true
            assertTrue("FIFO migration must add the sale COGS snapshot", hasCogs)
        }
        upgraded.rawQuery("PRAGMA table_info(sales_invoices)", null).use { c ->
            var hasPostedAt = false
            while (c.moveToNext()) if (c.getString(1) == "posted_at") hasPostedAt = true
            assertTrue("Posting timestamp migration must add posted_at", hasPostedAt)
        }
        upgraded.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name IN ('idx_sales_posted_at','idx_sales_posted_operating_date')",
            null
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0)) }
        upgraded.rawQuery("PRAGMA table_info(sales_invoices)", null).use { c ->
            var hasOperatingDate = false
            while (c.moveToNext()) if (c.getString(1) == "posted_operating_date") hasOperatingDate = true
            assertTrue("Migration must add the local operating date", hasOperatingDate)
        }
        upgraded.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('inventory_cost_layers','sales_item_cost_allocations')",
            null
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0)) }
        assertTrue("Migrated database must pass SQLite integrity check", upgraded.isDatabaseIntegrityOk)
        upgraded.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            assertTrue("Migration must not introduce foreign-key violations", !cursor.moveToFirst())
        }
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
               WHERE name IN ('قطعة','كيلوغرام','غرام','لتر','علبة','ربطة','كرتونة','طرد')""",
            null
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(8, c.getInt(0))
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
        val actor = managerId()
        val db = database.writableDatabase
        try {
            repository.saveExchangeRate(BigDecimal("15000"), actor)
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
        val actor = managerId()
        val id = repository.saveProduct(
            null, "منتج اختباري", "123456789", null, category.id, unit.id,
            125, 175, true, 8.0, actor
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
