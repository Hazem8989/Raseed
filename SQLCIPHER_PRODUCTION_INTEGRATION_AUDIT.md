# SQLCipher Production Integration Audit

Date: 2026-11-07
Branch: `the-daily-shop-development`
Latest implementation: `a2016998730201e24bbe018854fae7b107c0634c`

## Decision

**Do not activate SQLCipher in production yet.** The six Android instrumentation tests on the preceding SQLCipher commit proved the key vault, synthetic export, and encrypted reopen path. New backup-key envelope code has now been added with six JVM unit tests; its CI run is pending. This is still component-level evidence, not proof of a complete application backup/restore or production migration.

The live database remains ordinary SQLite and has not been changed.

## Current live database boundary

- `AppDatabase` still extends Android `SQLiteOpenHelper`.
- Live filename: `the_daily_shop.db`; schema version: 33.
- Production consumers obtain the singleton through `AppDatabase.getInstance(context)`.
- The full schema creation and upgrade chain remains in `AppDatabase.onCreate/onUpgrade`.
- `DatabaseKeyVault` is not connected to `AppDatabase`.
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

## Backup key recovery work added

New file: `app/src/main/java/com/hazem/dailystore/data/BackupKeyEnvelope.kt`.

It defines a versioned binary envelope containing a format marker, version, bounded PBKDF2 iteration count, random salt, random GCM IV, and AES-GCM authenticated ciphertext of the 256-bit SQLCipher database key. The key is never serialized in plaintext. The format binds the header as GCM additional authenticated data and rejects malformed versions, out-of-range KDF settings, wrong passwords, and tampering.

New tests: `app/src/test/java/com/hazem/dailystore/data/BackupKeyEnvelopeTest.kt` (six cases: round trip, fresh salt/IV, wrong password, tampering, malformed/unsupported format, and invalid key/password).

**Important limitation:** this is the key-recovery envelope primitive only. It is not yet integrated into `BackupRestoreManager`, the backup container, or `DatabaseKeyVault`. No claim is made that cross-device restore works yet.

## Critical backup/restore incompatibility

Current `BackupRestoreManager` copies the live database file after a WAL checkpoint, wraps the copy in password-derived AES-GCM encryption, then validates a restored staging file with Android's ordinary `SQLiteDatabase.openDatabase`. That validation cannot open SQLCipher files.

A cross-device restore must unwrap the database key from the authenticated backup envelope, import it into the destination device's local Keystore wrapper, and validate/open the staged database only through SQLCipher. The raw database key must never be sent through the sync relay or stored unprotected.

## Required order of work

1. Finish and test a versioned backup container integrating the encrypted database payload and key-recovery envelope.
2. Add a safe import operation to `DatabaseKeyVault` for a fresh destination installation, refusing to overwrite an existing database/key.
3. Replace the live helper with SQLCipher's helper/API, load the native library before first open, and preserve all schema/version-33 upgrade behavior.
4. Convert every production consumer listed above, including exception/constants imports and test database factories.
5. Convert backup/restore to validate staged databases through SQLCipher only and prove fresh-device recovery.
6. Add a resumable maintenance-mode migration coordinator: stop writes/sync, checkpoint WAL, close every connection, make and verify a backup, export to a temporary encrypted file, compare schema/row counts/financial totals/stock/FIFO/sync state, reopen and test, atomically swap, and retain the original until post-migration acceptance.
7. Add failure injection at each migration phase, fresh-device restore tests, and representative version-33 upgrade tests.
8. Run the complete accounting, inventory, permissions, backup/restore, and sync suites against SQLCipher.

## Acceptance gate

Do not wire `DatabaseKeyVault` into production open, rename/delete the plaintext source, or publish an APK claiming encrypted live storage until all gates pass. A missing/corrupt key must fail closed without creating an empty replacement database.

**Status: backup-key envelope primitive implemented; CI pending; end-to-end backup/restore and production SQLCipher integration remain blocked.**
