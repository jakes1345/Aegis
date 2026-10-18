#!/usr/bin/env python3
"""Aggregate the public WZDx / CWZ work-zone feeds into per-state files for the phone.

Reads the USDOT WZDx feed registry, fetches every active feed (key-gated feeds only when a key
is configured in WZDX_KEYS), normalizes the features and writes

    <out>/workzones-<slug>.json.gz   for every region slug in tools/graph-builder/us-regions.txt
    <out>/workzones-index.json       per-feed status

usage: aggregate.py [--registry URL|PATH] [--out DIR] [--regions FILE] [--bboxes FILE]
"""
from __future__ import annotations

import argparse
import concurrent.futures
import gzip
import hashlib
import io
import json
import logging
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
REGISTRY_URL = "https://data.transportation.gov/resource/69qe-yiui.json?$limit=500"
DEFAULT_REGIONS = os.path.join(HERE, "..", "graph-builder", "us-regions.txt")
DEFAULT_BBOXES = os.path.join(HERE, "state_bboxes.json")

FETCH_TIMEOUT_S = 60
MAX_BYTES = 50 * 1024 * 1024
# Bounds a gzip body after decompression. Larger than MAX_BYTES because big feeds compress ~60x
# (Florida's: 1.6 MB on the wire, 106 MB of JSON).
MAX_DECOMPRESSED_BYTES = 256 * 1024 * 1024
RETRIES = 2
RETRY_DELAY_S = 2.0
USER_AGENT = "Aegis-workzones/1 (+https://github.com/jakes1345/Aegis)"
LOOKAHEAD_S = 24 * 3600
MAX_POINTS = 200
SIMPLIFY_TOLERANCE_M = 10.0
MAX_DESCRIPTION = 1000
EARTH_RADIUS_M = 6371008.8
MPH_TO_KPH = 1.609344

TYPES = ("closure", "lane_closure", "work_zone", "detour", "restriction")
IMPACTS = ("all_closed", "some_closed", "shoulder", "open", "unknown")

# Lane types that carry no through traffic: closing only these does not reduce capacity.
NON_TRAVEL_LANES = {
    "shoulder", "left-shoulder", "right-shoulder", "median", "sidewalk", "bike-lane",
    "parking", "curb", "two-way-center-turn-lane",
}
SHOULDER_LANES = {"shoulder", "left-shoulder", "right-shoulder"}
CLOSED_LANE_STATUSES = {"closed", "merge-left", "merge-right"}

# Registry URLs for key-gated feeds often carry a literal placeholder where the key belongs.
PLACEHOLDER_RE = re.compile(r"^(?:%3c.*%3e|<.*>|\[.*\]|.*(?:insert|your).*|key)$", re.IGNORECASE)

log = logging.getLogger("workzones")


# ---------------------------------------------------------------------------------------------
# Registry and fetching


def truthy(v) -> bool:
    if isinstance(v, bool):
        return v
    if isinstance(v, str):
        return v.strip().lower() in ("true", "1", "yes")
    return bool(v)


def registry_url_of(entry: dict) -> str | None:
    u = entry.get("url")
    if isinstance(u, dict):
        u = u.get("url")
    if isinstance(u, str) and u.strip():
        return u.strip()
    return None


def apply_key(url: str, key: str) -> str:
    """Put an API key into a registry URL.

    A URL ending in "=" is waiting for its key. A URL whose last query value is a placeholder
    (INSERT-API-KEY-HERE, [Your-API-Key-Here], %3ckey%3e, ...) gets the placeholder replaced.
    Anything else gets api_key appended.
    """
    enc = urllib.parse.quote(key, safe="")
    if url.endswith("="):
        return url + enc
    parts = urllib.parse.urlsplit(url)
    if parts.query:
        pairs = parts.query.split("&")
        last = pairs[-1]
        if "=" in last:
            name, value = last.split("=", 1)
            if value and PLACEHOLDER_RE.match(value):
                pairs[-1] = f"{name}={enc}"
                return urllib.parse.urlunsplit(parts._replace(query="&".join(pairs)))
    sep = "&" if parts.query else "?"
    return url + sep + "api_key=" + enc


def read_capped(resp, cap: int) -> bytes:
    buf = io.BytesIO()
    while True:
        chunk = resp.read(1 << 16)
        if not chunk:
            break
        buf.write(chunk)
        if buf.tell() > cap:
            raise ValueError(f"response larger than {cap} bytes")
    return buf.getvalue()


def gunzip_capped(data: bytes, cap: int) -> bytes:
    with gzip.GzipFile(fileobj=io.BytesIO(data)) as gz:
        out = gz.read(cap + 1)
    if len(out) > cap:
        raise ValueError(f"decompressed response larger than {cap} bytes")
    return out


def fetch_bytes(url: str, timeout: float = FETCH_TIMEOUT_S, cap: int = MAX_BYTES,
                retries: int | None = None) -> bytes:
    retries = RETRIES if retries is None else retries
    last: Exception | None = None
    for attempt in range(retries + 1):
        if attempt:
            time.sleep(RETRY_DELAY_S * attempt)
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": USER_AGENT,
                "Accept": "application/geo+json, application/json;q=0.9, */*;q=0.5",
                "Accept-Encoding": "gzip",
            })
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                data = read_capped(resp, cap)
            # Some servers gzip without saying so, so trust the magic bytes over the header.
            if data[:2] == b"\x1f\x8b":
                data = gunzip_capped(data, MAX_DECOMPRESSED_BYTES)
            return data
        except urllib.error.HTTPError as e:
            last = e
            if 400 <= e.code < 500 and e.code not in (408, 429):
                break
        except Exception as e:  # noqa: BLE001 - any transport failure is retried, then reported
            last = e
    assert last is not None
    raise last


def load_json_source(src: str):
    if re.match(r"^https?://", src):
        return json.loads(fetch_bytes(src).decode("utf-8-sig"))
    with open(src, "rb") as f:
        return json.loads(f.read().decode("utf-8-sig"))


def describe_error(e: Exception) -> str:
    # Never str() a URLError's URL-bearing pieces: the URL may contain an API key.
    if isinstance(e, urllib.error.HTTPError):
        return f"HTTP {e.code}"
    if isinstance(e, urllib.error.URLError):
        return f"network error: {type(e.reason).__name__}: {e.reason}"[:200]
    if isinstance(e, json.JSONDecodeError):
        return f"invalid JSON: {e.msg} at {e.pos}"
    return f"{type(e).__name__}: {e}"[:200]


# ---------------------------------------------------------------------------------------------
# Parsing


def parse_time(v) -> int | None:
    if v is None or v == "":
        return None
    if isinstance(v, (int, float)) and not isinstance(v, bool):
        x = float(v)
        if x > 1e11:  # milliseconds
            x /= 1000.0
        return int(x)
    if not isinstance(v, str):
        return None
    s = v.strip()
    if s.endswith("Z") or s.endswith("z"):
        s = s[:-1] + "+00:00"
    # fromisoformat in 3.11 accepts at most 6 fractional digits; .NET feeds send 7.
    s = re.sub(r"(\.\d{6})\d+", r"\1", s)
    try:
        dt = datetime.fromisoformat(s)
    except ValueError:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    if dt.year < 1971:
        return None
    return int(dt.timestamp())


def num(v) -> float | None:
    if isinstance(v, bool) or v is None:
        return None
    if isinstance(v, (int, float)):
        f = float(v)
    elif isinstance(v, str):
        try:
            f = float(v.strip())
        except ValueError:
            return None
    else:
        return None
    return f if math.isfinite(f) else None


def clean_str(v, limit: int | None = None) -> str | None:
    if isinstance(v, list):
        parts = [p.strip() for p in v if isinstance(p, str) and p.strip()]
        v = " / ".join(dict.fromkeys(parts)) if parts else None
    if not isinstance(v, str):
        return None
    s = " ".join(v.split())
    if not s:
        return None
    if limit and len(s) > limit:
        s = s[: limit - 1].rstrip() + "…"
    return s


def clean_direction(v) -> str | None:
    d = clean_str(v, 40)
    if d is None or d.lower() in ("unknown", "undefined", "none", "n/a"):
        return None
    return d.lower()


def lanes_impact(lanes) -> str | None:
    """Impact implied by a WZDx lanes array, or None when it says nothing."""
    if not isinstance(lanes, list) or not lanes:
        return None
    travel_total = travel_closed = 0
    shoulder_closed = False
    for lane in lanes:
        if not isinstance(lane, dict):
            continue
        ltype = str(lane.get("type") or "general").lower()
        status = str(lane.get("status") or "").lower()
        closed = status in CLOSED_LANE_STATUSES
        if ltype in NON_TRAVEL_LANES:
            if closed and ltype in SHOULDER_LANES:
                shoulder_closed = True
            continue
        travel_total += 1
        if closed:
            travel_closed += 1
    if travel_total == 0:
        return "shoulder" if shoulder_closed else None
    if travel_closed == travel_total:
        return "all_closed"
    if travel_closed:
        return "some_closed"
    return "shoulder" if shoulder_closed else "open"


def map_impact(vehicle_impact, lanes) -> str:
    vi = str(vehicle_impact or "").strip().lower().replace("_", "-")
    from_lanes = lanes_impact(lanes)
    if vi == "all-lanes-closed":
        return "all_closed"
    if vi in ("some-lanes-closed", "some-lanes-closed-merge-left", "some-lanes-closed-merge-right",
              "some-lanes-closed-split", "alternating-one-way", "flagging"):
        return "shoulder" if from_lanes == "shoulder" else "some_closed"
    if vi in ("all-lanes-open", "all-lanes-open-shift-left", "all-lanes-open-shift-right",
              "all-lanes-open-split", "lanes-shift"):
        return "shoulder" if from_lanes == "shoulder" else "open"
    return from_lanes or "unknown"


def map_type(event_type, impact: str) -> str:
    et = str(event_type or "").strip().lower().replace("_", "-")
    if et == "detour":
        return "detour"
    if et == "restriction":
        return "restriction"
    if impact == "all_closed":
        return "closure"
    if impact == "some_closed":
        return "lane_closure"
    return "work_zone"


def valid_coord(c) -> tuple[float, float] | None:
    if not isinstance(c, (list, tuple)) or len(c) < 2:
        return None
    lon, lat = num(c[0]), num(c[1])
    if lon is None or lat is None or not (-90 <= lat <= 90) or not (-180 <= lon <= 180):
        return None
    if lat == 0 and lon == 0:
        return None
    return (lat, lon)


def geometry_parts(geom) -> list[list[tuple[float, float]]]:
    """Each separate line/point set of a GeoJSON geometry as [(lat, lon), ...]."""
    if not isinstance(geom, dict):
        return []
    gtype = geom.get("type")
    coords = geom.get("coordinates")
    raw: list[list] = []
    if gtype == "Point":
        raw = [[coords]]
    elif gtype in ("LineString", "MultiPoint"):
        raw = [coords] if isinstance(coords, list) else []
    elif gtype == "MultiLineString":
        raw = [p for p in coords if isinstance(p, list)] if isinstance(coords, list) else []
    elif gtype == "GeometryCollection":
        out = []
        for g in geom.get("geometries") or []:
            out.extend(geometry_parts(g))
        return out
    parts = []
    for part in raw:
        pts = []
        for c in part if isinstance(part, list) else []:
            p = valid_coord(c)
            if p is not None and (not pts or pts[-1] != p):
                pts.append(p)
        if pts:
            parts.append(pts)
    return parts


def _seg_dist(px: float, py: float, ax: float, ay: float, bx: float, by: float) -> float:
    dx, dy = bx - ax, by - ay
    seg2 = dx * dx + dy * dy
    if seg2 == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / seg2))
    return math.hypot(px - ax - t * dx, py - ay - t * dy)


def dp_significance(pts: list[tuple[float, float]], tol_m: float) -> list[float]:
    """Douglas-Peucker with tolerance tol_m, returning each point's split distance (0 = dropped).

    A point's value is capped by its parent's, so the points above any threshold >= tol_m are
    exactly the Douglas-Peucker result for that threshold; one pass serves every tolerance.
    """
    n = len(pts)
    sig = [0.0] * n
    if n == 0:
        return sig
    sig[0] = sig[-1] = math.inf
    if n <= 2:
        return sig
    # Local equirectangular projection: exact enough at the 10 m scale of the tolerance.
    k = math.radians(1) * EARTH_RADIUS_M
    coslat = math.cos(math.radians(sum(p[0] for p in pts) / n))
    xs = [p[1] * k * coslat for p in pts]
    ys = [p[0] * k for p in pts]
    stack = [(0, n - 1, math.inf)]
    while stack:
        i, j, cap = stack.pop()
        if j <= i + 1:
            continue
        ax, ay, bx, by = xs[i], ys[i], xs[j], ys[j]
        best, best_d = -1, -1.0
        for m in range(i + 1, j):
            d = _seg_dist(xs[m], ys[m], ax, ay, bx, by)
            if d > best_d:
                best, best_d = m, d
        if best_d > tol_m:
            v = min(best_d, cap)
            sig[best] = v
            stack.append((i, best, v))
            stack.append((best, j, v))
    return sig


def simplify(pts: list[tuple[float, float]], tol_m: float = SIMPLIFY_TOLERANCE_M,
             max_points: int = MAX_POINTS) -> list[list[float]]:
    """Douglas-Peucker at tol_m; if that leaves more than max_points, the tolerance is raised
    to the smallest value that fits."""
    sig = dp_significance(pts, tol_m)
    kept = [i for i, v in enumerate(sig) if v > 0]
    if len(kept) > max_points:
        threshold = sorted((sig[i] for i in kept), reverse=True)[max_points - 1]
        kept = [i for i in kept if sig[i] >= threshold][:max_points]
        if kept[-1] != len(pts) - 1:
            kept[-1] = len(pts) - 1
    rounded: list[list[float]] = []
    for i in kept:
        q = [round(pts[i][0], 5), round(pts[i][1], 5)]
        if not rounded or rounded[-1] != q:
            rounded.append(q)
    return rounded


def feature_list(doc) -> list:
    if isinstance(doc, dict):
        feats = doc.get("features")
        if isinstance(feats, list):
            return feats
        for key in ("data", "result", "results"):
            inner = doc.get(key)
            if isinstance(inner, dict) and isinstance(inner.get("features"), list):
                return inner["features"]
        return []
    if isinstance(doc, list):
        return [f for f in doc if isinstance(f, dict) and "geometry" in f]
    return []


def feature_fields(props: dict) -> dict:
    """Pull the fields we use out of a v4.x / CWZ (core_details) or v3.x (flat) feature."""
    core = props.get("core_details")
    if isinstance(core, dict):
        road = core.get("road_names", core.get("road_name"))
        event_type = core.get("event_type")
        direction = core.get("direction")
        description = core.get("description")
        name = core.get("name")
    else:
        road = props.get("road_names", props.get("road_name"))
        event_type = props.get("event_type")
        direction = props.get("direction")
        description = props.get("description")
        name = props.get("name")
    speed = num(props.get("reduced_speed_limit_kph"))
    if speed is None:
        mph = num(props.get("reduced_speed_limit"))
        speed = mph * MPH_TO_KPH if mph is not None else None
    if speed is not None and speed <= 0:
        speed = None  # several feeds send 0 for "no reduced limit"
    return {
        "event_type": event_type,
        "road": clean_str(road, 200),
        "direction": clean_direction(direction),
        "description": clean_str(description or name, MAX_DESCRIPTION),
        "start": parse_time(props.get("start_date")),
        "end": parse_time(props.get("end_date")),
        "vehicle_impact": props.get("vehicle_impact"),
        "lanes": props.get("lanes"),
        "speed_kph": round(speed, 1) if speed is not None else None,
        "event_status": str(props.get("event_status") or "").lower(),
    }


def stable_hash(feature: dict) -> str:
    blob = json.dumps({"p": feature.get("properties"), "g": feature.get("geometry")},
                      sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha1(blob.encode("utf-8")).hexdigest()[:16]


def parse_feed(doc, feedname: str, source: str, now: int) -> list[dict]:
    items: list[dict] = []
    seen: dict[str, int] = {}
    horizon = now + LOOKAHEAD_S
    for feat in feature_list(doc):
        if not isinstance(feat, dict):
            continue
        props = feat.get("properties")
        if not isinstance(props, dict):
            continue
        f = feature_fields(props)
        if f["event_status"] in ("completed", "cancelled", "canceled"):
            continue
        if f["end"] is not None and f["end"] < now:
            continue
        if f["start"] is not None and f["start"] > horizon:
            continue
        parts = geometry_parts(feat.get("geometry"))
        if not parts:
            continue
        impact = map_impact(f["vehicle_impact"], f["lanes"])
        etype = map_type(f["event_type"], impact)
        fid = feat.get("id", props.get("road_event_id"))
        base = str(fid).strip() if fid not in (None, "") else stable_hash(feat)
        for n, part in enumerate(parts):
            ident = base if len(parts) == 1 else f"{base}#{n}"
            dup = seen.get(ident, 0)
            seen[ident] = dup + 1
            if dup:
                ident = f"{ident}~{dup}"
            items.append({
                "id": f"{feedname}:{ident}",
                "type": etype,
                "impact": impact,
                "speed_kph": f["speed_kph"],
                "road": f["road"],
                "direction": f["direction"],
                "description": f["description"],
                "start": f["start"],
                "end": f["end"],
                "geom": simplify(part),
                "source": source,
            })
    return items


# ---------------------------------------------------------------------------------------------
# State split and output


def region_slugs(path: str) -> list[str]:
    slugs = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                slugs.append(line.replace("/", "-"))
    return slugs


def load_bboxes(path: str) -> dict[str, list[float]]:
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    return {k: v for k, v in raw.items() if not k.startswith("_")}


def geom_bbox(geom: list[list[float]]) -> tuple[float, float, float, float]:
    lats = [p[0] for p in geom]
    lons = [p[1] for p in geom]
    return min(lats), min(lons), max(lats), max(lons)


def bbox_intersects(a, b) -> bool:
    return a[0] <= b[2] and b[0] <= a[2] and a[1] <= b[3] and b[1] <= a[3]


def split_by_state(items: list[dict], bboxes: dict[str, list[float]]) -> dict[str, list[dict]]:
    out: dict[str, list[dict]] = {slug: [] for slug in bboxes}
    for it in items:
        ib = geom_bbox(it["geom"])
        for slug, sb in bboxes.items():
            if bbox_intersects(ib, sb):
                out[slug].append(it)
    return out


def write_gz_json(path: str, obj) -> None:
    data = json.dumps(obj, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    tmp = path + ".tmp"
    with open(tmp, "wb") as raw:
        # Empty name and mtime=0 keep unchanged content byte-identical between runs.
        with gzip.GzipFile(filename="", fileobj=raw, mode="wb", compresslevel=9, mtime=0) as gz:
            gz.write(data)
    os.replace(tmp, path)


def write_outputs(out_dir: str, slugs: list[str], by_state: dict[str, list[dict]],
                  feeds: list[dict], now: int) -> None:
    os.makedirs(out_dir, exist_ok=True)
    for slug in slugs:
        items = sorted(by_state.get(slug, []), key=lambda i: i["id"])
        write_gz_json(os.path.join(out_dir, f"workzones-{slug}.json.gz"),
                      {"version": 1, "generated": now, "slug": slug, "items": items})
    index = {"generated": now, "feeds": feeds}
    tmp = os.path.join(out_dir, "workzones-index.json.tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(index, f, indent=1, ensure_ascii=False)
        f.write("\n")
    os.replace(tmp, os.path.join(out_dir, "workzones-index.json"))


# ---------------------------------------------------------------------------------------------
# Driver


def plan_feeds(registry: list, keys: dict[str, str]) -> tuple[list[dict], list[dict]]:
    """(feeds to fetch, index rows for feeds skipped up front)."""
    to_fetch, skipped = [], []
    seen = set()
    for entry in registry if isinstance(registry, list) else []:
        if not isinstance(entry, dict) or not truthy(entry.get("active")):
            continue
        name = str(entry.get("feedname") or "").strip()
        url = registry_url_of(entry)
        if not name or not url or name in seen:
            continue
        seen.add(name)
        row = {"name": name, "state": entry.get("state"),
               "source": entry.get("issuingorganization") or name, "url": url}
        if truthy(entry.get("needapikey")):
            key = keys.get(name)
            if not key:
                skipped.append({"name": name, "state": entry.get("state"), "items": 0,
                                "ok": False, "error": "api key not configured"})
                continue
            row["url"] = apply_key(url, key)
        to_fetch.append(row)
    return to_fetch, skipped


def process_feed(feed: dict, now: int) -> tuple[dict, list[dict]]:
    status = {"name": feed["name"], "state": feed["state"], "items": 0, "ok": False, "error": None}
    try:
        raw = fetch_bytes(feed["url"])
        if raw.lstrip()[:1] == b"<":
            raise ValueError("response is HTML/XML, not GeoJSON")
        doc = json.loads(raw.decode("utf-8-sig"))
        del raw
        if not feature_list(doc) and not (isinstance(doc, dict) and "features" in doc):
            raise ValueError("not a GeoJSON FeatureCollection")
        items = parse_feed(doc, feed["name"], feed["source"], now)
    except Exception as e:  # noqa: BLE001 - one bad feed must not sink the run
        status["error"] = describe_error(e)
        log.warning("feed %s failed: %s", feed["name"], status["error"])
        return status, []
    status["items"] = len(items)
    status["ok"] = True
    log.info("feed %s: %d items", feed["name"], len(items))
    return status, items


def parse_keys(env_value: str | None) -> dict[str, str]:
    if not env_value or not env_value.strip():
        return {}
    try:
        obj = json.loads(env_value)
    except json.JSONDecodeError as e:
        raise SystemExit(f"WZDX_KEYS is not valid JSON ({e.msg})") from None
    if not isinstance(obj, dict):
        raise SystemExit("WZDX_KEYS must be a JSON object of feedname -> key")
    return {str(k): str(v) for k, v in obj.items() if isinstance(v, (str, int)) and str(v)}


def run(registry_src: str, out_dir: str, regions_path: str, bboxes_path: str,
        keys: dict[str, str], now: int | None = None, workers: int = 8) -> dict:
    now = int(time.time()) if now is None else now
    slugs = region_slugs(regions_path)
    bboxes = load_bboxes(bboxes_path)
    missing = [s for s in slugs if s not in bboxes]
    if missing:
        raise SystemExit(f"state_bboxes.json lacks: {', '.join(missing)}")
    bboxes = {s: bboxes[s] for s in slugs}

    registry = load_json_source(registry_src)
    to_fetch, statuses = plan_feeds(registry, keys)
    all_items: list[dict] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        results = list(pool.map(lambda f: process_feed(f, now), to_fetch))
    for status, items in results:
        statuses.append(status)
        all_items.extend(items)
    statuses.sort(key=lambda s: s["name"])

    by_state = split_by_state(all_items, bboxes)
    write_outputs(out_dir, slugs, by_state, statuses, now)
    ok = sum(1 for s in statuses if s["ok"])
    log.info("%d/%d feeds ok, %d items, wrote %d state files to %s",
             ok, len(statuses), len(all_items), len(slugs), out_dir)
    return {"feeds": statuses, "items": len(all_items),
            "per_state": {s: len(by_state[s]) for s in slugs}}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--registry", default=os.environ.get("WZDX_REGISTRY", REGISTRY_URL))
    ap.add_argument("--out", default="dist")
    ap.add_argument("--regions", default=DEFAULT_REGIONS)
    ap.add_argument("--bboxes", default=DEFAULT_BBOXES)
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s", stream=sys.stderr)

    result = run(args.registry, args.out, args.regions, args.bboxes,
                 parse_keys(os.environ.get("WZDX_KEYS")))
    if not any(s["ok"] for s in result["feeds"]):
        # Publishing now would replace every state's last good file with an empty one.
        log.error("no feed could be fetched; not publishing")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
