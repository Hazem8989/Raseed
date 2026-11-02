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


## Remediation update 2 — 2026-11-02 (permission policy and invoice actor enforcement)

This update supersedes the earlier statement that the permission tables were unused. The development branch now has a centralized database-backed role policy.

Implemented:
- Added `PermissionPolicy` and seeded the permission catalogue plus ADMIN/CASHIER role defaults in schema migration **12**. Existing data is preserved; defaults use conflict-ignore so a previously customized role grant is not overwritten by the migration.
- ADMIN receives the full permission catalogue. CASHIER defaults are deliberately limited to sales checkout, customer read/create, and customer debt collection. Purchase management, supplier management/payments, catalog, exchange rate, cash-account administration, daily cash controls, backup, returns, stock adjustment, and expenses are denied by default.
- Added an audited role-level permission setter. It validates an active manager who completed password rotation, rejects unknown permissions, and prevents removing the ADMIN role's baseline grants. **There is not yet an in-app permission-editor screen**; this API and database policy are implemented, but configuration UX remains open.
- Invoice draft creation and posting now require the actor's database-backed permission for both sales and purchases. The API requires the authenticated user ID; callers cannot gain authority by passing a role label.
- Supplier-payment posting and correction require separate database-backed permissions and an actor identity.
- Customer-debt collection requires the customer-collection permission and actor identity.
- Customer create/update/deactivation and supplier creation require their respective permissions. Customer and supplier writes include audit records; customer writes and supplier creation are atomic with their audit entry.
- Catalog management, exchange-rate changes, account administration, daily-cash manager operations, service-transfer manager operations, and user management now use the centralized permission policy or validate the corresponding actor permission.
- Cashier navigation no longer shows purchases or financial reports. Purchase/supplier/report screens also have manager guards.
- Added tests for cashier default grants/denials, manager permission grant/revoke with audit history, and cashier sale access versus purchase-invoice denial.

Verification:
- Latest tested source HEAD: `baadfe794fef57755145ee7b67278526a7ee5309`.
- GitHub Actions run `41368320646`: [successful unit tests and debug build](https://github.com/Hazem8989/Raseed/actions/runs/41368320646).
- `testDebugUnitTest`: passed.
- `assembleDebug`: passed.
- APK signature verification: v1 and v2 passed.
- Artifact `the-daily-shop-debug-development-only`, 9,386,861 bytes; SHA-256 `eae088f283891b2b5ebb1d8995e8ceb4a3085d6a26b84cab448c1fd9ac9586ae`; expires 2026-11-16.
- Permanent release signing remains unconfigured; release build was skipped. **Do not install this debug artifact as an update to a release-signed app, and do not delete app data.**
- Main remains unchanged at `63b233cd4fb3085d0ade05e3e4c1007efcae30ca`.

Remaining authorization work:
- Add an in-app manager screen for role permissions and explain that changes apply to all users with that role.
- Complete actor/permission enforcement for read-only customer, supplier, purchase, account, and report queries where UI gating is currently the primary boundary.
- Review every financial mutation path not yet migrated, including future return, stock-adjustment, and expense workflows. Those new workflows must require actor identity and permission from their first implementation.
- Add repository-level negative tests for each remaining mutation path and read-access boundary.

**Gate decision:** the first authorization batch now passes CI, so it is reasonable to begin the next financial-control phase. It is not reasonable to claim that the entire authorization subsystem is complete or that the app is production-ready. Returns/refunds, inventory adjustments, expenses, migration matrix, backup fault injection, device acceptance, and permanent release signing remain open.


---

## Corrective audit addendum — verified against current branch HEAD 2026-11-02

**Current source HEAD:** `8133188f790dfcc8d650f98dfe0015b9d8ddc4f1`  
**Current database schema:** version **15**.  
**Main branch:** `63b233cd4fb3085d0ade05e3e4c1007efcae30ca`; unchanged.

This addendum supersedes earlier status statements above wherever they conflict with the current source. The original audit snapshot was stale in several areas and understated completed work.

### Corrections verified in source

- The database schema is version 15, not version 11. The migration chain includes versions 12–15 for permission defaults, sales-return schema, permission-default correction, and inventory-adjustment schema.
- A centralized `PermissionPolicy` is implemented and is used by financial and master-data mutation repositories.
- An in-app manager screen for CASHIER role permissions is present in `MainActivity`; the earlier statement that there was no permission-editor screen is no longer correct.
- `SalesReturnRepository` implements customer sales-return posting, including original-invoice/item validation, cumulative return limits, refund versus credit-reduction calculation, FIFO cost provenance, audit/idempotency records, and transaction-scoped posting. Tests cover cash returns, credit returns, repeat posting, later-period reporting, and cashier denial.
- `InventoryAdjustmentRepository` implements atomic positive/negative stock adjustments, explicit cost for increases, FIFO consumption for decreases, actor permission checks, audit/idempotency records, and inventory valuation integration. Tests cover FIFO costing, retries, negative-stock rejection, missing cost, non-inventory products, and cashier denial.
- The above two repositories are **not yet integrated into the app's user interface**. The current `MainActivity` contains no calls/imports for either repository and has no return-posting or stock-adjustment screen. Existing opening-stock entry is not a substitute for ongoing stocktake adjustments.
- Purchase returns are still not implemented. They require a separate supplier-credit/refund ledger and must not reuse customer sales-refund logic.
- No expense ledger, expense repository, or expense workflow is present. Gross profit remains gross trading profit after sales returns and FIFO COGS, not net profit.
- Migration tests currently exercise representative upgrade paths from versions 7, 8, 9, and 13. This is useful coverage but not a complete fixture matrix for every supported historical version.
- The earlier audit's authorization finding is partly remediated: the permission editor exists and core mutation paths use database-backed permissions. Read-access boundaries and every remaining mutation path still require systematic review.

### Current CI and artifact evidence

- Latest source commit: `8133188f790dfcc8d650f98dfe0015b9d8ddc4f1`.
- GitHub Actions run: [41433643515](https://github.com/Hazem8989/Raseed/actions/runs/41433643515), completed successfully at 2026-11-02 11:06 UTC.
- `testDebugUnitTest`: passed.
- Debug APK build, APK signature verification, and artifact upload: passed.
- Artifact: `the-daily-shop-debug-development-only`, 9,438,451 bytes; SHA-256 `64d408c1ada27736b4069fdb997332c4b6c1877c6ae639abd9c75772c01f1d5b`; expires 2026-11-16.
- Permanent release signing is still not configured; the release build was skipped. This artifact is debug-only and is not evidence of safe in-place upgrade to a release-signed installation.
- No physical-device acceptance has been performed.

### Updated execution order

1. Complete end-to-end sales-return UI and connect it to the already-tested repository; test UI validation and financial posting.
2. Add end-to-end stocktake/adjustment UI and connect it to the already-tested adjustment repository.
3. Design and implement purchase returns with a supplier-credit/refund ledger as a separate accounting workflow.
4. Implement operating expenses with actor, account/currency, operating date, references, idempotency, audit, and expense reports; only then add net-profit reporting.
5. Complete authorization/read-boundary review and add negative tests for all remaining financial operations.
6. Expand migration fixtures across all supported versions and add backup/restore fault-injection and background-execution tests.
7. Perform realistic on-device acceptance, then establish permanent release signing and update-continuity verification.
8. Keep multi-device sync deferred until its security/authority/conflict model is implemented and explicitly approved.

No cloud service, network listener, or sync transport is approved by this addendum.

## تحديث التنفيذ — 2026-11-02

أُضيفت إلى واجهة التطبيق على فرع `the-daily-shop-development`:
- شاشة مرتجعات المبيعات: اختيار الفاتورة، إدخال كميات مرتجعة متعددة البنود، معاينة قيمة المرتجع وتخفيض الدين والمبلغ النقدي، وتوزيع الرد على أكثر من حساب بعملته الأصلية.
- شاشة تسويات المخزون: الزيادة بتكلفة وحدة شراء بالدولار، والنقص بتكلفة FIFO المحسوبة من طبقات المخزون، مع عرض سجل التسويات.
- إظهار الشاشتين وفق صلاحيات `RETURNS_PROCESS` و`INVENTORY_ADJUST`، مع بقاء التحقق النهائي داخل المستودعات.
- أُضيف اختبار تكاملي يغطي توزيع رد نقدي واحد بين حساب USD وحساب SYP، والتحقق من أرصدة الحسابين بعد العملية.

**حالة التحقق:** التعديلات محفوظة على فرع التطوير فقط. لم يتوفر في هذه الجلسة تشغيل Gradle أو Workflow جديد على GitHub، لذلك لا يُعد هذا التحديث اجتيازاً للاختبارات بعد. يجب تشغيل `testDebugUnitTest` قبل اعتماد المرحلة، ومراجعة أي فشل وإصلاحه قبل طلب اختبار جهاز فعلي. لم يُطلب تثبيت APK.

### نتيجة الاختبار والاعتماد — 2026-11-02

اكتمل GitHub Actions run رقم 442 على commit `4ec7584d4cc49fded55243e9c5a30b4623d0ac96` بنتيجة **success**:
- Checkout، Java 17، Android SDK، وGradle 8.9: ناجحة.
- `clean testDebugUnitTest`: ناجح.
- بناء `assembleDebug` والتحقق من توقيع APK: ناجح.
- رفع artifact: ناجح.
- بناء Release الموقّع الدائم: **تجاوز (skipped)** لعدم توفر أسرار توقيع Release كاملة في بيئة GitHub.

يشمل هذا التشغيل تغييرات واجهات مرتجعات المبيعات وتسويات المخزون واختبار توزيع الرد على حسابي USD وSYP. لذلك تُعتمد هذه المرحلة **على فرع التطوير بعد اجتياز اختبارات الوحدة والبناء**. لا يعني ذلك اعتماد Release إنتاجي أو إثبات التحديث فوق تثبيت سابق؛ اختبار التثبيت/الترقية على جهاز فعلي ما زال مطلوباً، ولا ينبغي استخدام APK من نوع debug لتحديث نسخة release.
