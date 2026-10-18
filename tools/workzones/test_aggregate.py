import gzip
import json
import math
import os
import pathlib

import pytest

import aggregate as agg

HERE = pathlib.Path(__file__).resolve().parent
FIX = HERE / "fixtures"
REGIONS = HERE.parent / "graph-builder" / "us-regions.txt"
BBOXES = HERE / "state_bboxes.json"
NOW = agg.parse_time("2026-10-18T03:00:00Z")


@pytest.fixture(autouse=True)
def no_retry_delay(monkeypatch):
    monkeypatch.setattr(agg, "RETRY_DELAY_S", 0)


def load(name):
    return json.loads((FIX / name).read_text(encoding="utf-8"))


def by_id(items):
    return {i["id"]: i for i in items}


# --- parsing -----------------------------------------------------------------------------------


def test_v3_parsing_impact_and_mph():
    items = by_id(agg.parse_feed(load("wzdx_v3_1.geojson"), "sc", "Sample City DOT", NOW))
    closed = items["sc:v3-closed-1"]
    assert closed["type"] == "closure"
    assert closed["impact"] == "all_closed"
    assert closed["road"] == "Main St / SR 12"
    assert closed["direction"] == "southbound"
    assert closed["speed_kph"] == pytest.approx(25 * 1.609344, abs=0.05)
    assert closed["start"] == agg.parse_time("2026-10-01T11:00:00Z")
    assert closed["end"] == agg.parse_time("2026-11-18T21:00:00Z")
    assert closed["geom"][0] == [44.9778, -93.265]
    assert closed["source"] == "Sample City DOT"

    # No id: falls back to a stable content hash; road_name (singular) is read; MultiPoint kept.
    hashed = [i for k, i in items.items() if k.startswith("sc:") and i["road"] == "Oak Ave"]
    assert len(hashed) == 1
    assert len(hashed[0]["id"]) == len("sc:") + 16
    assert hashed[0]["impact"] == "shoulder"
    assert hashed[0]["type"] == "work_zone"
    assert hashed[0]["geom"] == [[44.97, -93.27], [44.97, -93.26]]
    again = agg.parse_feed(load("wzdx_v3_1.geojson"), "sc", "Sample City DOT", NOW)
    assert hashed[0]["id"] in by_id(again)

    assert "sc:v3-expired" not in items
    assert "sc:v3-no-geometry" not in items
    assert len(items) == 2


def test_v4_2_parsing():
    items = by_id(agg.parse_feed(load("wzdx_v4_2.geojson"), "ss", "Sample State DOT", NOW))
    lane = items["ss:757-1"]
    assert lane["type"] == "lane_closure"
    assert lane["impact"] == "some_closed"
    assert lane["speed_kph"] == 88.5
    assert lane["road"] == "I-40"
    assert lane["start"] == agg.parse_time("2026-04-20T11:00:00Z")
    assert lane["description"].startswith("Construction on I-40 East")

    # MultiLineString parts become separate items so no bogus segment joins them.
    assert items["ss:detour-9#0"]["type"] == "detour"
    assert items["ss:detour-9#1"]["geom"] == [[35.82, -78.63], [35.83, -78.63]]

    restr = items["ss:restr-3"]
    assert restr["type"] == "restriction"
    assert restr["impact"] == "open"
    assert restr["end"] is None
    assert restr["geom"] == [[35.91, -78.9]]

    zero = items["ss:zero-speed"]
    assert zero["speed_kph"] is None
    assert zero["impact"] == "unknown"
    assert zero["type"] == "work_zone"

    assert "ss:future-1" not in items


def test_cwz_parsing_lanes_infer_impact_and_drop_z():
    items = agg.parse_feed(load("cwz_1_0.geojson"), "pbs", "PurposeBuilt", NOW)
    assert len(items) == 1
    it = items[0]
    assert it["id"] == "pbs:747474d6-3243-4083-96ad-465b4d643859"
    assert it["impact"] == "some_closed"  # vehicle_impact unknown, lanes say one of three closed
    assert it["type"] == "lane_closure"
    assert it["speed_kph"] == 56
    assert it["road"] == "E Sixth St"
    assert all(len(p) == 2 for p in it["geom"])
    assert set(it) == {"id", "type", "impact", "speed_kph", "road", "direction", "description",
                       "start", "end", "geom", "source"}


@pytest.mark.parametrize("vi,lanes,expected", [
    ("all-lanes-closed", None, "all_closed"),
    ("some-lanes-closed", None, "some_closed"),
    ("alternating-one-way", None, "some_closed"),
    ("all-lanes-open", None, "open"),
    ("unknown", None, "unknown"),
    (None, None, "unknown"),
    ("unknown", [{"type": "general", "status": "closed"}, {"type": "general", "status": "closed"},
                 {"type": "shoulder", "status": "open"}], "all_closed"),
    ("some-lanes-closed", [{"type": "general", "status": "open"},
                           {"type": "shoulder", "status": "closed"}], "shoulder"),
    ("all-lanes-open", [{"type": "general", "status": "open"},
                        {"type": "shoulder", "status": "closed"}], "shoulder"),
    ("unknown", [{"type": "general", "status": "merge-left"},
                 {"type": "general", "status": "open"}], "some_closed"),
    ("unknown", [{"type": "sidewalk", "status": "closed"}], "unknown"),
])
def test_impact_mapping(vi, lanes, expected):
    assert agg.map_impact(vi, lanes) == expected


@pytest.mark.parametrize("event_type,impact,expected", [
    ("work-zone", "all_closed", "closure"),
    ("work-zone", "some_closed", "lane_closure"),
    ("work-zone", "shoulder", "work_zone"),
    ("work-zone", "unknown", "work_zone"),
    ("detour", "all_closed", "detour"),
    ("restriction", "some_closed", "restriction"),
    (None, "open", "work_zone"),
])
def test_type_mapping(event_type, impact, expected):
    assert agg.map_type(event_type, impact) == expected


def test_direction_placeholders_become_null():
    assert agg.clean_direction("Northbound") == "northbound"
    assert agg.clean_direction("unknown") is None
    assert agg.clean_direction("undefined") is None
    assert agg.clean_direction(None) is None


def test_mph_to_kph():
    v3 = agg.feature_fields({"reduced_speed_limit": 45})
    assert v3["speed_kph"] == pytest.approx(72.4, abs=0.05)
    v4 = agg.feature_fields({"core_details": {}, "reduced_speed_limit_kph": 72})
    assert v4["speed_kph"] == 72


def test_parse_time_variants():
    assert agg.parse_time("2026-10-18T00:00:00Z") == 1792281600
    assert agg.parse_time("2026-10-17T18:00:00-06:00") == 1792281600
    assert agg.parse_time("2026-10-18T00:00:00.1234567+00:00") == 1792281600
    assert agg.parse_time("2026-10-18T00:00:00") == 1792281600
    assert agg.parse_time(1792281600000) == 1792281600
    assert agg.parse_time("0001-01-01T00:00:00+00:00") is None
    assert agg.parse_time("not a date") is None
    assert agg.parse_time(None) is None


# --- expiry ------------------------------------------------------------------------------------


def _feature(start, end):
    return {"type": "FeatureCollection", "features": [{
        "id": "x", "type": "Feature",
        "properties": {"core_details": {"event_type": "work-zone"}, "start_date": start, "end_date": end},
        "geometry": {"type": "Point", "coordinates": [-90.0, 40.0]},
    }]}


def test_expiry_filter():
    now = agg.parse_time("2026-10-18T12:00:00Z")
    keep = lambda s, e: len(agg.parse_feed(_feature(s, e), "f", "F", now)) == 1  # noqa: E731
    assert keep("2026-10-01T00:00:00Z", "2026-10-19T00:00:00Z")
    assert not keep("2026-10-01T00:00:00Z", "2026-10-18T11:59:59Z")
    assert keep("2026-10-19T11:00:00Z", "2026-10-30T00:00:00Z")  # starts within 24 h
    assert not keep("2026-10-19T12:00:01Z", "2026-10-30T00:00:00Z")
    assert keep(None, None)


# --- key substitution --------------------------------------------------------------------------


@pytest.mark.parametrize("url,expected", [
    ("https://data.cotrip.org/api/v1/wzdx?apiKey=", "https://data.cotrip.org/api/v1/wzdx?apiKey=a%2Fb%20c"),
    ("https://ex.org/feed", "https://ex.org/feed?api_key=a%2Fb%20c"),
    ("https://ex.org/feed?limit=200&_format=json", "https://ex.org/feed?limit=200&_format=json&api_key=a%2Fb%20c"),
    ("https://ex.org/feed?api_key=INSERT-API-KEY-HERE", "https://ex.org/feed?api_key=a%2Fb%20c"),
    ("https://ex.org/feed?api_key=%3ckey%3e", "https://ex.org/feed?api_key=a%2Fb%20c"),
    ("https://ex.org/wzdx/v4.2?api_key=[Your-API-Key-Here]", "https://ex.org/wzdx/v4.2?api_key=a%2Fb%20c"),
    ("https://ex.org/cwz?api_key=YOUR-CWZ-API-KEY", "https://ex.org/cwz?api_key=a%2Fb%20c"),
])
def test_key_substitution(url, expected):
    assert agg.apply_key(url, "a/b c") == expected


def test_plan_feeds_respects_active_and_keys():
    registry = [
        {"feedname": "open", "state": "utah", "issuingorganization": "UDOT", "active": True,
         "needapikey": False, "url": {"url": "https://ex.org/open"}},
        {"feedname": "keyed", "state": "colorado", "active": True, "needapikey": True,
         "url": {"url": "https://ex.org/k?apiKey="}},
        {"feedname": "nokey", "state": "ohio", "active": "true", "needapikey": "true",
         "url": {"url": "https://ex.org/n"}},
        {"feedname": "off", "state": "iowa", "active": False, "needapikey": False,
         "url": {"url": "https://ex.org/off"}},
    ]
    fetch, skipped = agg.plan_feeds(registry, {"keyed": "K1"})
    assert [(f["name"], f["url"], f["source"]) for f in fetch] == [
        ("open", "https://ex.org/open", "UDOT"),
        ("keyed", "https://ex.org/k?apiKey=K1", "keyed"),
    ]
    assert [(s["name"], s["ok"]) for s in skipped] == [("nokey", False)]


def test_parse_keys():
    assert agg.parse_keys(None) == {}
    assert agg.parse_keys("") == {}
    assert agg.parse_keys('{"cdot": "abc"}') == {"cdot": "abc"}
    with pytest.raises(SystemExit):
        agg.parse_keys("not json")
    with pytest.raises(SystemExit):
        agg.parse_keys('["a"]')


# --- simplification ----------------------------------------------------------------------------


def test_simplify_drops_collinear_and_keeps_corners():
    line = [(40.0, -90.0 + i * 0.0001) for i in range(101)]  # ~850 m straight east
    line += [(40.0 + i * 0.0001, -89.99) for i in range(1, 101)]  # then north
    out = agg.simplify(line)
    assert out == [[40.0, -90.0], [40.0, -89.99], [40.01, -89.99]]


def test_simplify_tolerance_is_10_m():
    k = 1 / 111195.0  # degrees latitude per metre
    bump5 = [(40.0, -90.0), (40.0 + 5 * k, -89.995), (40.0, -89.99)]
    bump20 = [(40.0, -90.0), (40.0 + 20 * k, -89.995), (40.0, -89.99)]
    assert len(agg.simplify(bump5)) == 2
    assert len(agg.simplify(bump20)) == 3


def test_simplify_caps_points():
    # A zigzag with 60 m teeth survives a 10 m tolerance, so the cap has to kick in.
    pts = [(40.0 + (0.0006 if i % 2 else 0.0), -90.0 + i * 0.0005) for i in range(1000)]
    out = agg.simplify(pts)
    assert 2 <= len(out) <= 200
    assert out[0] == [40.0, -90.0]
    assert out[-1] == [round(pts[-1][0], 5), round(pts[-1][1], 5)]


# --- state split -------------------------------------------------------------------------------


def test_bboxes_cover_every_region():
    slugs = agg.region_slugs(str(REGIONS))
    boxes = agg.load_bboxes(str(BBOXES))
    assert len(slugs) == 52
    assert set(slugs) == set(boxes)
    for slug, (min_lat, min_lon, max_lat, max_lon) in boxes.items():
        assert -90 <= min_lat < max_lat <= 90, slug
        assert -180 <= min_lon < max_lon <= 180, slug
    assert boxes["north-america-us-alaska"][1] == -180.0
    assert boxes["north-america-us-alaska"][3] == -129.9


@pytest.mark.parametrize("lat,lon,slug", [
    (40.7128, -74.0060, "north-america-us-new-york"),
    (34.0522, -118.2437, "north-america-us-california"),
    (61.2181, -149.9003, "north-america-us-alaska"),
    (21.3069, -157.8583, "north-america-us-hawaii"),
    (18.4655, -66.1057, "north-america-us-puerto-rico"),
    (25.7617, -80.1918, "north-america-us-florida"),
    (47.6062, -122.3321, "north-america-us-washington"),
    (38.8977, -77.0365, "north-america-us-district-of-columbia"),
])
def test_known_cities_land_in_their_state(lat, lon, slug):
    b = agg.load_bboxes(str(BBOXES))[slug]
    assert b[0] <= lat <= b[2] and b[1] <= lon <= b[3]


def test_split_by_state_bbox_intersection():
    boxes = agg.load_bboxes(str(BBOXES))
    items = [
        # Crosses the Missouri River at Kansas City: both Kansas and Missouri.
        {"id": "a", "geom": [[39.10, -94.65], [39.10, -94.55]]},
        {"id": "b", "geom": [[44.98, -93.27]]},  # Minneapolis
        {"id": "c", "geom": [[46.81, -71.21]]},  # Quebec City: no US state
        {"id": "d", "geom": [[52.0, 177.0]]},  # Aleutians east of 180: excluded
    ]
    out = agg.split_by_state(items, boxes)
    where = {i: sorted(s for s, its in out.items() if any(x["id"] == i for x in its)) for i in "abcd"}
    assert "north-america-us-kansas" in where["a"] and "north-america-us-missouri" in where["a"]
    assert "north-america-us-minnesota" in where["b"]
    assert where["c"] == []
    assert where["d"] == []
    assert set(out) == set(boxes)


# --- end to end --------------------------------------------------------------------------------


def test_run_end_to_end(tmp_path):
    registry = [
        {"feedname": "sc", "state": "minnesota", "issuingorganization": "Sample City DOT",
         "active": True, "needapikey": False, "version": "3.1",
         "url": {"url": (FIX / "wzdx_v3_1.geojson").as_uri()}},
        {"feedname": "ss", "state": "north carolina", "issuingorganization": "Sample State DOT",
         "active": True, "needapikey": False, "version": "4.2",
         "url": {"url": (FIX / "wzdx_v4_2.geojson").as_uri()}},
        {"feedname": "cwz", "state": "texas", "issuingorganization": "Sample Contractor",
         "active": True, "needapikey": True, "version": "CWZ 1.0",
         "url": {"url": (tmp_path / "cwz.geojson").as_uri() + "?k="}},
        {"feedname": "broken", "state": "ohio", "issuingorganization": "Nobody",
         "active": True, "needapikey": False, "url": {"url": (tmp_path / "missing.json").as_uri()}},
        {"feedname": "locked", "state": "ohio", "active": True, "needapikey": True,
         "url": {"url": "https://ex.org/locked?api_key="}},
    ]
    reg_path = tmp_path / "registry.json"
    reg_path.write_text(json.dumps(registry))
    out = tmp_path / "dist"

    # urllib opens a file:// URL's query as part of the path, so the keyed feed only loads if
    # the key was substituted exactly where expected.
    (tmp_path / "cwz.geojson?k=secret").write_bytes((FIX / "cwz_1_0.geojson").read_bytes())
    res = agg.run(str(reg_path), str(out), str(REGIONS), str(BBOXES), {"cwz": "secret"}, now=NOW)

    files = sorted(os.listdir(out))
    assert "workzones-index.json" in files
    assert len([f for f in files if f.startswith("workzones-north-america-")]) == 52
    assert "workzones-feeds.json.gz" in files
    index = json.loads((out / "workzones-index.json").read_text())
    assert index["generated"] == NOW
    feeds = {f["name"]: f for f in index["feeds"]}
    assert set(feeds) == {"sc", "ss", "cwz", "broken", "locked"}
    assert feeds["sc"] == {"name": "sc", "state": "minnesota", "items": 2, "ok": True, "error": None}
    assert feeds["ss"]["items"] == 5 and feeds["ss"]["ok"]
    assert feeds["cwz"]["items"] == 1 and feeds["cwz"]["ok"]
    assert not feeds["broken"]["ok"] and feeds["broken"]["error"]
    assert not feeds["locked"]["ok"] and feeds["locked"]["error"] == "api key not configured"
    assert "secret" not in (out / "workzones-index.json").read_text()

    def state(slug):
        with gzip.open(out / f"workzones-{slug}.json.gz", "rt", encoding="utf-8") as f:
            return json.load(f)

    mn = state("north-america-us-minnesota")
    assert mn["version"] == 1 and mn["generated"] == NOW and mn["slug"] == "north-america-us-minnesota"
    assert {i["id"] for i in mn["items"]} == {i["id"] for i in mn["items"] if i["id"].startswith("sc:")}
    assert len(mn["items"]) == 2
    nc = state("north-america-us-north-carolina")
    assert {i["id"] for i in nc["items"]} == {"ss:757-1", "ss:detour-9#0", "ss:detour-9#1", "ss:restr-3"}
    tx = state("north-america-us-texas")
    assert [i["id"] for i in tx["items"]] == ["cwz:747474d6-3243-4083-96ad-465b4d643859"]
    wy = state("north-america-us-wyoming")
    assert wy["items"] == []
    assert res["per_state"]["north-america-us-texas"] == 1


def test_main_fails_when_no_feed_works(tmp_path, monkeypatch):
    reg = tmp_path / "registry.json"
    reg.write_text(json.dumps([{"feedname": "x", "active": True, "needapikey": False,
                                "url": {"url": (tmp_path / "nope.json").as_uri()}}]))
    monkeypatch.delenv("WZDX_KEYS", raising=False)
    monkeypatch.setattr(agg, "RETRIES", 0)
    rc = agg.main(["--registry", str(reg), "--out", str(tmp_path / "dist"), "--previous", ""])
    assert rc == 1


def test_gzip_output_is_deterministic(tmp_path):
    a, b = tmp_path / "a.json.gz", tmp_path / "b.json.gz"
    agg.write_gz_json(str(a), {"x": 1})
    agg.write_gz_json(str(b), {"x": 1})
    assert a.read_bytes() == b.read_bytes()


def test_douglas_peucker_handles_long_lines_without_recursion():
    pts = [(40.0 + 0.001 * math.sin(i / 10.0), -90.0 + i * 0.0001) for i in range(20000)]
    out = agg.simplify(pts)
    assert len(out) <= 200


# --- key redaction -----------------------------------------------------------------------------


def test_feed_errors_never_contain_the_api_key(monkeypatch):
    key = "s3cr/et key"
    fetch, _ = agg.plan_feeds([{"feedname": "k", "state": "ohio", "active": True, "needapikey": True,
                                "url": {"url": "ex.org/feed?api_key="}}], {"k": key})
    assert fetch[0]["key"] == key
    monkeypatch.setattr(agg, "RETRIES", 0)
    # A registry URL without a scheme makes urllib quote the whole URL, key included, in its
    # ValueError ("unknown url type: 'ex.org/feed?api_key=s3cr%2Fet%20key'").
    status, items = agg.process_feed(fetch[0], NOW)
    assert not status["ok"] and items == []
    assert "unknown url type" in status["error"]
    enc = "s3cr%2Fet%20key"
    assert key not in status["error"] and enc not in status["error"]
    assert "<redacted>" in status["error"]

    # Raw form too, and a key straddling the truncation point is never partly left behind.
    msg = agg.describe_error(ValueError("x" * 183 + key), key)  # key starts 5 chars before the cut
    assert len(msg) == 200 and "s3cr" not in msg and msg.endswith("<reda")
    assert agg.describe_error(ValueError(f"bad {enc} and {key}"), key) == \
        "ValueError: bad <redacted> and <redacted>"


# --- carry-forward -----------------------------------------------------------------------------


def _carry_registry(tmp_path):
    registry = [
        {"feedname": "sc", "state": "minnesota", "issuingorganization": "Sample City DOT",
         "active": True, "needapikey": False, "url": {"url": (FIX / "wzdx_v3_1.geojson").as_uri()}},
        {"feedname": "down", "state": "north carolina", "issuingorganization": "Down DOT",
         "active": True, "needapikey": False, "url": {"url": (tmp_path / "missing.json").as_uri()}},
    ]
    reg = tmp_path / "registry.json"
    reg.write_text(json.dumps(registry))
    return reg


def _prev_item(ident, end):
    return {"id": f"down:{ident}", "type": "closure", "impact": "all_closed", "speed_kph": None,
            "road": "I-40", "direction": None, "description": None, "start": None, "end": end,
            "geom": [[35.8, -78.6], [35.81, -78.6]], "source": "Down DOT"}


def _write_prev(path, generated):
    agg.write_gz_json(str(path), {"version": 1, "generated": generated, "feeds": {
        "down": {"generated": generated, "items": [
            _prev_item("live", NOW + 3600), _prev_item("open-ended", None), _prev_item("expired", NOW - 1)]},
    }})


def _state(out, slug):
    with gzip.open(out / f"workzones-{slug}.json.gz", "rt", encoding="utf-8") as f:
        return json.load(f)


def test_failed_feed_carries_recent_previous_items(tmp_path, monkeypatch):
    monkeypatch.setattr(agg, "RETRIES", 0)
    prev = tmp_path / "prev.json.gz"
    generated = NOW - 5 * 3600
    _write_prev(prev, generated)
    out = tmp_path / "dist"
    agg.run(str(_carry_registry(tmp_path)), str(out), str(REGIONS), str(BBOXES), {}, now=NOW,
            previous_src=str(prev))

    nc = _state(out, "north-america-us-north-carolina")
    assert sorted(i["id"] for i in nc["items"]) == ["down:live", "down:open-ended"]
    feeds = {f["name"]: f for f in json.loads((out / "workzones-index.json").read_text())["feeds"]}
    assert feeds["down"]["ok"] is False and feeds["down"]["error"]
    assert feeds["down"]["carried_from"] == generated and feeds["down"]["items"] == 2
    assert "carried_from" not in feeds["sc"]

    # The new feeds file keeps the carried feed's original fetch time, so the 6 h limit holds
    # across consecutive failures; fresh feeds are stamped with this run.
    with gzip.open(out / "workzones-feeds.json.gz", "rt", encoding="utf-8") as f:
        written = json.load(f)
    assert written["version"] == 1 and written["generated"] == NOW
    assert written["feeds"]["down"]["generated"] == generated
    assert len(written["feeds"]["down"]["items"]) == 2
    assert written["feeds"]["sc"]["generated"] == NOW and len(written["feeds"]["sc"]["items"]) == 2


def test_stale_previous_items_are_not_carried(tmp_path, monkeypatch):
    monkeypatch.setattr(agg, "RETRIES", 0)
    prev = tmp_path / "prev.json.gz"
    _write_prev(prev, NOW - 6 * 3600 - 1)
    out = tmp_path / "dist"
    agg.run(str(_carry_registry(tmp_path)), str(out), str(REGIONS), str(BBOXES), {}, now=NOW,
            previous_src=str(prev))
    assert _state(out, "north-america-us-north-carolina")["items"] == []
    feeds = {f["name"]: f for f in json.loads((out / "workzones-index.json").read_text())["feeds"]}
    assert "carried_from" not in feeds["down"] and feeds["down"]["items"] == 0
    with gzip.open(out / "workzones-feeds.json.gz", "rt", encoding="utf-8") as f:
        assert set(json.load(f)["feeds"]) == {"sc"}


@pytest.mark.parametrize("prev_content", [None, b"not gzip or json", b"\x1f\x8bcorrupt"])
def test_previous_feeds_unavailable_does_not_fail_the_run(tmp_path, monkeypatch, prev_content):
    monkeypatch.setattr(agg, "RETRIES", 0)
    monkeypatch.delenv("WZDX_KEYS", raising=False)
    prev = tmp_path / "prev.json.gz"
    if prev_content is not None:
        prev.write_bytes(prev_content)
    out = tmp_path / "dist"
    rc = agg.main(["--registry", str(_carry_registry(tmp_path)), "--out", str(out),
                   "--previous", str(prev)])
    assert rc == 0
    assert _state(out, "north-america-us-north-carolina")["items"] == []
    assert len(_state(out, "north-america-us-minnesota")["items"]) == 2


def test_previous_feeds_download_failure_is_tolerated(monkeypatch):
    def boom(*a, **k):
        raise agg.urllib.error.URLError(OSError("unreachable"))
    monkeypatch.setattr(agg, "fetch_bytes", boom)
    assert agg.load_previous_feeds(agg.PREVIOUS_FEEDS_URL) == {}
