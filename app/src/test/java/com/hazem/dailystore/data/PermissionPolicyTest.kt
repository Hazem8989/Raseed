package com.hazem.dailystore.data

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PermissionPolicyTest {
    private lateinit var database: AppDatabase
    private lateinit var auth: AuthRepository
    private lateinit var adminId: String
    private lateinit var cashierId: String

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        AppDatabase.resetForTests(context)
        database = AppDatabase.getInstance(context)
        auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        val admin = auth.authenticate("admin", "1234")!!
        auth.changePassword(admin.id, "1234", "TestAdmin123")
        adminId = admin.id
        cashierId = auth.createUser(
            "ADMIN", adminId, "cashier-permissions", "Cashier permissions",
            "Cashier12345", "CASHIER"
        )
        auth.changePassword(cashierId, "Cashier12345", "CashierStrong123")
    }

    @After
    fun tearDown() { database.close() }

    private fun allowed(userId: String, permission: String): Boolean = try {
        PermissionPolicy.require(database.readableDatabase, userId, permission)
        true
    } catch (_: IllegalArgumentException) { false }

    @Test
    fun cashierGetsOnlyExplicitSafeDefaults() {
        assertTrue(allowed(cashierId, PermissionPolicy.SALES_CHECKOUT))
        assertTrue(allowed(cashierId, PermissionPolicy.CUSTOMERS_READ))
        assertTrue(allowed(cashierId, PermissionPolicy.CUSTOMERS_CREATE))
        assertTrue(allowed(cashierId, PermissionPolicy.CUSTOMERS_COLLECT))
        assertTrue(allowed(cashierId, PermissionPolicy.SERVICE_TRANSFERS_EXECUTE))
        assertFalse(allowed(cashierId, PermissionPolicy.SERVICE_TRANSFERS_MANAGE))
        assertFalse(allowed(cashierId, PermissionPolicy.PURCHASES_MANAGE))
        assertFalse(allowed(cashierId, PermissionPolicy.CATALOG_MANAGE))
        assertFalse(allowed(cashierId, PermissionPolicy.ACCOUNTS_MANAGE))
        assertFalse(allowed(cashierId, PermissionPolicy.BACKUP_MANAGE))
        assertTrue(allowed(adminId, PermissionPolicy.BACKUP_MANAGE))
    }

    @Test
    fun managerCanGrantAndRevokeCashierPermissionWithAuditTrail() {
        PermissionPolicy.setRolePermission(
            database.writableDatabase, adminId, "CASHIER",
            PermissionPolicy.PURCHASES_MANAGE, true
        )
        assertTrue(allowed(cashierId, PermissionPolicy.PURCHASES_MANAGE))
        PermissionPolicy.setRolePermission(
            database.writableDatabase, adminId, "CASHIER",
            PermissionPolicy.PURCHASES_MANAGE, false
        )
        assertFalse(allowed(cashierId, PermissionPolicy.PURCHASES_MANAGE))
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM audit_log WHERE user_id=? AND action='ROLE_PERMISSION_CHANGE'",
            arrayOf(adminId)
        ).use { c -> assertTrue(c.moveToFirst()); assertTrue(c.getInt(0) >= 2) }
    }

    @Test
    fun passwordRotationAndInactiveUserAreAlwaysDenied() {
        database.writableDatabase.execSQL(
            "UPDATE users SET must_change_password=1 WHERE id=?", arrayOf(cashierId)
        )
        assertFalse(allowed(cashierId, PermissionPolicy.SALES_CHECKOUT))
        database.writableDatabase.execSQL(
            "UPDATE users SET must_change_password=0,is_active=0 WHERE id=?", arrayOf(cashierId)
        )
        assertFalse(allowed(cashierId, PermissionPolicy.SALES_CHECKOUT))
    }

    @Test
    fun managerCanDeactivateCashierButCannotDeactivateLastAdminOrSelf() {
        auth.setUserActive(adminId, cashierId, false)
        assertFalse(allowed(cashierId, PermissionPolicy.SALES_CHECKOUT))
        val history = database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM audit_log WHERE entity_type='USER' AND entity_id=? AND action='USER_DEACTIVATED'",
            arrayOf(cashierId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getInt(0) }
        assertEquals(1, history)
        try {
            auth.setUserActive(adminId, adminId, false)
            fail("Expected self-deactivation to be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun versionThirteenUpgradeSeedsServiceTransferExecutionPermission() {
        val cashier = auth.createUser(
            "ADMIN", adminId, "migration-cashier", "Migration cashier",
            "Cashier12345", "CASHIER"
        )
        auth.changePassword(cashier, "Cashier12345", "CashierStrong123")
        database.writableDatabase.version = 13
        database.close()
        val context = RuntimeEnvironment.getApplication()
        AppDatabase.resetForTests(context, deleteExisting = false)
        database = AppDatabase.getInstance(context)
        assertTrue(allowed(cashier, PermissionPolicy.SERVICE_TRANSFERS_EXECUTE))
        assertEquals(14, database.readableDatabase.version)
    }

    @Test
    fun administratorCannotRevokeAdministratorPermission() {
        try {
            PermissionPolicy.setRolePermission(
                database.writableDatabase, adminId, "ADMIN",
                PermissionPolicy.SALES_CHECKOUT, false
            )
            fail("Expected administrator permission lock")
        } catch (_: IllegalArgumentException) { }
    }
}
