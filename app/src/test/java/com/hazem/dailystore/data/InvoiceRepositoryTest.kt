package com.hazem.dailystore.data

import java.math.BigDecimal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class InvoiceRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var store: StoreRepository
    private lateinit var invoices: InvoiceRepository
    private lateinit var productId: String
    private lateinit var cashAccountId: String

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = AppDatabase.getInstance(context)
        store = StoreRepository(context)
        invoices = InvoiceRepository(context)
        store.ensureBaseSetup()
        val unit = store.getUnits().first()
        val category = store.getCategories().first()
        productId = store.saveProduct(
            null, "منتج فواتير اختباري", null, null, category.id, unit.id,
            100, 250, true, 10.0
        )
        cashAccountId = CashAccountRepository(context).create(
            "صندوق اختبار البيع", "CASH_BOX", "USD", 0L
        ).id
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun purchasePostingAddsStockAndRetryDoesNotDuplicateIt() {
        val draft = invoices.savePurchaseDraft(
            "purchase-draft-op-1", null, null, "2026-10-31",
            0, null,
            listOf(
                InvoiceLineInput(productId, BigDecimal("2"), 125),
                InvoiceLineInput(productId, BigDecimal("1"), 125)
            )
        )
        assertEquals(375L, invoices.listPurchaseInvoices().single().totalUsdCents)
        assertTrue(invoices.postPurchase(draft.invoiceId, "purchase-post-op-1"))
        assertFalse(invoices.postPurchase(draft.invoiceId, "purchase-post-op-1"))
        assertEquals(13.0, stock(), 0.000001)
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM inventory_movements WHERE source_id=? AND movement_type='PURCHASE'",
            arrayOf(draft.invoiceId)
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
    }

    @Test
    fun salePostingDeductsStockAndPreventsDuplicatePosting() {
        val draft = invoices.saveSalesDraft(
            "sale-draft-op-1", null, null, "2026-10-31",
            0, null, listOf(InvoiceLineInput(productId, BigDecimal("4"), 250))
        )
        assertTrue(invoices.postCashSale(draft.invoiceId, "sale-post-op-1", cashAccountId))
        assertFalse(invoices.postCashSale(draft.invoiceId, "sale-post-op-1", cashAccountId))
        assertEquals(6.0, stock(), 0.000001)
        assertEquals("PAID", invoices.listSalesInvoices().first { it.id == draft.invoiceId }.status)
        assertEquals(1000L, CashAccountRepository(RuntimeEnvironment.getApplication()).balance(cashAccountId))
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM invoice_payments WHERE invoice_type='SALE' AND invoice_id=?",
            arrayOf(draft.invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
    }

    @Test
    fun insufficientStockLeavesSaleDraftAndInventoryUnchanged() {
        val draft = invoices.saveSalesDraft(
            "sale-draft-op-2", null, null, "2026-10-31",
            0, null, listOf(InvoiceLineInput(productId, BigDecimal("11"), 250))
        )
        try {
            invoices.postCashSale(draft.invoiceId, "sale-post-op-2", cashAccountId)
            throw AssertionError("Posting should reject a sale above available stock")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
        assertEquals(10.0, stock(), 0.000001)
        assertEquals("DRAFT", invoices.listSalesInvoices().first { it.id == draft.invoiceId }.status)
    }

    @Test
    fun draftSaveIsIdempotentAndRejectsOperationIdReuseWithDifferentPayload() {
        val invoiceCountBefore = invoices.listSalesInvoices().size
        val first = invoices.saveSalesDraft(
            "sale-draft-retry", null, null, "2026-10-31",
            0, null, listOf(InvoiceLineInput(productId, BigDecimal("1"), 250))
        )
        val replay = invoices.saveSalesDraft(
            "sale-draft-retry", null, null, "2026-10-31",
            0, null, listOf(InvoiceLineInput(productId, BigDecimal("1"), 250))
        )
        assertTrue(replay.replayed)
        assertEquals(first.invoiceId, replay.invoiceId)
        try {
            invoices.saveSalesDraft(
                "sale-draft-retry", null, null, "2026-10-31",
                0, null, listOf(InvoiceLineInput(productId, BigDecimal("2"), 250))
            )
            throw AssertionError("Reusing an operation ID with different data must fail")
        } catch (_: IllegalArgumentException) {
            // Expected idempotency guard.
        }
        assertEquals(invoiceCountBefore + 1, invoices.listSalesInvoices().size)
    }

    @Test
    fun rejectsFractionalQuantityForWholePieceUnit() {
        try {
            invoices.savePurchaseDraft(
                "purchase-fractional-op", null, null, "2026-10-31",
                0, null, listOf(InvoiceLineInput(productId, BigDecimal("0.5"), 125))
            )
            throw AssertionError("Piece quantity must not accept fractions")
        } catch (_: IllegalArgumentException) {
            // The default first unit is a whole piece and allows no decimal places.
        }
        assertTrue(invoices.listPurchaseInvoices().isEmpty())
    }

    private fun stock(): Double = database.readableDatabase.rawQuery(
        "SELECT quantity FROM inventory_balances WHERE product_id=?",
        arrayOf(productId)
    ).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }
}
