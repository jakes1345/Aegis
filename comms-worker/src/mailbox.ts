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
/**
 * Per sender. A reconnecting phone sends a receipt for every message it
 * missed, so bursts are normal; the recipient is protected by MAX_QUEUE.
 */
const SEND_LIMIT_PER_MINUTE = 600;
const SEND_BURST = 300;
/** Registration attempts per client address, against guessing the enrollment secret. */
const REGISTER_LIMIT_PER_MINUTE = 10;
/** After one push wake, further envelopes do not wake again until the phone has fetched, or this long. */
const WAKE_COALESCE_MS = 30_000;
/** One-time key ids already handed out, remembered so a key is never offered twice. */
const MAX_CLAIMED_IDS = 2_000;
const CLEANUP_INTERVAL_MS = 24 * 60 * 60_000;
/** How long a live socket has to ack an envelope before the owner is also woken by push. */
const UNACKED_WAKE_MS = 8_000;
/**
 * A connected phone hears from the relay at least this often. The heartbeat
 * keeps carrier NAT from forgetting an idle connection, and briefly wakes a
 * sleeping phone, which lets it notice a connection that has died; the app
 * reconnects when it has heard nothing for a few heartbeats.
 */
const HEARTBEAT_MS = 120_000;
/** Durable Object storage deletes at most this many keys per call. */
const DELETE_BATCH = 128;

interface NonceRecord {
  [nonce: string]: number;
}

/** Envelopes handed to a socket that must be acked by `due`, or the owner is woken by push. */
interface WakeCheck {
  due: number;
  ids: string[];
}

/** A contact's public identity, without claiming a one-time key. */
export interface IdentityKeys {
  number: string;
  ed25519: string;
  curve25519: string;
  sealing: string;
  signature: string;
}

export class Mailbox extends DurableObject<Env> {
  // ── Registration and keys ────────────────────────────────────────────

  /** Claims this number for a profile. False when the number is already taken. */
  async claim(profile: Profile, oneTimeKeys: SignedKey[]): Promise<boolean> {
    const existing = await this.ctx.storage.get<Profile>("profile");
    if (existing) return false;
    await this.ctx.storage.put("profile", profile);
    await this.ctx.storage.put("otks", oneTimeKeys.slice(0, MAX_ONE_TIME_KEYS));
    await this.ctx.storage.put("cleanupAt", Date.now() + CLEANUP_INTERVAL_MS);
    await this.scheduleAlarm();
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
    // A key already handed out is spent on the owner's phone; offering it again
    // would make the next contact's first message undecryptable.
    const claimed = new Set((await this.ctx.storage.get<string[]>("claimedOtks")) ?? []);
    const known = new Set(existing.map((k) => k.id));
    const merged = existing.concat(oneTimeKeys.filter((k) => !known.has(k.id) && !claimed.has(k.id))).slice(-MAX_ONE_TIME_KEYS);
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
    if (oneTimeKey) {
      const claimed = (await this.ctx.storage.get<string[]>("claimedOtks")) ?? [];
      claimed.push(oneTimeKey.id);
      await this.ctx.storage.put({ otks, claimedOtks: claimed.slice(-MAX_CLAIMED_IDS) });
    }
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

  /** The owner's public identity, for confirming who sent a message; claims no key. */
  async identity(requireListed: boolean): Promise<IdentityKeys | null> {
    const profile = await this.ctx.storage.get<Profile>("profile");
    if (!profile) return null;
    if (requireListed && !profile.listed) return null;
    return {
      number: profile.number,
      ed25519: profile.ed25519,
      curve25519: profile.curve25519,
      sealing: profile.sealing,
      signature: profile.signature,
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

  /**
   * A token bucket per action: [perMinute] refill, up to [burst] saved up.
   * Returns whether this request may go ahead and, if not, how many seconds
   * until it may (sent to the client as Retry-After).
   */
  async rateOk(action: string, perMinute: number, burst: number = perMinute): Promise<{ ok: boolean; retryAfter: number }> {
    const key = `bucket:${action}`;
    const now = Date.now();
    const b = (await this.ctx.storage.get<{ tokens: number; at: number }>(key)) ?? { tokens: burst, at: now };
    b.tokens = Math.min(burst, b.tokens + ((now - b.at) * perMinute) / 60_000);
    b.at = now;
    const ok = b.tokens >= 1;
    if (ok) b.tokens -= 1;
    await this.ctx.storage.put(key, b);
    return { ok, retryAfter: ok ? 0 : Math.max(1, Math.ceil(((1 - b.tokens) * 60) / perMinute)) };
  }

  static readonly LOOKUP_LIMIT = LOOKUP_LIMIT_PER_MINUTE;
  static readonly SEND_LIMIT = SEND_LIMIT_PER_MINUTE;
  static readonly SEND_BURST = SEND_BURST;
  static readonly REGISTER_LIMIT = REGISTER_LIMIT_PER_MINUTE;
  static readonly TURN_LIMIT = 20;

  /** storage.delete() takes at most 128 keys; larger sets go in batches. */
  private async deleteKeys(keys: string[]): Promise<number> {
    let removed = 0;
    for (let i = 0; i < keys.length; i += DELETE_BATCH) {
      removed += await this.ctx.storage.delete(keys.slice(i, i + DELETE_BATCH));
    }
    return removed;
  }

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
    const endpoint = await this.ctx.storage.get<string>("push");
    if (endpoint) {
      if (delivered) {
        // A socket whose phone lost its network still accepts a send without
        // error, so "delivered" only means handed over. If the phone has not
        // acked within a few seconds, the alarm wakes it through push as well.
        const check = (await this.ctx.storage.get<WakeCheck>("wakeCheck")) ?? { due: Date.now() + UNACKED_WAKE_MS, ids: [] };
        check.ids.push(id);
        await this.ctx.storage.put("wakeCheck", { due: check.due, ids: check.ids.slice(-200) });
        await this.scheduleAlarm();
      } else {
        this.ctx.waitUntil(this.wakeOwner(endpoint));
      }
    }
    return { ok: true, id };
  }

  /**
   * Wakes the owner's phone through push. One wake covers everything queued
   * until the phone next fetches, so a busy chat does not use up the push
   * distributor's quota and leave the next call offer unannounced. An
   * endpoint the distributor says is gone is forgotten.
   */
  private async wakeOwner(endpoint: string, force = false): Promise<void> {
    const now = Date.now();
    const last = (await this.ctx.storage.get<number>("lastWakeAt")) ?? 0;
    const fetched = (await this.ctx.storage.get<number>("lastFetchAt")) ?? 0;
    if (!force && last > fetched && now - last < WAKE_COALESCE_MS) return;
    await this.ctx.storage.put("lastWakeAt", now);
    const status = await wake(endpoint);
    if (status === 404 || status === 410) await this.ctx.storage.delete("push");
  }

  /**
   * Sets the alarm for whichever comes first: the unacked-delivery check, the
   * next heartbeat while a phone is connected, or the daily cleanup.
   */
  private async scheduleAlarm(rearm = false): Promise<void> {
    const cleanupAt = (await this.ctx.storage.get<number>("cleanupAt")) ?? Date.now() + CLEANUP_INTERVAL_MS;
    const check = await this.ctx.storage.get<WakeCheck>("wakeCheck");
    let next = check ? Math.min(cleanupAt, check.due) : cleanupAt;
    if (this.ctx.getWebSockets().length > 0) next = Math.min(next, Date.now() + HEARTBEAT_MS);
    // From inside alarm() the alarm being handled may still read as set, so the
    // handler re-arms outright; anyone else only ever brings the alarm forward.
    const current = rearm ? null : await this.ctx.storage.getAlarm();
    if (current === null || current > next) await this.ctx.storage.setAlarm(next);
  }

  /** Queued envelopes, oldest first, one page at a time (acked ones are deleted, so the next call gets the next page). */
  async inbox(limit = 200): Promise<Envelope[]> {
    await this.ctx.storage.put("lastFetchAt", Date.now());
    const map = await this.ctx.storage.list<Envelope>({ prefix: "q:", limit });
    return [...map.values()].sort((a, b) => a.ts - b.ts || a.id.localeCompare(b.id));
  }

  /** Deletes envelopes the owner has stored locally. */
  async ack(ids: string[]): Promise<number> {
    const keys = ids.map((id) => `q:${id}`);
    const removed = await this.deleteKeys(keys);
    const count = (await this.ctx.storage.get<number>("queueCount")) ?? 0;
    await this.ctx.storage.put("queueCount", Math.max(0, count - removed));
    return removed;
  }

  /**
   * Three jobs share the object's one alarm: the heartbeat to a connected
   * phone, waking a phone that did not ack a live delivery in time, and the
   * daily drop of envelopes past their TTL.
   */
  override async alarm(): Promise<void> {
    const now = Date.now();
    for (const ws of this.ctx.getWebSockets()) {
      try {
        ws.send(JSON.stringify({ type: "hb", ts: now }));
      } catch {
        // a dead socket; the phone reconnects when it notices
      }
    }
    try {
      const check = await this.ctx.storage.get<WakeCheck>("wakeCheck");
      if (check && check.due <= now) {
        await this.ctx.storage.delete("wakeCheck");
        const still = await this.ctx.storage.get(check.ids.map((id) => `q:${id}`));
        const endpoint = await this.ctx.storage.get<string>("push");
        if (still.size > 0 && endpoint) await this.wakeOwner(endpoint, true);
      }
    } catch (e) {
      console.error("unacked-delivery wake failed", e);
    }
    const cleanupAt = (await this.ctx.storage.get<number>("cleanupAt")) ?? 0;
    if (cleanupAt <= now) {
      await this.cleanup();
      await this.ctx.storage.put("cleanupAt", now + CLEANUP_INTERVAL_MS);
    }
    if (await this.ctx.storage.get("profile")) await this.scheduleAlarm(true);
  }

  /** Drops envelopes older than the TTL. */
  private async cleanup(): Promise<void> {
    // A full mailbox is up to 2,000 envelopes of ~87 KiB, more than the
    // object's memory allows in one list; the scan is paged. Cleanup errors
    // are swallowed so the alarm is always re-armed: the platform stops
    // retrying a throwing alarm after six attempts.
    try {
      const cutoff = Date.now() - ENVELOPE_TTL_MS;
      let startAfter: string | undefined;
      let removed = 0;
      for (;;) {
        const page = await this.ctx.storage.list<Envelope>({ prefix: "q:", startAfter, limit: DELETE_BATCH });
        if (page.size === 0) break;
        const keys = [...page.keys()];
        const stale = [...page].filter(([, e]) => e.ts < cutoff).map(([key]) => key);
        if (stale.length > 0) removed += await this.deleteKeys(stale);
        startAfter = keys[keys.length - 1];
      }
      if (removed > 0) {
        const count = (await this.ctx.storage.get<number>("queueCount")) ?? 0;
        await this.ctx.storage.put("queueCount", Math.max(0, count - removed));
      }
    } catch (e) {
      console.error("envelope cleanup failed", e);
    }
  }

  // ── Live delivery ───────────────────────────────────────────────────

  /** Upgrades an already-authenticated request to a hibernatable WebSocket. */
  override async fetch(request: Request): Promise<Response> {
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return new Response("Expected WebSocket", { status: 426 });
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair) as [WebSocket, WebSocket];
    // One identity is one phone: a socket it left behind (on a network it has
    // since left, say) is dead, and would otherwise count as "delivered".
    for (const old of this.ctx.getWebSockets()) {
      try {
        old.close(4000, "replaced by a new connection");
      } catch {
        // already closed
      }
    }
    this.ctx.acceptWebSocket(server);
    // Anything already waiting goes out immediately, a page at a time; the
    // client fetches the rest through /v1/inbox when told there is more.
    const pending = await this.inbox();
    for (const envelope of pending) server.send(JSON.stringify({ type: "envelope", envelope }));
    server.send(JSON.stringify({ type: "ready", pending: pending.length, more: pending.length >= 200 }));
    await this.scheduleAlarm();
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
 * nothing: the app fetches its inbox over the authenticated channel. Returns
 * the distributor's HTTP status, or 0 when it could not be reached.
 */
async function wake(endpoint: string): Promise<number> {
  try {
    const res = await fetch(endpoint, {
      method: "POST",
      headers: { "content-type": "text/plain", ttl: "86400", urgency: "high" },
      body: "wake",
    });
    if (!res.ok) console.error("push wake refused", res.status);
    return res.status;
  } catch (e) {
    console.error("push wake failed", e);
    return 0;
  }
}
