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
        AppDatabase.resetForTests(context)
        database = AppDatabase.getInstance(context)
        database.writableDatabase.execSQL("DELETE FROM service_transfers")
        database.writableDatabase.execSQL("DELETE FROM service_wallet_transactions")
        database.writableDatabase.execSQL("DELETE FROM cash_transactions WHERE account_id IN (SELECT account_id FROM service_wallet_account_links)")
        database.writableDatabase.execSQL("DELETE FROM wallet_daily_openings WHERE account_id IN (SELECT account_id FROM service_wallet_account_links)")
        database.writableDatabase.execSQL("UPDATE cash_accounts SET opening_balance_minor=0 WHERE id IN (SELECT account_id FROM service_wallet_account_links)")
        StoreRepository(context).ensureBaseSetup()
        val auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        actor = auth.authenticate("admin", "1234")!!.id
        auth.changePassword(actor, "1234", "TestAdmin123")
        accounts = CashAccountRepository(context)
        repo = ServiceTransferRepository(context)
    }

    @After fun tearDown() { database.close() }

    @Test fun builtInServiceWalletsAreRealCashAccountsAndCanBeUsedForElectronicReceipts() {
        val wallets = repo.listWallets()
        assertEquals(2, wallets.size)
        wallets.forEach { wallet ->
            val account = accounts.listAll().single { it.id == wallet.accountId }
            assertEquals("WALLET", account.accountType)
            assertEquals("SYP", account.currencyCode)
        }
        assertTrue(wallets.any { it.name.contains("سيرياتيل") })
        assertTrue(wallets.any { it.name.contains("شام كاش") })
    }

    @Test fun syriatelBalanceAndCashShareFloatAndReceiptIsCreditedInFull() {
        val drawer = accounts.create(actor, "درج خدمة " + UUID.randomUUID(), "CASH_BOX", "SYP", 0L)
        accounts.setCurrentDayPrimaryDrawer("ADMIN", actor, drawer.id)
        repo.setOpeningBalance(actor, ServiceTransferRepository.SYRIATEL_SHARED, 100_000L)
        val first = repo.executeTransfer(actor, ServiceTransferRepository.SYRIATEL_BALANCE,
            10_000L, 500L, drawer.id, "svc-normal-" + UUID.randomUUID(),legacyBalanceDelivered=true)
        assertFalse(first.replayed)
        assertEquals(2_000L, first.profitMinor)
        assertEquals(12_500L, first.customerChargeMinor)
        assertEquals(12_500L, accounts.balance(drawer.id))
        assertEquals(90_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)

        val second = repo.executeTransfer(actor, ServiceTransferRepository.SYRIATEL_CASH,
            20_000L, 2L, drawer.id, "svc-cash-" + UUID.randomUUID())
        assertEquals(2_000L, second.profitMinor)
        assertEquals(69_998L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
        val daily = repo.dailySummary()
        val normal = daily.single { it.serviceType == ServiceTransferRepository.SYRIATEL_BALANCE }
        val cash = daily.single { it.serviceType == ServiceTransferRepository.SYRIATEL_CASH }
        assertEquals(2_000L, normal.profitMinor)
        assertEquals(2_000L, cash.profitMinor)
        assertEquals(12_500L, normal.customerReceiptsMinor)
        assertEquals(22_000L, cash.customerReceiptsMinor)
        assertEquals(2L, cash.providerFeeMinor)
        assertEquals(10_000L, normal.rechargeReserveMinor)
        assertEquals(20_002L, cash.rechargeReserveMinor)
    }

    @Test fun exactSyriatelBalanceAmountsAreRoundedOnlyAtCustomerCharge() {
        val drawer=accounts.create(actor,"دقة سيريتل "+UUID.randomUUID(),"CASH_BOX","SYP",0L)
        accounts.setCurrentDayPrimaryDrawer("ADMIN",actor,drawer.id)
        repo.setOpeningBalance(actor,ServiceTransferRepository.SYRIATEL_SHARED,100L)
        val result=repo.executeTransfer(actor,ServiceTransferRepository.SYRIATEL_BALANCE,43L,0L,drawer.id,"precision-"+UUID.randomUUID())
        assertEquals(43L,result.amountDebitedMinor)
        assertEquals(41340L,result.deliveredMilliSyp)
        assertEquals(8268L,result.profitMilliSyp)
        assertEquals(50L,result.customerChargeMinor)
        assertEquals(57L,repo.listWallets().single{it.serviceKey==ServiceTransferRepository.SYRIATEL_SHARED}.balanceMinor)
        val saved=repo.listTransfers().first()
        assertEquals(43L,saved.amountDebitedMinor)
        assertEquals(41340L,saved.deliveredMilliSyp)
        assertEquals(8268L,saved.profitMilliSyp)
    }

    @Test fun syriatelCashUsesRoundedAutomaticCompanyFeeAndTenPercentOfDeliveredOnly() {
        val drawer=accounts.create(actor,"درج سيريتل كاش "+UUID.randomUUID(),"CASH_BOX","SYP",0L)
        accounts.setCurrentDayPrimaryDrawer("ADMIN",actor,drawer.id)
        repo.setOpeningBalance(actor,ServiceTransferRepository.SYRIATEL_SHARED,10_000L)

        try {
            repo.executeTransfer(actor,ServiceTransferRepository.SYRIATEL_CASH,100L,1L,drawer.id,
                "svc-cash-wrong-fee-"+UUID.randomUUID())
            throw AssertionError("Expected manual provider fee to be rejected")
        } catch(expected:IllegalArgumentException) {
            assertTrue(expected.message!!.contains("تلقائياً"))
        }

        val belowThreshold=repo.executeTransfer(actor,ServiceTransferRepository.SYRIATEL_CASH,100L,2L,drawer.id,
            "svc-cash-below-"+UUID.randomUUID())
        assertEquals(10L,belowThreshold.profitMinor)
        assertEquals(2L,belowThreshold.providerFeeMinor)
        assertEquals(110L,belowThreshold.customerChargeMinor)

        val atThreshold=repo.executeTransfer(actor,ServiceTransferRepository.SYRIATEL_CASH,200L,2L,drawer.id,
            "svc-cash-at-"+UUID.randomUUID())
        assertEquals(20L,atThreshold.profitMinor)
        assertEquals(2L,atThreshold.providerFeeMinor)
        assertEquals(220L,atThreshold.customerChargeMinor)
        assertEquals(330L,accounts.balance(drawer.id))
        assertEquals(9_696L,repo.listWallets().single{it.serviceKey==ServiceTransferRepository.SYRIATEL_SHARED}.balanceMinor)
    }

    @Test fun shamCashUsesIndependentFloatAndFivePercentProfit() {
        val drawer = accounts.create(actor, "درج شام " + UUID.randomUUID(), "CASH_BOX", "SYP", 0L)
        accounts.setCurrentDayPrimaryDrawer("ADMIN", actor, drawer.id)
        repo.setOpeningBalance(actor, ServiceTransferRepository.SYRIATEL_SHARED, 50_000L)
        repo.setOpeningBalance(actor, ServiceTransferRepository.SHAM_CASH, 30_000L)
        val result = repo.executeTransfer(actor, ServiceTransferRepository.SHAM_CASH_TRANSFER,
            10_000L, 0L, drawer.id, "svc-sham-" + UUID.randomUUID())
        assertEquals(500L, result.profitMinor)
        assertEquals(10_500L, result.customerChargeMinor)
        assertEquals(50_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
        assertEquals(20_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SHAM_CASH }.balanceMinor)
        val rechargeRows = repo.dailySummary()
        assertEquals(0L, rechargeRows.single { it.serviceType == ServiceTransferRepository.SHAM_CASH_TRANSFER }.rechargeFeePaidMinor)
    }

    @Test fun electronicServiceReceiptIsAddedToTheSameOperationalWallet() {
        val collection = repo.serviceAccountId(ServiceTransferRepository.SYRIATEL_SHARED)
        repo.setOpeningBalance(actor, ServiceTransferRepository.SYRIATEL_SHARED, 40_000L)
        val result = repo.executeTransfer(actor, ServiceTransferRepository.SYRIATEL_BALANCE,
            5_000L, 0L, collection, "svc-electronic-" + UUID.randomUUID(),legacyBalanceDelivered=true)
        assertEquals(41_000L, accounts.balance(collection))
        assertEquals(41_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
        assertEquals("ELECTRONIC", repo.listTransfers().first().receiptChannel)
        assertEquals(6_000L, result.customerChargeMinor)
        val daily = DailyBalanceRepository(RuntimeEnvironment.getApplication()).dailyReport()
        val row = daily.accounts.single { it.accountId == collection }
        assertEquals(6_000L, row.serviceTransferReceiptsMinor)
        assertEquals(6_000L, row.electronicReceiptsMinor)
        assertEquals(6_000L, row.liquidatableElectronicReceiptsMinor)
        assertEquals(0L, row.salesReceiptsMinor)
        assertEquals(41_000L, row.closingMinor)
    }

    @Test fun rechargeDeductsFromDedicatedCashBoxAndAppliesTwoPercentOnlyOnExternalRecharge() {
        val source = accounts.create(actor, "صندوق شراء رصيد " + UUID.randomUUID(), "CASH_BOX", "SYP", 100_000L)
        repo.setRechargeAccount(actor, source.id)
        val syriatelOperation = "recharge-syr-" + UUID.randomUUID()
        val syriatelFee = repo.recharge(actor, ServiceTransferRepository.SYRIATEL_SHARED, source.id,
            50_000L, syriatelOperation)
        assertEquals(1_000L, syriatelFee)
        assertEquals(49_000L, accounts.balance(source.id))
        assertEquals(50_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
        assertEquals(1_000L, repo.recharge(actor, ServiceTransferRepository.SYRIATEL_SHARED, source.id,
            50_000L, syriatelOperation))
        assertEquals(49_000L, accounts.balance(source.id))
        assertEquals(50_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
        try {
            repo.recharge(actor, ServiceTransferRepository.SYRIATEL_SHARED, source.id,
                51_000L, syriatelOperation)
            throw AssertionError("Conflicting service recharge replay must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("مختلفة"))
        }
        val shamFee = repo.recharge(actor, ServiceTransferRepository.SHAM_CASH, source.id,
            20_000L, "recharge-sham-" + UUID.randomUUID())
        assertEquals(400L, shamFee)
        assertEquals(28_600L, accounts.balance(source.id))
        assertEquals(20_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SHAM_CASH }.balanceMinor)
        val daily = repo.dailySummary()
        assertEquals(1_000L, daily.single { it.serviceType == ServiceTransferRepository.SYRIATEL_BALANCE }.rechargeFeePaidMinor)
        assertEquals(400L, daily.single { it.serviceType == ServiceTransferRepository.SHAM_CASH_TRANSFER }.rechargeFeePaidMinor)
    }


    @Test fun electronicServiceReceiptCanBeMovedToCashAndBothBalancesReconcile() {
        val drawer = accounts.create(actor, "درج تصريف خدمة " + UUID.randomUUID(), "CASH_BOX", "SYP", 0L)
        val walletId = repo.serviceAccountId(ServiceTransferRepository.SYRIATEL_SHARED)
        repo.setOpeningBalance(actor, ServiceTransferRepository.SYRIATEL_SHARED, 40_000L)
        repo.executeTransfer(actor, ServiceTransferRepository.SYRIATEL_BALANCE,
            5_000L, 0L, walletId, "svc-wallet-receipt-" + UUID.randomUUID(),legacyBalanceDelivered=true)
        assertEquals(41_000L, accounts.balance(walletId))
        dailyTransferToCash(walletId, drawer.id, 6_000L)
        assertEquals(35_000L, accounts.balance(walletId))
        assertEquals(6_000L, accounts.balance(drawer.id))
        val report = DailyBalanceRepository(RuntimeEnvironment.getApplication()).dailyReport()
        assertEquals(35_000L, report.accounts.single { it.accountId == walletId }.closingMinor)
        assertEquals(6_000L, report.accounts.single { it.accountId == drawer.id }.closingMinor)
    }

    @Test fun deferredTransferCreatesReceivableWithoutImmediateReceiptAndSupportsPartialSettlement() {
        val context=RuntimeEnvironment.getApplication()
        val customerId="service-credit-customer-"+UUID.randomUUID()
        val now="2026-11-06T10:00:00.000Z"
        database.writableDatabase.execSQL(
            "INSERT INTO customers(id,name,is_active,created_at,updated_at) VALUES(?,?,1,?,?)",
            arrayOf(customerId,"زبون تحويل آجل",now,now)
        )
        val drawer=accounts.create(actor,"درج تحصيل دين "+UUID.randomUUID(),"CASH_BOX","SYP",0L)
        accounts.setCurrentDayPrimaryDrawer("ADMIN",actor,drawer.id)
        val wallet=repo.serviceAccountId(ServiceTransferRepository.SYRIATEL_SHARED)
        repo.setOpeningBalance(actor,ServiceTransferRepository.SYRIATEL_SHARED,10_000L)
        val operationId="svc-credit-"+UUID.randomUUID()
        val transfer=repo.executeTransfer(actor,ServiceTransferRepository.SYRIATEL_BALANCE,1_000L,0L,wallet,operationId,
            paymentMode="CREDIT",customerId=customerId,legacyBalanceDelivered=true)
        assertEquals(1_000L,transfer.deliveredMinor)
        assertEquals(1_200L,transfer.customerChargeMinor)
        assertEquals(0L,accounts.balance(drawer.id))
        val immediateReceipts=database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_transactions WHERE transaction_type='SERVICE_TRANSFER_RECEIPT' AND source_id=?",
            arrayOf(transfer.transferId)
        ).use{it.moveToFirst();it.getLong(0)}
        assertEquals(0L,immediateReceipts)
        assertEquals(9_000L,repo.listWallets().single{it.serviceKey==ServiceTransferRepository.SYRIATEL_SHARED}.balanceMinor)
        assertEquals("لا يُعترف بربح الذمة قبل التحصيل",0L,
            repo.dailySummary().single{it.serviceType==ServiceTransferRepository.SYRIATEL_BALANCE}.profitMinor)
        val receivables=ServiceTransferReceivableRepository(context)
        val replayedTransfer=repo.executeTransfer(actor,ServiceTransferRepository.SYRIATEL_BALANCE,1_000L,0L,wallet,operationId,
            paymentMode="CREDIT",customerId=customerId,legacyBalanceDelivered=true)
        assertTrue(replayedTransfer.replayed)
        assertEquals(9_000L,repo.listWallets().single{it.serviceKey==ServiceTransferRepository.SYRIATEL_SHARED}.balanceMinor)
        val debt=receivables.listOpenReceivables(customerId).single()
        assertEquals(1_200L,debt.outstandingMinor)
        val paymentOperation="svc-debt-pay-"+UUID.randomUUID()
        val first=receivables.collect(actor,transfer.transferId,paymentOperation,wallet,500L,"دفعة أولى عبر محفظة الرصيد")
        assertEquals(500L,first.paidMinor)
        assertEquals(700L,first.outstandingMinor)
        assertEquals(0L,accounts.balance(drawer.id))
        assertEquals("تحصيل الدين في محفظة الخدمة يزيدها بالمبلغ المقبوض فقط",9_500L,
            repo.listWallets().single{it.serviceKey==ServiceTransferRepository.SYRIATEL_SHARED}.balanceMinor)
        val partialProfit=repo.dailySummary().single{it.serviceType==ServiceTransferRepository.SYRIATEL_BALANCE}
        assertEquals("يُحتسب ربح الجزء المحصل فقط",83L,partialProfit.profitMinor)
        assertEquals(83L,repo.listTransfers().first().profitMinor)
        assertEquals(83_333L,repo.listTransfers().first().profitMilliSyp)
        assertEquals("لا يُخصم أصل التحويل مجدداً عند التحصيل؛ يُضاف المقبوض فقط",9_500L,
            repo.listWallets().single{it.serviceKey==ServiceTransferRepository.SYRIATEL_SHARED}.balanceMinor)
        val partialStatement=PartyStatementRepository(context).customer(customerId)
        assertEquals(700L,partialStatement.closingBalanceSypMinor)
        assertEquals(1_200L,partialStatement.entries.single{it.type=="SERVICE_TRANSFER_CREDIT"}.debitSypMinor)
        assertEquals(500L,partialStatement.entries.single{it.type=="SERVICE_TRANSFER_COLLECTION"}.creditSypMinor)
        val replay=receivables.collect(actor,transfer.transferId,paymentOperation,wallet,500L,"دفعة أولى عبر محفظة الرصيد")
        assertTrue(replay.replayed)
        assertEquals(0L,accounts.balance(drawer.id))
        assertEquals(9_500L,repo.listWallets().single{it.serviceKey==ServiceTransferRepository.SYRIATEL_SHARED}.balanceMinor)
        try {
            receivables.collect(actor,transfer.transferId,"svc-overpay-"+UUID.randomUUID(),drawer.id,701L)
            throw AssertionError("Over-collection must be rejected")
        } catch(expected:IllegalArgumentException) {
            assertTrue(expected.message!!.contains("يتجاوز"))
        }
        val finalOperation="svc-debt-final-"+UUID.randomUUID()
        val final=receivables.collect(actor,transfer.transferId,finalOperation,drawer.id,700L,"دفعة أخيرة")
        assertEquals("PAID",final.status)
        assertEquals(0L,final.outstandingMinor)
        val finalReplay=receivables.collect(actor,transfer.transferId,finalOperation,drawer.id,700L,"دفعة أخيرة")
        assertTrue(finalReplay.replayed)
        assertEquals(700L,accounts.balance(drawer.id))
        assertEquals(9_500L,repo.listWallets().single{it.serviceKey==ServiceTransferRepository.SYRIATEL_SHARED}.balanceMinor)
        val finalProfit=repo.dailySummary().single{it.serviceType==ServiceTransferRepository.SYRIATEL_BALANCE}
        assertEquals("يكتمل الربح عند تحصيل كامل الذمة",200L,finalProfit.profitMinor)
        assertEquals(200L,repo.listTransfers().first().profitMinor)
        assertEquals(200_000L,repo.listTransfers().first().profitMilliSyp)
        assertEquals(9_500L,repo.listWallets().single{it.serviceKey==ServiceTransferRepository.SYRIATEL_SHARED}.balanceMinor)
        assertEquals(0L,PartyStatementRepository(context).customer(customerId).closingBalanceSypMinor)
        assertTrue(receivables.listOpenReceivables(customerId).isEmpty())
        val summary=repo.dailySummary().single{it.serviceType==ServiceTransferRepository.SYRIATEL_BALANCE}
        assertEquals(0L,summary.customerReceiptsMinor)
        assertEquals(1_200L,summary.creditChargesMinor)
    }

    private fun dailyTransferToCash(walletId:String,drawerId:String,amount:Long) {
        DailyBalanceRepository(RuntimeEnvironment.getApplication()).transferWalletToDrawer(
            actor,walletId,drawerId,amount,"service-wallet-cash-" + UUID.randomUUID(),"تصريف قبض خدمة","تحويل داخلي"
        )
    }

    @Test fun configuringRechargeAccountOnSecondaryDoesNotRepairOrCreateFinancialAccounts() {
        val source = accounts.create(actor, "صندوق تعبئة ثانوي " + UUID.randomUUID(), "CASH_BOX", "SYP", 0L)
        val db = database.writableDatabase
        db.delete("daily_cash_sessions", null, null)
        db.delete("system_cash_account_roles", null, null)
        db.execSQL(
            "INSERT OR REPLACE INTO sync_protocol_state(state_key,state_value,updated_at) VALUES('local_device_role','SECONDARY','2026-11-04T00:00:00.000Z')"
        )
        try {
            repo.setRechargeAccount(actor, source.id)
            throw AssertionError("Secondary devices must not configure service recharge accounts")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("الجهاز الأساسي"))
        }
        val sessions = db.rawQuery("SELECT COUNT(*) FROM daily_cash_sessions", null).use { it.moveToFirst(); it.getInt(0) }
        val roles = db.rawQuery("SELECT COUNT(*) FROM system_cash_account_roles", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, sessions)
        assertEquals(0, roles)
    }

    @Test fun managerCanReverseTransferWithAuditedOppositeMovements() {
        val drawer = accounts.create(actor, "درج عكس " + UUID.randomUUID(), "CASH_BOX", "SYP", 0L)
        accounts.setCurrentDayPrimaryDrawer("ADMIN", actor, drawer.id)
        repo.setOpeningBalance(actor, ServiceTransferRepository.SYRIATEL_SHARED, 5_000L)
        val transfer = repo.executeTransfer(actor, ServiceTransferRepository.SYRIATEL_BALANCE,
            1_000L, 0L, drawer.id, "svc-reverse-" + UUID.randomUUID(),legacyBalanceDelivered=true)
        assertEquals(1_200L, accounts.balance(drawer.id))
        val reversalOperation = "svc-reverse-action-" + UUID.randomUUID()
        repo.reverseTransfer(actor, transfer.transferId, reversalOperation, "إدخال خاطئ")
        assertEquals(0L, accounts.balance(drawer.id))
        assertEquals(5_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
        assertEquals("REVERSED", repo.listTransfers().first().status)

        // A retry after a lost acknowledgement must be a no-op, not a second ledger posting.
        repo.reverseTransfer(actor, transfer.transferId, reversalOperation, "إدخال خاطئ")
        assertEquals(0L, accounts.balance(drawer.id))
        assertEquals(5_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
        try {
            repo.reverseTransfer(actor, transfer.transferId, reversalOperation, "سبب مختلف")
            throw AssertionError("Expected conflicting reversal replay to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("مختلفة"))
        }
    }

    @Test fun transferIsIdempotentAndInsufficientFloatDoesNotCreateReceipt() {
        val drawer = accounts.create(actor, "درج تكرار " + UUID.randomUUID(), "CASH_BOX", "SYP", 0L)
        accounts.setCurrentDayPrimaryDrawer("ADMIN", actor, drawer.id)
        repo.setOpeningBalance(actor, ServiceTransferRepository.SYRIATEL_SHARED, 10_000L)
        val op = "svc-idempotent-" + UUID.randomUUID()
        val first = repo.executeTransfer(actor, ServiceTransferRepository.SYRIATEL_BALANCE, 2_000L, 0L, drawer.id, op,legacyBalanceDelivered=true)
        val legacyHash=java.security.MessageDigest.getInstance("SHA-256")
            .digest("${ServiceTransferRepository.SYRIATEL_BALANCE}|2000|0|${drawer.id}|null|null".toByteArray(Charsets.UTF_8))
            .joinToString(""){"%02x".format(it)}
        database.writableDatabase.execSQL("UPDATE service_transfers SET payload_hash=? WHERE operation_id=?",arrayOf(legacyHash,op))
        val replay = repo.executeTransfer(actor, ServiceTransferRepository.SYRIATEL_BALANCE, 2_000L, 0L, drawer.id, op,legacyBalanceDelivered=true)
        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertEquals(2_400L, accounts.balance(drawer.id))
        assertEquals(8_000L, repo.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
        try {
            repo.executeTransfer(actor, ServiceTransferRepository.SYRIATEL_BALANCE, 9_000L, 0L, drawer.id, "svc-too-large-" + UUID.randomUUID(),legacyBalanceDelivered=true)
            throw AssertionError("Expected insufficient service float")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("غير كافٍ"))
        }
        assertEquals(2_400L, accounts.balance(drawer.id))
    }
    @Test fun serviceTransferAndWalletMovementPersistStoreOperatingDate() {
        StoreTime.setZoneId(database.writableDatabase, "Asia/Damascus", "2026-11-03T00:00:00.000Z")
        val drawer = accounts.create(actor, "درج تاريخ الخدمة " + UUID.randomUUID(), "CASH_BOX", "SYP", 0L)
        accounts.setCurrentDayPrimaryDrawer("ADMIN", actor, drawer.id)
        repo.setOpeningBalance(actor, ServiceTransferRepository.SYRIATEL_SHARED, 5_000L)
        val operationId = "svc-store-date-" + UUID.randomUUID()
        val result = repo.executeTransfer(
            actor, ServiceTransferRepository.SYRIATEL_BALANCE, 1_000L, 0L,
            drawer.id, operationId,legacyBalanceDelivered=true
        )
        val expectedDate = StoreTime.today(database.readableDatabase)
        val transferDate = database.readableDatabase.rawQuery(
            "SELECT operating_date FROM service_transfers WHERE id=?",
            arrayOf(result.transferId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
        val walletDate = database.readableDatabase.rawQuery(
            "SELECT operating_date FROM service_wallet_transactions WHERE operation_id=?",
            arrayOf(operationId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
        assertEquals(expectedDate, transferDate)
        assertEquals(expectedDate, walletDate)
        assertEquals(1, repo.dailySummary(expectedDate).single {
            it.serviceType == ServiceTransferRepository.SYRIATEL_BALANCE
        }.operationCount)
    }

}
