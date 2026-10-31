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
class CustomerPaymentRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var customers: CustomerRepository
    private lateinit var invoices: InvoiceRepository
    private lateinit var payments: CustomerPaymentRepository
    private lateinit var customerId: String
    private lateinit var accountId: String
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
        customerId = customers.create("زبون تحصيل " + UUID.randomUUID()).id
        val unit = store.getUnits().first()
        val category = store.getCategories().first()
        productId = store.saveProduct(null, "صنف تحصيل " + UUID.randomUUID(), null, null,
            category.id, unit.id, 100L, 500L, true, 20.0)
        val rate = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("currency_code", "SYP")
            put("rate_syp_per_usd_milli", 15000000L)
            put("effective_at", "2026-10-31T00:00:00.000Z")
            put("created_at", "2026-10-31T00:00:00.000Z")
        }
        database.writableDatabase.insertOrThrow("exchange_rates", null, rate)
        accountId = UUID.randomUUID().toString()
        val account = ContentValues().apply {
            put("id", accountId); put("name", "صندوق اختبار")
            put("account_type", "CASH_BOX"); put("currency_code", "USD")
            put("opening_balance_minor", 0L); put("is_active", 1)
            put("created_at", "2026-10-31T00:00:00.000Z")
        }
        database.writableDatabase.insertOrThrow("cash_accounts", null, account)
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun partialPaymentsAreAllocatedToSelectedCreditInvoiceAndRetryIsIdempotent() {
        val invoiceId = createCreditInvoice()
        val first = payments.addPayment(invoiceId, "customer-pay-first", accountId, 200L, "دفعة أولى")
        assertFalse(first.replayed)
        assertEquals(200L, first.paidUsdCents)
        assertEquals(300L, first.outstandingUsdCents)
        assertEquals("PARTIALLY_PAID", first.paymentStatus)

        val replay = payments.addPayment(invoiceId, "customer-pay-first", accountId, 200L, "دفعة أولى")
        assertTrue(replay.replayed)
        assertEquals(first.paymentId, replay.paymentId)
        assertEquals(200L, replay.paidUsdCents)

        val second = payments.addPayment(invoiceId, "customer-pay-second", accountId, 300L)
        assertEquals(500L, second.paidUsdCents)
        assertEquals(0L, second.outstandingUsdCents)
        assertEquals("PAID", second.paymentStatus)
        assertEquals(2, payments.listPayments(invoiceId).size)
        assertEquals(0L, payments.listCustomerSummaries().first { it.id == customerId }.outstandingUsdCents)
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_transactions WHERE source_id=? AND transaction_type='CUSTOMER_PAYMENT'",
            arrayOf(invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0)) }
    }

    @Test
    fun overpaymentIsRejectedWithoutChangingInvoiceOrCashAccount() {
        val invoiceId = createCreditInvoice()
        try {
            payments.addPayment(invoiceId, "customer-overpay", accountId, 501L)
            throw AssertionError("Overpayment must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0, payments.listPayments(invoiceId).size)
        assertEquals("POSTED", invoices.listSalesInvoices().first { it.id == invoiceId }.status)
    }

    @Test
    fun cashSaleCannotReceiveCustomerCreditCollection() {
        val draft = invoices.saveSalesDraft(
            "customer-cash-draft-" + UUID.randomUUID(), null, customerId, "2026-10-31",
            0L, null, listOf(InvoiceLineInput(productId, BigDecimal.ONE, 500L)),
            saleType = "CASH"
        )
        invoices.postCashSale(draft.invoiceId, "customer-cash-post-" + UUID.randomUUID(), accountId)
        // A cash sale correctly creates its own receipt row; the rejected customer-credit
        // collection must not add a second payment row.
        val receiptsBefore = payments.listPayments(draft.invoiceId).size
        try {
            payments.addPayment(draft.invoiceId, "customer-cash-payment-" + UUID.randomUUID(), accountId, 100L)
            throw AssertionError("Cash sale must not be collected as customer credit")
        } catch (_: IllegalArgumentException) { }
        assertEquals(receiptsBefore, payments.listPayments(draft.invoiceId).size)
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
}
