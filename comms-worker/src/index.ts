import { authenticate, enroll } from "./auth";
import {
  applyStatus,
  deleteDevice,
  fcmTokens,
  insertMessage,
  listDevices,
  messagesAfter,
  messagesUpdatedSince,
  setFcmToken,
  toDto,
  type MediaItem,
} from "./db";
import { HttpError, json, normalisePhone, type Env } from "./env";
import { sendData } from "./fcm";
import { emptyTwiml, fetchMessage, sendSms, verifyWebhook } from "./twilio";

/**
 * Aegis comms relay.
 *
 * Twilio-facing (signature-verified):
 *   POST /twilio/sms      inbound SMS/MMS
 *   POST /twilio/status   delivery status for outbound messages
 *
 * Phone-facing (bearer token from enrollment):
 *   POST /api/enroll                 {secret, name}         → {deviceId, token, number}
 *   GET  /api/status                                        → {number, deviceId, devices}
 *   PUT  /api/device/fcm             {token}                → registers the push token
 *   DELETE /api/device                                      → unpairs this phone
 *   GET  /api/messages?after=N       sync cursor            → {messages, next}
 *   GET  /api/messages/updates?since=T                      → status changes after epoch T
 *   POST /api/messages               {to, body}             → the stored outbound message
 *   POST /api/messages/:id/refresh                          → re-reads Twilio's status
 */
export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    try {
      return await route(request, env, ctx);
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.message }, e.status);
      console.error(e);
      return json({ error: "Internal error" }, 500);
    }
  },
} satisfies ExportedHandler<Env>;

async function route(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";
  const now = Date.now();

  if (request.method === "GET" && path === "/") {
    return json({ service: "aegis-comms", ok: true });
  }

  // ── Twilio webhooks ──────────────────────────────────────────────────────
  if (path === "/twilio/sms" || path === "/twilio/status") {
    if (request.method !== "POST") throw new HttpError(405, "POST only");
    const form = new URLSearchParams(await request.text());
    await verifyWebhook(env, request, form);
    if (path === "/twilio/sms") return inboundSms(env, ctx, form, now);
    return statusCallback(env, form, now);
  }

  // ── Enrollment ───────────────────────────────────────────────────────────
  if (path === "/api/enroll") {
    if (request.method !== "POST") throw new HttpError(405, "POST only");
    const body = await readJson<{ secret?: string; name?: string }>(request);
    if (typeof body.secret !== "string") throw new HttpError(400, "secret required");
    const { device, token } = await enroll(env, body.secret, typeof body.name === "string" ? body.name : "Aegis", now);
    return json({ deviceId: device.id, token, number: env.TWILIO_NUMBER ?? null });
  }

  // ── Authenticated API ────────────────────────────────────────────────────
  if (!path.startsWith("/api/")) throw new HttpError(404, "Not found");
  const device = await authenticate(env, request, now);

  if (path === "/api/status" && request.method === "GET") {
    return json({
      number: env.TWILIO_NUMBER ?? null,
      deviceId: device.id,
      pushRegistered: device.fcm_token !== null,
      devices: await listDevices(env),
    });
  }

  if (path === "/api/device/fcm" && request.method === "PUT") {
    const body = await readJson<{ token?: string | null }>(request);
    const token = typeof body.token === "string" && body.token.length > 0 ? body.token : null;
    await setFcmToken(env, device.id, token);
    return json({ ok: true });
  }

  if (path === "/api/device" && request.method === "DELETE") {
    await deleteDevice(env, device.id);
    return json({ ok: true });
  }

  if (path === "/api/messages" && request.method === "GET") {
    const after = Number(url.searchParams.get("after") ?? "0");
    if (!Number.isFinite(after) || after < 0) throw new HttpError(400, "after must be a non-negative integer");
    const rows = await messagesAfter(env, Math.floor(after), pageSize(env));
    const next = rows.length > 0 ? rows[rows.length - 1]!.seq : Math.floor(after);
    return json({ messages: rows.map(toDto), next, more: rows.length === pageSize(env) });
  }

  if (path === "/api/messages/updates" && request.method === "GET") {
    const since = Number(url.searchParams.get("since") ?? "0");
    if (!Number.isFinite(since) || since < 0) throw new HttpError(400, "since must be epoch millis");
    const rows = await messagesUpdatedSince(env, Math.floor(since), pageSize(env));
    return json({ messages: rows.map(toDto), now });
  }

  if (path === "/api/messages" && request.method === "POST") {
    const body = await readJson<{ to?: string; body?: string }>(request);
    const to = typeof body.to === "string" ? normalisePhone(body.to) : null;
    if (!to) throw new HttpError(400, "to must be a phone number");
    if (typeof body.body !== "string" || body.body.trim().length === 0) throw new HttpError(400, "body required");
    if (body.body.length > 1600) throw new HttpError(400, "body exceeds 1600 characters");
    const sent = await sendSms(env, to, body.body, `${url.origin}/twilio/status`);
    const row = await insertMessage(env, {
      id: sent.sid,
      direction: "out",
      peer: to,
      body: body.body,
      media: [],
      status: sent.status,
      error: sent.errorCode !== null ? `${sent.errorCode}: ${sent.errorMessage ?? ""}` : null,
      ts: now,
    });
    // Other paired phones learn about the send the same way they learn about a
    // receipt.
    ctx.waitUntil(pushAll(env, { kind: "message", seq: String(row.seq) }, device.id));
    return json({ message: toDto(row) }, 201);
  }

  const refresh = /^\/api\/messages\/([A-Za-z0-9]+)\/refresh$/.exec(path);
  if (refresh && refresh[1] && request.method === "POST") {
    const remote = await fetchMessage(env, refresh[1]);
    if (!remote) throw new HttpError(404, "Twilio has no such message");
    await applyStatus(
      env,
      remote.sid,
      remote.status,
      remote.errorCode !== null ? `${remote.errorCode}: ${remote.errorMessage ?? ""}` : null,
      now,
    );
    const rows = await messagesUpdatedSince(env, now - 1, 1);
    return json({ message: rows[0] ? toDto(rows[0]) : null, status: remote.status });
  }

  throw new HttpError(404, "Not found");
}

async function inboundSms(env: Env, ctx: ExecutionContext, form: URLSearchParams, now: number): Promise<Response> {
  const sid = form.get("MessageSid");
  const from = form.get("From");
  if (!sid || !from) throw new HttpError(400, "MessageSid and From required");
  const numMedia = Number(form.get("NumMedia") ?? "0");
  const media: MediaItem[] = [];
  for (let i = 0; i < numMedia; i++) {
    const mediaUrl = form.get(`MediaUrl${i}`);
    const contentType = form.get(`MediaContentType${i}`) ?? "application/octet-stream";
    if (mediaUrl) media.push({ url: mediaUrl, contentType });
  }
  const row = await insertMessage(env, {
    id: sid,
    direction: "in",
    peer: normalisePhone(from) ?? from,
    body: form.get("Body") ?? "",
    media,
    status: "received",
    ts: now,
  });
  ctx.waitUntil(pushAll(env, { kind: "message", seq: String(row.seq) }));
  return emptyTwiml();
}

async function statusCallback(env: Env, form: URLSearchParams, now: number): Promise<Response> {
  const sid = form.get("MessageSid");
  const status = form.get("MessageStatus");
  if (!sid || !status) throw new HttpError(400, "MessageSid and MessageStatus required");
  const code = form.get("ErrorCode");
  const error = code ? `${code}: ${form.get("ErrorMessage") ?? ""}`.trim() : null;
  await applyStatus(env, sid, status, error, now);
  return emptyTwiml();
}

/** Wakes every paired phone except [exceptDeviceId], dropping dead tokens. */
async function pushAll(env: Env, data: Record<string, string>, exceptDeviceId?: string): Promise<void> {
  let targets: { id: string; fcm_token: string }[];
  try {
    targets = await fcmTokens(env);
  } catch (e) {
    console.error("fcmTokens failed", e);
    return;
  }
  await Promise.all(
    targets
      .filter((t) => t.id !== exceptDeviceId)
      .map(async (t) => {
        try {
          const result = await sendData(env, t.fcm_token, data);
          if (result === "unregistered") await setFcmToken(env, t.id, null);
        } catch (e) {
          console.error("push failed for device", t.id, e);
        }
      }),
  );
}

async function readJson<T>(request: Request): Promise<T> {
  try {
    return (await request.json()) as T;
  } catch {
    throw new HttpError(400, "Body must be JSON");
  }
}

function pageSize(env: Env): number {
  const n = Number(env.SYNC_PAGE_SIZE ?? "200");
  return Number.isFinite(n) && n > 0 ? Math.min(Math.floor(n), 500) : 200;
}
