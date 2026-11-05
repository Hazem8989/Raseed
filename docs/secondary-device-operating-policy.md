# Secondary device operating policy

The primary device is the only authority for store master data and non-sales operations.

## Primary device
- Create, import, edit, activate, and deactivate products and categories.
- Maintain units, opening inventory, warehouses, customers, suppliers, and financial accounts.
- Record purchases, expenses, inventory adjustments, wallet operations, and store settings.
- Validate and post sales received from secondary devices, then return an authenticated application acknowledgement.

## Secondary device
- Use the locally replicated catalog to create and submit sales.
- Keep unacknowledged sales in the durable local outbox and retry transmission.
- Access device synchronization controls needed to send queued operations and verify acknowledgements.
- Do not create or edit master data or initiate purchasing and other non-sales financial/inventory workflows.

## Consistency requirements
- A secondary sale is a request, not an authoritative ledger posting.
- The primary remains responsible for stock, FIFO, cash, customer debt, and final invoice status.
- A relay delivery receipt is not a business acknowledgement.
- A sale is marked synchronized only after validating the primary's authenticated application acknowledgement.
- Catalog data must be distributed one-way from primary to secondary; local SQLite IDs must never be treated as shared identifiers.

This policy narrows the product scope. It does not by itself mean that one-way catalog bootstrap/refresh, full sales coverage, or real-device transport testing is complete.
