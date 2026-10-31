# FIFO Inventory Costing Policy

Status: Approved business rule; implementation and accounting validation pending.
Approved on: 2026-10-31

## Core rule
- Inventory cost and cost of goods sold (COGS) use FIFO: the oldest available received inventory layer is consumed first.
- A purchase receipt creates one or more cost layers tied to its source purchase invoice and purchase line.
- Each layer retains its received quantity, remaining quantity, original unit purchase cost, currency/reference amount, purchase-time exchange-rate snapshot, receipt date, and source references.
- Selling stock consumes quantities from the oldest eligible layers in order. A sale that spans multiple layers records the exact quantity and cost taken from each layer.
- The current product purchase-cost field is not a substitute for FIFO layer history and must not overwrite historical layer costs.

## Currency and precision
- Preserve the original purchase cost and currency values for every layer.
- Where the purchase invoice uses the agreed USD reference cost, preserve the USD minor-unit unit cost and the invoice-time SYP/USD snapshot used to establish its original SYP value.
- Do not reprice historical inventory layers when the current exchange rate changes.
- Use integer minor units and explicit quantity precision; never use floating-point arithmetic for money.
- Document and test the rounding rule when a layer is partially consumed.
- Profit reports must use the revenue value and the FIFO cost basis consistently, with the source invoice and cost layers traceable.

## Atomicity, reversals, and audit
- FIFO layer consumption, inventory movement, sales invoice posting, and the related cost allocation must succeed or roll back together.
- A retry of the same sale operation must not consume the same layer twice.
- A sale return must restore inventory with the original cost-layer provenance where the returned goods can be identified; otherwise use a separately documented return-cost rule and retain the link to the original sale.
- Purchase invoice corrections, purchase returns, stock adjustments, and damaged/expired stock must create traceable layer adjustments or reversals; they must not silently rewrite consumed historical layers.
- If available layer quantities do not reconcile with the stock balance, block silent negative stock and expose a reconciliation exception.

## Required tests
- A sale consumes the oldest layer first.
- A sale spanning multiple layers consumes the correct quantities and records each layer allocation.
- Partial layer consumption leaves the correct remaining quantity and cost.
- Changing the current exchange rate does not alter historical layer costs.
- Concurrent/retried sale posting cannot double-consume layers.
- Sale returns and purchase corrections preserve provenance and cost.
- Insufficient stock or a failed transaction leaves layer balances, inventory movements, and invoice state unchanged.
- FIFO COGS and profit totals reconcile to the underlying sale allocations.

## Scope note
The current repository contains inventory balances and movements, but this policy does not claim that FIFO cost layers or FIFO COGS have already been implemented. They must be added and tested before profit reports can be treated as FIFO-accurate.
