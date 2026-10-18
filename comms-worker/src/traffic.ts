import { DurableObject } from "cloudflare:workers";
import { hex, sha256Hex, type Env } from "./env";
import {
  BUCKET_SEC,
  GONE_VOTES_TO_REMOVE,
  INCIDENT_TTL_SEC,
  LIVE_WINDOW_SEC,
  PROBE_RETENTION_SEC,
  STOP_KPH,
  STOP_WINDOW_SEC,
  SUDDEN_WINDOW_SEC,
  bucketMedians,
  bucketStart,
  detectStoppage,
  ewma,
  findMergeTarget,
  liveSegments,
  robustBBox,
  round1,
  weekSlot,
  type BBox,
  type IncidentSource,
  type IncidentType,
  type LiveSegment,
  type ProbeRow,
  type WzdxEvent,
} from "./traffic-logic";

/**
 * Crowd-sourced traffic for one map region ("traffic:<region>"), plus one
 * extra instance, "traffic-registry", that only remembers which regions exist
 * so the WZDx cron knows where to deliver work zones.
 *
 * Probes arrive keyed by directed OSM way ("<wayId>:<dir>"). The sender's
 * Aegis number is never stored: each probe carries a salted SHA-256 of it,
 * with a salt that lives only in this object, so samples from one phone can be
 * told apart from another's (one vote per phone per segment, sudden-stop
 * detection) without the relay being able to say whose they were.
 */

export interface ProbeSample {
  seg: string;
  kph: number;
  /** Epoch seconds. */
  t: number;
  /** Optional position of the sample, used to place auto-detected incidents and learn the region's extent. */
  lat?: number;
  lon?: number;
}

export interface Incident {
  id: string;
  type: IncidentType;
  lat: number;
  lon: number;
  seg: string | null;
  source: IncidentSource;
  confirmations: number;
  /** Epoch seconds. */
  createdAt: number;
  expiresAt: number;
  description: string | null;
}

export interface LiveSnapshot {
  now: number;
  segments: Record<string, LiveSegment>;
  incidents: Incident[];
}

type IncidentRow = {
  id: string;
  type: string;
  lat: number;
  lon: number;
  seg: string | null;
  source: string;
  confirmations: number;
  created_at: number;
  active_at: number;
  expires_at: number;
  description: string | null;
  ext_id: string | null;
  feed: string | null;
};

export const REGISTRY_NAME = "traffic-registry";
/** Region instances re-announce themselves to the registry this often, so the cron can forget dead ones. */
const REGISTER_EVERY_SEC = 24 * 60 * 60;
const REGION_FORGET_SEC = 30 * 24 * 60 * 60;
const SEG_POS_RETENTION_SEC = 30 * 24 * 60 * 60;
const BBOX_CACHE_SEC = 60 * 60;
/** Region extent is tracked on a 0.1° grid, so the table stays small however many probes arrive. */
const AREA_CELL_DEG = 0.1;
const LIVE_INCIDENT_LIMIT = 500;
/** Durable Object SQLite takes at most 100 bound parameters per statement. */
const MAX_BINDINGS = 100;
const AUTO_DESCRIPTION = { jam: "Traffic stopped", crash: "Sudden stop — possible crash" } as const;

export class Traffic extends DurableObject<Env> {
  private readonly sql: SqlStorage;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS probes (
        seg TEXT NOT NULL, sender TEXT NOT NULL, kph REAL NOT NULL, t INTEGER NOT NULL,
        UNIQUE (seg, sender, t)
      );
      CREATE INDEX IF NOT EXISTS probes_t ON probes (t);
      CREATE INDEX IF NOT EXISTS probes_seg_t ON probes (seg, t);
      CREATE INDEX IF NOT EXISTS probes_sender_t ON probes (sender, t);
      CREATE TABLE IF NOT EXISTS typical (
        seg TEXT NOT NULL, slot INTEGER NOT NULL, kph REAL NOT NULL,
        PRIMARY KEY (seg, slot)
      ) WITHOUT ROWID;
      CREATE TABLE IF NOT EXISTS seg_pos (seg TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, updated INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS seg_pos_updated ON seg_pos (updated);
      CREATE TABLE IF NOT EXISTS area (cell TEXT PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, updated INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS incidents (
        id TEXT PRIMARY KEY, type TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, seg TEXT,
        source TEXT NOT NULL, confirmations INTEGER NOT NULL,
        created_at INTEGER NOT NULL, active_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
        description TEXT, ext_id TEXT, feed TEXT
      );
      CREATE UNIQUE INDEX IF NOT EXISTS incidents_ext ON incidents (ext_id) WHERE ext_id IS NOT NULL;
      CREATE INDEX IF NOT EXISTS incidents_expires ON incidents (expires_at);
      CREATE INDEX IF NOT EXISTS incidents_type_active ON incidents (type, active_at);
      CREATE INDEX IF NOT EXISTS incidents_seg ON incidents (seg);
      CREATE INDEX IF NOT EXISTS incidents_feed ON incidents (feed) WHERE feed IS NOT NULL;
      CREATE TABLE IF NOT EXISTS votes (
        incident_id TEXT NOT NULL, voter TEXT NOT NULL, gone INTEGER NOT NULL,
        PRIMARY KEY (incident_id, voter)
      ) WITHOUT ROWID;
      CREATE TABLE IF NOT EXISTS confirmers (
        incident_id TEXT NOT NULL, voter TEXT NOT NULL,
        PRIMARY KEY (incident_id, voter)
      ) WITHOUT ROWID;
      CREATE TABLE IF NOT EXISTS wzdx_dismissed (ext_id TEXT PRIMARY KEY, until INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS regions (name TEXT PRIMARY KEY, last_seen INTEGER NOT NULL);
    `);
  }

  // ── Probes ────────────────────────────────────────────────────────────

  /** Stores already-validated samples; returns how many were new. */
  async ingestProbes(region: string, senderNumber: string, samples: ProbeSample[], now: number): Promise<number> {
    const sender = await this.senderHash(senderNumber);
    await this.ensureRegistered(region, now);
    let accepted = 0;
    const stoppedSegs = new Set<string>();
    this.ctx.storage.transactionSync(() => {
      for (const s of samples) {
        const kph = round1(s.kph);
        const cursor = this.sql.exec("INSERT OR IGNORE INTO probes (seg, sender, kph, t) VALUES (?, ?, ?, ?)", s.seg, sender, kph, s.t);
        if (cursor.rowsWritten > 0) accepted++;
        if (s.lat !== undefined && s.lon !== undefined) {
          this.notePosition(s.seg, s.lat, s.lon, now);
          this.noteArea(s.lat, s.lon, now);
        }
        if (kph < STOP_KPH && s.t >= now - STOP_WINDOW_SEC) stoppedSegs.add(s.seg);
      }
      this.foldTypical(now);
      this.prune(now);
      for (const seg of stoppedSegs) this.detect(seg, now);
    });
    return accepted;
  }

  live(now: number): LiveSnapshot {
    const rows = this.sql.exec<ProbeRow>("SELECT seg, sender, kph, t FROM probes WHERE t >= ?", now - LIVE_WINDOW_SEC).toArray();
    const incidents = this.sql
      .exec<IncidentRow>("SELECT * FROM incidents WHERE expires_at > ? ORDER BY active_at DESC LIMIT ?", now, LIVE_INCIDENT_LIMIT)
      .toArray()
      .map(toIncident);
    return { now, segments: liveSegments(rows, now), incidents };
  }

  typical(segs: string[]): { segments: Record<string, { h: Array<number | null> }> } {
    const out: Record<string, { h: Array<number | null> }> = {};
    const unique = [...new Set(segs)];
    for (let i = 0; i < unique.length; i += MAX_BINDINGS) {
      const chunk = unique.slice(i, i + MAX_BINDINGS);
      const rows = this.sql
        .exec<{ seg: string; slot: number; kph: number }>(`SELECT seg, slot, kph FROM typical WHERE seg IN (${chunk.map(() => "?").join(",")})`, ...chunk)
        .toArray();
      for (const r of rows) {
        let entry = out[r.seg];
        if (!entry) out[r.seg] = entry = { h: new Array<number | null>(168).fill(null) };
        if (r.slot >= 0 && r.slot < 168) entry.h[r.slot] = round1(r.kph);
      }
    }
    return { segments: out };
  }

  // ── Reports and votes ─────────────────────────────────────────────────

  async report(region: string, reporterNumber: string, type: IncidentType, lat: number, lon: number, seg: string | null, now: number): Promise<Incident> {
    const reporter = await this.senderHash(reporterNumber);
    await this.ensureRegistered(region, now);
    return this.ctx.storage.transactionSync(() => {
      this.prune(now);
      this.noteArea(lat, lon, now);
      if (seg !== null) this.notePosition(seg, lat, lon, now);
      const candidates = this.sql
        .exec<IncidentRow>("SELECT * FROM incidents WHERE type = ? AND active_at >= ? AND expires_at > ?", type, now - 30 * 60, now)
        .toArray();
      const target = findMergeTarget(
        candidates.map((r) => ({ id: r.id, type: r.type, lat: r.lat, lon: r.lon, activeAt: r.active_at, expiresAt: r.expires_at })),
        type,
        lat,
        lon,
        now,
      );
      if (target) {
        this.confirm(target.id, reporter, now);
        return toIncident(this.incidentRow(target.id)!);
      }
      const id = crypto.randomUUID();
      this.sql.exec(
        `INSERT INTO incidents (id, type, lat, lon, seg, source, confirmations, created_at, active_at, expires_at, description, ext_id, feed)
         VALUES (?, ?, ?, ?, ?, 'user', 1, ?, ?, ?, NULL, NULL, NULL)`,
        id,
        type,
        lat,
        lon,
        seg,
        now,
        now,
        now + INCIDENT_TTL_SEC[type],
      );
      this.sql.exec("INSERT OR IGNORE INTO confirmers (incident_id, voter) VALUES (?, ?)", id, reporter);
      return toIncident(this.incidentRow(id)!);
    });
  }

  async vote(region: string, voterNumber: string, id: string, still: boolean, now: number): Promise<Incident | null> {
    const voter = await this.senderHash(voterNumber);
    await this.ensureRegistered(region, now);
    return this.ctx.storage.transactionSync(() => {
      this.prune(now);
      const row = this.incidentRow(id);
      if (!row || row.expires_at <= now) return null;
      this.sql.exec(
        "INSERT INTO votes (incident_id, voter, gone) VALUES (?, ?, ?) ON CONFLICT (incident_id, voter) DO UPDATE SET gone = excluded.gone",
        id,
        voter,
        still ? 0 : 1,
      );
      if (still) this.confirm(id, voter, now);
      const net = this.sql.exec<{ net: number | null }>("SELECT SUM(CASE gone WHEN 1 THEN 1 ELSE -1 END) AS net FROM votes WHERE incident_id = ?", id).one().net ?? 0;
      if (net >= GONE_VOTES_TO_REMOVE) {
        // A work zone voted away would come straight back on the next feed
        // refresh; remember the dismissal for as long as the feed says it lasts.
        if (row.ext_id !== null) {
          this.sql.exec(
            "INSERT INTO wzdx_dismissed (ext_id, until) VALUES (?, ?) ON CONFLICT (ext_id) DO UPDATE SET until = excluded.until",
            row.ext_id,
            row.expires_at,
          );
        }
        this.deleteIncident(id);
        return null;
      }
      return toIncident(this.incidentRow(id)!);
    });
  }

  // ── WZDx ──────────────────────────────────────────────────────────────

  /** Where this region's traffic has been seen, or null before any positioned data has arrived. */
  regionBBox(now: number): BBox | null {
    const cached = this.getMeta("bbox");
    const cachedAt = Number(this.getMeta("bboxAt") ?? "0");
    if (cached !== null && now - cachedAt < BBOX_CACHE_SEC) return JSON.parse(cached) as BBox | null;
    const points = this.sql.exec<{ lat: number; lon: number }>("SELECT lat, lon FROM area").toArray();
    const bbox = robustBBox(points);
    this.setMeta("bbox", JSON.stringify(bbox));
    this.setMeta("bboxAt", String(now));
    return bbox;
  }

  /**
   * Replaces this region's work zones from one feed with `events` (already
   * filtered to the region): new ones are inserted, known ones updated, and
   * ones the feed no longer lists are removed.
   */
  ingestWzdx(feedKey: string, events: WzdxEvent[], now: number): { upserted: number; removed: number } {
    return this.ctx.storage.transactionSync(() => {
      this.prune(now);
      const current = new Set<string>();
      let upserted = 0;
      for (const e of events) {
        current.add(e.extId);
        const dismissed = this.sql.exec<{ until: number }>("SELECT until FROM wzdx_dismissed WHERE ext_id = ?", e.extId).toArray()[0];
        if (dismissed && dismissed.until > now) continue;
        const expiresAt = e.endsAt ?? now + INCIDENT_TTL_SEC.closure;
        if (expiresAt <= now) continue;
        const existing = this.sql.exec<{ id: string }>("SELECT id FROM incidents WHERE ext_id = ?", e.extId).toArray()[0];
        if (existing) {
          this.sql.exec(
            "UPDATE incidents SET type = ?, lat = ?, lon = ?, expires_at = ?, active_at = ?, description = ?, feed = ? WHERE id = ?",
            e.type,
            e.lat,
            e.lon,
            expiresAt,
            now,
            e.description,
            feedKey,
            existing.id,
          );
        } else {
          this.sql.exec(
            `INSERT INTO incidents (id, type, lat, lon, seg, source, confirmations, created_at, active_at, expires_at, description, ext_id, feed)
             VALUES (?, ?, ?, ?, NULL, 'wzdx', 0, ?, ?, ?, ?, ?, ?)`,
            crypto.randomUUID(),
            e.type,
            e.lat,
            e.lon,
            now,
            now,
            expiresAt,
            e.description,
            e.extId,
            feedKey,
          );
        }
        upserted++;
      }
      let removed = 0;
      const known = this.sql.exec<{ id: string; ext_id: string }>("SELECT id, ext_id FROM incidents WHERE feed = ?", feedKey).toArray();
      for (const k of known) {
        if (current.has(k.ext_id)) continue;
        this.deleteIncident(k.id);
        removed++;
      }
      return { upserted, removed };
    });
  }

  // ── Registry (only on the "traffic-registry" instance) ────────────────

  registerRegion(name: string, now: number): void {
    this.sql.exec(
      "INSERT INTO regions (name, last_seen) VALUES (?, ?) ON CONFLICT (name) DO UPDATE SET last_seen = excluded.last_seen",
      name,
      now,
    );
  }

  /** The most recently active regions, at most `limit` of them (any signed-in phone can name a new region). */
  listRegions(now: number, limit: number): string[] {
    this.sql.exec("DELETE FROM regions WHERE last_seen < ?", now - REGION_FORGET_SEC);
    return this.sql
      .exec<{ name: string }>("SELECT name FROM regions ORDER BY last_seen DESC, name LIMIT ?", Math.max(0, Math.floor(limit)))
      .toArray()
      .map((r) => r.name);
  }

  // ── Internals ─────────────────────────────────────────────────────────

  private async senderHash(number: string): Promise<string> {
    let salt = this.getMeta("salt");
    if (salt === null) {
      salt = hex(crypto.getRandomValues(new Uint8Array(32)));
      this.setMeta("salt", salt);
    }
    return (await sha256Hex(`${salt}:${number}`)).slice(0, 32);
  }

  private async ensureRegistered(region: string, now: number): Promise<void> {
    const at = Number(this.getMeta("registeredAt") ?? "0");
    if (this.getMeta("region") === region && now - at < REGISTER_EVERY_SEC) return;
    this.setMeta("region", region);
    try {
      await this.env.TRAFFIC.get(this.env.TRAFFIC.idFromName(REGISTRY_NAME)).registerRegion(region, now);
      this.setMeta("registeredAt", String(now));
    } catch (e) {
      // Traffic still works without the registry; only WZDx delivery waits for the next attempt.
      console.error(`traffic: registering region ${region} failed`, e);
    }
  }

  /**
   * Folds finished 15-minute buckets into the typical week. A bucket is
   * folded only once probes for it can no longer arrive (they are accepted up
   * to 15 minutes late), and always before raw probes are pruned.
   */
  private foldTypical(now: number): void {
    const horizon = bucketStart(now - BUCKET_SEC);
    const stored = this.getMeta("foldedThrough");
    if (stored === null) {
      this.setMeta("foldedThrough", String(horizon));
      return;
    }
    const from = Math.max(Number(stored), bucketStart(now - PROBE_RETENTION_SEC));
    if (from >= horizon) return;
    const rows = this.sql.exec<ProbeRow>("SELECT seg, sender, kph, t FROM probes WHERE t >= ? AND t < ?", from, horizon).toArray();
    for (const m of bucketMedians(rows)) {
      const slot = weekSlot(m.bucket);
      const prev = this.sql.exec<{ kph: number }>("SELECT kph FROM typical WHERE seg = ? AND slot = ?", m.seg, slot).toArray()[0];
      this.sql.exec(
        "INSERT INTO typical (seg, slot, kph) VALUES (?, ?, ?) ON CONFLICT (seg, slot) DO UPDATE SET kph = excluded.kph",
        m.seg,
        slot,
        ewma(prev ? prev.kph : null, m.kph),
      );
    }
    this.setMeta("foldedThrough", String(horizon));
  }

  private prune(now: number): void {
    this.sql.exec("DELETE FROM probes WHERE t < ?", now - PROBE_RETENTION_SEC);
    const expired = this.sql.exec<{ id: string }>("SELECT id FROM incidents WHERE expires_at <= ?", now).toArray();
    for (const e of expired) this.deleteIncident(e.id);
    this.sql.exec("DELETE FROM wzdx_dismissed WHERE until <= ?", now);
    this.sql.exec("DELETE FROM seg_pos WHERE updated < ?", now - SEG_POS_RETENTION_SEC);
  }

  private detect(seg: string, now: number): void {
    const slow = this.sql
      .exec<{ sender: string }>("SELECT DISTINCT sender FROM probes WHERE seg = ? AND t >= ? AND kph < ? LIMIT ?", seg, now - STOP_WINDOW_SEC, STOP_KPH, MAX_BINDINGS - 1)
      .toArray()
      .map((r) => r.sender);
    if (slow.length < 2) return;
    const history = this.sql
      .exec<ProbeRow>(
        `SELECT seg, sender, kph, t FROM probes WHERE t >= ? AND sender IN (${slow.map(() => "?").join(",")})`,
        now - STOP_WINDOW_SEC - SUDDEN_WINDOW_SEC,
        ...slow,
      )
      .toArray();
    const typical = this.sql.exec<{ kph: number }>("SELECT kph FROM typical WHERE seg = ? AND slot = ?", seg, weekSlot(now)).toArray()[0];
    const kind = detectStoppage(seg, history, typical ? typical.kph : null, now);
    if (kind === null) return;

    const existing = this.sql
      .exec<IncidentRow>("SELECT * FROM incidents WHERE seg = ? AND type IN ('crash', 'jam') AND expires_at > ? ORDER BY type = 'crash' DESC", seg, now)
      .toArray();
    const crash = existing.find((r) => r.type === "crash");
    const jam = existing.find((r) => r.type === "jam");
    if (kind === "crash" && !crash && jam && jam.source === "auto") {
      this.sql.exec(
        "UPDATE incidents SET type = 'crash', description = ?, active_at = ?, expires_at = ? WHERE id = ?",
        AUTO_DESCRIPTION.crash,
        now,
        now + INCIDENT_TTL_SEC.crash,
        jam.id,
      );
      return;
    }
    // A crash already on the segment explains a jam there; do not add a second marker.
    const same = kind === "crash" ? crash : (crash ?? jam);
    if (same) {
      if (same.source === "auto") {
        this.sql.exec(
          "UPDATE incidents SET active_at = ?, expires_at = MAX(expires_at, ?) WHERE id = ?",
          now,
          now + INCIDENT_TTL_SEC[same.type as IncidentType],
          same.id,
        );
      }
      return;
    }
    // The relay has no road graph; a segment's position is known only from
    // positioned probes or reports on it. Without one there is nothing a
    // client could draw, so the stoppage waits until a position arrives.
    const pos = this.sql.exec<{ lat: number; lon: number }>("SELECT lat, lon FROM seg_pos WHERE seg = ?", seg).toArray()[0];
    if (!pos) return;
    this.sql.exec(
      `INSERT INTO incidents (id, type, lat, lon, seg, source, confirmations, created_at, active_at, expires_at, description, ext_id, feed)
       VALUES (?, ?, ?, ?, ?, 'auto', 0, ?, ?, ?, ?, NULL, NULL)`,
      crypto.randomUUID(),
      kind,
      pos.lat,
      pos.lon,
      seg,
      now,
      now,
      now + INCIDENT_TTL_SEC[kind],
      AUTO_DESCRIPTION[kind],
    );
  }

  /** One confirmation per person; each new one keeps a user or auto incident alive for another full TTL. */
  private confirm(id: string, voter: string, now: number): void {
    // Repeats from the same person neither count again nor keep the incident alive.
    const added = this.sql.exec("INSERT OR IGNORE INTO confirmers (incident_id, voter) VALUES (?, ?)", id, voter).rowsWritten > 0;
    if (!added) return;
    const row = this.incidentRow(id);
    if (!row) return;
    const ttl = INCIDENT_TTL_SEC[row.type as IncidentType];
    const expiresAt = row.source === "wzdx" ? row.expires_at : Math.max(row.expires_at, now + ttl);
    this.sql.exec(
      "UPDATE incidents SET confirmations = confirmations + 1, active_at = ?, expires_at = ? WHERE id = ?",
      now,
      expiresAt,
      id,
    );
  }

  private notePosition(seg: string, lat: number, lon: number, now: number): void {
    this.sql.exec(
      "INSERT INTO seg_pos (seg, lat, lon, updated) VALUES (?, ?, ?, ?) ON CONFLICT (seg) DO UPDATE SET lat = excluded.lat, lon = excluded.lon, updated = excluded.updated",
      seg,
      lat,
      lon,
      now,
    );
  }

  /**
   * Remembers only the centre of the 0.1° cell, never the reported point: the
   * first report in a quiet cell could otherwise pin someone's driveway forever.
   * The bbox pad (0.05°) is half a cell, so the cell is still fully covered.
   */
  private noteArea(lat: number, lon: number, now: number): void {
    const cy = Math.floor(lat / AREA_CELL_DEG);
    const cx = Math.floor(lon / AREA_CELL_DEG);
    this.sql.exec(
      "INSERT INTO area (cell, lat, lon, updated) VALUES (?, ?, ?, ?) ON CONFLICT (cell) DO UPDATE SET updated = excluded.updated",
      `${cy}:${cx}`,
      round6((cy + 0.5) * AREA_CELL_DEG),
      round6((cx + 0.5) * AREA_CELL_DEG),
      now,
    );
  }

  private incidentRow(id: string): IncidentRow | undefined {
    return this.sql.exec<IncidentRow>("SELECT * FROM incidents WHERE id = ?", id).toArray()[0];
  }

  private deleteIncident(id: string): void {
    this.sql.exec("DELETE FROM incidents WHERE id = ?", id);
    this.sql.exec("DELETE FROM votes WHERE incident_id = ?", id);
    this.sql.exec("DELETE FROM confirmers WHERE incident_id = ?", id);
  }

  private getMeta(k: string): string | null {
    const row = this.sql.exec<{ v: string }>("SELECT v FROM meta WHERE k = ?", k).toArray()[0];
    return row ? row.v : null;
  }

  private setMeta(k: string, v: string): void {
    this.sql.exec("INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT (k) DO UPDATE SET v = excluded.v", k, v);
  }
}

function round6(x: number): number {
  return Math.round(x * 1e6) / 1e6;
}

function toIncident(r: IncidentRow): Incident {
  return {
    id: r.id,
    type: r.type as IncidentType,
    lat: r.lat,
    lon: r.lon,
    seg: r.seg,
    source: r.source as IncidentSource,
    confirmations: r.confirmations,
    createdAt: r.created_at,
    expiresAt: r.expires_at,
    description: r.description,
  };
}
