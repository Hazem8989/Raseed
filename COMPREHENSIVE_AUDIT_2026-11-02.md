# The Daily Shop — Comprehensive Repository Audit

**Audit date:** 2026-11-02  
**Repository:** `Hazem8989/Raseed`  
**Audited branch:** `the-daily-shop-development`  
**Audited HEAD:** `78aba439f449d3b652ce96bb650c686ccbe2acf3`  
**Main branch observed:** `63b233cd4fb3085d0ade05e3e4c1007efcae30ca` (unchanged during audit)

## Executive conclusion

The current development branch is a substantial local Android POS/accounting prototype with working SQLite repositories, purchase/sales posting, customer collections, supplier payments, cash/wallet flows, FIFO cost layers, basic gross-profit reporting, encrypted backup/restore, and automated unit tests.

**It is not yet production-ready and must not be treated as a complete accounting system.** The most important blocker found in this audit is inconsistent authorization: manager-only functions are guarded in some screens/repositories, while other financially sensitive or master-data operations remain accessible without a manager check. Return/refund, stock-adjustment, and operating-expense workflows are also not implemented end-to-end.

The latest GitHub Actions run for the audited HEAD completed successfully. It ran `testDebugUnitTest`, built a **debug-only APK**, verified its APK signature, and uploaded the artifact. No permanent release signing credentials were detected; the release build was skipped. This is CI evidence, not Android-device acceptance or production-release evidence.

## Verified baseline

- Development HEAD: `78aba439f449d3b652ce96bb650c686ccbe2acf3`.
- Main HEAD: `63b233cd4fb3085d0ade05e3e4c1007efcae30ca`; no main-branch changes were made.
- Database schema version: **11**.
- Automated test source files: **16**.
- The latest Android workflow completed with conclusion `success`.
- Test task completed successfully; debug APK build and artifact upload completed successfully.
- Artifact: `the-daily-shop-debug-development-only`; it is not a signed release upgrade.
- Manifest has no internet/network permissions and no sync service. This is consistent with the approved local-only posture; it also confirms live multi-device sync is not implemented.
- The schema contains `sync_queue` and `sync_conflicts`, but those tables are not a synchronization engine.
- The schema contains return-related tables, but no complete return/refund repository or UI workflow was found.
- No operating-expense table/repository/workflow was found.
- `permissions` and `role_permissions` tables exist, but no permission seed/authorization implementation was found in the inspected application flow.

## Findings by severity

### Critical — Authorization is inconsistent

Evidence:
- `MainActivity.showProductManagement()` exposes product creation/edit/deactivation and exchange-rate editing without a manager gate.
- Product editing and product activation are performed through `StoreRepository.saveProduct()` and `setProductActive()`, neither of which accepts/verifies an authenticated actor.
- `StoreRepository.saveExchangeRate()` does not verify an actor or role.
- `CashAccountRepository.create()` creates a financial account without an actor, role check, or audit entry; the UI exposes the add-account action without a manager gate.
- Customer and supplier master-data create/update methods do not record actor provenance or audit events.
- Purchase screens are available to cashier navigation; whether employees may purchase or record supplier payments needs an explicit permission policy.
- Permission tables are present but appear unused; no populated role-permission policy was located.
- Some other functions do perform manager checks (e.g. supplier-payment correction, selected account configuration, backup/restore, service-wallet reversals), so the issue is inconsistent rather than total absence.

Risk: a cashier session can reach actions that should be manager-controlled, and repository calls are not consistently protected even if UI controls are hidden.

Required action:
1. Define the initial ADMIN/CASHIER permission matrix with the owner.
2. Enforce the matrix inside repositories/data services, not only in UI visibility.
3. Verify active user and role at commit time; do not trust a role string supplied by the caller.
4. Add audit provenance for master-data and opening-balance/account creation changes.
5. Add negative tests proving cashier attempts are rejected for every manager-only operation.

### High — Returns/refunds are not implemented end-to-end

The database has `returns`, `return_items`, and `return_refund_allocations` tables. No return repository or complete UI flow was found. There is no verified workflow that validates the original invoice and returned quantity, restores the appropriate FIFO cost, posts cash/wallet refunds, applies provider/company fees, updates debt/payment allocations, and writes an audit trail atomically.

Required action: implement sales returns and purchase returns as distinct workflows, including partial returns, repeated-return limits, FIFO provenance, refund allocation, fee rules, idempotency, audit, and reversal tests.

### High — Inventory adjustments and reconciliation are incomplete

Current stock writes are primarily purchase receipts, sales deductions, and opening-stock entries. A full stocktake/adjustment workflow for shortages, surpluses, damage, loss, and warehouse transfers was not found. The valuation repository appropriately flags current balance/ledger mismatches and uncosted quantities rather than presenting them as confirmed capital, but it cannot replace a controlled adjustment workflow.

Required action: add an auditable stocktake session and adjustment posting flow. Define cost treatment for positive and negative adjustments and ensure FIFO layers and movement ledger update atomically.

### High — Expenses and net-profit accounting are absent

No expense ledger/repository was found. The current sales-profit report is explicitly gross trading profit (net invoice revenue less FIFO COGS) and excludes operating expenses and returns. It must not be called net profit.

Required action: implement expense categories, payment account/currency, operating date, actor, attachment/reference, idempotency, audit, and expense reporting before claiming net profit.

### High — Production release and device acceptance are pending

The workflow did not detect permanent signing configuration, skipped the release APK, and built a debug-only APK. A debug artifact is not a safe signed update over a future release-signed installation. No physical Android device acceptance was evidenced in this audit.

Required action: establish a permanent signing key securely outside source control; verify update continuity using the same key; then complete on-device acceptance with a backup and test data before any production use.

### Medium — Backup/restore needs operational hardening

Implemented code uses authenticated AES-GCM encryption, password-based key derivation, staging validation, SQLite integrity/foreign-key checks, and rollback retention. Tests cover roundtrip, wrong password, corrupted authentication tag, and short password rejection.

Remaining risks:
- Backup/restore is invoked synchronously from Activity result handling; large files may block the UI or cause an ANR.
- No progress/cancellation UX or tested large-database performance limit was demonstrated.
- No test was found for failure during opening/migrating the restored database after file replacement and rollback.
- Password confirmation and a guided pre-restore safety flow can be improved.
- The rollback file and staging lifecycle deserve explicit fault-injection tests.

Required action: move backup/restore to a background executor with progress and controlled lifecycle; add failure injection tests for every replacement/rollback boundary.

### Medium — Database migration coverage is not exhaustive

The migration chain is sequential through version 11. Automated tests cover selected historical upgrade paths (including FIFO schema migration and posting-date migration), but not a representative fixture for every supported historical version 1–10.

Required action: add migration fixtures for each supported version and assert row counts, balances, foreign keys, FIFO layers, invoice status, and audit/operation history after upgrade.

### Medium — Reporting is not yet a complete financial statement set

Implemented reports include daily cash/wallet summary, inventory FIFO valuation with incompleteness warnings, and gross sales profit when all invoice COGS are available. Full customer/supplier running statements, return-aware gross profit, expense-aware net profit, bank reconciliation, and export-to-PDF/Excel were not verified as complete.

### Medium — Audit trail coverage is uneven

Some posting and manager operations write audit entries, but product, exchange-rate, account-creation, customer, and supplier master-data changes are not consistently attributable to a user or recorded with before/after state.

### Medium — MainActivity concentration

`MainActivity.kt` is approximately 3,461 lines and contains navigation, UI, and much of the business interaction logic. This increases regression risk and makes permissions and workflow testing harder. Refactor incrementally after correctness/security gates, not as an unbounded redesign.

### Medium — No live sync engine

The current architecture correctly avoids network exposure and external hosting. Device identity and schema foundations do not constitute pairing, authenticated transport, primary authority, outbox replay, acknowledgement, conflict resolution, or multi-device operation. Do not enable network listeners until those gates are implemented and security-reviewed.

## What is implemented and has automated evidence

- SQLite schema and sequential migrations through version 11.
- Login/password hashing and forced password rotation for initial/existing users.
- Encrypted local session reference using Android Keystore.
- Product import validation and USD/SYP reference-price handling.
- Purchase and sales invoice draft/posting transactions with operation idempotency.
- Stock movements for purchases, sales, and opening stock.
- FIFO cost layers, per-sale allocations, and COGS fields.
- Gross sales profit report with an explicit incomplete-cost warning.
- Customer credit collections and revaluation logic.
- Supplier payment flows and manager correction tests.
- Cash accounts, daily rollover, wallet opening, internal transfer, and service-transfer accounting.
- Encrypted database backup/restore with basic integrity validation.
- CI unit tests and debug APK packaging.

This list does not imply device acceptance or that omitted edge cases are fully tested.

## Recommended execution order after this audit

1. **Authorization and audit provenance gate** — close manager/cashier bypasses and add negative tests.
2. **Financial and inventory control gate** — returns/refunds, stocktake adjustments, and expenses, with atomic ledgers and FIFO.
3. **Reporting reconciliation gate** — customer/supplier statements, return-aware gross profit, expense-aware net profit, and account reconciliation.
4. **Data safety gate** — migration fixture matrix and backup/restore fault-injection/background execution.
5. **Phone acceptance gate** — realistic test data, offline/restart, barcode and performance checks.
6. **Release gate** — permanent signing and update-continuity verification.
7. **Sync gate** — only after explicit architecture/security gates; no cloud relay or hosted data service without owner approval.

## Scope and limits of this audit

This is a source-code and GitHub Actions review of the current branch. It did not inspect a live production database, execute the app on the user's phone, perform penetration testing, verify physical barcode hardware, or prove real-world accounting reconciliation against actual store transactions. Those remain explicit acceptance tasks.


## Remediation update — 2026-11-02

The first corrective batch for the highest-risk authorization and stock-integrity findings has now been implemented on the development branch.

Implemented:
- Product/catalog create and edit, product activation/deactivation, category creation, product import, opening-stock registration, and exchange-rate changes now require an active `ADMIN` user whose forced password rotation is complete.
- Financial-account creation now requires the same manager state and writes an audit entry including its opening balance.
- Manager-only catalog and backup entries are hidden from cashier navigation; product-management and account-creation screens also check the role.
- Product changes now reject tracked-inventory items with zero purchase cost, zero sale price, non-finite opening quantities, opening quantities beyond unit precision, and opening stock on non-inventory items.
- After inventory movement or a non-zero balance exists, changing a product's inventory-tracking flag or unit of measure is rejected.
- Product create/update/deactivation, category creation, and exchange-rate changes write actor-attributed audit records.
- Product-setup opening movements now record the acting user and a stable operation identifier.
- Privileged manager checks in daily-balance controls, supplier-payment correction, and service-transfer manager operations now also require completed password rotation.
- Added negative tests for cashier account/catalog/FX/stock-opening attempts, product stock-tracking/unit safeguards, and account-creation audit provenance.

Verification:
- GitHub Actions run: `41365521758`.
- Head tested: `a2563ca6ec41326ffd0abe8138377e1c688ef338`.
- Android unit-test task: passed.
- Debug APK build: passed.
- APK v1 and v2 signature verification: passed.
- Artifact uploaded as `the-daily-shop-debug-development-only`.
- Permanent release signing was not configured; the release build was skipped. This debug artifact is not an approved in-place release update.
- Main branch remains at `63b233cd4fb3085d0ade05e3e4c1007efcae30ca`.

**Authorization is improved but not complete.** The `permissions`/`role_permissions` tables still do not form a configurable permission matrix. Purchase access, supplier/customer master-data permissions, and broader actor provenance still need an explicit policy and implementation. The return/refund, stock-adjustment, and expense findings above remain open. CI success does not equal device acceptance or production readiness.
