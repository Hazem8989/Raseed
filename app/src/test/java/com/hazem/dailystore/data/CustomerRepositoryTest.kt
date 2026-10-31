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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CustomerRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var customers: CustomerRepository
    private lateinit var invoices: InvoiceRepository
    private lateinit var productId: String

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = AppDatabase.getInstance(context)
        val store = StoreRepository(context)
        store.ensureBaseSetup()
        customers = CustomerRepository(context)
        invoices = InvoiceRepository(context)
        val unit = store.getUnits().first()
        val category = store.getCategories().first()
        productId = store.saveProduct(null, "صنف اختبار زبائن", null, null,
            category.id, unit.id, 100, 250, true, 5.0)
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun customerCanBeCreatedUpdatedAndDeactivatedWithoutDeletingHistory() {
        val customer = customers.create("زبون اختبار ${UUID.randomUUID()}", "0999000000")
        assertEquals("0999000000", customer.phone)
        val updated = customers.update(customer.id, "اسم معدل", "0999111111")
        assertEquals("اسم معدل", updated.name)
        customers.setActive(customer.id, false)
        assertNull(customers.listActive().firstOrNull { it.id == customer.id })
        assertTrue(customers.get(customer.id) != null)
    }

    @Test
    fun creditSaleRequiresCustomerAndIsExplicitlyStored() {
        val customer = customers.create("زبون آجل ${UUID.randomUUID()}")
        val rate = ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("currency_code", "SYP")
            put("rate_syp_per_usd_milli", 15000000L)
            put("effective_at", "2026-10-31T00:00:00.000Z")
            put("created_at", "2026-10-31T00:00:00.000Z")
        }
        database.writableDatabase.insertOrThrow("exchange_rates", null, rate)
        val draft = invoices.saveSalesDraft(
            "credit-draft-${UUID.randomUUID()}", null, customer.id, "2026-10-31",
            0L, null, listOf(InvoiceLineInput(productId, BigDecimal.ONE, 250L)),
            saleType = "CREDIT"
        )
        database.readableDatabase.rawQuery(
            "SELECT sale_type FROM sales_invoices WHERE id=?", arrayOf(draft.invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals("CREDIT", c.getString(0)) }
        val cash = invoices.saveSalesDraft(
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
            invoices.saveSalesDraft(
                "credit-no-customer-${UUID.randomUUID()}", null, null, "2026-10-31",
                0L, null, listOf(InvoiceLineInput(productId, BigDecimal.ONE, 250L)),
                saleType = "CREDIT"
            )
            throw AssertionError("Credit sale without a customer must be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
