# SQLCipher Production Integration Audit

Date: 2026-11-07
Branch: `the-daily-shop-development`
Latest implementation at audit: `a1b3ddfbaca2bf37ef32da8b9f0416698a9ff99a` (subsequent backup-pipeline commits are recorded in Git history)

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
- `SqlCipherBackupRestorePipeline.kt`: validates a closed/checkpointed SQLCipher file before backup; stages and validates restored ciphertext with SQLCipher; restricts commit to an empty destination; enforces same-directory atomic rename; and leaves the staged file available if the key-import/rename sequence needs retry.\n- `SqlCipherRestoreJournal.kt`: atomically persists key-free restore phases (STAGED → KEY_IMPORTED → DATABASE_COMMITTED), validates same-directory safe file names, rejects skipped/reversed transitions and corrupt records, and stores no passwords, keys, or absolute paths.\n- `SqlCipherRestoreCoordinator.kt` (new): coordinates empty-destination restore using the journal; validates encrypted data before key import and after rename; recovers the interrupted states (key imported before phase advance, rename completed before phase advance, and committed state before journal cleanup); waits without modifying files if key metadata is absent; never creates a replacement key or overwrites an existing destination. This remains an isolated new-device primitive and is not connected to `BackupRestoreManager` or `AppDatabase`.
- `DatabaseKeyVault.importDatabaseKey(...)`: imports only on an empty destination; an interrupted retry is accepted only when the existing local key matches byte-for-byte, and a different or unreadable key fails closed. A non-creating recovery lookup was added so startup recovery never generates a replacement key.
- `SqlCipherBackupRestoreInstrumentedTest.kt`: covers pipeline round-trip, wrong password, payload tampering, truncation, trailing bytes, non-empty WAL refusal, non-empty destination refusal, idempotent/same-key versus different-key import, durable journal persistence/transition/corruption checks, plus coordinator recovery when key import is delayed until after restart. Latest CI for the coordinator is pending.
- `SqlCipherBackupRestoreInstrumentedTest.kt`: previously passed a fresh-vault restore test, including integrity, foreign-key, data, trigger, and write checks.
- `SqlCipherDatabaseMigrator.kt` (new): non-destructive export primitive. It leaves the source file untouched, exports through SQLCipher `sqlcipher_export`, validates integrity/foreign keys/version, and compares schema objects and per-table row counts between source and encrypted output.
- `SqlCipherDatabaseMigratorInstrumentedTest.kt` (new): verifies source preservation, schema/data/trigger migration, and encrypted write access. Current CI is pending.

## Remaining work — required order

1. Finish CI for the non-destructive export primitive and fix any issues before proceeding.
2. Complete production-grade backup creation/restore integration around `SqlCipherBackupRestorePipeline`: connect permission checks, maintenance/connection closure, connect the new durable journal to an interruption-recovery coordinator, implement same-device replacement with rollback, and add explicit format dispatch preserving legacy plaintext/password-protected backups. The current pipeline and journal are deliberately isolated; neither is wired into `BackupRestoreManager` or `AppDatabase`.
3. Complete and test the migration coordinator: explicit maintenance mode; stop sales/purchases/collections and sync writes; checkpoint WAL; close every connection; create and verify a password-protected rollback backup; export to a temporary encrypted file; compare schema, row counts, financial totals, balances, inventory/FIFO and sync state; reopen and test; atomically swap; retain original until post-migration acceptance.
4. Convert `AppDatabase` to SQLCipher only behind the completed migration gate, preserving every schema creation and version-33 upgrade step.
5. Convert every direct production consumer listed above, including `MainActivity`, sync applier/protocol, exception types, conflict constants, and test database factories.
6. Add failure injection at every migration phase, interrupted-startup recovery, missing/corrupt-key tests, fresh-device restore tests, and representative schema-upgrade tests.
7. Run the complete accounting, inventory, permissions, backup/restore, sync, and release-build suites against the encrypted production helper.
8. Only then enable the migration for a controlled first launch and validate on a copy of representative store data before any live-store rollout.

## Acceptance gate

Do not wire `DatabaseKeyVault` into production open, replace/rename/delete the plaintext source, or publish an APK claiming encrypted live storage until all gates pass. A missing/corrupt key must fail closed; it must never create an empty replacement database. Never send raw database keys through the sync relay, logs, or Git.

**Status: isolated key vault, cross-device backup container, non-destructive database export, isolated SQLCipher backup/restore pipeline, durable key-free journal, and a journal-driven new-device recovery coordinator have been added. Coordinator CI is pending. Production manager integration, same-device transactional restore, legacy-format dispatch, full consumer conversion, and production helper conversion remain blocked.**
