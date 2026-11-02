# Returns and stock adjustments — implementation design

**Date:** 2026-11-02  
**Branch:** `the-daily-shop-development`  
**Starting source HEAD:** `baadfe794fef57755145ee7b67278526a7ee5309`

## Delivery order

1. Sales returns and customer refunds/credit reductions.
2. Purchase returns and supplier credits/refunds (separate accounting path).
3. Inventory count and adjustment postings.
4. Reconcile reports, FIFO valuation, migrations, and negative tests.

Purchase returns are deliberately not implemented by reusing sales-refund logic. The current supplier balance model is based on purchase invoices and supplier payments; it needs an explicit supplier-credit/refund ledger before purchase returns can be posted safely.

## Sales-return rules

- A return must reference a posted/paid/partially-paid original sales invoice and its exact original sales item.
- Cumulative returned quantity for an original sales item may never exceed the original sold quantity. Drafts do not reserve quantity; the posting transaction rechecks the remaining quantity atomically.
- Return value is derived from the original line price and a proportional share of the original invoice discount. Cumulative rounding is used so that returning the full line returns exactly its original net line value; users cannot type an arbitrary refund price.
- Returned tracked goods re-enter the same warehouse as the original sale. Non-inventory items can be refunded but do not create stock movements.
- Returned stock receives FIFO layers based on the original sale's recorded FIFO cost allocations, not current product cost or current selling price.
- Cost allocations for returns point back to original sale cost allocations and track cumulative quantity returned from each allocation.
- For credit invoices, the return reduces the invoice's outstanding USD reference first. Only any amount exceeding the outstanding debt is refunded in cash/wallet. For cash invoices, the return value is refundable.
- The operator chooses the account distribution for the actual cash refund. The sum of allocations must exactly equal the cash-refund portion using the original invoice FX snapshot; no implicit company fee is invented.
- Refund account outflows, return header/items, inventory movements/FIFO layers, customer-credit reduction, operation registry, and audit record commit in one SQLite transaction.
- All posting requires `returns.process`, a valid active actor, a unique operation ID, payload hash validation, and retry-safe idempotency.
- Sales gross-profit reporting must become return-aware: subtract returned revenue and returned COGS on the return operating date. Customer receivable calculations must subtract posted return credit before accepting further collections/revaluing balances.

## Purchase returns — separate second slice

- Must validate original purchase item and cumulative returned quantity.
- Must remove the source purchase quantity from its identifiable remaining FIFO layer(s), not blindly debit unrelated current stock.
- Must record a supplier credit note and optionally a supplier cash refund received into selected accounts.
- Supplier statement/payment-state calculations must include credits without rewriting the original purchase invoice or prior payment history.
- Must use its own settlement-allocation table because supplier refunds are cash inflows or payable reductions, unlike customer cash refunds.

## Stock adjustments

- Manager-only; requires `inventory.adjust` and an active user who completed password rotation.
- A stocktake/adjustment has a stable ID, operation ID, operating date, warehouse, reason, note, actor, and immutable posted status.
- Every line records system quantity, counted quantity, delta, and the chosen reason (surplus, shortage, damage, loss, expired, or correction).
- Positive tracked-stock adjustments require an explicit positive USD unit cost; that cost creates a FIFO layer.
- Negative adjustments cannot exceed on-hand stock or available FIFO layers. They consume FIFO layers and persist per-layer cost allocations; unknown-cost layers remain explicitly unvalued rather than silently becoming zero cost.
- Quantity balance, movement ledger, FIFO layers/allocations, cost total, operation registry, and audit entry commit atomically.
- Adjustment losses are reported separately from gross sales COGS; they must not be disguised as sales or collections.

## Required test matrix

- Full and partial sale returns; multiple returns against one line; over-return rejected.
- Discounted invoice and fractional quantity cumulative rounding; full return equals original net line amount exactly.
- FIFO return across multiple original sale cost layers; returned COGS matches source allocations; subsequent sale consumes the returned layers in correct order.
- Cash sale refund split across multiple accounts/currencies; exact reconciliation; insufficient balance and inactive account rejection.
- Credit-sale return first reduces receivable; excess becomes refund; future collection cannot exceed remaining receivable.
- Retry same operation ID/payload is harmless; same ID with changed payload is rejected.
- Cashier denied; manager allowed; actor and audit provenance retained.
- Stock surplus, shortage, damage, fractional quantities, insufficient stock, uncosted layer, duplicate operation, and ledger/balance reconciliation.
- Database migration from version 12 preserves prior data and passes SQLite integrity and foreign-key checks.
- Gross profit and inventory valuation reconcile before and after returns/adjustments.

## Safety boundaries

- No cloud service, network listener, sync transport, or external financial API is introduced.
- No existing invoice, payment, movement, or audit history is deleted or rewritten.
- No APK is offered as a production update until permanent signing and phone backup/restore acceptance are complete.
