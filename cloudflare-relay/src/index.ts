export interface Env {
  SYNC_ROOMS: DurableObjectNamespace;
  /** JSON map: store UUID -> { device_id, public_key } (base64 X.509/SPKI). */
  STORE_ROOT_PUBLIC_KEYS: string;
}

type RootTrust = { device_id: string; public_key: string };
type DeviceCertificate = {
  version: 1; store_id: string; issuer_device_id: string; device_id: string; public_key: string;
  serial: string; issued_at: number; expires_at: number; signature: string;
};
type Revocation = {
  version: 1; store_id: string; device_id: string; serial: string;
  revoked_at: number; signature: string;
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MAX_FRAME_CHARS = 700_000;
const MAX_CONNECTIONS_PER_STORE = 64;
const MAX_MESSAGES_PER_SOCKET = 60;
const MESSAGE_WINDOW_MS = 10_000;
const CHALLENGE_TTL_MS = 30_000;
const MAX_CERTIFICATE_TTL_MS = 365 * 24 * 60 * 60 * 1000;

function decodeBase64(value: string): Uint8Array {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value)) throw new Error("invalid base64");
  const binary = atob(value);
  return Uint8Array.from(binary, c => c.charCodeAt(0));
}
function encodeBase64Url(value: Uint8Array): string {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}
function fromBase64Url(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]{20,128}$/.test(value)) throw new Error("invalid challenge");
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  return decodeBase64(padded);
}
function certPayload(cert: DeviceCertificate): string {
  return JSON.stringify({
    version: cert.version, store_id: cert.store_id, issuer_device_id: cert.issuer_device_id, device_id: cert.device_id,
    public_key: cert.public_key, serial: cert.serial,
    issued_at: cert.issued_at, expires_at: cert.expires_at,
  });
}
function revokePayload(value: Revocation): string {
  return JSON.stringify({
    version: value.version, store_id: value.store_id, device_id: value.device_id,
    serial: value.serial, revoked_at: value.revoked_at,
  });
}
function derEcdsaToRaw(der: Uint8Array): Uint8Array {
  // Android SHA256withECDSA emits ASN.1 DER; WebCrypto expects fixed-width r||s.
  if (der.length < 8 || der[0] !== 0x30) throw new Error("invalid ECDSA signature");
  let offset = 2;
  if (der[1] & 0x80) {
    const lengthBytes = der[1] & 0x7f;
    if (lengthBytes !== 1 || der[2] !== der.length - 3) throw new Error("invalid DER length");
    offset = 3;
  }
  if (der[offset++] !== 0x02) throw new Error("invalid DER r");
  const rLength = der[offset++];
  const r = der.slice(offset, offset + rLength); offset += rLength;
  if (der[offset++] !== 0x02) throw new Error("invalid DER s");
  const sLength = der[offset++];
  const s = der.slice(offset, offset + sLength);
  const trim = (v: Uint8Array) => v[0] === 0 ? v.slice(1) : v;
  const rr = trim(r), ss = trim(s);
  if (rr.length > 32 || ss.length > 32) throw new Error("invalid ECDSA scalar");
  const raw = new Uint8Array(64);
  raw.set(rr, 32 - rr.length); raw.set(ss, 64 - ss.length);
  return raw;
}
async function verifySignature(publicKeyBase64: string, data: string, signatureBase64: string): Promise<boolean> {
  try {
    const key = await crypto.subtle.importKey(
      "spki", decodeBase64(publicKeyBase64),
      { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]
    );
    const encodedSignature = decodeBase64(signatureBase64);
    const signature = encodedSignature.length === 64 ? encodedSignature : derEcdsaToRaw(encodedSignature);
    return await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" }, key, signature,
      new TextEncoder().encode(data)
    );
  } catch { return false; }
}
function rootTrust(env: Env, storeId: string): RootTrust | null {
  try {
    const parsed = JSON.parse(env.STORE_ROOT_PUBLIC_KEYS || "{}") as Record<string, RootTrust>;
    const root = parsed[storeId];
    if (!root || !UUID.test(root.device_id) || typeof root.public_key !== "string") return null;
    return root;
  } catch { return null; }
}
async function readJsonLimited<T>(request: Request, maxBytes: number): Promise<T> {
  const declaredLength = request.headers.get("Content-Length");
  if (declaredLength !== null) {
    if (!/^\\d+$/.test(declaredLength)) throw new Error("invalid content length");
    if (Number(declaredLength) > maxBytes) throw new RangeError("request body too large");
  }
  const reader = request.body?.getReader();
  if (!reader) throw new Error("missing request body");
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const part = await reader.read();
    if (part.done) break;
    total += part.value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      throw new RangeError("request body too large");
    }
    chunks.push(part.value);
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as T;
}

async function verifyCertificate(env: Env, storeId: string, deviceId: string, cert: DeviceCertificate): Promise<boolean> {
  const root = rootTrust(env, storeId);
  if (!root || cert.version !== 1 || cert.store_id !== storeId || cert.issuer_device_id !== root.device_id || cert.device_id !== deviceId ||
      !UUID.test(cert.serial) || !UUID.test(cert.device_id) ||
      typeof cert.public_key !== "string" || typeof cert.signature !== "string") return false;
  const now = Date.now();
  if (!Number.isSafeInteger(cert.issued_at) || !Number.isSafeInteger(cert.expires_at) ||
      cert.issued_at > now + 60_000 || cert.expires_at <= now ||
      cert.expires_at <= cert.issued_at || cert.expires_at - cert.issued_at > MAX_CERTIFICATE_TTL_MS) return false;
  if (deviceId === root.device_id) return false; // root identity is pinned directly, never re-enrolled.
  try {
    const deviceKey = await crypto.subtle.importKey(
      "spki", decodeBase64(cert.public_key),
      { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]
    );
    void deviceKey;
  } catch { return false; }
  return verifySignature(root.public_key, certPayload(cert), cert.signature);
}
async function verifyRevocation(env: Env, value: Revocation): Promise<boolean> {
  const root = rootTrust(env, value.store_id);
  // A root-signed revocation may be uploaded after a long offline period.
  // Its signature and certificate serial provide authenticity/idempotency;
  // only timestamps implausibly in the future are rejected.
  if (!root || value.version !== 1 || !UUID.test(value.device_id) ||
      !UUID.test(value.serial) || !Number.isSafeInteger(value.revoked_at) ||
      value.revoked_at <= 0 || value.revoked_at > Date.now() + 60_000) return false;
  return verifySignature(root.public_key, revokePayload(value), value.signature);
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/healthz") {
      return Response.json({ service: "the-daily-shop-relay", status: "ok", protocol: 2 });
    }
    const trustMatch = url.pathname.match(/^\/v1\/trust\/([^/]+)$/);
    if (request.method === "GET" && trustMatch) {
      const storeId = trustMatch[1];
      if (!UUID.test(storeId)) return new Response("Invalid store identifier", { status: 400 });
      const root = rootTrust(env, storeId);
      if (!root) return new Response("Trust root is not provisioned", { status: 404 });
      return Response.json({ store_id: storeId, device_id: root.device_id, public_key: root.public_key, algorithm: "EC_P256_SHA256" });
    }
    const register = url.pathname.match(/^\/v1\/devices\/([^/]+)\/([^/]+)\/certificate$/);
    if (request.method === "POST" && register) {
      const [, storeId, deviceId] = register;
      if (!UUID.test(storeId) || !UUID.test(deviceId)) return new Response("Invalid identity", { status: 400 });
      let cert: DeviceCertificate;
      try { cert = await readJsonLimited<DeviceCertificate>(request, 8_192); }
      catch (error) {
        return new Response(error instanceof RangeError ? "Request body too large" : "Invalid JSON",
          { status: error instanceof RangeError ? 413 : 400 });
      }
      if (!await verifyCertificate(env, storeId, deviceId, cert)) return new Response("Certificate rejected", { status: 401 });
      const id = env.SYNC_ROOMS.idFromName(storeId);
      return env.SYNC_ROOMS.get(id).fetch(new Request(
        `https://relay.internal/registry/certificate/${deviceId}`,
        { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(cert) }
      ));
    }
    const revoke = url.pathname.match(/^\/v1\/devices\/([^/]+)\/([^/]+)\/revoke$/);
    if (request.method === "POST" && revoke) {
      const [, storeId, deviceId] = revoke;
      if (!UUID.test(storeId) || !UUID.test(deviceId)) return new Response("Invalid identity", { status: 400 });
      let value: Revocation;
      try { value = await readJsonLimited<Revocation>(request, 8_192); }
      catch (error) {
        return new Response(error instanceof RangeError ? "Request body too large" : "Invalid JSON",
          { status: error instanceof RangeError ? 413 : 400 });
      }
      if (value.store_id !== storeId || value.device_id !== deviceId ||
          !await verifyRevocation(env, value)) return new Response("Revocation rejected", { status: 401 });
      const id = env.SYNC_ROOMS.idFromName(storeId);
      return env.SYNC_ROOMS.get(id).fetch(new Request(
        `https://relay.internal/registry/revoke/${deviceId}`,
        { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(value) }
      ));
    }

    const match = url.pathname.match(/^\/v1\/ws\/([^/]+)\/([^/]+)$/);
    if (!match) return new Response("Not found", { status: 404 });
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket upgrade required", { status: 426 });
    }
    const [, storeId, deviceId] = match;
    if (!UUID.test(storeId) || !UUID.test(deviceId)) return new Response("Invalid identity", { status: 400 });
    const root = rootTrust(env, storeId);
    if (!root) return new Response("Store trust root is not provisioned", { status: 503 });
    const id = env.SYNC_ROOMS.idFromName(storeId);
    return env.SYNC_ROOMS.get(id).fetch(new Request(
      `https://relay.internal/connect/${storeId}/${deviceId}`, request
    ));
  },
} satisfies ExportedHandler<Env>;

type SocketAttachment = {
  storeId: string; deviceId: string; serial: string; publicKeyBase64: string; challenge: string;
  challengeExpiresAt: number; authenticated: boolean;
};

export class SyncRoom implements DurableObject {
  constructor(private readonly state: DurableObjectState, private readonly env: Env) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const certMatch = url.pathname.match(/^\/registry\/certificate\/([^/]+)$/);
    if (request.method === "PUT" && certMatch) {
      const deviceId = certMatch[1];
      const cert = await request.json() as DeviceCertificate;
      const revokedIncoming = await this.state.storage.get<boolean>(`revoked:${deviceId}:${cert.serial}`);
      if (revokedIncoming) return new Response("Certificate serial has been revoked", { status: 410 });
      const existing = await this.state.storage.get<DeviceCertificate>(`cert:${deviceId}`);
      if (existing) {
        if (existing.serial === cert.serial) {
          if (JSON.stringify(existing) !== JSON.stringify(cert)) {
            return new Response("Certificate serial collision", { status: 409 });
          }
          return Response.json({ registered: true, serial: cert.serial, duplicate: true });
        }
        const revokedExisting = await this.state.storage.get<boolean>(`revoked:${deviceId}:${existing.serial}`);
        if (!revokedExisting) return new Response("Active certificate already exists", { status: 409 });
      }
      await this.state.storage.put(`cert:${deviceId}`, cert);
      return Response.json({ registered: true, serial: cert.serial });
    }
    const revokeMatch = url.pathname.match(/^\/registry\/revoke\/([^/]+)$/);
    if (request.method === "POST" && revokeMatch) {
      const deviceId = revokeMatch[1];
      const value = await request.json() as Revocation;
      const revokedKey = `revoked:${deviceId}:${value.serial}`;
      if (await this.state.storage.get<boolean>(revokedKey)) {
        return Response.json({ revoked: true, duplicate: true, device_id: deviceId, serial: value.serial });
      }
      const cert = await this.state.storage.get<DeviceCertificate>(`cert:${deviceId}`);
      // A signed revocation may arrive before its certificate registration when
      // the primary was offline. Persist the tombstone anyway so a delayed
      // registration of that serial can never resurrect the revoked device.
      await this.state.storage.put(revokedKey, true);
      if (cert?.serial === value.serial) {
        await this.state.storage.delete(`cert:${deviceId}`);
      }
      // A delayed revocation for an older serial must not disconnect a newer
      // certificate that was issued after a legitimate re-pairing.
      for (const socket of this.state.getWebSockets(deviceId)) {
        const attachment = socket.deserializeAttachment() as SocketAttachment | null;
        if (attachment?.serial !== value.serial) continue;
        try { socket.send(JSON.stringify({ type: "error", code: "DEVICE_REVOKED" })); socket.close(1008, "device revoked"); }
        catch { /* already closed */ }
      }
      return Response.json({
        revoked: true, device_id: deviceId, serial: value.serial,
        certificate_was_active: cert?.serial === value.serial,
      });
    }

    const match = url.pathname.match(/^\/connect\/([^/]+)\/([^/]+)$/);
    if (!match || request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("Not found", { status: 404 });
    }
    const [, storeId, deviceId] = match;
    if (!UUID.test(storeId) || !UUID.test(deviceId)) return new Response("Invalid device identifier", { status: 400 });
    const root = rootTrust(this.env, storeId);
    if (!root) return new Response("Trust root unavailable", { status: 503 });

    let publicKey: string;
    let serial: string;
    if (deviceId === root.device_id) {
      publicKey = root.public_key;
      serial = "ROOT";
    } else {
      const cert = await this.state.storage.get<DeviceCertificate>(`cert:${deviceId}`);
      if (!cert || cert.store_id !== storeId || cert.device_id !== deviceId ||
          cert.expires_at <= Date.now() ||
          await this.state.storage.get<boolean>(`revoked:${deviceId}:${cert.serial}`) ||
          !await verifyCertificate(this.env, storeId, deviceId, cert)) {
        return new Response("Device is not enrolled or has been revoked", { status: 403 });
      }
      publicKey = cert.public_key;
      serial = cert.serial;
    }

    if (this.state.getWebSockets(deviceId).length > 0) return new Response("Device already connected", { status: 409 });
    if (this.state.getWebSockets().length >= MAX_CONNECTIONS_PER_STORE) return new Response("Store relay capacity reached", { status: 503 });

    const pair = new WebSocketPair();
    const client = pair[0], server = pair[1];
    const challenge = new Uint8Array(32);
    crypto.getRandomValues(challenge);
    const challengeText = encodeBase64Url(challenge);
    const attachment: SocketAttachment = {
      storeId, deviceId, serial, publicKeyBase64: publicKey, challenge: challengeText,
      challengeExpiresAt: Date.now() + CHALLENGE_TTL_MS, authenticated: false,
    };
    this.state.acceptWebSocket(server, [deviceId]);
    server.serializeAttachment(attachment);
    try {
      await this.scheduleChallengeExpiry();
    } catch {
      this.failSocket(server, "AUTH_CHALLENGE_TIMER_UNAVAILABLE");
      return new Response("Unable to schedule authentication challenge expiry", { status: 503 });
    }
    server.send(JSON.stringify({ type: "challenge", challenge: challengeText, expires_at: attachment.challengeExpiresAt }));
    return new Response(null, { status: 101, webSocket: client });
  }

  /** Close unauthenticated sockets when their one-time challenge expires. */
  async alarm(): Promise<void> {
    const now = Date.now();
    let nextExpiry = Number.POSITIVE_INFINITY;
    for (const socket of this.state.getWebSockets()) {
      const attachment = socket.deserializeAttachment() as SocketAttachment | null;
      if (!attachment || attachment.authenticated) continue;
      if (attachment.challengeExpiresAt <= now) {
        this.failSocket(socket, "AUTHENTICATION_TIMEOUT");
      } else {
        nextExpiry = Math.min(nextExpiry, attachment.challengeExpiresAt);
      }
    }
    if (Number.isFinite(nextExpiry)) await this.state.storage.setAlarm(nextExpiry);
  }

  private async scheduleChallengeExpiry(): Promise<void> {
    let nextExpiry = Number.POSITIVE_INFINITY;
    for (const socket of this.state.getWebSockets()) {
      const attachment = socket.deserializeAttachment() as SocketAttachment | null;
      if (attachment && !attachment.authenticated) {
        nextExpiry = Math.min(nextExpiry, attachment.challengeExpiresAt);
      }
    }
    if (Number.isFinite(nextExpiry)) await this.state.storage.setAlarm(nextExpiry);
  }

  async webSocketMessage(sender: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const attachment = sender.deserializeAttachment() as SocketAttachment | null;
    if (!attachment || typeof message !== "string" || message.length > MAX_FRAME_CHARS) {
      this.failSocket(sender, "UNAUTHENTICATED_SOCKET");
      return;
    }
    if (!attachment.authenticated) {
      let auth: { type?: string; challenge?: string; signature?: string; serial?: string };
      try { auth = JSON.parse(message); } catch { this.failSocket(sender, "INVALID_AUTH_MESSAGE"); return; }
      if (auth.type !== "authenticate" || auth.challenge !== attachment.challenge ||
          auth.serial !== attachment.serial || attachment.challengeExpiresAt < Date.now() ||
          typeof auth.signature !== "string") {
        this.failSocket(sender, "AUTHENTICATION_FAILED");
        return;
      }
      const root = rootTrust(this.env, attachment.storeId);
      let publicKey: string | null = null;
      if (attachment.serial === "ROOT" && root?.device_id === attachment.deviceId &&
          root.public_key === attachment.publicKeyBase64) {
        publicKey = root.public_key;
      } else {
        const cert = await this.state.storage.get<DeviceCertificate>(`cert:${attachment.deviceId}`);
        if (cert && cert.serial === attachment.serial && cert.expires_at > Date.now() &&
            !(await this.state.storage.get<boolean>(`revoked:${attachment.deviceId}:${cert.serial}`)) &&
            await verifyCertificate(this.env, attachment.storeId, attachment.deviceId, cert)) {
          publicKey = cert.public_key;
        }
      }
      if (!publicKey) { this.failSocket(sender, "DEVICE_REVOKED"); return; }
      const challengeBytes = fromBase64Url(attachment.challenge);
      const proof = JSON.stringify([
        "THE_DAILY_SHOP_RELAY_AUTH_V1", attachment.storeId, attachment.deviceId,
        attachment.serial, attachment.challenge, attachment.challengeExpiresAt,
      ]);
      if (!await verifySignature(publicKey, proof, auth.signature)) {
        this.failSocket(sender, "AUTHENTICATION_FAILED");
        return;
      }
      attachment.authenticated = true;
      sender.serializeAttachment(attachment);
      sender.send(JSON.stringify({ type: "ready", protocol: 2, device_id: attachment.deviceId }));
      void challengeBytes;
      return;
    }

    if (attachment.serial === "ROOT") {
      const root = rootTrust(this.env, attachment.storeId);
      if (!root || root.device_id !== attachment.deviceId || root.public_key !== attachment.publicKeyBase64) {
        this.failSocket(sender, "TRUST_ROOT_CHANGED");
        return;
      }
    } else {
      const currentCert = await this.state.storage.get<DeviceCertificate>(`cert:${attachment.deviceId}`);
      if (!currentCert || currentCert.serial !== attachment.serial ||
          currentCert.expires_at <= Date.now() ||
          await this.state.storage.get<boolean>(`revoked:${attachment.deviceId}:${attachment.serial}`) ||
          !await verifyCertificate(this.env, attachment.storeId, attachment.deviceId, currentCert)) {
        this.failSocket(sender, "DEVICE_REVOKED");
        return;
      }
    }

    const now = Date.now();
    const rateLimited = await this.state.storage.transaction(async transaction => {
      const key = `rate:${attachment.deviceId}`;
      const current = await transaction.get<{ startedAt: number; count: number }>(key);
      const next = !current || now - current.startedAt >= MESSAGE_WINDOW_MS
        ? { startedAt: now, count: 1 }
        : { startedAt: current.startedAt, count: current.count + 1 };
      await transaction.put(key, next);
      return next.count > MAX_MESSAGES_PER_SOCKET;
    });
    if (rateLimited) { this.failSocket(sender, "RATE_LIMITED"); return; }

    let frame: { type?: string; to_device_id?: string; frame_id?: string; ciphertext?: string };
    try { frame = JSON.parse(message); } catch { this.failSocket(sender, "INVALID_JSON"); return; }
    if (frame.type !== "relay" || !frame.to_device_id || !UUID.test(frame.to_device_id) ||
        !frame.frame_id || frame.frame_id.length > 80 || !frame.ciphertext ||
        frame.ciphertext.length > MAX_FRAME_CHARS || frame.to_device_id === attachment.deviceId) {
      sender.send(JSON.stringify({ type: "error", code: "INVALID_RELAY_ENVELOPE" }));
      return;
    }
    const recipients = this.state.getWebSockets(frame.to_device_id).filter(socket => {
      const peer = socket.deserializeAttachment() as SocketAttachment | null;
      return peer?.authenticated === true && peer.storeId === attachment.storeId;
    });
    if (recipients.length === 0) {
      sender.send(JSON.stringify({ type: "not_delivered", frame_id: frame.frame_id, retryable: true }));
      return;
    }
    const routed = JSON.stringify({
      type: "relay", from_device_id: attachment.deviceId,
      frame_id: frame.frame_id, ciphertext: frame.ciphertext,
    });
    let delivered = false;
    for (const recipient of recipients) {
      try { recipient.send(routed); delivered = true; } catch { /* sender retries */ }
    }
    sender.send(JSON.stringify({ type: delivered ? "forwarded" : "not_delivered", frame_id: frame.frame_id, retryable: !delivered }));
  }

  private failSocket(socket: WebSocket, code: string): void {
    try { socket.send(JSON.stringify({ type: "error", code })); socket.close(1008, code); }
    catch { /* already closed */ }
  }

  async webSocketClose(socket: WebSocket): Promise<void> {
    try { socket.close(1000, "closed"); } catch { /* already closed */ }
  }
  async webSocketError(socket: WebSocket): Promise<void> {
    try { socket.close(1011, "transport error"); } catch { /* already closed */ }
  }
}
