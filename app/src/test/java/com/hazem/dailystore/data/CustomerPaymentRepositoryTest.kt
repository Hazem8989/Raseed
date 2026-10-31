package com.hazem.dailystore.data

import android.content.ContentValues
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
class CustomerPaymentRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var customers: CustomerRepository
    private lateinit var invoices: InvoiceRepository
    private lateinit var payments: CustomerPaymentRepository
    private lateinit var customerId: String
    private lateinit var cashDrawerId: String
    private lateinit var walletId: String
    private lateinit var usdAccountId: String
    private lateinit var productId: String

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = AppDatabase.getInstance(context)
        val store = StoreRepository(context)
        store.ensureBaseSetup()
        customers = CustomerRepository(context)
        invoices = InvoiceRepository(context)
        payments = CustomerPaymentRepository(context)
        database.writableDatabase.delete("exchange_rates", null, null)
        database.writableDatabase.delete("daily_cash_sessions", null, null)
        customerId = customers.create("زبون تحصيل " + UUID.randomUUID()).id
        val unit = store.getUnits().first()
        val category = store.getCategories().first()
        productId = store.saveProduct(null, "صنف تحصيل " + UUID.randomUUID(), null, null,
            category.id, unit.id, 100L, 500L, true, 20.0)
        insertRate(15_000_000L, "2026-10-31T00:00:00.000Z")
        cashDrawerId = insertAccount("درج اليوم", "CASH_BOX", "SYP")
        walletId = insertAccount("محفظة اختبار", "WALLET", "SYP")
        usdAccountId = insertAccount("حساب دولار قديم", "CASH_BOX", "USD")
        val session = ContentValues().apply {
            put("operating_date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
            put("primary_cash_account_id", cashDrawerId)
            putNull("created_by")
            put("created_at", "2026-10-31T00:00:00.000Z")
        }
        database.writableDatabase.insertOrThrow("daily_cash_sessions", null, session)
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun sypCashCollectionIsFinancialOnlyAndIdempotent() {
        val invoiceId = createCreditInvoice()
        val stockBefore = stockQuantity()
        val movementCountBefore = inventoryMovementCount(invoiceId)
        val first = payments.addPayment(invoiceId, "customer-pay-first", cashDrawerId, 15_000L, "دفعة أولى")
        assertFalse(first.replayed)
        assertEquals(100L, first.paidUsdCents)
        assertEquals(400L, first.outstandingUsdCents)
        assertEquals(60_000L, first.outstandingSypMinor)

        val replay = payments.addPayment(invoiceId, "customer-pay-first", cashDrawerId, 15_000L, "دفعة أولى")
        assertTrue(replay.replayed)
        assertEquals(first.paymentId, replay.paymentId)
        assertEquals(1, payments.listPayments(invoiceId).size)
        assertEquals(stockBefore, stockQuantity(), 0.000001)
        assertEquals(movementCountBefore, inventoryMovementCount(invoiceId))
        assertEquals(15_000L, CashAccountRepository(RuntimeEnvironment.getApplication()).balance(cashDrawerId))
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_transactions WHERE source_id=? AND transaction_type='CUSTOMER_CREDIT_COLLECTION'",
            arrayOf(invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
    }

    @Test
    fun collectionUsesGreaterOfCurrentAndOriginalRate() {
        val invoiceId = createCreditInvoice()
        insertRate(20_000_000L, "2026-11-01T00:00:00.000Z")
        val result = payments.addPayment(invoiceId, "customer-high-rate", cashDrawerId, 20_000L)
        assertEquals(100L, result.paidUsdCents)
        assertEquals(400L, result.outstandingUsdCents)
        assertEquals(80_000L, result.outstandingSypMinor)
        assertEquals(20_000_000L, payments.listPayments(invoiceId).single().effectiveRateMilli)
    }

    @Test
    fun fallingRateCannotReduceDebtBelowOriginalInvoiceRateFloor() {
        val invoiceId = createCreditInvoice()
        insertRate(10_000_000L, "2026-11-02T00:00:00.000Z")
        val result = payments.addPayment(invoiceId, "customer-low-rate", cashDrawerId, 15_000L)
        assertEquals(100L, result.paidUsdCents)
        assertEquals(400L, result.outstandingUsdCents)
        assertEquals(60_000L, result.outstandingSypMinor)
        assertEquals(15_000_000L, payments.listPayments(invoiceId).single().effectiveRateMilli)
    }

    @Test
    fun electronicCollectionIncreasesOnlySelectedWallet() {
        val invoiceId = createCreditInvoice()
        val cashBefore = CashAccountRepository(RuntimeEnvironment.getApplication()).balance(cashDrawerId)
        val result = payments.addPayment(invoiceId, "customer-wallet", walletId, 30_000L)
        assertEquals(200L, result.paidUsdCents)
        val accounts = CashAccountRepository(RuntimeEnvironment.getApplication())
        assertEquals(30_000L, accounts.balance(walletId))
        assertEquals(cashBefore, accounts.balance(cashDrawerId))
        database.readableDatabase.rawQuery(
            "SELECT account_id,transaction_type FROM cash_transactions WHERE operation_id='customer-wallet'",
            null
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(walletId, c.getString(0))
            assertEquals("CUSTOMER_CREDIT_COLLECTION", c.getString(1))
        }
    }

    @Test
    fun missingDailyDrawerAndNonSypAccountAreRejected() {
        val invoiceId = createCreditInvoice()
        database.writableDatabase.delete("daily_cash_sessions", null, null)
        try {
            payments.addPayment(invoiceId, "no-drawer", cashDrawerId, 1000L)
            throw AssertionError("Missing primary daily drawer must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            payments.addPayment(invoiceId, "usd-collection", usdAccountId, 1000L)
            throw AssertionError("USD collection must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertTrue(payments.listPayments(invoiceId).isEmpty())
    }

    @Test
    fun overpaymentIsRejectedWithoutChangingInvoiceOrCashAccount() {
        val invoiceId = createCreditInvoice()
        try {
            payments.addPayment(invoiceId, "customer-overpay", cashDrawerId, 75_001L)
            throw AssertionError("Overpayment must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertTrue(payments.listPayments(invoiceId).isEmpty())
        assertEquals("POSTED", invoices.listSalesInvoices().first { it.id == invoiceId }.status)
        assertEquals(0L, CashAccountRepository(RuntimeEnvironment.getApplication()).balance(cashDrawerId))
    }

    @Test
    fun cashSaleCannotReceiveCustomerCreditCollection() {
        val draft = invoices.saveSalesDraft(
            "customer-cash-draft-" + UUID.randomUUID(), null, customerId, "2026-10-31",
            0L, null, listOf(InvoiceLineInput(productId, BigDecimal.ONE, 500L)),
            saleType = "CASH"
        )
        invoices.postCashSale(draft.invoiceId, "customer-cash-post-" + UUID.randomUUID(), cashDrawerId)
        val receiptsBefore = payments.listPayments(draft.invoiceId).size
        try {
            payments.addPayment(draft.invoiceId, "customer-cash-payment-" + UUID.randomUUID(), cashDrawerId, 1000L)
            throw AssertionError("Cash sale must not be collected as customer credit")
        } catch (_: IllegalArgumentException) { }
        assertEquals(receiptsBefore, payments.listPayments(draft.invoiceId).size)
    }

    @Test
    fun revaluationLedgerRecordsRateChangeWithoutChangingInventory() {
        val invoiceId = createCreditInvoice()
        val stockBefore = stockQuantity()
        insertRate(20_000_000L, "2026-11-03T00:00:00.000Z")
        val listed = payments.listInvoices(customerId).first { it.id == invoiceId }
        assertEquals(100_000L, listed.outstandingSypMinor)
        val entries = payments.listRevaluations(invoiceId)
        assertEquals(1, entries.size)
        assertEquals(75_000L, entries.single().previousSypMinor)
        assertEquals(100_000L, entries.single().newSypMinor)
        payments.listInvoices(customerId)
        assertEquals(1, payments.listRevaluations(invoiceId).size)
        assertEquals(stockBefore, stockQuantity(), 0.000001)
    }

    private fun createCreditInvoice(): String {
        val draft = invoices.saveSalesDraft(
            "customer-credit-draft-" + UUID.randomUUID(), null, customerId, "2026-10-31",
            0L, null, listOf(InvoiceLineInput(productId, BigDecimal.ONE, 500L)),
            saleType = "CREDIT"
        )
        invoices.postSale(draft.invoiceId, "customer-credit-post-" + UUID.randomUUID())
        return draft.invoiceId
    }

    private fun insertRate(rate: Long, effectiveAt: String) {
        val values = ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("currency_code", "SYP")
            put("rate_syp_per_usd_milli", rate); put("effective_at", effectiveAt)
            put("created_at", effectiveAt)
        }
        database.writableDatabase.insertOrThrow("exchange_rates", null, values)
    }

    private fun insertAccount(name: String, type: String, currency: String): String {
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id); put("name", name); put("account_type", type); put("currency_code", currency)
            put("opening_balance_minor", 0L); put("is_active", 1); put("created_at", "2026-10-31T00:00:00.000Z")
        }
        database.writableDatabase.insertOrThrow("cash_accounts", null, values)
        return id
    }

    private fun stockQuantity(): Double =
        database.readableDatabase.rawQuery(
            "SELECT quantity FROM inventory_balances WHERE product_id=?", arrayOf(productId)
        ).use { if (it.moveToFirst()) it.getDouble(0) else 0.0 }

    private fun inventoryMovementCount(invoiceId: String): Int =
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM inventory_movements WHERE source_id=? AND source_type='SALES_INVOICE'",
            arrayOf(invoiceId)
        ).use { it.moveToFirst(); it.getInt(0) }
}
