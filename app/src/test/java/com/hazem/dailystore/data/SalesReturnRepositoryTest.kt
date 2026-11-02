package com.hazem.dailystore.data

import android.content.ContentValues
import java.math.BigDecimal
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
class SalesReturnRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var adminId: String
    private lateinit var productId: String
    private lateinit var cashAccountId: String
    private lateinit var invoices: InvoiceRepository
    private lateinit var returns: SalesReturnRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        AppDatabase.resetForTests(context)
        database = AppDatabase.getInstance(context)
        val store = StoreRepository(context)
        store.ensureBaseSetup()
        val auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        val admin = auth.authenticate("admin", "1234")!!
        auth.changePassword(admin.id, "1234", "TestAdmin123")
        adminId = admin.id
        val fx = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("currency_code", "USD")
            put("rate_syp_per_usd_milli", 15_000_000L)
            put("effective_at", "2026-11-02T08:00:00.000Z")
            put("created_at", "2026-11-02T08:00:00.000Z")
        }
        database.writableDatabase.insertOrThrow("exchange_rates", null, fx)
        val unit = store.getUnits().first()
        val category = store.getCategories().first()
        productId = store.saveProduct(
            null, "صنف مرتجعات ${UUID.randomUUID()}", null, null, category.id, unit.id,
            100L, 250L, true, 10.0, adminId
        )
        cashAccountId = CashAccountRepository(context).create(
            adminId, "صندوق مرتجعات ${UUID.randomUUID()}", "CASH_BOX", "USD", 0L
        ).id
        invoices = InvoiceRepository(context)
        returns = SalesReturnRepository(context)
    }

    @After
    fun tearDown() { database.close() }

    private fun saleItemId(invoiceId: String): String =
        database.readableDatabase.rawQuery(
            "SELECT id FROM sales_items WHERE sales_invoice_id=?", arrayOf(invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }

    private fun stock(): Double =
        StoreRepository(RuntimeEnvironment.getApplication()).listProducts()
            .first { it.id == productId }.stock

    @Test
    fun cashReturnRestoresStockFifoCostAndRefundsOnce() {
        val draft = invoices.saveSalesDraft(
            adminId, "return-cash-draft", null, null, "2026-11-02", 0L, null,
            listOf(InvoiceLineInput(productId, BigDecimal("3"), 250L))
        )
        assertTrue(invoices.postCashSale(adminId, draft.invoiceId, "return-cash-sale", cashAccountId))
        assertEquals(7.0, stock(), 0.000001)
        val itemId = saleItemId(draft.invoiceId)
        val input = listOf(SalesReturnItemInput(itemId, BigDecimal.ONE))
        val allocation = listOf(SalesRefundAllocationInput(cashAccountId, 250L))
        val first = returns.postSalesReturn(
            adminId, draft.invoiceId, "cash-return-op", "2026-11-02",
            "إرجاع قطعة سليمة", input, allocation
        )
        assertFalse(first.replayed)
        assertEquals(250L, first.totalUsdCents)
        assertEquals(0L, first.creditReductionUsdCents)
        assertEquals(250L, first.cashRefundUsdCents)
        assertEquals(100L, first.returnedCogsUsdCents)
        assertEquals(8.0, stock(), 0.000001)
        assertEquals(500L, CashAccountRepository(RuntimeEnvironment.getApplication()).balance(cashAccountId))
        val dailyCash=DailyBalanceRepository(RuntimeEnvironment.getApplication()).dailyReport("2026-11-02")
        val dailyAccount=dailyCash.accounts.first { it.accountId==cashAccountId }
        assertEquals(250L,dailyAccount.salesReturnRefundsMinor)
        assertEquals(500L,dailyAccount.closingMinor)
        val replay = returns.postSalesReturn(
            adminId, draft.invoiceId, "cash-return-op", "2026-11-02",
            "إرجاع قطعة سليمة", input, allocation
        )
        assertTrue(replay.replayed)
        assertEquals(first.returnId, replay.returnId)
        assertEquals(8.0, stock(), 0.000001)
        assertEquals(1, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM returns WHERE original_invoice_id=? AND status='POSTED'",
            arrayOf(draft.invoiceId)
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        val profit = SalesProfitRepository(RuntimeEnvironment.getApplication()).allTime()
        assertEquals(500L, profit.revenueUsdCents - profit.returnUsdCents)
        assertEquals(200L, profit.cogsUsdCents - profit.returnedCogsUsdCents)
        assertEquals(300L, profit.grossProfitUsdCents)
        assertTrue(profit.isComplete)
    }

    @Test
    fun creditReturnReducesCustomerDebtWithoutCashRefundAndUpdatesProfit() {
        val customerId = CustomerRepository(RuntimeEnvironment.getApplication())
            .create(adminId, "زبون مرتجع ${UUID.randomUUID()}").id
        val draft = invoices.saveSalesDraft(
            adminId, "return-credit-draft", null, customerId, "2026-11-02", 0L, null,
            listOf(InvoiceLineInput(productId, BigDecimal("2"), 250L)), saleType = "CREDIT"
        )
        assertTrue(invoices.postSale(adminId, draft.invoiceId, "return-credit-sale"))
        val itemId = saleItemId(draft.invoiceId)
        val input = listOf(SalesReturnItemInput(itemId, BigDecimal.ONE))
        val first = returns.postSalesReturn(
            adminId, draft.invoiceId, "credit-return-op-1", "2026-11-02",
            "إرجاع من بيع آجل", input, emptyList()
        )
        assertEquals(250L, first.creditReductionUsdCents)
        assertEquals(0L, first.cashRefundUsdCents)
        assertEquals(250L, CustomerPaymentRepository(RuntimeEnvironment.getApplication())
            .listInvoices(customerId).single().outstandingUsdCents)
        val second = returns.postSalesReturn(
            adminId, draft.invoiceId, "credit-return-op-2", "2026-11-02",
            "إرجاع بقية الكمية", input, emptyList()
        )
        assertEquals(250L, second.creditReductionUsdCents)
        assertEquals(0L, CustomerPaymentRepository(RuntimeEnvironment.getApplication())
            .listInvoices(customerId).single().outstandingUsdCents)
        val accounts = CashAccountRepository(RuntimeEnvironment.getApplication())
        accounts.listActive() // Ensure the current operating day's primary drawer exists.
        val drawer = accounts.currentDayPrimaryDrawer()!!
        val paymentsBefore = database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM invoice_payments WHERE invoice_id=?", arrayOf(draft.invoiceId)
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        try {
            CustomerPaymentRepository(RuntimeEnvironment.getApplication()).addPayment(
                adminId, draft.invoiceId, "collection-after-full-return", drawer.id, 1L
            )
            throw AssertionError("A fully returned credit balance must not be collected")
        } catch (_: IllegalArgumentException) { }
        val paymentsAfter = database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM invoice_payments WHERE invoice_id=?", arrayOf(draft.invoiceId)
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        assertEquals(paymentsBefore, paymentsAfter)
        val profit = SalesProfitRepository(RuntimeEnvironment.getApplication()).allTime()
        assertEquals(0L, profit.revenueUsdCents - profit.returnUsdCents)
        assertEquals(0L, profit.cogsUsdCents - profit.returnedCogsUsdCents)
        assertEquals(0L, profit.grossProfitUsdCents)
        assertTrue(profit.isComplete)
    }


    @Test
    fun returnIsReportedOnItsOwnDateWhenOriginalSaleWasInEarlierPeriod() {
        val draft = invoices.saveSalesDraft(
            adminId, "return-later-period-draft", null, null, "2026-11-01", 0L, null,
            listOf(InvoiceLineInput(productId, BigDecimal.ONE, 250L))
        )
        assertTrue(invoices.postCashSale(adminId, draft.invoiceId, "return-later-period-sale", cashAccountId))
        database.writableDatabase.execSQL(
            "UPDATE sales_invoices SET posted_operating_date='2026-11-01' WHERE id=?",
            arrayOf(draft.invoiceId)
        )
        val result = returns.postSalesReturn(
            adminId, draft.invoiceId, "return-later-period-op", "2026-11-02",
            "إرجاع في يوم لاحق",
            listOf(SalesReturnItemInput(saleItemId(draft.invoiceId), BigDecimal.ONE)),
            listOf(SalesRefundAllocationInput(cashAccountId, 250L))
        )
        assertEquals(250L, result.totalUsdCents)
        val report = SalesProfitRepository(RuntimeEnvironment.getApplication())
            .summarize("2026-11-02", "2026-11-02")
        assertEquals(0L, report.revenueUsdCents)
        assertEquals(250L, report.returnUsdCents)
        assertEquals(0L, report.cogsUsdCents)
        assertEquals(100L, report.returnedCogsUsdCents)
        assertEquals(-250L, report.netRevenueUsdCents)
        assertEquals(-100L, report.netCogsUsdCents)
        assertEquals(-150L, report.grossProfitUsdCents)
        assertTrue(report.isComplete)
    }

    @Test
    fun cashierCannotPostSalesReturn() {
        val auth = AuthRepository(RuntimeEnvironment.getApplication())
        val cashierId = auth.createUser(
            "ADMIN", adminId, "return-cashier", "Return cashier", "Cashier12345", "CASHIER"
        )
        auth.changePassword(cashierId, "Cashier12345", "CashierStrong123")
        try {
            returns.postSalesReturn(
                cashierId, "missing-invoice", "unauthorized-return", "2026-11-02",
                "محاولة غير مصرح بها", listOf(SalesReturnItemInput("missing-item", BigDecimal.ONE)),
                emptyList()
            )
            throw AssertionError("Expected return permission denial")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun cashRefundCanBeSplitAcrossUsdAndSypAccountsWithoutRoundingDrift() {
        val context = RuntimeEnvironment.getApplication()
        val accounts = CashAccountRepository(context)
        val sypAccountId = accounts.create(
            adminId, "صندوق ليرة للمرتجعات ${UUID.randomUUID()}",
            "CASH_BOX", "SYP", 10_000L
        ).id
        val draft = invoices.saveSalesDraft(
            adminId, "return-split-draft", null, null, "2026-11-02", 0L, null,
            listOf(InvoiceLineInput(productId, BigDecimal("3"), 250L))
        )
        assertTrue(invoices.postCashSale(adminId, draft.invoiceId, "return-split-sale", cashAccountId))
        val result = returns.postSalesReturn(
            adminId, draft.invoiceId, "return-split-op", "2026-11-02",
            "رد موزع على حسابين",
            listOf(SalesReturnItemInput(saleItemId(draft.invoiceId), BigDecimal.ONE)),
            listOf(
                SalesRefundAllocationInput(cashAccountId, 200L),
                SalesRefundAllocationInput(sypAccountId, 7_500L)
            )
        )
        assertEquals(250L, result.cashRefundUsdCents)
        assertEquals(550L, accounts.balance(cashAccountId))
        assertEquals(2_500L, accounts.balance(sypAccountId))
        assertEquals(8.0, stock(), 0.000001)
    }


    @Test
    fun primarySalesReturnIsAddedToOutboxAtomically() {
        val context = RuntimeEnvironment.getApplication()
        val sync = SyncProtocolRepository(context)
        sync.configureAsPrimary(adminId, "الهاتف الرئيسي")
        val draft = invoices.saveSalesDraft(
            adminId, "sync-sales-return-draft", null, null, "2026-11-02", 0L, null,
            listOf(InvoiceLineInput(productId, BigDecimal("2"), 250L))
        )
        assertTrue(invoices.postCashSale(adminId, draft.invoiceId, "sync-sales-return-sale", cashAccountId))
        val itemId = saleItemId(draft.invoiceId)
        returns.postSalesReturn(
            adminId, draft.invoiceId, "sync-sales-return-op", "2026-11-02",
            "مرتجع للمزامنة", listOf(SalesReturnItemInput(itemId, BigDecimal.ONE)),
            listOf(SalesRefundAllocationInput(cashAccountId, 250L))
        )
        assertTrue(sync.pendingOutbox().any { it.entityType == "SALES_RETURN" && it.operationType == "CREATE" &&
            it.payloadJson.contains("POST_SALES_RETURN") })
    }

}
