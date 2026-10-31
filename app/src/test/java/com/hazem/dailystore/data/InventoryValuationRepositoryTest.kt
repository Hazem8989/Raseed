package com.hazem.dailystore.data

import android.content.ContentValues
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
class InventoryValuationRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var productId: String
    private lateinit var warehouseId: String
    private var unitId: Long = 0L

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = AppDatabase.getInstance(context)
        StoreRepository(context).ensureBaseSetup()
        val db = database.writableDatabase
        productId = UUID.randomUUID().toString()
        warehouseId = db.rawQuery(
            "SELECT id FROM warehouses WHERE is_default=1 AND is_active=1 LIMIT 1", null
        ).use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
        unitId = db.rawQuery("SELECT id FROM units WHERE name='قطعة' LIMIT 1", null).use { cursor ->
            check(cursor.moveToFirst()); cursor.getLong(0)
        }

        val now = stamp(System.currentTimeMillis())
        val product = ContentValues().apply {
            put("id", productId)
            put("name", "FIFO_TEST_$productId")
            put("unit_id", unitId)
            put("purchase_cost_usd_cents", 100L)
            put("sale_price_usd_cents", 150L)
            put("track_inventory", 1)
            put("created_at", now)
            put("updated_at", now)
        }
        db.insertOrThrow("products", null, product)
    }

    @After
    fun tearDown() {
        val db = database.writableDatabase
        db.delete("inventory_movements", "product_id=?", arrayOf(productId))
        db.delete("inventory_balances", "product_id=?", arrayOf(productId))
        db.delete("products", "id=?", arrayOf(productId))
    }

    @Test
    fun valuesRemainingInventoryUsingOldestPurchaseLayersFirst() {
        val db = database.writableDatabase
        val start = System.currentTimeMillis() - 30_000L
        addMovement("PURCHASE", 10.0, 100L, start + 1_000L)
        addMovement("SALE", -6.0, 100L, start + 2_000L)
        addMovement("PURCHASE", 5.0, 200L, start + 3_000L)
        setBalance(9.0)

        val valuation = InventoryValuationRepository(RuntimeEnvironment.getApplication()).dailyCapital().current

        // FIFO consumes six units from the first 100-cent layer:
        // four units remain at 100 cents and five at 200 cents.
        assertEquals(1_400L, valuation.totalCostUsdCents)
        assertEquals(9.0, valuation.valuedQuantity, 0.000001)
        assertTrue(valuation.isComplete)
        assertEquals(0, valuation.unvaluedProductCount)
    }

    @Test
    fun excludesAProductWhenTheMovementLedgerDoesNotMatchItsStockBalance() {
        addMovement("PURCHASE", 10.0, 100L, System.currentTimeMillis() - 10_000L)
        setBalance(9.0)

        val valuation = InventoryValuationRepository(RuntimeEnvironment.getApplication()).dailyCapital().current

        assertEquals(0L, valuation.totalCostUsdCents)
        assertFalse(valuation.isComplete)
        assertEquals(1, valuation.unvaluedProductCount)
    }

    @Test
    fun excludesInventoryWithNoRecordedPurchaseCost() {
        addMovement("PURCHASE", 5.0, 0L, System.currentTimeMillis() - 10_000L)
        setBalance(5.0)

        val valuation = InventoryValuationRepository(RuntimeEnvironment.getApplication()).dailyCapital().current

        assertEquals(0L, valuation.totalCostUsdCents)
        assertFalse(valuation.isComplete)
        assertEquals(1, valuation.unvaluedProductCount)
        assertEquals(5.0, valuation.unvaluedQuantity, 0.000001)
    }

    private fun addMovement(type: String, quantity: Double, costCents: Long, atMillis: Long) {
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("product_id", productId)
            put("warehouse_id", warehouseId)
            put("movement_type", type)
            put("quantity_delta", quantity)
            put("unit_cost_usd_cents", costCents)
            put("source_type", "VALUATION_TEST")
            put("source_id", productId)
            put("created_at", stamp(atMillis))
        }
        database.writableDatabase.insertOrThrow("inventory_movements", null, values)
    }

    private fun setBalance(quantity: Double) {
        val values = ContentValues().apply {
            put("product_id", productId)
            put("warehouse_id", warehouseId)
            put("quantity", quantity)
            put("updated_at", stamp(System.currentTimeMillis()))
        }
        database.writableDatabase.insertOrThrow("inventory_balances", null, values)
    }

    private fun stamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))
}
