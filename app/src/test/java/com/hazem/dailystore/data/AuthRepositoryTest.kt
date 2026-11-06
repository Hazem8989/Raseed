package com.hazem.dailystore.data

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var auth: AuthRepository

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        AppDatabase.resetForTests(context)
        database = AppDatabase.getInstance(context)
        auth = AuthRepository(context)
    }

    @After fun tearDown() { database.close() }

    private fun createAdmin(): AuthenticatedUser {
        auth.createInitialAdmin("admin", "مدير المتجر", "ManagerPass2026")
        return auth.authenticate("admin", "ManagerPass2026")!!
    }

    @Test fun freshInstallRequiresOwnerChosenCredentialsAndHasNoDefaultLogin() {
        assertTrue(auth.needsInitialAdminSetup())
        assertNull(auth.authenticate("admin", "1234"))
        val id = auth.createInitialAdmin("storeowner", "مدير المتجر", "ManagerPass2026")
        assertFalse(auth.needsInitialAdminSetup())
        val user = auth.authenticate("storeowner", "ManagerPass2026")
        assertNotNull(user)
        assertEquals(id, user!!.id)
        assertEquals("ADMIN", user.roleKey)
        assertFalse(user.mustChangePassword)
        assertNull(auth.authenticate("admin", "1234"))
        database.readableDatabase.rawQuery(
            "SELECT password_hash,password_salt FROM users WHERE id=?", arrayOf(id)
        ).use {
            assertTrue(it.moveToFirst())
            assertNotEquals("ManagerPass2026", it.getString(0))
            assertTrue(it.getString(0).isNotBlank())
            assertTrue(it.getString(1).isNotBlank())
        }
    }

    @Test fun legacyUnclaimedDefaultAdminIsClaimedWithoutKeepingDefaultPassword() {
        val legacyId = auth.createInitialAdmin("admin", "مدير المتجر", "LegacyPass2026")
        database.writableDatabase.execSQL(
            "UPDATE users SET must_change_password=1 WHERE id=?", arrayOf(legacyId)
        )
        assertTrue(auth.needsInitialAdminSetup())
        val claimedId = auth.createInitialAdmin("newowner", "المدير الجديد", "NewManager2026")
        assertEquals(legacyId, claimedId)
        assertFalse(auth.needsInitialAdminSetup())
        assertNotNull(auth.authenticate("newowner", "NewManager2026"))
        assertNull(auth.authenticate("admin", "LegacyPass2026"))
    }

    @Test fun fiveFailedLoginsTemporarilyBlockEvenCorrectCredentials() {
        auth.createInitialAdmin("admin", "مدير المتجر", "ManagerPass2026")
        repeat(5) {
            assertNull(auth.authenticate("admin", "WrongPassword2026"))
        }
        assertNull("Correct credentials must be blocked during the temporary lock",
            auth.authenticate("admin", "ManagerPass2026"))
        val usernameHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest("admin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        database.writableDatabase.execSQL(
            "UPDATE auth_login_attempts SET locked_until_ms=0 WHERE username_hash=?",
            arrayOf(usernameHash)
        )
        assertNotNull("A correct login must work after the lock expires",
            auth.authenticate("admin", "ManagerPass2026"))
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM auth_login_attempts WHERE username_hash=?", arrayOf(usernameHash)
        ).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun initialAdminSetupCannotBeRepeatedAfterAnAccountExists() {
        createAdmin()
        try {
            auth.createInitialAdmin("secondadmin", "مدير آخر", "AnotherPass2026")
            fail("Initial setup must be closed after the first administrator is created")
        } catch (_: IllegalStateException) { }
    }

    @Test fun managerCanCreateCashierButCashierCannotCreateUsers() {
        val admin = createAdmin()
        val cashierId = auth.createUser(admin.roleKey, admin.id,
            "cashier1", "موظف الصندوق", "Cashier5678", "CASHIER")
        assertEquals("CASHIER", auth.authenticate("cashier1", "Cashier5678")!!.roleKey)
        try {
            auth.createUser("CASHIER", cashierId, "cashier2", "موظف آخر", "Cashier6789", "CASHIER")
            fail("Cashier must not create users")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun listingUsersRequiresTheUsersManagePermission() {
        val admin = createAdmin()
        val cashierId = auth.createUser(admin.roleKey, admin.id,
            "cashierlist", "موظف قائمة", "Cashier5678", "CASHIER")
        auth.changePassword(cashierId, "Cashier5678", "CashierPass2026")
        assertEquals(2, auth.listUsers(admin.id).size)
        try {
            auth.listUsers(cashierId)
            fail("Cashier must not read user-management records")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun weakPasswordsAreRejectedForFirstAdminAndNewUsers() {
        try {
            auth.createInitialAdmin("weakadmin", "مدير ضعيف", "123456")
            fail("Weak initial administrator password must be rejected")
        } catch (_: IllegalArgumentException) { }
        val admin = createAdmin()
        try {
            auth.createUser(admin.roleKey, admin.id, "weakuser", "مستخدم ضعيف", "123456", "CASHIER")
            fail("Weak password must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun cashierDefaultsAllowSalesAndCustomerCollectionButDenyPurchasingAndCatalogChanges() {
        val admin = createAdmin()
        val cashierId = auth.createUser(admin.roleKey, admin.id,
            "cashierperm", "موظف صلاحيات", "Cashier5678", "CASHIER")
        auth.changePassword(cashierId, "Cashier5678", "CashierPass2026")
        val db = database.writableDatabase
        PermissionPolicy.require(db, cashierId, PermissionPolicy.SALES_CHECKOUT)
        PermissionPolicy.require(db, cashierId, PermissionPolicy.CUSTOMERS_READ)
        PermissionPolicy.require(db, cashierId, PermissionPolicy.CUSTOMERS_CREATE)
        PermissionPolicy.require(db, cashierId, PermissionPolicy.CUSTOMERS_COLLECT)
        listOf(
            PermissionPolicy.PURCHASES_MANAGE, PermissionPolicy.SUPPLIERS_MANAGE,
            PermissionPolicy.CATALOG_MANAGE, PermissionPolicy.ACCOUNTS_MANAGE,
            PermissionPolicy.RETURNS_PROCESS, PermissionPolicy.INVENTORY_ADJUST,
            PermissionPolicy.EXPENSES_MANAGE, PermissionPolicy.BACKUP_MANAGE
        ).forEach { key ->
            try { PermissionPolicy.require(db, cashierId, key); fail("Cashier unexpectedly received $key") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
