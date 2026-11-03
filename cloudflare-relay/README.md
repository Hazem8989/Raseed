# The Daily Shop — ephemeral sync relay

This Worker is a transport relay only. It does not store a store database, ledger, inventory, or durable business messages. A Durable Object routes an encrypted frame to a currently connected device in the same store room. If the target is offline, the sender receives a retryable response and must retain the operation in its local SQLite outbox.

## Endpoints

- GET /healthz — deployment health check.
- GET /v1/ws/{store_uuid}/{device_uuid} — WebSocket connection for one enrolled device.
- Client frames: {"type":"relay","to_device_id":"...","frame_id":"...","ciphertext":"..."}.

The relay returns forwarded only to indicate that a live WebSocket send was attempted. It is not a business acknowledgement. Only a separately authenticated acknowledgement from the primary after its SQLite transaction commits can clear the secondary outbox item.

## Important security gate

The Android protocol currently has canonical payload hashing and HMAC authentication, but the envelope payload is not yet end-to-end encrypted. Therefore this relay is scaffolding only and must not be deployed for real store traffic until the Android transport wraps the complete authenticated envelope in authenticated encryption (AES-GCM), verifies it on receipt, and passes replay/idempotency tests. The Worker forwards ciphertext and never needs device credentials.

Store and device UUIDs are routing identifiers, not authentication. Production deployment must add a reviewed connection-admission mechanism and abuse controls without putting a reusable owner secret in the APK.

## Deploy (owner action, not performed by CI)

1. Create/sign in to a Cloudflare account on the Free plan.
2. From this directory, install dependencies and run npx wrangler login on a trusted computer.
3. Run npx wrangler deploy only after the Android encryption and transport security gates are complete.
4. Set the resulting Worker URL in the app's future sync configuration. Do not put Cloudflare account credentials in the repository.

The Worker uses a SQLite-backed Durable Object. No business payload is written to Durable Object storage. Cloudflare quotas still apply; quota exhaustion or recipient unavailability must leave all unacknowledged commands in the app's local outbox.

## Local validation

Run npm install, npm run check, then npx wrangler dev. Exercise health and WebSocket routing with two independent clients. A local test is not proof of real cellular-network connectivity.
