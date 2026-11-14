package com.hazem.dailystore.data

import android.content.ContentValues
import java.math.BigDecimal
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class CustomerRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var customers: CustomerRepository
    private lateinit var invoices: InvoiceRepository
    private lateinit var productId: String
    private lateinit var adminId: String

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
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
        val unit = store.getUnits().first()
        val category = store.getCategories().first()
        productId = store.saveProduct(null, "صنف اختبار زبائن", null, null,
            category.id, unit.id, 100, 250, true, 5.0, adminId)
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun customerCanBeCreatedUpdatedAndDeactivatedWithoutDeletingHistory() {
        val customer = customers.create(adminId, "زبون اختبار ${UUID.randomUUID()}", "0999000000")
        assertEquals("0999000000", customer.phone)
        val updated = customers.update(adminId, customer.id, "اسم معدل", "0999111111")
        assertEquals("اسم معدل", updated.name)
        customers.setActive(adminId, customer.id, false)
        assertNull(customers.listActive().firstOrNull { it.id == customer.id })
        assertTrue(customers.get(customer.id) != null)
    }

    @Test
    fun secondaryCannotCreateUpdateOrDeactivateCustomerMasterData() {
        val existing = customers.create(adminId, "زبون أساسي ${UUID.randomUUID()}")
        val db = database.writableDatabase
        db.insertOrThrow("sync_protocol_state", null, ContentValues().apply {
            put("state_key", "local_device_role"); put("state_value", "SECONDARY")
            put("updated_at", "2026-11-05T12:00:00.000Z")
        })
        db.insertOrThrow("sync_protocol_state", null, ContentValues().apply {
            put("state_key", "authority_device_id"); put("state_value", UUID.randomUUID().toString())
            put("updated_at", "2026-11-05T12:00:00.000Z")
        })
        var createRejected = false
        try { customers.create(adminId, "محاولة إنشاء ثانوي") }
        catch (_: IllegalArgumentException) { createRejected = true }
        var updateRejected = false
        try { customers.update(adminId, existing.id, "محاولة تعديل ثانوي") }
        catch (_: IllegalArgumentException) { updateRejected = true }
        var deactivateRejected = false
        try { customers.setActive(adminId, existing.id, false) }
        catch (_: IllegalArgumentException) { deactivateRejected = true }
        assertTrue(createRejected)
        assertTrue(updateRejected)
        assertTrue(deactivateRejected)
        assertEquals("زبون أساسي", customers.get(existing.id)?.name?.substringBeforeLast(" "))
        assertEquals(1, customers.listAll().size)
    }

    @Test
    fun primaryCustomerCreateUpdateAndDeactivationAreQueuedForSecondary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        SyncDeviceTestKeyProvider.install()
        SyncProtocolRepository(context).configureAsPrimary(adminId, "Customer sync primary")
        val customer = customers.create(adminId, "زبون مزامنة ${UUID.randomUUID()}", "0999000000")
        customers.update(adminId, customer.id, "زبون بعد التعديل", "0999111111", "عنوان", "ملاحظة")
        customers.setActive(adminId, customer.id, false)
        val events = database.readableDatabase.rawQuery(
            """SELECT operation_type,payload_json FROM sync_operation_envelopes
               WHERE entity_type='CUSTOMER' AND entity_id=? ORDER BY sequence_number""".trimIndent(),
            arrayOf(customer.id)
        ).use { c ->
            val rows = mutableListOf<Pair<String, org.json.JSONObject>>()
            while (c.moveToNext()) rows.add(c.getString(0) to org.json.JSONObject(c.getString(1)))
            rows
        }
        assertEquals(3, events.size)
        assertEquals("CREATE", events[0].first)
        assertEquals("UPSERT_CUSTOMER", events[0].second.getString("business_operation"))
        assertEquals("UPDATE", events[1].first)
        assertEquals("زبون بعد التعديل", events[1].second.getString("name"))
        assertEquals("UPDATE", events[2].first)
        assertEquals("SET_CUSTOMER_ACTIVE", events[2].second.getString("business_operation"))
        assertEquals(false, events[2].second.getBoolean("is_active"))
    }

    @Test
    fun creditSaleRequiresCustomerAndIsExplicitlyStored() {
        val customer = customers.create(adminId, "زبون آجل ${UUID.randomUUID()}")
        val rate = ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("currency_code", "SYP")
            put("rate_syp_per_usd_milli", 15000000L)
            put("effective_at", "2026-10-31T00:00:00.000Z")
            put("created_at", "2026-10-31T00:00:00.000Z")
        }
        database.writableDatabase.insertOrThrow("exchange_rates", null, rate)
        val draft = invoices.saveSalesDraft(adminId, 
            "credit-draft-${UUID.randomUUID()}", null, customer.id, "2026-10-31",
            0L, null, listOf(InvoiceLineInput(productId, BigDecimal.ONE, 250L)),
            saleType = "CREDIT"
        )
        database.readableDatabase.rawQuery(
            "SELECT sale_type FROM sales_invoices WHERE id=?", arrayOf(draft.invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals("CREDIT", c.getString(0)) }
        val cash = invoices.saveSalesDraft(adminId, 
            "cash-draft-${UUID.randomUUID()}", null, null, "2026-10-31",
            0L, null, listOf(InvoiceLineInput(productId, BigDecimal.ONE, 250L))
        )
        database.readableDatabase.rawQuery(
            "SELECT sale_type FROM sales_invoices WHERE id=?", arrayOf(cash.invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals("CASH", c.getString(0)) }
    }

    @Test
    fun creditSaleWithoutCustomerIsRejected() {
        try {
            invoices.saveSalesDraft(adminId, 
                "credit-no-customer-${UUID.randomUUID()}", null, null, "2026-10-31",
                0L, null, listOf(InvoiceLineInput(productId, BigDecimal.ONE, 250L)),
                saleType = "CREDIT"
            )
            throw AssertionError("Credit sale without a customer must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
