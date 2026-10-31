package com.hazem.dailystore.data

import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CashAccountRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var accounts: CashAccountRepository
    private lateinit var adminId: String

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = AppDatabase.getInstance(context)
        StoreRepository(context).ensureBaseSetup()
        val auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        adminId = auth.authenticate("admin", "1234")!!.id
        accounts = CashAccountRepository(context)
    }

    @After fun tearDown() { database.close() }

    @Test fun managerCanRenameAccountAndAuditChange() {
        val account = accounts.create("محفظة قديمة ${UUID.randomUUID()}", "WALLET", "USD", 1200L)
        accounts.updateDetails("ADMIN", adminId, account.id, "المحفظة الرئيسية", "WALLET", "USD")
        assertEquals("المحفظة الرئيسية", accounts.listAll().first { it.id == account.id }.name)
        database.readableDatabase.rawQuery(
            "SELECT action,entity_type,before_json,after_json FROM audit_log WHERE entity_id=?",
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
        val account = accounts.create("محفظة برصيد ${UUID.randomUUID()}", "WALLET", "USD", 1000L)
        try {
            accounts.updateDetails("ADMIN", adminId, account.id, account.name, "WALLET", "SYP")
            fail("Currency change must be rejected because opening balance is non-zero")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1000L, accounts.balance(account.id))
    }

    @Test fun nonManagerCannotEditAccount() {
        val account = accounts.create("محفظة اختبار ${UUID.randomUUID()}", "WALLET", "USD", 0L)
        try {
            accounts.updateDetails("CASHIER", "cashier-id", account.id, "اسم جديد", "WALLET", "USD")
            fail("Cashier must not edit accounts")
        } catch (_: IllegalArgumentException) { }
        assertEquals(account.name, accounts.listAll().first { it.id == account.id }.name)
    }
}
