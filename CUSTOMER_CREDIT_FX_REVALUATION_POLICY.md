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
