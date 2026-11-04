# The Daily Shop — authenticated ephemeral sync relay

This Worker is a transport relay only. It does not store a store database, ledger, inventory, or durable business messages. A Durable Object routes encrypted frames only between currently authenticated devices in the same store room. If the target is offline, the sender receives a retryable response and must retain the operation in its local SQLite outbox.

## Trust and device authentication

The relay does not trust store/device UUIDs or public keys supplied during a WebSocket connection.

- `STORE_ROOT_PUBLIC_KEYS` is a JSON map keyed by store UUID. Each value contains `device_id` and `public_key` (base64 X.509/SPKI P-256 public key) for the store's primary/root device.
- The primary's public key is provisioned out-of-band in Cloudflare Worker variables. The matching private key remains in Android Keystore on the primary device.
- The primary signs each secondary device certificate. Certificates bind protocol version, store UUID, device UUID, device public key, unique serial, issue time and expiry.
- The primary registers a certificate using `POST /v1/devices/{store_uuid}/{device_uuid}/certificate`. The Worker verifies the signature against the pinned root key before persisting the certificate in the store Durable Object.
- A WebSocket connection is not ready on upgrade. The relay sends a random, short-lived challenge. The client must prove possession of the private key matching its registered certificate (or the pinned root key for the primary). Only then does the relay send `ready`.
- The primary revokes a secondary using a root-signed request to `POST /v1/devices/{store_uuid}/{device_uuid}/revoke`. The relay records a revocation tombstone, removes the active certificate and closes that device's live sessions. A revoked device cannot reconnect using its old certificate.

Certificate and revocation requests are authenticated by the root signature; they do not rely on a bearer secret embedded in the APK. Certificate metadata and revocation state are stored in the Durable Object; business records and message payloads are not.

## Endpoints

- `GET /healthz` — health check.
- `POST /v1/devices/{store_uuid}/{device_uuid}/certificate` — register a root-signed device certificate.
- `POST /v1/devices/{store_uuid}/{device_uuid}/revoke` — apply a root-signed revocation.
- `GET /v1/ws/{store_uuid}/{device_uuid}` — challenge-response authenticated WebSocket.
- Client frames after authentication: `{"type":"relay","to_device_id":"...","frame_id":"...","ciphertext":"..."}`.

`forwarded` only means a live WebSocket send was attempted. It is not a business acknowledgement. Only a separately authenticated acknowledgement from the primary after its SQLite transaction commits can clear a secondary outbox item.

## Required production provisioning

After the primary app has generated its stable Android Keystore identity, its public key and device UUID must be added to `STORE_ROOT_PUBLIC_KEYS` in the Cloudflare Worker configuration for that store. This is a public trust anchor, not a private key. Never upload the private key. The Worker must remain undeployed for real traffic until this provisioning and the Android certificate/pairing integration have been completed and verified.

For local tests, the integration suite creates a temporary `.dev.vars` file with an ephemeral test root and removes it afterwards.

## Deploy (owner action, not performed by CI)

1. Create/sign in to Cloudflare on a trusted computer.
2. From this directory, install dependencies and run `npx wrangler login`.
3. Configure `STORE_ROOT_PUBLIC_KEYS` with the correct root entry.
4. Run `npx wrangler deploy` only after the Android trust provisioning and all security gates are complete.
5. Keep unacknowledged commands in the local SQLite outbox through outages or quota exhaustion.

The relay uses a SQLite-backed Durable Object for device certificates, revocation tombstones and abuse counters. It does not persist business payloads. Cloudflare quotas still apply.
