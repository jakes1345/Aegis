// Run with `npm test` (node --test). Node 22.18+ strips the TypeScript types itself.
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  bucketMedians,
  detectStoppage,
  ewma,
  findMergeTarget,
  haversineM,
  lineMidpoint,
  liveSegments,
  median,
  parseWzdx,
  readTextCapped,
  robustBBox,
  weekSlot,
} from "../src/traffic-logic.ts";

const NOW = 1_760_500_000;

test("median of odd, even and empty lists", () => {
  assert.equal(median([5, 1, 3]), 3);
  assert.equal(median([4, 1, 3, 2]), 2.5);
  assert.equal(median([7]), 7);
  assert.equal(median([]), null);
});

test("haversine matches a known distance", () => {
  // One degree of latitude is about 111.2 km.
  assert.ok(Math.abs(haversineM(40, -83, 41, -83) - 111_195) < 50);
  assert.equal(haversineM(40, -83, 40, -83), 0);
});

test("weekSlot is UTC with Monday = 0", () => {
  // 2025-10-13 is a Monday.
  assert.equal(weekSlot(Date.UTC(2025, 9, 13, 0, 30) / 1000), 0);
  assert.equal(weekSlot(Date.UTC(2025, 9, 13, 17, 59) / 1000), 17);
  assert.equal(weekSlot(Date.UTC(2025, 9, 19, 23, 0) / 1000), 6 * 24 + 23);
});

test("ewma starts at the first sample and then moves 20% toward each new one", () => {
  assert.equal(ewma(null, 50), 50);
  assert.equal(ewma(50, 100), 60);
});

test("live speed counts one sample per sender per segment, the latest", () => {
  const rows = [
    { seg: "1:1", sender: "a", kph: 10, t: NOW - 500 },
    { seg: "1:1", sender: "a", kph: 30, t: NOW - 100 },
    { seg: "1:1", sender: "a", kph: 31, t: NOW - 200 },
    { seg: "1:1", sender: "b", kph: 50, t: NOW - 60 },
    { seg: "1:1", sender: "c", kph: 70, t: NOW - 30 },
    { seg: "2:0", sender: "a", kph: 99, t: NOW - 700 },
  ];
  const live = liveSegments(rows, NOW);
  assert.deepEqual(live["1:1"], { kph: 50, n: 3, t: NOW - 30 });
  assert.equal(live["2:0"], undefined, "older than ten minutes");
});

test("bucket medians collapse each sender first", () => {
  const b = Math.floor(NOW / 900) * 900;
  const rows = [
    { seg: "1:1", sender: "a", kph: 10, t: b + 1 },
    { seg: "1:1", sender: "a", kph: 10, t: b + 2 },
    { seg: "1:1", sender: "a", kph: 10, t: b + 3 },
    { seg: "1:1", sender: "b", kph: 60, t: b + 4 },
    { seg: "1:1", sender: "c", kph: 50, t: b + 5 },
    { seg: "1:1", sender: "c", kph: 50, t: b + 900 },
  ];
  const out = bucketMedians(rows);
  assert.deepEqual(out, [
    { bucket: b, seg: "1:1", kph: 50 },
    { bucket: b + 900, seg: "1:1", kph: 50 },
  ]);
});

test("reports merge within 150 m and 30 minutes, same type only", () => {
  const base = { lat: 40, lon: -83, activeAt: NOW - 60, expiresAt: NOW + 600 };
  const near = { id: "near", type: "police", ...base };
  // About 0.0012° of latitude is 133 m; 0.0015° is 167 m.
  assert.equal(findMergeTarget([near], "police", 40.0012, -83, NOW)?.id, "near");
  assert.equal(findMergeTarget([near], "police", 40.0015, -83, NOW), null);
  assert.equal(findMergeTarget([near], "crash", 40.0001, -83, NOW), null);
  const stale = { ...near, id: "stale", activeAt: NOW - 31 * 60 };
  assert.equal(findMergeTarget([stale], "police", 40, -83, NOW), null);
  const expired = { ...near, id: "expired", expiresAt: NOW };
  assert.equal(findMergeTarget([expired], "police", 40, -83, NOW), null);
  const closer = { ...near, id: "closer", lat: 40.0005 };
  assert.equal(findMergeTarget([near, closer], "police", 40.0006, -83, NOW)?.id, "closer");
});

test("two senders stopping suddenly from speed is a crash", () => {
  const history = [
    { seg: "9:0", sender: "a", kph: 95, t: NOW - 90 },
    { seg: "7:1", sender: "b", kph: 88, t: NOW - 80 },
    { seg: "7:1", sender: "a", kph: 3, t: NOW - 30 },
    { seg: "7:1", sender: "b", kph: 0, t: NOW - 20 },
  ];
  assert.equal(detectStoppage("7:1", history, 100, NOW), "crash");
});

test("two senders stopped without a sudden drop is a jam", () => {
  const history = [
    { seg: "7:1", sender: "a", kph: 30, t: NOW - 100 },
    { seg: "7:1", sender: "a", kph: 5, t: NOW - 30 },
    { seg: "7:1", sender: "b", kph: 2, t: NOW - 20 },
  ];
  assert.equal(detectStoppage("7:1", history, null, NOW), "jam");
  // Only one of them was fast beforehand: still a jam.
  history.push({ seg: "7:1", sender: "b", kph: 90, t: NOW - 60 });
  assert.equal(detectStoppage("7:1", history, null, NOW), "jam");
});

test("no detection with one sender, slow roads, or old samples", () => {
  const one = [
    { seg: "7:1", sender: "a", kph: 2, t: NOW - 10 },
    { seg: "7:1", sender: "a", kph: 1, t: NOW - 5 },
  ];
  assert.equal(detectStoppage("7:1", one, 100, NOW), null);
  const two = [...one, { seg: "7:1", sender: "b", kph: 1, t: NOW - 5 }];
  assert.equal(detectStoppage("7:1", two, 30, NOW), null, "typical under 40 km/h");
  const old = [
    { seg: "7:1", sender: "a", kph: 2, t: NOW - 400 },
    { seg: "7:1", sender: "b", kph: 2, t: NOW - 10 },
  ];
  assert.equal(detectStoppage("7:1", old, 100, NOW), null);
  // A fast sample more than two minutes before the stop does not count.
  const late = [
    { seg: "7:1", sender: "a", kph: 90, t: NOW - 200 },
    { seg: "7:1", sender: "b", kph: 90, t: NOW - 200 },
    { seg: "7:1", sender: "a", kph: 2, t: NOW - 10 },
    { seg: "7:1", sender: "b", kph: 2, t: NOW - 10 },
  ];
  assert.equal(detectStoppage("7:1", late, 100, NOW), "jam");
});

test("senders that drove off again do not count as stopped", () => {
  // Two cars wait at a red light, then one of them pulls away onto the next segment.
  const light = [
    { seg: "7:1", sender: "a", kph: 70, t: NOW - 100 },
    { seg: "7:1", sender: "b", kph: 70, t: NOW - 100 },
    { seg: "7:1", sender: "a", kph: 2, t: NOW - 60 },
    { seg: "7:1", sender: "b", kph: 2, t: NOW - 60 },
    { seg: "8:1", sender: "a", kph: 45, t: NOW - 10 },
  ];
  assert.equal(detectStoppage("7:1", light, 100, NOW), null);
  light.push({ seg: "8:1", sender: "b", kph: 1, t: NOW - 5 });
  assert.equal(detectStoppage("7:1", light, 100, NOW), null, "b is still slow but a has gone");
  light.push({ seg: "7:1", sender: "c", kph: 0, t: NOW - 5 });
  assert.equal(detectStoppage("7:1", light, 100, NOW), "jam");
});

test("line midpoint is halfway along the length", () => {
  const mid = lineMidpoint([
    [-83, 40],
    [-83, 40.01],
    [-83, 40.03],
  ]);
  assert.ok(Math.abs(mid[1] - 40.015) < 1e-9);
  assert.equal(mid[0], -83);
});

test("robust bbox drops a lone outlier once there are enough points", () => {
  const pts = [];
  for (let i = 0; i < 300; i++) pts.push({ lat: 39 + (i % 30) * 0.1, lon: -84 + Math.floor(i / 30) * 0.1 });
  pts.push({ lat: -33, lon: 151 });
  const b = robustBBox(pts, 0);
  assert.ok(b[0] >= 39 && b[3] <= -83 + 0.0001, JSON.stringify(b));
  assert.deepEqual(robustBBox([{ lat: 40, lon: -83 }], 0.05), [39.95, -83.05, 40.05, -82.95]);
  assert.equal(robustBBox([]), null);
});

test("WZDx work zones become closures or hazards", () => {
  const doc = {
    type: "FeatureCollection",
    features: [
      {
        id: "wz-1",
        type: "Feature",
        geometry: { type: "LineString", coordinates: [[-83, 40], [-83, 40.02]] },
        properties: {
          core_details: { event_type: "work-zone", road_names: ["I-70"], description: "Bridge deck repair" },
          start_date: "2025-01-01T00:00:00Z",
          end_date: "2099-01-01T00:00:00Z",
          vehicle_impact: "all-lanes-closed",
        },
      },
      {
        id: "wz-2",
        type: "Feature",
        geometry: { type: "MultiPoint", coordinates: [[-82.5, 39.9], [-82.4, 39.9]] },
        properties: { core_details: { event_type: "work-zone" }, vehicle_impact: "some-lanes-closed" },
      },
      {
        id: "future",
        type: "Feature",
        geometry: { type: "Point", coordinates: [-83, 40] },
        properties: { core_details: { event_type: "work-zone" }, start_date: "2099-01-01T00:00:00Z" },
      },
      {
        id: "ended",
        type: "Feature",
        geometry: { type: "Point", coordinates: [-83, 40] },
        properties: { core_details: { event_type: "work-zone" }, end_date: "2020-01-01T00:00:00Z" },
      },
      {
        id: "detour",
        type: "Feature",
        geometry: { type: "Point", coordinates: [-83, 40] },
        properties: { core_details: { event_type: "detour" } },
      },
    ],
  };
  const events = parseWzdx("https://example.org/wzdx.geojson", doc, NOW);
  assert.equal(events.length, 2);
  const [a, b] = events;
  assert.equal(a.type, "closure");
  assert.ok(Math.abs(a.lat - 40.01) < 1e-9);
  assert.equal(a.description, "Work zone on I-70: Bridge deck repair");
  assert.equal(a.endsAt, Date.UTC(2099, 0, 1) / 1000);
  assert.ok(a.extId.endsWith(":wz-1"));
  assert.equal(b.type, "hazard");
  assert.equal(b.lat, 39.9);
  assert.equal(b.lon, -82.5);
  assert.equal(b.endsAt, null);
  assert.equal(b.description, null);
  assert.deepEqual(parseWzdx("x", { nope: true }, NOW), []);
});

function streamOf(chunks, onCancel) {
  let i = 0;
  return new ReadableStream({
    pull(controller) {
      if (i < chunks.length) controller.enqueue(chunks[i++]);
      else controller.close();
    },
    cancel() {
      onCancel?.();
    },
  });
}

test("readTextCapped stops reading a body that grows past the cap", async () => {
  let cancelled = false;
  let pulled = 0;
  const chunk = new Uint8Array(400).fill(0x61);
  const body = new ReadableStream({
    pull(controller) {
      pulled++;
      controller.enqueue(chunk);
    },
    cancel() {
      cancelled = true;
    },
  });
  assert.equal(await readTextCapped(body, null, 1000), null);
  assert.ok(cancelled);
  assert.ok(pulled <= 4);
});

test("readTextCapped refuses a declared content-length over the cap without reading", async () => {
  let cancelled = false;
  const body = streamOf([new TextEncoder().encode("{}")], () => {
    cancelled = true;
  });
  assert.equal(await readTextCapped(body, "5000", 1000), null);
  assert.ok(cancelled);
});

test("readTextCapped returns the text of a body under the cap, across split UTF-8", async () => {
  const bytes = new TextEncoder().encode('{"name":"Café road"}');
  const cut = bytes.indexOf(0xc3) + 1; // split inside the two-byte é
  const body = streamOf([bytes.slice(0, cut), bytes.slice(cut)]);
  assert.equal(await readTextCapped(body, String(bytes.length), 1000), '{"name":"Café road"}');
  assert.equal(await readTextCapped(null, null, 1000), "");
});
