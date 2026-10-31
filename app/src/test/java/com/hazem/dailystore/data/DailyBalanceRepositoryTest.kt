package com.hazem.dailystore.data

import android.content.ContentValues
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
class DailyBalanceRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var accounts: CashAccountRepository
    private lateinit var daily: DailyBalanceRepository
    private lateinit var actorId: String
    private val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = AppDatabase.getInstance(context)
        StoreRepository(context).ensureBaseSetup()
        val auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        actorId = auth.authenticate("admin", "1234")!!.id
        accounts = CashAccountRepository(context)
        daily = DailyBalanceRepository(context)
    }

    @After fun tearDown() { database.close() }

    @Test fun walletSalesAndDebtCollectionsBothAppearInLiquidatableElectronicReceipts() {
        val wallet = accounts.create("محفظة تقرير " + UUID.randomUUID(), "WALLET", "SYP", 0L)
        insertMovement(wallet.id, "SALE_RECEIPT", 40_000L, "sale-report")
        insertMovement(wallet.id, "CUSTOMER_CREDIT_COLLECTION", 20_000L, "debt-report")
        val report = daily.dailyReport()
        assertEquals(60_000L, report.electronicReceiptsByCurrency["SYP"])
        val row = report.accounts.single { it.accountId == wallet.id }
        assertEquals(40_000L, row.salesReceiptsMinor)
        assertEquals(20_000L, row.debtCollectionsMinor)
        assertEquals(60_000L, row.electronicReceiptsMinor)
        assertEquals(0L, report.physicalCashReceiptsByCurrency["SYP"] ?: 0L)
    }

    @Test fun walletToDrawerTransferIsAtomicIdempotentAndNotNewSales() {
        val drawer = accounts.create("درج تحويل " + UUID.randomUUID(), "CASH_BOX", "SYP", 0L)
        val wallet = accounts.create("محفظة تحويل " + UUID.randomUUID(), "WALLET", "SYP", 100_000L)
        accounts.setCurrentDayPrimaryDrawer("ADMIN", actorId, drawer.id)
        val first = daily.transferWalletToDrawer(actorId, wallet.id, 25_000L, "transfer-" + UUID.randomUUID())
        assertFalse(first.replayed)
        assertEquals(75_000L, accounts.balance(wallet.id))
        assertEquals(25_000L, accounts.balance(drawer.id))
        val replay = daily.transferWalletToDrawer(actorId, wallet.id, 25_000L,
            database.readableDatabase.rawQuery("SELECT operation_id FROM cash_account_transfers WHERE id=?", arrayOf(first.transferId)).use {
                it.moveToFirst(); it.getString(0)
            })
        assertTrue(replay.replayed)
        assertEquals(75_000L, accounts.balance(wallet.id))
        assertEquals(25_000L, accounts.balance(drawer.id))
        val report = daily.dailyReport()
        assertEquals(25_000L, report.accounts.single { it.accountId == wallet.id }.transferOutMinor)
        assertEquals(25_000L, report.accounts.single { it.accountId == drawer.id }.transferInMinor)
        assertEquals(0L, report.accounts.single { it.accountId == drawer.id }.salesReceiptsMinor)
    }

    @Test fun dailyWalletOpeningAndTopUpAreAuditedAndNotDoubleCounted() {
        val wallet = accounts.create("محفظة افتتاح " + UUID.randomUUID(), "WALLET", "SYP", 10_000L)
        daily.setWalletOpening(actorId, wallet.id, 15_000L, "مطابقة كشف المحفظة")
        daily.addWalletTopUp(actorId, wallet.id, 5_000L, "topup-once", "REC-1")
        daily.addWalletTopUp(actorId, wallet.id, 5_000L, "topup-once", "REC-1")
        assertEquals(20_000L, accounts.balance(wallet.id))
        val row = daily.dailyReport().accounts.single { it.accountId == wallet.id }
        assertEquals(15_000L, row.openingMinor)
        assertEquals(5_000L, row.otherInflowsMinor)
        assertEquals(20_000L, row.closingMinor)
    }

    @Test fun supplierRolloverIsSeparateAndPersonalWithdrawalsReduceItsAvailability() {
        val drawer = accounts.create("درج رئيسي " + UUID.randomUUID(), "CASH_BOX", "SYP", 0L)
        val supplierCash = accounts.create("نقد الموردين " + UUID.randomUUID(), "CASH_BOX", "SYP", 100_000L)
        accounts.setCurrentDayPrimaryDrawer("ADMIN", actorId, drawer.id)
        val initial = daily.setSupplierRollover(actorId, supplierCash.id, 80_000L, "من كشف أمس")
        assertEquals(80_000L, initial.availableMinor)
        daily.addPersonalWithdrawal(actorId, supplierCash.id, 15_000L, "personal-" + UUID.randomUUID(), "سحب شخصي")
        val updated = daily.currentSupplierRollover()!!
        assertEquals(65_000L, updated.availableMinor)
        assertEquals(0L, accounts.balance(drawer.id))
        assertEquals(85_000L, accounts.balance(supplierCash.id))
    }

    private fun insertMovement(accountId: String, type: String, amount: Long, operation: String) {
        val v = ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("account_id", accountId)
            put("direction", "IN"); put("amount_minor", amount); put("currency_code", "SYP")
            put("transaction_type", type); put("source_type", type); put("source_id", operation)
            put("operation_id", operation); put("operating_date", date)
            put("created_at", date + "T12:00:00.000Z")
        }
        database.writableDatabase.insertOrThrow("cash_transactions", null, v)
    }
}
