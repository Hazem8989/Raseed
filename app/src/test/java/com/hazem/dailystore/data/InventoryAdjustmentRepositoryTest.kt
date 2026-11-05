package com.hazem.dailystore.data

import android.content.ContentValues
import java.math.BigDecimal
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class InventoryAdjustmentRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var store: StoreRepository
    private lateinit var repository: InventoryAdjustmentRepository
    private lateinit var adminId: String
    private lateinit var productId: String

    @Before
    fun setUp() {
        SyncDeviceTestKeyProvider.install()
        val context = RuntimeEnvironment.getApplication()
        AppDatabase.resetForTests(context)
        database = AppDatabase.getInstance(context)
        store = StoreRepository(context)
        store.ensureBaseSetup()
        val auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        val admin = auth.authenticate("admin", "1234")!!
        auth.changePassword(admin.id, "1234", "TestAdmin123")
        adminId = admin.id
        val rate = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("currency_code", "USD")
            put("rate_syp_per_usd_milli", 15_000_000L)
            put("effective_at", "2026-11-02T08:00:00.000Z")
            put("created_at", "2026-11-02T08:00:00.000Z")
        }
        database.writableDatabase.insertOrThrow("exchange_rates", null, rate)
        val unit = store.getUnits().first()
        val category = store.getCategories().first()
        productId = store.saveProduct(
            null, "صنف تسوية ${UUID.randomUUID()}", null, null, category.id, unit.id,
            100L, 250L, true, 5.0, adminId
        )
        repository = InventoryAdjustmentRepository(context)
    }

    @After
    fun tearDown() { database.close() }

    private fun stock(): Double = store.listProducts().first { it.id == productId }.stock

    @Test
    fun increaseAndDecreaseAdjustmentsMaintainFifoCostAndNeverCreateSaleAllocations() {
        val increase = repository.postAdjustment(
            adminId, productId, BigDecimal("3"), "كمية جرد زائدة",
            "adjustment-increase-1", 200L
        )
        assertEquals("INCREASE", increase.adjustmentType)
        assertEquals(BigDecimal("5.0"), increase.quantityBefore)
        assertEquals(BigDecimal("8.0"), increase.quantityAfter)
        assertEquals(600L, increase.costUsdCents)
        assertEquals(8.0, stock(), 0.000001)

        val decrease = repository.postAdjustment(
            adminId, productId, BigDecimal("-6"), "تلف وفقد أثناء الجرد",
            "adjustment-decrease-1"
        )
        assertEquals("DECREASE", decrease.adjustmentType)
        assertEquals(BigDecimal("8.0"), decrease.quantityBefore)
        assertEquals(BigDecimal("2.0"), decrease.quantityAfter)
        assertNull(decrease.unitCostUsdCents)
        assertEquals(700L, decrease.costUsdCents)
        assertEquals(2.0, stock(), 0.000001)

        val saleAllocations = database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sales_item_cost_allocations", null
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(0, saleAllocations)
        val movementTypes = database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM inventory_movements WHERE movement_type IN ('ADJUSTMENT_IN','ADJUSTMENT_OUT')",
            null
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(2, movementTypes)

        val capital = InventoryValuationRepository(RuntimeEnvironment.getApplication()).dailyCapital().current
        assertTrue(capital.isComplete)
        assertEquals(400L, capital.totalCostUsdCents)
        assertEquals(2, repository.listRecent().size)
    }

    @Test
    fun retriesAreIdempotentAndChangedPayloadCannotReuseOperationId() {
        val first = repository.postAdjustment(
            adminId, productId, BigDecimal.ONE, "زيادة جرد",
            "adjustment-idempotent", 150L
        )
        val replay = repository.postAdjustment(
            adminId, productId, BigDecimal.ONE, "زيادة جرد",
            "adjustment-idempotent", 150L
        )
        assertTrue(replay.replayed)
        assertEquals(first.adjustmentId, replay.adjustmentId)
        assertEquals(6.0, stock(), 0.000001)
        try {
            repository.postAdjustment(
                adminId, productId, BigDecimal("2"), "زيادة مختلفة",
                "adjustment-idempotent", 150L
            )
            throw AssertionError("Expected idempotency payload rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(6.0, stock(), 0.000001)
        assertEquals(1, repository.listRecent().size)
    }

    @Test
    fun rejectsNegativeStockMissingIncreaseCostAndCashierAccess() {
        try {
            repository.postAdjustment(
                adminId, productId, BigDecimal("-6"), "نقص جرد",
                "adjustment-negative-too-large"
            )
            throw AssertionError("Expected negative stock rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(5.0, stock(), 0.000001)

        try {
            repository.postAdjustment(
                adminId, productId, BigDecimal.ONE, "زيادة بلا تكلفة",
                "adjustment-no-cost"
            )
            throw AssertionError("Expected missing cost rejection")
        } catch (_: IllegalArgumentException) { }

        val auth = AuthRepository(RuntimeEnvironment.getApplication())
        val cashierId = auth.createUser(
            "ADMIN", adminId, "adjustment-cashier", "Adjustment cashier",
            "Cashier12345", "CASHIER"
        )
        auth.changePassword(cashierId, "Cashier12345", "CashierStrong123")
        try {
            repository.postAdjustment(
                cashierId, productId, BigDecimal.ONE, "محاولة موظف",
                "adjustment-cashier-denied", 100L
            )
            throw AssertionError("Expected permission rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(5.0, stock(), 0.000001)
    }

    @Test
    fun nonInventoryProductsCannotReceiveStockAdjustments() {
        val unit = store.getUnits().first()
        val category = store.getCategories().first()
        val serviceProduct = store.saveProduct(
            null, "خدمة غير مخزنية ${UUID.randomUUID()}", null, null, category.id, unit.id,
            0L, 100L, false, 0.0, adminId
        )
        try {
            repository.postAdjustment(
                adminId, serviceProduct, BigDecimal.ONE, "غير قابل للجرد",
                "adjustment-non-inventory", 100L
            )
            throw AssertionError("Expected non-inventory rejection")
        } catch (_: IllegalArgumentException) { }
    }


    @Test
    fun secondaryInventoryAdjustmentIsRejectedBySalesOnlyPolicy() {
        val context = RuntimeEnvironment.getApplication()
        val before = stock()
        val vault = SyncCredentialVault(context) { javax.crypto.spec.SecretKeySpec(ByteArray(32) { 41 }, "AES") }
        val sync = SyncProtocolRepository(context, vault)
        val credential = android.util.Base64.encodeToString(ByteArray(32) { (it + 11).toByte() },
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        sync.configureAsSecondary(adminId, "inventory-adjust-sync-store", UUID.randomUUID().toString(),
            credential, "جهاز جرد ثانوي", "Asia/Damascus")
        var rejected = false
        try { repository.postAdjustment(adminId, productId, BigDecimal.ONE, "زيادة جرد ممنوعة",
            "secondary-adjustment-" + UUID.randomUUID(), 200L) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(before, stock(), 0.000001)
        assertTrue(sync.pendingOutbox().isEmpty())
    }

    @Test
    fun primaryInventoryAdjustmentRemainsLocalToPrimaryDevice() {
        val context = RuntimeEnvironment.getApplication()
        val sync = SyncProtocolRepository(context)
        sync.configureAsPrimary(adminId, "الهاتف الرئيسي")
        repository.postAdjustment(adminId, productId, BigDecimal("1"),
            "تسوية للمزامنة", "sync-adjustment-" + UUID.randomUUID(), 200L)
        assertTrue("Inventory adjustments are primary-only; they must not be broadcast to a sales-only secondary",
            sync.pendingOutbox().isEmpty())
    }

}
