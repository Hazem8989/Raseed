package com.hazem.dailystore.data

import android.content.ContentValues
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
class OperatingExpenseRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var adminId: String
    private lateinit var categoryId: String
    private lateinit var sypAccountId: String
    private lateinit var usdAccountId: String
    private lateinit var repository: OperatingExpenseRepository
    private lateinit var accounts: CashAccountRepository

    @Before fun setUp() {
        SyncDeviceTestKeyProvider.install()
        val context=RuntimeEnvironment.getApplication()
        AppDatabase.resetForTests(context)
        db=AppDatabase.getInstance(context)
        val store=StoreRepository(context); store.ensureBaseSetup()
        val auth=AuthRepository(context); auth.ensureInitialAdmin()
        adminId=auth.authenticate("admin","1234")!!.id
        auth.changePassword(adminId,"1234","TestAdmin123")
        db.writableDatabase.delete("exchange_rates",null,null)
        db.writableDatabase.insertOrThrow("exchange_rates",null,ContentValues().apply {
            put("id",UUID.randomUUID().toString()); put("currency_code","USD")
            put("rate_syp_per_usd_milli",10_000_000L)
            put("effective_at","2026-11-02T08:00:00.000Z"); put("created_at","2026-11-02T08:00:00.000Z")
        })
        repository=OperatingExpenseRepository(context)
        accounts=CashAccountRepository(context)
        categoryId=repository.listCategories().first().id
        sypAccountId=accounts.create(adminId,"صندوق مصروفات "+UUID.randomUUID(),"CASH_BOX","SYP",1000000L).id
        usdAccountId=accounts.create(adminId,"حساب دولار للمصروفات "+UUID.randomUUID(),"WALLET","USD",10000L).id
    }

    @After fun tearDown() { db.close() }

    @Test fun sypExpensePostsAtomicCashOutflowAndUsesPostingTimeExchangeRate() {
        val result=repository.postExpense(adminId,"expense-syp-"+UUID.randomUUID(),categoryId,sypAccountId,
            100000L,"2026-11-02","فاتورة صيانة","صيانة جهاز التبريد")
        assertFalse(result.replayed)
        assertEquals("SYP",result.currencyCode)
        assertEquals(1000L,result.amountUsdCents)
        assertEquals(900000L,accounts.balance(sypAccountId))
        assertEquals(1000L,repository.totalUsdCents("2026-11-02","2026-11-02"))
        val listed=repository.listExpenses("2026-11-02","2026-11-02")
        assertEquals(1,listed.size)
        assertEquals("فاتورة صيانة",listed.first().reference)
        assertEquals("صيانة جهاز التبريد",listed.first().notes)
        val cash=db.readableDatabase.rawQuery(
            "SELECT direction,transaction_type,source_type,source_id FROM cash_transactions WHERE id=?",
            arrayOf(result.cashTransactionId)
        ).use { c -> assertTrue(c.moveToFirst()); listOf(c.getString(0),c.getString(1),c.getString(2),c.getString(3)) }
        assertEquals("OUT",cash[0])
        assertEquals("OPERATING_EXPENSE",cash[1])
        assertEquals("OPERATING_EXPENSE",cash[2])
        assertEquals(result.expenseId,cash[3])
        val profit=SalesProfitRepository(RuntimeEnvironment.getApplication()).allTime()
        assertEquals(1,profit.operatingExpenseCount)
        assertEquals(1000L,profit.operatingExpensesUsdCents)
        assertEquals(-1000L,profit.netOperatingProfitUsdCents)
        val daily=DailyBalanceRepository(RuntimeEnvironment.getApplication()).dailyReport("2026-11-02")
        val dailyAccount=daily.accounts.first { it.accountId==sypAccountId }
        assertEquals(100000L,dailyAccount.operatingExpensesMinor)
        assertEquals(900000L,dailyAccount.closingMinor)
    }

    @Test fun usdExpenseIsRecordedWithoutRevaluingItAtLaterExchangeRates() {
        val result=repository.postExpense(adminId,"expense-usd-"+UUID.randomUUID(),categoryId,usdAccountId,
            2500L,"2026-11-01","اشتراك برنامج",null)
        assertEquals(2500L,result.amountUsdCents)
        assertEquals(7500L,accounts.balance(usdAccountId))
        db.writableDatabase.insertOrThrow("exchange_rates",null,ContentValues().apply {
            put("id",UUID.randomUUID().toString()); put("currency_code","USD")
            put("rate_syp_per_usd_milli",20_000_000L)
            put("effective_at","2026-11-03T08:00:00.000Z"); put("created_at","2026-11-03T08:00:00.000Z")
        })
        assertEquals(2500L,repository.totalUsdCents("2026-11-01","2026-11-01"))
        assertEquals(2500L,repository.listExpenses().first().amountUsdCents)
    }

    @Test fun operationRetryDoesNotDuplicateExpenseOrCashOutflow() {
        val op="expense-idempotent-"+UUID.randomUUID()
        val first=repository.postExpense(adminId,op,categoryId,sypAccountId,50000L,"2026-11-02","وقود",null)
        val retry=repository.postExpense(adminId,op,categoryId,sypAccountId,50000L,"2026-11-02","وقود",null)
        assertFalse(first.replayed)
        assertTrue(retry.replayed)
        assertEquals(first.expenseId,retry.expenseId)
        assertEquals(950000L,accounts.balance(sypAccountId))
        assertEquals(1,repository.listExpenses().size)
        try {
            repository.postExpense(adminId,op,categoryId,sypAccountId,60000L,"2026-11-02","وقود",null)
            throw AssertionError("A reused operation id with different payload must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(950000L,accounts.balance(sypAccountId))
    }

    @Test fun insufficientBalanceAndMissingSypExchangeRateAreRejectedAtomically() {
        try {
            repository.postExpense(adminId,"expense-overdraw-"+UUID.randomUUID(),categoryId,sypAccountId,
                1000001L,"2026-11-02",null,null)
            throw AssertionError("Expense must not overdraw the cash account")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1000000L,accounts.balance(sypAccountId))
        db.writableDatabase.delete("exchange_rates",null,null)
        try {
            repository.postExpense(adminId,"expense-no-rate-"+UUID.randomUUID(),categoryId,sypAccountId,
                100L,"2026-11-02",null,null)
            throw AssertionError("SYP expense must require a valid exchange-rate snapshot")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0,repository.listExpenses().size)
        assertEquals(1000000L,accounts.balance(sypAccountId))
    }

    @Test fun cashierCannotCreateCategoriesOrPostOperatingExpenses() {
        val auth=AuthRepository(RuntimeEnvironment.getApplication())
        val cashier=auth.createUser("ADMIN",adminId,"expense-cashier","Cashier","Cashier12345","CASHIER")
        auth.changePassword(cashier,"Cashier12345","CashierStrong123")
        try {
            repository.postExpense(cashier,"expense-denied-"+UUID.randomUUID(),categoryId,sypAccountId,
                100L,"2026-11-02",null,null)
            throw AssertionError("Cashier must not post expenses")
        } catch (_: IllegalArgumentException) { }
        try {
            repository.createCategory(cashier,"تصنيف غير مصرح")
            throw AssertionError("Cashier must not create expense categories")
        } catch (_: IllegalArgumentException) { }
        assertEquals(0,repository.listExpenses().size)
    }

    @Test fun primaryOperatingExpenseIsAddedToOutboxAtomically() {
        val context=RuntimeEnvironment.getApplication()
        val sync=SyncProtocolRepository(context)
        sync.configureAsPrimary(adminId,"الهاتف الرئيسي")
        repository.postExpense(adminId,"sync-expense-"+UUID.randomUUID(),categoryId,sypAccountId,
            10000L,"2026-11-02","مصروف للمزامنة",null)
        val queued=sync.pendingOutbox().single()
        assertEquals("OPERATING_EXPENSE",queued.entityType)
        assertEquals("CREATE",queued.operationType)
        assertTrue(queued.payloadJson.contains("POST_OPERATING_EXPENSE"))
        assertTrue(queued.payloadJson.contains("amount_usd_cents"))
    }

    // Sync-domain coverage is added below.

    @Test fun secondaryExpenseQueuesWithoutChangingLocalCashOrExpenseLedger() {
        val context=RuntimeEnvironment.getApplication()
        val vault=SyncCredentialVault(context) {
            javax.crypto.spec.SecretKeySpec(ByteArray(32) { 31 }, "AES")
        }
        repository=OperatingExpenseRepository(context,vault)
        val sync=SyncProtocolRepository(context,vault)
        val primaryId=UUID.randomUUID().toString()
        val credential=android.util.Base64.encodeToString(
            ByteArray(32) { (it+7).toByte() },
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        sync.configureAsSecondary(adminId,"expense-sync-store",primaryId,credential,"الجهاز الثانوي","Asia/Damascus")
        val before=accounts.balance(sypAccountId)
        val operationId="secondary-expense-"+UUID.randomUUID()
        val result=repository.postExpense(adminId,operationId,categoryId,sypAccountId,
            25000L,"2026-11-02","مصروف معلق","لا يرحل محلياً")
        assertEquals("pending-expense-$operationId",result.expenseId)
        assertEquals(before,accounts.balance(sypAccountId))
        assertTrue(repository.listExpenses().isEmpty())
        val queued=sync.pendingOutbox().single()
        assertEquals(operationId,queued.operationId)
        assertEquals("OPERATING_EXPENSE",queued.entityType)
        assertTrue(queued.payloadJson.contains("POST_OPERATING_EXPENSE"))
    }


    @Test fun primaryAppliesSecondaryExpenseOnceAndPreservesOriginalEventTimeAndDate() {
        val context=RuntimeEnvironment.getApplication()
        val vault=SyncCredentialVault(context) {
            javax.crypto.spec.SecretKeySpec(ByteArray(32) { 37 }, "AES")
        }
        val sync=SyncProtocolRepository(context,vault)
        sync.configureAsPrimary(adminId,"الجهاز الرئيسي")
        val invitation=sync.createPairingInvitation(adminId)
        val secondaryId=UUID.randomUUID().toString()
        sync.enrollSecondaryDevice(adminId,invitation.token,secondaryId,"الجهاز الثانوي")
        sync.mapSecondaryActor(adminId,secondaryId,"remote-admin",adminId)
        sync.mapSecondaryCashAccount(adminId,secondaryId,"remote-expense-wallet",sypAccountId)
        val operationId="incoming-expense-"+UUID.randomUUID()
        val expenseId="pending-expense-$operationId"
        val occurredAt="2026-11-03T12:34:56.000Z"
        val payload=org.json.JSONObject()
            .put("business_operation","POST_OPERATING_EXPENSE")
            .put("expense_id",expenseId)
            .put("cash_transaction_id","pending-cash-$operationId")
            .put("category_id",categoryId)
            .put("cash_account_id","remote-expense-wallet")
            .put("cash_account_type","CASH_BOX")
            .put("cash_account_currency_code","SYP")
            .put("amount_minor",25000L)
            .put("currency_code","SYP")
            .put("exchange_rate_syp_per_usd_milli",10_000_000L)
            .put("amount_usd_cents",250L)
            .put("expense_date","2026-11-02")
            .put("operating_date","2026-11-03")
            .put("occurred_at",occurredAt)
            .put("reference","فاتورة صيانة")
            .put("notes","حركة من الثانوي")
        val secret=vault.load(secondaryId)!!
        val storeId=db.readableDatabase.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='store_id'",null
        ).use { assertTrue(it.moveToFirst());it.getString(0) }
        val envelope=SyncEnvelopeSecurity.create(
            storeId,operationId,1L,secondaryId,"remote-admin",
            "CREATE","OPERATING_EXPENSE",expenseId,0L,payload.toString(),occurredAt,secret
        )
        secret.fill(0)
        assertEquals(SyncInboxDisposition.RECEIVED,
            sync.recordIncomingFromSecondary(SyncEnvelopeSecurity.encode(envelope)))
        val applier=SyncDomainApplier(context)
        assertTrue(applier.applyReceivedOperatingExpense(operationId).applied)
        assertTrue(applier.applyReceivedOperatingExpense(operationId).applied)
        assertEquals(975000L,accounts.balance(sypAccountId))
        val posted=repository.listExpenses().single()
        assertEquals(25000L,posted.amountMinor)
        assertEquals("2026-11-02",posted.date)
        assertEquals(occurredAt,posted.createdAt)
        assertEquals("فاتورة صيانة",posted.reference)
        db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_transactions WHERE operation_id=?",
            arrayOf(operationId+":CASH")
        ).use { assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0)) }
        db.readableDatabase.rawQuery(
            "SELECT status FROM sync_inbox WHERE operation_id=?",arrayOf(operationId)
        ).use { assertTrue(it.moveToFirst());assertEquals("APPLIED",it.getString(0)) }
    }

}
