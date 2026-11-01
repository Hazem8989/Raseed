# The Daily Shop — Primary-Phone Synchronization Architecture

Status: architecture decision approved by the owner; implementation not yet enabled.
Development branch: the-daily-shop-development.
Scope: Android application only. The main branch must remain untouched.

## 1. Owner-approved operating model

- The owner's phone is the only authoritative primary node.
- The primary phone stores the canonical SQLite database and decides final financial and inventory posting.
- Staff phones are enrolled clients. They keep only the local data needed for permitted work and a durable queue of unacknowledged operations.
- The owner approves and revokes every enrolled device from the primary phone.
- No external hosted database, cloud storage, hosted business API, or third-party repository for store data is allowed.
- The store ADSL router is only a local network path. It is not a server and must not store store records.
- Wi-Fi and mobile data are both supported as transport networks; the design must tolerate switching between them.

## 2. Critical reachability constraint

A phone using 4G is commonly behind carrier-grade NAT (CGNAT). Being on the same mobile carrier does not guarantee that one subscriber phone can accept an inbound connection from another. The owner's phone also changes networks and may be suspended by Android battery/background restrictions.

Therefore:
- Direct peer-to-peer connections are preferred.
- Local Wi-Fi direct-to-primary access can be used when both devices are on the store LAN and the router permits client-to-client traffic.
- Remote direct access requires a reachable endpoint or successful NAT traversal. It must be measured and tested, not assumed.
- No cloud relay is to be added by default. A rendezvous/STUN-only helper may be considered only after explicit owner approval; it must not proxy, retain, or inspect business payloads.
- If direct reachability fails, the app must show the primary as unavailable and retain client operations in a durable local queue. It must never pretend that a queued operation has been centrally committed.
- When the primary phone is powered off, offline, force-stopped, or unreachable, final central posting and distribution to other clients pause. This is an inherent consequence of choosing a phone as the sole primary server.

## 3. Transaction and synchronization rules

1. Each device has a cryptographically random stable device ID. Each operation has a globally unique idempotency ID.
2. A local business action and its outbox record must be committed atomically in the same SQLite transaction.
3. Client-originated financial/inventory operations remain PENDING_PRIMARY until the primary validates and commits them.
4. The primary validates user status, device enrollment, permissions, payload hash, idempotency key, business invariants, available stock, and account balances before posting.
5. Re-delivery of an identical operation returns the original result. Reuse of an operation ID with a different payload hash is rejected and logged as a security/conflict event.
6. The primary assigns authoritative sequence/version values. Client timestamps are informational and never decide financial ordering.
7. Never use last-write-wins for invoices, stock, debts, wallets, cash accounts, returns, or audit records. Conflicts preserve both payloads and require deterministic validation or owner review.
8. Acknowledgements are durable and include operation ID, accepted/rejected status, primary sequence, and result reference.
9. Retries use bounded exponential backoff with jitter; app restart and network changes must not lose the queue.
10. Device revocation blocks future requests. Pending operations from a revoked device are retained for owner review, not silently deleted.

## 4. Android runtime and security

- The primary listener must be an explicitly started, user-visible foreground service when Android requires it; it must have clear start/stop state and notification.
- The implementation must respect Android background execution, foreground-service type/permissions, battery optimization, and target-SDK requirements. A foreground service does not guarantee availability if the user force-stops the app or the phone shuts down.
- Use TLS with mutual device authentication or an equivalently reviewed authenticated channel. Never expose unauthenticated database or repository methods over the network.
- Pairing must use a short-lived one-time enrollment code/QR, owner approval, and per-device credentials stored using Android Keystore-backed protection.
- Do not store passwords or reusable owner secrets in plaintext. Do not embed server private keys in the APK.
- Limit network exposure to the synchronization protocol. Do not expose SQLite, debug endpoints, or general-purpose file access.
- Audit enrollment, revocation, permission changes, accepted/rejected operations, and conflict resolution.

## 5. Implementation sequence and gates

### Gate 0 — repository and schema audit
- Confirm current database version, migrations, manifest, dependencies, outbox schema, operation registry, audit log, and test baseline.
- Identify every financial repository write path before wiring atomic outbox events.
- Do not claim existing sync_queue or sync_conflicts tables are a working sync engine.

### Gate 1 — local synchronization core
- Add device identity, store identity, primary/client role, operation envelope, payload hashing, authoritative sequence/version fields, durable cursor, acknowledgement state, and migration tests.
- Add a repository API that atomically records an outbox event with each supported business transaction.
- Add idempotent inbox processing and replay tests before any network listener is enabled.

### Gate 2 — device enrollment and authorization
- Add owner-approved pairing, device list, revocation, device/user binding, and permission checks in the data layer.
- Test revoked devices, disabled users, stale credentials, duplicate enrollment, and unauthorized operations.

### Gate 3 — local-network transport
- Implement an authenticated encrypted connection on the store LAN first.
- Test same Wi-Fi, client isolation, changing primary IP, interrupted transfer, duplicate delivery, and app restart.
- No port forwarding or public listener until security review.

### Gate 4 — remote direct connectivity feasibility
- Test the owner's 4G and staff 4G networks for CGNAT/NAT type and direct reachability.
- Test ADSL public IP availability and router capabilities as an optional path, not as a dependency.
- If direct P2P cannot be made reliable without a relay, stop and report the limitation. Do not add a relay, VPN host, cloud API, or third-party data service without explicit approval.

### Gate 5 — multi-device accounting acceptance
- Test concurrent sales against limited stock, credit collections, wallet receipts, returns/refunds, exchange-rate changes, and account transfers.
- Verify exactly-once financial effect under retries, offline periods, primary unavailability, and app restarts.
- Only then enable multi-device production use.

## 6. Current verified baseline

- AppDatabase.DATABASE_VERSION is 7.
- The schema contains operation_registry, sync_queue, sync_conflicts, and audit_log.
- The existing queue schema does not yet provide the full device enrollment, authoritative sequencing, acknowledgement/cursor, or transport engine described above.
- AndroidManifest.xml currently has no INTERNET or network-state permissions and declares no sync service.
- app/build.gradle.kts currently has no networking, coroutine, or WorkManager dependency.
- Therefore live synchronization is not implemented. These findings are a starting baseline, not a completed feature.
