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
class PurchasePaymentRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var invoices: InvoiceRepository
    private lateinit var payments: PurchasePaymentRepository
    private lateinit var cashAccounts: CashAccountRepository
    private lateinit var supplierId: String
    private lateinit var invoiceId: String
    private lateinit var accountId: String
    private lateinit var adminId: String

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = AppDatabase.getInstance(context)
        val store = StoreRepository(context)
        store.ensureBaseSetup()
        invoices = InvoiceRepository(context)
        payments = PurchasePaymentRepository(context)
        cashAccounts = CashAccountRepository(context)
        val auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        adminId = auth.authenticate("admin", "1234")!!.id

        supplierId = SupplierRepository(context).create("مورد دفع ${UUID.randomUUID()}").id
        val unit = store.getUnits().first()
        val category = store.getCategories().first()
        val productId = store.saveProduct(
            null, "صنف دفع ${UUID.randomUUID()}", null, null, category.id, unit.id,
            500, 1000, true, 0.0
        )
        val draft = invoices.savePurchaseDraft(
            "payment-draft-${UUID.randomUUID()}", null, supplierId, "2026-10-31",
            0L, null, listOf(InvoiceLineInput(productId, BigDecimal.ONE, 10000L))
        )
        invoiceId = draft.invoiceId
        assertTrue(invoices.postPurchase(invoiceId, "payment-post-${UUID.randomUUID()}"))
        accountId = cashAccounts.create(
            "صندوق اختبار ${UUID.randomUUID()}", "CASH_BOX", "USD", 50000L
        ).id
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun partialAndFinalPaymentsUpdateInvoiceAndCashBalances() {
        val first = payments.addPayment(invoiceId, "pay-part-1-${UUID.randomUUID()}",
            accountId, 4000L, "دفعة أولى")
        assertEquals(4000L, first.paidUsdCents)
        assertEquals(6000L, first.outstandingUsdCents)
        assertEquals("PARTIALLY_PAID", first.paymentStatus)
        assertEquals(46000L, cashAccounts.balance(accountId))

        val second = payments.addPayment(invoiceId, "pay-part-2-${UUID.randomUUID()}",
            accountId, 6000L, "تسديد نهائي")
        assertEquals(10000L, second.paidUsdCents)
        assertEquals(0L, second.outstandingUsdCents)
        assertEquals("PAID", second.paymentStatus)
        assertEquals(40000L, cashAccounts.balance(accountId))
        assertEquals("PAID", payments.listPurchaseInvoices().first { it.id == invoiceId }.paymentStatus)
    }

    @Test
    fun retryWithSameOperationDoesNotCreateSecondPaymentOrCashOutflow() {
        val operation = "pay-retry-${UUID.randomUUID()}"
        val first = payments.addPayment(invoiceId, operation, accountId, 2500L)
        val retry = payments.addPayment(invoiceId, operation, accountId, 2500L)
        assertFalse(first.replayed)
        assertTrue(retry.replayed)
        assertEquals(first.paymentId, retry.paymentId)
        assertEquals(47500L, cashAccounts.balance(accountId))
        assertEquals(1, payments.listPayments(invoiceId).size)
    }

    @Test
    fun rejectsPaymentAboveOutstandingWithoutChangingCashOrInvoice() {
        try {
            payments.addPayment(invoiceId, "pay-over-${UUID.randomUUID()}", accountId, 10001L)
            throw AssertionError("Overpayment must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation.
        }
        assertEquals(50000L, cashAccounts.balance(accountId))
        assertTrue(payments.listPayments(invoiceId).isEmpty())
        assertEquals("UNPAID", payments.listPurchaseInvoices().first { it.id == invoiceId }.paymentStatus)
    }

    @Test
    fun rejectsPaymentWhenCashAccountDoesNotHaveEnoughFunds() {
        val poor = cashAccounts.create("صندوق محدود ${UUID.randomUUID()}",
            "CASH_BOX", "USD", 100L)
        try {
            payments.addPayment(invoiceId, "pay-poor-${UUID.randomUUID()}", poor.id, 200L)
            throw AssertionError("Insufficient cash balance must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation.
        }
        assertEquals(100L, cashAccounts.balance(poor.id))
        assertTrue(payments.listPayments(invoiceId).isEmpty())
    }

    @Test
    fun syrianPoundPaymentUsesAndRetainsItsExchangeRateSnapshot() {
        val rate = 10_000_000L
        val rateValues = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("currency_code", "SYP")
            put("rate_syp_per_usd_milli", rate)
            put("effective_at", "2026-10-31T10:00:00.000Z")
            put("created_at", "2026-10-31T10:00:00.000Z")
        }
        database.writableDatabase.insertOrThrow("exchange_rates", null, rateValues)
        val syp = cashAccounts.create("محفظة ليرة ${UUID.randomUUID()}",
            "WALLET", "SYP", 500000L)
        val result = payments.addPayment(invoiceId, "pay-syp-${UUID.randomUUID()}",
            syp.id, 100000L)
        assertEquals(1000L, result.paidUsdCents)
        assertEquals(9000L, result.outstandingUsdCents)

        val newerRate = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("currency_code", "SYP")
            put("rate_syp_per_usd_milli", 20_000_000L)
            put("effective_at", "2026-11-01T10:00:00.000Z")
            put("created_at", "2026-11-01T10:00:00.000Z")
        }
        database.writableDatabase.insertOrThrow("exchange_rates", null, newerRate)
        assertEquals(1000L, payments.listPurchaseInvoices().first { it.id == invoiceId }.paidUsdCents)
        database.readableDatabase.rawQuery(
            "SELECT exchange_rate_syp_per_usd_milli FROM invoice_payments WHERE invoice_id=?",
            arrayOf(invoiceId)
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(rate, it.getLong(0))
        }
    }

    @Test
    fun managerCanCorrectPaymentAmountAndAccountAtomically() {
        val payment = payments.addPayment(invoiceId, "pay-edit-${UUID.randomUUID()}", accountId, 4000L, "قبل التصحيح")
        val replacement = cashAccounts.create("صندوق بديل ${UUID.randomUUID()}", "CASH_BOX", "USD", 10000L)

        val corrected = payments.editPayment(
            "ADMIN", adminId, payment.paymentId, replacement.id, 3500L, "تصحيح دفعة"
        )

        assertEquals(3500L, corrected.paidUsdCents)
        assertEquals(6500L, corrected.outstandingUsdCents)
        assertEquals("PARTIALLY_PAID", corrected.paymentStatus)
        assertEquals(50000L, cashAccounts.balance(accountId))
        assertEquals(6500L, cashAccounts.balance(replacement.id))
        val history = payments.listPayments(invoiceId).single()
        assertEquals(3500L, history.amountMinor)
        assertEquals(replacement.id, history.cashAccountId)
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type='PURCHASE_PAYMENT' AND entity_id=?",
            arrayOf(payment.paymentId)
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
    }

    @Test
    fun cashierCannotCorrectSupplierPayment() {
        val payment = payments.addPayment(invoiceId, "pay-edit-denied-${UUID.randomUUID()}", accountId, 2000L)
        try {
            payments.editPayment("CASHIER", "cashier-id", payment.paymentId, accountId, 1000L, "غير مسموح")
            throw AssertionError("Cashier must not edit supplier payments")
        } catch (_: IllegalArgumentException) { }
        assertEquals(48000L, cashAccounts.balance(accountId))
        assertEquals(2000L, payments.listPayments(invoiceId).single().amountMinor)
    }

    @Test
    fun supplierSummaryMatchesInvoicesAndPayments() {
        payments.addPayment(invoiceId, "pay-supplier-${UUID.randomUUID()}", accountId, 3000L)
        val supplier = payments.listSupplierSummaries().first { it.id == supplierId }
        assertEquals(10000L, supplier.totalPurchaseUsdCents)
        assertEquals(3000L, supplier.paidUsdCents)
        assertEquals(7000L, supplier.outstandingUsdCents)
        assertEquals(1, supplier.unpaidInvoiceCount)
    }
}
