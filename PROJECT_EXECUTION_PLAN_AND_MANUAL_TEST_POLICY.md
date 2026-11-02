# The Daily Shop — Execution Plan and Manual-Test Policy

**Repository:** Hazem8989/Raseed  
**Working branch:** the-daily-shop-development  
**Protected branch:** main (do not modify or merge without explicit owner approval)  
**Owner's operating instruction (2026-11-02):** No manual in-app operations or live business data entry during development. The owner will perform one coordinated acceptance pass only after the complete application is ready. The final acceptance APK must be a new release-signed build. The owner will then re-import the product catalog and enter opening stock once, immediately before live operation.

## 1. Confirmed baseline

- Current branch baseline at plan creation: b85e92c1babd88c2d2cdb1066b1cd436088ad8e9.
- SQLite schema version: 15.
- Product CSV import was successfully completed on the owner's phone. Exchange rate must be configured before importing SYP-priced products.
- Sales-return and inventory-adjustment repositories and UI screens exist; their automated build/test evidence is recorded in the 2026-11-02 audit update. They still need consolidated acceptance coverage; no owner-side live operations are requested now.
- CI run 41453706402 completed successfully, including unit tests and a permanently signed release APK.
- Current code includes backup/restore, role permissions, FIFO, sales/purchases, customer collections, supplier payments, and preliminary sync tables/device identity.
- No live synchronization transport or device-pairing engine is implemented.
- Expense workflow and purchase-return workflow are not implemented.
- The owner has explicitly deferred all manual in-app testing until the application is complete.

## 2. Development gates and order

### Gate A — Complete accounting and inventory workflows
1. Design and implement purchase returns as a separate supplier-return workflow; do not reuse customer sales-return accounting.
2. Implement operating expenses: expense categories, actor/permission, payment account and currency, operating date, reference/notes, idempotency, audit log, and atomic account-ledger posting.
3. Reconcile sales returns, purchase returns, inventory movements, FIFO layers, supplier/customer balances, cash accounts, and daily cash.
4. Add negative authorization tests and idempotency/retry tests for every new posting path.

**Exit evidence:** unit/integration tests pass; every posting is atomic; retries cannot duplicate financial effects; accounting invariants are asserted.

### Gate B — Complete financial reporting
1. Customer and supplier statements with opening/closing balances and transaction detail.
2. Return-aware gross profit and FIFO COGS reconciliation.
3. Expense-aware net operating result; distinguish gross profit, operating expenses, and net result.
4. Account/cash/wallet reconciliation and inventory valuation with explicit incompleteness warnings.
5. PDF and spreadsheet exports with date, currency, and filter consistency.

**Exit evidence:** report totals reconcile against underlying ledgers using deterministic test fixtures.

### Gate C — Authorization, audit, and data safety
1. Review every repository read and write path; UI hiding is never the sole security boundary.
2. Ensure actor attribution and before/after audit evidence for privileged master-data and financial changes.
3. Add historical database migration fixtures for every supported schema version (not only selected versions).
4. Move large backup/restore operations to a controlled background execution path with progress and lifecycle handling.
5. Add backup/restore failure-injection tests covering staging, validation, replacement, rollback, and recovery.

**Exit evidence:** permission-denial matrix, migration matrix, backup fault-injection suite, all green in CI.

### Gate D — Synchronization design implementation
Follow the owner-approved primary-phone architecture. The primary phone remains the sole authority for final financial and inventory posting. Do not introduce a hosted database, cloud storage, cloud API, relay, VPN host, or third-party business-data service without explicit owner approval.

1. Complete local sync schema: store/device identities, enrollment credentials, operation envelopes, payload hashes, authoritative sequence, outbox/inbox, acknowledgements, cursors, and durable conflict records.
2. Atomically write each supported local business action and its outbox event.
3. Implement idempotent primary-side validation and replay before enabling any network listener.
4. Implement owner-approved pairing/revocation and user/device/permission binding.
5. Implement authenticated encrypted local-network transport first.
6. Measure remote direct-connectivity feasibility over Wi-Fi, ADSL, and mobile networks; do not assume CGNAT/NAT traversal will work.
7. If remote reachability requires a relay or hosted component, stop and obtain explicit owner approval before adding one.

**Exit evidence:** protocol, security review, replay/retry tests, and transport tests. No false “synced” status for pending operations.

### Gate E — Final internal verification and release candidate
1. Run full CI tests and release build from the development branch.
2. Verify permanent signing certificate continuity with the previously installed release.
3. Verify upgrade path, database preservation, backup/restore, and release artifact integrity.
4. Review POS speed/usability, navigation, error handling, accessibility, and device performance using automated/static checks and available test environments.
5. Freeze the candidate; do not ask the owner to perform partial tests or enter real stock.

**Exit evidence:** release workflow success, signing continuity, upgrade/migration tests, full test report, and explicit list of any limitations.

### Gate F — One coordinated owner acceptance pass
Only after Gates A–E pass, provide one new release-signed APK and a single organized test checklist. The owner will then:
1. Install/update the new APK without clearing app data.
2. Verify login, primary device setup, and device pairing.
3. Perform the agreed end-to-end acceptance checks using controlled test records (not live business stock).
4. Confirm sync behavior and backup/restore.
5. If acceptance passes, clear only test records through a designed safe reset procedure if one exists; never ask the owner to delete app data as a workaround.
6. Re-import the product catalog into the accepted final version.
7. Set the current exchange rate, verify product prices, and enter opening inventory exactly once.
8. Begin live business operation.

**Do not request manual testing before Gate F.** If an exceptional issue requires owner action earlier (for example, a device-specific defect that cannot be reproduced in CI), ask for permission first, explain why it is essential, and request only a non-destructive diagnostic check—not sales, purchases, stocktake, or real accounting entries.

## 3. Manual-test notification rule

The owner should receive a manual-test request only when:
- the full feature set and synchronization implementation are complete;
- CI and automated accounting/security/data-safety suites pass;
- a new release-signed APK is available;
- update/signature continuity is verified;
- the exact test steps and expected results are prepared;
- no real opening inventory or business operation is required during the acceptance pass.

Until then, continue development and automated testing on the development branch. Do not ask the owner to install intermediate APKs or conduct repeated partial tests.

## 4. Current outstanding work

- Purchase returns: not implemented.
- Operating expenses and net-profit calculation: not implemented.
- Financial statements/report exports: require completion and reconciliation.
- Complete repository-level authorization and audit review: outstanding.
- Full historical migration test matrix: outstanding.
- Backup/restore background execution and fault injection: outstanding.
- Full sync core, enrollment, transport, conflict handling, and multi-device acceptance: outstanding.
- Consolidated owner acceptance and final catalog re-import/opening stock: deliberately deferred.

## 5. Status reporting convention

For every phase, report separately:
- Implemented in source.
- Automated tests passed/failed (with workflow URL and commit).
- Release artifact status/signing.
- Requires owner manual acceptance (yes/no).
- Known limitations and next gate.

Never describe a feature as device-tested unless the owner has actually tested it or a real device test has been performed and evidenced.
