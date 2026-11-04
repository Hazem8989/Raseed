package com.hazem.dailystore.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local-first database for The Daily Shop.
 *
 * Money is stored as integer minor units (USD cents; SYP whole pounds).
 * USD product prices are the stable reference values. SYP display prices
 * must be calculated from the currently selected exchange rate and must
 * never overwrite the stored USD reference price.
 *
 * Exchange rates are stored as SYP per USD multiplied by 1,000.
 */
class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    /**
     * Flushes committed WAL frames and closes SQLite before a consistent file snapshot.
     * The helper instance remains reusable; the next database access reopens it.
     */
    @Synchronized
    internal fun checkpointAndCloseForBackup() {
        val db = writableDatabase
        db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            if (cursor.moveToFirst()) {
                check(cursor.getInt(0) == 0) {
                    "قاعدة البيانات مشغولة؛ تعذر تجهيز نسخة احتياطية آمنة"
                }
            }
        }
        close()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            SCHEMA.forEach(db::execSQL)
            PermissionPolicy.ensureDefaults(db)
            ensureFifoSchema(db)
            ensureSalesReturnSchema(db)
            ensureInventoryAdjustmentSchema(db)
            ensureSupplierReturnSchema(db)
            ensureOperatingExpenseSchema(db)
            ensureSyncProtocolSchema(db)
            ensureSyncDeviceIdentitySchema(db)
            ensureSyncActorMappingSchema(db)
            ensureSyncCashAccountMappingSchema(db)
            db.execSQL(
                """INSERT INTO app_settings(setting_key, setting_value, updated_at)
                   VALUES('base_currency', 'USD', strftime('%Y-%m-%dT%H:%M:%fZ','now'))"""
            )
            db.execSQL(
                """INSERT INTO app_settings(setting_key, setting_value, updated_at)
                   VALUES('store_currency', 'SYP', strftime('%Y-%m-%dT%H:%M:%fZ','now'))"""
            )
            db.execSQL(
                """INSERT INTO app_settings(setting_key, setting_value, updated_at)
                   VALUES('store_time_zone', 'Asia/Damascus', strftime('%Y-%m-%dT%H:%M:%fZ','now'))"""
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        if (version < 2) {
            db.beginTransaction()
            try {
                db.execSQL("ALTER TABLE sales_invoices ADD COLUMN sale_type TEXT NOT NULL DEFAULT 'CASH' CHECK(sale_type IN ('CASH','CREDIT'))")
                db.execSQL("CREATE INDEX idx_sales_customer_date ON sales_invoices(customer_id, invoice_date)")
                db.setTransactionSuccessful()
                version = 2
            } finally {
                db.endTransaction()
            }
        }
        if (version < 3) {
            db.beginTransaction()
            try {
                db.execSQL("""
                    CREATE TABLE daily_cash_sessions (
                        operating_date TEXT PRIMARY KEY NOT NULL,
                        primary_cash_account_id TEXT NOT NULL,
                        created_by TEXT,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY(primary_cash_account_id) REFERENCES cash_accounts(id)
                            ON UPDATE RESTRICT ON DELETE RESTRICT,
                        FOREIGN KEY(created_by) REFERENCES users(id)
                            ON UPDATE RESTRICT ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE customer_credit_revaluations (
                        id TEXT PRIMARY KEY NOT NULL,
                        invoice_id TEXT NOT NULL,
                        customer_id TEXT NOT NULL,
                        outstanding_usd_cents INTEGER NOT NULL CHECK(outstanding_usd_cents > 0),
                        previous_syp_minor INTEGER NOT NULL CHECK(previous_syp_minor >= 0),
                        new_syp_minor INTEGER NOT NULL CHECK(new_syp_minor >= 0),
                        applied_rate_syp_per_usd_milli INTEGER NOT NULL CHECK(applied_rate_syp_per_usd_milli > 0),
                        calculation_basis TEXT NOT NULL,
                        operation_id TEXT NOT NULL UNIQUE,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY(invoice_id) REFERENCES sales_invoices(id)
                            ON UPDATE RESTRICT ON DELETE RESTRICT,
                        FOREIGN KEY(customer_id) REFERENCES customers(id)
                            ON UPDATE RESTRICT ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX idx_daily_cash_session_account ON daily_cash_sessions(primary_cash_account_id)")
                db.execSQL("CREATE INDEX idx_customer_credit_revaluation_invoice ON customer_credit_revaluations(invoice_id, created_at)")
                db.setTransactionSuccessful()
                version = 3
            } finally {
                db.endTransaction()
            }
        }
        if (version < 4) {
            db.beginTransaction()
            try {
                db.execSQL("ALTER TABLE cash_transactions ADD COLUMN operating_date TEXT")
                db.execSQL("UPDATE cash_transactions SET operating_date=substr(created_at,1,10) WHERE operating_date IS NULL")
                db.execSQL("""
                    CREATE TABLE daily_supplier_rollovers (
                        operating_date TEXT PRIMARY KEY NOT NULL,
                        account_id TEXT NOT NULL,
                        allocated_minor INTEGER NOT NULL CHECK(allocated_minor >= 0),
                        note TEXT,
                        updated_by TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY(account_id) REFERENCES cash_accounts(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                        FOREIGN KEY(updated_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE wallet_daily_openings (
                        operating_date TEXT NOT NULL,
                        account_id TEXT NOT NULL,
                        opening_minor INTEGER NOT NULL CHECK(opening_minor >= 0),
                        note TEXT,
                        updated_by TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(operating_date, account_id),
                        FOREIGN KEY(account_id) REFERENCES cash_accounts(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                        FOREIGN KEY(updated_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE cash_account_transfers (
                        id TEXT PRIMARY KEY NOT NULL,
                        operation_id TEXT NOT NULL UNIQUE,
                        source_account_id TEXT NOT NULL,
                        destination_account_id TEXT NOT NULL,
                        amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
                        currency_code TEXT NOT NULL,
                        reference TEXT,
                        note TEXT,
                        payload_hash TEXT NOT NULL,
                        created_by TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY(source_account_id) REFERENCES cash_accounts(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                        FOREIGN KEY(destination_account_id) REFERENCES cash_accounts(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                        FOREIGN KEY(created_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX idx_cash_transactions_operating_date ON cash_transactions(operating_date, account_id)")
                db.execSQL("CREATE INDEX idx_cash_transfers_accounts ON cash_account_transfers(source_account_id, destination_account_id, created_at)")
                db.setTransactionSuccessful()
                version = 4
            } finally {
                db.endTransaction()
            }
        }
        if (version < 5) {
            db.beginTransaction()
            try {
                db.execSQL("""
                    CREATE TABLE service_wallets (
                        service_key TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        created_at TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE service_wallet_transactions (
                        id TEXT PRIMARY KEY NOT NULL,
                        service_key TEXT NOT NULL,
                        direction TEXT NOT NULL CHECK(direction IN ('IN','OUT')),
                        amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
                        transaction_type TEXT NOT NULL,
                        source_id TEXT,
                        operation_id TEXT NOT NULL UNIQUE,
                        payload_hash TEXT,
                        fee_minor INTEGER NOT NULL DEFAULT 0 CHECK(fee_minor >= 0),
                        note TEXT,
                        created_by TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY(service_key) REFERENCES service_wallets(service_key) ON UPDATE RESTRICT ON DELETE RESTRICT,
                        FOREIGN KEY(created_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE service_transfers (
                        id TEXT PRIMARY KEY NOT NULL,
                        operation_id TEXT NOT NULL UNIQUE,
                        service_type TEXT NOT NULL CHECK(service_type IN ('SYRIATEL_BALANCE','SYRIATEL_CASH','SHAM_CASH')),
                        service_key TEXT NOT NULL,
                        amount_delivered_minor INTEGER NOT NULL CHECK(amount_delivered_minor > 0),
                        profit_minor INTEGER NOT NULL CHECK(profit_minor >= 0),
                        provider_fee_minor INTEGER NOT NULL CHECK(provider_fee_minor >= 0),
                        customer_charge_minor INTEGER NOT NULL CHECK(customer_charge_minor > 0),
                        receipt_account_id TEXT NOT NULL,
                        receipt_channel TEXT NOT NULL CHECK(receipt_channel IN ('CASH','ELECTRONIC')),
                        reference TEXT,
                        note TEXT,
                        payload_hash TEXT NOT NULL,
                        created_by TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'POSTED' CHECK(status IN ('POSTED','REVERSED')),
                        reversed_at TEXT,
                        reversed_by TEXT,
                        reversal_reason TEXT,
                        FOREIGN KEY(service_key) REFERENCES service_wallets(service_key) ON UPDATE RESTRICT ON DELETE RESTRICT,
                        FOREIGN KEY(receipt_account_id) REFERENCES cash_accounts(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                        FOREIGN KEY(created_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                        FOREIGN KEY(reversed_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX idx_service_wallet_transactions_key_date ON service_wallet_transactions(service_key, created_at)")
                db.execSQL("CREATE INDEX idx_service_transfers_date ON service_transfers(created_at, service_type)")
                db.setTransactionSuccessful()
                version = 5
            } finally {
                db.endTransaction()
            }
        }
        if (version < 6) {
            db.beginTransaction()
            try {
                db.execSQL("""
                    CREATE TABLE service_wallet_account_links (
                        service_key TEXT PRIMARY KEY NOT NULL,
                        account_id TEXT NOT NULL UNIQUE,
                        linked_at TEXT NOT NULL,
                        FOREIGN KEY(service_key) REFERENCES service_wallets(service_key)
                            ON UPDATE RESTRICT ON DELETE RESTRICT,
                        FOREIGN KEY(account_id) REFERENCES cash_accounts(id)
                            ON UPDATE RESTRICT ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.setTransactionSuccessful()
                version = 6
            } finally {
                db.endTransaction()
            }
        }
        if (version < 7) {
            db.beginTransaction()
            try {
                db.execSQL("""
                    CREATE TABLE system_cash_account_roles (
                        role_key TEXT PRIMARY KEY NOT NULL,
                        account_id TEXT NOT NULL,
                        linked_at TEXT NOT NULL,
                        FOREIGN KEY(account_id) REFERENCES cash_accounts(id)
                            ON UPDATE RESTRICT ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.setTransactionSuccessful()
                version = 7
            } finally {
                db.endTransaction()
            }
        }
        if (version < 8) {
            db.beginTransaction()
            try {
                db.execSQL("""
                    ALTER TABLE users ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 1
                        CHECK(must_change_password IN (0,1))
                """.trimIndent())
                // Existing accounts must rotate their credentials after this security upgrade.
                db.execSQL("UPDATE users SET must_change_password=1")
                db.setTransactionSuccessful()
                version = 8
            } finally {
                db.endTransaction()
            }
        }
        if (version < 9) {
            db.beginTransaction()
            try {
                ensureFifoSchema(db)
                backfillFifoCostLayers(db)
                db.setTransactionSuccessful()
                version = 9
            } finally {
                db.endTransaction()
            }
        }
        if (version < 10) {
            db.beginTransaction()
            try {
                var hasPostedAt = false
                db.rawQuery("PRAGMA table_info(sales_invoices)", null).use { c ->
                    while (c.moveToNext()) if (c.getString(1) == "posted_at") hasPostedAt = true
                }
                if (!hasPostedAt) db.execSQL("ALTER TABLE sales_invoices ADD COLUMN posted_at TEXT")
                db.execSQL("""
                    UPDATE sales_invoices
                    SET posted_at = COALESCE(
                        (SELECT MIN(op.created_at) FROM operation_registry op
                         WHERE op.operation_type='POST_SALE_INVOICE'
                           AND op.result_reference=sales_invoices.id),
                        (SELECT MIN(m.created_at) FROM inventory_movements m
                         WHERE m.source_id=sales_invoices.id AND m.movement_type='SALE'),
                        updated_at
                    )
                    WHERE status IN ('POSTED','PARTIALLY_PAID','PAID') AND posted_at IS NULL
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_sales_posted_at ON sales_invoices(posted_at)")
                db.setTransactionSuccessful()
                version = 10
            } finally {
                db.endTransaction()
            }
        }
        if (version < 11) {
            db.beginTransaction()
            try {
                var hasOperatingDate = false
                db.rawQuery("PRAGMA table_info(sales_invoices)", null).use { c ->
                    while (c.moveToNext()) if (c.getString(1) == "posted_operating_date") hasOperatingDate = true
                }
                if (!hasOperatingDate) db.execSQL("ALTER TABLE sales_invoices ADD COLUMN posted_operating_date TEXT")
                db.execSQL("""
                    UPDATE sales_invoices
                    SET posted_operating_date = COALESCE(
                        strftime('%Y-%m-%d', posted_at, 'localtime'),
                        substr(posted_at,1,10)
                    )
                    WHERE status IN ('POSTED','PARTIALLY_PAID','PAID')
                      AND posted_operating_date IS NULL AND posted_at IS NOT NULL
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_sales_posted_operating_date ON sales_invoices(posted_operating_date)")
                db.setTransactionSuccessful()
                version = 11
            } finally {
                db.endTransaction()
            }
        }
        if (version < 12) {
            db.beginTransaction()
            try {
                PermissionPolicy.ensureDefaults(db)
                db.setTransactionSuccessful()
                version = 12
            } finally {
                db.endTransaction()
            }
        }
        if (version < 13) {
            db.beginTransaction()
            try {
                ensureSalesReturnSchema(db)
                db.setTransactionSuccessful()
                version = 13
            } finally {
                db.endTransaction()
            }
        }
        if (version < 14) {
            db.beginTransaction()
            try {
                PermissionPolicy.ensureDefaults(db)
                db.setTransactionSuccessful()
                version = 14
            } finally {
                db.endTransaction()
            }
        }
        if (version < 15) {
            db.beginTransaction()
            try {
                ensureInventoryAdjustmentSchema(db)
                db.setTransactionSuccessful()
                version = 15
            } finally {
                db.endTransaction()
            }
        }
        if (version < 16) {
            db.beginTransaction()
            try {
                ensureSupplierReturnSchema(db)
                db.setTransactionSuccessful()
                version = 16
            } finally {
                db.endTransaction()
            }
        }
        if (version < 17) {
            db.beginTransaction()
            try {
                ensureOperatingExpenseSchema(db)
                db.setTransactionSuccessful()
                version = 17
            } finally {
                db.endTransaction()
            }
        }
        if (version < 18) {
            db.beginTransaction()
            try {
                ensureSyncProtocolSchema(db)
                PermissionPolicy.ensureDefaults(db)
                db.setTransactionSuccessful()
                version = 18
            } finally {
                db.endTransaction()
            }
        }
        if (version < 19) {
            db.beginTransaction()
            try {
                ensureSyncActorMappingSchema(db)
                db.setTransactionSuccessful()
                version = 19
            } finally {
                db.endTransaction()
            }
        }
        if (version < 20) {
            db.beginTransaction()
            try {
                ensureSyncInboxOriginTimestampColumn(db)
                db.setTransactionSuccessful()
                version = 20
            } finally { db.endTransaction() }
        }
        if (version < 21) {
            db.beginTransaction()
            try {
                ensureSyncCashAccountMappingSchema(db)
                db.setTransactionSuccessful()
                version = 21
            } finally { db.endTransaction() }
        }
        if (version < 22) {
            db.beginTransaction()
            try {
                db.execSQL(
                    """INSERT OR IGNORE INTO app_settings(setting_key, setting_value, updated_at)
                       VALUES('store_time_zone', 'Asia/Damascus', strftime('%Y-%m-%dT%H:%M:%fZ','now'))"""
                )
                val missingCashDates = mutableListOf<Pair<String, String>>()
                db.rawQuery(
                    "SELECT id,created_at FROM cash_transactions WHERE operating_date IS NULL OR TRIM(operating_date)=''",
                    null
                ).use { c ->
                    while (c.moveToNext()) missingCashDates.add(c.getString(0) to c.getString(1))
                }
                missingCashDates.forEach { (id, createdAt) ->
                    db.update(
                        "cash_transactions",
                        android.content.ContentValues().apply {
                            put("operating_date", StoreTime.operatingDate(db, createdAt))
                        },
                        "id=?",
                        arrayOf(id)
                    )
                }
                db.setTransactionSuccessful()
                version = 22
            } finally { db.endTransaction() }
        }
        if (version < 23) {
            db.beginTransaction()
            try {
                fun ensureOperatingDateColumn(table: String) {
                    var exists = false
                    db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                        while (c.moveToNext()) if (c.getString(1) == "operating_date") exists = true
                    }
                    if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN operating_date TEXT")
                }
                ensureOperatingDateColumn("service_transfers")
                ensureOperatingDateColumn("service_wallet_transactions")

                val missingServiceTransferDates = mutableListOf<Pair<String, String>>()
                db.rawQuery(
                    "SELECT id,created_at FROM service_transfers WHERE operating_date IS NULL OR TRIM(operating_date)=''",
                    null
                ).use { c -> while (c.moveToNext()) missingServiceTransferDates.add(c.getString(0) to c.getString(1)) }
                missingServiceTransferDates.forEach { (id, createdAt) ->
                    db.update("service_transfers", android.content.ContentValues().apply {
                        put("operating_date", StoreTime.operatingDate(db, createdAt))
                    }, "id=?", arrayOf(id))
                }

                val missingServiceWalletDates = mutableListOf<Pair<String, String>>()
                db.rawQuery(
                    "SELECT id,created_at FROM service_wallet_transactions WHERE operating_date IS NULL OR TRIM(operating_date)=''",
                    null
                ).use { c -> while (c.moveToNext()) missingServiceWalletDates.add(c.getString(0) to c.getString(1)) }
                missingServiceWalletDates.forEach { (id, createdAt) ->
                    db.update("service_wallet_transactions", android.content.ContentValues().apply {
                        put("operating_date", StoreTime.operatingDate(db, createdAt))
                    }, "id=?", arrayOf(id))
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_service_transfers_operating_date ON service_transfers(operating_date,service_type,status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_service_wallet_transactions_operating_date ON service_wallet_transactions(operating_date,service_key,transaction_type)")
                db.setTransactionSuccessful()
                version = 23
            } finally { db.endTransaction() }
        }
        if (version < 24) {
            db.beginTransaction()
            try {
                ensureSyncDeviceIdentitySchema(db)
                db.setTransactionSuccessful()
                version = 24
            } finally { db.endTransaction() }
        }
        if (version != newVersion) {
            throw IllegalStateException("Missing database migration from version $version to $newVersion")
        }
    }

    private fun ensureSyncCashAccountMappingSchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_cash_account_mappings (
                device_id TEXT NOT NULL,
                remote_cash_account_id TEXT NOT NULL,
                primary_cash_account_id TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
                mapped_by TEXT NOT NULL,
                mapped_at TEXT NOT NULL,
                PRIMARY KEY(device_id, remote_cash_account_id),
                FOREIGN KEY(device_id) REFERENCES sync_devices(device_id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(primary_cash_account_id) REFERENCES cash_accounts(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(mapped_by) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_cash_account_mappings_primary ON sync_cash_account_mappings(primary_cash_account_id,is_active)")
    }

    private fun ensureSyncInboxOriginTimestampColumn(db: SQLiteDatabase) {
        var exists = false
        db.rawQuery("PRAGMA table_info(sync_inbox)", null).use { c ->
            while (c.moveToNext()) if (c.getString(1) == "origin_created_at") exists = true
        }
        if (!exists) db.execSQL("ALTER TABLE sync_inbox ADD COLUMN origin_created_at TEXT NOT NULL DEFAULT ''")
    }

    /**
     * Stores pinned public identities for enrolled peers. Private signing keys
     * remain exclusively in Android Keystore and are never persisted here.
     */
    private fun ensureSyncDeviceIdentitySchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_device_public_keys (
                device_id TEXT PRIMARY KEY NOT NULL,
                public_key_base64 TEXT NOT NULL,
                public_key_fingerprint TEXT NOT NULL,
                algorithm TEXT NOT NULL CHECK(algorithm='EC_P256_SHA256'),
                status TEXT NOT NULL CHECK(status IN ('ACTIVE','REVOKED')),
                enrolled_at TEXT NOT NULL,
                revoked_at TEXT,
                FOREIGN KEY(device_id) REFERENCES sync_devices(device_id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_device_public_keys_status ON sync_device_public_keys(status,device_id)")
    }

    private fun ensureSyncActorMappingSchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_actor_mappings (
                device_id TEXT NOT NULL,
                remote_actor_user_id TEXT NOT NULL,
                primary_user_id TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
                mapped_by TEXT NOT NULL,
                mapped_at TEXT NOT NULL,
                PRIMARY KEY(device_id, remote_actor_user_id),
                FOREIGN KEY(device_id) REFERENCES sync_devices(device_id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(primary_user_id) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(mapped_by) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_actor_mappings_primary_user ON sync_actor_mappings(primary_user_id,is_active)")
    }

    private fun ensureSalesReturnSchema(db: SQLiteDatabase) {
        fun ensureColumn(table: String, column: String, definition: String) {
            var exists = false
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                while (c.moveToNext()) if (c.getString(1) == column) exists = true
            }
            if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
        ensureColumn("returns", "operation_id", "TEXT")
        ensureColumn("returns", "posted_at", "TEXT")
        ensureColumn("returns", "cash_refund_usd_cents", "INTEGER NOT NULL DEFAULT 0 CHECK(cash_refund_usd_cents >= 0)")
        ensureColumn("returns", "credit_reduction_usd_cents", "INTEGER NOT NULL DEFAULT 0 CHECK(credit_reduction_usd_cents >= 0)")
        ensureColumn("returns", "returned_cogs_usd_cents", "INTEGER CHECK(returned_cogs_usd_cents IS NULL OR returned_cogs_usd_cents >= 0)")
        ensureColumn("returns", "exchange_rate_syp_per_usd_milli", "INTEGER")
        ensureColumn("return_items", "original_sales_item_id", "TEXT")
        ensureColumn("return_items", "returned_cogs_usd_cents", "INTEGER CHECK(returned_cogs_usd_cents IS NULL OR returned_cogs_usd_cents >= 0)")
        ensureColumn("return_refund_allocations", "operation_id", "TEXT")
        ensureColumn("return_refund_allocations", "cash_transaction_id", "TEXT")
        ensureColumn("return_refund_allocations", "refund_equivalent_syp_minor", "INTEGER NOT NULL DEFAULT 0 CHECK(refund_equivalent_syp_minor >= 0)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_returns_operation_id ON returns(operation_id) WHERE operation_id IS NOT NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_returns_original_invoice ON returns(original_invoice_type,original_invoice_id,status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_return_items_original_sales_item ON return_items(original_sales_item_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_return_refund_operation_id ON return_refund_allocations(operation_id) WHERE operation_id IS NOT NULL")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_return_refund_cash_transaction ON return_refund_allocations(cash_transaction_id) WHERE cash_transaction_id IS NOT NULL")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS return_cost_allocations (
                id TEXT PRIMARY KEY NOT NULL,
                return_item_id TEXT NOT NULL,
                source_sale_allocation_id TEXT NOT NULL,
                inventory_movement_id TEXT,
                quantity REAL NOT NULL CHECK(quantity > 0),
                unit_cost_usd_cents INTEGER CHECK(unit_cost_usd_cents IS NULL OR unit_cost_usd_cents > 0),
                cost_usd_cents INTEGER CHECK(cost_usd_cents IS NULL OR cost_usd_cents >= 0),
                created_at TEXT NOT NULL,
                FOREIGN KEY(return_item_id) REFERENCES return_items(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(source_sale_allocation_id) REFERENCES sales_item_cost_allocations(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(inventory_movement_id) REFERENCES inventory_movements(id) ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_return_cost_source ON return_cost_allocations(source_sale_allocation_id)")
    }

    private fun ensureInventoryAdjustmentSchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS inventory_adjustments (
                id TEXT PRIMARY KEY NOT NULL,
                operation_id TEXT NOT NULL UNIQUE,
                product_id TEXT NOT NULL,
                warehouse_id TEXT NOT NULL,
                inventory_movement_id TEXT NOT NULL UNIQUE,
                adjustment_type TEXT NOT NULL CHECK(adjustment_type IN ('INCREASE','DECREASE')),
                quantity REAL NOT NULL CHECK(quantity > 0),
                quantity_before REAL NOT NULL CHECK(quantity_before >= 0),
                quantity_after REAL NOT NULL CHECK(quantity_after >= 0),
                unit_cost_usd_cents INTEGER CHECK(unit_cost_usd_cents IS NULL OR unit_cost_usd_cents > 0),
                cost_usd_cents INTEGER CHECK(cost_usd_cents IS NULL OR cost_usd_cents >= 0),
                reason TEXT NOT NULL,
                created_by TEXT NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY(product_id) REFERENCES products(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(warehouse_id) REFERENCES warehouses(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(inventory_movement_id) REFERENCES inventory_movements(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(created_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_inventory_adjustments_product_date ON inventory_adjustments(product_id,created_at)")
    }

    /**
     * Supplier returns share the immutable return header/line ledger, but use
     * their own source-item link and inbound cash-refund allocations. This
     * keeps customer refund semantics and supplier credits separate.
     */
    private fun ensureSyncProtocolSchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_devices (
                device_id TEXT PRIMARY KEY NOT NULL,
                display_name TEXT NOT NULL,
                device_role TEXT NOT NULL CHECK(device_role IN ('PRIMARY','SECONDARY')),
                credential_hash TEXT,
                status TEXT NOT NULL CHECK(status IN ('ACTIVE','REVOKED')),
                enrolled_by TEXT,
                created_at TEXT NOT NULL,
                last_seen_at TEXT,
                revoked_at TEXT,
                FOREIGN KEY(enrolled_by) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_pairing_invitations (
                invitation_id TEXT PRIMARY KEY NOT NULL,
                invitation_hash TEXT NOT NULL UNIQUE,
                created_by TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                consumed_at TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY(created_by) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_protocol_state (
                state_key TEXT PRIMARY KEY NOT NULL,
                state_value TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_operation_envelopes (
                operation_id TEXT PRIMARY KEY NOT NULL,
                sequence_number INTEGER NOT NULL UNIQUE CHECK(sequence_number > 0),
                origin_device_id TEXT NOT NULL,
                actor_user_id TEXT NOT NULL,
                operation_type TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                payload_sha256 TEXT NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY(origin_device_id) REFERENCES sync_devices(device_id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(actor_user_id) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_inbox (
                operation_id TEXT PRIMARY KEY NOT NULL,
                origin_device_id TEXT NOT NULL,
                sequence_number INTEGER NOT NULL CHECK(sequence_number > 0),
                operation_type TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                payload_sha256 TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('RECEIVED','APPLIED','REJECTED','CONFLICT')),
                received_at TEXT NOT NULL,
                applied_at TEXT,
                rejection_reason TEXT,
                FOREIGN KEY(origin_device_id) REFERENCES sync_devices(device_id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_acknowledgements (
                operation_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                sequence_number INTEGER NOT NULL CHECK(sequence_number > 0),
                status TEXT NOT NULL CHECK(status IN ('RECEIVED','APPLIED','REJECTED')),
                acknowledged_at TEXT NOT NULL,
                PRIMARY KEY(operation_id, device_id),
                FOREIGN KEY(operation_id) REFERENCES sync_operation_envelopes(operation_id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(device_id) REFERENCES sync_devices(device_id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_cursors (
                device_id TEXT PRIMARY KEY NOT NULL,
                last_sequence INTEGER NOT NULL DEFAULT 0 CHECK(last_sequence >= 0),
                updated_at TEXT NOT NULL,
                FOREIGN KEY(device_id) REFERENCES sync_devices(device_id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_devices_status ON sync_devices(status, device_role)")
        ensureSyncInboxOriginTimestampColumn(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_inbox_status_sequence ON sync_inbox(status, sequence_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_envelopes_sequence ON sync_operation_envelopes(sequence_number)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_invitations_expiry ON sync_pairing_invitations(expires_at, consumed_at)")
    }

    private fun ensureOperatingExpenseSchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS expense_categories (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
                created_by TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY(created_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS operating_expenses (
                id TEXT PRIMARY KEY NOT NULL,
                category_id TEXT NOT NULL,
                cash_account_id TEXT NOT NULL,
                cash_transaction_id TEXT NOT NULL UNIQUE,
                amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
                currency_code TEXT NOT NULL CHECK(currency_code IN ('USD','SYP')),
                exchange_rate_syp_per_usd_milli INTEGER CHECK(
                    exchange_rate_syp_per_usd_milli IS NULL OR exchange_rate_syp_per_usd_milli > 0
                ),
                amount_usd_cents INTEGER NOT NULL CHECK(amount_usd_cents >= 0),
                expense_date TEXT NOT NULL,
                reference TEXT,
                notes TEXT,
                created_by TEXT NOT NULL,
                operation_id TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL,
                FOREIGN KEY(category_id) REFERENCES expense_categories(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(cash_account_id) REFERENCES cash_accounts(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(cash_transaction_id) REFERENCES cash_transactions(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(created_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_operating_expenses_date ON operating_expenses(expense_date,id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_operating_expenses_category ON operating_expenses(category_id,expense_date)")
        val defaults = listOf(
            "expense-rent" to "إيجار",
            "expense-salaries" to "رواتب وأجور",
            "expense-transport" to "نقل ومواصلات",
            "expense-maintenance" to "صيانة",
            "expense-communications" to "اتصالات وإنترنت",
            "expense-energy" to "وقود وطاقة",
            "expense-supplies" to "مواد تشغيلية",
            "expense-fees" to "رسوم وعمولات",
            "expense-other" to "أخرى"
        )
        defaults.forEach { (id,name) ->
            db.execSQL(
                "INSERT OR IGNORE INTO expense_categories(id,name,is_active,created_at) VALUES(?,?,1,strftime('%Y-%m-%dT%H:%M:%fZ','now'))",
                arrayOf(id,name)
            )
        }
    }

    private fun ensureSupplierReturnSchema(db: SQLiteDatabase) {
        fun ensureColumn(table: String, column: String, definition: String) {
            var exists = false
            db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                while (c.moveToNext()) if (c.getString(1) == column) exists = true
            }
            if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
        ensureColumn("return_items", "original_purchase_item_id", "TEXT")
        ensureColumn("returns", "supplier_credit_reduction_syp_minor",
            "INTEGER NOT NULL DEFAULT 0 CHECK(supplier_credit_reduction_syp_minor >= 0)")
        ensureColumn("returns", "supplier_cash_refund_syp_minor",
            "INTEGER NOT NULL DEFAULT 0 CHECK(supplier_cash_refund_syp_minor >= 0)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_return_items_original_purchase_item ON return_items(original_purchase_item_id)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS supplier_return_refund_allocations (
                id TEXT PRIMARY KEY NOT NULL,
                return_id TEXT NOT NULL,
                cash_account_id TEXT NOT NULL,
                cash_transaction_id TEXT NOT NULL UNIQUE,
                operation_id TEXT NOT NULL UNIQUE,
                amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
                currency_code TEXT NOT NULL CHECK(currency_code IN ('USD','SYP')),
                exchange_rate_syp_per_usd_milli INTEGER NOT NULL CHECK(exchange_rate_syp_per_usd_milli > 0),
                refund_equivalent_syp_minor INTEGER NOT NULL CHECK(refund_equivalent_syp_minor > 0),
                created_at TEXT NOT NULL,
                FOREIGN KEY(return_id) REFERENCES returns(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(cash_account_id) REFERENCES cash_accounts(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(cash_transaction_id) REFERENCES cash_transactions(id) ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_supplier_return_refunds_return ON supplier_return_refund_allocations(return_id)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS supplier_return_cost_allocations (
                id TEXT PRIMARY KEY NOT NULL,
                return_item_id TEXT NOT NULL,
                source_cost_layer_id TEXT NOT NULL,
                inventory_movement_id TEXT NOT NULL UNIQUE,
                quantity REAL NOT NULL CHECK(quantity > 0),
                unit_cost_usd_cents INTEGER CHECK(unit_cost_usd_cents IS NULL OR unit_cost_usd_cents > 0),
                cost_usd_cents INTEGER CHECK(cost_usd_cents IS NULL OR cost_usd_cents >= 0),
                created_at TEXT NOT NULL,
                FOREIGN KEY(return_item_id) REFERENCES return_items(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(source_cost_layer_id) REFERENCES inventory_cost_layers(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(inventory_movement_id) REFERENCES inventory_movements(id) ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_supplier_return_cost_source ON supplier_return_cost_allocations(source_cost_layer_id)")
    }

    private fun ensureFifoSchema(db: SQLiteDatabase) {
        var hasCogsColumn = false
        db.rawQuery("PRAGMA table_info(sales_items)", null).use { c ->
            while (c.moveToNext()) if (c.getString(1) == "cogs_usd_cents") hasCogsColumn = true
        }
        if (!hasCogsColumn) {
            db.execSQL("ALTER TABLE sales_items ADD COLUMN cogs_usd_cents INTEGER CHECK(cogs_usd_cents IS NULL OR cogs_usd_cents >= 0)")
        }
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS inventory_cost_layers (
                id TEXT PRIMARY KEY NOT NULL,
                product_id TEXT NOT NULL,
                warehouse_id TEXT NOT NULL,
                source_movement_id TEXT NOT NULL UNIQUE,
                source_type TEXT,
                source_id TEXT,
                source_item_id TEXT,
                received_quantity REAL NOT NULL CHECK(received_quantity > 0),
                remaining_quantity REAL NOT NULL CHECK(remaining_quantity >= 0 AND remaining_quantity <= received_quantity),
                unit_cost_usd_cents INTEGER CHECK(unit_cost_usd_cents IS NULL OR unit_cost_usd_cents > 0),
                purchase_exchange_rate_syp_per_usd_milli INTEGER,
                received_at TEXT NOT NULL,
                FOREIGN KEY(product_id) REFERENCES products(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(warehouse_id) REFERENCES warehouses(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(source_movement_id) REFERENCES inventory_movements(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(source_item_id) REFERENCES purchase_items(id) ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sales_item_cost_allocations (
                id TEXT PRIMARY KEY NOT NULL,
                sales_invoice_id TEXT NOT NULL,
                sales_item_id TEXT,
                cost_layer_id TEXT NOT NULL,
                quantity REAL NOT NULL CHECK(quantity > 0),
                unit_cost_usd_cents INTEGER CHECK(unit_cost_usd_cents IS NULL OR unit_cost_usd_cents > 0),
                cost_usd_cents INTEGER CHECK(cost_usd_cents IS NULL OR cost_usd_cents >= 0),
                created_at TEXT NOT NULL,
                FOREIGN KEY(sales_invoice_id) REFERENCES sales_invoices(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(sales_item_id) REFERENCES sales_items(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(cost_layer_id) REFERENCES inventory_cost_layers(id) ON UPDATE RESTRICT ON DELETE RESTRICT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fifo_layers_available ON inventory_cost_layers(product_id,warehouse_id,received_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fifo_allocations_invoice ON sales_item_cost_allocations(sales_invoice_id,sales_item_id)")
    }

    private fun backfillFifoCostLayers(db: SQLiteDatabase) {
        data class Movement(
            val id: String, val productId: String, val warehouseId: String,
            val type: String, val delta: Double, val unitCost: Long,
            val sourceType: String?, val sourceId: String?,
            val operationId: String?, val createdAt: String
        )
        val movements = mutableListOf<Movement>()
        db.rawQuery(
            """SELECT id,product_id,warehouse_id,movement_type,quantity_delta,
                      unit_cost_usd_cents,source_type,source_id,operation_id,created_at
               FROM inventory_movements ORDER BY created_at ASC,rowid ASC""".trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) movements.add(
                Movement(c.getString(0),c.getString(1),c.getString(2),c.getString(3),
                    c.getDouble(4),c.getLong(5),if(c.isNull(6)) null else c.getString(6),
                    if(c.isNull(7)) null else c.getString(7),
                    if(c.isNull(8)) null else c.getString(8),c.getString(9))
            )
        }
        movements.forEach { movement ->
            if (movement.delta > 0.0) {
                FifoCosting.addLayer(db,movement.id,movement.productId,movement.warehouseId,
                    movement.delta,movement.unitCost,movement.sourceType,movement.sourceId,movement.createdAt,
                    movement.operationId?.substringAfterLast(':')?.takeIf { movement.sourceType == "PURCHASE_INVOICE" })
            } else {
                val salesItemId = if (movement.type == "SALE") {
                    movement.operationId?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
                } else null
                FifoCosting.consumeForSale(
                    db,movement.sourceId ?: if (movement.type == "SALE") {
                        error("حركة بيع سالبة بلا مرجع فاتورة: ${movement.id}")
                    } else "LEGACY_MOVEMENT:${movement.id}",
                    salesItemId,movement.productId,movement.warehouseId,-movement.delta,movement.createdAt,
                    recordSaleAllocation = movement.type == "SALE"
                )
            }
        }
    }

    companion object {
        const val DATABASE_NAME = "the_daily_shop.db"
        const val DATABASE_VERSION = 24

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Starts each Robolectric repository test with a clean, isolated database.
         * Production code must use getInstance(); this helper is only called by tests.
         */
        @Synchronized
        internal fun resetForTests(context: Context, deleteExisting: Boolean = true) {
            INSTANCE?.close()
            INSTANCE = null
            if (deleteExisting) context.deleteDatabase(DATABASE_NAME)
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppDatabase(context.applicationContext)
                    .also { INSTANCE = it }
            }

        private val SCHEMA = arrayOf(
            """
            CREATE TABLE app_settings (
                setting_key TEXT PRIMARY KEY NOT NULL,
                setting_value TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),

            """
            CREATE TABLE users (
                id TEXT PRIMARY KEY NOT NULL,
                username TEXT NOT NULL COLLATE NOCASE UNIQUE,
                display_name TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                password_salt TEXT NOT NULL,
                role_key TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
                must_change_password INTEGER NOT NULL DEFAULT 1 CHECK(must_change_password IN (0,1)),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                last_login_at TEXT
            )
            """.trimIndent(),

            """
            CREATE TABLE permissions (
                permission_key TEXT PRIMARY KEY NOT NULL,
                description TEXT NOT NULL
            )
            """.trimIndent(),

            """
            CREATE TABLE role_permissions (
                role_key TEXT NOT NULL,
                permission_key TEXT NOT NULL,
                allowed INTEGER NOT NULL CHECK(allowed IN (0,1)),
                PRIMARY KEY(role_key, permission_key),
                FOREIGN KEY(permission_key) REFERENCES permissions(permission_key)
                    ON UPDATE CASCADE ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                parent_id INTEGER,
                is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
                created_at TEXT NOT NULL,
                FOREIGN KEY(parent_id) REFERENCES categories(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE units (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                symbol TEXT NOT NULL,
                decimal_places INTEGER NOT NULL DEFAULT 0
                    CHECK(decimal_places BETWEEN 0 AND 3)
            )
            """.trimIndent(),

            """
            CREATE TABLE products (
                id TEXT PRIMARY KEY NOT NULL,
                barcode TEXT UNIQUE,
                sku TEXT UNIQUE,
                name TEXT NOT NULL COLLATE NOCASE,
                category_id INTEGER,
                unit_id INTEGER NOT NULL,
                purchase_cost_usd_cents INTEGER NOT NULL DEFAULT 0
                    CHECK(purchase_cost_usd_cents >= 0),
                sale_price_usd_cents INTEGER NOT NULL DEFAULT 0
                    CHECK(sale_price_usd_cents >= 0),
                track_inventory INTEGER NOT NULL DEFAULT 1
                    CHECK(track_inventory IN (0,1)),
                minimum_stock REAL NOT NULL DEFAULT 0 CHECK(minimum_stock >= 0),
                is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY(category_id) REFERENCES categories(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(unit_id) REFERENCES units(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE warehouses (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                is_default INTEGER NOT NULL DEFAULT 0 CHECK(is_default IN (0,1)),
                is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
                created_at TEXT NOT NULL
            )
            """.trimIndent(),

            """
            CREATE TABLE inventory_balances (
                product_id TEXT NOT NULL,
                warehouse_id TEXT NOT NULL,
                quantity REAL NOT NULL DEFAULT 0,
                updated_at TEXT NOT NULL,
                PRIMARY KEY(product_id, warehouse_id),
                FOREIGN KEY(product_id) REFERENCES products(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(warehouse_id) REFERENCES warehouses(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE inventory_movements (
                id TEXT PRIMARY KEY NOT NULL,
                product_id TEXT NOT NULL,
                warehouse_id TEXT NOT NULL,
                movement_type TEXT NOT NULL CHECK(movement_type IN
                    ('OPENING','PURCHASE','SALE','SALE_RETURN','PURCHASE_RETURN',
                     'ADJUSTMENT_IN','ADJUSTMENT_OUT','TRANSFER_IN','TRANSFER_OUT')),
                quantity_delta REAL NOT NULL CHECK(quantity_delta <> 0),
                unit_cost_usd_cents INTEGER NOT NULL DEFAULT 0
                    CHECK(unit_cost_usd_cents >= 0),
                source_type TEXT,
                source_id TEXT,
                operation_id TEXT UNIQUE,
                note TEXT,
                created_by TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY(product_id) REFERENCES products(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(warehouse_id) REFERENCES warehouses(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(created_by) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE exchange_rates (
                id TEXT PRIMARY KEY NOT NULL,
                currency_code TEXT NOT NULL,
                rate_syp_per_usd_milli INTEGER NOT NULL
                    CHECK(rate_syp_per_usd_milli > 0),
                effective_at TEXT NOT NULL,
                created_by TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY(created_by) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE customers (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                phone TEXT,
                address TEXT,
                notes TEXT,
                opening_balance_minor INTEGER NOT NULL DEFAULT 0,
                opening_balance_currency TEXT NOT NULL DEFAULT 'SYP',
                is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),

            """
            CREATE TABLE suppliers (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                phone TEXT,
                address TEXT,
                notes TEXT,
                opening_balance_minor INTEGER NOT NULL DEFAULT 0,
                opening_balance_currency TEXT NOT NULL DEFAULT 'SYP',
                is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),

            """
            CREATE TABLE purchase_invoices (
                id TEXT PRIMARY KEY NOT NULL,
                invoice_number TEXT NOT NULL UNIQUE,
                supplier_id TEXT,
                warehouse_id TEXT NOT NULL,
                invoice_date TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN
                    ('DRAFT','POSTED','PARTIALLY_PAID','PAID','CANCELLED')),
                subtotal_usd_cents INTEGER NOT NULL DEFAULT 0,
                discount_usd_cents INTEGER NOT NULL DEFAULT 0,
                total_usd_cents INTEGER NOT NULL DEFAULT 0,
                exchange_rate_syp_per_usd_milli INTEGER NOT NULL DEFAULT 0,
                notes TEXT,
                created_by TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY(supplier_id) REFERENCES suppliers(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(warehouse_id) REFERENCES warehouses(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(created_by) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                CHECK(subtotal_usd_cents >= 0 AND discount_usd_cents >= 0
                    AND total_usd_cents >= 0)
            )
            """.trimIndent(),

            """
            CREATE TABLE purchase_items (
                id TEXT PRIMARY KEY NOT NULL,
                purchase_invoice_id TEXT NOT NULL,
                product_id TEXT NOT NULL,
                quantity REAL NOT NULL CHECK(quantity > 0),
                unit_cost_usd_cents INTEGER NOT NULL CHECK(unit_cost_usd_cents >= 0),
                line_total_usd_cents INTEGER NOT NULL CHECK(line_total_usd_cents >= 0),
                FOREIGN KEY(purchase_invoice_id) REFERENCES purchase_invoices(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(product_id) REFERENCES products(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE sales_invoices (
                id TEXT PRIMARY KEY NOT NULL,
                invoice_number TEXT NOT NULL UNIQUE,
                customer_id TEXT,
                sale_type TEXT NOT NULL DEFAULT 'CASH' CHECK(sale_type IN ('CASH','CREDIT')),
                warehouse_id TEXT NOT NULL,
                invoice_date TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN
                    ('DRAFT','POSTED','PARTIALLY_PAID','PAID','CANCELLED')),
                subtotal_usd_cents INTEGER NOT NULL DEFAULT 0,
                discount_usd_cents INTEGER NOT NULL DEFAULT 0,
                total_usd_cents INTEGER NOT NULL DEFAULT 0,
                exchange_rate_syp_per_usd_milli INTEGER NOT NULL DEFAULT 0,
                notes TEXT,
                created_by TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                posted_at TEXT,
                posted_operating_date TEXT,
                FOREIGN KEY(customer_id) REFERENCES customers(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(warehouse_id) REFERENCES warehouses(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(created_by) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                CHECK(subtotal_usd_cents >= 0 AND discount_usd_cents >= 0
                    AND total_usd_cents >= 0)
            )
            """.trimIndent(),

            """
            CREATE TABLE sales_items (
                id TEXT PRIMARY KEY NOT NULL,
                sales_invoice_id TEXT NOT NULL,
                product_id TEXT NOT NULL,
                quantity REAL NOT NULL CHECK(quantity > 0),
                unit_price_usd_cents INTEGER NOT NULL CHECK(unit_price_usd_cents >= 0),
                unit_cost_usd_cents INTEGER NOT NULL DEFAULT 0
                    CHECK(unit_cost_usd_cents >= 0),
                line_total_usd_cents INTEGER NOT NULL CHECK(line_total_usd_cents >= 0),
                FOREIGN KEY(sales_invoice_id) REFERENCES sales_invoices(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(product_id) REFERENCES products(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE cash_accounts (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                account_type TEXT NOT NULL CHECK(account_type IN
                    ('CASH_BOX','WALLET','BANK','OTHER')),
                currency_code TEXT NOT NULL,
                opening_balance_minor INTEGER NOT NULL DEFAULT 0,
                is_active INTEGER NOT NULL DEFAULT 1 CHECK(is_active IN (0,1)),
                created_at TEXT NOT NULL
            )
            """.trimIndent(),

            """
            CREATE TABLE system_cash_account_roles (
                role_key TEXT PRIMARY KEY NOT NULL,
                account_id TEXT NOT NULL,
                linked_at TEXT NOT NULL,
                FOREIGN KEY(account_id) REFERENCES cash_accounts(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE daily_cash_sessions (
                operating_date TEXT PRIMARY KEY NOT NULL,
                primary_cash_account_id TEXT NOT NULL,
                created_by TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY(primary_cash_account_id) REFERENCES cash_accounts(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(created_by) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE daily_supplier_rollovers (
                operating_date TEXT PRIMARY KEY NOT NULL,
                account_id TEXT NOT NULL,
                allocated_minor INTEGER NOT NULL CHECK(allocated_minor >= 0),
                note TEXT,
                updated_by TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY(account_id) REFERENCES cash_accounts(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(updated_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE wallet_daily_openings (
                operating_date TEXT NOT NULL,
                account_id TEXT NOT NULL,
                opening_minor INTEGER NOT NULL CHECK(opening_minor >= 0),
                note TEXT,
                updated_by TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                PRIMARY KEY(operating_date, account_id),
                FOREIGN KEY(account_id) REFERENCES cash_accounts(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(updated_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE cash_account_transfers (
                id TEXT PRIMARY KEY NOT NULL,
                operation_id TEXT NOT NULL UNIQUE,
                source_account_id TEXT NOT NULL,
                destination_account_id TEXT NOT NULL,
                amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
                currency_code TEXT NOT NULL,
                reference TEXT,
                note TEXT,
                payload_hash TEXT NOT NULL,
                created_by TEXT NOT NULL,
                created_at TEXT NOT NULL,
                FOREIGN KEY(source_account_id) REFERENCES cash_accounts(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(destination_account_id) REFERENCES cash_accounts(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(created_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE service_wallets (
                service_key TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
            """.trimIndent(),

            """
            CREATE TABLE service_wallet_account_links (
                service_key TEXT PRIMARY KEY NOT NULL,
                account_id TEXT NOT NULL UNIQUE,
                linked_at TEXT NOT NULL,
                FOREIGN KEY(service_key) REFERENCES service_wallets(service_key)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(account_id) REFERENCES cash_accounts(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE service_wallet_transactions (
                id TEXT PRIMARY KEY NOT NULL,
                service_key TEXT NOT NULL,
                direction TEXT NOT NULL CHECK(direction IN ('IN','OUT')),
                amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
                transaction_type TEXT NOT NULL,
                source_id TEXT,
                operation_id TEXT NOT NULL UNIQUE,
                payload_hash TEXT,
                fee_minor INTEGER NOT NULL DEFAULT 0 CHECK(fee_minor >= 0),
                note TEXT,
                created_by TEXT NOT NULL,
                created_at TEXT NOT NULL,
                operating_date TEXT,
                FOREIGN KEY(service_key) REFERENCES service_wallets(service_key) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(created_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE service_transfers (
                id TEXT PRIMARY KEY NOT NULL,
                operation_id TEXT NOT NULL UNIQUE,
                service_type TEXT NOT NULL CHECK(service_type IN ('SYRIATEL_BALANCE','SYRIATEL_CASH','SHAM_CASH')),
                service_key TEXT NOT NULL,
                amount_delivered_minor INTEGER NOT NULL CHECK(amount_delivered_minor > 0),
                profit_minor INTEGER NOT NULL CHECK(profit_minor >= 0),
                provider_fee_minor INTEGER NOT NULL CHECK(provider_fee_minor >= 0),
                customer_charge_minor INTEGER NOT NULL CHECK(customer_charge_minor > 0),
                receipt_account_id TEXT NOT NULL,
                receipt_channel TEXT NOT NULL CHECK(receipt_channel IN ('CASH','ELECTRONIC')),
                reference TEXT,
                note TEXT,
                payload_hash TEXT NOT NULL,
                created_by TEXT NOT NULL,
                created_at TEXT NOT NULL,
                operating_date TEXT,
                status TEXT NOT NULL DEFAULT 'POSTED' CHECK(status IN ('POSTED','REVERSED')),
                reversed_at TEXT,
                reversed_by TEXT,
                reversal_reason TEXT,
                FOREIGN KEY(service_key) REFERENCES service_wallets(service_key) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(receipt_account_id) REFERENCES cash_accounts(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(created_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(reversed_by) REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE customer_credit_revaluations (
                id TEXT PRIMARY KEY NOT NULL,
                invoice_id TEXT NOT NULL,
                customer_id TEXT NOT NULL,
                outstanding_usd_cents INTEGER NOT NULL CHECK(outstanding_usd_cents > 0),
                previous_syp_minor INTEGER NOT NULL CHECK(previous_syp_minor >= 0),
                new_syp_minor INTEGER NOT NULL CHECK(new_syp_minor >= 0),
                applied_rate_syp_per_usd_milli INTEGER NOT NULL CHECK(applied_rate_syp_per_usd_milli > 0),
                calculation_basis TEXT NOT NULL,
                operation_id TEXT NOT NULL UNIQUE,
                created_at TEXT NOT NULL,
                FOREIGN KEY(invoice_id) REFERENCES sales_invoices(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(customer_id) REFERENCES customers(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE cash_transactions (
                id TEXT PRIMARY KEY NOT NULL,
                account_id TEXT NOT NULL,
                direction TEXT NOT NULL CHECK(direction IN ('IN','OUT')),
                amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
                currency_code TEXT NOT NULL,
                exchange_rate_syp_per_usd_milli INTEGER,
                transaction_type TEXT NOT NULL,
                source_type TEXT,
                source_id TEXT,
                operation_id TEXT UNIQUE,
                description TEXT,
                created_by TEXT,
                created_at TEXT NOT NULL,
                operating_date TEXT,
                FOREIGN KEY(account_id) REFERENCES cash_accounts(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(created_by) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE invoice_payments (
                id TEXT PRIMARY KEY NOT NULL,
                invoice_type TEXT NOT NULL CHECK(invoice_type IN ('SALE','PURCHASE')),
                invoice_id TEXT NOT NULL,
                cash_transaction_id TEXT NOT NULL UNIQUE,
                amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
                currency_code TEXT NOT NULL,
                exchange_rate_syp_per_usd_milli INTEGER,
                created_at TEXT NOT NULL,
                FOREIGN KEY(cash_transaction_id) REFERENCES cash_transactions(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE debts (
                id TEXT PRIMARY KEY NOT NULL,
                party_type TEXT NOT NULL CHECK(party_type IN ('CUSTOMER','SUPPLIER')),
                party_id TEXT NOT NULL,
                direction TEXT NOT NULL CHECK(direction IN ('RECEIVABLE','PAYABLE')),
                amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
                currency_code TEXT NOT NULL,
                source_type TEXT,
                source_id TEXT,
                operation_id TEXT UNIQUE,
                description TEXT,
                created_by TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY(created_by) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE returns (
                id TEXT PRIMARY KEY NOT NULL,
                return_number TEXT NOT NULL UNIQUE,
                original_invoice_type TEXT NOT NULL CHECK(original_invoice_type IN
                    ('SALE','PURCHASE')),
                original_invoice_id TEXT NOT NULL,
                party_type TEXT CHECK(party_type IN ('CUSTOMER','SUPPLIER')),
                party_id TEXT,
                return_date TEXT NOT NULL,
                total_usd_cents INTEGER NOT NULL DEFAULT 0 CHECK(total_usd_cents >= 0),
                reason TEXT,
                status TEXT NOT NULL CHECK(status IN ('DRAFT','POSTED','CANCELLED')),
                created_by TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY(created_by) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE return_items (
                id TEXT PRIMARY KEY NOT NULL,
                return_id TEXT NOT NULL,
                product_id TEXT NOT NULL,
                quantity REAL NOT NULL CHECK(quantity > 0),
                unit_price_usd_cents INTEGER NOT NULL CHECK(unit_price_usd_cents >= 0),
                line_total_usd_cents INTEGER NOT NULL CHECK(line_total_usd_cents >= 0),
                FOREIGN KEY(return_id) REFERENCES returns(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(product_id) REFERENCES products(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE return_refund_allocations (
                id TEXT PRIMARY KEY NOT NULL,
                return_id TEXT NOT NULL,
                cash_account_id TEXT NOT NULL,
                amount_minor INTEGER NOT NULL CHECK(amount_minor > 0),
                currency_code TEXT NOT NULL,
                company_fee_minor INTEGER NOT NULL DEFAULT 0
                    CHECK(company_fee_minor >= 0),
                exchange_rate_syp_per_usd_milli INTEGER,
                created_at TEXT NOT NULL,
                FOREIGN KEY(return_id) REFERENCES returns(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT,
                FOREIGN KEY(cash_account_id) REFERENCES cash_accounts(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE operation_registry (
                operation_id TEXT PRIMARY KEY NOT NULL,
                operation_type TEXT NOT NULL,
                payload_hash TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN
                    ('PENDING','COMPLETED','FAILED','CONFLICT')),
                result_reference TEXT,
                created_at TEXT NOT NULL,
                completed_at TEXT
            )
            """.trimIndent(),

            """
            CREATE TABLE sync_queue (
                id TEXT PRIMARY KEY NOT NULL,
                operation_id TEXT NOT NULL UNIQUE,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                operation_type TEXT NOT NULL CHECK(operation_type IN
                    ('CREATE','UPDATE','DELETE')),
                payload_json TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN
                    ('PENDING','IN_PROGRESS','SYNCED','FAILED','CONFLICT')),
                retry_count INTEGER NOT NULL DEFAULT 0 CHECK(retry_count >= 0),
                last_error TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),

            """
            CREATE TABLE sync_conflicts (
                id TEXT PRIMARY KEY NOT NULL,
                operation_id TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                local_payload_json TEXT NOT NULL,
                remote_payload_json TEXT,
                resolution TEXT CHECK(resolution IN
                    ('KEEP_LOCAL','KEEP_REMOTE','MERGED','DISMISSED')),
                resolved_by TEXT,
                resolved_at TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY(resolved_by) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            """
            CREATE TABLE audit_log (
                id TEXT PRIMARY KEY NOT NULL,
                user_id TEXT,
                action TEXT NOT NULL,
                entity_type TEXT NOT NULL,
                entity_id TEXT,
                before_json TEXT,
                after_json TEXT,
                device_id TEXT,
                created_at TEXT NOT NULL,
                FOREIGN KEY(user_id) REFERENCES users(id)
                    ON UPDATE RESTRICT ON DELETE RESTRICT
            )
            """.trimIndent(),

            "CREATE INDEX idx_products_name ON products(name)",
            "CREATE INDEX idx_products_category ON products(category_id)",
            "CREATE INDEX idx_products_barcode ON products(barcode)",
            "CREATE INDEX idx_inventory_movements_product_date ON inventory_movements(product_id, created_at)",
            "CREATE INDEX idx_sales_date ON sales_invoices(invoice_date)",
            "CREATE INDEX idx_sales_posted_at ON sales_invoices(posted_at)",
            "CREATE INDEX idx_sales_posted_operating_date ON sales_invoices(posted_operating_date)",
            "CREATE INDEX idx_sales_customer_date ON sales_invoices(customer_id, invoice_date)",
            "CREATE INDEX idx_purchase_date ON purchase_invoices(invoice_date)",
            "CREATE INDEX idx_cash_transactions_account_date ON cash_transactions(account_id, created_at)",
            "CREATE INDEX idx_daily_cash_session_account ON daily_cash_sessions(primary_cash_account_id)",
            "CREATE INDEX idx_customer_credit_revaluation_invoice ON customer_credit_revaluations(invoice_id, created_at)",
            "CREATE INDEX idx_service_wallet_transactions_key_date ON service_wallet_transactions(service_key, created_at)",
            "CREATE INDEX idx_service_transfers_date ON service_transfers(created_at, service_type)",
            "CREATE INDEX idx_debts_party ON debts(party_type, party_id, created_at)",
            "CREATE INDEX idx_sync_queue_status_date ON sync_queue(status, created_at)",
            "CREATE INDEX idx_audit_log_entity_date ON audit_log(entity_type, entity_id, created_at)"
        )
    }
}
