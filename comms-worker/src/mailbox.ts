import { DurableObject } from "cloudflare:workers";
import { verifyEd25519 } from "./auth";
import type { Env } from "./env";

/**
 * One Durable Object per Aegis number: the device's published keys, its queue
 * of sealed envelopes, its live WebSocket(s) and its push endpoint.
 *
 * The relay never sees a message: envelopes are opaque bytes sealed to the
 * owner's sealing key, and they carry no sender. What this object knows is the
 * owner's public keys (which are public), how many envelopes are waiting, and
 * the push endpoint the owner chose to be woken through.
 */

export interface SignedKey {
  id: string;
  key: string;
  signature: string;
}

export interface Profile {
  number: string;
  ed25519: string;
  curve25519: string;
  sealing: string;
  /** Signature by ed25519 over `curve25519|sealing`, so a relay cannot swap keys. */
  signature: string;
  fallback: SignedKey | null;
  /** Whether the number can be looked up. Unlisted numbers are reached by QR only. */
  listed: boolean;
  createdAt: number;
  lastSeen: number;
}

export interface Envelope {
  id: string;
  ts: number;
  /** Base64 of the sealed envelope bytes. */
  data: string;
}

/** What a lookup returns: the identity and one key to start a session with. */
export interface Bundle {
  number: string;
  ed25519: string;
  curve25519: string;
  sealing: string;
  signature: string;
  oneTimeKey: SignedKey | null;
  fallback: SignedKey | null;
}

const MAX_QUEUE = 2000;
const MAX_ENVELOPE_BYTES = 64 * 1024;
const ENVELOPE_TTL_MS = 30 * 24 * 60 * 60_000;
const MAX_ONE_TIME_KEYS = 100;
const NONCE_WINDOW_MS = 5 * 60_000;
const LOOKUP_LIMIT_PER_MINUTE = 30;

interface NonceRecord {
  [nonce: string]: number;
}

export class Mailbox extends DurableObject<Env> {
  // ── Registration and keys ────────────────────────────────────────────

  /** Claims this number for a profile. False when the number is already taken. */
  async claim(profile: Profile, oneTimeKeys: SignedKey[]): Promise<boolean> {
    const existing = await this.ctx.storage.get<Profile>("profile");
    if (existing) return false;
    await this.ctx.storage.put("profile", profile);
    await this.ctx.storage.put("otks", oneTimeKeys.slice(0, MAX_ONE_TIME_KEYS));
    await this.ctx.storage.setAlarm(Date.now() + ENVELOPE_TTL_MS);
    return true;
  }

  async profile(): Promise<Profile | null> {
    return (await this.ctx.storage.get<Profile>("profile")) ?? null;
  }

  /**
   * Checks a request signature against the owner's key and enforces the
   * single-use nonce. Returns the profile on success, null otherwise.
   */
  async authenticate(payload: string, signature: string, nonce: string, ts: number): Promise<Profile | null> {
    const profile = await this.ctx.storage.get<Profile>("profile");
    if (!profile) return null;
    if (!(await verifyEd25519(profile.ed25519, payload, signature))) return null;
    const nonces = (await this.ctx.storage.get<NonceRecord>("nonces")) ?? {};
    const now = Date.now();
    for (const [n, seen] of Object.entries(nonces)) if (now - seen > NONCE_WINDOW_MS) delete nonces[n];
    if (nonces[nonce] !== undefined) return null;
    nonces[nonce] = ts;
    profile.lastSeen = now;
    await this.ctx.storage.put({ nonces, profile });
    return profile;
  }

  /** Replaces the fallback key and adds one-time keys. */
  async putKeys(oneTimeKeys: SignedKey[], fallback: SignedKey | null): Promise<{ oneTimeKeys: number }> {
    const profile = await this.ctx.storage.get<Profile>("profile");
    if (!profile) throw new Error("unregistered");
    const existing = (await this.ctx.storage.get<SignedKey[]>("otks")) ?? [];
    const known = new Set(existing.map((k) => k.id));
    const merged = existing.concat(oneTimeKeys.filter((k) => !known.has(k.id))).slice(-MAX_ONE_TIME_KEYS);
    if (fallback) profile.fallback = fallback;
    await this.ctx.storage.put({ otks: merged, profile });
    return { oneTimeKeys: merged.length };
  }

  async oneTimeKeyCount(): Promise<number> {
    return ((await this.ctx.storage.get<SignedKey[]>("otks")) ?? []).length;
  }

  /**
   * The bundle a contact needs. Claims one one-time key (each is handed out
   * exactly once); when none are left the fallback key is offered instead.
   */
  async bundle(requireListed: boolean): Promise<Bundle | null> {
    const profile = await this.ctx.storage.get<Profile>("profile");
    if (!profile) return null;
    if (requireListed && !profile.listed) return null;
    const otks = (await this.ctx.storage.get<SignedKey[]>("otks")) ?? [];
    const oneTimeKey = otks.shift() ?? null;
    if (oneTimeKey) await this.ctx.storage.put("otks", otks);
    return {
      number: profile.number,
      ed25519: profile.ed25519,
      curve25519: profile.curve25519,
      sealing: profile.sealing,
      signature: profile.signature,
      oneTimeKey,
      fallback: oneTimeKey ? null : profile.fallback,
    };
  }

  async setListed(listed: boolean): Promise<void> {
    const profile = await this.ctx.storage.get<Profile>("profile");
    if (!profile) throw new Error("unregistered");
    profile.listed = listed;
    await this.ctx.storage.put("profile", profile);
  }

  async setPush(endpoint: string | null): Promise<void> {
    if (endpoint) await this.ctx.storage.put("push", endpoint);
    else await this.ctx.storage.delete("push");
  }

  /** A token bucket per action, for the lookups a caller may make. */
  async rateOk(action: string, limitPerMinute: number): Promise<boolean> {
    const key = `rate:${action}`;
    const rec = (await this.ctx.storage.get<{ minute: number; count: number }>(key)) ?? { minute: 0, count: 0 };
    const minute = Math.floor(Date.now() / 60_000);
    if (rec.minute !== minute) {
      rec.minute = minute;
      rec.count = 0;
    }
    rec.count++;
    await this.ctx.storage.put(key, rec);
    return rec.count <= limitPerMinute;
  }

  static readonly LOOKUP_LIMIT = LOOKUP_LIMIT_PER_MINUTE;

  /** Deletes everything about this number. */
  async wipe(): Promise<void> {
    for (const ws of this.ctx.getWebSockets()) {
      try {
        ws.close(1000, "mailbox deleted");
      } catch {
        // already gone
      }
    }
    await this.ctx.storage.deleteAll();
  }

  // ── Envelopes ───────────────────────────────────────────────────────

  /**
   * Queues an envelope for the owner and delivers it to any live socket. Returns
   * false when the mailbox does not exist or is full.
   */
  async enqueue(data: string): Promise<{ ok: true; id: string } | { ok: false; reason: string }> {
    const profile = await this.ctx.storage.get<Profile>("profile");
    if (!profile) return { ok: false, reason: "no such number" };
    if (data.length > (MAX_ENVELOPE_BYTES * 4) / 3 + 4) return { ok: false, reason: "envelope too large" };
    const count = (await this.ctx.storage.get<number>("queueCount")) ?? 0;
    if (count >= MAX_QUEUE) return { ok: false, reason: "mailbox full" };
    const id = `${Date.now().toString(36)}-${crypto.randomUUID()}`;
    const envelope: Envelope = { id, ts: Date.now(), data };
    await this.ctx.storage.put({ [`q:${id}`]: envelope, queueCount: count + 1 });

    const sockets = this.ctx.getWebSockets();
    let delivered = false;
    for (const ws of sockets) {
      try {
        ws.send(JSON.stringify({ type: "envelope", envelope }));
        delivered = true;
      } catch {
        // a dead socket; the owner will fetch on reconnect
      }
    }
    if (!delivered) {
      const endpoint = await this.ctx.storage.get<string>("push");
      if (endpoint) this.ctx.waitUntil(wake(endpoint));
    }
    return { ok: true, id };
  }

  /** Every queued envelope, oldest first. */
  async inbox(limit = 200): Promise<Envelope[]> {
    const map = await this.ctx.storage.list<Envelope>({ prefix: "q:", limit });
    return [...map.values()].sort((a, b) => a.ts - b.ts || a.id.localeCompare(b.id));
  }

  /** Deletes envelopes the owner has stored locally. */
  async ack(ids: string[]): Promise<number> {
    const keys = ids.map((id) => `q:${id}`);
    const removed = await this.ctx.storage.delete(keys);
    const count = (await this.ctx.storage.get<number>("queueCount")) ?? 0;
    await this.ctx.storage.put("queueCount", Math.max(0, count - removed));
    return removed;
  }

  /** Drops envelopes older than the TTL; runs daily. */
  override async alarm(): Promise<void> {
    const cutoff = Date.now() - ENVELOPE_TTL_MS;
    const map = await this.ctx.storage.list<Envelope>({ prefix: "q:" });
    const stale = [...map.values()].filter((e) => e.ts < cutoff).map((e) => `q:${e.id}`);
    if (stale.length > 0) {
      const removed = await this.ctx.storage.delete(stale);
      const count = (await this.ctx.storage.get<number>("queueCount")) ?? 0;
      await this.ctx.storage.put("queueCount", Math.max(0, count - removed));
    }
    if (await this.ctx.storage.get("profile")) await this.ctx.storage.setAlarm(Date.now() + 24 * 60 * 60_000);
  }

  // ── Live delivery ───────────────────────────────────────────────────

  /** Upgrades an already-authenticated request to a hibernatable WebSocket. */
  override async fetch(request: Request): Promise<Response> {
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return new Response("Expected WebSocket", { status: 426 });
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair) as [WebSocket, WebSocket];
    this.ctx.acceptWebSocket(server);
    // Anything already waiting goes out immediately.
    const pending = await this.inbox();
    for (const envelope of pending) server.send(JSON.stringify({ type: "envelope", envelope }));
    server.send(JSON.stringify({ type: "ready", pending: pending.length }));
    return new Response(null, { status: 101, webSocket: client });
  }

  override async webSocketMessage(ws: WebSocket, message: ArrayBuffer | string): Promise<void> {
    if (typeof message !== "string") return;
    let parsed: { type?: string; ids?: unknown };
    try {
      parsed = JSON.parse(message) as { type?: string; ids?: unknown };
    } catch {
      return;
    }
    if (parsed.type === "ack" && Array.isArray(parsed.ids)) {
      const ids = parsed.ids.filter((x): x is string => typeof x === "string");
      await this.ack(ids);
      ws.send(JSON.stringify({ type: "acked", ids }));
    } else if (parsed.type === "ping") {
      ws.send(JSON.stringify({ type: "pong", ts: Date.now() }));
    }
  }

  override async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    try {
      ws.close(code, reason);
    } catch {
      // already closed
    }
  }

  override async webSocketError(ws: WebSocket): Promise<void> {
    try {
      ws.close(1011, "error");
    } catch {
      // already closed
    }
  }
}

/**
 * Wakes the owner's phone through its UnifiedPush endpoint. The body says
 * nothing: the app fetches its inbox over the authenticated channel.
 */
async function wake(endpoint: string): Promise<void> {
  try {
    await fetch(endpoint, {
      method: "POST",
      headers: { "content-type": "text/plain", ttl: "86400", urgency: "high" },
      body: "wake",
    });
  } catch (e) {
    console.error("push wake failed", e);
  }
}
