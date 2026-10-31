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
