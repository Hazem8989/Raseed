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

    override fun onCreate(db: SQLiteDatabase) {
        db.beginTransaction()
        try {
            SCHEMA.forEach(db::execSQL)
            db.execSQL(
                """INSERT INTO app_settings(setting_key, setting_value, updated_at)
                   VALUES('base_currency', 'USD', strftime('%Y-%m-%dT%H:%M:%fZ','now'))"""
            )
            db.execSQL(
                """INSERT INTO app_settings(setting_key, setting_value, updated_at)
                   VALUES('store_currency', 'SYP', strftime('%Y-%m-%dT%H:%M:%fZ','now'))"""
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
        if (version != newVersion) {
            throw IllegalStateException("Missing database migration from version $version to $newVersion")
        }
    }

    companion object {
        const val DATABASE_NAME = "the_daily_shop.db"
        const val DATABASE_VERSION = 5

        @Volatile
        private var INSTANCE: AppDatabase? = null

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
