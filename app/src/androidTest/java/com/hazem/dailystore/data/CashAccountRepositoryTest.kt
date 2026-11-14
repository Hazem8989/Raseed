package com.hazem.dailystore.data

import android.content.ContentValues
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class CashAccountRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var accounts: CashAccountRepository
    private lateinit var adminId: String

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        AppDatabase.resetForTests(context)
        database = AppDatabase.getInstance(context)
        StoreRepository(context).ensureBaseSetup()
        val auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        adminId = auth.authenticate("admin", "1234")!!.id
        auth.changePassword(adminId, "1234", "TestAdmin123")
        accounts = CashAccountRepository(context)
    }

    @After fun tearDown() { database.close() }

    @Test fun managerCanRenameAccountAndAuditChange() {
        val account = accounts.create(adminId, "محفظة قديمة ${UUID.randomUUID()}", "WALLET", "USD", 1200L)
        accounts.updateDetails("ADMIN", adminId, account.id, "المحفظة الرئيسية", "WALLET", "USD")
        assertEquals("المحفظة الرئيسية", accounts.listAll().first { it.id == account.id }.name)
        database.readableDatabase.rawQuery(
            "SELECT action,entity_type,before_json,after_json FROM audit_log WHERE entity_id=? AND action='UPDATE'",
            arrayOf(account.id)
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("UPDATE", it.getString(0))
            assertEquals("CASH_ACCOUNT", it.getString(1))
            assertTrue(it.getString(2).contains("محفظة قديمة"))
            assertTrue(it.getString(3).contains("المحفظة الرئيسية"))
        }
    }

    @Test fun accountCurrencyCannotChangeAfterOpeningBalanceExists() {
        val account = accounts.create(adminId, "محفظة برصيد ${UUID.randomUUID()}", "WALLET", "USD", 1000L)
        try {
            accounts.updateDetails("ADMIN", adminId, account.id, account.name, "WALLET", "SYP")
            fail("Currency change must be rejected because opening balance is non-zero")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1000L, accounts.balance(account.id))
    }

    @Test fun supplierRolloverAccountCannotAlsoBeTheDailySalesDrawer() {
        val supplierCash = accounts.create(adminId, "صندوق موردين " + UUID.randomUUID(), "CASH_BOX", "SYP", 50000L)
        DailyBalanceRepository(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
            .setSupplierRollover(adminId, supplierCash.id, 30000L, "فصل الصناديق")
        try {
            accounts.setCurrentDayPrimaryDrawer("ADMIN", adminId, supplierCash.id)
            fail("Supplier rollover account must not also be the daily drawer")
        } catch (_: IllegalArgumentException) { }
        val dailyDrawer = accounts.currentDayPrimaryDrawer()
        assertNotNull(dailyDrawer)
        assertNotEquals(supplierCash.id, dailyDrawer!!.id)
    }

    @Test fun cashierCannotCreateFinancialAccount() {
        val cashierId = UUID.randomUUID().toString()
        val now = "2026-11-02T10:00:00.000Z"
        val cashier = ContentValues().apply {
            put("id", cashierId); put("username", "cashier_$cashierId")
            put("display_name", "Cashier"); put("password_hash", "test")
            put("password_salt", "test"); put("role_key", "CASHIER")
            put("is_active", 1); put("must_change_password", 0)
            put("created_at", now); put("updated_at", now)
        }
        database.writableDatabase.insertOrThrow("users", null, cashier)
        val before = accounts.listAll().size
        try {
            accounts.create(cashierId, "حساب غير مصرح", "CASH_BOX", "SYP", 50_000L)
            fail("Cashier must not create a financial account")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, accounts.listAll().size)
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM audit_log WHERE user_id=? AND action='CREATE' AND entity_type='CASH_ACCOUNT'",
            arrayOf(cashierId)
        ).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
    }

    @Test fun managerAccountCreationIsAudited() {
        val account = accounts.create(adminId, "حساب مدقق " + UUID.randomUUID(), "WALLET", "SYP", 2500L)
        database.readableDatabase.rawQuery(
            "SELECT user_id,action,after_json FROM audit_log WHERE entity_id=? AND action='CREATE'",
            arrayOf(account.id)
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(adminId, it.getString(0))
            assertEquals("CREATE", it.getString(1))
            assertTrue(it.getString(2).contains("2500"))
        }
    }

    @Test fun nonManagerCannotEditAccount() {
        val account = accounts.create(adminId, "محفظة اختبار ${UUID.randomUUID()}", "WALLET", "USD", 0L)
        try {
            accounts.updateDetails("CASHIER", "cashier-id", account.id, "اسم جديد", "WALLET", "USD")
            fail("Cashier must not edit accounts")
        } catch (_: IllegalArgumentException) { }
        assertEquals(account.name, accounts.listAll().first { it.id == account.id }.name)
    }
}
