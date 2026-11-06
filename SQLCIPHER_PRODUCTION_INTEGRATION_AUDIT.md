# SQLCipher Production Integration Audit

Date: 2026-11-07
Branch: `the-daily-shop-development`
Audited commit: `e4b838544bff2cb588181dc190042dd2853312b1`

## Decision

**Do not activate SQLCipher in production yet.** The successful Android instrumentation run proves six component tests: four key-vault cases, one synthetic SQLite-to-SQLCipher export case, and one encrypted reopen/wrong-key case. It does not prove that the live application, its backup/restore flow, or a real version-33 database can be migrated safely.

No production database code was changed during this audit.

## Current live database boundary

- `AppDatabase` still extends Android `SQLiteOpenHelper`.
- Live filename: `the_daily_shop.db`; schema version: 33.
- Production consumers obtain the singleton through `AppDatabase.getInstance(context)`.
- The full schema creation and upgrade chain remains in `AppDatabase.onCreate/onUpgrade`.
- `DatabaseKeyVault` explicitly states it is not connected to `AppDatabase`.
- SQLCipher dependency exists, but ordinary Android SQLite remains the live engine.

## Production consumers requiring coordinated conversion

Direct SQLite database/helper types or calls were found in:

- Core: `AppDatabase.kt`
- Authentication/permissions: `AuthRepository.kt`, `PermissionPolicy.kt`
- Store/catalog: `StoreRepository.kt`, `CashAccountRepository.kt`, `CustomerRepository.kt`, `SupplierRepository.kt`
- Sales/purchases/returns/payments: `InvoiceRepository.kt`, `CustomerPaymentRepository.kt`, `PurchasePaymentRepository.kt`, `PurchaseReturnRepository.kt`, `SalesReturnRepository.kt`
- Cash/reports/expenses/inventory: `DailyBalanceRepository.kt`, `OperatingExpenseRepository.kt`, `InventoryValuationRepository.kt`, `SalesProfitRepository.kt`, `PartyStatementRepository.kt`, `InventoryAdjustmentRepository.kt`
- Service transfers: `ServiceTransferRepository.kt`, `ServiceTransferReceivableRepository.kt`
- Sync: `SyncDomainApplier.kt`, `SyncProtocolRepository.kt`
- UI: `MainActivity.kt`
- Backup/restore: `BackupRestoreManager.kt`

The sync applier and protocol repository contain extensive direct transactional database access. A partial conversion would leave mixed engines/types and is not acceptable.

## Critical backup/restore incompatibility

Current `BackupRestoreManager` copies the live database file after a WAL checkpoint, wraps the copy in password-derived AES-GCM encryption, then validates a restored staging file with Android's ordinary `SQLiteDatabase.openDatabase`. That validation cannot open SQLCipher files.

A cross-device restore also cannot rely on the source device's Android Keystore key. The versioned backup format must carry the database payload and a copy of its database key wrapped by the backup password/recovery key. Restore must re-wrap that key under the destination device's Keystore before opening SQLCipher. The raw database key must never be sent through the sync relay or stored unprotected.

## Required order of work

1. Replace the live helper with SQLCipher's helper/API, load the native library before first open, and preserve all schema/version-33 upgrade behavior.
2. Convert every production consumer listed above, including exception/constants imports and test database factories.
3. Redesign backup/restore and cross-device key recovery; validate staged databases through SQLCipher only.
4. Add a resumable maintenance-mode migration coordinator: stop writes/sync, checkpoint WAL, close every connection, make and verify a backup, export to a temporary encrypted file, compare schema/row counts/financial totals/stock/FIFO/sync state, reopen and test, atomically swap, and retain the original until post-migration acceptance.
5. Add failure injection at each migration phase, fresh-device restore tests, and representative version-33 upgrade tests.
6. Run the complete accounting, inventory, permissions, backup/restore, and sync suites against SQLCipher.

## Acceptance gate

Do not wire `DatabaseKeyVault` into production open, rename/delete the plaintext source, or publish an APK claiming encrypted live storage until all gates pass. A missing/corrupt key must fail closed without creating an empty replacement database.

**Audit status: integration blocked; live database unchanged.** The next implementation step is the coordinated SQLCipher helper conversion, with backup/restore remaining a hard gate before activation.
