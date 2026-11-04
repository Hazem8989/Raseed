package com.hazem.dailystore.data

import android.content.Context
import java.math.BigDecimal
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
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
class SyncDomainApplierTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var auth: AuthRepository
    private lateinit var protocol: SyncProtocolRepository
    private lateinit var vault: SyncCredentialVault
    private lateinit var adminId: String
    private lateinit var productId: String
    private lateinit var secondaryId: String
    private lateinit var secondarySecret: ByteArray

    @Before fun setUp() {
        SyncDeviceTestKeyProvider.install()
        context = RuntimeEnvironment.getApplication()
        AppDatabase.resetForTests(context)
        database = AppDatabase.getInstance(context)
        StoreRepository(context).ensureBaseSetup()
        auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        val admin = auth.authenticate("admin", "1234")!!
        auth.changePassword(admin.id, "1234", "TestAdmin123")
        adminId = admin.id

        val store = StoreRepository(context)
        val unit = store.getUnits().first()
        val category = store.getCategories().first()
        productId = store.saveProduct(
            null, "منتج مزامنة اختباري", null, null, category.id, unit.id,
            100, 250, true, 10.0, adminId
        )
        database.writableDatabase.execSQL(
            "INSERT INTO exchange_rates(id,currency_code,rate_syp_per_usd_milli,effective_at,created_at) VALUES(?,?,?,?,?)",
            arrayOf(UUID.randomUUID().toString(), "USD", 10_000_000L,
                "2026-11-03T10:00:00.000Z", "2026-11-03T10:00:00.000Z")
        )

        vault = SyncCredentialVault(context) {
            javax.crypto.spec.SecretKeySpec(ByteArray(32) { 7 }, "AES")
        }
        protocol = SyncProtocolRepository(context, vault)
        protocol.configureAsPrimary(adminId, "الجهاز الأساسي")
        val invitation = protocol.createPairingInvitation(adminId)
        secondaryId = UUID.randomUUID().toString()
        protocol.enrollSecondaryDevice(adminId, invitation.token, secondaryId, "الجهاز الثانوي")
        protocol.mapSecondaryActor(adminId, secondaryId, "remote-admin", adminId)
        secondarySecret = vault.load(secondaryId)!!
    }

    @After fun tearDown() {
        secondarySecret.fill(0)
        database.close()
    }

    @Test fun secondaryMapsDifferentPrimaryCategoryIdsToItsOwnLocalIdsAndRejectsConflicts() {
        val db = database.writableDatabase
        val primaryId = "category-mapping-primary"
        val now = "2026-11-05T10:00:00.000Z"
        db.execSQL(
            """INSERT INTO sync_devices(device_id,display_name,device_role,status,created_at)
               VALUES(?,?,'PRIMARY','ACTIVE',?)""",
            arrayOf(primaryId, "الجهاز الأساسي للتصنيفات", now)
        )
        db.execSQL(
            "UPDATE sync_protocol_state SET state_value='SECONDARY' WHERE state_key='local_device_role'"
        )
        db.execSQL(
            "UPDATE sync_protocol_state SET state_value=? WHERE state_key='authority_device_id'",
            arrayOf(primaryId)
        )

        // Deliberately make the secondary's SQLite IDs unrelated to the primary IDs.
        db.execSQL("INSERT INTO categories(id,name,created_at) VALUES(501,'تصنيف محلي أقدم',?)", arrayOf(now))
        db.execSQL("INSERT INTO categories(id,name,created_at) VALUES(902,'تصنيف محلي آخر',?)", arrayOf(now))

        fun receiveCategory(operationId: String, sequence: Long, primaryCategoryId: Long, name: String) {
            val payload = JSONObject()
                .put("business_operation", "CREATE_CATEGORY")
                .put("primary_category_id", primaryCategoryId)
                .put("name", name)
                .put("created_at", now)
            db.execSQL(
                """INSERT INTO sync_inbox(operation_id,origin_device_id,sequence_number,operation_type,
                   entity_type,entity_id,payload_json,payload_sha256,status,received_at,origin_created_at)
                   VALUES(?,?,?,'CREATE','CATEGORY',?,?,?,'RECEIVED',?,?)""".trimIndent(),
                arrayOf(operationId, primaryId, sequence, name.lowercase(java.util.Locale.ROOT),
                    payload.toString(), "category-hash-$operationId", now, now)
            )
        }

        val firstId = "primary-category-2"
        val secondId = "primary-category-9"
        receiveCategory(firstId, 1L, 2L, "مشروبات")
        receiveCategory(secondId, 2L, 9L, "معلبات")
        val applier = SyncDomainApplier(context)
        assertTrue(applier.applyReceivedOperation(firstId).applied)
        assertTrue(applier.applyReceivedOperation(secondId).applied)

        val store = StoreRepository(context)
        val firstLocalId = store.resolveSecondaryCategoryId(primaryId, 2L)
        val secondLocalId = store.resolveSecondaryCategoryId(primaryId, 9L)
        assertTrue(firstLocalId != null && firstLocalId != 2L)
        assertTrue(secondLocalId != null && secondLocalId != 9L)
        assertTrue(firstLocalId != secondLocalId)
        assertEquals("مشروبات", db.rawQuery(
            "SELECT name FROM categories WHERE id=?", arrayOf(firstLocalId.toString())
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        assertEquals("معلبات", db.rawQuery(
            "SELECT name FROM categories WHERE id=?", arrayOf(secondLocalId.toString())
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })

        val categoryCount = db.rawQuery("SELECT COUNT(*) FROM categories", null)
            .use { c -> c.moveToFirst(); c.getInt(0) }
        // Replaying the same already-applied inbox item must not create another row or mapping.
        db.execSQL("UPDATE sync_inbox SET status='RECEIVED',applied_at=NULL WHERE operation_id=?", arrayOf(firstId))
        assertTrue(applier.applyReceivedOperation(firstId).applied)
        assertEquals(categoryCount, db.rawQuery("SELECT COUNT(*) FROM categories", null)
            .use { c -> c.moveToFirst(); c.getInt(0) })
        assertEquals(2, db.rawQuery("SELECT COUNT(*) FROM sync_category_mappings", null)
            .use { c -> c.moveToFirst(); c.getInt(0) })

        // Reusing primary ID 2 for another name must fail atomically and preserve the original mapping.
        val conflictId = "primary-category-2-conflict"
        receiveCategory(conflictId, 3L, 2L, "تصنيف متعارض")
        try {
            applier.applyReceivedOperation(conflictId)
            throw AssertionError("Expected a conflicting primary category ID to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("مرتبط مسبقاً"))
        }
        assertEquals(categoryCount, db.rawQuery("SELECT COUNT(*) FROM categories", null)
            .use { c -> c.moveToFirst(); c.getInt(0) })
        assertTrue(store.resolveSecondaryCategoryId(primaryId, 2L) !in listOf(501L, 902L))
        assertEquals("RECEIVED", inboxStatus(conflictId))
    }

    @Test fun primaryExchangeRateChangeIsQueuedAsAnAuthoritativeReferenceEvent() {
        val before = database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sync_operation_envelopes WHERE entity_type='EXCHANGE_RATE'", null
        ).use { it.moveToFirst(); it.getInt(0) }

        val saved = StoreRepository(context).saveExchangeRate(BigDecimal("15250.75"), adminId)
        val event = database.readableDatabase.rawQuery(
            """SELECT e.operation_id,e.entity_id,e.payload_json,q.status
               FROM sync_operation_envelopes e JOIN sync_queue q ON q.operation_id=e.operation_id
               WHERE e.entity_type='EXCHANGE_RATE' ORDER BY e.sequence_number DESC LIMIT 1""".trimIndent(), null
        ).use { c ->
            assertTrue(c.moveToFirst())
            arrayOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
        }
        val payload = JSONObject(event[2])
        assertEquals(before + 1, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sync_operation_envelopes WHERE entity_type='EXCHANGE_RATE'", null
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(saved, payload.getLong("rate_syp_per_usd_milli"))
        assertEquals(event[1], payload.getString("id"))
        assertEquals("SET_EXCHANGE_RATE", payload.getString("business_operation"))
        assertEquals("PENDING", event[3])
    }

    @Test fun secondaryAppliesPrimaryExchangeRateExactlyOnceWithoutEcho() {
        val db = database.writableDatabase
        val primaryId = "remote-authoritative-primary"
        val now = "2026-11-03T13:00:00.000Z"
        db.execSQL(
            """INSERT INTO sync_devices(device_id,display_name,device_role,status,created_at)
               VALUES(?,?,'PRIMARY','ACTIVE',?)""",
            arrayOf(primaryId, "الجهاز الأساسي البعيد", now)
        )
        db.execSQL(
            "UPDATE sync_protocol_state SET state_value='SECONDARY' WHERE state_key='local_device_role'"
        )
        db.execSQL(
            "UPDATE sync_protocol_state SET state_value=? WHERE state_key='authority_device_id'",
            arrayOf(primaryId)
        )
        val operationId = "primary-exchange-rate-event"
        val rateId = "primary-exchange-rate-row"
        val payload = JSONObject().put("business_operation", "SET_EXCHANGE_RATE")
            .put("id", rateId).put("currency_code", "USD")
            .put("rate_syp_per_usd_milli", 15_750_000L)
            .put("effective_at", now).put("created_at", now).toString()
        db.execSQL(
            """INSERT INTO sync_inbox(operation_id,origin_device_id,sequence_number,operation_type,
               entity_type,entity_id,payload_json,payload_sha256,status,received_at,origin_created_at)
               VALUES(?,?,?,'CREATE','EXCHANGE_RATE',?,?,?,'RECEIVED',?,?)""".trimIndent(),
            arrayOf(operationId, primaryId, 4L, rateId, payload, "test-payload-hash", now, now)
        )

        val applier = SyncDomainApplier(context)
        assertTrue(applier.applyReceivedOperation(operationId).applied)
        assertEquals("APPLIED", inboxStatus(operationId))
        assertEquals(15_750_000L, db.rawQuery(
            "SELECT rate_syp_per_usd_milli FROM exchange_rates WHERE id=?", arrayOf(rateId)
        ).use { it.moveToFirst(); it.getLong(0) })
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM exchange_rates WHERE id=?", arrayOf(rateId)
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, db.rawQuery(
            "SELECT COUNT(*) FROM sync_operation_envelopes WHERE entity_id=?", arrayOf(rateId)
        ).use { it.moveToFirst(); it.getInt(0) })

        db.execSQL(
            "UPDATE sync_inbox SET status='RECEIVED',applied_at=NULL WHERE operation_id=?",
            arrayOf(operationId)
        )
        assertTrue(applier.applyReceivedOperation(operationId).applied)
        assertEquals(1, db.rawQuery(
            "SELECT COUNT(*) FROM exchange_rates WHERE id=?", arrayOf(rateId)
        ).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun appliesIncomingPurchaseOnceAndMarksInboxApplied() {
        val operationId = receiveInvoice("POST_PURCHASE_INVOICE", "purchase-remote-1", null, "PURCHASE")
        val applier = SyncDomainApplier(context)

        assertTrue(applier.applyReceivedOperation(operationId).applied)
        // Simulate process death after the invoice posting committed but before
        // the inbox APPLIED marker was durably written.
        database.writableDatabase.execSQL(
            "UPDATE sync_inbox SET status='RECEIVED',applied_at=NULL WHERE operation_id=?",
            arrayOf(operationId)
        )
        assertTrue(applyWithDiagnostic(applier, operationId).applied)
        assertTrue(applyWithDiagnostic(applier, operationId).applied)
        assertEquals("POSTED", invoiceStatus("purchase_invoices", "purchase-remote-1"))
        assertEquals(12.0, stock(), 0.000001)
        assertEquals("APPLIED", inboxStatus(operationId))
        assertEquals("2026-11-03T12:00:00Z", database.readableDatabase.rawQuery(
            "SELECT origin_created_at FROM sync_inbox WHERE operation_id=?", arrayOf(operationId)
        ).use { it.moveToFirst(); it.getString(0) })
        assertEquals("2026-11-03T12:00:00Z", database.readableDatabase.rawQuery(
            "SELECT created_at FROM inventory_movements WHERE source_id=? AND movement_type='PURCHASE' LIMIT 1",
            arrayOf("purchase-remote-1")
        ).use { it.moveToFirst(); it.getString(0) })
        assertEquals(1, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM inventory_movements WHERE source_id=? AND movement_type='PURCHASE'",
            arrayOf("purchase-remote-1")
        ).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun appliesIncomingCreditSaleAndDoesNotCreateCashReceipt() {
        val customerId = CustomerRepository(context).create(adminId, "زبون مزامنة").id
        val operationId = receiveInvoice("POST_SALES_INVOICE", "sale-remote-1", customerId, "CREDIT")

        assertTrue(applyWithDiagnostic(SyncDomainApplier(context), operationId).applied)
        assertEquals("POSTED", invoiceStatus("sales_invoices", "sale-remote-1"))
        assertEquals(8.0, stock(), 0.000001)
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_transactions WHERE source_type='SALES_INVOICE' AND source_id=?",
            arrayOf("sale-remote-1")
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals("2026-11-03T12:00:00Z", database.readableDatabase.rawQuery(
            "SELECT posted_at FROM sales_invoices WHERE id=?",
            arrayOf("sale-remote-1")
        ).use { it.moveToFirst(); it.getString(0) })
        assertEquals("2026-11-03", database.readableDatabase.rawQuery(
            "SELECT posted_operating_date FROM sales_invoices WHERE id=?",
            arrayOf("sale-remote-1")
        ).use { it.moveToFirst(); it.getString(0) })
        assertEquals("2026-11-03T12:00:00Z", database.readableDatabase.rawQuery(
            "SELECT created_at FROM inventory_movements WHERE source_id=? AND movement_type='SALE' LIMIT 1",
            arrayOf("sale-remote-1")
        ).use { it.moveToFirst(); it.getString(0) })
        assertEquals(1, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM inventory_movements WHERE source_id=? AND movement_type='SALE'",
            arrayOf("sale-remote-1")
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals("APPLIED", inboxStatus(operationId))
    }

    @Test fun appliesMappedCashSaleOnceWithOriginalReceiptTimestamp() {
        val primaryWalletId = UUID.randomUUID().toString()
        database.writableDatabase.execSQL(
            """INSERT INTO cash_accounts(id,name,account_type,currency_code,opening_balance_minor,is_active,created_at)
               VALUES(?,?,?,?,0,1,?)""",
            arrayOf(primaryWalletId, "محفظة الاختبار الأساسية", "WALLET", "USD", "2026-11-03T11:00:00Z")
        )
        protocol.mapSecondaryCashAccount(adminId, secondaryId, "remote-wallet-usd", primaryWalletId)
        val operationId = receiveInvoice(
            "POST_SALES_INVOICE", "sale-cash-mapped", null, "CASH",
            "remote-wallet-usd", "WALLET", "USD"
        )
        val applier = SyncDomainApplier(context)

        assertTrue(applyWithDiagnostic(applier, operationId).applied)
        assertEquals("PAID", invoiceStatus("sales_invoices", "sale-cash-mapped"))
        assertEquals(8.0, stock(), 0.000001)
        assertEquals(1, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_transactions WHERE source_type='SALES_INVOICE' AND source_id=?",
            arrayOf("sale-cash-mapped")
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(primaryWalletId, database.readableDatabase.rawQuery(
            "SELECT account_id FROM cash_transactions WHERE source_type='SALES_INVOICE' AND source_id=?",
            arrayOf("sale-cash-mapped")
        ).use { it.moveToFirst(); it.getString(0) })
        assertEquals(300L, database.readableDatabase.rawQuery(
            "SELECT amount_minor FROM cash_transactions WHERE source_type='SALES_INVOICE' AND source_id=?",
            arrayOf("sale-cash-mapped")
        ).use { it.moveToFirst(); it.getLong(0) })
        assertEquals("2026-11-03T12:00:00Z", database.readableDatabase.rawQuery(
            "SELECT created_at FROM cash_transactions WHERE source_type='SALES_INVOICE' AND source_id=?",
            arrayOf("sale-cash-mapped")
        ).use { it.moveToFirst(); it.getString(0) })
        assertEquals("2026-11-03T12:00:00Z", database.readableDatabase.rawQuery(
            "SELECT created_at FROM invoice_payments WHERE invoice_id=?",
            arrayOf("sale-cash-mapped")
        ).use { it.moveToFirst(); it.getString(0) })

        // Replaying after the ledger transaction committed must not duplicate cash or stock.
        database.writableDatabase.execSQL(
            "UPDATE sync_inbox SET status='RECEIVED',applied_at=NULL WHERE operation_id=?",
            arrayOf(operationId)
        )
        assertTrue(applyWithDiagnostic(applier, operationId).applied)
        assertEquals(1, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_transactions WHERE source_type='SALES_INVOICE' AND source_id=?",
            arrayOf("sale-cash-mapped")
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM inventory_movements WHERE source_id=? AND movement_type='SALE'",
            arrayOf("sale-cash-mapped")
        ).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun rejectsCashSaleWithoutApplyingOrChangingInventory() {
        val operationId = receiveInvoice(
            "POST_SALES_INVOICE", "sale-cash-remote", null, "CASH",
            "remote-wallet-unmapped", "WALLET", "USD"
        )
        var rejected = false
        try { SyncDomainApplier(context).applyReceivedInvoice(operationId) }
        catch (e: IllegalArgumentException) { rejected = e.message?.contains("لم يتم ربط حساب قبض") == true }

        assertTrue(rejected)
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sales_invoices WHERE id=?", arrayOf("sale-cash-remote")
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(10.0, stock(), 0.000001)
        assertEquals("RECEIVED", inboxStatus(operationId))
    }

    @Test fun rejectsCashSaleWhenReceiptAmountDoesNotMatchInvoice() {
        val primaryWalletId = createPrimaryWallet("USD")
        protocol.mapSecondaryCashAccount(adminId, secondaryId, "remote-wallet-bad-amount", primaryWalletId)
        val operationId = receiveInvoice(
            "POST_SALES_INVOICE", "sale-cash-bad-amount", null, "CASH",
            "remote-wallet-bad-amount", "WALLET", "USD", receiptAmountMinor = 299L
        )

        var rejected = false
        try { SyncDomainApplier(context).applyReceivedInvoice(operationId) }
        catch (e: IllegalArgumentException) { rejected = e.message?.contains("قيمة قيد القبض") == true }

        assertTrue(rejected)
        assertEquals("RECEIVED", inboxStatus(operationId))
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sales_invoices WHERE id='sale-cash-bad-amount'", null
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(10.0, stock(), 0.000001)
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_transactions WHERE source_id='sale-cash-bad-amount'", null
        ).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun rejectsCashSaleWhenMappedAccountIsDeactivated() {
        val primaryWalletId = createPrimaryWallet("USD")
        protocol.mapSecondaryCashAccount(adminId, secondaryId, "remote-wallet-disabled", primaryWalletId)
        database.writableDatabase.execSQL(
            "UPDATE sync_cash_account_mappings SET is_active=0 WHERE device_id=? AND remote_cash_account_id=?",
            arrayOf(secondaryId, "remote-wallet-disabled")
        )
        val operationId = receiveInvoice(
            "POST_SALES_INVOICE", "sale-cash-disabled-map", null, "CASH",
            "remote-wallet-disabled", "WALLET", "USD"
        )

        var rejected = false
        try { SyncDomainApplier(context).applyReceivedInvoice(operationId) }
        catch (e: IllegalArgumentException) { rejected = e.message?.contains("لم يتم ربط حساب قبض") == true }

        assertTrue(rejected)
        assertEquals("RECEIVED", inboxStatus(operationId))
        assertEquals(10.0, stock(), 0.000001)
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_transactions WHERE source_id='sale-cash-disabled-map'", null
        ).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun rejectsCashSaleWhenRemoteAccountTypeOrCurrencyDoesNotMatchMapping() {
        val primaryWalletId = createPrimaryWallet("USD")
        protocol.mapSecondaryCashAccount(adminId, secondaryId, "remote-wallet-metadata", primaryWalletId)
        val operationId = receiveInvoice(
            "POST_SALES_INVOICE", "sale-cash-metadata-mismatch", null, "CASH",
            "remote-wallet-metadata", "CASH_BOX", "USD"
        )

        var rejected = false
        try { SyncDomainApplier(context).applyReceivedInvoice(operationId) }
        catch (e: IllegalArgumentException) { rejected = e.message?.contains("نوع أو عملة حساب القبض") == true }

        assertTrue(rejected)
        assertEquals("RECEIVED", inboxStatus(operationId))
        assertEquals(10.0, stock(), 0.000001)
    }

    @Test fun rejectsMalformedInvoicePayloadWithoutCreatingInvoice() {
        val operationId = receiveRaw(
            "POST_PURCHASE_INVOICE", "purchase-malformed",
            JSONObject().put("business_operation", "POST_PURCHASE_INVOICE")
                .put("id", "purchase-malformed").put("invoice_date", "2026-11-03")
                .put("discount_usd_cents", 0).put("exchange_rate_syp_per_usd_milli", 10_000_000)
                .put("items", JSONArray())
        )
        var rejected = false
        try { SyncDomainApplier(context).applyReceivedInvoice(operationId) }
        catch (_: Exception) { rejected = true }

        assertTrue(rejected)
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM purchase_invoices WHERE id='purchase-malformed'", null
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals("RECEIVED", inboxStatus(operationId))
    }

    private fun applyWithDiagnostic(applier: SyncDomainApplier, operationId: String): SyncDomainApplier.ApplyResult =
        try { applier.applyReceivedInvoice(operationId) }
        catch (e: IllegalArgumentException) {
            throw AssertionError("Incoming invoice apply failed: ${e.message}", e)
        }

    private fun createPrimaryWallet(currency: String): String {
        val id = UUID.randomUUID().toString()
        database.writableDatabase.execSQL(
            """INSERT INTO cash_accounts(id,name,account_type,currency_code,opening_balance_minor,is_active,created_at)
               VALUES(?,?,?,?,0,1,?)""",
            arrayOf(id, "محفظة اختبار $currency", "WALLET", currency, "2026-11-03T11:00:00Z")
        )
        return id
    }

    private fun receiveInvoice(
        operation: String, invoiceId: String, partyId: String?, saleType: String,
        remoteCashAccountId: String? = null, remoteAccountType: String? = null,
        remoteCurrency: String? = null, receiptAmountMinor: Long = 300L
    ): String {
        val payload = JSONObject().apply {
            put("business_operation", operation)
            put("id", invoiceId)
            put("party_id", partyId ?: JSONObject.NULL)
            put("sale_type", saleType)
            put("invoice_date", "2026-11-03")
            put("discount_usd_cents", 0)
            put("total_usd_cents", 300L)
            put("notes", JSONObject.NULL)
            put("exchange_rate_syp_per_usd_milli", 10_000_000L)
            put("items", JSONArray().put(JSONObject().apply {
                put("product_id", productId)
                put("quantity", BigDecimal("2").toPlainString())
                put("unit_price_or_cost_usd_cents", 150L)
            }))
            if (remoteCashAccountId != null) {
                put("cash_account_id", remoteCashAccountId)
                put("cash_account_type", remoteAccountType)
                put("cash_account_currency_code", remoteCurrency)
                put("cash_receipts", JSONArray().put(JSONObject().apply {
                    put("cash_transaction_id", "remote-cash-receipt-$invoiceId")
                    put("amount_minor", receiptAmountMinor)
                    put("currency_code", remoteCurrency)
                    put("exchange_rate_syp_per_usd_milli", 10_000_000L)
                }))
            }
        }
        return receiveRaw(operation, invoiceId, payload)
    }

    @Test fun appliesCustomerCollectionWithOriginalTimeAndNoDuplicateOnReplay() {
        val customerId = CustomerRepository(context).create(adminId, "زبون مزامنة").id
        val invoices = InvoiceRepository(context)
        val draft = invoices.saveSalesDraft(
            adminId, "sync-credit-draft", null, customerId, "2026-11-03", 0L, null,
            listOf(InvoiceLineInput(productId, BigDecimal("2"), 150L)), saleType = "CREDIT"
        )
        invoices.postSale(adminId, draft.invoiceId, "sync-credit-post")
        val primaryWallet = createPrimaryWallet("SYP")
        protocol.mapSecondaryCashAccount(adminId, secondaryId, "remote-syp-wallet", primaryWallet)
        val paymentId = "remote-customer-payment-1"
        val payload = JSONObject().put("business_operation","COLLECT_CUSTOMER_PAYMENT")
            .put("invoice_id",draft.invoiceId).put("payment_id",paymentId)
            .put("cash_transaction_id","remote-cash-1").put("cash_account_id","remote-syp-wallet")
            .put("cash_account_type","WALLET").put("cash_account_currency_code","SYP")
            .put("amount_minor",10_000L).put("currency_code","SYP")
            .put("collection_rate_milli",10_000_000L).put("effective_rate_milli",10_000_000L)
            .put("usd_reference_reduction_cents",100L).put("note","تحصيل اختباري")
            .put("occurred_at","2026-11-03T12:00:00Z").put("operating_date","2026-11-03")
        val operationId = receiveRaw("COLLECT_CUSTOMER_PAYMENT",paymentId,payload)
        val applier=SyncDomainApplier(context)
        assertTrue(applier.applyReceivedCustomerCollection(operationId).applied)
        assertEquals("PARTIALLY_PAID",invoiceStatus("sales_invoices",draft.invoiceId))
        assertEquals(1,database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM invoice_payments WHERE invoice_id=?",arrayOf(draft.invoiceId)
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(10_000L,database.readableDatabase.rawQuery(
            "SELECT amount_minor FROM cash_transactions WHERE operation_id=?",arrayOf(operationId)
        ).use { it.moveToFirst(); it.getLong(0) })
        assertEquals(primaryWallet,database.readableDatabase.rawQuery(
            "SELECT account_id FROM cash_transactions WHERE operation_id=?",arrayOf(operationId)
        ).use { it.moveToFirst(); it.getString(0) })
        assertEquals("2026-11-03T12:00:00Z",database.readableDatabase.rawQuery(
            "SELECT created_at FROM invoice_payments WHERE invoice_id=?",arrayOf(draft.invoiceId)
        ).use { it.moveToFirst(); it.getString(0) })
        assertEquals("2026-11-03",database.readableDatabase.rawQuery(
            "SELECT operating_date FROM cash_transactions WHERE operation_id=?",arrayOf(operationId)
        ).use { it.moveToFirst(); it.getString(0) })
        database.writableDatabase.execSQL(
            "UPDATE sync_inbox SET status='RECEIVED',applied_at=NULL WHERE operation_id=?",arrayOf(operationId)
        )
        assertTrue(applier.applyReceivedCustomerCollection(operationId).applied)
        assertEquals(1,database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM invoice_payments WHERE invoice_id=?",arrayOf(draft.invoiceId)
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1,database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_transactions WHERE operation_id=?",arrayOf(operationId)
        ).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun acceptsCustomerCollectionWhenSyriaOperatingDateDiffersFromUtcCalendarDate() {
        val customerId=CustomerRepository(context).create(adminId,"زبون توقيت سوريا").id
        val invoices=InvoiceRepository(context)
        val draft=invoices.saveSalesDraft(adminId,"syria-time-credit-draft",null,customerId,
            "2026-11-03",0L,null,listOf(InvoiceLineInput(productId,BigDecimal.ONE,150L)),saleType="CREDIT")
        invoices.postSale(adminId,draft.invoiceId,"syria-time-credit-post")
        val wallet=createPrimaryWallet("SYP")
        protocol.mapSecondaryCashAccount(adminId,secondaryId,"remote-syria-wallet",wallet)
        val payload=JSONObject().put("business_operation","COLLECT_CUSTOMER_PAYMENT")
            .put("invoice_id",draft.invoiceId).put("payment_id","remote-syria-payment")
            .put("cash_transaction_id","remote-syria-cash").put("cash_account_id","remote-syria-wallet")
            .put("cash_account_type","WALLET").put("cash_account_currency_code","SYP")
            .put("amount_minor",1_000L).put("currency_code","SYP")
            .put("collection_rate_milli",10_000_000L).put("effective_rate_milli",10_000_000L)
            .put("usd_reference_reduction_cents",10L).put("occurred_at","2026-11-03T22:30:00Z")
            .put("operating_date","2026-11-04")
        val operationId=receiveRaw("COLLECT_CUSTOMER_PAYMENT","remote-syria-payment",payload)
        assertTrue(SyncDomainApplier(context).applyReceivedCustomerCollection(operationId).applied)
        assertEquals("2026-11-03T22:30:00Z",database.readableDatabase.rawQuery(
            "SELECT created_at FROM invoice_payments WHERE invoice_id=?",arrayOf(draft.invoiceId)
        ).use { it.moveToFirst(); it.getString(0) })
        assertEquals("2026-11-04",database.readableDatabase.rawQuery(
            "SELECT operating_date FROM cash_transactions WHERE operation_id=?",arrayOf(operationId)
        ).use { it.moveToFirst(); it.getString(0) })
    }

    @Test fun rejectsCustomerCollectionWithoutActiveCashMapping() {
        val customerId=CustomerRepository(context).create(adminId,"زبون رفض").id
        val invoices=InvoiceRepository(context)
        val draft=invoices.saveSalesDraft(adminId,"reject-credit-draft",null,customerId,
            "2026-11-03",0L,null,listOf(InvoiceLineInput(productId,BigDecimal.ONE,150L)),saleType="CREDIT")
        invoices.postSale(adminId,draft.invoiceId,"reject-credit-post")
        val payload=JSONObject().put("business_operation","COLLECT_CUSTOMER_PAYMENT")
            .put("invoice_id",draft.invoiceId).put("payment_id","remote-payment-unmapped")
            .put("cash_transaction_id","remote-cash-unmapped").put("cash_account_id","unmapped-wallet")
            .put("cash_account_type","WALLET").put("cash_account_currency_code","SYP")
            .put("amount_minor",1_000L).put("currency_code","SYP")
            .put("collection_rate_milli",10_000_000L).put("effective_rate_milli",10_000_000L)
            .put("usd_reference_reduction_cents",10L).put("occurred_at","2026-11-03T12:00:00Z")
            .put("operating_date","2026-11-03")
        val operationId=receiveRaw("COLLECT_CUSTOMER_PAYMENT","remote-payment-unmapped",payload)
        var rejected=false
        try { SyncDomainApplier(context).applyReceivedCustomerCollection(operationId) }
        catch(e:IllegalArgumentException) { rejected=e.message?.contains("غير مربوط أو موقوف")==true }
        assertTrue(rejected)
        assertEquals("RECEIVED",inboxStatus(operationId))
        assertEquals(0,database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM invoice_payments WHERE invoice_id=?",arrayOf(draft.invoiceId)
        ).use { it.moveToFirst(); it.getInt(0) })
    }

    private data class CustomerCollectionFixture(
        val customerId: String, val invoiceId: String, val operationId: String
    )

    private fun receiveCustomerCollectionCommand(
        amountMinor: Long = 10_000L,
        collectionRateMilli: Long = 10_000_000L,
        effectiveRateMilli: Long = 10_000_000L,
        reductionCents: Long = 100L,
        remoteAccountType: String = "WALLET",
        remoteCurrency: String = "SYP",
        mappingActive: Boolean = true
    ): CustomerCollectionFixture {
        val customerId = CustomerRepository(context).create(adminId, "زبون تحصيل " + UUID.randomUUID()).id
        val invoices = InvoiceRepository(context)
        val draft = invoices.saveSalesDraft(
            adminId, "sync-collection-draft-" + UUID.randomUUID(), null, customerId,
            "2026-11-03", 0L, null,
            listOf(InvoiceLineInput(productId, BigDecimal("2"), 150L)), saleType = "CREDIT"
        )
        invoices.postSale(adminId, draft.invoiceId, "sync-collection-post-" + UUID.randomUUID())
        val primaryWallet = createPrimaryWallet("SYP")
        val remoteAccountId = "remote-wallet-" + UUID.randomUUID()
        protocol.mapSecondaryCashAccount(adminId, secondaryId, remoteAccountId, primaryWallet)
        if (!mappingActive) {
            database.writableDatabase.execSQL(
                "UPDATE sync_cash_account_mappings SET is_active=0 WHERE device_id=? AND remote_cash_account_id=?",
                arrayOf(secondaryId, remoteAccountId)
            )
        }
        val paymentId = "remote-payment-" + UUID.randomUUID()
        val payload = JSONObject().put("business_operation", "COLLECT_CUSTOMER_PAYMENT")
            .put("invoice_id", draft.invoiceId).put("payment_id", paymentId)
            .put("cash_transaction_id", "remote-cash-" + UUID.randomUUID())
            .put("cash_account_id", remoteAccountId)
            .put("cash_account_type", remoteAccountType)
            .put("cash_account_currency_code", remoteCurrency)
            .put("amount_minor", amountMinor).put("currency_code", "SYP")
            .put("collection_rate_milli", collectionRateMilli)
            .put("effective_rate_milli", effectiveRateMilli)
            .put("usd_reference_reduction_cents", reductionCents)
            .put("note", "تحصيل اختبار")
            .put("occurred_at", "2026-11-03T12:00:00Z")
            .put("operating_date", "2026-11-03")
        val operationId = receiveRaw("COLLECT_CUSTOMER_PAYMENT", paymentId, payload)
        return CustomerCollectionFixture(customerId, draft.invoiceId, operationId)
    }

    private fun assertCustomerCollectionRejected(
        fixture: CustomerCollectionFixture, expectedMessage: String
    ) {
        var rejected = false
        try {
            SyncDomainApplier(context).applyReceivedCustomerCollection(fixture.operationId)
        } catch (e: IllegalArgumentException) {
            rejected = e.message?.contains(expectedMessage) == true
        }
        assertTrue("Expected rejection containing: $expectedMessage", rejected)
        assertEquals("RECEIVED", inboxStatus(fixture.operationId))
        assertEquals("POSTED", invoiceStatus("sales_invoices", fixture.invoiceId))
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM invoice_payments WHERE invoice_id=?", arrayOf(fixture.invoiceId)
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_transactions WHERE operation_id=?", arrayOf(fixture.operationId)
        ).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun rejectsCustomerCollectionWhenMappedAccountTypeOrCurrencyDoesNotMatch() {
        val fixture = receiveCustomerCollectionCommand(remoteAccountType = "CASH_BOX")
        assertCustomerCollectionRejected(fixture, "نوع أو عملة حساب القبض المرتبط")
    }

    @Test fun rejectsCustomerCollectionWhenMappedAccountIsInactive() {
        val fixture = receiveCustomerCollectionCommand(mappingActive = false)
        assertCustomerCollectionRejected(fixture, "غير مربوط أو موقوف")
    }

    @Test fun rejectsCustomerCollectionOverpaymentWithoutAnyLedgerMutation() {
        val fixture = receiveCustomerCollectionCommand(amountMinor = 30_001L, reductionCents = 300L)
        assertCustomerCollectionRejected(fixture, "يتجاوز الرصيد المتبقي")
    }

    @Test fun rejectsCustomerCollectionWithInvalidRateOrReferenceReduction() {
        val invalidRate = receiveCustomerCollectionCommand(collectionRateMilli = 0L)
        assertCustomerCollectionRejected(invalidRate, "سعر صرف التحصيل غير صالح")

        val invalidReduction = receiveCustomerCollectionCommand(reductionCents = 99L)
        assertCustomerCollectionRejected(invalidReduction, "تخفيض الدين المرجعي لا يطابق")
    }

    @Test fun customerCollectionReducesUsdReferenceAndCreatesNoOutboxEcho() {
        val fixture = receiveCustomerCollectionCommand()
        val outboxBefore = protocol.pendingOutbox().size
        assertTrue(SyncDomainApplier(context).applyReceivedCustomerCollection(fixture.operationId).applied)

        val remaining = CustomerPaymentRepository(context).listInvoices(fixture.customerId)
            .single { it.id == fixture.invoiceId }
        assertEquals(200L, remaining.outstandingUsdCents)
        assertEquals(1, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM invoice_payments WHERE invoice_id=?", arrayOf(fixture.invoiceId)
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_transactions WHERE operation_id=?", arrayOf(fixture.operationId)
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals("APPLIED", inboxStatus(fixture.operationId))
        assertEquals(outboxBefore, protocol.pendingOutbox().size)
    }


    @Test fun appliesIncomingSalesReturnOncePreservesEventTimeAndDoesNotEcho() {
        val customer=CustomerRepository(context).create(adminId,"زبون مرتجع مزامنة").id
        val invoices=InvoiceRepository(context)
        val draft=invoices.saveSalesDraft(adminId,"sync-return-sale-draft",null,customer,"2026-11-03",0L,null,
            listOf(InvoiceLineInput(productId,BigDecimal("2"),250L)),saleType="CREDIT")
        invoices.postSale(adminId,draft.invoiceId,"sync-return-sale-post")
        val itemId=database.readableDatabase.rawQuery("SELECT id FROM sales_items WHERE sales_invoice_id=?",
            arrayOf(draft.invoiceId)).use { it.moveToFirst();it.getString(0) }
        val outboxBefore=protocol.pendingOutbox().size
        val payload=JSONObject().put("business_operation","POST_SALES_RETURN")
            .put("original_invoice_id",draft.invoiceId).put("return_date","2026-11-03")
            .put("reason","مرتجع وارد").put("occurred_at","2026-11-03T12:00:00Z")
            .put("operating_date","2026-11-03")
            .put("items",JSONArray().put(JSONObject().put("original_sales_item_id",itemId).put("quantity","1")))
            .put("refund_allocations",JSONArray())
        val operationId=receiveRaw("POST_SALES_RETURN","pending-return-"+UUID.randomUUID(),payload)
        val applier=SyncDomainApplier(context)
        assertTrue(applier.applyReceivedOperation(operationId).applied)
        assertEquals("APPLIED",inboxStatus(operationId))
        assertEquals(9.0,stock(),0.000001)
        assertEquals(1,database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM returns WHERE operation_id=?",arrayOf(operationId)
        ).use { it.moveToFirst();it.getInt(0) })
        assertEquals("2026-11-03T12:00:00Z",database.readableDatabase.rawQuery(
            "SELECT created_at FROM returns WHERE operation_id=?",arrayOf(operationId)
        ).use { it.moveToFirst();it.getString(0) })
        assertEquals(outboxBefore,protocol.pendingOutbox().size)
        database.writableDatabase.execSQL("UPDATE sync_inbox SET status='RECEIVED',applied_at=NULL WHERE operation_id=?",
            arrayOf(operationId))
        assertTrue(applier.applyReceivedOperation(operationId).applied)
        assertEquals(1,database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM returns WHERE operation_id=?",arrayOf(operationId)
        ).use { it.moveToFirst();it.getInt(0) })
        assertEquals(9.0,stock(),0.000001)
    }

    @Test fun appliesIncomingPurchaseReturnOnceWithoutDuplicateStockMovement() {
        val supplier=SupplierRepository(context).create(adminId,"مورد مرتجع مزامنة").id
        val invoices=InvoiceRepository(context)
        val draft=invoices.savePurchaseDraft(adminId,"sync-return-purchase-draft",null,supplier,"2026-11-03",0L,null,
            listOf(InvoiceLineInput(productId,BigDecimal("2"),100L)))
        invoices.postPurchase(adminId,draft.invoiceId,"sync-return-purchase-post")
        val itemId=database.readableDatabase.rawQuery("SELECT id FROM purchase_items WHERE purchase_invoice_id=?",
            arrayOf(draft.invoiceId)).use { it.moveToFirst();it.getString(0) }
        val outboxBefore=protocol.pendingOutbox().size
        val payload=JSONObject().put("business_operation","POST_PURCHASE_RETURN")
            .put("original_invoice_id",draft.invoiceId).put("return_date","2026-11-03")
            .put("reason","مرتجع مورد وارد").put("occurred_at","2026-11-03T13:00:00Z")
            .put("operating_date","2026-11-03")
            .put("items",JSONArray().put(JSONObject().put("original_purchase_item_id",itemId).put("quantity","1")))
            .put("refund_allocations",JSONArray())
        val operationId=receiveRaw("POST_PURCHASE_RETURN","unused",payload)
        val applier=SyncDomainApplier(context)
        assertTrue(applier.applyReceivedOperation(operationId).applied)
        assertEquals("APPLIED",inboxStatus(operationId))
        assertEquals(1,database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM returns WHERE operation_id=?",arrayOf(operationId)
        ).use { it.moveToFirst();it.getInt(0) })
        assertEquals("2026-11-03T13:00:00Z",database.readableDatabase.rawQuery(
            "SELECT created_at FROM returns WHERE operation_id=?",arrayOf(operationId)
        ).use { it.moveToFirst();it.getString(0) })
        assertEquals(outboxBefore,protocol.pendingOutbox().size)
        database.writableDatabase.execSQL("UPDATE sync_inbox SET status='RECEIVED',applied_at=NULL WHERE operation_id=?",
            arrayOf(operationId))
        assertTrue(applier.applyReceivedOperation(operationId).applied)
        assertEquals(1,database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM returns WHERE operation_id=?",arrayOf(operationId)
        ).use { it.moveToFirst();it.getInt(0) })
    }

    @Test fun appliesIncomingInventoryAdjustmentOnceUsingPrimaryFifoAndNoEcho() {
        val outboxBefore=protocol.pendingOutbox().size
        val payload=JSONObject().put("business_operation","POST_INVENTORY_ADJUSTMENT")
            .put("product_id",productId).put("warehouse_id",database.readableDatabase.rawQuery(
                "SELECT id FROM warehouses WHERE is_active=1 ORDER BY is_default DESC LIMIT 1",null
            ).use { it.moveToFirst();it.getString(0) })
            .put("quantity_delta","1").put("unit_cost_usd_cents",200L).put("reason","تسوية واردة")
            .put("occurred_at","2026-11-03T14:00:00Z").put("operating_date","2026-11-03")
        val operationId=receiveRaw("POST_INVENTORY_ADJUSTMENT","unused",payload)
        val applier=SyncDomainApplier(context)
        assertTrue(applier.applyReceivedOperation(operationId).applied)
        assertEquals("APPLIED",inboxStatus(operationId))
        assertEquals(11.0,stock(),0.000001)
        assertEquals(1,database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM inventory_adjustments WHERE operation_id=?",arrayOf(operationId)
        ).use { it.moveToFirst();it.getInt(0) })
        assertEquals("2026-11-03T14:00:00Z",database.readableDatabase.rawQuery(
            "SELECT created_at FROM inventory_adjustments WHERE operation_id=?",arrayOf(operationId)
        ).use { it.moveToFirst();it.getString(0) })
        assertEquals(outboxBefore,protocol.pendingOutbox().size)
        database.writableDatabase.execSQL("UPDATE sync_inbox SET status='RECEIVED',applied_at=NULL WHERE operation_id=?",
            arrayOf(operationId))
        assertTrue(applier.applyReceivedOperation(operationId).applied)
        assertEquals(11.0,stock(),0.000001)
        assertEquals(1,database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM inventory_adjustments WHERE operation_id=?",arrayOf(operationId)
        ).use { it.moveToFirst();it.getInt(0) })
    }

    @Test fun rejectsIncomingServiceTransferWithoutCashMappingAndLeavesBothLedgersUntouched() {
        val wallet = ServiceTransferRepository(context)
        wallet.setOpeningBalance(adminId, ServiceTransferRepository.SYRIATEL_SHARED, 20_000L)
        val beforeFloat = wallet.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor
        val payload = JSONObject().put("business_operation", "EXECUTE_SERVICE_TRANSFER")
            .put("service_type", ServiceTransferRepository.SYRIATEL_BALANCE)
            .put("service_key", ServiceTransferRepository.SYRIATEL_SHARED)
            .put("delivered_minor", 2_000L).put("provider_fee_minor", 0L)
            .put("profit_minor", 400L).put("customer_charge_minor", 2_400L)
            .put("cash_account_id", "unmapped-service-receipt").put("cash_account_type", "WALLET")
            .put("cash_account_currency_code", "SYP").put("occurred_at", "2026-11-03T14:15:00Z")
            .put("operating_date", "2026-11-03")
        val operationId = receiveRaw("EXECUTE_SERVICE_TRANSFER", "unused", payload)
        var rejected = false
        try {
            SyncDomainApplier(context).applyReceivedOperation(operationId)
        } catch (expected: IllegalArgumentException) {
            rejected = expected.message?.contains("غير مربوط أو موقوف") == true
        }
        assertTrue(rejected)
        assertEquals("RECEIVED", inboxStatus(operationId))
        assertEquals(beforeFloat, wallet.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM service_transfers WHERE operation_id=?", arrayOf(operationId)
        ).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun rejectsIncomingServiceTransferWhenPrimaryFloatIsInsufficientWithoutPartialPosting() {
        val wallet = ServiceTransferRepository(context)
        wallet.setOpeningBalance(adminId, ServiceTransferRepository.SYRIATEL_SHARED, 500L)
        val receipt = CashAccountRepository(context).create(adminId, "قبض تحويل رصيد غير كاف", "WALLET", "SYP", 0L)
        protocol.mapSecondaryCashAccount(adminId, secondaryId, "remote-low-float-receipt", receipt.id)
        val beforeReceipt = CashAccountRepository(context).balance(receipt.id)
        val payload = JSONObject().put("business_operation", "EXECUTE_SERVICE_TRANSFER")
            .put("service_type", ServiceTransferRepository.SYRIATEL_BALANCE)
            .put("service_key", ServiceTransferRepository.SYRIATEL_SHARED)
            .put("delivered_minor", 2_000L).put("provider_fee_minor", 0L)
            .put("profit_minor", 400L).put("customer_charge_minor", 2_400L)
            .put("cash_account_id", "remote-low-float-receipt").put("cash_account_type", "WALLET")
            .put("cash_account_currency_code", "SYP").put("occurred_at", "2026-11-03T14:15:00Z")
            .put("operating_date", "2026-11-03")
        val operationId = receiveRaw("EXECUTE_SERVICE_TRANSFER", "unused", payload)
        var rejected = false
        try {
            SyncDomainApplier(context).applyReceivedOperation(operationId)
        } catch (expected: IllegalArgumentException) {
            rejected = expected.message?.contains("غير كافٍ") == true
        }
        assertTrue(rejected)
        assertEquals("RECEIVED", inboxStatus(operationId))
        assertEquals(beforeReceipt, CashAccountRepository(context).balance(receipt.id))
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM service_transfers WHERE operation_id=?", arrayOf(operationId)
        ).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun appliesIncomingServiceTransferOnceAndPreservesOriginalEventDateWithoutEcho() {
        val wallet = ServiceTransferRepository(context)
        wallet.setOpeningBalance(adminId, ServiceTransferRepository.SYRIATEL_SHARED, 20_000L)
        val receipt = CashAccountRepository(context).create(adminId, "محفظة قبض تحويل مزامنة", "WALLET", "SYP", 0L)
        protocol.mapSecondaryCashAccount(adminId, secondaryId, "remote-service-wallet", receipt.id)
        val beforeOutbox = protocol.pendingOutbox().size
        val payload = JSONObject().put("business_operation", "EXECUTE_SERVICE_TRANSFER")
            .put("service_type", ServiceTransferRepository.SYRIATEL_BALANCE).put("service_key", ServiceTransferRepository.SYRIATEL_SHARED)
            .put("delivered_minor", 2_000L).put("provider_fee_minor", 100L).put("profit_minor", 400L).put("customer_charge_minor", 2_500L)
            .put("cash_account_id", "remote-service-wallet").put("cash_account_type", "WALLET").put("cash_account_currency_code", "SYP")
            .put("reference", "اختبار").put("note", JSONObject.NULL).put("occurred_at", "2026-11-03T14:15:00Z").put("operating_date", "2026-11-03")
        val operationId = receiveRaw("EXECUTE_SERVICE_TRANSFER", "unused", payload)
        val applier = SyncDomainApplier(context)
        assertTrue(applier.applyReceivedOperation(operationId).applied)
        assertEquals("APPLIED", inboxStatus(operationId))
        assertEquals(18_000L, wallet.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
        assertEquals(2_500L, CashAccountRepository(context).balance(receipt.id))
        assertEquals("2026-11-03T14:15:00Z", database.readableDatabase.rawQuery("SELECT created_at FROM service_transfers WHERE operation_id=?",arrayOf(operationId)).use{c->assertTrue(c.moveToFirst());c.getString(0)})
        assertEquals("2026-11-03", database.readableDatabase.rawQuery("SELECT operating_date FROM service_transfers WHERE operation_id=?",arrayOf(operationId)).use{c->assertTrue(c.moveToFirst());c.getString(0)})
        assertEquals(beforeOutbox, protocol.pendingOutbox().size)
        database.writableDatabase.execSQL("UPDATE sync_inbox SET status='RECEIVED',applied_at=NULL WHERE operation_id=?",arrayOf(operationId))
        assertTrue(applier.applyReceivedOperation(operationId).applied)
        assertEquals(1,database.readableDatabase.rawQuery("SELECT COUNT(*) FROM service_transfers WHERE operation_id=?",arrayOf(operationId)).use{it.moveToFirst();it.getInt(0)})
        assertEquals(18_000L, wallet.listWallets().single { it.serviceKey == ServiceTransferRepository.SYRIATEL_SHARED }.balanceMinor)
    }

    @Test fun appliesIncomingSupplierRolloverOnceWithMappedAccountAndOriginalBusinessDate() {
        val source = CashAccountRepository(context).create(adminId, "صندوق الموردين المزامن", "CASH_BOX", "SYP", 50_000L)
        protocol.mapSecondaryCashAccount(adminId, secondaryId, "remote-supplier-box", source.id)
        val beforeOutbox = protocol.pendingOutbox().size
        val payload = JSONObject().put("business_operation","SET_SUPPLIER_ROLLOVER")
            .put("cash_account_id","remote-supplier-box").put("cash_account_type","CASH_BOX")
            .put("cash_account_currency_code","SYP").put("amount_minor",12_000L)
            .put("note","تخصيص تجريبي").put("occurred_at","2026-11-03T15:20:00Z")
            .put("operating_date","2026-11-03")
        val operationId = receiveRaw("SET_SUPPLIER_ROLLOVER","unused",payload)
        val applier = SyncDomainApplier(context)
        assertTrue(applier.applyReceivedOperation(operationId).applied)
        assertEquals("APPLIED",inboxStatus(operationId))
        assertEquals(12_000L,database.readableDatabase.rawQuery(
            "SELECT allocated_minor FROM daily_supplier_rollovers WHERE operating_date='2026-11-03'",null
        ).use { it.moveToFirst();it.getLong(0) })
        assertEquals(source.id,database.readableDatabase.rawQuery(
            "SELECT account_id FROM daily_supplier_rollovers WHERE operating_date='2026-11-03'",null
        ).use { it.moveToFirst();it.getString(0) })
        assertEquals("2026-11-03T15:20:00Z",database.readableDatabase.rawQuery(
            "SELECT created_at FROM daily_supplier_rollovers WHERE operating_date='2026-11-03'",null
        ).use { it.moveToFirst();it.getString(0) })
        assertEquals(beforeOutbox,protocol.pendingOutbox().size)
        database.writableDatabase.execSQL("UPDATE sync_inbox SET status='RECEIVED',applied_at=NULL WHERE operation_id=?",arrayOf(operationId))
        assertTrue(applier.applyReceivedOperation(operationId).applied)
        assertEquals(1,database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM daily_supplier_rollovers WHERE operating_date='2026-11-03'",null
        ).use { it.moveToFirst();it.getInt(0) })
    }

    private fun receiveRaw(operation: String, entityId: String, payload: JSONObject): String {
        val db = database.readableDatabase
        val storeId = db.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='store_id'", null
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
        val operationId = UUID.randomUUID().toString()
        val resolvedEntityId = when(operation) {
            "POST_SALES_RETURN", "POST_PURCHASE_RETURN" -> "pending-return-$operationId"
            "POST_INVENTORY_ADJUSTMENT" -> "pending-adjustment-$operationId"
            "EXECUTE_SERVICE_TRANSFER" -> "pending-service-transfer-$operationId"
            "SET_SUPPLIER_ROLLOVER" -> "pending-rollover-$operationId"
            else -> entityId
        }
        val entityType = when(operation) {
            "POST_PURCHASE_INVOICE" -> "PURCHASE_INVOICE"
            "COLLECT_CUSTOMER_PAYMENT" -> "CUSTOMER_PAYMENT"
            "POST_SALES_RETURN" -> "SALES_RETURN"
            "POST_PURCHASE_RETURN" -> "PURCHASE_RETURN"
            "POST_INVENTORY_ADJUSTMENT" -> "INVENTORY_ADJUSTMENT"
            "EXECUTE_SERVICE_TRANSFER" -> "SERVICE_TRANSFER"
            "SET_SUPPLIER_ROLLOVER" -> "DAILY_SUPPLIER_ROLLOVER"
            else -> "SALES_INVOICE"
        }
        val envelope = SyncEnvelopeSecurity.create(
            storeId, operationId, nextSequence(), secondaryId, "remote-admin",
            "CREATE", entityType, resolvedEntityId, 0L, payload.toString(), "2026-11-03T12:00:00Z", secondarySecret
        )
        assertEquals(SyncInboxDisposition.RECEIVED,
            protocol.recordIncomingFromSecondary(SyncEnvelopeSecurity.encode(envelope)))
        return operationId
    }

    private fun nextSequence(): Long = database.readableDatabase.rawQuery(
        "SELECT COALESCE(MAX(last_sequence),0)+1 FROM sync_cursors WHERE device_id=?",
        arrayOf(secondaryId)
    ).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }

    private fun invoiceStatus(table: String, id: String): String =
        database.readableDatabase.rawQuery("SELECT status FROM $table WHERE id=?", arrayOf(id))
            .use { c -> assertTrue(c.moveToFirst()); c.getString(0) }

    private fun inboxStatus(id: String): String =
        database.readableDatabase.rawQuery("SELECT status FROM sync_inbox WHERE operation_id=?", arrayOf(id))
            .use { c -> assertTrue(c.moveToFirst()); c.getString(0) }

    private fun stock(): Double =
        database.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(quantity),0) FROM inventory_balances WHERE product_id=?",
            arrayOf(productId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getDouble(0) }
}
