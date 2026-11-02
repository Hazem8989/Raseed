package com.hazem.dailystore.data

import android.content.ContentValues
import java.util.UUID
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
    private lateinit var adminId: String

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        AppDatabase.resetForTests(context)
        database = AppDatabase.getInstance(context)
        store = StoreRepository(context)
        invoices = InvoiceRepository(context)
        store.ensureBaseSetup()
        val auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        val admin = auth.authenticate("admin", "1234")!!
        auth.changePassword(admin.id, "1234", "TestAdmin123")
        adminId = admin.id
        val fx = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("currency_code", "USD")
            put("rate_syp_per_usd_milli", 10_000_000L)
            put("effective_at", "2026-10-31T10:00:00.000Z")
            put("created_at", "2026-10-31T10:00:00.000Z")
        }
        database.writableDatabase.insertOrThrow("exchange_rates", null, fx)
        val unit = store.getUnits().first()
        val category = store.getCategories().first()
        productId = store.saveProduct(
            null, "منتج فواتير اختباري", null, null, category.id, unit.id,
            100, 250, true, 10.0, adminId
        )
        cashAccountId = CashAccountRepository(context).create(
            adminId, "صندوق اختبار البيع", "CASH_BOX", "USD", 0L
        ).id
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun purchasePostingAddsStockAndRetryDoesNotDuplicateIt() {
        val draft = invoices.savePurchaseDraft(adminId, 
            "purchase-draft-op-1", null, null, "2026-10-31",
            0, null,
            listOf(
                InvoiceLineInput(productId, BigDecimal("2"), 125),
                InvoiceLineInput(productId, BigDecimal("1"), 125)
            )
        )
        assertEquals(375L, invoices.listPurchaseInvoices().single().totalUsdCents)
        assertTrue(invoices.postPurchase(adminId, draft.invoiceId, "purchase-post-op-1"))
        assertFalse(invoices.postPurchase(adminId, draft.invoiceId, "purchase-post-op-1"))
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
    fun invoicePostingWritesActorAndBeforeAfterAuditEvidenceAtomically() {
        val draft = invoices.savePurchaseDraft(
            adminId, "purchase-audit-draft", null, null, "2026-10-31",
            0, null, listOf(InvoiceLineInput(productId, BigDecimal("2"), 125))
        )
        assertTrue(invoices.postPurchase(adminId, draft.invoiceId, "purchase-audit-post"))

        database.readableDatabase.rawQuery(
            """SELECT user_id,action,entity_type,entity_id,before_json,after_json,device_id
               FROM audit_log WHERE entity_id=? AND action='POST_PURCHASE_INVOICE'""".trimIndent(),
            arrayOf(draft.invoiceId)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(adminId, cursor.getString(0))
            assertEquals("POST_PURCHASE_INVOICE", cursor.getString(1))
            assertEquals("PURCHASE_INVOICE", cursor.getString(2))
            assertEquals(draft.invoiceId, cursor.getString(3))
            assertTrue(cursor.getString(4).contains("\"status\":\"DRAFT\""))
            assertTrue(cursor.getString(5).contains("\"status\":\"POSTED\""))
            assertTrue(cursor.getString(5).contains("\"total_usd_cents\":250"))
            assertTrue(!cursor.isNull(6) && cursor.getString(6).isNotBlank())
            assertFalse(cursor.moveToNext())
        }
    }

    @Test
    fun primaryDeviceWritesInvoiceAndOutboxEnvelopeInTheSamePostingTransaction() {
        SyncProtocolRepository(RuntimeEnvironment.getApplication())
            .configureAsPrimary(adminId, "الهاتف الرئيسي")
        val draft = invoices.savePurchaseDraft(
            adminId, "sync-purchase-draft", null, null, "2026-10-31", 0, null,
            listOf(InvoiceLineInput(productId, BigDecimal("2"), 125))
        )
        assertTrue(invoices.postPurchase(adminId, draft.invoiceId, "sync-purchase-post"))
        val outbox = SyncProtocolRepository(RuntimeEnvironment.getApplication()).pendingOutbox()
        assertEquals(1, outbox.size)
        assertEquals("POST_PURCHASE_INVOICE", outbox.single().operationType)
        assertTrue(outbox.single().payloadJson.contains(draft.invoiceId))
        assertEquals(1L, outbox.single().sequence)
    }

    @Test
    fun salePostingDeductsStockAndPreventsDuplicatePosting() {
        val draft = invoices.saveSalesDraft(adminId, 
            "sale-draft-op-1", null, null, "2026-10-31",
            0, null, listOf(InvoiceLineInput(productId, BigDecimal("4"), 250))
        )
        assertTrue(invoices.postCashSale(adminId, draft.invoiceId, "sale-post-op-1", cashAccountId))
        assertFalse(invoices.postCashSale(adminId, draft.invoiceId, "sale-post-op-1", cashAccountId))
        assertEquals(6.0, stock(), 0.000001)
        assertEquals("PAID", invoices.listSalesInvoices().first { it.id == draft.invoiceId }.status)
        assertEquals(1000L, CashAccountRepository(RuntimeEnvironment.getApplication()).balance(cashAccountId))
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM invoice_payments WHERE invoice_type='SALE' AND invoice_id=?",
            arrayOf(draft.invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
    }

    @Test
    fun saleConsumesOldestFifoLayersAndPersistsExactCogsAllocations() {
        val purchase = invoices.savePurchaseDraft(adminId, 
            "fifo-purchase-draft", null, null, "2026-10-31", 0, null,
            listOf(InvoiceLineInput(productId, BigDecimal("5"), 200))
        )
        assertTrue(invoices.postPurchase(adminId, purchase.invoiceId, "fifo-purchase-post"))

        val sale = invoices.saveSalesDraft(adminId, 
            "fifo-sale-draft", null, null, "2026-10-31", 0, null,
            listOf(InvoiceLineInput(productId, BigDecimal("12"), 250))
        )
        assertTrue(invoices.postCashSale(adminId, sale.invoiceId, "fifo-sale-post", cashAccountId))
        // The product starts with 10 units at 100 cents, then receives 5 at 200 cents.
        // Selling 12 therefore consumes 10*100 + 2*200 = 1,400 cents.
        database.readableDatabase.rawQuery(
            "SELECT cogs_usd_cents FROM sales_items WHERE sales_invoice_id=?",
            arrayOf(sale.invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(1_400L, c.getLong(0)) }
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*),SUM(quantity),SUM(cost_usd_cents) FROM sales_item_cost_allocations WHERE sales_invoice_id=?",
            arrayOf(sale.invoiceId)
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
            assertEquals(12.0, c.getDouble(1), 0.000001)
            assertEquals(1_400L, c.getLong(2))
        }
        database.readableDatabase.rawQuery(
            "SELECT remaining_quantity FROM inventory_cost_layers WHERE source_type='PURCHASE_INVOICE' AND source_id=?",
            arrayOf(purchase.invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(3.0, c.getDouble(0), 0.000001) }
        // A retry must not consume the same FIFO layers a second time.
        assertFalse(invoices.postCashSale(adminId, sale.invoiceId, "fifo-sale-post", cashAccountId))
        database.readableDatabase.rawQuery(
            "SELECT SUM(remaining_quantity) FROM inventory_cost_layers WHERE product_id=?",
            arrayOf(productId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(3.0, c.getDouble(0), 0.000001) }
    }

    @Test
    fun versionNineMigrationRestoresImmutableSalePostingDateFromOperationLedger() {
        val sale = invoices.saveSalesDraft(adminId, 
            "posted-date-migration-draft", null, null, "2026-10-31", 0, null,
            listOf(InvoiceLineInput(productId, BigDecimal.ONE, 250L))
        )
        assertTrue(invoices.postCashSale(adminId, sale.invoiceId, "posted-date-migration-op", cashAccountId))
        val originalPostedAt = database.readableDatabase.rawQuery(
            "SELECT created_at FROM operation_registry WHERE operation_id='posted-date-migration-op'",
            null
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
        database.writableDatabase.execSQL(
            "UPDATE sales_invoices SET updated_at='2099-12-31T23:59:59.000Z' WHERE id=?",
            arrayOf(sale.invoiceId)
        )
        // Recreate v9, before immutable posting timestamps were added.
        database.writableDatabase.execSQL("DROP INDEX idx_sales_posted_operating_date")
        database.writableDatabase.execSQL("DROP INDEX idx_sales_posted_at")
        database.writableDatabase.execSQL("ALTER TABLE sales_invoices DROP COLUMN posted_operating_date")
        database.writableDatabase.execSQL("ALTER TABLE sales_invoices DROP COLUMN posted_at")
        database.writableDatabase.version = 9
        database.close()
        AppDatabase.resetForTests(RuntimeEnvironment.getApplication(), deleteExisting = false)
        database = AppDatabase.getInstance(RuntimeEnvironment.getApplication())
        val upgraded = database.writableDatabase
        assertEquals(17, upgraded.version)
        val restored = upgraded.rawQuery(
            "SELECT posted_at,posted_operating_date FROM sales_invoices WHERE id=?",
            arrayOf(sale.invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) to c.getString(1) }
        assertEquals(originalPostedAt, restored.first)
        assertEquals(10, restored.second.length)
        val report = SalesProfitRepository(RuntimeEnvironment.getApplication())
            .summarize(restored.second, restored.second)
        assertEquals(1, report.invoiceCount)
        assertEquals(250L, report.revenueUsdCents)
    }

    @Test
    fun fractionalFifoAllocationsRoundOnceAcrossLayers() {
        val kgUnit = store.getUnits().first { it.decimalPlaces == 3 }
        val fractionalProduct = store.saveProduct(
            null, "منتج كسري FIFO", null, null, store.getCategories().first().id,
            kgUnit.id, 1L, 250L, true, 0.0, adminId
        )
        val purchase = invoices.savePurchaseDraft(adminId, 
            "fractional-fifo-purchase", null, null, "2026-10-31", 0, null,
            listOf(
                InvoiceLineInput(fractionalProduct, BigDecimal("0.5"), 1L),
                InvoiceLineInput(fractionalProduct, BigDecimal("0.5"), 1L)
            )
        )
        assertTrue(invoices.postPurchase(adminId, purchase.invoiceId, "fractional-fifo-purchase-post"))
        val sale = invoices.saveSalesDraft(adminId, 
            "fractional-fifo-sale", null, null, "2026-10-31", 0, null,
            listOf(InvoiceLineInput(fractionalProduct, BigDecimal("1.0"), 250L))
        )
        assertTrue(invoices.postCashSale(adminId, sale.invoiceId, "fractional-fifo-sale-post", cashAccountId))
        database.readableDatabase.rawQuery(
            "SELECT cogs_usd_cents FROM sales_items WHERE sales_invoice_id=?",
            arrayOf(sale.invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(1L, c.getLong(0)) }
        database.readableDatabase.rawQuery(
            "SELECT SUM(cost_usd_cents) FROM sales_item_cost_allocations WHERE sales_invoice_id=?",
            arrayOf(sale.invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(1L, c.getLong(0)) }
    }

    @Test
    fun versionEightMigrationRebuildsFifoLayersAndHistoricalSaleCogs() {
        val sale = invoices.saveSalesDraft(adminId, 
            "legacy-fifo-sale-draft", null, null, "2026-10-31", 0, null,
            listOf(InvoiceLineInput(productId, BigDecimal("4"), 250))
        )
        assertTrue(invoices.postCashSale(adminId, sale.invoiceId, "legacy-fifo-sale-post", cashAccountId))
        // Recreate the v8 on-disk schema while preserving the old inventory ledger and invoice.
        database.writableDatabase.execSQL("DROP TABLE sales_item_cost_allocations")
        database.writableDatabase.execSQL("DROP TABLE inventory_cost_layers")
        database.writableDatabase.execSQL("ALTER TABLE sales_items DROP COLUMN cogs_usd_cents")
        database.writableDatabase.version = 8
        database.close()
        AppDatabase.resetForTests(RuntimeEnvironment.getApplication(), deleteExisting = false)
        database = AppDatabase.getInstance(RuntimeEnvironment.getApplication())
        val upgraded = database.writableDatabase
        assertEquals(17, upgraded.version)
        upgraded.rawQuery(
            "SELECT remaining_quantity FROM inventory_cost_layers WHERE product_id=?",
            arrayOf(productId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(6.0, c.getDouble(0), 0.000001) }
        upgraded.rawQuery(
            "SELECT cogs_usd_cents FROM sales_items WHERE sales_invoice_id=?",
            arrayOf(sale.invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(400L, c.getLong(0)) }
        upgraded.rawQuery(
            "SELECT SUM(quantity),SUM(cost_usd_cents) FROM sales_item_cost_allocations WHERE sales_invoice_id=?",
            arrayOf(sale.invoiceId)
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(4.0, c.getDouble(0), 0.000001)
            assertEquals(400L, c.getLong(1))
        }
        assertTrue(upgraded.isDatabaseIntegrityOk)
        upgraded.rawQuery("PRAGMA foreign_key_check", null).use { c -> assertTrue(!c.moveToFirst()) }
    }

    @Test
    fun fifoProfitReportUsesPostedRevenueAndCogsWithoutDoubleCountingCollections() {
        val purchase = invoices.savePurchaseDraft(adminId, 
            "profit-fifo-purchase", null, null, "2026-10-31", 0, null,
            listOf(InvoiceLineInput(productId, BigDecimal("5"), 200))
        )
        assertTrue(invoices.postPurchase(adminId, purchase.invoiceId, "profit-fifo-purchase-post"))
        val sale = invoices.saveSalesDraft(adminId, 
            "profit-fifo-sale", null, null, "2026-10-31", 0, null,
            listOf(InvoiceLineInput(productId, BigDecimal("12"), 250))
        )
        assertTrue(invoices.postCashSale(adminId, sale.invoiceId, "profit-fifo-sale-post", cashAccountId))
        val report = SalesProfitRepository(RuntimeEnvironment.getApplication()).allTime()
        assertTrue(report.isComplete)
        assertEquals(1, report.invoiceCount)
        assertEquals(3_000L, report.revenueUsdCents)
        assertEquals(1_400L, report.cogsUsdCents)
        assertEquals(1_600L, report.grossProfitUsdCents)
        assertEquals(0, report.uncostedInvoiceCount)
    }

    @Test
    fun insufficientStockLeavesSaleDraftAndInventoryUnchanged() {
        val draft = invoices.saveSalesDraft(adminId, 
            "sale-draft-op-2", null, null, "2026-10-31",
            0, null, listOf(InvoiceLineInput(productId, BigDecimal("11"), 250))
        )
        try {
            invoices.postCashSale(adminId, draft.invoiceId, "sale-post-op-2", cashAccountId)
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
        val first = invoices.saveSalesDraft(adminId, 
            "sale-draft-retry", null, null, "2026-10-31",
            0, null, listOf(InvoiceLineInput(productId, BigDecimal("1"), 250))
        )
        val replay = invoices.saveSalesDraft(adminId, 
            "sale-draft-retry", null, null, "2026-10-31",
            0, null, listOf(InvoiceLineInput(productId, BigDecimal("1"), 250))
        )
        assertTrue(replay.replayed)
        assertEquals(first.invoiceId, replay.invoiceId)
        try {
            invoices.saveSalesDraft(adminId, 
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
            invoices.savePurchaseDraft(adminId, 
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

    @Test
    fun cashierCanSaveSalesButCannotCreatePurchaseInvoices() {
        val auth = AuthRepository(RuntimeEnvironment.getApplication())
        val cashierId = auth.createUser(
            "ADMIN", adminId, "cashierinvoice", "موظف بيع", "Cashier5678", "CASHIER"
        )
        auth.changePassword(cashierId, "Cashier5678", "CashierPass2026")

        val sale = invoices.saveSalesDraft(
            cashierId, "cashier-sale-${UUID.randomUUID()}", null, null,
            "2026-11-02", 0L, null, listOf(InvoiceLineInput(productId, BigDecimal.ONE, 250L))
        )
        assertTrue(sale.invoiceId.isNotBlank())

        val before = database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM purchase_invoices", null
        ).use { it.moveToFirst(); it.getInt(0) }
        try {
            invoices.savePurchaseDraft(
                cashierId, "cashier-purchase-${UUID.randomUUID()}", null, null,
                "2026-11-02", 0L, null, listOf(InvoiceLineInput(productId, BigDecimal.ONE, 100L))
            )
            org.junit.Assert.fail("Cashier must not create purchase invoices")
        } catch (_: IllegalArgumentException) { }
        val after = database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM purchase_invoices", null
        ).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(before, after)
    }
}
