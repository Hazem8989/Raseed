# The Daily Shop — Synchronization Event Coverage Matrix

**Date:** 2026-11-03  
**Branch:** `the-daily-shop-development`  
**Base code commit audited:** `063f5bec9e3b3053f4296d9962b0d83fb85c6923`

This inventory supplements `SYNC_PROTOCOL_AUDIT_2026-11-03.md`. It records repository-level hooks found during source inspection; it is not a claim that the listed events can already be transmitted or applied remotely.

## Existing primary-side outbox hooks found

| Business operation | Repository | Outbox behavior found |
|---|---|---|
| Sales invoice posting | InvoiceRepository | Enqueues invoice header, lines, and cash receipt snapshot inside posting transaction |
| Purchase invoice posting | InvoiceRepository | Enqueues invoice header and lines inside posting transaction |
| Customer credit collection | CustomerPaymentRepository | Enqueues payment, cash transaction, invoice and FX allocation data inside transaction |
| Supplier payment | PurchasePaymentRepository | Enqueues payment, cash transaction, invoice and FX snapshot inside transaction |
| Sales return | SalesReturnRepository | Enqueues return totals, credit reduction, cash refund and returned COGS |
| Purchase return | PurchaseReturnRepository | Enqueues supplier-return operation |
| Inventory adjustment | InventoryAdjustmentRepository | Enqueues adjustment operation |
| Operating expense | OperatingExpenseRepository | Enqueues expense and cash transaction references inside transaction |

The associated tests include primary-side atomic outbox checks for invoice posting, customer collections, supplier payments, returns, inventory adjustments, and operating expenses. These are local transactional tests, not two-device delivery tests.

## Write paths with no outbox hook found in the inspected source

| Business operation | Repository | Why it matters |
|---|---|---|
| Product create/update/activation and import | StoreRepository | Secondary catalog divergence; imported products and stock opening must be consistent |
| Category and exchange-rate changes | StoreRepository | Master-data and historical calculation context |
| Opening stock registration | StoreRepository | Inventory quantity and cost basis must be authoritative |
| Customer create/update/activation | CustomerRepository | Party identity and invoice references |
| Supplier creation | SupplierRepository | Supplier identity and purchase/payment references |
| Cash-account create/details and daily drawer selection | CashAccountRepository | Account identity, opening balances and posting destination |
| Supplier cash rollover | DailyBalanceRepository | Daily cash allocation state |
| Wallet opening balance | DailyBalanceRepository | Electronic wallet opening state |
| Cash box to cash box transfer | DailyBalanceRepository | Two-sided cash ledger movement |
| Cash box to wallet transfer/top-up | DailyBalanceRepository | Two-sided cash and wallet ledger movement |
| Wallet to primary drawer transfer | DailyBalanceRepository | Cash receipt reconciliation |
| Personal withdrawal | DailyBalanceRepository | Cash outflow and owner withdrawal ledger |
| Service wallet opening/recharge | ServiceTransferRepository | Service float balance and linked cash movement |
| Service transfer/reversal | ServiceTransferRepository | Revenue, fees, float, cash receipt and reversal provenance |

The inspected implementations above contain local SQLite writes and, in several cases, audit/idempotency logic, but no call to the sync outbox API.

## Event-contract requirements before enabling sync

For each event family, define and test:
- stable globally unique operation ID and entity ID;
- immutable canonical payload with all financial snapshots required for replay;
- originating device, actor identity mapping, and authorization re-check on the primary;
- explicit CREATE/UPDATE/DELETE or command semantics;
- expected base revision and conflict policy;
- idempotent primary-side application and durable acknowledgement;
- complete accounting side effects (cash, wallet, inventory, debt, supplier payable, FIFO, audit);
- reversal/correction behavior without deleting posted history.

## Exclusions and safety

- User credentials, password hashes, local session tokens, and device secrets must never be synchronized as business entities.
- Do not broadcast local-only UI preferences or raw SQLite files.
- A financial action on a secondary must remain visibly pending until the primary accepts and commits it.
- No operation may be marked centrally committed merely because it entered a local outbox.
