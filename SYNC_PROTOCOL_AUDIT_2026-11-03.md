# The Daily Shop — Sync Protocol Audit and Two-Device Simulation Gate

**Audit date:** 2026-11-03  
**Repository:** `Hazem8989/Raseed`  
**Branch:** `the-daily-shop-development`  
**Audited HEAD:** `063f5bec9e3b3053f4296d9962b0d83fb85c6923`  
**Main branch:** not modified.

## Executive conclusion

The repository contains useful local synchronization primitives, but it does **not** contain a working multi-device synchronization engine. No network transport, background worker, primary listener, inbound operation processor, durable acknowledgement flow, or conflict-resolution workflow is wired. The current app must not be represented as synchronizing two phones.

The previous architecture document's baseline statement that the database is version 11 is stale. The audited source declares `AppDatabase.DATABASE_VERSION = 18`.

## Verified foundations

- A per-installation UUID device identity is stored in private preferences. It is provenance metadata, not authentication.
- The primary can be configured locally; it creates a store ID and initial sequence state.
- Pairing invitations are random, short-lived, one-use tokens stored as SHA-256 hashes.
- A primary can register a secondary and store a 256-bit credential hash in SQLite plus the credential encrypted in the Android Keystore-backed vault.
- A secondary can store its primary/store identity and encrypted credential.
- HMAC-SHA256 envelopes bind store, operation ID, sequence, origin device, actor, operation kind, entity, base revision, payload hash, and timestamp.
- Canonical JSON hashing and signature verification have unit tests, including altered metadata and non-canonical payload rejection.
- Selected primary-side business repositories append outbox records inside the same SQLite transaction as the business posting. This atomicity is a good foundation.

## Blocking findings

### S1 — Secondary devices cannot enqueue business operations

`SyncProtocolRepository.enqueueLocalOperation()` returns `null` unless the local installation is the configured primary. This means a configured secondary cannot create a durable outbound operation, despite the approved architecture requiring staff devices to retain pending operations.

The existing invoice/payment repositories also post locally before calling the sync hook. They do not convert secondary-originated financial actions into `PENDING_PRIMARY` requests. A secondary can therefore create a locally posted invoice/stock/cash effect that is not authoritative and cannot currently be delivered.

### S2 — No inbound persistence or replay/idempotency gate

`verifyIncomingFromPrimary()` and `verifyIncomingFromSecondary()` decode and authenticate an envelope, then return it. They do not:
- persist it into `sync_inbox`;
- compare an existing operation ID's payload hash;
- detect reused sequence numbers or sequence gaps;
- reject replayed operations durably;
- update an applied/received cursor;
- create a durable acknowledgement;
- invoke a domain validator or posting transaction.

The `sync_inbox`, `sync_acknowledgements`, and `sync_cursors` tables exist, but their existence alone is not an inbox processor.

### S3 — Secondary outbound signing is not correctly wired

The primary stores a peer credential under the secondary device ID. The secondary stores its copy under its own local device ID. `authenticatedOutbox(operationId, recipientDeviceId)` always loads the vault entry using the recipient ID. There is no direction-aware mapping for a secondary signing an operation to its primary, and no secondary enqueue path currently calls it.

### S4 — No network/runtime transport exists

The manifest declares no INTERNET or network-state permission, no foreground sync service, and no receiver. The Gradle dependencies include no networking, coroutine, or WorkManager stack. No socket, TLS/mutual-auth transport, pairing QR handshake, retry worker, or network-change handling was found in the audited source tree.

This is intentionally safer than exposing an unfinished listener, but it means live sync is absent. Wi-Fi/4G/CGNAT reachability has not been tested.

### S5 — Business-event coverage is incomplete

Outbox hooks were found for invoice posting, customer collections, supplier payments, sales returns, purchase returns, inventory adjustments, and operating expenses.

No corresponding enqueue hook was found in the inspected implementations for cash-account transfers, service transfers, product/catalog changes, customer/supplier master-data changes, or exchange-rate changes. This is not a complete event catalogue; every repository write path must be inventoried before sync can be enabled.

### S6 — Revision and conflict protocol is not implemented

The envelope supports `baseRevision`, but `authenticatedOutbox()` currently supplies `0L`. No entity revision store, deterministic conflict detector, owner-review queue, or conflict resolution transaction was found. Financial and inventory events must never use last-write-wins.

### S7 — Authentication/authorization mapping across devices is unresolved

Envelopes carry `actorUserId`, but there is no verified cross-device user identity mapping or primary-side authorization/revalidation workflow for a remote actor. A locally valid user ID on a secondary is not automatically a valid user ID in the primary database.

### S8 — Delivery state/retry management is absent

`pendingOutbox()` reads PENDING/FAILED rows, but no worker or repository API was found to atomically claim, retry with bounded exponential backoff/jitter, mark delivery, persist acknowledgements, or recover IN_PROGRESS rows after process death.

## Two-device simulation status

**Not yet executed as an end-to-end two-database simulation.** Existing tests cover local pairing primitives, credential storage, primary outbox sequencing/idempotency, and envelope integrity. They do not instantiate two independent app databases and run a complete send → receive → validate → apply → acknowledge → replay cycle.

A meaningful simulation must use two isolated database files and independent installation identities, not two repository objects sharing the same singleton database. It must prove:

1. Primary and secondary enroll using a one-time invitation and distinct device IDs.
2. Each side signs with the correct peer credential; wrong credentials, wrong store IDs, unknown devices, and revoked devices fail.
3. Secondary-originated operations are durably queued as pending and do not mutate authoritative balances/stock.
4. Primary validates and applies an operation atomically with inbox state and operation registry.
5. Re-delivery of the same operation ID and same hash has one financial effect and returns the original acknowledgement.
6. Reuse of an operation ID with a different hash is rejected and audited.
7. Sequence replay, gaps, out-of-order delivery, duplicate batches, and process restart preserve deterministic state.
8. Concurrent sales against limited stock cannot oversell when both clients were offline.
9. Customer collections, supplier payments, returns/refunds, expenses, transfers, and exchange-rate changes reconcile across both databases.
10. Primary offline/unreachable leaves secondary work visibly pending; it never appears centrally posted.
11. Revocation blocks later requests while retaining already-pending operations for owner review.
12. Network loss, app termination, and network switching do not lose queued operations or duplicate financial effects.

## Safe implementation order

1. Complete the write-path/event catalogue and define canonical request payloads for every supported operation.
2. Separate client-side drafts/requests from primary-authoritative financial posting. This is required before enabling secondary financial workflows.
3. Implement per-device sequence, durable inbox admission, replay/idempotency, actor mapping, and acknowledgement semantics with migration tests.
4. Build a two-independent-database simulation harness and prove the full protocol without sockets.
5. Add domain-specific primary validators/appliers and exact-once accounting tests.
6. Only after those tests pass, add authenticated encrypted LAN transport and a visible foreground service.
7. Test remote direct reachability on Wi-Fi/mobile networks. Do not add a relay, hosted database, or business-data service without separate owner approval.

## Release/build evidence

The latest CI run at this audit point is run 559 on the audited HEAD. Unit tests and signed Release APK packaging succeeded, and APK v1/v2 signature verification passed. This is build evidence only; it does not establish live sync, two-device behavior, or production acceptance.
