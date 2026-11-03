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

    @Test fun appliesIncomingPurchaseOnceAndMarksInboxApplied() {
        val operationId = receiveInvoice("POST_PURCHASE_INVOICE", "purchase-remote-1", null, "PURCHASE")
        val applier = SyncDomainApplier(context)

        assertTrue(applier.applyReceivedInvoice(operationId).applied)
        assertTrue(applier.applyReceivedInvoice(operationId).applied)
        assertEquals("POSTED", invoiceStatus("purchase_invoices", "purchase-remote-1"))
        assertEquals(12.0, stock(), 0.000001)
        assertEquals("APPLIED", inboxStatus(operationId))
        assertEquals(1, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM inventory_movements WHERE source_id=? AND movement_type='PURCHASE'",
            arrayOf("purchase-remote-1")
        ).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun appliesIncomingCreditSaleAndDoesNotCreateCashReceipt() {
        val customerId = CustomerRepository(context).create(adminId, "زبون مزامنة").id
        val operationId = receiveInvoice("POST_SALES_INVOICE", "sale-remote-1", customerId, "CREDIT")

        assertTrue(SyncDomainApplier(context).applyReceivedInvoice(operationId).applied)
        assertEquals("POSTED", invoiceStatus("sales_invoices", "sale-remote-1"))
        assertEquals(8.0, stock(), 0.000001)
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_transactions WHERE source_type='SALES_INVOICE' AND source_id=?",
            arrayOf("sale-remote-1")
        ).use { it.moveToFirst(); it.getInt(0) })
        assertEquals("APPLIED", inboxStatus(operationId))
    }

    @Test fun rejectsCashSaleWithoutApplyingOrChangingInventory() {
        val operationId = receiveInvoice("POST_SALES_INVOICE", "sale-cash-remote", null, "CASH")
        var rejected = false
        try { SyncDomainApplier(context).applyReceivedInvoice(operationId) }
        catch (_: IllegalArgumentException) { rejected = true }

        assertTrue(rejected)
        assertEquals("DRAFT", invoiceStatus("sales_invoices", "sale-cash-remote"))
        assertEquals(10.0, stock(), 0.000001)
        assertEquals("RECEIVED", inboxStatus(operationId))
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

    private fun receiveInvoice(
        operation: String, invoiceId: String, partyId: String?, saleType: String
    ): String {
        val payload = JSONObject().apply {
            put("business_operation", operation)
            put("id", invoiceId)
            put("party_id", partyId ?: JSONObject.NULL)
            put("sale_type", saleType)
            put("invoice_date", "2026-11-03")
            put("discount_usd_cents", 0)
            put("notes", JSONObject.NULL)
            put("exchange_rate_syp_per_usd_milli", 10_000_000L)
            put("items", JSONArray().put(JSONObject().apply {
                put("product_id", productId)
                put("quantity", BigDecimal("2").toPlainString())
                put("unit_price_or_cost_usd_cents", 150L)
            }))
        }
        return receiveRaw(operation, invoiceId, payload)
    }

    private fun receiveRaw(operation: String, entityId: String, payload: JSONObject): String {
        val db = database.readableDatabase
        val storeId = db.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='store_id'", null
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
        val operationId = UUID.randomUUID().toString()
        val envelope = SyncEnvelopeSecurity.create(
            storeId, operationId, nextSequence(), secondaryId, "remote-admin",
            "CREATE", if (operation == "POST_PURCHASE_INVOICE") "PURCHASE_INVOICE" else "SALES_INVOICE",
            entityId, 0L, payload.toString(), "2026-11-03T12:00:00Z", secondarySecret
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
