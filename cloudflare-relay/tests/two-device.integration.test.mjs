import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";

const baseUrl = "http://127.0.0.1:8787";
let worker;
let workerOutput = "";

async function waitForWorker() {
  const deadline = Date.now() + 60_000;
  let lastError;
  while (Date.now() < deadline) {
    if (worker?.exitCode !== null && worker?.exitCode !== undefined) {
      throw new Error(`wrangler dev exited with code ${worker.exitCode}. Output:\n${workerOutput.slice(-12000)}`);
    }
    try {
      const response = await fetch(`${baseUrl}/healthz`);
      if (response.ok) return;
    } catch (error) {
      lastError = error;
    }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  throw new Error(`Worker did not become healthy: ${String(lastError)}. Wrangler output:\n${workerOutput.slice(-12000)}`);
}

function connect(storeId, deviceId) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(
      `ws://127.0.0.1:8787/v1/ws/${storeId}/${deviceId}`
    );
    const timeout = setTimeout(() => reject(new Error("WebSocket ready timeout")), 10_000);
    socket.addEventListener("message", event => {
      const message = JSON.parse(String(event.data));
      if (message.type === "ready") {
        clearTimeout(timeout);
        resolve(socket);
      }
    });
    socket.addEventListener("error", () => {
      clearTimeout(timeout);
      reject(new Error("WebSocket connection failed"));
    }, { once: true });
  });
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

function close(socket) {
  if (socket && socket.readyState < WebSocket.CLOSING) socket.close();
}

before(async () => {
  worker = spawn("npx", ["wrangler", "dev", "--local", "--ip", "127.0.0.1", "--port", "8787", "--log-level", "debug"], {
    cwd: process.cwd(),
    stdio: ["ignore", "pipe", "pipe"],
    env: process.env,
  });
  worker.stdout.setEncoding("utf8");
  worker.stderr.setEncoding("utf8");
  worker.stdout.on("data", chunk => { workerOutput = (workerOutput + chunk).slice(-16000); });
  worker.stderr.on("data", chunk => { workerOutput = (workerOutput + chunk).slice(-16000); });
  await waitForWorker();
});

after(() => {
  if (worker && worker.exitCode === null) worker.kill("SIGTERM");
});

test("two independent devices exchange a frame and offline peer remains retryable", async () => {
  const storeId = crypto.randomUUID();
  const primaryId = crypto.randomUUID();
  const secondaryId = crypto.randomUUID();
  let primary;
  let secondary;
  try {
    primary = await connect(storeId, primaryId);
    secondary = await connect(storeId, secondaryId);

    const frameId = crypto.randomUUID();
    const ciphertext = "opaque-test-ciphertext-from-independent-secondary";
    const receivedPromise = nextMessage(primary, message =>
      message.type === "relay" && message.frame_id === frameId
    );
    const ackPromise = nextMessage(secondary, message =>
      message.type === "forwarded" && message.frame_id === frameId
    );

    secondary.send(JSON.stringify({
      type: "relay",
      to_device_id: primaryId,
      frame_id: frameId,
      ciphertext,
    }));

    const [received, ack] = await Promise.all([receivedPromise, ackPromise]);
    assert.equal(received.from_device_id, secondaryId);
    assert.equal(received.ciphertext, ciphertext);
    assert.equal(ack.retryable, false);

    const offlineFrameId = crypto.randomUUID();
    const offlineAckPromise = nextMessage(secondary, message =>
      message.type === "not_delivered" && message.frame_id === offlineFrameId
    );
    secondary.send(JSON.stringify({
      type: "relay",
      to_device_id: crypto.randomUUID(),
      frame_id: offlineFrameId,
      ciphertext,
    }));
    const offlineAck = await offlineAckPromise;
    assert.equal(offlineAck.retryable, true);
  } finally {
    close(primary);
    close(secondary);
  }
});
