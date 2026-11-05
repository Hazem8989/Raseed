package com.hazem.dailystore.data

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
    fun secondaryDeviceFailsClosedForAllAuthoritativeWritePermissions() {
        database.writableDatabase.execSQL(
            "INSERT INTO sync_protocol_state(state_key,state_value,updated_at) VALUES('local_device_role','SECONDARY','2026-11-03T00:00:00Z')"
        )
        val protectedPermissions = listOf(
            PermissionPolicy.USERS_MANAGE,
            PermissionPolicy.CATALOG_MANAGE,
            PermissionPolicy.EXCHANGE_RATE_MANAGE,
            PermissionPolicy.ACCOUNTS_MANAGE,
            PermissionPolicy.SUPPLIERS_MANAGE,
            PermissionPolicy.PURCHASES_MANAGE,
            PermissionPolicy.SUPPLIER_PAYMENTS_MANAGE,
            PermissionPolicy.SUPPLIER_PAYMENTS_CORRECT,
            PermissionPolicy.CUSTOMERS_CREATE,
            PermissionPolicy.CUSTOMERS_MANAGE,
            PermissionPolicy.DAILY_CASH_MANAGE,
            PermissionPolicy.SERVICE_TRANSFERS_MANAGE,
            PermissionPolicy.SERVICE_TRANSFERS_EXECUTE,
            PermissionPolicy.BACKUP_MANAGE,
            PermissionPolicy.INVENTORY_ADJUST,
            PermissionPolicy.RETURNS_PROCESS,
            PermissionPolicy.EXPENSES_MANAGE
        )
        protectedPermissions.forEach { permission ->
            try {
                PermissionPolicy.require(database.readableDatabase, adminId, permission)
                fail("Secondary must not authorize $permission")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("الجهاز ثانوي"))
            }
            try {
                PermissionPolicy.requireAssigned(database.readableDatabase, adminId, permission)
                fail("Secondary must not assign $permission")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("الجهاز الثانوي"))
            }
        }
        try {
            PermissionPolicy.require(database.readableDatabase, adminId, PermissionPolicy.SALES_CHECKOUT)
            fail("A secondary must not locally authorize the generic sales-posting permission")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("الجهاز ثانوي"))
        }
        // POS checkout and customer debt collection may be authorized on the secondary,
        // but their financial effects must be queued for primary-side application.
        // Ordinary require() remains primary-only for local financial posting paths.
        PermissionPolicy.requireAssigned(database.readableDatabase, adminId, PermissionPolicy.SALES_CHECKOUT)
        PermissionPolicy.requireAssigned(database.readableDatabase, adminId, PermissionPolicy.CUSTOMERS_COLLECT)
        PermissionPolicy.requireAssigned(database.readableDatabase, adminId, PermissionPolicy.CUSTOMERS_READ)
        PermissionPolicy.requireAssigned(database.readableDatabase, adminId, PermissionPolicy.SYNC_MANAGE)
        assertTrue(allowed(adminId, PermissionPolicy.SYNC_MANAGE))
    }

    @Test
    fun enrolledSecondaryRemainsRestrictedWhenRoleFlagIsMissing() {
        val localDeviceId = java.util.UUID.randomUUID().toString()
        database.writableDatabase.execSQL(
            """INSERT INTO sync_devices(
                device_id,display_name,device_role,status,created_at,last_seen_at
            ) VALUES(?,?,?,'ACTIVE','2026-11-06T00:00:00Z','2026-11-06T00:00:00Z')""",
            arrayOf(localDeviceId, "الثانوي", "SECONDARY")
        )
        database.writableDatabase.execSQL(
            """INSERT INTO sync_protocol_state(state_key,state_value,updated_at)
               VALUES('local_device_id',?,'2026-11-06T00:00:00Z')""",
            arrayOf(localDeviceId)
        )

        try {
            PermissionPolicy.require(
                database.readableDatabase, adminId, PermissionPolicy.PURCHASES_MANAGE
            )
            fail("An enrolled secondary must remain blocked without the role flag")
        } catch (_: IllegalArgumentException) { }
        try {
            PermissionPolicy.setRolePermission(
                database.writableDatabase, adminId, "CASHIER",
                PermissionPolicy.PURCHASES_MANAGE, true
            )
            fail("An enrolled secondary must not manage role permissions")
        } catch (_: IllegalArgumentException) { }
        PermissionPolicy.requireAssigned(
            database.readableDatabase, adminId, PermissionPolicy.SALES_CHECKOUT
        )
        PermissionPolicy.requireAssigned(
            database.readableDatabase, adminId, PermissionPolicy.CUSTOMERS_COLLECT
        )
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
        database.writableDatabase.delete("role_permissions", "permission_key=?", arrayOf(PermissionPolicy.SERVICE_TRANSFERS_EXECUTE))
        database.writableDatabase.delete("permissions", "permission_key=?", arrayOf(PermissionPolicy.SERVICE_TRANSFERS_EXECUTE))
        database.writableDatabase.version = 13
        database.close()
        val context = RuntimeEnvironment.getApplication()
        AppDatabase.resetForTests(context, deleteExisting = false)
        database = AppDatabase.getInstance(context)
        assertTrue(allowed(cashier, PermissionPolicy.SERVICE_TRANSFERS_EXECUTE))
        assertEquals(AppDatabase.DATABASE_VERSION, database.readableDatabase.version)
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
