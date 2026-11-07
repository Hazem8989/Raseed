# SQLCipher Production Integration Audit

Date: 2026-11-07
Branch: `the-daily-shop-development`
Latest implementation at audit: `4abb04fb26bcfeb9bba297f3f1d02c4e4c965d89`

## Decision

**SQLCipher is not enabled for the production database. Do not activate it or install a migration build on the shop device yet.** The existing live database remains ordinary SQLite, schema version 33.

The preceding commit `096fa4d` passed:
- SQLCipher instrumentation, including password-protected backup-container restore into a fresh device-local key vault.
- Signed release APK build.
- Cloudflare sync-relay validation.

The new plaintext-to-SQLCipher export primitive and its expanded parity checks are on the current branch and await their own CI run. They have not yet been accepted as production migration code.

## Current production boundary

- `AppDatabase` still extends Android `SQLiteOpenHelper`.
- Live filename: `the_daily_shop.db`; schema version: 33.
- Production consumers use `AppDatabase.getInstance(context)`.
- `DatabaseKeyVault` is not connected to production database opening.
- Existing `BackupRestoreManager` still uses ordinary SQLite validation and its legacy backup format.
- SQLCipher remains an isolated implementation/test path.

## Direct consumers requiring coordinated conversion

- Core: `AppDatabase.kt`
- Authentication and permissions: `AuthRepository.kt`, `PermissionPolicy.kt`
- Store/catalog: `StoreRepository.kt`, `CashAccountRepository.kt`, `CustomerRepository.kt`, `SupplierRepository.kt`
- Sales/purchases/returns/payments: `InvoiceRepository.kt`, `CustomerPaymentRepository.kt`, `PurchasePaymentRepository.kt`, `PurchaseReturnRepository.kt`, `SalesReturnRepository.kt`
- Cash/reports/expenses/inventory: `DailyBalanceRepository.kt`, `OperatingExpenseRepository.kt`, `InventoryValuationRepository.kt`, `SalesProfitRepository.kt`, `PartyStatementRepository.kt`, `InventoryAdjustmentRepository.kt`
- Service transfers: `ServiceTransferRepository.kt`, `ServiceTransferReceivableRepository.kt`
- Sync: `SyncDomainApplier.kt`, `SyncProtocolRepository.kt`
- UI: `MainActivity.kt`
- Backup/restore: `BackupRestoreManager.kt`

The sync repositories contain extensive transactional access. All these call sites, SQLite exception imports/constants, and test factories must move together to avoid mixed database engines.

## Implemented SQLCipher building blocks

- `DatabaseKeyVault.kt`: generates a random 256-bit key and wraps it with an Android Keystore AES-GCM key. It fails closed if a database exists but its key metadata is missing.
- `BackupKeyEnvelope.kt`: password-protects the SQLCipher key using a versioned authenticated envelope and bounded PBKDF2 parameters.
- `SqlCipherBackupContainer.kt`: stores the encrypted SQLCipher payload plus the wrapped key envelope; never serializes the raw key.
- `DatabaseKeyVault.importDatabaseKey(...)`: allows importing a recovered key only when the destination has neither a database nor existing key metadata.
- `SqlCipherBackupRestoreInstrumentedTest.kt`: previously passed a fresh-vault restore test, including integrity, foreign-key, data, trigger, and write checks.
- `SqlCipherDatabaseMigrator.kt` (new): non-destructive export primitive. It leaves the source file untouched, exports through SQLCipher `sqlcipher_export`, validates integrity/foreign keys/version, and compares schema objects and per-table row counts between source and encrypted output.
- `SqlCipherDatabaseMigratorInstrumentedTest.kt` (new): verifies source preservation, schema/data/trigger migration, and encrypted write access. Current CI is pending.

## Remaining work — required order

1. Finish CI for the non-destructive export primitive and fix any issues before proceeding.
2. Add production-grade backup creation/restore using `SqlCipherBackupContainer`, with SQLCipher-only staged validation, key import rules, rollback, interruption recovery, and backward compatibility for existing plaintext/legacy backups.
3. Complete and test the migration coordinator: explicit maintenance mode; stop sales/purchases/collections and sync writes; checkpoint WAL; close every connection; create and verify a password-protected rollback backup; export to a temporary encrypted file; compare schema, row counts, financial totals, balances, inventory/FIFO and sync state; reopen and test; atomically swap; retain original until post-migration acceptance.
4. Convert `AppDatabase` to SQLCipher only behind the completed migration gate, preserving every schema creation and version-33 upgrade step.
5. Convert every direct production consumer listed above, including `MainActivity`, sync applier/protocol, exception types, conflict constants, and test database factories.
6. Add failure injection at every migration phase, interrupted-startup recovery, missing/corrupt-key tests, fresh-device restore tests, and representative schema-upgrade tests.
7. Run the complete accounting, inventory, permissions, backup/restore, sync, and release-build suites against the encrypted production helper.
8. Only then enable the migration for a controlled first launch and validate on a copy of representative store data before any live-store rollout.

## Acceptance gate

Do not wire `DatabaseKeyVault` into production open, replace/rename/delete the plaintext source, or publish an APK claiming encrypted live storage until all gates pass. A missing/corrupt key must fail closed; it must never create an empty replacement database. Never send raw database keys through the sync relay, logs, or Git.

**Status: isolated key vault and cross-device backup primitives tested; non-destructive database export primitive added and awaiting CI; production helper conversion, full consumer conversion, and production backup/restore integration remain blocked.**
