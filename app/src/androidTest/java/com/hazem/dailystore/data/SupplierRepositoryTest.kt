package com.hazem.dailystore.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class SupplierRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    init {
        AppDatabase.resetForTests(context)
    }

    private val database = AppDatabase.getInstance(context)
    private val suppliers = SupplierRepository(context)
    private fun managerId(): String {
        val auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        val admin = auth.authenticate("admin", "1234")!!
        auth.changePassword(admin.id, "1234", "TestAdmin123")
        return admin.id
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createsAndListsSupplierWithOptionalContactDetails() {
        val created = suppliers.create(managerId(), "مورد اختبار", "0999000000", "دمشق")
        val found = suppliers.listActive().first { it.id == created.id }

        assertEquals("مورد اختبار", found.name)
        assertEquals("0999000000", found.phone)
        assertEquals("دمشق", found.address)
    }

    @Test
    fun rejectsBlankSupplierName() {
        try {
            suppliers.create(managerId(), "   ")
            throw AssertionError("A blank supplier name must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected validation.
        }
    }
}
