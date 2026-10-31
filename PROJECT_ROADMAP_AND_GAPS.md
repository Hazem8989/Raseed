# The Daily Shop — Project Decisions, Status, and Remaining Work

Last reviewed: 2026-10-31
Development branch: the-daily-shop-development
Main branch is protected from this work and must not be modified.

## 1. Confirmed product and accounting decisions

### Inventory costing
- FIFO is the approved inventory valuation and cost-of-goods-sold method.
- Consume the oldest available purchase cost layer first.
- Preserve each receipt's original cost, currency, invoice-time exchange rate, quantity, and source references.
- Historical cost layers must not change when the current exchange rate changes.
- FIFO layer accounting is not yet implemented merely because inventory balances and movements exist. See FIFO_INVENTORY_COSTING_POLICY.md.

### Warehouses
- Operate with one default warehouse for the current phase.
- POS must use that default warehouse consistently; do not ask the cashier to select a warehouse for every sale.
- Keep warehouse identifiers and stock movement design compatible with multiple warehouses later.
- Do not introduce multi-warehouse UI or transfer workflows in the current phase unless separately approved.

### Offline-first and synchronization
- The app must remain fully usable for supported local workflows while offline.
- Prepare the synchronization architecture now; do not defer the data model and operation semantics until a later release.
- A synchronization-ready local architecture is not the same as a live cloud sync service. Server deployment/provider and multi-device rollout remain separate decisions.
- Use stable globally unique entity and operation IDs, an outbox/sync queue, retry-safe idempotency, per-entity versions or equivalent concurrency tokens, sync cursors, and explicit conflict records.
- Keep local business transactions atomic. A committed local operation should enqueue its sync event in the same local database transaction.
- Define deterministic conflict policies by entity type. Never silently overwrite financial, invoice, inventory, or audit history.
- Support retry with backoff, resumable sync, duplicate delivery, partial failure, and device clock differences. Prefer server-issued ordering/versions where available; local timestamps alone must not decide financial conflicts.
- Keep credentials and server secrets out of the client app. Plan authenticated transport and authorization before enabling remote sync.
- Sync must preserve operation provenance, actor/device identity, and audit history.

### Sales and exchange-rate rules
- Product sale price in USD is the manually maintained reference price.
- SYP sale price is derived from the USD reference price and the current exchange rate.
- Changing the current rate updates derived SYP sale prices without changing the USD reference price or historical invoices.
- Cash sale and credit sale are distinct. Cash checkout requires a selected active cash account and full receipt; credit checkout requires a customer.
- Customer credit FX revaluation applies only to the unpaid eligible credit merchandise balance, with the original sale-time SYP value as the floor. It does not apply to supplier balances.

### Supplier payable rules
- Supplier invoice SYP liability is fixed at the purchase invoice's saved exchange rate.
- Supplier outstanding SYP balance does not float with the current exchange rate.
- USD supplier payments reduce the fixed SYP liability using the original purchase invoice rate; historical payment snapshots remain preserved.
- Old purchase invoices without a reliable saved rate must not be guessed or silently repriced.

### Audit, corrections, and data safety
- Do not delete posted invoices or historical financial movements to correct mistakes.
- Posted invoice correction is a manager-only audited process with reason, before/after versions, inventory adjustments, and financial settlement handling.
- Do not enable posted invoice editing until authentication and authorization are production-ready and all linked financial/inventory effects can be safely reconciled.
- Preserve existing business data during every database migration. Use sequential migrations and migration tests.

## 2. Current implementation snapshot

This section distinguishes code present from features that are merely planned. Build success and unit tests do not replace user acceptance testing on the Android device.

### Implemented foundations (code present; some require device acceptance/reconciliation)
- Product/category/unit management and product activation/deactivation.
- Inventory balances and inventory movement records.
- USD reference sale pricing and derived SYP pricing.
- Purchase invoice creation/posting and stock receipt.
- Supplier records and supplier payment flows, including partial/multiple payments and manager correction logic.
- Cash accounts/wallets, opening balances, and linked cash transactions.
- POS cart and cash/credit checkout.
- Customer records and credit-sale association.
- Customer payment allocation to a specific credit invoice, including partial/full collection.
- Operation idempotency foundations and audit-related tables.
- Local SQLite database and a debug APK CI workflow.

### Implemented but still needs broader verification
- POS cash receipt and customer collection reconciliation across multiple invoices and currencies.
- Supplier summaries and detailed account statement reconciliation.
- Manager-only actions across all screens and repository entry points.
- Legacy data migration and recovery behavior on an existing installed app.
- Default warehouse consistency throughout POS, purchase posting, stock movements, and inventory displays.
- Actual offline operation and resilience after app termination or device restart.

### Approved but not yet implemented
- FIFO cost layers and FIFO COGS/profit calculations.
- Customer credit FX revaluation ledger and account display.
- Full supplier and customer running statements with all adjustments.
- Daily purchase/expense/personal-withdrawal balance workflow.
- Cash transfers between accounts, deposits, withdrawals, and operating/personal expenses.
- Complete returns/refunds and payment-provider fee handling.
- Posted invoice correction/version history with safe inventory and financial reversals.
- Fine-grained user/role/permission management and production login/security lifecycle.
- Barcode stocktaking and reconciliation of physical versus recorded stock.
- Complete sales, profit, inventory valuation, customer, supplier, and cash-account reports.
- PDF and Excel exports.
- Backup/restore and data integrity verification.
- Sync engine, remote API/server integration, device enrollment, and conflict resolution UI. The architecture must be prepared now; live server sync remains pending.
- Production release signing, distribution, and final end-to-end acceptance.

## 3. Prioritized remaining-work checklist

### Gate A — Verify the current baseline on the phone
- [ ] Install the latest verified development APK.
- [ ] Confirm navigation closes after choosing a destination.
- [ ] Test cash sale in USD and SYP and reconcile invoice, stock, payment, and cash account.
- [ ] Test credit sale and customer account balance.
- [ ] Test partial and full customer collection.
- [ ] Test purchase posting and supplier partial/final payments in SYP and USD.
- [ ] Test supplier payment correction as manager and rejection as cashier.
- [ ] Verify account balances and supplier/customer summaries against transaction details.
- [ ] Test restart/offline behavior and ensure no duplicate or lost operations.

### Gate B — Accounting correctness
- [ ] Implement FIFO inventory cost layers and COGS.
- [ ] Implement customer credit FX revaluation according to its approved policy.
- [ ] Complete customer and supplier running statements.
- [ ] Implement returns/refunds and provider-fee rules.
- [ ] Implement daily purchase/expense/personal-withdrawal balance workflow.
- [ ] Implement account transfers, deposits, withdrawals, and cash reconciliation.
- [ ] Implement manager-only posted invoice correction with immutable version/audit trail.

### Gate C — Administration and operational controls
- [ ] Replace the temporary initial admin credential and provide secure password change/reset flows.
- [ ] Complete user creation, activation/deactivation, and role assignment.
- [ ] Enforce granular permissions in the data layer, not only by hiding UI controls.
- [ ] Complete audit log review and exception handling.
- [ ] Add barcode stocktaking and stock discrepancy workflows.
- [ ] Verify one-default-warehouse behavior and future multi-warehouse compatibility.

### Gate D — Reporting, resilience, and sync
- [ ] Complete sales, margin/profit, FIFO stock valuation, and cash reports.
- [ ] Add PDF and Excel exports.
- [ ] Implement and test backup/restore.
- [ ] Build sync-ready outbox, stable IDs, versions, cursors, retries, and conflict records now.
- [ ] Add sync conflict review/resolution and safe replay tests.
- [ ] Integrate an authenticated server only after provider/deployment decisions and security review.
- [ ] Test offline-to-online reconciliation across app restarts and duplicate deliveries.

### Gate E — Release readiness
- [ ] All unit, repository, migration, and end-to-end tests pass.
- [ ] Debug/release builds and artifact integrity are verified.
- [ ] Production signing and secure configuration are complete.
- [ ] Full phone acceptance is completed with test data.
- [ ] Backup/restore and recovery are demonstrated before official data entry.

## 4. Non-negotiable development safeguards
- Work only on the-daily-shop-development; never modify main without explicit approval.
- Read existing files before editing and verify every changed file after commit.
- Never remove historical business records to make a test pass.
- Use integer minor units for money and explicit quantity precision.
- Keep financial posting, stock movements, payment allocations, audit entries, and local sync enqueue atomic where they belong to one operation.
- Use idempotency keys for all retryable financial and stock operations.
- Do not claim a feature is complete based only on a schema table, UI element, documentation, or successful APK build.
- A milestone is complete only after code, automated tests, verified APK, and required user-device acceptance.


### Customer credit collection — final currency rule (approved 2026-10-31)

- Customer credit invoices are collected in **SYP only**; the customer-credit collection UI must not offer USD payment accounts.
- Each credit invoice retains its original sale-time SYP value and FX snapshot, plus its USD reference amount.
- Outstanding SYP valuation uses `max(current exchange rate, original invoice exchange rate)`: increases in the rate increase the SYP debt; decreases never take it below the original-rate floor for the remaining balance.
- A SYP payment reduces the USD reference using `max(collection-time rate, original invoice rate)`, calculated separately for each invoice allocation. This prevents a below-original exchange rate from reducing the USD debt by too much and breaching the SYP floor.
- Preserve the SYP received, collection-time/original/effective rates, USD reference reduction, invoice allocation, and linked cash movement in an immutable idempotent audit trail.
- This is an approved specification only. SYP-only collection conversion, revaluation ledger, UI, migration safeguards, and tests remain pending implementation.
