# SQLCipher production integration — audited conversion plan

Branch: `the-daily-shop-development`
Audit base: `381b2d55e90c297fd437ce4387e737e5717c1f2d`

## Current verified state

- `net.zetetic:sqlcipher-android:4.17.0` and `androidx.sqlite:sqlite:2.7.0` are already dependencies.
- `AppDatabase` is still backed by `android.database.sqlite.SQLiteOpenHelper` and `android.database.sqlite.SQLiteDatabase`.
- `TheDailyShopApplication.onCreate()` currently recovers same-device SQLCipher restore journals and then runs the legacy plaintext `BackupRestoreManager` recovery. It does not load SQLCipher or run the legacy-to-encrypted migration coordinator.
- `SqlCipherLegacyMigrationCoordinator`, key vault, encrypted backup container/pipeline, and restore coordinators are standalone primitives, not the production database path.
- The current `BackupRestoreManager` snapshots the live DB file, encrypts the snapshot with the backup password, and validates/restores it by opening it as plaintext Android SQLite. It is not compatible with an encrypted production DB as written.
- Existing Robolectric tests instantiate `AppDatabase` and exercise plaintext SQLite directly. These tests cannot be treated as proof of native SQLCipher production behavior; preserve their behavioral assertions while adding/running device instrumentation coverage for the SQLCipher implementation.

## Direct production SQLite API consumers found

All of these source files use Android SQLite types/constants directly and must be migrated together with the production helper. Do not replace only the imports in `AppDatabase.kt`.

- `data/AppDatabase.kt` — helper base class, database callback types, all schema migrations and schema helpers.
- `data/AuthRepository.kt` — `SQLiteDatabase`, `SQLiteConstraintException`, conflict constants, helper callback.
- `data/CashAccountRepository.kt` — fully-qualified `android.database.sqlite.SQLiteDatabase` in signatures and conflict constants.
- `data/CustomerPaymentRepository.kt`
- `data/CustomerRepository.kt` — fully-qualified SQLite types.
- `data/DailyBalanceRepository.kt`
- `data/InventoryAdjustmentRepository.kt`
- `data/InvoiceRepository.kt`
- `data/OperatingExpenseRepository.kt`
- `data/PermissionPolicy.kt`
- `data/PurchasePaymentRepository.kt`
- `data/PurchaseReturnRepository.kt`
- `data/SalesReturnRepository.kt`
- `data/ServiceTransferReceivableRepository.kt`
- `data/ServiceTransferRepository.kt`
- `data/StoreRepository.kt` — fully-qualified SQLite types and Android constraint exception.
- `data/StoreTime.kt`
- `data/SyncDomainApplier.kt` — fully-qualified conflict constants and SQLite type.
- `data/SyncProtocolRepository.kt`
- `data/BackupRestoreManager.kt` — plaintext-only file open/validation/restore logic; requires a deliberate format-compatible rewrite, not an import substitution.
- UI and query-only consumers also use the public helper: `MainActivity.kt`, `InventoryValuationRepository.kt`, `PartyStatementRepository.kt`, and `SalesProfitRepository.kt`. Their call sites should remain source-compatible through `AppDatabase`'s public helper accessors.

## Required implementation order

1. **Keep production on the existing SQLite implementation while preparing the conversion.** No startup migration is safe until every database consumer and backup/restore path understands SQLCipher.
2. **Introduce a single SQLCipher database runtime.** Load `sqlcipher` before any SQLCipher operation; obtain the existing key from `DatabaseKeyVault`; keep key byte arrays short-lived and clear caller-owned copies; never create a replacement key when a live DB exists but key metadata is missing.
3. **Convert the complete database API surface.** Move `AppDatabase` to `net.zetetic.database.sqlcipher.SQLiteOpenHelper` and update every direct SQLite type/constant/exception consumer listed above. Verify constructor signatures and exception packages against the pinned 4.17.0 artifact rather than assuming all Android SQLite APIs are identical.
4. **Preserve schema semantics.** Keep database version 33 and every historical upgrade path. Do not recreate a missing/undecipherable live database as an empty database. Keep foreign-key enforcement and transaction behavior.
5. **Rework backup/restore before startup migration.** New backups must preserve authenticated encrypted DB bytes and the DB key envelope under the existing password-protected backup contract. Restore must accept historical TDSBACK1 plaintext-SQLite payloads, validate their SQLite integrity/foreign keys, then migrate them to SQLCipher before installing/opening production DB. New encrypted backups must validate with SQLCipher and key. Same-device restore must coordinate DB-file and key rollback together. Keep all rollback/journal files until post-install SQLCipher validation succeeds.
6. **Install the startup barrier.** In `TheDailyShopApplication`, load native library, recover any in-progress same-device restore, recover any in-progress legacy migration, migrate the legacy SQLite DB if and only if it is proven plaintext, validate the installed encrypted DB, then allow all normal consumers. Startup must fail closed on missing key, corrupt journal, ambiguous/orphan files, or failed validation. Never open `AppDatabase` before these steps.
7. **Move/extend tests without weakening them.** Retain schema-upgrade/data-preservation assertions. Add device tests for legacy v33 migration, all business tables/rows and foreign keys parity, encrypted backup/restore, historical backup restore, key/DB rollback consistency, process death at every journal checkpoint, and normal sales/purchase/debt/returns/sync flows through the SQLCipher-backed repositories.
8. **Run CI against one commit SHA.** Require unit tests, SQLCipher instrumentation (including migration, key vault, restore coordinator and same-device restore), signed APK build, and Cloudflare relay suite all green on the same branch head. Do not report standalone coordinator success as production integration success.

## Compatibility constraints

- The pinned modern SQLCipher API uses package `net.zetetic.database.sqlcipher` and requires `System.loadLibrary("sqlcipher")` before database operations.
- The production migration must use the existing coordinator's closed-database callback and recovery protocol; it must not invoke migration while any repository, service, receiver, or UI has an open helper.
- Current Android SQLite exception imports in `AuthRepository.kt` and `StoreRepository.kt` need explicit compatibility review. Do not blindly rewrite exception imports.
- The current backup format is TDSBACK1 and is an authenticated AES-GCM envelope around a plaintext SQLite snapshot. Preserve backwards restore support; do not silently reinterpret its payload as SQLCipher ciphertext.
- Do not change the database schema version solely to change the encryption provider.
- Do not touch the `main` branch.

## Acceptance gate

Production SQLCipher is complete only when the production `AppDatabase` opens an encrypted file with the vault key after startup migration, all direct consumers compile and pass tests, old and new backup/restore formats work, interruption recovery preserves user data, and the full required CI matrix passes on one SHA.
