import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { webcrypto } from "node:crypto";\nimport { writeFileSync, unlinkSync, existsSync } from "node:fs";

const crypto = webcrypto;
const baseUrl = "http://127.0.0.1:8787";
const storeId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const rootDeviceId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
let rootKeys;
let worker;
let workerOutput = "";\nlet devVarsCreated = false;

const b64 = bytes => Buffer.from(bytes).toString("base64");
const json = value => JSON.stringify(value);
async function keyPair() {
  return crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
}
async function publicKeyBase64(pair) {
  return b64(await crypto.subtle.exportKey("spki", pair.publicKey));
}
async function sign(pair, value) {
  return b64(await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" }, pair.privateKey,
    new TextEncoder().encode(value)
  ));
}
function certificatePayload(cert) {
  return json({
    version: cert.version, store_id: cert.store_id, device_id: cert.device_id,
    public_key: cert.public_key, serial: cert.serial,
    issued_at: cert.issued_at, expires_at: cert.expires_at,
  });
}
function revokePayload(value) {
  return json({
    version: value.version, store_id: value.store_id, device_id: value.device_id,
    serial: value.serial, revoked_at: value.revoked_at,
  });
}
async function makeCertificate(deviceId, deviceKeys = null) {
  const pair = deviceKeys ?? await keyPair();
  const cert = {
    version: 1, store_id: storeId, device_id: deviceId,
    public_key: await publicKeyBase64(pair),
    serial: crypto.randomUUID(), issued_at: Date.now(),
    expires_at: Date.now() + 30 * 24 * 60 * 60 * 1000,
  };
  cert.signature = await sign(rootKeys.privateKey, certificatePayload(cert));
  return { cert, pair };
}
async function registerCertificate(cert) {
  return fetch(`${baseUrl}/v1/devices/${storeId}/${cert.device_id}/certificate`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: json(cert),
  });
}
function authProof(deviceId, serial, challenge, expiresAt) {
  return json([
    "THE_DAILY_SHOP_RELAY_AUTH_V1", storeId, deviceId, serial, challenge, expiresAt,
  ]);
}
async function openChallenge(deviceId) {
  const socket = new WebSocket(`ws://127.0.0.1:8787/v1/ws/${storeId}/${deviceId}`);
  const challenge = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("challenge timeout")), 10_000);
    socket.addEventListener("message", event => {
      const message = JSON.parse(String(event.data));
      if (message.type === "challenge") { clearTimeout(timeout); resolve(message); }
      if (message.type === "error") { clearTimeout(timeout); reject(new Error(message.code)); }
    });
    socket.addEventListener("error", () => { clearTimeout(timeout); reject(new Error("WebSocket connection failed")); }, { once: true });
  });
  return { socket, challenge };
}
async function authenticate(deviceId, pair, serial = "ROOT") {
  const { socket, challenge } = await openChallenge(deviceId);
  const signature = await sign(pair, authProof(deviceId, serial, challenge.challenge, challenge.expires_at));
  const ready = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("ready timeout")), 10_000);
    socket.addEventListener("message", event => {
      const message = JSON.parse(String(event.data));
      if (message.type === "ready") { clearTimeout(timeout); resolve(message); }
      if (message.type === "error") { clearTimeout(timeout); reject(new Error(message.code)); }
    });
  });
  socket.send(json({ type: "authenticate", serial, challenge: challenge.challenge, signature }));
  await ready;
  return socket;
}
function nextMessage(socket, predicate, timeoutMs = 10_000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      socket.removeEventListener("message", onMessage);
      reject(new Error("Timed out waiting for expected relay message"));
    }, timeoutMs);
    function onMessage(event) {
      let value;
      try { value = JSON.parse(String(event.data)); } catch { return; }
      if (!predicate(value)) return;
      clearTimeout(timeout);
      socket.removeEventListener("message", onMessage);
      resolve(value);
    }
    socket.addEventListener("message", onMessage);
  });
}
async function close(socket) {
  if (!socket || socket.readyState === WebSocket.CLOSED) return;
  await new Promise(resolve => {
    const timeout = setTimeout(resolve, 1_000);
    socket.addEventListener("close", () => { clearTimeout(timeout); resolve(); }, { once: true });
    if (socket.readyState < WebSocket.CLOSING) socket.close();
  });
}
async function stopWorker() {
  if (!worker) return;
  const child = worker, pid = child.pid;
  const closed = child.exitCode !== null || child.signalCode !== null
    ? Promise.resolve() : new Promise(resolve => child.once("close", resolve));
  if (pid) { try { process.kill(-pid, "SIGTERM"); } catch (error) { if (error.code !== "ESRCH") throw error; } }
  await Promise.race([closed, new Promise(resolve => setTimeout(resolve, 5_000))]);
  if (pid) { try { process.kill(-pid, "SIGKILL"); } catch (error) { if (error.code !== "ESRCH") throw error; } }
  worker = undefined;
}

before(async () => {
  rootKeys = await keyPair();
  process.env.STORE_ROOT_PUBLIC_KEYS = json({
    [storeId]: { device_id: rootDeviceId, public_key: await publicKeyBase64(rootKeys) },
  });
  worker = spawn(process.execPath, [
    "node_modules/wrangler/bin/wrangler.js", "dev", "--local",
    "--ip", "127.0.0.1", "--port", "8787", "--log-level", "debug",
  ], { cwd: process.cwd(), detached: true, stdio: ["ignore", "pipe", "pipe"], env: process.env });
  worker.stdout.setEncoding("utf8"); worker.stderr.setEncoding("utf8");
  worker.stdout.on("data", chunk => { workerOutput = (workerOutput + chunk).slice(-16000); });
  worker.stderr.on("data", chunk => { workerOutput = (workerOutput + chunk).slice(-16000); });
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    if (worker.exitCode !== null) throw new Error(`wrangler exited ${worker.exitCode}: ${workerOutput.slice(-12000)}`);
    try { if ((await fetch(`${baseUrl}/healthz`)).ok) return; } catch {}
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error(`Worker did not become healthy: ${workerOutput.slice(-12000)}`);
});
after(async () => { await stopWorker(); if (devVarsCreated && existsSync(".dev.vars")) unlinkSync(".dev.vars"); });

test("root-pinned devices authenticate before relay; independent peers exchange frames", async () => {
  const { cert, pair } = await makeCertificate(crypto.randomUUID());
  assert.equal((await registerCertificate(cert)).status, 200);
  const primary = await authenticate(rootDeviceId, rootKeys);
  const secondary = await authenticate(cert.device_id, pair, cert.serial);
  try {
    const frameId = crypto.randomUUID();
    const receivedPromise = nextMessage(primary, m => m.type === "relay" && m.frame_id === frameId);
    const forwardedPromise = nextMessage(secondary, m => m.type === "forwarded" && m.frame_id === frameId);
    secondary.send(json({ type: "relay", to_device_id: rootDeviceId, frame_id: frameId, ciphertext: "opaque-encrypted-frame" }));
    const [received, forwarded] = await Promise.all([receivedPromise, forwardedPromise]);
    assert.equal(received.from_device_id, cert.device_id);
    assert.equal(received.ciphertext, "opaque-encrypted-frame");
    assert.equal(forwarded.retryable, false);

    await assert.rejects(openChallenge(crypto.randomUUID()), /Device is not enrolled|WebSocket connection failed/);
    await assert.rejects(authenticate(cert.device_id, pair, cert.serial), /Device already connected|WebSocket connection failed/);

    const offlineId = crypto.randomUUID();
    const offline = nextMessage(secondary, m => m.type === "not_delivered" && m.frame_id === offlineId);
    secondary.send(json({ type: "relay", to_device_id: crypto.randomUUID(), frame_id: offlineId, ciphertext: "opaque" }));
    assert.equal((await offline).retryable, true);
  } finally { await Promise.all([close(primary), close(secondary)]); }
});

test("forged certificates and replayed or incorrect challenge proofs are rejected", async () => {
  const deviceId = crypto.randomUUID();
  const { cert, pair } = await makeCertificate(deviceId);
  const forged = { ...cert, device_id: deviceId, public_key: await publicKeyBase64(await keyPair()) };
  assert.equal((await registerCertificate(forged)).status, 401);
  assert.equal((await registerCertificate(cert)).status, 200);

  const first = await openChallenge(deviceId);
  const oldSignature = await sign(pair, authProof(deviceId, cert.serial, first.challenge.challenge, first.challenge.expires_at));
  first.socket.send(json({ type: "authenticate", serial: cert.serial, challenge: first.challenge.challenge, signature: oldSignature }));
  await nextMessage(first.socket, m => m.type === "ready");

  await close(first.socket);
  const replay = await openChallenge(deviceId);
  const result = nextMessage(replay.socket, m => m.type === "error");
  replay.socket.send(json({
    type: "authenticate", serial: cert.serial,
    challenge: replay.challenge.challenge, signature: oldSignature,
  }));
  assert.equal((await result).code, "AUTHENTICATION_FAILED");
  await close(replay.socket);

  const wrong = await openChallenge(deviceId);
  const error = nextMessage(wrong.socket, m => m.type === "error");
  wrong.socket.send(json({ type: "authenticate", serial: cert.serial, challenge: wrong.challenge.challenge, signature: "AAAA" }));
  assert.equal((await error).code, "AUTHENTICATION_FAILED");
  await close(wrong.socket);
});

test("root-signed revocation closes an active session and blocks reconnection", async () => {
  const { cert, pair } = await makeCertificate(crypto.randomUUID());
  assert.equal((await registerCertificate(cert)).status, 200);
  const socket = await authenticate(cert.device_id, pair, cert.serial);
  const revokedAt = Date.now();
  const revocation = {
    version: 1, store_id: storeId, device_id: cert.device_id,
    serial: cert.serial, revoked_at: revokedAt,
  };
  revocation.signature = await sign(rootKeys.privateKey, revokePayload(revocation));
  const response = await fetch(`${baseUrl}/v1/devices/${storeId}/${cert.device_id}/revoke`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: json(revocation),
  });
  assert.equal(response.status, 200);
  const revokedEvent = await nextMessage(socket, m => m.type === "error");
  assert.equal(revokedEvent.code, "DEVICE_REVOKED");
  await close(socket);
  await assert.rejects(openChallenge(cert.device_id), /not enrolled|WebSocket connection failed/);
});

test("authenticated sockets retain per-device rate limiting", async () => {
  const { cert, pair } = await makeCertificate(crypto.randomUUID());
  assert.equal((await registerCertificate(cert)).status, 200);
  const primary = await authenticate(rootDeviceId, rootKeys);
  const secondary = await authenticate(cert.device_id, pair, cert.serial);
  try {
    const limited = nextMessage(secondary, m => m.type === "error" && m.code === "RATE_LIMITED");
    for (let index = 0; index < 61; index++) {
      secondary.send(json({ type: "relay", to_device_id: rootDeviceId, frame_id: crypto.randomUUID(), ciphertext: "opaque" }));
    }
    assert.equal((await limited).code, "RATE_LIMITED");
  } finally { await Promise.all([close(primary), close(secondary)]); }
});
