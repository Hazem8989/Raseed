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
            "sync_conflicts", "audit_log", "inventory_cost_layers", "sales_item_cost_allocations", "inventory_adjustments"
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
    fun upgradesEverySupportedHistoricalDatabaseVersionToCurrentSchema() {
        val context = RuntimeEnvironment.getApplication()
        for (targetVersion in 1 until AppDatabase.DATABASE_VERSION) {
            AppDatabase.resetForTests(context)
            database = AppDatabase.getInstance(context)
            val db = database.writableDatabase
            db.insertOrThrow("users", null, ContentValues().apply {
                put("id", "legacy-user-$targetVersion")
                put("username", "legacy$targetVersion")
                put("display_name", "مستخدم قديم")
                put("password_hash", "preserved-hash")
                put("password_salt", "preserved-salt")
                put("role_key", "ADMIN")
                put("is_active", 1)
                put("must_change_password", 0)
                put("created_at", "2026-10-01T08:00:00Z")
                put("updated_at", "2026-10-01T08:00:00Z")
            })
            downgradeSchemaForMigrationTest(db, targetVersion)
            db.version = targetVersion
            database.close()

            AppDatabase.resetForTests(context, deleteExisting = false)
            database = AppDatabase.getInstance(context)
            val upgraded = database.writableDatabase
            assertEquals("Upgrade from version $targetVersion", AppDatabase.DATABASE_VERSION, upgraded.version)
            upgraded.rawQuery(
                "SELECT username,must_change_password FROM users WHERE id=?",
                arrayOf("legacy-user-$targetVersion")
            ).use { cursor ->
                assertTrue("Legacy user must survive upgrade from $targetVersion", cursor.moveToFirst())
                assertEquals("legacy$targetVersion", cursor.getString(0))
                assertEquals(if (targetVersion < 8) 1 else 0, cursor.getInt(1))
            }
            listOf(
                "daily_cash_sessions", "customer_credit_revaluations", "daily_supplier_rollovers",
                "wallet_daily_openings", "cash_account_transfers", "service_wallets",
                "service_wallet_transactions", "service_transfers", "service_wallet_account_links",
                "system_cash_account_roles", "inventory_cost_layers", "sales_item_cost_allocations",
                "return_cost_allocations", "inventory_adjustments", "supplier_return_refund_allocations",
                "supplier_return_cost_allocations", "expense_categories", "operating_expenses", "sync_devices", "sync_pairing_invitations",
                "sync_protocol_state", "sync_operation_envelopes", "sync_inbox",
                "sync_acknowledgements", "sync_cursors"
            ).forEach { table ->
                upgraded.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                    arrayOf(table)
                ).use { cursor ->
                    assertTrue("Migration from $targetVersion must create $table", cursor.moveToFirst())
                }
            }
            assertTrue("Integrity check failed after upgrade from $targetVersion", upgraded.isDatabaseIntegrityOk)
            upgraded.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                assertTrue("Foreign-key check failed after upgrade from $targetVersion", !cursor.moveToFirst())
            }
            database.close()
        }
        AppDatabase.resetForTests(context)
        database = AppDatabase.getInstance(context)
        database.writableDatabase
    }

    private fun downgradeSchemaForMigrationTest(db: android.database.sqlite.SQLiteDatabase, targetVersion: Int) {
        fun dropIndex(name: String) { db.execSQL("DROP INDEX IF EXISTS $name") }
        fun dropTable(name: String) { db.execSQL("DROP TABLE IF EXISTS $name") }
        fun dropColumn(table: String, column: String) {
            val exists = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                var found = false
                while (cursor.moveToNext()) if (cursor.getString(1) == column) found = true
                found
            }
            if (exists) db.execSQL("ALTER TABLE $table DROP COLUMN $column")
        }

        if (targetVersion < 18) {
            listOf(
                "idx_sync_devices_status", "idx_sync_inbox_status_sequence",
                "idx_sync_envelopes_sequence", "idx_sync_invitations_expiry"
            ).forEach(::dropIndex)
            listOf(
                "sync_cursors", "sync_acknowledgements", "sync_inbox",
                "sync_operation_envelopes", "sync_protocol_state",
                "sync_pairing_invitations", "sync_devices"
            ).forEach(::dropTable)
        }
        if (targetVersion < 17) {
            dropIndex("idx_operating_expenses_date")
            dropIndex("idx_operating_expenses_category")
            dropTable("operating_expenses")
            dropTable("expense_categories")
        }
        if (targetVersion < 16) {
            dropIndex("idx_return_items_original_purchase_item")
            dropIndex("idx_supplier_return_refunds_return")
            dropIndex("idx_supplier_return_cost_source")
            dropTable("supplier_return_refund_allocations")
            dropTable("supplier_return_cost_allocations")
            dropColumn("return_items", "original_purchase_item_id")
            dropColumn("returns", "supplier_credit_reduction_syp_minor")
            dropColumn("returns", "supplier_cash_refund_syp_minor")
        }
        if (targetVersion < 15) {
            dropIndex("idx_inventory_adjustments_product_date")
            dropTable("inventory_adjustments")
        }
        if (targetVersion == 13 || targetVersion < 12) {
            db.delete("role_permissions", null, null)
            db.delete("permissions", null, null)
        }
        if (targetVersion < 13) {
            listOf(
                "idx_returns_operation_id", "idx_returns_original_invoice",
                "idx_return_items_original_sales_item", "idx_return_refund_operation_id",
                "idx_return_refund_cash_transaction"
            ).forEach(::dropIndex)
            dropTable("return_cost_allocations")
            listOf(
                "operation_id", "posted_at", "cash_refund_usd_cents",
                "credit_reduction_usd_cents", "returned_cogs_usd_cents",
                "exchange_rate_syp_per_usd_milli"
            ).forEach { dropColumn("returns", it) }
            listOf("original_sales_item_id", "returned_cogs_usd_cents")
                .forEach { dropColumn("return_items", it) }
            listOf("operation_id", "cash_transaction_id", "refund_equivalent_syp_minor")
                .forEach { dropColumn("return_refund_allocations", it) }
        }
        if (targetVersion < 11) {
            dropIndex("idx_sales_posted_operating_date")
            dropColumn("sales_invoices", "posted_operating_date")
        }
        if (targetVersion < 10) {
            dropIndex("idx_sales_posted_at")
            dropColumn("sales_invoices", "posted_at")
        }
        if (targetVersion < 9) {
            dropIndex("idx_fifo_allocations_invoice")
            dropIndex("idx_fifo_layers_available")
            dropTable("sales_item_cost_allocations")
            dropTable("inventory_cost_layers")
            dropColumn("sales_items", "cogs_usd_cents")
        }
        if (targetVersion < 8) dropColumn("users", "must_change_password")
        if (targetVersion < 7) dropTable("system_cash_account_roles")
        if (targetVersion < 6) dropTable("service_wallet_account_links")
        if (targetVersion < 5) {
            dropIndex("idx_service_wallet_transactions_key_date")
            dropIndex("idx_service_transfers_date")
            dropTable("service_transfers")
            dropTable("service_wallet_transactions")
            dropTable("service_wallets")
        }
        if (targetVersion < 4) {
            dropIndex("idx_cash_transactions_operating_date")
            dropTable("cash_account_transfers")
            dropTable("daily_supplier_rollovers")
            dropTable("wallet_daily_openings")
            dropColumn("cash_transactions", "operating_date")
        }
        if (targetVersion < 3) {
            dropIndex("idx_daily_cash_session_account")
            dropIndex("idx_customer_credit_revaluation_invoice")
            dropTable("daily_cash_sessions")
            dropTable("customer_credit_revaluations")
        }
        if (targetVersion < 2) {
            dropIndex("idx_sales_customer_date")
            dropColumn("sales_invoices", "sale_type")
        }
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
        // The current onCreate schema includes later security/FIFO columns, so changing only user_version would
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

        assertEquals(17, upgraded.version)
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
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('inventory_cost_layers','sales_item_cost_allocations','supplier_return_refund_allocations','supplier_return_cost_allocations','expense_categories','operating_expenses')",
            null
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(6, c.getInt(0)) }
        upgraded.rawQuery("PRAGMA table_info(return_items)", null).use { c ->
            var hasPurchaseSource = false
            while (c.moveToNext()) if (c.getString(1) == "original_purchase_item_id") hasPurchaseSource = true
            assertTrue("Supplier return migration must add the purchase-line source link", hasPurchaseSource)
        }
        upgraded.rawQuery("PRAGMA table_info(returns)", null).use { c ->
            val columns = mutableSetOf<String>()
            while (c.moveToNext()) columns.add(c.getString(1))
            assertTrue(columns.contains("supplier_credit_reduction_syp_minor"))
            assertTrue(columns.contains("supplier_cash_refund_syp_minor"))
        }
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
