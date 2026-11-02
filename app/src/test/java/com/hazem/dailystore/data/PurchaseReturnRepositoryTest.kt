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
class PurchaseReturnRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var adminId: String
    private lateinit var supplierId: String
    private lateinit var productId: String
    private lateinit var invoiceId: String
    private lateinit var purchaseItemId: String
    private lateinit var invoiceRepo: InvoiceRepository
    private lateinit var returns: PurchaseReturnRepository
    private lateinit var payments: PurchasePaymentRepository
    private lateinit var accounts: CashAccountRepository

    @Before fun setUp() {
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
        supplierId=SupplierRepository(context).create(adminId,"مورد مرتجعات "+UUID.randomUUID()).id
        val unit=store.getUnits().first(); val category=store.getCategories().first()
        productId=store.saveProduct(null,"صنف مرتجعات "+UUID.randomUUID(),null,null,category.id,unit.id,
            1000L,2000L,true,0.0,adminId)
        invoiceRepo=InvoiceRepository(context); returns=PurchaseReturnRepository(context)
        payments=PurchasePaymentRepository(context); accounts=CashAccountRepository(context)
        val draft=invoiceRepo.savePurchaseDraft(adminId,"purchase-return-draft-"+UUID.randomUUID(),
            null,supplierId,"2026-11-02",0L,null,
            listOf(InvoiceLineInput(productId,BigDecimal("10"),1000L)))
        invoiceId=draft.invoiceId
        assertTrue(invoiceRepo.postPurchase(adminId,invoiceId,"purchase-return-post-"+UUID.randomUUID()))
        purchaseItemId=db.readableDatabase.rawQuery(
            "SELECT id FROM purchase_items WHERE purchase_invoice_id=?",arrayOf(invoiceId)
        ).use { it.moveToFirst(); it.getString(0) }
    }

    @After fun tearDown() { db.close() }

    private fun stock(): Double = db.readableDatabase.rawQuery(
        "SELECT quantity FROM inventory_balances WHERE product_id=?",
        arrayOf(productId)
    ).use { if(it.moveToFirst()) it.getDouble(0) else 0.0 }

    private fun item(quantity: String) = listOf(
        PurchaseReturnItemInput(purchaseItemId,BigDecimal(quantity))
    )

    @Test fun supplierStatementReconcilesPurchaseAndReturnCredit() {
        returns.postPurchaseReturn(adminId,invoiceId,"supplier-statement-return-"+UUID.randomUUID(),
            "2026-11-02","مرتجع لاختبار كشف الحساب",item("3"),emptyList())
        val statement=PartyStatementRepository(RuntimeEnvironment.getApplication()).supplier(supplierId)
        assertEquals(2,statement.entries.size)
        assertEquals(10000L,statement.entries.first { it.type=="PURCHASE_INVOICE" }.debitUsdCents)
        assertEquals(300000L,statement.entries.first { it.type=="PURCHASE_RETURN" }.creditSypMinor)
        assertEquals(700000L,statement.closingBalanceSypMinor)
        assertEquals(7000L,statement.closingBalanceUsdCents)
        assertEquals(0,statement.unpricedEntryCount)
    }

    @Test fun unpaidReturnReducesSupplierPayableAndRestoresSourceLayerOnce() {
        val op="purchase-return-idempotent-"+UUID.randomUUID()
        val first=returns.postPurchaseReturn(adminId,invoiceId,op,"2026-11-02","إرجاع جزء من البضاعة",
            item("3"),emptyList())
        assertFalse(first.replayed)
        assertEquals(3000L,first.totalUsdCents)
        assertEquals(300000L,first.supplierCreditReductionSypMinor)
        assertEquals(0L,first.supplierCashRefundSypMinor)
        assertEquals(7.0,stock(),0.000001)
        val invoice=payments.listPurchaseInvoices().first { it.id==invoiceId }
        assertEquals(700000L,invoice.outstandingSypMinor)
        val salesProfit=SalesProfitRepository(RuntimeEnvironment.getApplication()).allTime()
        assertEquals(0L,salesProfit.returnUsdCents)
        assertEquals(0,salesProfit.returnCount)
        val layerRemaining=db.readableDatabase.rawQuery(
            "SELECT remaining_quantity FROM inventory_cost_layers WHERE source_item_id=?",arrayOf(purchaseItemId)
        ).use { it.moveToFirst();it.getDouble(0) }
        assertEquals(7.0,layerRemaining,0.000001)

        val retry=returns.postPurchaseReturn(adminId,invoiceId,op,"2026-11-02","إرجاع جزء من البضاعة",
            item("3"),emptyList())
        assertTrue(retry.replayed)
        assertEquals(first.returnId,retry.returnId)
        assertEquals(7.0,stock(),0.000001)
        assertEquals(1,db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM returns WHERE original_invoice_type='PURCHASE' AND original_invoice_id=?",
            arrayOf(invoiceId)).use { it.moveToFirst();it.getInt(0) })
    }

    @Test fun paidInvoiceReturnReceivesSupplierCashRefundIntoSelectedAccount() {
        val usd=accounts.create(adminId,"صندوق دفع المورد "+UUID.randomUUID(),"CASH_BOX","USD",20000L)
        payments.addPayment(adminId,invoiceId,"pay-purchase-return-"+UUID.randomUUID(),usd.id,10000L)
        val syp=accounts.create(adminId,"محفظة استرداد "+UUID.randomUUID(),"WALLET","SYP",0L)
        val result=returns.postPurchaseReturn(adminId,invoiceId,"paid-return-"+UUID.randomUUID(),
            "2026-11-02","إرجاع بعد تسديد الفاتورة",item("2"),
            listOf(SupplierRefundAllocationInput(syp.id,200000L)))
        assertEquals(2000L,result.totalUsdCents)
        assertEquals(0L,result.supplierCreditReductionSypMinor)
        assertEquals(200000L,result.supplierCashRefundSypMinor)
        assertEquals(200000L,accounts.balance(syp.id))
        val daily=DailyBalanceRepository(RuntimeEnvironment.getApplication()).dailyReport("2026-11-02")
        val dailyRefund=daily.accounts.first { it.accountId==syp.id }
        assertEquals(200000L,dailyRefund.supplierReturnRefundsMinor)
        assertEquals(200000L,dailyRefund.closingMinor)
        assertEquals(8.0,stock(),0.000001)
        db.readableDatabase.rawQuery(
            "SELECT direction,transaction_type FROM cash_transactions WHERE source_id=?",
            arrayOf(result.returnId)
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("IN",c.getString(0))
            assertEquals("PURCHASE_RETURN_REFUND",c.getString(1))
        }
    }

    @Test fun cannotReturnQuantityAlreadyConsumedFromOriginalPurchaseFifoLayer() {
        val saleCash=accounts.create(adminId,"صندوق بيع "+UUID.randomUUID(),"CASH_BOX","USD",50000L)
        val sale=invoiceRepo.saveSalesDraft(adminId,"sale-before-purchase-return-"+UUID.randomUUID(),
            null,null,"2026-11-02",0L,null,
            listOf(InvoiceLineInput(productId,BigDecimal("8"),2000L)))
        assertTrue(invoiceRepo.postCashSale(adminId,sale.invoiceId,"sale-post-before-return-"+UUID.randomUUID(),saleCash.id))
        assertEquals(2.0,stock(),0.000001)
        try {
            returns.postPurchaseReturn(adminId,invoiceId,"over-source-return-"+UUID.randomUUID(),
                "2026-11-02","محاولة إرجاع كمية مباعة",item("3"),emptyList())
            throw AssertionError("Return must not consume unrelated stock or already-consumed source layer quantity")
        } catch (_: IllegalArgumentException) { }
        assertEquals(2.0,stock(),0.000001)
        assertTrue(db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM returns WHERE original_invoice_type='PURCHASE'",null
        ).use { it.moveToFirst();it.getInt(0)==0 })
    }

    @Test fun fullAndPartialReturnsRespectInvoiceDiscountAndCumulativeRounding() {
        // Use a second invoice so the fixture's original purchase history remains untouched.
        val draft=invoiceRepo.savePurchaseDraft(adminId,"discounted-return-draft-"+UUID.randomUUID(),
            null,supplierId,"2026-11-02",333L,null,
            listOf(InvoiceLineInput(productId,BigDecimal("10"),1000L)))
        invoiceId=draft.invoiceId
        assertTrue(invoiceRepo.postPurchase(adminId,invoiceId,"discounted-return-post-"+UUID.randomUUID()))
        purchaseItemId=db.readableDatabase.rawQuery(
            "SELECT id FROM purchase_items WHERE purchase_invoice_id=?",arrayOf(invoiceId)
        ).use { it.moveToFirst();it.getString(0) }
        val first=returns.postPurchaseReturn(adminId,invoiceId,"discounted-return-part1-"+UUID.randomUUID(),
            "2026-11-02","مرتجع جزئي",item("4"),emptyList())
        val second=returns.postPurchaseReturn(adminId,invoiceId,"discounted-return-part2-"+UUID.randomUUID(),
            "2026-11-02","استكمال المرتجع",item("6"),emptyList())
        assertEquals(3867L,first.totalUsdCents)
        assertEquals(5800L,second.totalUsdCents)
        assertEquals(9667L,first.totalUsdCents+second.totalUsdCents)
        assertEquals(10.0,stock(),0.000001)
    }

    @Test fun fullUnpaidReturnIsShownAsSettledBySupplierCreditNotCashPayment() {
        val result=returns.postPurchaseReturn(adminId,invoiceId,"full-credit-return-"+UUID.randomUUID(),
            "2026-11-02","إرجاع كامل الفاتورة",item("10"),emptyList())
        assertEquals(10000L,result.totalUsdCents)
        val summary=payments.listPurchaseInvoices().first { it.id==invoiceId }
        assertEquals("SETTLED_BY_RETURN",summary.paymentStatus)
        assertEquals(1000000L,summary.supplierReturnCreditSypMinor)
        assertEquals(0L,summary.paidSypMinor)
        assertEquals(0L,summary.outstandingSypMinor)
        val supplier=payments.listSupplierSummaries().first { it.id==supplierId }
        assertEquals(1000000L,supplier.supplierReturnCreditsSypMinor)
    }

    @Test fun supplierReturnCreditPreventsOverpaymentButAllowsExactRemainingPayment() {
        returns.postPurchaseReturn(adminId,invoiceId,"partial-credit-before-payment-"+UUID.randomUUID(),
            "2026-11-02","مرتجع جزئي",item("3"),emptyList())
        val account=accounts.create(adminId,"صندوق تسديد المتبقي "+UUID.randomUUID(),"CASH_BOX","USD",10000L)
        try {
            payments.addPayment(adminId,invoiceId,"overpay-after-return-"+UUID.randomUUID(),account.id,8000L)
            throw AssertionError("Payment above the reduced payable must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(10000L,accounts.balance(account.id))
        val payment=payments.addPayment(adminId,invoiceId,"exact-pay-after-return-"+UUID.randomUUID(),account.id,7000L)
        assertEquals(700000L,payment.paidSypMinor)
        assertEquals(0L,payment.outstandingSypMinor)
        assertEquals("SETTLED_BY_RETURN",payments.listPurchaseInvoices().first { it.id==invoiceId }.paymentStatus)
        assertEquals(300000L,payments.listPurchaseInvoices().first { it.id==invoiceId }.supplierReturnCreditSypMinor)
    }

    @Test fun inventoryValuationRemovesTheReturnedPurchaseLayerNotAnOlderUnrelatedLayer() {
        val secondDraft=invoiceRepo.savePurchaseDraft(adminId,"second-fifo-purchase-"+UUID.randomUUID(),
            null,supplierId,"2026-11-02",0L,null,
            listOf(InvoiceLineInput(productId,BigDecimal("10"),2000L)))
        assertTrue(invoiceRepo.postPurchase(adminId,secondDraft.invoiceId,"second-fifo-post-"+UUID.randomUUID()))
        val secondItemId=db.readableDatabase.rawQuery(
            "SELECT id FROM purchase_items WHERE purchase_invoice_id=?",arrayOf(secondDraft.invoiceId)
        ).use { it.moveToFirst();it.getString(0) }
        val saleAccount=accounts.create(adminId,"صندوق اختبار FIFO "+UUID.randomUUID(),"CASH_BOX","USD",50000L)
        val sale=invoiceRepo.saveSalesDraft(adminId,"fifo-audit-sale-"+UUID.randomUUID(),
            null,null,"2026-11-02",0L,null,
            listOf(InvoiceLineInput(productId,BigDecimal("5"),2000L)))
        assertTrue(invoiceRepo.postCashSale(adminId,sale.invoiceId,"fifo-audit-sale-post-"+UUID.randomUUID(),saleAccount.id))
        assertEquals(15.0,stock(),0.000001)

        returns.postPurchaseReturn(adminId,secondDraft.invoiceId,"fifo-audit-purchase-return-"+UUID.randomUUID(),
            "2026-11-02","إرجاع من طبقة الشراء الثانية",
            listOf(PurchaseReturnItemInput(secondItemId,BigDecimal("5"))),emptyList())
        assertEquals(10.0,stock(),0.000001)
        val valuation=InventoryValuationRepository(RuntimeEnvironment.getApplication()).dailyCapital().current
        assertTrue("Inventory valuation should remain fully costed",valuation.isComplete)
        assertEquals(10.0,valuation.valuedQuantity,0.000001)
        assertEquals(15000L,valuation.totalCostUsdCents)
    }

    @Test fun cashierCannotPostSupplierReturn() {
        val auth=AuthRepository(RuntimeEnvironment.getApplication())
        val cashier=auth.createUser("ADMIN",adminId,"supplier-return-cashier","Cashier","Cashier12345","CASHIER")
        auth.changePassword(cashier,"Cashier12345","CashierStrong123")
        try {
            returns.postPurchaseReturn(cashier,invoiceId,"supplier-return-denied-"+UUID.randomUUID(),
                "2026-11-02","غير مصرح",item("1"),emptyList())
            throw AssertionError("Cashier must not post supplier returns")
        } catch (_: IllegalArgumentException) { }
        assertEquals(10.0,stock(),0.000001)
    }

    @Test fun primaryPurchaseReturnIsAddedToOutboxAtomically() {
        val context=RuntimeEnvironment.getApplication()
        val sync=SyncProtocolRepository(context)
        sync.configureAsPrimary(adminId,"الهاتف الرئيسي")
        returns.postPurchaseReturn(adminId,invoiceId,"sync-purchase-return-"+UUID.randomUUID(),
            "2026-11-02","مرتجع للمزامنة",item("1"),emptyList())
        assertTrue(sync.pendingOutbox().any { it.entityType=="PURCHASE_RETURN" &&
            it.operationType=="POST_PURCHASE_RETURN" })
    }

}
