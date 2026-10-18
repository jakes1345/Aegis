// Run with `npm test` (node --test). Node 22.18+ strips the TypeScript types itself.
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  DEFAULT_DAILY_CAP,
  MAX_GEOM_POINTS,
  MAX_UPSTREAM_TILES_PER_REQUEST,
  TILE_ERROR_BACKOFF_SEC,
  TILE_FRESH_SEC,
  TILE_STALE_MAX_SEC,
  combineStatus,
  decideTile,
  hereUrl,
  mapHereType,
  mapTomTomCategory,
  mergeForBBox,
  normalizeHere,
  normalizeTomTom,
  parseBBoxParam,
  parseCap,
  pickTilesToFetch,
  quotaTake,
  serializeTile,
  staleUsable,
  tileFromKey,
  tilesForBBox,
  tomtomUrl,
  utcDay,
} from "../src/incidents-logic.ts";

const NOW = Date.UTC(2025, 9, 15, 12, 0) / 1000;

// Shaped like a HERE Traffic v7 /incidents?locationReferencing=shape reply.
const HERE_SAMPLE = {
  sourceUpdated: "2025-10-15T11:58:00Z",
  results: [
    {
      location: {
        length: 812.0,
        description: "I-70 E",
        shape: {
          links: [
            { points: [{ lat: 39.96021, lng: -83.00214 }, { lat: 39.96055, lng: -82.99801 }], length: 352.6 },
            { points: [{ lat: 39.96055, lng: -82.99801 }, { lat: 39.96102, lng: -82.99263 }], length: 459.4 },
          ],
        },
      },
      incidentDetails: {
        id: "1270784339381044836",
        originalId: "3412769540116412011",
        hrn: "hrn:here:traffic:na:incident:1270784339381044836",
        startTime: "2025-10-15T11:20:00Z",
        endTime: "2025-10-15T13:30:00Z",
        entryTime: "2025-10-15T11:21:30Z",
        roadClosed: false,
        criticality: "major",
        type: "accident",
        codes: [201],
        description: { value: "Accident on I-70 Eastbound at 4th St. Right lane blocked.", language: "en" },
        summary: { value: "Accident on I-70 Eastbound.", language: "en" },
        junctionTraversability: "allOpen",
      },
    },
    {
      location: {
        length: 1500.0,
        shape: { links: [{ points: [{ lat: 39.9, lng: -83.1 }, { lat: 39.91, lng: -83.1 }], length: 1500 }] },
      },
      incidentDetails: {
        id: "998",
        startTime: "2025-10-01T05:00:00Z",
        endTime: "2025-11-20T22:00:00Z",
        roadClosed: true,
        criticality: "critical",
        type: "construction",
        summary: { value: "Closed due to construction.", language: "en" },
      },
    },
    {
      location: { shape: { links: [{ points: [{ lat: 39.95, lng: -83.05 }, { lat: 39.951, lng: -83.05 }] }] } },
      incidentDetails: { id: "ended", type: "congestion", startTime: "2025-10-15T08:00:00Z", endTime: "2025-10-15T09:00:00Z" },
    },
    {
      location: { shape: { links: [] } },
      incidentDetails: { id: "no-shape", type: "roadHazard" },
    },
  ],
};

// Shaped like a TomTom Traffic v5 incidentDetails reply with the proxy's fields filter.
const TOMTOM_SAMPLE = {
  incidents: [
    {
      type: "Feature",
      geometry: {
        type: "LineString",
        coordinates: [
          [-83.0021, 39.9602],
          [-83.0001, 39.9604],
          [-82.9981, 39.9606],
        ],
      },
      properties: {
        id: "4f1a3c7e5b9d2e8f0a1b2c3d4e5f6a7b",
        iconCategory: 6,
        magnitudeOfDelay: 2,
        events: [{ description: "Stationary traffic", code: 101 }],
        startTime: "2025-10-15T11:40:00Z",
        endTime: null,
        from: "Exit 100A (4th St)",
        to: "Exit 101 (18th St)",
        delay: 241,
        roadNumbers: ["I-70", "I-71"],
      },
    },
    {
      type: "Feature",
      geometry: { type: "Point", coordinates: [-83.01, 39.97] },
      properties: {
        id: "point-1",
        iconCategory: 14,
        magnitudeOfDelay: 0,
        events: [{ description: "Broken down vehicle", code: 211 }],
        startTime: "2025-10-15T11:50:00Z",
        endTime: "2025-10-15T12:30:00Z",
        from: null,
        to: null,
        delay: null,
        roadNumbers: [],
      },
    },
    {
      type: "Feature",
      geometry: { type: "LineString", coordinates: [[-83.02, 39.98], [-83.02, 39.99]] },
      properties: { id: "old", iconCategory: 9, events: [], startTime: "2025-10-10T00:00:00Z", endTime: "2025-10-14T00:00:00Z" },
    },
  ],
};

test("bbox validation", () => {
  assert.deepEqual(parseBBoxParam("39.5,-83.5,40.5,-82.5"), { bbox: [39.5, -83.5, 40.5, -82.5] });
  assert.ok("error" in parseBBoxParam(null));
  assert.ok("error" in parseBBoxParam(""));
  assert.ok("error" in parseBBoxParam("1,2,3"));
  assert.ok("error" in parseBBoxParam("1,2,3,x"));
  assert.ok("error" in parseBBoxParam("39,-83,39,-82"), "zero height");
  assert.ok("error" in parseBBoxParam("40,-83,39,-82"), "min above max");
  assert.ok("error" in parseBBoxParam("39,-83,40.01,-82"), "more than 1° tall");
  assert.ok("error" in parseBBoxParam("39,-84.5,40,-83"), "more than 1° wide");
  assert.ok("error" in parseBBoxParam("89.5,0,91,1"), "latitude out of range");
  assert.ok("error" in parseBBoxParam("0,179.5,1,180.5"), "longitude out of range");
});

test("tiles snap outward to 0.25°", () => {
  const aligned = tilesForBBox([40.25, -83.0, 40.5, -82.75]);
  assert.deepEqual(aligned.map((t) => t.key), ["161:-332"]);
  assert.deepEqual(aligned[0].bbox, [40.25, -83, 40.5, -82.75]);

  const inside = tilesForBBox([40.3, -82.9, 40.31, -82.89]);
  assert.deepEqual(inside.map((t) => t.key), ["161:-332"]);

  const straddle = tilesForBBox([40.2, -83.1, 40.3, -82.9]);
  assert.deepEqual(straddle.map((t) => t.key).sort(), ["160:-333", "160:-332", "161:-333", "161:-332"].sort());

  // A full 1°×1° box off the grid covers 5×5 tiles, never more.
  assert.equal(tilesForBBox([39.9, -83.1, 40.9, -82.1]).length, 25);
  assert.equal(tilesForBBox([40, -83, 41, -82]).length, 16);
  for (const t of tilesForBBox([39.9, -83.1, 40.9, -82.1])) {
    assert.ok(t.bbox[0] <= 40.9 && t.bbox[2] >= 39.9 && t.bbox[1] <= -82.1 && t.bbox[3] >= -83.1);
  }
});

test("tile keys round-trip and reject the impossible", () => {
  assert.deepEqual(tileFromKey("161:-332")?.bbox, [40.25, -83, 40.5, -82.75]);
  assert.equal(tileFromKey("360:0"), null);
  assert.equal(tileFromKey("0:720"), null);
  assert.equal(tileFromKey("a:b"), null);
});

test("upstream URLs put the coordinates in each provider's order", () => {
  const tile = tileFromKey("161:-332");
  const here = new URL(hereUrl(tile, "k e y"));
  assert.equal(here.origin + here.pathname, "https://data.traffic.hereapi.com/v7/incidents");
  assert.equal(here.searchParams.get("in"), "bbox:-83,40.25,-82.75,40.5");
  assert.equal(here.searchParams.get("locationReferencing"), "shape");
  assert.equal(here.searchParams.get("apiKey"), "k e y");

  const tt = new URL(tomtomUrl(tile, "abc"));
  assert.equal(tt.origin + tt.pathname, "https://api.tomtom.com/traffic/services/5/incidentDetails");
  assert.equal(tt.searchParams.get("bbox"), "-83,40.25,-82.75,40.5");
  assert.equal(tt.searchParams.get("key"), "abc");
  assert.equal(tt.searchParams.get("language"), "en-US");
  assert.equal(tt.searchParams.get("timeValidityFilter"), "present");
  assert.match(tt.searchParams.get("fields"), /^\{incidents\{type,geometry\{type,coordinates\},properties\{id,iconCategory,/);
});

test("HERE types map to the proxy's types", () => {
  assert.equal(mapHereType("accident", false), "crash");
  assert.equal(mapHereType("roadClosure", false), "closure");
  assert.equal(mapHereType("laneRestriction", false), "lane_closure");
  assert.equal(mapHereType("construction", false), "work_zone");
  assert.equal(mapHereType("plannedEvent", undefined), "work_zone");
  assert.equal(mapHereType("congestion", false), "jam");
  assert.equal(mapHereType("roadHazard", false), "hazard");
  assert.equal(mapHereType("weather", false), "hazard");
  assert.equal(mapHereType("disabledVehicle", false), "hazard");
  assert.equal(mapHereType("police", false), "police");
  assert.equal(mapHereType("massTransit", false), "other");
  assert.equal(mapHereType(undefined, false), "other");
  assert.equal(mapHereType("construction", true), "closure");
});

test("HERE incidents normalise", () => {
  const out = normalizeHere(HERE_SAMPLE, NOW);
  assert.deepEqual(out.map((i) => i.id), ["here:1270784339381044836", "here:998"]);
  const crash = out[0];
  assert.equal(crash.type, "crash");
  assert.equal(crash.source, "here");
  assert.equal(crash.road, "I-70 E");
  assert.equal(crash.description, "Accident on I-70 Eastbound at 4th St. Right lane blocked.");
  assert.equal(crash.start, Date.UTC(2025, 9, 15, 11, 20) / 1000);
  assert.equal(crash.end, Date.UTC(2025, 9, 15, 13, 30) / 1000);
  assert.equal(crash.delay_s, null);
  // The shared point between links appears once.
  assert.deepEqual(crash.geom, [
    [39.96021, -83.00214],
    [39.96055, -82.99801],
    [39.96102, -82.99263],
  ]);
  assert.ok(crash.lat > 39.9602 && crash.lat < 39.9611 && crash.lon > -83.0022 && crash.lon < -82.9926);

  const closure = out[1];
  assert.equal(closure.type, "closure");
  assert.equal(closure.description, "Closed due to construction.");
  assert.equal(closure.road, null);
  assert.ok(Math.abs(closure.lat - 39.905) < 1e-9);

  assert.throws(() => normalizeHere({ error: "Unauthorized" }, NOW));
});

test("TomTom icon categories map to the proxy's types", () => {
  const expect = { 0: "other", 1: "crash", 2: "hazard", 3: "hazard", 4: "hazard", 5: "hazard", 6: "jam", 7: "lane_closure", 8: "closure", 9: "work_zone", 10: "hazard", 11: "hazard", 12: "other", 13: "other", 14: "hazard" };
  for (const [cat, type] of Object.entries(expect)) assert.equal(mapTomTomCategory(Number(cat)), type, `category ${cat}`);
  assert.equal(mapTomTomCategory("6"), "other");
});

test("TomTom incidents normalise", () => {
  const out = normalizeTomTom(TOMTOM_SAMPLE, NOW);
  assert.deepEqual(out.map((i) => i.id), ["tomtom:4f1a3c7e5b9d2e8f0a1b2c3d4e5f6a7b", "tomtom:point-1"]);
  const jam = out[0];
  assert.equal(jam.type, "jam");
  assert.equal(jam.source, "tomtom");
  assert.equal(jam.road, "I-70, I-71");
  assert.equal(jam.description, "Stationary traffic: Exit 100A (4th St) to Exit 101 (18th St)");
  assert.equal(jam.delay_s, 241);
  assert.equal(jam.end, null);
  // Coordinates come back [lon, lat] and leave as [lat, lon].
  assert.deepEqual(jam.geom[0], [39.9602, -83.0021]);
  assert.ok(Math.abs(jam.lat - 39.9604) < 1e-4 && Math.abs(jam.lon + 83.0001) < 1e-3);

  const point = out[1];
  assert.equal(point.type, "hazard");
  assert.equal(point.geom, null);
  assert.equal(point.lat, 39.97);
  assert.equal(point.lon, -83.01);
  assert.equal(point.road, null);
  assert.equal(point.description, "Broken down vehicle");
  assert.equal(point.delay_s, null);

  assert.throws(() => normalizeTomTom({ detailedError: { code: "InvalidKey" } }, NOW));
});

test("long geometries are capped and keep both ends", () => {
  const coordinates = [];
  for (let k = 0; k < 1000; k++) coordinates.push([-83 + k * 0.0001, 40]);
  const doc = { incidents: [{ type: "Feature", geometry: { type: "LineString", coordinates }, properties: { id: "long", iconCategory: 9 } }] };
  const [inc] = normalizeTomTom(doc, NOW);
  assert.equal(inc.geom.length, MAX_GEOM_POINTS);
  assert.deepEqual(inc.geom[0], [40, -83]);
  assert.deepEqual(inc.geom[MAX_GEOM_POINTS - 1], [40, -82.9001]);
});

test("merging tiles dedupes, drops ended and keeps only the requested box", () => {
  const a = { id: "here:1", type: "crash", lat: 40.1, lon: -83.1, geom: null, road: null, description: null, start: null, end: null, delay_s: null, source: "here" };
  const ended = { ...a, id: "here:2", end: NOW - 1 };
  const outside = { ...a, id: "here:3", lat: 41, lon: -83.1 };
  const crossing = { ...a, id: "tomtom:4", lat: 41, lon: -83.1, geom: [[41, -83.1], [40.2, -83.1]], source: "tomtom" };
  const merged = mergeForBBox([[a, ended, outside], [a, crossing]], [40, -83.5, 40.5, -83], NOW);
  assert.deepEqual(merged.map((i) => i.id), ["here:1", "tomtom:4"]);
});

test("provider status across tiles", () => {
  assert.equal(combineStatus(["ok", "ok"]), "ok");
  assert.equal(combineStatus(["ok", "quota"]), "quota");
  assert.equal(combineStatus(["quota", "error", "ok"]), "error");
  assert.equal(combineStatus([]), "ok");
});

test("daily quota counts up to the cap and rolls over at UTC midnight", () => {
  let counter = null;
  for (let i = 0; i < 3; i++) {
    const r = quotaTake(counter, NOW, 3);
    assert.equal(r.allowed, true);
    counter = r.counter;
  }
  assert.deepEqual(counter, { day: utcDay(NOW), count: 3 });
  const refused = quotaTake(counter, NOW + 60, 3);
  assert.equal(refused.allowed, false);
  assert.deepEqual(refused.counter, counter);

  const lastSecond = Date.UTC(2025, 9, 15, 23, 59, 59) / 1000;
  assert.equal(quotaTake(counter, lastSecond, 3).allowed, false);
  const midnight = Date.UTC(2025, 9, 16, 0, 0, 0) / 1000;
  const next = quotaTake(counter, midnight, 3);
  assert.equal(next.allowed, true);
  assert.deepEqual(next.counter, { day: utcDay(midnight), count: 1 });

  assert.equal(quotaTake(null, NOW, 0).allowed, false);
});

test("caps come from env or default", () => {
  assert.equal(parseCap(undefined, DEFAULT_DAILY_CAP.here), 1000);
  assert.equal(parseCap("", DEFAULT_DAILY_CAP.tomtom), 2300);
  assert.equal(parseCap("50", 1000), 50);
  assert.equal(parseCap("0", 1000), 0);
  assert.equal(parseCap("-3", 1000), 1000);
  assert.equal(parseCap("lots", 1000), 1000);
});

test("tile cache decisions", () => {
  assert.equal(decideTile(null, NOW), "fetch");
  assert.equal(decideTile({ goodAt: NOW - 10, errorAt: null }, NOW), "fresh");
  assert.equal(decideTile({ goodAt: NOW - TILE_FRESH_SEC, errorAt: null }, NOW), "fetch");
  assert.equal(decideTile({ goodAt: NOW - 600, errorAt: NOW - 10 }, NOW), "backoff");
  assert.equal(decideTile({ goodAt: null, errorAt: NOW - TILE_ERROR_BACKOFF_SEC }, NOW), "fetch");
  assert.equal(staleUsable({ goodAt: NOW - 600, errorAt: NOW }, NOW), true);
  assert.equal(staleUsable({ goodAt: NOW - TILE_STALE_MAX_SEC, errorAt: NOW }, NOW), false);
  assert.equal(staleUsable({ goodAt: null, errorAt: NOW }, NOW), false);
});

test("one request fetches a bounded number of tiles, empty ones first", () => {
  const box = [40.1, -83.1, 41.1, -82.1];
  const tiles = tilesForBBox(box);
  assert.equal(tiles.length, 25);
  const rows = new Map(tiles.map((t) => [t.key, null]));
  // Cold cache: a 1°×1° box still starts only the budgeted calls, nearest the centre first.
  const cold = pickTilesToFetch(tiles, rows, box, NOW, MAX_UPSTREAM_TILES_PER_REQUEST);
  assert.equal(cold.length, MAX_UPSTREAM_TILES_PER_REQUEST);
  assert.ok(MAX_UPSTREAM_TILES_PER_REQUEST * 2 <= 6, "both providers fit the 6 simultaneous connections");
  assert.equal(cold[0], "162:-331"); // 40.5..40.75 × -82.75..-82.5 holds the centre (40.6, -82.6)

  // Fresh tiles are never refetched; tiles with nothing to show go before stale ones.
  const fresh = tiles[0].key;
  const stale = tiles[1].key;
  const empty = tiles[24].key;
  const r2 = new Map(tiles.map((t) => [t.key, { goodAt: NOW - TILE_FRESH_SEC - 10, errorAt: null }]));
  r2.set(fresh, { goodAt: NOW - 10, errorAt: null });
  r2.set(stale, { goodAt: NOW - TILE_STALE_MAX_SEC + 60, errorAt: null });
  r2.set(empty, null);
  const picked = pickTilesToFetch(tiles, r2, box, NOW, 2);
  assert.equal(picked[0], empty);
  assert.equal(picked[1], stale, "then the oldest result");
  assert.ok(!pickTilesToFetch(tiles, r2, box, NOW, 25).includes(fresh));
  assert.deepEqual(pickTilesToFetch(tiles, r2, box, NOW, 0), []);
});

test("tile bodies stay under the storage limit", () => {
  const inc = (i) => ({
    id: `tomtom:${i}`, type: "jam", lat: 40, lon: -83, road: "I-70", description: "x".repeat(400),
    geom: Array.from({ length: 200 }, (_, k) => [40 + k * 1e-4, -83 - k * 1e-4]), start: null, end: null, delay_s: 60, source: "tomtom",
  });
  const many = Array.from({ length: 500 }, (_, i) => inc(i));
  const full = JSON.stringify(many);
  assert.ok(full.length > 2_000_000, "the worst case really is over the 2 MB row limit");
  const body = serializeTile(many);
  assert.ok(body.length <= 1_500_000);
  const back = JSON.parse(body);
  assert.ok(back.length > 0 && back.length < 500);
  assert.equal(back[0].id, "tomtom:0");
  const small = many.slice(0, 3);
  assert.equal(serializeTile(small), JSON.stringify(small));
});
