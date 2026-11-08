# SQLCipher Production Integration Audit

Audit date: 2026-11-08  
Audited branch: `the-daily-shop-development`  
Audited commit: `c77baecefd2a7eee9d06d49e59a339a15ee40a61`  
Scope: production integration readiness, backup/restore, process interruption recovery, and preservation of the existing plaintext SQLite database.

## Executive decision

**NO-GO: SQLCipher must remain isolated and must not be enabled for the production database or shipped as a live migration yet.**

The current production database is still `the_daily_shop.db`, opened by Android `SQLiteOpenHelper` through `AppDatabase`, schema version 33. The SQLCipher key vault, exporter, backup container, restore pipeline, journals, and restore coordinators are not connected to the production database lifecycle.

This is a safe boundary today: the existing plaintext SQLite database has not been replaced by the new SQLCipher code. It is not evidence that production migration is ready.

## Verified current state

### Production boundary

- `AppDatabase.kt` still extends `SQLiteOpenHelper` and opens `the_daily_shop.db` at schema version 33.
- `AppDatabase.getInstance(context)` remains the production database factory.
- `MainActivity.onCreate()` invokes `BackupRestoreManager(this).recoverInterruptedRestore()` before the normal repository setup. This is recovery for the legacy SQLite restore flow only.
- `BackupRestoreManager.kt` still creates/restores the legacy `TDSBACK1` password-encrypted SQLite backup format.
- There is no format dispatcher connecting the legacy backup format to the new SQLCipher `TDSBKUP1` container.
- No production migration coordinator, app-wide database maintenance gate, or SQLCipher startup-recovery hook exists in the audited source tree.
- The SQLCipher same-device coordinator documents that recovery must run before opening the live database, but no production startup path invokes it.
- The new-device SQLCipher restore coordinator is also isolated from `BackupRestoreManager` and `AppDatabase`.

### SQLCipher implementation present

- `DatabaseKeyVault.kt`: device-local database key wrapped using Android Keystore AES-GCM; missing key metadata with an existing database fails closed.
- `SqlCipherDatabaseMigrator.kt`: plaintext-to-SQLCipher export primitive. It does not rename or delete the plaintext source; it validates SQLCipher integrity, foreign keys and version, then compares schema and typed table-content digests.
- `BackupKeyEnvelope.kt` and `SqlCipherBackupContainer.kt`: password-protected key envelope and encrypted backup container.
- `SqlCipherBackupRestorePipeline.kt`: validates closed/checkpointed SQLCipher input and stages restore; its direct commit path is restricted to an empty destination.
- `SqlCipherRestoreJournal.kt` and `SqlCipherRestoreCoordinator.kt`: journaled new-device restore and interrupted-operation recovery.
- `SqlCipherSameDeviceRestoreJournal.kt` and `SqlCipherSameDeviceRestoreCoordinator.kt`: journaled same-device replacement with wrapped-key snapshot and rollback database.

### CI evidence at the audited commit

All three latest workflows below ran against `c77baecefd2a7eee9d06d49e59a339a15ee40a61`:

- SQLCipher Android Instrumentation, run [42461608554](https://github.com/Hazem8989/Raseed/actions/runs/42461608554): **success**. All four jobs passed: core, coordinator, end_to_end, and pipeline.
- Android APK build, run [42461608519](https://github.com/Hazem8989/Raseed/actions/runs/42461608519): **success**.
- Cloudflare Sync Relay validation, run [42461608521](https://github.com/Hazem8989/Raseed/actions/runs/42461608521): **success**.

The pipeline instrumentation reports were inspected; the individual reports show successful tests for wrong-password rejection, tampered/truncated/trailing payload rejection, valid restore, non-empty WAL rejection, non-empty destination/key-import safety, and restore-journal recovery/corruption cases.

Passing these tests proves the isolated components and test fixtures, not production integration.

## Findings and risk assessment

| ID | Severity | Finding | Consequence / required action |
|---|---|---|---|
| F-01 | BLOCKER | No production migration transaction/coordinator exists. | There is no single controlled operation that blocks sales, purchases, collections and sync writes, checkpoints WAL, closes every DB handle, exports, verifies, swaps, and recovers. Do not invoke the exporter from production yet. |
| F-02 | BLOCKER | SQLCipher recovery is not wired into application startup before database access. | A future partially completed same-device restore could be followed by opening the wrong database/key pair. Invoke recovery before any repository, `StoreRepository.ensureBaseSetup()`, authentication, sync, or `AppDatabase` access. Fail closed if recovery cannot prove a valid state. |
| F-03 | BLOCKER | Production database consumers remain on Android SQLite APIs. | Switching only `AppDatabase` would leave mixed SQLite/SQLCipher APIs, incompatible transaction/database types, and unconverted repository/sync paths. Convert all consumers and test factories together. |
| F-04 | BLOCKER | Production backup/restore still uses legacy SQLite-only manager and format. | SQLCipher backups cannot be created/restored through the live production UI, and legacy backup compatibility is not yet dispatched explicitly. Add format detection and tested legacy restore/import before changing the live database. |
| F-05 | HIGH | Migration instrumentation uses a synthetic miniature schema, not a real version-33 application database fixture. | The test proves typed-value/schema/trigger/sequence preservation and source-byte preservation for its fixture, but not all real tables, indexes, triggers, views, FIFO/accounting/sync state, or real v33 data. Add a representative full-schema fixture and populated financial/inventory/sync data. |
| F-06 | HIGH | Existing legacy backup recovery is not equivalent to the SQLCipher same-device crash matrix. | Legacy tests cover interruption after moving the live database and recovery from a corrupted live file, but do not simulate process death at every restore phase. Keep this flow unchanged until SQLCipher recovery is integrated and tested at every durable boundary. |
| F-07 | HIGH | No app-wide write/maintenance lock is connected to migration. | A sync worker or another repository could write while migration is taking its snapshot or while files are being swapped. Add a shared gate covering UI writes, background work, relay sync, scheduled jobs, and all database handles. |
| F-08 | HIGH | SQLCipher restore coordinators are tested as isolated helpers, not through the actual startup and backup UI paths. | Their recovery guarantees are not yet demonstrated end-to-end with Android process recreation, real database factory initialization, permissions, and user-selected backup streams. Add instrumentation through those production entry points. |
| F-09 | MEDIUM | The migration primitive validates structural integrity and exact fixture parity, but there is no production acceptance report for domain-level totals. | Add explicit pre/post checks for product quantities, FIFO layers/allocations, inventory valuation, invoice/payment totals, customer/supplier balances, wallet balances, cash movements, and sync inbox/outbox/ack/conflict state. Exact full-table parity remains the primary invariant. |
| F-10 | MEDIUM | File rename/journal tests simulate process interruption, not device power-loss durability or storage faults. | Add fault-injection tests around file sync/rename/journal persistence and verify recovery preserves both old and new files when filesystem operations fail. Never clean an ambiguous rollback file automatically. |

## Data-preservation review

### What is already supported by evidence

1. The exporter has no code path that renames or deletes its plaintext source.
2. The migration instrumentation explicitly compares the source file bytes before and after export and asserts the source remains present and unchanged.
3. The exporter rejects an invalid destination, validates the encrypted result, checks foreign keys/integrity/version, and compares typed table content and schema.
4. The same-device restore coordinator retains the old encrypted database and a ciphertext-only wrapped-key snapshot while the new database is being committed.
5. The same-device instrumentation simulates interruption at each of its nine declared checkpoints and verifies either completion of the new database or rollback to the old database.
6. The legacy restore manager has a rollback file and startup recovery path for its existing SQLite format.

### What is not yet proven

- The actual production v33 database, with representative shop data, has not been migrated end-to-end.
- No production migration transaction currently preserves the old SQLite database through post-swap application acceptance.
- No startup path currently reconciles a SQLCipher migration/restore journal before the production database factory is used.
- No complete rollback test covers failure during each migration phase while real application writes and sync are disabled.
- No full application suite has run against a SQLCipher-backed production database helper because the production helper and consumers have not been converted.

**Preservation rule:** the original `the_daily_shop.db` must not be deleted, overwritten, or renamed away until the encrypted copy has passed full structural and domain parity checks, the SQLCipher database has been reopened by the production helper, representative read/write and application tests have passed, and restart recovery has been verified. Retain a verified rollback copy until explicit post-migration acceptance. Missing/corrupt key metadata or ambiguous journal/file states must stop startup without creating an empty database or deleting any candidate file.

## Required implementation sequence

1. **Add a migration transaction coordinator** with an app-wide maintenance/write gate. Stop sales, purchases, collections, inventory adjustments, background tasks and sync; prevent new DB handles; checkpoint WAL; close every connection; verify no non-empty sidecars remain.
2. **Create and verify a pre-migration rollback backup** of the existing SQLite database using the legacy restore-compatible format. Keep the original source untouched.
3. **Export to a unique temporary SQLCipher file** using a fresh key from the key vault. Never target the live database path.
4. **Validate before swap:** integrity check, foreign-key check, schema version, complete schema comparison, typed table digests/counts, and explicit business-domain totals for inventory/FIFO, invoices/payments, cash/wallets, parties and sync state.
5. **Add a durable migration journal and recovery coordinator** with explicit phases. Simulate process death after every file/key/journal operation. Recovery must choose only a fully validated new database or the original plaintext database; otherwise preserve all files and fail closed.
6. **Convert the production database factory and every consumer**—repositories, authentication/permissions, MainActivity, sync applier/protocol, exception handling, and test factories—to SQLCipher as one coordinated change. Do not mix Android SQLite and SQLCipher database objects.
7. **Wire startup recovery before any database access.** The migration/restore coordinator must run before repositories, session restoration, sync, or initial setup. If a journal is corrupt or a key is unavailable, block normal database opening and preserve all files.
8. **Integrate backup/restore format dispatch** for legacy `TDSBACK1` and SQLCipher `TDSBKUP1`; test old backups remain restorable and new backups restore on a fresh device and on the same device with rollback.
9. **Run the full test matrix** against the SQLCipher production helper: schema creation/upgrades through v33, repositories, authentication/permissions, financial calculations, inventory/FIFO, sync, backup/restore, crash recovery, and release APK.
10. **Perform a controlled rehearsal on a copy** of a representative production database. Verify totals, restart, backup, restore, and rollback. Only then consider a staged live rollout.

## Acceptance gate

SQLCipher production activation is permitted only when all of the following are true:

- Every production database consumer uses SQLCipher consistently.
- Migration is protected by a tested maintenance gate and durable journal.
- Startup recovery executes before any database open.
- Legacy backups remain restorable and new SQLCipher backups pass both fresh-device and same-device tests.
- The real v33 schema and representative store data pass exact parity and domain-total checks.
- Failure injection proves the old SQLite database is recoverable after every interruption point.
- The full application test suite and release APK build pass on the integrated production implementation.
- The original SQLite source/rollback is retained until post-migration acceptance.

**Final audit result: NO-GO.** The isolated SQLCipher components and their current CI suites pass, and the existing plaintext source-preservation primitive is promising. Production migration, startup recovery wiring, same-device restore integration, legacy/new backup dispatch, complete consumer conversion, and full-schema rehearsal remain unfinished. No production database or live store data should be migrated yet.
