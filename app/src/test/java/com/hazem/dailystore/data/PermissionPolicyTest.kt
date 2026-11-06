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
        adminId = auth.createInitialAdmin("admin", "مدير المتجر", "TestAdmin123")
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
        // A cashier on the secondary gets only the two business actions assigned by policy.
        PermissionPolicy.requireAssigned(database.readableDatabase, cashierId, PermissionPolicy.SALES_CHECKOUT)
        PermissionPolicy.requireAssigned(database.readableDatabase, cashierId, PermissionPolicy.CUSTOMERS_COLLECT)
        assertSecondaryDenied("cashier customer creation") {
            PermissionPolicy.requireAssigned(database.readableDatabase, cashierId, PermissionPolicy.CUSTOMERS_CREATE)
        }
        assertSecondaryDenied("cashier service transfer") {
            PermissionPolicy.requireAssigned(database.readableDatabase, cashierId, PermissionPolicy.SERVICE_TRANSFERS_EXECUTE)
        }
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

    private fun assertSecondaryDenied(label: String, action: () -> Unit) {
        var denied = false
        try {
            action()
        } catch (_: IllegalArgumentException) {
            denied = true
        }
        assertTrue("$label must be denied on a secondary device", denied)
    }

    private fun financialMutationCounts(): Map<String, Int> {
        val tables = listOf(
            "cash_accounts", "service_wallets", "service_wallet_account_links",
            "cash_transactions", "invoice_payments", "inventory_movements",
            "inventory_adjustments", "operating_expenses", "returns",
            "service_transfers", "service_wallet_transactions", "cash_account_transfers",
            "daily_supplier_rollovers", "sync_operation_envelopes", "sync_queue"
        )
        return tables.associateWith { table ->
            database.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                assertTrue(c.moveToFirst())
                c.getInt(0)
            }
        }
    }

    @Test
    fun everyFinancialRepositoryRejectsDirectSecondaryWritesBeforeMutation() {
        val context = RuntimeEnvironment.getApplication()
        database.writableDatabase.execSQL(
            "INSERT INTO sync_protocol_state(state_key,state_value,updated_at) VALUES('local_device_role','SECONDARY','2026-11-06T00:00:00Z')"
        )
        val before = financialMutationCounts()

        assertSecondaryDenied("purchase invoice posting") {
            InvoiceRepository(context).postPurchase(adminId, "missing-invoice", "secondary-purchase-post")
        }
        assertSecondaryDenied("supplier payment") {
            PurchasePaymentRepository(context).addPayment(
                adminId, "missing-invoice", "secondary-supplier-payment", "missing-account", 1L
            )
        }
        assertSecondaryDenied("supplier payment correction") {
            PurchasePaymentRepository(context).editPayment(
                "ADMIN", adminId, "missing-payment", "missing-account", 1L, null
            )
        }
        assertSecondaryDenied("sales return") {
            SalesReturnRepository(context).postSalesReturn(
                adminId, "missing-invoice", "secondary-sales-return", "2026-11-06",
                "test", listOf(SalesReturnItemInput("missing-item", java.math.BigDecimal.ONE)),
                emptyList()
            )
        }
        assertSecondaryDenied("purchase return") {
            PurchaseReturnRepository(context).postPurchaseReturn(
                adminId, "missing-invoice", "secondary-purchase-return", "2026-11-06",
                "test", listOf(PurchaseReturnItemInput("missing-item", java.math.BigDecimal.ONE)),
                emptyList()
            )
        }
        assertSecondaryDenied("expense category creation") {
            OperatingExpenseRepository(context).createCategory(adminId, "secondary-category")
        }
        assertSecondaryDenied("operating expense") {
            OperatingExpenseRepository(context).postExpense(
                adminId, "secondary-expense", "missing-category", "missing-account",
                1L, "2026-11-06"
            )
        }
        assertSecondaryDenied("inventory adjustment") {
            InventoryAdjustmentRepository(context).postAdjustment(
                adminId, "missing-product", java.math.BigDecimal.ONE, "test",
                "secondary-adjustment", 1L
            )
        }

        val daily = DailyBalanceRepository(context)
        assertSecondaryDenied("supplier rollover allocation") {
            daily.setSupplierRollover(adminId, "missing-account", 1L)
        }
        assertSecondaryDenied("cash-box transfer") {
            daily.transferCashBetweenBoxes(adminId, "source", "destination", 1L, "secondary-cash-transfer")
        }
        assertSecondaryDenied("cash-to-wallet transfer") {
            daily.transferCashToWallet(adminId, "source", "wallet", 1L, "secondary-wallet-funding")
        }
        assertSecondaryDenied("wallet-to-drawer transfer") {
            daily.transferWalletToDrawer(adminId, "wallet", "destination", 1L, "secondary-wallet-draw")
        }
        assertSecondaryDenied("personal withdrawal") {
            daily.addPersonalWithdrawal(adminId, "missing-account", 1L, "secondary-withdrawal")
        }
        assertSecondaryDenied("wallet opening balance") {
            daily.setWalletOpening(adminId, "missing-wallet", 1L)
        }

        val accounts = CashAccountRepository(context)
        assertSecondaryDenied("cash account creation") {
            accounts.create(adminId, "secondary-account", "CASH_BOX", "SYP", 0L)
        }
        assertSecondaryDenied("cash account editing") {
            accounts.updateDetails("ADMIN", adminId, "missing-account", "changed", "CASH_BOX", "SYP")
        }
        assertSecondaryDenied("daily primary drawer assignment") {
            accounts.setCurrentDayPrimaryDrawer("ADMIN", adminId, "missing-account")
        }

        val services = ServiceTransferRepository(context)
        services.ensureSystemAccounts()
        assertSecondaryDenied("service recharge-account configuration") {
            services.setRechargeAccount(adminId, "missing-account")
        }
        assertSecondaryDenied("service wallet recharge") {
            services.recharge(
                adminId, ServiceTransferRepository.SYRIATEL_SHARED, "missing-account",
                1L, "secondary-service-recharge"
            )
        }
        assertSecondaryDenied("customer service transfer") {
            services.executeTransfer(
                adminId, ServiceTransferRepository.SYRIATEL_BALANCE, 1L, 0L,
                "missing-account", "secondary-service-transfer"
            )
        }
        assertSecondaryDenied("service transfer reversal") {
            services.reverseTransfer(adminId, "missing-transfer", "secondary-service-reversal", "test")
        }

        assertSecondaryDenied("supplier creation") {
            SupplierRepository(context).create(adminId, "secondary-supplier")
        }
        assertSecondaryDenied("customer creation") {
            CustomerRepository(context).create(adminId, "secondary-customer")
        }
        assertSecondaryDenied("customer editing") {
            CustomerRepository(context).update(adminId, "missing-customer", "changed")
        }
        assertSecondaryDenied("employee administration") {
            auth.createUser("ADMIN", adminId, "secondary-user", "Secondary user", "SecondaryPass123", "CASHIER")
        }

        assertEquals("No financial ledger or sync command may be written by a rejected direct call",
            before, financialMutationCounts())
    }

    @Test
    fun directFinancialRepositoriesRemainDeniedWhenRoleFlagIsLost() {
        val context = RuntimeEnvironment.getApplication()
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
        // Deliberately omit local_device_role: repository-level gates must use the enrolled role.
        val sync = SyncProtocolRepository(context)
        assertTrue("The durable enrollment record must still identify this installation as secondary",
            sync.isConfiguredSecondary())
        val accounts = CashAccountRepository(context)
        val accountCountBefore = database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM cash_accounts", null
        ).use { c -> assertTrue(c.moveToFirst()); c.getInt(0) }
        val ledgerBefore = financialMutationCounts()
        accounts.ensureSystemCashAccounts()
        ServiceTransferRepository(context).ensureSystemAccounts()
        assertEquals("A secondary with a lost role flag must not seed independent cash accounts",
            accountCountBefore, database.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM cash_accounts", null
            ).use { c -> assertTrue(c.moveToFirst()); c.getInt(0) })
        assertSecondaryDenied("cash account creation after role-state loss") {
            accounts.create(adminId, "lost-role-account", "CASH_BOX", "SYP", 0L)
        }
        assertSecondaryDenied("customer creation after role-state loss") {
            CustomerRepository(context).create(adminId, "lost-role-customer")
        }
        assertSecondaryDenied("supplier creation after role-state loss") {
            SupplierRepository(context).create(adminId, "lost-role-supplier")
        }
        assertSecondaryDenied("service recharge after role-state loss") {
            ServiceTransferRepository(context).recharge(
                adminId, ServiceTransferRepository.SYRIATEL_SHARED, "account", 1L,
                "lost-role-service-recharge"
            )
        }
        assertSecondaryDenied("expense posting after role-state loss") {
            OperatingExpenseRepository(context).postExpense(
                adminId, "lost-role-expense", "category", "account", 1L, "2026-11-06"
            )
        }
        assertSecondaryDenied("supplier payment after role-state loss") {
            PurchasePaymentRepository(context).addPayment(
                adminId, "invoice", "lost-role-payment", "account", 1L
            )
        }
        assertSecondaryDenied("inventory adjustment after role-state loss") {
            InventoryAdjustmentRepository(context).postAdjustment(
                adminId, "product", java.math.BigDecimal.ONE, "test", "lost-role-adjustment", 1L
            )
        }
        assertSecondaryDenied("sales return after role-state loss") {
            SalesReturnRepository(context).postSalesReturn(
                adminId, "invoice", "lost-role-sales-return", "2026-11-06", "test",
                listOf(SalesReturnItemInput("item", java.math.BigDecimal.ONE)), emptyList()
            )
        }
        assertSecondaryDenied("purchase return after role-state loss") {
            PurchaseReturnRepository(context).postPurchaseReturn(
                adminId, "invoice", "lost-role-purchase-return", "2026-11-06", "test",
                listOf(PurchaseReturnItemInput("item", java.math.BigDecimal.ONE)), emptyList()
            )
        }
        assertSecondaryDenied("cash transfer after role-state loss") {
            DailyBalanceRepository(context).transferCashBetweenBoxes(
                adminId, "source", "destination", 1L, "lost-role-transfer"
            )
        }
        assertSecondaryDenied("service transfer after role-state loss") {
            ServiceTransferRepository(context).executeTransfer(
                adminId, ServiceTransferRepository.SYRIATEL_BALANCE, 1L, 0L,
                "account", "lost-role-service-transfer"
            )
        }
        assertSecondaryDenied("purchase posting after role-state loss") {
            InvoiceRepository(context).postPurchase(adminId, "invoice", "lost-role-purchase-post")
        }
        assertEquals("Rejected operations must not mutate financial ledgers or create sync commands",
            ledgerBefore, financialMutationCounts())
    }

}
