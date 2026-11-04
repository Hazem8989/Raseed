export interface Env {
  SYNC_ROOMS: DurableObjectNamespace;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const MAX_FRAME_CHARS = 700_000;
const MAX_CONNECTIONS_PER_STORE = 64;
const MAX_MESSAGES_PER_SOCKET = 60;
const MESSAGE_WINDOW_MS = 10_000;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/healthz") {
      return Response.json({ service: "the-daily-shop-relay", status: "ok", protocol: 1 });
    }

    const match = url.pathname.match(/^\/v1\/ws\/([^/]+)\/([^/]+)$/);
    if (!match) return new Response("Not found", { status: 404 });
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket upgrade required", { status: 426 });
    }
    const [, storeId, deviceId] = match;
    if (!UUID.test(storeId) || !UUID.test(deviceId)) {
      return new Response("Invalid store or device identifier", { status: 400 });
    }
    const id = env.SYNC_ROOMS.idFromName(storeId);
    return env.SYNC_ROOMS.get(id).fetch(
      new Request(`https://relay.internal/connect/${deviceId}`, request)
    );
  },
} satisfies ExportedHandler<Env>;

export class SyncRoom implements DurableObject {
  constructor(private readonly state: DurableObjectState) {}

  async fetch(request: Request): Promise<Response> {
    const match = new URL(request.url).pathname.match(/^\/connect\/([^/]+)$/);
    if (!match || request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("Not found", { status: 404 });
    }
    const deviceId = match[1];
    if (!UUID.test(deviceId)) return new Response("Invalid device identifier", { status: 400 });

    // Reject duplicate sessions for one device and cap room occupancy before accepting.
    if (this.state.getWebSockets(deviceId).length > 0) {
      return new Response("Device already connected", { status: 409 });
    }
    if (this.state.getWebSockets().length >= MAX_CONNECTIONS_PER_STORE) {
      return new Response("Store relay capacity reached", { status: 503 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.state.acceptWebSocket(server, [deviceId]);
    server.send(JSON.stringify({ type: "ready", protocol: 1, device_id: deviceId }));
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(sender: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const senderDeviceIdForLimit = this.state.getTags(sender)[0];
    if (!senderDeviceIdForLimit) {
      sender.send(JSON.stringify({ type: "error", code: "UNAUTHENTICATED_SOCKET" }));
      try { sender.close(1008, "missing device identity"); } catch { /* already closed */ }
      return;
    }
    const now = Date.now();
    // Persist the fixed-window counter so Durable Object hibernation/restarts
    // cannot reset the per-device message budget.
    const rateLimited = await this.state.storage.transaction(async transaction => {
      const key = `rate:${senderDeviceIdForLimit}`;
      const current = await transaction.get<{ startedAt: number; count: number }>(key);
      const next = !current || now - current.startedAt >= MESSAGE_WINDOW_MS
        ? { startedAt: now, count: 1 }
        : { startedAt: current.startedAt, count: current.count + 1 };
      await transaction.put(key, next);
      return next.count > MAX_MESSAGES_PER_SOCKET;
    });
    if (rateLimited) {
      sender.send(JSON.stringify({ type: "error", code: "RATE_LIMITED" }));
      try { sender.close(1008, "rate limited"); } catch { /* already closed */ }
      return;
    }
    if (typeof message !== "string" || message.length > MAX_FRAME_CHARS) {
      sender.send(JSON.stringify({ type: "error", code: "INVALID_FRAME" }));
      return;
    }
    let frame: { type?: string; to_device_id?: string; frame_id?: string; ciphertext?: string };
    try { frame = JSON.parse(message); } catch {
      sender.send(JSON.stringify({ type: "error", code: "INVALID_JSON" }));
      return;
    }
    if (frame.type !== "relay" || !frame.to_device_id || !UUID.test(frame.to_device_id) ||
        !frame.frame_id || frame.frame_id.length > 80 || !frame.ciphertext ||
        frame.ciphertext.length > MAX_FRAME_CHARS) {
      sender.send(JSON.stringify({ type: "error", code: "INVALID_RELAY_ENVELOPE" }));
      return;
    }

    const senderTags = this.state.getTags(sender);
    const senderDeviceId = senderTags[0];
    if (!senderDeviceId || senderDeviceId === frame.to_device_id) {
      sender.send(JSON.stringify({ type: "error", code: "INVALID_ROUTE" }));
      return;
    }

    const recipients = this.state.getWebSockets(frame.to_device_id);
    if (recipients.length === 0) {
      sender.send(JSON.stringify({ type: "not_delivered", frame_id: frame.frame_id, retryable: true }));
      return;
    }
    const routed = JSON.stringify({
      type: "relay",
      from_device_id: senderDeviceId,
      frame_id: frame.frame_id,
      ciphertext: frame.ciphertext,
    });
    let delivered = false;
    for (const recipient of recipients) {
      try { recipient.send(routed); delivered = true; } catch { /* disconnected peer; sender retries */ }
    }
    sender.send(JSON.stringify({
      type: delivered ? "forwarded" : "not_delivered",
      frame_id: frame.frame_id,
      retryable: !delivered,
    }));
  }

  async webSocketClose(socket: WebSocket): Promise<void> {
    try { socket.close(1000, "closed"); } catch { /* already closed */ }
  }

  async webSocketError(socket: WebSocket): Promise<void> {
    try { socket.close(1011, "transport error"); } catch { /* already closed */ }
  }
}
