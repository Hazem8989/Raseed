# Customer Credit FX Revaluation Policy

Status: Approved business rule; implementation pending.

## Scope
- This rule applies only to merchandise sold on credit to a customer.
- It does not apply to cash sales, services, fees, unrelated opening balances, or supplier balances.
- A customer having an outstanding balance does not by itself make every balance eligible: the underlying sale must be identified as a credit sale.

## Agreed exchange-rate behavior
- Preserve the original invoice, its item quantities, original prices, and the exchange rate captured when the credit sale was made.
- Revalue only the unpaid portion of an eligible credit sale using the current exchange rate.
- If the current exchange rate rises above the original sale rate, the SYP value of the unpaid merchandise increases accordingly.
- If the current exchange rate falls, the SYP value may decrease, but never below the original SYP value of that unpaid merchandise at the exchange rate captured on the sale date.
- Therefore the original purchase-time SYP value is the floor; the debt is not permanently locked to the highest rate reached.
- If no payment has been made, the entire eligible invoice balance is revalued. If partial payments have been made, only the remaining unpaid balance is revalued; past payments remain fixed and are not recalculated.

## Accounting and audit requirements
- Do not rewrite historical invoice lines, original prices, or the exchange-rate snapshot.
- Record every revaluation as a separate, traceable customer-account adjustment with the old value, new value, applied rate, timestamp, source invoice, and calculation basis.
- Repeated opening of a customer account or retrying settlement must not apply the same revaluation twice.
- Revaluation and payment posting must be atomic and idempotent.
- The customer account must show original credit value, revaluation increases/decreases, payments, and current remaining balance distinctly.
- Use exact integer minor-unit arithmetic and a documented rounding rule; never use floating-point money calculations.
- Do not revalue amounts already paid.

## Implementation safeguards
- Determine and persist payment allocation against eligible credit invoice balances so only the unpaid portion is recalculated.
- If the data model cannot reliably distinguish credit from cash sales, or cannot identify the unpaid portion, do not guess or silently revalue; add the required explicit state and migration first.
- Existing historical invoices without a reliable original exchange-rate snapshot must not be revalued automatically until a safe migration/backfill rule is approved.
- This feature is not implemented merely by this policy document. It requires database migration, repository logic, customer-account UI, and automated tests.


## Final clarification: SYP-only collection and USD reference (approved 2026-10-31)

This section is the final approved rule and supersedes any earlier ambiguity about the currency accepted when collecting customer credit.

1. **Collection currency:** customer credit is collected in SYP only. Do not offer USD accounts as a payment method for customer credit settlement.
2. **Debt reference:** at the original credit sale, retain the invoice's SYP value and original sale-time FX snapshot, and establish the corresponding USD reference amount. The original invoice and snapshot remain immutable.
3. **Outstanding valuation:** for each invoice, value the remaining unpaid USD reference using `max(current_rate, original_invoice_rate)`. If the rate rises, the SYP debt rises. If it falls, the remaining debt's SYP valuation cannot fall below the original-rate SYP floor for that remaining balance.
4. **Converting a SYP payment into a reference reduction:** use `effective_collection_rate = max(collection_time_rate, original_invoice_rate)`; then convert the received SYP amount to its USD-reference reduction at that effective rate. This rule is mandatory to preserve the original SYP floor when the market rate falls below the sale-time rate.
5. **Per-invoice allocation:** if one payment is allocated across multiple invoices, calculate each allocation separately using that invoice's own original rate. Never use a blended exchange rate across invoices.
6. **Audit record:** retain SYP amount received, collection-time rate, original invoice rate, effective collection rate, USD-reference reduction, invoice allocation, cash-account transaction, timestamp, actor, and idempotency key. Use integer minor-unit arithmetic with explicit rounding.
7. **Partial payment:** only the allocated unpaid reference amount is reduced; previous payments are immutable. Revaluation is applied only to the unpaid merchandise balance and must be idempotent.
8. **Legacy protection:** invoices without a reliable original FX snapshot are not automatically revalued or converted under this rule until safely reviewed.

### Worked examples

- Original debt: 1,500 SYP at 150 SYP/USD = $10 reference.
- If the rate rises to 200 and the customer pays 1,000 SYP: effective rate is 200, the reference reduction is $5, and $5 remains, valued at 1,000 SYP.
- If the rate falls to 100 and the customer pays 750 SYP: effective rate remains the original 150, the reference reduction is $5, and $5 remains, valued at the 750 SYP original-rate floor.

Status remains **approved business rule; implementation pending**. This document does not mean the feature is already available in the APK.


## Receiving account and daily cash classification (approved 2026-10-31)

- Customer debt collection is a receivable settlement, not a new sale; it must not create a sales invoice or count as today's sales revenue/profit.
- SYP physical-cash collections post by default to the configured **current-day primary cash drawer** and count as today's cash inflow/customer-debt-collection category. The primary daily drawer must be distinct from a carried-over cash box used for supplier payments. If no valid primary drawer exists, block the cash collection rather than choosing another account silently.
- Electronic SYP collections require explicit selection of the receiving active wallet and increase only that wallet's balance. They must appear in that wallet's statement and not in physical-cash totals.
- Keep the supplier-payment rollover workflow separate. Do not route customer collections into it or merge its balance with the daily drawer automatically.
- The immutable collection record and account movement must include the actual collection timestamp, customer, invoice allocation(s), SYP amount, channel, destination account, FX details, USD-reference reduction, and idempotency key. Daily reports classify the receipt as customer debt collection, not a sale.
