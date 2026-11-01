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
        db.delete("users", "id=?", arrayOf(actorId))
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
