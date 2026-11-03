# The Daily Shop — Execution Tracker
Date: 2026-11-03
Development branch: `the-daily-shop-development`
Protected main snapshot: `63b233cd4fb3085d0ade05e3e4c1007efcae30ca`

This tracker records verified implementation state. A completed unit test or successful APK build is not proof of network synchronization.

## Six execution gates

### 1. Repository, schema, and business-write audit
Status: **in progress**

- Confirmed the current development branch and protected main snapshot.
- Reconciled stale database-version references: current schema is version 23.
- Distinguished primary-side outbox hooks, secondary pending-command support, and actual network delivery.
- Remaining: inventory every repository write path and verify transaction-level event coverage for products, categories, exchange rates, customers, suppliers, cash accounts, daily balances, wallet transfers, service transfers, returns, and expenses.

### 2. Local-first command and authoritative application
Status: **partially implemented; not production-complete**

- Secondary customer collections have a pending-command path and do not post the local ledger.
- Primary-side invoice and selected payment/return/expense outbox hooks exist.
- Invoice posting and several other financial/master-data paths are not yet converted into secondary pending commands.
- The domain applier currently supports invoice posting, customer collections, and the store time-zone setting; this does not cover the full business event catalogue.
- Do not enable multi-device accounting until every supported action is queued atomically on the secondary and validated/applied idempotently on the primary.

### 3. Authenticated transport and Android runtime
Status: **not implemented**

- No production socket/HTTPS transport, pairing handshake, foreground listener service, retry worker, or network-change recovery is wired.
- Do not add INTERNET permission or expose a listener until authenticated transport and pairing are implemented and reviewed.
- Direct remote reachability over 4G/CGNAT remains unproven. No relay or hosted business-data service is authorized by the current architecture decision.

### 4. Two-database production integration tests
Status: **not passed**

- `TwoPeerSyncSimulationTest` is a simplified protocol harness using separate SQLite files; it does not run the production repositories and transport as a complete pipeline.
- Required production test: independent app databases and identities, queue → signed send → authenticated receive → domain apply → durable ACK → duplicate replay, including process restart and network interruption.
- Add tests for operation-ID/hash conflicts, sequence gaps, revocation, authorization, stock races, and exactly-once cash/debt/wallet effects.

### 5. Store time-zone and reporting audit
Status: **targeted fixes committed; full audit remains open**

- Store time zone remains IANA-based, default `Asia/Damascus`; UTC event instants remain immutable.
- Database v23 adds `operating_date` to service transfer and service-wallet ledgers and backfills existing rows using the configured store zone.
- Service daily summaries use the explicit store operating date instead of the UTC date prefix.
- Daily cash movement list timestamps are formatted in the store zone.
- Added tests for the new schema, service posting dates, and v22→v23 migration.
- Follow-up correction: newly created operating dates in collections, supplier payments, daily cash movements, and service movements are now derived from the exact UTC event timestamp, avoiding a midnight-boundary race.

### 6. Final release and user acceptance
Status: **blocked by gates 1–5**

- Do not ask the owner to install an intermediate APK or enter inventory.
- Do not provide a release APK until the full production sync path, tests, migration/upgrade checks, and signature verification pass.
- Final manual acceptance is requested only after a complete release candidate is ready.

## Latest implementation changes

- `AppDatabase.kt`: schema version 23; service-ledger operating-date migration and backfill.
- `ServiceTransferRepository.kt`: service daily reports filter by `operating_date`; new transfer and wallet movement rows persist it using the exact event timestamp.
- `DailyBalanceRepository.kt`: cash movement list renders UTC instants in store time; new cash movement dates derive from the event timestamp.
- `CustomerPaymentRepository.kt` and `PurchasePaymentRepository.kt`: operating dates derive from the event timestamp.
- `StoreTimeTest.kt`: schema and v22→v23 migration coverage.
- `ServiceTransferRepositoryTest.kt`: service posting date persistence and daily summary coverage.

CI run `41640124214` passed unit tests, signed release APK build, and artifact upload on code snapshot `18bb17353fb81f045fea28434adf4591a879be28`. Follow-up exact-event-timestamp changes have a newer CI run pending and must pass before those changes are accepted.
