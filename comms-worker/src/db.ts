import type { Env } from "./env";

export interface MessageRow {
  id: string;
  seq: number;
  direction: "in" | "out";
  peer: string;
  body: string;
  media: string;
  status: string;
  error: string | null;
  ts: number;
  updated: number;
}

export interface DeviceRow {
  id: string;
  token_hash: string;
  name: string;
  fcm_token: string | null;
  created_at: number;
  last_seen: number;
}

export interface MediaItem {
  url: string;
  contentType: string;
}

export interface NewMessage {
  id: string;
  direction: "in" | "out";
  peer: string;
  body: string;
  media: MediaItem[];
  status: string;
  error?: string | null;
  ts: number;
}

/** What the phone receives for each message. */
export interface MessageDto {
  id: string;
  seq: number;
  direction: "in" | "out";
  peer: string;
  body: string;
  media: MediaItem[];
  status: string;
  error: string | null;
  ts: number;
  updated: number;
}

export function toDto(row: MessageRow): MessageDto {
  let media: MediaItem[] = [];
  try {
    const parsed: unknown = JSON.parse(row.media);
    if (Array.isArray(parsed)) media = parsed as MediaItem[];
  } catch {
    media = [];
  }
  return {
    id: row.id,
    seq: row.seq,
    direction: row.direction,
    peer: row.peer,
    body: row.body,
    media,
    status: row.status,
    error: row.error,
    ts: row.ts,
    updated: row.updated,
  };
}

/**
 * Inserts a message with the next sequence number, applying any status callback
 * that beat it here. Returns the stored row, or the existing one if the id was
 * already present (Twilio retries webhooks, so inserts must be idempotent).
 */
export async function insertMessage(env: Env, m: NewMessage): Promise<MessageRow> {
  const existing = await env.DB.prepare("SELECT * FROM messages WHERE id = ?").bind(m.id).first<MessageRow>();
  if (existing) return existing;

  const pending = await env.DB.prepare("SELECT status, error, updated FROM pending_status WHERE id = ?")
    .bind(m.id)
    .first<{ status: string; error: string | null; updated: number }>();
  const status = pending?.status ?? m.status;
  const error = pending?.error ?? m.error ?? null;
  const updated = pending?.updated ?? m.ts;

  // seq is assigned inside the statement so two concurrent inserts cannot pick
  // the same value; the UNIQUE constraint would reject a collision, and the
  // retry below re-reads the max.
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      await env.DB.batch([
        env.DB.prepare(
          `INSERT INTO messages (id, seq, direction, peer, body, media, status, error, ts, updated)
           VALUES (?, (SELECT COALESCE(MAX(seq), 0) + 1 FROM messages), ?, ?, ?, ?, ?, ?, ?, ?)`,
        ).bind(m.id, m.direction, m.peer, m.body, JSON.stringify(m.media), status, error, m.ts, updated),
        env.DB.prepare("DELETE FROM pending_status WHERE id = ?").bind(m.id),
      ]);
      break;
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      if (attempt === 2 || !/UNIQUE/i.test(message)) throw e;
    }
  }
  const row = await env.DB.prepare("SELECT * FROM messages WHERE id = ?").bind(m.id).first<MessageRow>();
  if (!row) throw new Error("message vanished after insert");
  return row;
}

/**
 * Applies a delivery status. Twilio's states only move forward (queued → sent →
 * delivered/undelivered/failed), so an older callback arriving late is ignored.
 * A callback for a message not yet stored is parked in pending_status.
 */
export async function applyStatus(env: Env, id: string, status: string, error: string | null, now: number): Promise<boolean> {
  const rank = statusRank(status);
  const row = await env.DB.prepare("SELECT status FROM messages WHERE id = ?").bind(id).first<{ status: string }>();
  if (!row) {
    await env.DB.prepare(
      `INSERT INTO pending_status (id, status, error, updated) VALUES (?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET status = excluded.status, error = excluded.error, updated = excluded.updated`,
    )
      .bind(id, status, error, now)
      .run();
    return false;
  }
  if (rank < statusRank(row.status)) return false;
  await env.DB.prepare("UPDATE messages SET status = ?, error = ?, updated = ? WHERE id = ?")
    .bind(status, error, now, id)
    .run();
  return true;
}

function statusRank(status: string): number {
  switch (status) {
    case "accepted":
      return 0;
    case "queued":
      return 1;
    case "sending":
      return 2;
    case "sent":
      return 3;
    case "delivered":
    case "undelivered":
    case "failed":
    case "received":
      return 4;
    default:
      return 0;
  }
}

/** Messages with seq greater than [after], oldest first. */
export async function messagesAfter(env: Env, after: number, limit: number): Promise<MessageRow[]> {
  const res = await env.DB.prepare("SELECT * FROM messages WHERE seq > ? ORDER BY seq ASC LIMIT ?")
    .bind(after, limit)
    .all<MessageRow>();
  return res.results;
}

/** Messages whose status changed after [since] (epoch millis), for delivery-state sync. */
export async function messagesUpdatedSince(env: Env, since: number, limit: number): Promise<MessageRow[]> {
  const res = await env.DB.prepare("SELECT * FROM messages WHERE updated > ? ORDER BY updated ASC LIMIT ?")
    .bind(since, limit)
    .all<MessageRow>();
  return res.results;
}

export async function deviceByTokenHash(env: Env, tokenHash: string): Promise<DeviceRow | null> {
  return env.DB.prepare("SELECT * FROM devices WHERE token_hash = ?").bind(tokenHash).first<DeviceRow>();
}

export async function insertDevice(env: Env, d: DeviceRow): Promise<void> {
  await env.DB.prepare(
    "INSERT INTO devices (id, token_hash, name, fcm_token, created_at, last_seen) VALUES (?, ?, ?, ?, ?, ?)",
  )
    .bind(d.id, d.token_hash, d.name, d.fcm_token, d.created_at, d.last_seen)
    .run();
}

export async function touchDevice(env: Env, id: string, now: number): Promise<void> {
  await env.DB.prepare("UPDATE devices SET last_seen = ? WHERE id = ?").bind(now, id).run();
}

export async function setFcmToken(env: Env, id: string, token: string | null): Promise<void> {
  await env.DB.prepare("UPDATE devices SET fcm_token = ? WHERE id = ?").bind(token, id).run();
}

export async function deleteDevice(env: Env, id: string): Promise<void> {
  await env.DB.prepare("DELETE FROM devices WHERE id = ?").bind(id).run();
}

export async function fcmTokens(env: Env): Promise<{ id: string; fcm_token: string }[]> {
  const res = await env.DB.prepare("SELECT id, fcm_token FROM devices WHERE fcm_token IS NOT NULL").all<{
    id: string;
    fcm_token: string;
  }>();
  return res.results;
}

export async function listDevices(env: Env): Promise<Omit<DeviceRow, "token_hash">[]> {
  const res = await env.DB.prepare("SELECT id, name, fcm_token, created_at, last_seen FROM devices ORDER BY created_at").all<
    Omit<DeviceRow, "token_hash">
  >();
  return res.results;
}
