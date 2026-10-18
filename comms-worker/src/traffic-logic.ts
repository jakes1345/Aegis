/**
 * Pure traffic logic shared by the Traffic Durable Object and its tests:
 * medians, distances, time buckets, stoppage detection and WZDx parsing.
 * No Workers APIs here, so `node --test` can load this file directly.
 */

export type IncidentType = "police" | "crash" | "hazard" | "closure" | "jam";
export type IncidentSource = "user" | "auto" | "wzdx";

export const INCIDENT_TYPES: readonly IncidentType[] = ["police", "crash", "hazard", "closure", "jam"];

/** Seconds a user or auto incident lives without further confirmation. */
export const INCIDENT_TTL_SEC: Record<IncidentType, number> = {
  police: 45 * 60,
  crash: 90 * 60,
  hazard: 60 * 60,
  jam: 30 * 60,
  closure: 240 * 60,
};

export const LIVE_WINDOW_SEC = 10 * 60;
export const PROBE_MAX_AGE_SEC = 15 * 60;
export const PROBE_RETENTION_SEC = 2 * 60 * 60;
export const BUCKET_SEC = 15 * 60;
export const EWMA_ALPHA = 0.2;
export const MERGE_RADIUS_M = 150;
export const MERGE_WINDOW_SEC = 30 * 60;
export const STOP_KPH = 8;
export const STOP_WINDOW_SEC = 5 * 60;
export const SUDDEN_FROM_KPH = 60;
export const SUDDEN_WINDOW_SEC = 2 * 60;
export const DETECT_MIN_REF_KPH = 40;
export const DEFAULT_REF_KPH = 50;
export const DETECT_MIN_SENDERS = 2;
export const GONE_VOTES_TO_REMOVE = 2;

export const SEGMENT_RE = /^[0-9]{1,20}:[01]$/;
export const REGION_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;

export function median(values: readonly number[]): number | null {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = sorted.length >> 1;
  return sorted.length % 2 === 1 ? sorted[mid]! : (sorted[mid - 1]! + sorted[mid]!) / 2;
}

const EARTH_RADIUS_M = 6_371_008.8;

export function haversineM(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const toRad = Math.PI / 180;
  const dLat = (lat2 - lat1) * toRad;
  const dLon = (lon2 - lon1) * toRad;
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * toRad) * Math.cos(lat2 * toRad) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
}

/** Index into the 168-slot typical week: dow*24+hour, UTC, Monday = 0. */
export function weekSlot(epochSec: number): number {
  const d = new Date(epochSec * 1000);
  const dow = (d.getUTCDay() + 6) % 7;
  return dow * 24 + d.getUTCHours();
}

export function bucketStart(epochSec: number): number {
  return Math.floor(epochSec / BUCKET_SEC) * BUCKET_SEC;
}

export function ewma(previous: number | null, sample: number, alpha: number = EWMA_ALPHA): number {
  return previous === null ? sample : previous + alpha * (sample - previous);
}

export type ProbeRow = {
  seg: string;
  sender: string;
  kph: number;
  t: number;
};

export interface LiveSegment {
  kph: number;
  n: number;
  t: number;
}

/**
 * Live speed per segment: each sender contributes only its most recent sample
 * on that segment, so one phone stuck at a light cannot outvote the rest.
 */
export function liveSegments(rows: readonly ProbeRow[], now: number, windowSec: number = LIVE_WINDOW_SEC): Record<string, LiveSegment> {
  const latest = new Map<string, Map<string, ProbeRow>>();
  for (const r of rows) {
    if (r.t < now - windowSec || r.t > now + 60) continue;
    let bySender = latest.get(r.seg);
    if (!bySender) latest.set(r.seg, (bySender = new Map()));
    const prev = bySender.get(r.sender);
    if (!prev || r.t > prev.t) bySender.set(r.sender, r);
  }
  const out: Record<string, LiveSegment> = {};
  for (const [seg, bySender] of latest) {
    const samples = [...bySender.values()];
    const m = median(samples.map((s) => s.kph));
    if (m === null) continue;
    out[seg] = { kph: round1(m), n: samples.length, t: Math.max(...samples.map((s) => s.t)) };
  }
  return out;
}

/**
 * The 15-minute median per (bucket, segment) for typical speeds. A sender's
 * samples inside one bucket collapse to their own median first, so the
 * bucket value is a median across vehicles, not across GPS fixes.
 */
export function bucketMedians(rows: readonly ProbeRow[]): Array<{ bucket: number; seg: string; kph: number }> {
  const groups = new Map<string, Map<string, number[]>>();
  for (const r of rows) {
    const key = `${bucketStart(r.t)}|${r.seg}`;
    let bySender = groups.get(key);
    if (!bySender) groups.set(key, (bySender = new Map()));
    const list = bySender.get(r.sender);
    if (list) list.push(r.kph);
    else bySender.set(r.sender, [r.kph]);
  }
  const out: Array<{ bucket: number; seg: string; kph: number }> = [];
  for (const [key, bySender] of groups) {
    const sep = key.indexOf("|");
    const perSender: number[] = [];
    for (const list of bySender.values()) {
      const m = median(list);
      if (m !== null) perSender.push(m);
    }
    const m = median(perSender);
    if (m !== null) out.push({ bucket: Number(key.slice(0, sep)), seg: key.slice(sep + 1), kph: m });
  }
  out.sort((a, b) => a.bucket - b.bucket || (a.seg < b.seg ? -1 : a.seg > b.seg ? 1 : 0));
  return out;
}

/**
 * Stoppage on `seg`: at least two distinct senders under STOP_KPH there in the
 * last five minutes, on a road that normally flows at DETECT_MIN_REF_KPH or
 * more. A sender whose latest sample (on any segment) is no longer slow has
 * moved on, so it does not count: two cars that waited at a red light and then
 * drove off are not a jam. It is a crash rather than a jam when at least two of
 * the stopped senders were doing more than SUDDEN_FROM_KPH (on any segment) in
 * the two minutes before they stopped. `history` must hold those senders'
 * recent samples on every segment.
 */
export function detectStoppage(seg: string, history: readonly ProbeRow[], typicalKph: number | null, now: number): "crash" | "jam" | null {
  const ref = typicalKph ?? DEFAULT_REF_KPH;
  if (ref < DETECT_MIN_REF_KPH) return null;
  const firstSlow = new Map<string, number>();
  for (const r of history) {
    if (r.seg !== seg || r.kph >= STOP_KPH || r.t < now - STOP_WINDOW_SEC || r.t > now + 60) continue;
    const prev = firstSlow.get(r.sender);
    if (prev === undefined || r.t < prev) firstSlow.set(r.sender, r.t);
  }
  if (firstSlow.size < DETECT_MIN_SENDERS) return null;
  const latest = new Map<string, ProbeRow>();
  for (const r of history) {
    if (!firstSlow.has(r.sender) || r.t > now + 60) continue;
    const prev = latest.get(r.sender);
    if (!prev || r.t > prev.t || (r.t === prev.t && r.kph > prev.kph)) latest.set(r.sender, r);
  }
  for (const [sender, r] of latest) if (r.kph >= STOP_KPH) firstSlow.delete(sender);
  if (firstSlow.size < DETECT_MIN_SENDERS) return null;
  let sudden = 0;
  for (const [sender, slowT] of firstSlow) {
    const wasFast = history.some((r) => r.sender === sender && r.kph > SUDDEN_FROM_KPH && r.t >= slowT - SUDDEN_WINDOW_SEC && r.t < slowT);
    if (wasFast) sudden++;
  }
  return sudden >= DETECT_MIN_SENDERS ? "crash" : "jam";
}

export interface Positioned {
  id: string;
  type: string;
  lat: number | null;
  lon: number | null;
  /** When it was last reported or confirmed, epoch seconds. */
  activeAt: number;
  expiresAt: number;
}

/** The nearest live incident of the same type, reported or confirmed within the last 30 minutes, within 150 m, if any. */
export function findMergeTarget<T extends Positioned>(candidates: readonly T[], type: string, lat: number, lon: number, now: number): T | null {
  let best: T | null = null;
  let bestD = Infinity;
  for (const c of candidates) {
    if (c.type !== type || c.lat === null || c.lon === null) continue;
    if (c.expiresAt <= now || c.activeAt < now - MERGE_WINDOW_SEC) continue;
    const d = haversineM(lat, lon, c.lat, c.lon);
    if (d <= MERGE_RADIUS_M && d < bestD) {
      best = c;
      bestD = d;
    }
  }
  return best;
}

export type BBox = [minLat: number, minLon: number, maxLat: number, maxLon: number];

export function bboxContains(b: BBox, lat: number, lon: number): boolean {
  return lat >= b[0] && lat <= b[2] && lon >= b[1] && lon <= b[3];
}

/**
 * Bounding box of the points a region has seen. With enough points the
 * extreme 0.5% on each side is dropped, so one bogus coordinate cannot widen
 * the region to a whole continent and pull in every work zone on the feed.
 */
export function robustBBox(points: ReadonlyArray<{ lat: number; lon: number }>, padDeg = 0.05): BBox | null {
  if (points.length === 0) return null;
  const lats = points.map((p) => p.lat).sort((a, b) => a - b);
  const lons = points.map((p) => p.lon).sort((a, b) => a - b);
  const trim = points.length >= 100 ? Math.floor(points.length * 0.005) : 0;
  const lo = trim;
  const hi = points.length - 1 - trim;
  return [
    Math.max(-90, lats[lo]! - padDeg),
    Math.max(-180, lons[lo]! - padDeg),
    Math.min(90, lats[hi]! + padDeg),
    Math.min(180, lons[hi]! + padDeg),
  ];
}

// ── WZDx v4 ─────────────────────────────────────────────────────────────

export interface WzdxEvent {
  /** Stable across refreshes of the same feed. */
  extId: string;
  type: "closure" | "hazard";
  lat: number;
  lon: number;
  description: string | null;
  /** Epoch seconds; null when the feed gives no end. */
  endsAt: number | null;
}

/** 32-bit FNV-1a, hex: a short stable key for feed URLs and id-less features. */
export function fnv1a(text: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, "0");
}

type Coord = [number, number];

function isCoord(v: unknown): v is Coord {
  return Array.isArray(v) && v.length >= 2 && typeof v[0] === "number" && typeof v[1] === "number" && Number.isFinite(v[0]) && Number.isFinite(v[1]);
}

/** The point halfway along a line, measured along its length. */
export function lineMidpoint(coords: readonly Coord[]): Coord | null {
  if (coords.length === 0) return null;
  if (coords.length === 1) return coords[0]!;
  let total = 0;
  const lens: number[] = [];
  for (let i = 1; i < coords.length; i++) {
    const a = coords[i - 1]!;
    const b = coords[i]!;
    const d = haversineM(a[1], a[0], b[1], b[0]);
    lens.push(d);
    total += d;
  }
  if (total === 0) return coords[0]!;
  let remaining = total / 2;
  for (let i = 0; i < lens.length; i++) {
    const d = lens[i]!;
    if (remaining <= d) {
      const a = coords[i]!;
      const b = coords[i + 1]!;
      const f = d === 0 ? 0 : remaining / d;
      return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
    }
    remaining -= d;
  }
  return coords[coords.length - 1]!;
}

/** [lon, lat] for a GeoJSON geometry: LineStrings by their midpoint, everything else by its first coordinate. */
export function representativePoint(geometry: unknown): Coord | null {
  if (!geometry || typeof geometry !== "object") return null;
  const g = geometry as { type?: unknown; coordinates?: unknown };
  const c = g.coordinates;
  switch (g.type) {
    case "Point":
      return isCoord(c) ? c : null;
    case "LineString":
      return Array.isArray(c) ? lineMidpoint(c.filter(isCoord)) : null;
    case "MultiPoint":
      return Array.isArray(c) && isCoord(c[0]) ? c[0] : null;
    case "MultiLineString":
      return Array.isArray(c) && Array.isArray(c[0]) ? lineMidpoint((c[0] as unknown[]).filter(isCoord)) : null;
    case "Polygon":
    case "MultiPolygon": {
      let v: unknown = c;
      while (Array.isArray(v) && !isCoord(v)) v = v[0];
      return isCoord(v) ? v : null;
    }
    default:
      return null;
  }
}

function parseDateSec(v: unknown): number | null {
  if (typeof v !== "string" || v.length === 0) return null;
  const ms = Date.parse(v);
  return Number.isFinite(ms) ? Math.floor(ms / 1000) : null;
}

const DESCRIPTION_MAX = 280;

/**
 * Work zones from a WZDx v4 FeatureCollection that are in effect at `now`.
 * Fields are read from `properties.core_details` (v4.x) with a fallback to
 * `properties` itself, which some state feeds still use.
 */
export function parseWzdx(feedUrl: string, doc: unknown, now: number): WzdxEvent[] {
  if (!doc || typeof doc !== "object") return [];
  const features = (doc as { features?: unknown }).features;
  if (!Array.isArray(features)) return [];
  const feedKey = fnv1a(feedUrl);
  const out: WzdxEvent[] = [];
  const seen = new Set<string>();
  for (const f of features) {
    if (!f || typeof f !== "object") continue;
    const feature = f as { id?: unknown; geometry?: unknown; properties?: unknown };
    const props = (feature.properties && typeof feature.properties === "object" ? feature.properties : {}) as Record<string, unknown>;
    const core = (props.core_details && typeof props.core_details === "object" ? props.core_details : props) as Record<string, unknown>;
    if (core.event_type !== "work-zone") continue;
    const start = parseDateSec(props.start_date);
    const end = parseDateSec(props.end_date);
    if (start !== null && start > now) continue;
    if (end !== null && end <= now) continue;
    const point = representativePoint(feature.geometry);
    if (!point) continue;
    const [lon, lat] = point;
    if (lat < -90 || lat > 90 || lon < -180 || lon > 180) continue;
    const rawId = typeof feature.id === "string" || typeof feature.id === "number" ? String(feature.id) : fnv1a(`${JSON.stringify(feature.geometry)}|${String(props.start_date ?? "")}`);
    const extId = `${feedKey}:${rawId}`;
    if (seen.has(extId)) continue;
    seen.add(extId);
    out.push({
      extId,
      type: props.vehicle_impact === "all-lanes-closed" ? "closure" : "hazard",
      lat,
      lon,
      description: wzdxDescription(core),
      endsAt: end,
    });
  }
  return out;
}

function wzdxDescription(core: Record<string, unknown>): string | null {
  const desc = typeof core.description === "string" ? core.description.trim() : "";
  const roads = Array.isArray(core.road_names) ? core.road_names.filter((r): r is string => typeof r === "string" && r.trim().length > 0).map((r) => r.trim()) : [];
  let text = desc;
  if (roads.length > 0) {
    const roadText = `Work zone on ${roads.join(", ")}`;
    text = desc ? `${roadText}: ${desc}` : roadText;
  }
  if (!text) return null;
  return text.length > DESCRIPTION_MAX ? `${text.slice(0, DESCRIPTION_MAX - 1)}…` : text;
}

export function round1(x: number): number {
  return Math.round(x * 10) / 10;
}

/**
 * Reads a response body as UTF-8 text, giving up (null) as soon as it is known to exceed maxBytes:
 * up front from a declared content-length, otherwise while streaming, so an oversized or endless
 * body is never buffered whole.
 */
export async function readTextCapped(
  body: ReadableStream<Uint8Array> | null,
  contentLength: string | null,
  maxBytes: number,
): Promise<string | null> {
  const declared = contentLength === null ? NaN : Number(contentLength);
  if (Number.isFinite(declared) && declared > maxBytes) {
    if (body) await body.cancel().catch(() => {});
    return null;
  }
  if (!body) return "";
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel().catch(() => {});
      return null;
    }
    chunks.push(value);
  }
  const decoder = new TextDecoder();
  let text = "";
  for (const c of chunks) text += decoder.decode(c, { stream: true });
  return text + decoder.decode();
}
