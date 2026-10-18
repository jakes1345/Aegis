import { DurableObject } from "cloudflare:workers";
import type { Env } from "./env";
import {
  DEFAULT_DAILY_CAP,
  MAX_UPSTREAM_TILES_PER_REQUEST,
  PROVIDERS,
  TILE_ERROR_BACKOFF_SEC,
  combineStatus,
  decideTile,
  hereUrl,
  mergeForBBox,
  normalizeHere,
  normalizeTomTom,
  parseCap,
  pickTilesToFetch,
  quotaTake,
  serializeTile,
  staleUsable,
  tileFromKey,
  tilesForBBox,
  tomtomUrl,
  type ProviderIncident,
  type ProviderName,
  type ProviderStatus,
  type Tile,
  type TileRow,
} from "./incidents-logic";
import { readTextCapped, type BBox } from "./traffic-logic";

/**
 * Commercial incident feeds (HERE, TomTom) proxied for the phones, so API
 * keys stay on the relay and one upstream call per 0.25° tile every five
 * minutes serves every phone in that area.
 *
 * There is one instance, idFromName("incidents"). Being single-threaded, it
 * makes the daily quota counters exact and lets concurrent requests for the
 * same tile wait on one in-flight upstream call.
 */

export const INCIDENTS_NAME = "incidents";

export interface IncidentsReply {
  now: number;
  incidents: ProviderIncident[];
  providers: Record<ProviderName, ProviderStatus>;
}

const UPSTREAM_TIMEOUT_MS = 10_000;
const UPSTREAM_MAX_BYTES = 5 * 1024 * 1024;
/** Cached tiles older than any use for them (fresh or stale) are deleted. */
const TILE_RETENTION_SEC = 60 * 60;
/** Parsed tiles kept in memory: two providers × a few phones' 1° boxes. */
const PARSED_TILES_MAX = 200;

type TileDbRow = { good_at: number | null; body: string | null; error_at: number | null };
type TileOutcome = { status: ProviderStatus; incidents: ProviderIncident[] };

export class IncidentCache extends DurableObject<Env> {
  private readonly sql: SqlStorage;
  /** Upstream calls under way, keyed "<provider>|<tile>", shared by every request that wants the same tile. */
  private readonly inflight = new Map<string, Promise<TileOutcome>>();
  /** Parsed tile bodies keyed like [inflight], valid while their good_at matches the stored row. */
  private readonly parsed = new Map<string, { goodAt: number; incidents: ProviderIncident[] }>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS tiles (
        provider TEXT NOT NULL, tile TEXT NOT NULL,
        good_at INTEGER, body TEXT, error_at INTEGER,
        PRIMARY KEY (provider, tile)
      ) WITHOUT ROWID;
      CREATE TABLE IF NOT EXISTS quota (provider TEXT PRIMARY KEY, day INTEGER NOT NULL, count INTEGER NOT NULL);
    `);
  }

  /**
   * Incidents from every configured provider for [bbox], which the caller has
   * already validated, as the JSON text of an IncidentsReply: serialised here
   * so the Worker in front, on the 10 ms Free CPU budget, only passes it on.
   */
  async incidents(bbox: BBox, now: number): Promise<string> {
    this.prune(now);
    const box = Array.from(bbox) as BBox;
    const tiles = tilesForBBox(box);
    const providers = {} as Record<ProviderName, ProviderStatus>;
    const lists: ProviderIncident[][] = [];
    await Promise.all(
      PROVIDERS.map(async (provider) => {
        if (!this.apiKey(provider)) {
          providers[provider] = "off";
          return;
        }
        const rows = new Map<string, TileDbRow | null>(tiles.map((t) => [t.key, this.readTile(provider, t.key)]));
        const metas = new Map<string, TileRow | null>(
          [...rows].map(([k, r]) => [k, r ? { goodAt: r.good_at, errorAt: r.error_at } : null]),
        );
        const toFetch = new Set(pickTilesToFetch(tiles, metas, box, now, MAX_UPSTREAM_TILES_PER_REQUEST));
        const outcomes = await Promise.all(
          tiles.map((t) => this.tile(provider, t, rows.get(t.key) ?? null, now, toFetch.has(t.key))),
        );
        providers[provider] = combineStatus(outcomes.map((o) => o.status));
        for (const o of outcomes) lists.push(o.incidents);
      }),
    );
    const reply: IncidentsReply = { now, incidents: mergeForBBox(lists, box, now), providers };
    return JSON.stringify(reply);
  }

  private apiKey(provider: ProviderName): string | null {
    const key = provider === "here" ? this.env.HERE_API_KEY : this.env.TOMTOM_API_KEY;
    return typeof key === "string" && key.trim() !== "" ? key.trim() : null;
  }

  private cap(provider: ProviderName): number {
    return provider === "here"
      ? parseCap(this.env.HERE_DAILY_CAP, DEFAULT_DAILY_CAP.here)
      : parseCap(this.env.TOMTOM_DAILY_CAP, DEFAULT_DAILY_CAP.tomtom);
  }

  /** A tile's cached incidents, parsed once per stored result rather than on every request. */
  private cached(flightKey: string, row: TileDbRow | null): ProviderIncident[] {
    if (!row || row.body === null || row.good_at === null) return [];
    const hit = this.parsed.get(flightKey);
    if (hit && hit.goodAt === row.good_at) return hit.incidents;
    const incidents = parseBody(row.body);
    this.remember(flightKey, row.good_at, incidents);
    return incidents;
  }

  private remember(flightKey: string, goodAt: number, incidents: ProviderIncident[]): void {
    this.parsed.delete(flightKey);
    this.parsed.set(flightKey, { goodAt, incidents });
    while (this.parsed.size > PARSED_TILES_MAX) this.parsed.delete(this.parsed.keys().next().value!);
  }

  private tile(provider: ProviderName, tile: Tile, row: TileDbRow | null, now: number, mayFetch: boolean): Promise<TileOutcome> {
    const flightKey = `${provider}|${tile.key}`;
    const pending = this.inflight.get(flightKey);
    if (pending) return pending;

    const meta: TileRow | null = row ? { goodAt: row.good_at, errorAt: row.error_at } : null;
    const stale = () => (staleUsable(meta, now) ? this.cached(flightKey, row) : []);
    const decision = decideTile(meta, now);
    if (decision === "fresh") return Promise.resolve({ status: "ok", incidents: this.cached(flightKey, row) });
    if (decision === "backoff") return Promise.resolve({ status: "error", incidents: stale() });
    // Over this request's upstream budget: a later poll fetches it.
    if (!mayFetch) return Promise.resolve({ status: "ok", incidents: stale() });
    if (!this.takeQuota(provider, now)) return Promise.resolve({ status: "quota", incidents: stale() });

    const call = this.fetchTile(provider, tile, now)
      .then((incidents): TileOutcome => {
        this.sql.exec(
          `INSERT INTO tiles (provider, tile, good_at, body, error_at) VALUES (?, ?, ?, ?, NULL)
           ON CONFLICT (provider, tile) DO UPDATE SET good_at = excluded.good_at, body = excluded.body, error_at = NULL`,
          provider,
          tile.key,
          now,
          serializeTile(incidents),
        );
        this.remember(flightKey, now, incidents);
        return { status: "ok", incidents };
      })
      .catch((e): TileOutcome => {
        console.error(`incidents: ${provider} tile ${tile.key} failed`, e instanceof Error ? e.message : e);
        this.sql.exec(
          `INSERT INTO tiles (provider, tile, good_at, body, error_at) VALUES (?, ?, NULL, NULL, ?)
           ON CONFLICT (provider, tile) DO UPDATE SET error_at = excluded.error_at`,
          provider,
          tile.key,
          now,
        );
        return { status: "error", incidents: stale() };
      })
      .finally(() => this.inflight.delete(flightKey));
    this.inflight.set(flightKey, call);
    return call;
  }

  private async fetchTile(provider: ProviderName, tile: Tile, now: number): Promise<ProviderIncident[]> {
    const key = this.apiKey(provider);
    if (!key) throw new Error("no API key");
    const url = provider === "here" ? hereUrl(tile, key) : tomtomUrl(tile, key);
    const res = await fetch(url, {
      headers: { accept: "application/json" },
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
    if (!res.ok) {
      // The URL carries the API key, so only the status is logged.
      await res.body?.cancel().catch(() => {});
      throw new Error(`HTTP ${res.status}`);
    }
    const text = await readTextCapped(res.body, res.headers.get("content-length"), UPSTREAM_MAX_BYTES);
    if (text === null) throw new Error(`response larger than ${UPSTREAM_MAX_BYTES} bytes`);
    const doc: unknown = JSON.parse(text);
    return provider === "here" ? normalizeHere(doc, now) : normalizeTomTom(doc, now);
  }

  /** Counts one upstream call against today's cap; false (and nothing counted) when the cap is reached. */
  private takeQuota(provider: ProviderName, now: number): boolean {
    const row = this.sql.exec<{ day: number; count: number }>("SELECT day, count FROM quota WHERE provider = ?", provider).toArray()[0] ?? null;
    const { allowed, counter } = quotaTake(row, now, this.cap(provider));
    if (allowed) {
      this.sql.exec(
        "INSERT INTO quota (provider, day, count) VALUES (?, ?, ?) ON CONFLICT (provider) DO UPDATE SET day = excluded.day, count = excluded.count",
        provider,
        counter.day,
        counter.count,
      );
    }
    return allowed;
  }

  private readTile(provider: ProviderName, key: string): TileDbRow | null {
    if (!tileFromKey(key)) return null;
    return this.sql.exec<TileDbRow>("SELECT good_at, body, error_at FROM tiles WHERE provider = ? AND tile = ?", provider, key).toArray()[0] ?? null;
  }

  private prune(now: number): void {
    const cutoff = now - Math.max(TILE_RETENTION_SEC, TILE_ERROR_BACKOFF_SEC);
    this.sql.exec("DELETE FROM tiles WHERE COALESCE(good_at, 0) < ? AND COALESCE(error_at, 0) < ?", cutoff, cutoff);
  }
}

function parseBody(body: string): ProviderIncident[] {
  try {
    const v: unknown = JSON.parse(body);
    return Array.isArray(v) ? (v as ProviderIncident[]) : [];
  } catch {
    return [];
  }
}
