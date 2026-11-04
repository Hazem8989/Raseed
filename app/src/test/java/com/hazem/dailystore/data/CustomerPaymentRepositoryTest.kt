package com.hazem.dailystore.data

import android.content.ContentValues
import java.math.BigDecimal
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
import javax.crypto.spec.SecretKeySpec
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
    private lateinit var adminId: String

    @Before
    fun setUp() {
        SyncDeviceTestKeyProvider.install()
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
        customers = CustomerRepository(context)
        invoices = InvoiceRepository(context)
        payments = CustomerPaymentRepository(context)
        database.writableDatabase.delete("exchange_rates", null, null)
        database.writableDatabase.delete("daily_cash_sessions", null, null)
        customerId = customers.create(adminId, "زبون تحصيل " + UUID.randomUUID()).id
        val unit = store.getUnits().first()
        val category = store.getCategories().first()
        productId = store.saveProduct(null, "صنف تحصيل " + UUID.randomUUID(), null, null,
            category.id, unit.id, 100L, 500L, true, 20.0, adminId)
        insertRate(15_000_000L, "2026-10-31T00:00:00.000Z")
        cashDrawerId = insertAccount("درج اليوم", "CASH_BOX", "SYP")
        walletId = insertAccount("محفظة اختبار", "WALLET", "SYP")
        usdAccountId = insertAccount("حساب دولار قديم", "CASH_BOX", "USD")
        val session = ContentValues().apply {
            put("operating_date", SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Damascus") }.format(Date()))
            put("primary_cash_account_id", cashDrawerId)
            putNull("created_by")
            put("created_at", "2026-10-31T00:00:00.000Z")
        }
        database.writableDatabase.insertOrThrow("daily_cash_sessions", null, session)
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun customerStatementReconcilesCreditInvoiceAndCollectionInUsdReference() {
        val invoiceId = createCreditInvoice()
        val before = PartyStatementRepository(RuntimeEnvironment.getApplication()).customer(customerId)
        assertEquals(1, before.entries.size)
        assertEquals(500L, before.closingBalanceUsdCents)
        assertEquals(75000L, before.closingBalanceSypMinor)

        payments.addPayment(adminId, invoiceId, "customer-statement-collection", cashDrawerId, 15_000L)
        val statement = PartyStatementRepository(RuntimeEnvironment.getApplication()).customer(customerId)
        assertEquals(2, statement.entries.size)
        assertEquals(400L, statement.closingBalanceUsdCents)
        assertEquals(60_000L, statement.closingBalanceSypMinor)
        assertEquals(1, statement.entries.count { it.type == "CUSTOMER_COLLECTION" })
        assertEquals(0, statement.unpricedEntryCount)
    }

    @Test
    fun usdCollectionReducesBothCustomerStatementBalances() {
        val invoiceId = createCreditInvoice()
        val cashId = UUID.randomUUID().toString()
        database.writableDatabase.insertOrThrow("cash_transactions", null, ContentValues().apply {
            put("id", cashId); put("account_id", usdAccountId); put("direction", "IN")
            put("amount_minor", 100L); put("currency_code", "USD")
            put("exchange_rate_syp_per_usd_milli", 15_000_000L)
            put("transaction_type", "CUSTOMER_CREDIT_COLLECTION")
            put("source_type", "CUSTOMER_CREDIT_COLLECTION"); put("source_id", invoiceId)
            put("created_by", adminId); put("created_at", "2026-11-02T10:00:00.000Z")
            put("operating_date", "2026-11-02")
        })
        database.writableDatabase.insertOrThrow("invoice_payments", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("invoice_type", "SALE")
            put("invoice_id", invoiceId); put("cash_transaction_id", cashId)
            put("amount_minor", 100L); put("currency_code", "USD")
            put("exchange_rate_syp_per_usd_milli", 15_000_000L)
            put("created_at", "2026-11-02T10:00:00.000Z")
        })
        val statement = PartyStatementRepository(RuntimeEnvironment.getApplication()).customer(customerId)
        assertEquals(400L, statement.closingBalanceUsdCents)
        assertEquals(60_000L, statement.closingBalanceSypMinor)
        assertEquals(0, statement.unpricedEntryCount)
    }

    @Test
    fun sypCashCollectionIsFinancialOnlyAndIdempotent() {
        val invoiceId = createCreditInvoice()
        val stockBefore = stockQuantity()
        val movementCountBefore = inventoryMovementCount(invoiceId)
        val first = payments.addPayment(adminId, invoiceId, "customer-pay-first", cashDrawerId, 15_000L, "دفعة أولى")
        assertFalse(first.replayed)
        assertEquals(100L, first.paidUsdCents)
        assertEquals(400L, first.outstandingUsdCents)
        assertEquals(60_000L, first.outstandingSypMinor)

        val replay = payments.addPayment(adminId, invoiceId, "customer-pay-first", cashDrawerId, 15_000L, "دفعة أولى")
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

        // Debt collection belongs to the collection day, is reported separately from sales,
        // increases only the selected cash account, and never posts a second stock movement.
        val collectionDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Damascus") }.format(Date())
        val dailyReport = DailyBalanceRepository(RuntimeEnvironment.getApplication()).dailyReport(collectionDate)
        val drawerSummary = dailyReport.accounts.single { it.accountId == cashDrawerId }
        assertEquals(0L, drawerSummary.salesReceiptsMinor)
        assertEquals(15_000L, drawerSummary.debtCollectionsMinor)
        assertEquals(15_000L, drawerSummary.electronicReceiptsMinor)
        assertEquals(15_000L, dailyReport.physicalCashReceiptsByCurrency["SYP"])
        assertEquals(stockBefore, stockQuantity(), 0.000001)
        assertEquals(movementCountBefore, inventoryMovementCount(invoiceId))
    }

    @Test
    fun customerCollectionDoesNotMoveSaleRevenueToCollectionDay() {
        val invoiceId = createCreditInvoice()
        val db = database.writableDatabase
        db.execSQL(
            "UPDATE sales_invoices SET posted_at='2026-10-31T10:00:00.000Z',posted_operating_date='2026-10-31',updated_at='2026-10-31T10:00:00.000Z' WHERE id=?",
            arrayOf(invoiceId)
        )
        // Simulate a later collection that updates the invoice's mutable updated_at field.
        payments.addPayment(adminId, invoiceId, "collection-after-sale-date", cashDrawerId, 15_000L)
        db.execSQL(
            "UPDATE sales_invoices SET updated_at='2026-11-02T10:00:00.000Z' WHERE id=?",
            arrayOf(invoiceId)
        )
        val saleDay = SalesProfitRepository(RuntimeEnvironment.getApplication())
            .summarize("2026-10-31", "2026-10-31")
        val collectionDay = SalesProfitRepository(RuntimeEnvironment.getApplication())
            .summarize("2026-11-02", "2026-11-02")
        assertEquals(1, saleDay.invoiceCount)
        assertEquals(500L, saleDay.revenueUsdCents)
        assertEquals(0, collectionDay.invoiceCount)
        assertEquals(0L, collectionDay.revenueUsdCents)
        assertEquals("2026-10-31T10:00:00.000Z",
            db.rawQuery("SELECT posted_at FROM sales_invoices WHERE id=?", arrayOf(invoiceId))
                .use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
    }

    @Test
    fun collectionUsesGreaterOfCurrentAndOriginalRate() {
        val invoiceId = createCreditInvoice()
        insertRate(20_000_000L, "2026-11-01T00:00:00.000Z")
        val result = payments.addPayment(adminId, invoiceId, "customer-high-rate", cashDrawerId, 20_000L)
        assertEquals(100L, result.paidUsdCents)
        assertEquals(400L, result.outstandingUsdCents)
        assertEquals(80_000L, result.outstandingSypMinor)
        assertEquals(20_000_000L, payments.listPayments(invoiceId).single().effectiveRateMilli)
    }

    @Test
    fun fallingRateCannotReduceDebtBelowOriginalInvoiceRateFloor() {
        val invoiceId = createCreditInvoice()
        insertRate(10_000_000L, "2026-11-02T00:00:00.000Z")
        val result = payments.addPayment(adminId, invoiceId, "customer-low-rate", cashDrawerId, 15_000L)
        assertEquals(100L, result.paidUsdCents)
        assertEquals(400L, result.outstandingUsdCents)
        assertEquals(60_000L, result.outstandingSypMinor)
        assertEquals(15_000_000L, payments.listPayments(invoiceId).single().effectiveRateMilli)
    }

    @Test
    fun electronicCollectionIncreasesOnlySelectedWallet() {
        val invoiceId = createCreditInvoice()
        val cashBefore = CashAccountRepository(RuntimeEnvironment.getApplication()).balance(cashDrawerId)
        val result = payments.addPayment(adminId, invoiceId, "customer-wallet", walletId, 30_000L)
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
            payments.addPayment(adminId, invoiceId, "no-drawer", cashDrawerId, 1000L)
            throw AssertionError("Missing primary daily drawer must be rejected")
        } catch (_: IllegalArgumentException) { }
        try {
            payments.addPayment(adminId, invoiceId, "usd-collection", usdAccountId, 1000L)
            throw AssertionError("USD collection must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertTrue(payments.listPayments(invoiceId).isEmpty())
    }

    @Test
    fun overpaymentIsRejectedWithoutChangingInvoiceOrCashAccount() {
        val invoiceId = createCreditInvoice()
        try {
            payments.addPayment(adminId, invoiceId, "customer-overpay", cashDrawerId, 75_001L)
            throw AssertionError("Overpayment must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertTrue(payments.listPayments(invoiceId).isEmpty())
        assertEquals("POSTED", invoices.listSalesInvoices().first { it.id == invoiceId }.status)
        assertEquals(0L, CashAccountRepository(RuntimeEnvironment.getApplication()).balance(cashDrawerId))
    }

    @Test
    fun cashSaleCannotReceiveCustomerCreditCollection() {
        val draft = invoices.saveSalesDraft(adminId, 
            "customer-cash-draft-" + UUID.randomUUID(), null, customerId, "2026-10-31",
            0L, null, listOf(InvoiceLineInput(productId, BigDecimal.ONE, 500L)),
            saleType = "CASH"
        )
        invoices.postCashSale(adminId, draft.invoiceId, "customer-cash-post-" + UUID.randomUUID(), cashDrawerId)
        val receiptsBefore = payments.listPayments(draft.invoiceId).size
        try {
            payments.addPayment(adminId, draft.invoiceId, "customer-cash-payment-" + UUID.randomUUID(), cashDrawerId, 1000L)
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
        val draft = invoices.saveSalesDraft(adminId, 
            "customer-credit-draft-" + UUID.randomUUID(), null, customerId, "2026-10-31",
            0L, null, listOf(InvoiceLineInput(productId, BigDecimal.ONE, 500L)),
            saleType = "CREDIT"
        )
        invoices.postSale(adminId, draft.invoiceId, "customer-credit-post-" + UUID.randomUUID())
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

    @Test
    fun secondaryCustomerCollectionIsQueuedWithoutLocalFinancialPosting() {
        val context = RuntimeEnvironment.getApplication()
        val invoiceId = createCreditInvoice()
        val sync = SyncProtocolRepository(context, SyncCredentialVault(context) { SecretKeySpec(ByteArray(32) { 13 }, "AES") })
        // Give this isolated secondary fixture a store identity without changing its local role to PRIMARY.
        val storeId = UUID.randomUUID().toString()
        val primaryDeviceId = UUID.randomUUID().toString()
        val credential = android.util.Base64.encodeToString(
            ByteArray(32) { 11 },
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        sync.configureAsSecondary(adminId, storeId, primaryDeviceId, credential, "جهاز ثانوي اختباري")

        val operationId = "secondary-customer-collection-" + UUID.randomUUID()
        val result = payments.addPayment(adminId, invoiceId, operationId, cashDrawerId, 15_000L)

        assertFalse(result.replayed)
        assertTrue(payments.listPayments(invoiceId).isEmpty())
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_transactions WHERE operation_id=?", arrayOf(operationId)
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        val queued = sync.pendingOutbox().single { it.operationId == operationId }
        assertEquals("CUSTOMER_PAYMENT", queued.entityType)
        assertTrue(queued.payloadJson.contains("\"usd_reference_reduction_cents\":100"))
    }

    @Test
    fun primaryCustomerCollectionIsAddedToOutboxAtomically() {
        val context = RuntimeEnvironment.getApplication()
        val sync = SyncProtocolRepository(context)
        sync.configureAsPrimary(adminId, "الهاتف الرئيسي")
        val before = sync.pendingOutbox().size
        val invoiceId = createCreditInvoice()
        payments.addPayment(adminId, invoiceId, "sync-customer-collection-" + UUID.randomUUID(),
            cashDrawerId, 15000L, "تحصيل للمزامنة")
        val queued = sync.pendingOutbox()
        assertEquals(before + 2, queued.size)
        assertTrue(queued.any { it.entityType == "CUSTOMER_PAYMENT" && it.operationType == "CREATE" &&
            it.payloadJson.contains("CUSTOMER_CREDIT_COLLECTION") })
    }

}
