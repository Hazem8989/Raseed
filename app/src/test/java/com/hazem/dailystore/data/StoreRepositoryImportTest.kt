package com.hazem.dailystore.data

import android.content.ContentValues
import java.time.Instant
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StoreRepositoryImportTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: StoreRepository
    private lateinit var actorId: String
    private lateinit var categoryName: String
    private lateinit var productName: String

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        AppDatabase.resetForTests(context)
        database = AppDatabase.getInstance(context)
        repository = StoreRepository(context)
        repository.ensureBaseSetup()
        actorId = UUID.randomUUID().toString()
        categoryName = "IMPORT_TEST_CATEGORY_$actorId"
        productName = "IMPORT_TEST_PRODUCT_$actorId"

        val now = Instant.now().toString()
        val user = ContentValues().apply {
            put("id", actorId)
            put("username", "import_test_$actorId")
            put("display_name", "Import test")
            put("password_hash", "test-hash")
            put("password_salt", "test-salt")
            put("role_key", "ADMIN")
            put("is_active", 1)
            put("must_change_password", 0)
            put("created_at", now)
            put("updated_at", now)
        }
        database.writableDatabase.insertOrThrow("users", null, user)
    }

    @After
    fun tearDown() {
        val db = database.writableDatabase
        db.delete("inventory_movements", "product_id IN (SELECT id FROM products WHERE name=?)", arrayOf(productName))
        db.delete("inventory_balances", "product_id IN (SELECT id FROM products WHERE name=?)", arrayOf(productName))
        db.delete("products", "name=?", arrayOf(productName))
        db.delete("audit_log", "user_id=?", arrayOf(actorId))
        db.delete("categories", "name=?", arrayOf(categoryName))
        // Keep the manager row until the isolated test database is closed; stock/audit rows may reference it.
    }

    @Test
    fun productEditsCannotDisableTrackedStockOrChangeItsUnitAfterMovement() {
        val piece = repository.getUnits().first()
        val kg = repository.getUnits().first { it.decimalPlaces == 3 }
        val category = repository.getCategories().first()
        val id = repository.saveProduct(
            null, "Tracked edit guard $actorId", null, null, category.id, piece.id,
            100L, 200L, true, 5.0, actorId
        )

        fun assertRejected(block: () -> Unit) {
            var rejected = false
            try { block() } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Unsafe tracked-inventory edit must be rejected", rejected)
        }

        assertRejected {
            repository.saveProduct(id, "Tracked edit guard $actorId", null, null,
                category.id, piece.id, 100L, 200L, false, 0.0, actorId)
        }
        assertRejected {
            repository.saveProduct(id, "Tracked edit guard $actorId", null, null,
                category.id, kg.id, 100L, 200L, true, 0.0, actorId)
        }
        assertRejected {
            repository.saveProduct(null, "Uncosted tracked $actorId", null, null,
                category.id, piece.id, 0L, 200L, true, 0.0, actorId)
        }
        val current = repository.listProducts().single { it.id == id }
        assertTrue(current.tracksInventory)
        assertEquals(5.0, current.stock, 0.000001)

        val nonInventory = repository.saveProduct(
            null, "Non inventory $actorId", null, null, category.id, piece.id,
            0L, 200L, false, 0.0, actorId
        )
        assertFalse(repository.listProducts().single { it.id == nonInventory }.tracksInventory)
    }

    @Test
    fun cashierCannotChangeCatalogExchangeRateOrOpeningStock() {
        val cashierId = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val cashier = ContentValues().apply {
            put("id", cashierId); put("username", "cashier_$cashierId")
            put("display_name", "Cashier"); put("password_hash", "test")
            put("password_salt", "test"); put("role_key", "CASHIER")
            put("is_active", 1); put("must_change_password", 0)
            put("created_at", now); put("updated_at", now)
        }
        database.writableDatabase.insertOrThrow("users", null, cashier)

        val unit = repository.getUnits().first()
        val category = repository.getCategories().first()
        val existingId = repository.saveProduct(
            null, "Protected product $actorId", null, null, category.id, unit.id,
            100L, 200L, true, 0.0, actorId
        )
        val beforeProducts = repository.listProducts().size

        fun assertDenied(block: () -> Unit) {
            var denied = false
            try { block() } catch (_: IllegalArgumentException) { denied = true }
            assertTrue("Cashier operation must be rejected", denied)
        }

        assertDenied { repository.saveExchangeRate(java.math.BigDecimal("15000"), cashierId) }
        assertDenied {
            repository.saveProduct(null, "Unauthorized $actorId", null, null, category.id,
                unit.id, 100L, 200L, true, 0.0, cashierId)
        }
        assertDenied { repository.setProductActive(existingId, false, cashierId) }
        assertDenied { repository.addCategory("Unauthorized category $actorId", cashierId) }
        assertDenied { repository.recordOpeningStock(existingId, 5.0, cashierId, "unauthorized") }
        val importRow = ProductImportRow(
            rowNumber = 2, name = "Unauthorized import $actorId", category = categoryName,
            unit = "قطعة", purchaseCostSyp = null, salePriceSyp = null,
            purchaseCostUsdCents = 100L, salePriceUsdCents = 200L, sku = null, barcode = null
        )
        assertDenied { repository.importProducts(listOf(importRow), 0L, cashierId) }

        assertEquals(beforeProducts, repository.listProducts().size)
        assertTrue(repository.listProducts().any { it.id == existingId && it.stock == 0.0 })
        database.readableDatabase.rawQuery("SELECT COUNT(*) FROM exchange_rates", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        database.writableDatabase.delete("users", "id=?", arrayOf(cashierId))
    }

    @Test
    fun importedNewCategoryIsQueuedForReplicationInTheSameCatalogTransaction() {
        val context = RuntimeEnvironment.getApplication()
        SyncDeviceTestKeyProvider.install()
        SyncProtocolRepository(context).configureAsPrimary(actorId, "Import test primary")
        val row = ProductImportRow(
            rowNumber = 2, name = productName, category = categoryName,
            unit = "قطعة", purchaseCostSyp = null, salePriceSyp = null,
            purchaseCostUsdCents = 100L, salePriceUsdCents = 200L,
            sku = null, barcode = null
        )

        assertEquals(1, repository.importProducts(listOf(row), 0L, actorId))
        database.readableDatabase.rawQuery(
            """SELECT e.operation_type,e.entity_type,e.entity_id,e.payload_json
               FROM sync_operation_envelopes e
               WHERE e.entity_type='CATEGORY' AND e.operation_type='CREATE'
               ORDER BY e.sequence_number DESC LIMIT 1""".trimIndent(), null
        ).use { cursor ->
            assertTrue("Imported category must create a durable sync event", cursor.moveToFirst())
            assertEquals("CREATE", cursor.getString(0))
            assertEquals("CATEGORY", cursor.getString(1))
            assertEquals(categoryName.lowercase(java.util.Locale.ROOT), cursor.getString(2))
            val payload = org.json.JSONObject(cursor.getString(3))
            assertEquals("CREATE_CATEGORY", payload.getString("business_operation"))
            assertTrue(payload.getLong("primary_category_id") > 0L)
            assertEquals(categoryName, payload.getString("name"))
        }

        val importedProductId = database.readableDatabase.rawQuery(
            "SELECT id FROM products WHERE name=?", arrayOf(productName)
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }
        database.readableDatabase.rawQuery(
            """SELECT e.operation_type,e.entity_id,e.payload_json
               FROM sync_operation_envelopes e
               WHERE e.entity_type='PRODUCT' AND e.entity_id=?
               ORDER BY e.sequence_number DESC LIMIT 1""".trimIndent(), arrayOf(importedProductId)
        ).use { cursor ->
            assertTrue("Imported product must create a durable sync event", cursor.moveToFirst())
            assertEquals("CREATE", cursor.getString(0))
            assertEquals(importedProductId, cursor.getString(1))
            val payload = org.json.JSONObject(cursor.getString(2))
            assertEquals("UPSERT_PRODUCT", payload.getString("business_operation"))
            assertEquals(productName, payload.getString("name"))
            assertEquals("قطعة", payload.getString("unit_name"))
            assertEquals(0.0, payload.getDouble("opening_quantity"), 0.000001)
        }
    }

    @Test
    fun importsSyrianPoundPricesUsingTheExplicitRateAndLeavesStockAtZero() {
        val rateMilli = 15_000_000L
        val row = ProductImportRow(
            rowNumber = 2,
            name = productName,
            category = categoryName,
            unit = "قطعة",
            purchaseCostSyp = 15_000L,
            salePriceSyp = 22_500L,
            purchaseCostUsdCents = null,
            salePriceUsdCents = null,
            sku = null,
            barcode = null
        )

        assertEquals(1, repository.importProducts(listOf(row), rateMilli, actorId))
        val imported = repository.listProducts().single { it.name == productName }
        assertEquals(100L, imported.purchaseCostUsdCents)
        assertEquals(150L, imported.salePriceUsdCents)
        assertEquals(0.0, imported.stock, 0.000001)
        assertFalse(imported.barcode?.isNotBlank() == true)
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM inventory_movements WHERE product_id=?",
            arrayOf(imported.id)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun rejectsTrackedInventoryWithoutPositivePurchaseCost() {
        val row = ProductImportRow(
            rowNumber = 2,
            name = productName,
            category = categoryName,
            unit = "قطعة",
            purchaseCostSyp = 0L,
            salePriceSyp = 22_500L,
            purchaseCostUsdCents = null,
            salePriceUsdCents = null,
            sku = null,
            barcode = null
        )

        var rejected = false
        try {
            repository.importProducts(listOf(row), 15_000_000L, actorId)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("An uncosted tracked product must not be imported", rejected)
        assertFalse(repository.listProducts().any { it.name == productName })
    }
    @Test
    fun allowsZeroCostOnlyForProductsThatDoNotTrackInventory() {
        val row = ProductImportRow(
            rowNumber = 2,
            name = productName,
            category = categoryName,
            unit = "قطعة",
            purchaseCostSyp = 0L,
            salePriceSyp = 22_500L,
            purchaseCostUsdCents = null,
            salePriceUsdCents = null,
            sku = null,
            barcode = null,
            tracksInventory = false
        )

        assertEquals(1, repository.importProducts(listOf(row), 15_000_000L, actorId))
        val imported = repository.listProducts().single { it.name == productName }
        assertEquals(0L, imported.purchaseCostUsdCents)
        assertEquals(150L, imported.salePriceUsdCents)
        assertFalse(imported.tracksInventory)
        assertEquals(0.0, imported.stock, 0.000001)
    }

}
