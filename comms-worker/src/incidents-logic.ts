/**
 * Pure logic for the HERE / TomTom incidents proxy: bbox validation, tile
 * snapping, provider JSON normalisation, the daily quota counter and the
 * per-tile cache decision. No Workers APIs here, so `node --test` can load it.
 */

import type { BBox } from "./traffic-logic";

export type ProviderName = "here" | "tomtom";
export type ProviderStatus = "ok" | "off" | "quota" | "error";
export type ProviderIncidentType = "crash" | "closure" | "lane_closure" | "work_zone" | "jam" | "hazard" | "police" | "other";

export interface ProviderIncident {
  id: string;
  type: ProviderIncidentType;
  lat: number;
  lon: number;
  /** [lat, lon] pairs, or null for a point incident. */
  geom: Array<[number, number]> | null;
  road: string | null;
  description: string | null;
  /** Epoch seconds. */
  start: number | null;
  end: number | null;
  delay_s: number | null;
  source: ProviderName;
}

export const PROVIDERS: readonly ProviderName[] = ["here", "tomtom"];

export const MAX_BBOX_SPAN_DEG = 1.0;
export const TILE_DEG = 0.25;
/** A fetched tile is served from cache this long. */
export const TILE_FRESH_SEC = 5 * 60;
/** After an upstream failure the tile is not retried for this long, so a broken provider is not hammered by every phone. */
export const TILE_ERROR_BACKOFF_SEC = 60;
/** While a provider is failing or over quota, a tile's last good result is still served up to this age. */
export const TILE_STALE_MAX_SEC = 30 * 60;
export const DEFAULT_DAILY_CAP: Record<ProviderName, number> = { here: 1000, tomtom: 2300 };
/** Keeps one tile's cached row well under the Durable Object SQLite row limit. */
export const MAX_INCIDENTS_PER_TILE = 500;
export const MAX_GEOM_POINTS = 200;

// ── Request bbox ─────────────────────────────────────────────────────────

/** Parses "minLat,minLon,maxLat,maxLon"; returns the box, or a message saying what is wrong with it. */
export function parseBBoxParam(raw: string | null): { bbox: BBox } | { error: string } {
  if (raw === null || raw.trim() === "") return { error: "bbox required: minLat,minLon,maxLat,maxLon" };
  const parts = raw.split(",").map((p) => p.trim());
  if (parts.length !== 4 || parts.some((p) => p === "")) return { error: "bbox must be minLat,minLon,maxLat,maxLon" };
  const nums = parts.map(Number);
  if (nums.some((n) => !Number.isFinite(n))) return { error: "bbox values must be numbers" };
  const [minLat, minLon, maxLat, maxLon] = nums as [number, number, number, number];
  if (minLat < -90 || maxLat > 90) return { error: "bbox latitudes must be from -90 to 90" };
  if (minLon < -180 || maxLon > 180) return { error: "bbox longitudes must be from -180 to 180" };
  if (!(minLat < maxLat) || !(minLon < maxLon)) return { error: "bbox minimums must be below its maximums" };
  if (maxLat - minLat > MAX_BBOX_SPAN_DEG + 1e-9 || maxLon - minLon > MAX_BBOX_SPAN_DEG + 1e-9) {
    return { error: `bbox may span at most ${MAX_BBOX_SPAN_DEG}° in each direction` };
  }
  return { bbox: [minLat, minLon, maxLat, maxLon] };
}

// ── Tiles ────────────────────────────────────────────────────────────────

export interface Tile {
  /** "<latIndex>:<lonIndex>" on the TILE_DEG grid. */
  key: string;
  bbox: BBox;
}

export function tileFromKey(key: string): Tile | null {
  const m = /^(-?\d{1,4}):(-?\d{1,4})$/.exec(key);
  if (!m) return null;
  const i = Number(m[1]);
  const j = Number(m[2]);
  const minLat = round6(i * TILE_DEG);
  const minLon = round6(j * TILE_DEG);
  if (minLat < -90 || minLat + TILE_DEG > 90 || minLon < -180 || minLon + TILE_DEG > 180) return null;
  return { key, bbox: [minLat, minLon, round6(minLat + TILE_DEG), round6(minLon + TILE_DEG)] };
}

/** The TILE_DEG tiles covering [bbox], snapped outward. A 1°×1° box needs at most 5×5. */
export function tilesForBBox(bbox: BBox): Tile[] {
  // The epsilon keeps a box edge that sits exactly on a grid line (40.25) from
  // pulling in a whole extra row of tiles through floating-point noise.
  const i0 = Math.floor(bbox[0] / TILE_DEG + 1e-9);
  const j0 = Math.floor(bbox[1] / TILE_DEG + 1e-9);
  const i1 = Math.max(i0 + 1, Math.ceil(bbox[2] / TILE_DEG - 1e-9));
  const j1 = Math.max(j0 + 1, Math.ceil(bbox[3] / TILE_DEG - 1e-9));
  const out: Tile[] = [];
  for (let i = i0; i < i1; i++) {
    for (let j = j0; j < j1; j++) {
      const t = tileFromKey(`${i}:${j}`);
      if (t) out.push(t);
    }
  }
  return out;
}

// ── Upstream URLs ────────────────────────────────────────────────────────

export function hereUrl(tile: Tile, apiKey: string): string {
  const [south, west, north, east] = tile.bbox;
  const q = new URLSearchParams({
    in: `bbox:${west},${south},${east},${north}`,
    locationReferencing: "shape",
    apiKey,
  });
  return `https://data.traffic.hereapi.com/v7/incidents?${q.toString()}`;
}

export const TOMTOM_FIELDS =
  "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay,events{description,code},startTime,endTime,from,to,delay,roadNumbers}}}";

export function tomtomUrl(tile: Tile, apiKey: string): string {
  const [minLat, minLon, maxLat, maxLon] = tile.bbox;
  const q = new URLSearchParams({
    key: apiKey,
    bbox: `${minLon},${minLat},${maxLon},${maxLat}`,
    fields: TOMTOM_FIELDS,
    language: "en-US",
    timeValidityFilter: "present",
  });
  return `https://api.tomtom.com/traffic/services/5/incidentDetails?${q.toString()}`;
}

// ── HERE ─────────────────────────────────────────────────────────────────

const HERE_TYPES: Record<string, ProviderIncidentType> = {
  accident: "crash",
  roadClosure: "closure",
  laneRestriction: "lane_closure",
  construction: "work_zone",
  plannedEvent: "work_zone",
  congestion: "jam",
  roadHazard: "hazard",
  weather: "hazard",
  disabledVehicle: "hazard",
  police: "police",
};

export function mapHereType(type: unknown, roadClosed: unknown): ProviderIncidentType {
  // HERE files a closed carriageway under its cause (construction, accident…)
  // with roadClosed set; for routing, that it is closed matters most.
  if (roadClosed === true) return "closure";
  return typeof type === "string" ? (HERE_TYPES[type] ?? "other") : "other";
}

/** Normalises a HERE Traffic v7 /incidents response; drops incidents that have ended. */
export function normalizeHere(doc: unknown, now: number): ProviderIncident[] {
  const results = (doc as { results?: unknown } | null)?.results;
  if (!Array.isArray(results)) throw new Error("HERE response has no results array");
  const out: ProviderIncident[] = [];
  for (const r of results) {
    if (!r || typeof r !== "object") continue;
    const { location, incidentDetails: d } = r as { location?: unknown; incidentDetails?: unknown };
    if (!d || typeof d !== "object") continue;
    const det = d as Record<string, unknown>;
    const rawId = typeof det.id === "string" || typeof det.id === "number" ? String(det.id) : null;
    if (rawId === null) continue;
    const end = isoToEpoch(det.endTime);
    if (end !== null && end < now) continue;
    const line: Array<[number, number]> = [];
    const links = (location as { shape?: { links?: unknown } } | undefined)?.shape?.links;
    if (Array.isArray(links)) {
      for (const link of links) {
        const points = (link as { points?: unknown } | null)?.points;
        if (!Array.isArray(points)) continue;
        for (const p of points) {
          const lat = (p as { lat?: unknown } | null)?.lat;
          const lng = (p as { lng?: unknown } | null)?.lng;
          if (isLat(lat) && isLon(lng)) pushDistinct(line, [lat, lng]);
        }
      }
    }
    const anchor = pathMidpoint(line);
    if (!anchor) continue;
    const roadRaw = (location as { description?: unknown } | undefined)?.description;
    out.push({
      id: `here:${rawId}`,
      type: mapHereType(det.type, det.roadClosed),
      lat: round5(anchor[0]),
      lon: round5(anchor[1]),
      geom: line.length >= 2 ? capPoints(line) : null,
      road: cleanText(typeof roadRaw === "string" ? roadRaw : (roadRaw as { value?: unknown } | undefined)?.value),
      description: cleanText((det.description as { value?: unknown } | undefined)?.value ?? (det.summary as { value?: unknown } | undefined)?.value),
      start: isoToEpoch(det.startTime),
      end,
      delay_s: null,
      source: "here",
    });
    if (out.length >= MAX_INCIDENTS_PER_TILE) break;
  }
  return out;
}

// ── TomTom ───────────────────────────────────────────────────────────────

export function mapTomTomCategory(iconCategory: unknown): ProviderIncidentType {
  switch (iconCategory) {
    case 1:
      return "crash";
    case 6:
      return "jam";
    case 7:
      return "lane_closure";
    case 8:
      return "closure";
    case 9:
      return "work_zone";
    case 2:
    case 3:
    case 4:
    case 5:
    case 10:
    case 11:
    case 14:
      return "hazard";
    default:
      return "other";
  }
}

/** Normalises a TomTom Traffic v5 incidentDetails response; drops incidents that have ended. */
export function normalizeTomTom(doc: unknown, now: number): ProviderIncident[] {
  const incidents = (doc as { incidents?: unknown } | null)?.incidents;
  if (!Array.isArray(incidents)) throw new Error("TomTom response has no incidents array");
  const out: ProviderIncident[] = [];
  for (const f of incidents) {
    if (!f || typeof f !== "object") continue;
    const { geometry, properties } = f as { geometry?: unknown; properties?: unknown };
    if (!properties || typeof properties !== "object") continue;
    const p = properties as Record<string, unknown>;
    const rawId = typeof p.id === "string" || typeof p.id === "number" ? String(p.id) : null;
    if (rawId === null) continue;
    const end = isoToEpoch(p.endTime);
    if (end !== null && end < now) continue;
    const g = geometry as { type?: unknown; coordinates?: unknown } | undefined;
    const line: Array<[number, number]> = [];
    if (g?.type === "Point") {
      const ll = lonLatToLatLon(g.coordinates);
      if (ll) line.push(ll);
    } else if (g?.type === "LineString" && Array.isArray(g.coordinates)) {
      for (const c of g.coordinates) {
        const ll = lonLatToLatLon(c);
        if (ll) pushDistinct(line, ll);
      }
    }
    const anchor = pathMidpoint(line);
    if (!anchor) continue;
    const events = Array.isArray(p.events) ? p.events : [];
    const eventText = events
      .map((e) => (e as { description?: unknown } | null)?.description)
      .filter((s): s is string => typeof s === "string" && s.trim() !== "")
      .map((s) => s.trim());
    const from = cleanText(p.from);
    const to = cleanText(p.to);
    let description = eventText.length > 0 ? [...new Set(eventText)].join("; ") : null;
    if (from !== null && to !== null) description = `${description ?? "Incident"}: ${from} to ${to}`;
    else if (from !== null) description = `${description ?? "Incident"}: ${from}`;
    const roads = Array.isArray(p.roadNumbers) ? p.roadNumbers.filter((s): s is string => typeof s === "string" && s.trim() !== "") : [];
    const delay = typeof p.delay === "number" && Number.isFinite(p.delay) && p.delay >= 0 ? Math.round(p.delay) : null;
    out.push({
      id: `tomtom:${rawId}`,
      type: mapTomTomCategory(p.iconCategory),
      lat: round5(anchor[0]),
      lon: round5(anchor[1]),
      geom: line.length >= 2 ? capPoints(line) : null,
      road: roads.length > 0 ? roads.join(", ") : null,
      description: cleanText(description),
      start: isoToEpoch(p.startTime),
      end,
      delay_s: delay,
      source: "tomtom",
    });
    if (out.length >= MAX_INCIDENTS_PER_TILE) break;
  }
  return out;
}

// ── Merging tiles ────────────────────────────────────────────────────────

/**
 * Incidents from several tiles for one requested box: one copy of each id
 * (an incident crossing a tile edge is listed by both tiles), ended ones
 * dropped, and only those touching the box the phone asked for.
 */
export function mergeForBBox(lists: ReadonlyArray<readonly ProviderIncident[]>, bbox: BBox, now: number): ProviderIncident[] {
  const seen = new Set<string>();
  const out: ProviderIncident[] = [];
  for (const list of lists) {
    for (const inc of list) {
      if (seen.has(inc.id)) continue;
      if (inc.end !== null && inc.end < now) continue;
      if (!touches(inc, bbox)) continue;
      seen.add(inc.id);
      out.push(inc);
    }
  }
  return out;
}

function touches(inc: ProviderIncident, b: BBox): boolean {
  const inside = (lat: number, lon: number) => lat >= b[0] && lat <= b[2] && lon >= b[1] && lon <= b[3];
  if (inside(inc.lat, inc.lon)) return true;
  if (!inc.geom) return false;
  return inc.geom.some(([lat, lon]) => inside(lat, lon));
}

/** The status reported for a provider across all tiles of one request: any error wins, then quota, else ok. */
export function combineStatus(statuses: readonly ProviderStatus[]): ProviderStatus {
  if (statuses.length === 0) return "ok";
  if (statuses.includes("off")) return "off";
  if (statuses.includes("error")) return "error";
  if (statuses.includes("quota")) return "quota";
  return "ok";
}

// ── Quota ────────────────────────────────────────────────────────────────

export interface QuotaCounter {
  /** UTC day number (epoch seconds / 86400). */
  day: number;
  count: number;
}

export function utcDay(now: number): number {
  return Math.floor(now / 86400);
}

/**
 * Whether one more upstream call fits today's cap, and the counter after it.
 * A counter from an earlier UTC day starts over at zero.
 */
export function quotaTake(counter: QuotaCounter | null, now: number, cap: number): { allowed: boolean; counter: QuotaCounter } {
  const day = utcDay(now);
  const current = counter && counter.day === day ? counter.count : 0;
  if (current >= cap) return { allowed: false, counter: { day, count: current } };
  return { allowed: true, counter: { day, count: current + 1 } };
}

/**
 * Share of a provider's daily cap one caller (phone) may spend, so one phone,
 * or one cycling through boxes, cannot use up the shared cap for everyone.
 */
export const PER_CALLER_DAILY_SHARE = 0.25;
/**
 * Once a provider's calls today reach this share of its cap, only tiles with
 * nothing to show are fetched; tiles with a usable result keep serving it, so
 * the rest of the cap goes to areas that would otherwise show nothing.
 */
export const HIGH_WATER_SHARE = 0.8;

/** A share in (0, 1] from an env var, or the default. */
export function parseShare(raw: string | undefined, fallback: number): number {
  if (raw === undefined || raw.trim() === "") return fallback;
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 && n <= 1 ? n : fallback;
}

/** Upstream calls one caller may make per provider per UTC day: at least one while the cap allows any. */
export function callerAllowance(cap: number, share: number): number {
  return cap <= 0 ? 0 : Math.max(1, Math.floor(cap * share));
}

/** Today's upstream calls for one provider: all callers', and the requesting caller's. */
export interface UpstreamUsage {
  globalCount: number;
  cap: number;
  callerCount: number;
  callerAllowance: number;
}

export function highWater(u: UpstreamUsage): boolean {
  return u.globalCount >= u.cap * HIGH_WATER_SHARE;
}

/**
 * Whether one more upstream call may start: "cap" when the provider's daily
 * cap is reached, "limited" when the high-water mark (for a tile that has a
 * usable result) or the caller's allowance stands in the way.
 */
export function upstreamGate(u: UpstreamUsage, hasUsable: boolean): "fetch" | "cap" | "limited" {
  if (u.globalCount >= u.cap) return "cap";
  if (hasUsable && highWater(u)) return "limited";
  if (u.callerCount >= u.callerAllowance) return "limited";
  return "fetch";
}

/** A positive whole-number cap from an env var, or the default. */
export function parseCap(raw: string | undefined, fallback: number): number {
  if (raw === undefined || raw.trim() === "") return fallback;
  const n = Number(raw);
  return Number.isInteger(n) && n >= 0 ? n : fallback;
}

// ── Per-tile cache ───────────────────────────────────────────────────────

export interface TileRow {
  /** When the last good result was fetched, or null if never. */
  goodAt: number | null;
  /** When the last upstream failure happened, or null. */
  errorAt: number | null;
}

export type TileDecision = "fresh" | "backoff" | "fetch";

export function decideTile(row: TileRow | null, now: number): TileDecision {
  if (row?.goodAt !== null && row?.goodAt !== undefined && now - row.goodAt < TILE_FRESH_SEC) return "fresh";
  if (row?.errorAt !== null && row?.errorAt !== undefined && now - row.errorAt < TILE_ERROR_BACKOFF_SEC) return "backoff";
  return "fetch";
}

/** Whether a tile's last good result may still be shown while its provider is failing or over quota. */
export function staleUsable(row: TileRow | null, now: number): boolean {
  return row?.goodAt !== null && row?.goodAt !== undefined && now - row.goodAt < TILE_STALE_MAX_SEC;
}

/**
 * Upstream calls one request may start per provider. A 1°×1° box spans up to
 * 25 tiles; fetching them all at once would be 50 subrequests (the Workers
 * Free per-invocation limit), queue behind the 6-connection limit until the
 * timeouts fire, and spend a day's HERE quota on a few phones. Tiles left
 * over keep their stale result and are fetched by later polls.
 */
export const MAX_UPSTREAM_TILES_PER_REQUEST = 3;

/**
 * Which of [tiles] to fetch upstream now, at most [budget]: only tiles whose
 * cache says "fetch"; first those with nothing usable to show, then the
 * oldest result, then the nearest to the requested box's centre.
 */
export function pickTilesToFetch(
  tiles: readonly Tile[],
  rows: ReadonlyMap<string, TileRow | null>,
  bbox: BBox,
  now: number,
  budget: number,
): string[] {
  const cLat = (bbox[0] + bbox[2]) / 2;
  const cLon = (bbox[1] + bbox[3]) / 2;
  const cands = tiles
    .filter((t) => decideTile(rows.get(t.key) ?? null, now) === "fetch")
    .map((t) => {
      const row = rows.get(t.key) ?? null;
      return {
        key: t.key,
        empty: staleUsable(row, now) ? 1 : 0,
        goodAt: row?.goodAt ?? -Infinity,
        dist: Math.hypot((t.bbox[0] + t.bbox[2]) / 2 - cLat, (t.bbox[1] + t.bbox[3]) / 2 - cLon),
      };
    });
  cands.sort((a, b) => a.empty - b.empty || a.goodAt - b.goodAt || a.dist - b.dist);
  return cands.slice(0, Math.max(0, budget)).map((c) => c.key);
}

/**
 * What each tile that needs a refresh does on this request:
 *   fetch    call upstream now
 *   defer    over this request's budget; serve what it has, a later poll fetches it
 *   limited  held back by the caller's allowance or the high-water mark; serve what it has
 *   quota    the provider's daily cap is reached; serve what it has
 * Tiles that need no refresh (fresh, or backing off after an error) are absent.
 */
export type UpstreamPlan = "fetch" | "defer" | "limited" | "quota";

export function planUpstream(
  tiles: readonly Tile[],
  rows: ReadonlyMap<string, TileRow | null>,
  bbox: BBox,
  now: number,
  usage: UpstreamUsage,
  perRequest: number = MAX_UPSTREAM_TILES_PER_REQUEST,
): Map<string, UpstreamPlan> {
  const out = new Map<string, UpstreamPlan>();
  const hw = highWater(usage);
  const eligible: Tile[] = [];
  for (const t of tiles) {
    const row = rows.get(t.key) ?? null;
    if (decideTile(row, now) !== "fetch") continue;
    if (hw && usage.globalCount < usage.cap && staleUsable(row, now)) out.set(t.key, "limited");
    else eligible.push(t);
  }
  const capLeft = Math.max(0, usage.cap - usage.globalCount);
  const callerLeft = Math.max(0, usage.callerAllowance - usage.callerCount);
  const budget = Math.min(perRequest, capLeft, callerLeft);
  const picked = new Set(pickTilesToFetch(eligible, rows, bbox, now, budget));
  // Why the tiles left over were left over: the tightest of the three limits.
  const leftover: UpstreamPlan = capLeft <= budget ? "quota" : callerLeft <= budget ? "limited" : "defer";
  for (const t of eligible) out.set(t.key, picked.has(t.key) ? "fetch" : leftover);
  return out;
}

/** One tile's part in a reply: its status ("limited" as planned above) and whether it had a result to serve. */
export interface TileReport {
  status: ProviderStatus | "limited";
  served: boolean;
}

/**
 * The provider status for one request. Tiles held back by the caller's
 * allowance or the high-water mark do not count against it as long as the
 * provider served something: "ok" then, "quota" if nothing at all was served.
 */
export function providerStatus(tiles: readonly TileReport[]): ProviderStatus {
  const base = combineStatus(tiles.filter((t) => t.status !== "limited").map((t) => t.status as ProviderStatus));
  if (base !== "ok" || !tiles.some((t) => t.status === "limited")) return base;
  return tiles.some((t) => t.served) ? "ok" : "quota";
}

/** Durable Object SQLite refuses strings over 2 MB; a tile's cached body stays well below that. */
export const MAX_TILE_BODY_BYTES = 1_500_000;

/** JSON for a tile's cache row, dropping incidents from the end until it fits [maxBytes]. */
export function serializeTile(incidents: readonly ProviderIncident[], maxBytes = MAX_TILE_BODY_BYTES): string {
  let list = incidents;
  let body = JSON.stringify(list);
  // Upstream text is overwhelmingly ASCII, so string length is a close, cheap byte estimate; the margin covers the rest.
  while (body.length > maxBytes && list.length > 0) {
    list = list.slice(0, Math.floor((list.length * maxBytes) / body.length * 0.95));
    body = JSON.stringify(list);
  }
  return body;
}

// ── Helpers ──────────────────────────────────────────────────────────────

function isLat(v: unknown): v is number {
  return typeof v === "number" && Number.isFinite(v) && v >= -90 && v <= 90;
}

function isLon(v: unknown): v is number {
  return typeof v === "number" && Number.isFinite(v) && v >= -180 && v <= 180;
}

function lonLatToLatLon(c: unknown): [number, number] | null {
  if (!Array.isArray(c) || c.length < 2) return null;
  const [lon, lat] = c as [unknown, unknown];
  return isLat(lat) && isLon(lon) ? [lat, lon] : null;
}

function pushDistinct(line: Array<[number, number]>, p: [number, number]): void {
  const last = line[line.length - 1];
  if (last && last[0] === p[0] && last[1] === p[1]) return;
  line.push(p);
}

/** The point halfway along a [lat, lon] path by length; its only point for a single one. */
export function pathMidpoint(line: ReadonlyArray<[number, number]>): [number, number] | null {
  if (line.length === 0) return null;
  if (line.length === 1) return line[0]!;
  const lens: number[] = [];
  let total = 0;
  for (let i = 1; i < line.length; i++) {
    const d = approxDistM(line[i - 1]!, line[i]!);
    lens.push(d);
    total += d;
  }
  if (total === 0) return line[0]!;
  let remaining = total / 2;
  for (let i = 0; i < lens.length; i++) {
    const d = lens[i]!;
    if (remaining <= d) {
      const a = line[i]!;
      const b = line[i + 1]!;
      const f = d === 0 ? 0 : remaining / d;
      return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
    }
    remaining -= d;
  }
  return line[line.length - 1]!;
}

function approxDistM(a: [number, number], b: [number, number]): number {
  const k = Math.cos(((a[0] + b[0]) / 2) * (Math.PI / 180));
  const dy = (b[0] - a[0]) * 111_195;
  const dx = (b[1] - a[1]) * 111_195 * k;
  return Math.hypot(dx, dy);
}

/** At most MAX_GEOM_POINTS evenly picked points, always keeping both ends; coordinates rounded to ~1 m. */
function capPoints(line: ReadonlyArray<[number, number]>): Array<[number, number]> {
  let picked: ReadonlyArray<[number, number]> = line;
  if (line.length > MAX_GEOM_POINTS) {
    const out: Array<[number, number]> = [];
    const step = (line.length - 1) / (MAX_GEOM_POINTS - 1);
    for (let k = 0; k < MAX_GEOM_POINTS; k++) out.push(line[Math.round(k * step)]!);
    picked = out;
  }
  return picked.map(([lat, lon]) => [round5(lat), round5(lon)]);
}

export function isoToEpoch(v: unknown): number | null {
  if (typeof v !== "string" || v.trim() === "") return null;
  const ms = Date.parse(v);
  return Number.isFinite(ms) ? Math.floor(ms / 1000) : null;
}

function cleanText(v: unknown): string | null {
  if (typeof v !== "string") return null;
  const t = v.replace(/\s+/g, " ").trim();
  return t === "" ? null : t.slice(0, 500);
}

function round5(x: number): number {
  return Math.round(x * 1e5) / 1e5;
}

function round6(x: number): number {
  return Math.round(x * 1e6) / 1e6;
}
