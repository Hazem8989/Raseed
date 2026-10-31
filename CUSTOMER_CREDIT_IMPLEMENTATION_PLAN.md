# Customer Credit and FX Revaluation Implementation Plan

Status: Approved scope. Customer checkout and collection foundations are implemented; FX revaluation remains pending.

## Current implementation facts (reviewed 2026-10-31)
- Customer records and customer list/create/edit/disable flows exist.
- POS supports cash and credit checkout. Credit sales require a customer; cash sales require an active cash account and post the full receipt atomically with invoice posting.
- Customer payments can be allocated to a specific credit invoice, including partial and full payments, with cash-account inflow and payment records in one transaction.
- Automated tests cover partial allocation, idempotent retry, overpayment rejection, and rejection of collection against a cash sale.
- The customer credit FX revaluation policy is approved but its database ledger, repository logic, UI adjustments, and full test suite are NOT implemented.
- The app currently has a local SQLite database. Sync tables exist, but a working synchronization engine/server integration is not yet implemented.

## Milestone 1 — Customer records and sales checkout
Implementation status: implemented in code; APK build and unit tests have passed. User-device acceptance testing remains part of release validation.
- Maintain customer records without deleting historical relationships.
- Require a customer for credit sales.
- Require a selected active cash account for cash sales and post the full receipt atomically.
- Preserve invoice-time exchange-rate snapshots and immutable invoice line details.
- Prevent ambiguous payment states and duplicate posting.

## Milestone 2 — Customer payments and account ledger
Implementation status: repository and UI foundations implemented; full device acceptance and broader account-ledger reconciliation remain.
- Record payments against a specific posted credit invoice.
- Post cash-account inflow and invoice payment allocation in one SQLite transaction.
- Support partial and full settlement, USD/SYP accounts, and payment-time exchange-rate snapshots.
- Use operation idempotency and reject overpayment or invalid account state.
- Display customer invoices, payments, and outstanding amounts.
- Keep supplier payables separate from customer receivables.

## Milestone 3 — Customer credit FX revaluation
Implementation status: policy documented; implementation pending.
- Add a sequential database migration without deleting or recreating business data.
- Revalue only the unpaid merchandise amount of customer-linked credit invoices.
- Exclude cash sales, supplier balances, opening balances, services, and unrelated charges.
- For each eligible invoice, calculate the SYP value using max(current exchange rate, original invoice exchange rate). This allows the debt to decrease from a previous peak, but never below its original sale-date SYP floor.
- Revalue only the unpaid portion; previously paid portions remain fixed.
- Persist payment allocations against invoice balances so unpaid portions are determinable.
- Store every adjustment in a separate immutable, auditable revaluation ledger with invoice/customer, old and new SYP value, applied rate, timestamp, calculation basis, and idempotency key.
- Run revaluation safely when viewing the customer account and immediately before payment allocation; retries or repeated views at an unchanged rate must not duplicate adjustments.
- Keep the original invoice and its original rate snapshot unchanged.
- Do not auto-revalue historical invoices without a reliable original rate snapshot; surface them for review.

## Required tests
- Cash sales, services, fees, and unrelated opening balances are excluded.
- A credit invoice with no payment is revalued in full.
- Partial payment revalues only the outstanding portion; paid portions remain unchanged.
- Fully paid invoices are never revalued.
- A rate increase raises the unpaid SYP valuation.
- A rate decrease lowers the valuation only to the original invoice-rate floor, not to a previous peak.
- Repeated account opening and payment retries do not duplicate ledger entries or cash movements.
- USD and SYP payments use the exchange-rate snapshot recorded at payment.
- Legacy invoices without a reliable rate snapshot are flagged and not silently changed.
- Database migration preserves existing invoices, suppliers, payments, cash balances, and inventory.

## Release gates
- Unit tests and database migration tests pass.
- Debug APK builds successfully and the artifact is verified.
- User tests the APK on the Android phone before the milestone is marked complete.
- All work remains on the-daily-shop-development; main must not be modified.


## Final collection-currency clarification (approved 2026-10-31)

This clarification supersedes any earlier wording in this plan that implied customer credit invoices may be collected through either USD or SYP accounts.

- Customer credit collections must be received in **Syrian pounds (SYP) only**. The collection UI must not offer a USD cash account for settling customer credit.
- At credit-sale posting, preserve the invoice's original SYP amount and original sale-time exchange-rate snapshot, and preserve its USD reference amount calculated from that snapshot.
- The outstanding debt is tracked against that USD reference. Its SYP valuation is calculated using the greater of the current exchange rate and the original invoice rate. A rise in the rate therefore increases the SYP amount due; a fall never takes the SYP value below the original sale-time SYP floor for the remaining eligible balance.
- For a SYP collection, convert the amount received into the USD reference reduction using the **effective collection rate = max(current collection-time rate, original invoice rate)**. This is essential: when the current rate is below the original rate, using the lower current rate would over-reduce the USD reference and violate the original SYP floor.
- Example: a 1,500 SYP credit balance posted at 150 SYP/USD creates a $10 reference debt. At 200 SYP/USD, 1,000 SYP collected reduces the reference by $5, leaving $5 (valued at 1,000 SYP). At 100 SYP/USD, 750 SYP collected reduces the reference by $5 using the original 150 rate, leaving $5 (valued at the 750 SYP original-rate floor).
- Persist the SYP amount received, collection-time rate, original invoice rate, effective collection rate, USD reference reduction, invoice allocation, account movement, timestamp, actor, and idempotency key. Use integer minor units and explicit rounding; never floating-point money.
- For a customer with multiple eligible credit invoices, allocate each SYP payment to selected invoice(s) and calculate the USD reduction per invoice using that invoice's own original rate. Do not apply one blended rate across invoices with different snapshots.
- Partial collections reduce only the allocated unpaid reference balance; prior payments remain immutable. Revaluation must never alter the original invoice or duplicate on account viewing/retry.
- Legacy invoices without a trustworthy original exchange-rate snapshot remain excluded from automatic revaluation and collection conversion under this rule until safely reviewed.

Implementation remains pending: database/repository logic, SYP-only collection UI, migration/backfill safeguards, account statement display, and automated tests must be completed before this milestone is considered implemented.


## Customer debt collection destination and daily cash treatment (approved 2026-10-31)

- A customer credit collection is a receipt of an old receivable, not a new sale. It must never create a new sales invoice or inflate today's sales/revenue/profit totals.
- If the customer pays physical cash in SYP, post the receipt automatically to the **current day's designated primary daily cash drawer**. It must increase that drawer's actual balance and appear in today's daily cash receipts/closing totals, even when the debt originated from an older invoice.
- The primary daily cash drawer is a specifically designated active SYP cash account for the current operating day. It is distinct from any carried-over/rolled cash box used for supplier-payment and purchase/expense balance workflows. Never select a carried-over supplier-payment box as the default destination for customer cash collections.
- The application must provide a reliable configuration/identity for the primary daily cash drawer and the current operating day. If no valid primary drawer is configured, block cash collection with a clear instruction to configure it; do not silently post to another account.
- If the customer pays electronically, the operator explicitly selects the receiving active electronic wallet. Increase that wallet's balance by the SYP amount received; do not post it to the daily physical-cash drawer.
- The collection screen should make the destination clear: cash = current primary daily drawer (shown, not arbitrarily replaced by the rollover box); electronic = selected wallet. USD collection remains unavailable.
- Every receipt must create one linked, idempotent financial movement and one customer-payment allocation atomically. The statement must show actual collection date/time, customer, invoice allocation(s), SYP amount, collection channel, receiving drawer/wallet, applicable FX snapshots and USD-reference reduction.
- Daily cash report/closing must include cash collections of old customer debts as today's cash inflows, separately categorized as **customer debt collections**, not sales. Electronic wallet collections appear in that wallet's ledger and balance, not in physical-cash totals.
- Supplier-payment rollover balances remain a separate workflow and must not be increased by customer collections unless a distinct, explicitly approved transfer is recorded.
- Corrections/refunds must reverse or adjust the original linked movement with an auditable record; never delete the collection or silently rewrite the daily close.

Required tests:
- Cash collection increases the configured current-day primary SYP drawer and today's cash-in total exactly once.
- Cash collection does not increase sales count, sales revenue, or sales profit.
- A previous-day/rollover supplier-payment box is not selected as the collection destination.
- Electronic collection increases only the selected wallet and appears in its statement, not the physical cash drawer.
- Missing/inactive primary drawer blocks cash collection safely.
- Collection, payment allocation, account movement, and audit record commit or roll back together; retries do not duplicate any of them.
- Daily close reconciles opening balance + today's cash sales receipts + today's customer debt cash collections + other classified cash inflows - outflows.
