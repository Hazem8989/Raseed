# Customer Credit and FX Revaluation Implementation Plan

Status: Approved scope; implementation in progress by milestones.

## Existing code facts
- Sales invoice drafts and posting exist in InvoiceRepository.
- Sales invoices capture an exchange-rate snapshot and sales items store product lines.
- The POS checkout is still a placeholder; there is no completed in-app sales payment flow.
- Customers table exists, but the navigation entry for customers/debts is still a placeholder.
- invoice_payments and cash_transactions support SALE records at schema level, but the implemented payment repository currently handles supplier payments only.
- Therefore customer credit cannot yet be reliably created, paid, or viewed through the app. FX revaluation must not be bolted onto a generic customer balance before those flows exist.

## Delivery sequence

### Milestone 1 — Customer records and sales checkout
- Add customer repository and customer list/create/edit flows.
- Complete sales invoice editor/checkout from POS.
- Require cash sales to be fully settled at checkout.
- Allow credit sales only when a customer is selected.
- Preserve invoice-time exchange-rate snapshot and item/discount values.
- Prevent posting a sale with ambiguous payment state.

### Milestone 2 — Customer payments and account ledger
- Add customer payment recording against a specific posted sales invoice.
- Record cash-account inflow and invoice_payments row in one SQLite transaction.
- Support partial and full settlement, USD/SYP accounts, and payment-time exchange-rate snapshots.
- Use operation_registry idempotency and reject overpayment/insufficient data.
- Display each customer's invoices, payments, and outstanding balance.
- Keep supplier payable balances separate from customer receivables.

### Milestone 3 — Credit FX revaluation
- Add sequential database migration; do not delete or recreate existing business data.
- Revalue only the unpaid amount of customer-linked credit merchandise invoices.
- Exclude cash sales, supplier balances, opening balances, and unrelated charges.
- For each invoice, calculate SYP valuation using max(current exchange rate, original invoice exchange rate). This permits decreases from a prior peak but never below the original purchase-time SYP floor.
- Calculate only from unpaid USD-equivalent balance; paid portions are immutable.
- Store each change in a separate revaluation ledger with invoice/customer, previous and new SYP valuation, rate, timestamp, and idempotency key.
- Trigger valuation when opening the customer account and immediately before applying a payment.
- Re-running at an unchanged valuation must create no duplicate adjustment.
- Do not auto-revalue legacy invoices without a reliable original exchange-rate snapshot; surface them for review instead.

## Required tests
- Cash sale is excluded.
- Customer credit invoice with no payment revalues in full.
- Partial payment revalues only the outstanding portion.
- Full payment is never revalued.
- Rate increase raises the unpaid SYP valuation.
- Rate decrease lowers valuation only to the original invoice-rate floor.
- Repeated account opening and payment retries do not duplicate ledger entries or cash movements.
- USD and SYP payments use the exchange-rate snapshot recorded at payment.
- Database upgrade from version 1 preserves existing invoices, suppliers, payments, cash balances, and inventory.

## Release gates
- Unit tests and database migration tests pass.
- Debug APK builds successfully.
- User tests the APK on the Android phone before the milestone is marked complete.
- All work remains on the-daily-shop-development; main is not modified.
