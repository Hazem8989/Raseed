package com.hazem.dailystore.data

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
class ServiceTransferRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var accounts: CashAccountRepository
    private lateinit var repo: ServiceTransferRepository
    private lateinit var actor: String

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = AppDatabase.getInstance(context)
        StoreRepository(context).ensureBaseSetup()
        val auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        actor = auth.authenticate("admin", "1234")!!.id
        accounts = CashAccountRepository(context)
        repo = ServiceTransferRepository(context)
    }

    @After fun tearDown() { database.close() }

    @Test fun syriatelBalanceAndCashShareFloatAndReceiptIsCreditedInFull() {
        val drawer = accounts.create("درج خدمة " + UUID.randomUUID(), "CASH_BOX", "SYP", 0L)
        accounts.setCurrentDayPrimaryDrawer("ADMIN", actor, drawer.id)
        repo.setOpeningBalance(actor, ServiceTransferRepository.SYRIATEL_SHARED, 100_000L)
        val first = repo.executeTransfer(actor, ServiceTransferRepository.SYRIATEL_BALANCE,
            10_000L, 500L, drawer.id, "svc-normal-" + UUID.randomUUID())
        assertFalse(first.replayed)
        assertEquals(2_000L, first.profitMinor)
        assertEquals(12_500L, first.customerChargeMinor)
        assertEquals(12_500L, accounts.balance(drawer.id))
        assertEquals(90_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)

        val second = repo.executeTransfer(actor, ServiceTransferRepository.SYRIATEL_CASH,
            20_000L, 0L, drawer.id, "svc-cash-" + UUID.randomUUID())
        assertEquals(2_000L, second.profitMinor)
        assertEquals(70_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
    }

    @Test fun shamCashUsesIndependentFloatAndFivePercentProfit() {
        val drawer = accounts.create("درج شام " + UUID.randomUUID(), "CASH_BOX", "SYP", 0L)
        accounts.setCurrentDayPrimaryDrawer("ADMIN", actor, drawer.id)
        repo.setOpeningBalance(actor, ServiceTransferRepository.SYRIATEL_SHARED, 50_000L)
        repo.setOpeningBalance(actor, ServiceTransferRepository.SHAM_CASH, 30_000L)
        val result = repo.executeTransfer(actor, ServiceTransferRepository.SHAM_CASH_TRANSFER,
            10_000L, 0L, drawer.id, "svc-sham-" + UUID.randomUUID())
        assertEquals(500L, result.profitMinor)
        assertEquals(10_500L, result.customerChargeMinor)
        assertEquals(50_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
        assertEquals(20_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SHAM_CASH }.balanceMinor)
    }

    @Test fun electronicReceiptIncreasesCollectionWalletNotServiceFloat() {
        val collection = accounts.create("محفظة قبض خدمة " + UUID.randomUUID(), "WALLET", "SYP", 0L)
        repo.setOpeningBalance(actor, ServiceTransferRepository.SYRIATEL_SHARED, 40_000L)
        val result = repo.executeTransfer(actor, ServiceTransferRepository.SYRIATEL_BALANCE,
            5_000L, 0L, collection.id, "svc-electronic-" + UUID.randomUUID())
        assertEquals(6_000L, accounts.balance(collection.id))
        assertEquals(35_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
        assertEquals("ELECTRONIC", repo.listTransfers().first().receiptChannel)
        assertEquals(6_000L, result.customerChargeMinor)
    }

    @Test fun rechargeDeductsFromElectronicProceedsAndAppliesTwoPercentOnlyToSyriatel() {
        val source = accounts.create("محفظة تمويل " + UUID.randomUUID(), "WALLET", "SYP", 100_000L)
        val syriatelFee = repo.recharge(actor, ServiceTransferRepository.SYRIATEL_SHARED, source.id,
            50_000L, "recharge-syr-" + UUID.randomUUID())
        assertEquals(1_000L, syriatelFee)
        assertEquals(49_000L, accounts.balance(source.id))
        assertEquals(50_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
        val shamFee = repo.recharge(actor, ServiceTransferRepository.SHAM_CASH, source.id,
            20_000L, "recharge-sham-" + UUID.randomUUID())
        assertEquals(0L, shamFee)
        assertEquals(29_000L, accounts.balance(source.id))
        assertEquals(20_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SHAM_CASH }.balanceMinor)
    }

    @Test fun transferIsIdempotentAndInsufficientFloatDoesNotCreateReceipt() {
        val drawer = accounts.create("درج تكرار " + UUID.randomUUID(), "CASH_BOX", "SYP", 0L)
        accounts.setCurrentDayPrimaryDrawer("ADMIN", actor, drawer.id)
        repo.setOpeningBalance(actor, ServiceTransferRepository.SYRIATEL_SHARED, 10_000L)
        val op = "svc-idempotent-" + UUID.randomUUID()
        val first = repo.executeTransfer(actor, ServiceTransferRepository.SYRIATEL_BALANCE, 2_000L, 0L, drawer.id, op)
        val replay = repo.executeTransfer(actor, ServiceTransferRepository.SYRIATEL_BALANCE, 2_000L, 0L, drawer.id, op)
        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(2_400L, accounts.balance(drawer.id))
        assertEquals(8_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
        try {
            repo.executeTransfer(actor, ServiceTransferRepository.SYRIATEL_BALANCE, 9_000L, 0L, drawer.id, "svc-too-large-" + UUID.randomUUID())
            throw AssertionError("Expected insufficient service float")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("غير كافٍ"))
        }
        assertEquals(2_400L, accounts.balance(drawer.id))
    }
}
