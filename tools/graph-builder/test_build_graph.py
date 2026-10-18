import math
import os
import struct
import re
import subprocess
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_graph  # noqa: E402

SCRIPT = Path(__file__).resolve().parent / "build_graph.py"

# Node id -> (lat, lon, tags)
NODES = {
    1: (40.000, -83.000, {}),
    2: (40.000, -82.999, {}),
    3: (40.000, -82.998, {"highway": "traffic_signals"}),
    4: (40.001, -82.998, {}),
    5: (40.001, -83.000, {}),
    6: (39.999, -82.997, {"highway": "stop"}),
    7: (39.999, -82.998, {}),
    8: (39.998, -82.997, {}),
    20: (41.000, -84.000, {}),
    21: (41.000, -84.001, {}),
    30: (40.0015, -82.998, {}),
    31: (40.0005, -82.999, {}),
}
# Way id -> (node refs, tags)
WAYS = {
    100: ([1, 2, 3], {"highway": "residential", "name": "Elm Street"}),
    200: ([3, 4, 5], {"highway": "primary", "oneway": "yes", "maxspeed": "50", "name": "Main Street"}),
    300: ([5, 1], {"highway": "motorway", "toll": "yes", "maxspeed": "65 mph", "name": "Turnpike"}),
    400: ([3, 6, 7, 3], {"highway": "tertiary", "junction": "roundabout"}),
    450: ([6, 8], {"highway": "residential", "name": "Spur Road", "surface": "gravel"}),
    500: ([20, 21], {"highway": "residential", "name": "Island Road"}),
    600: ([8, 5], {"highway": "secondary", "oneway": "-1", "name": "Back Lane"}),
    700: ([4, 30], {"highway": "service", "service": "driveway"}),
    800: ([2, 31], {"highway": "residential", "access": "private"}),
}


def write_osm(path: Path) -> None:
    lines = ['<?xml version="1.0" encoding="UTF-8"?>', '<osm version="0.6" generator="test">']
    for nid, (lat, lon, tags) in NODES.items():
        lines.append(f'  <node id="{nid}" version="1" lat="{lat:.7f}" lon="{lon:.7f}">')
        lines += [f'    <tag k="{k}" v="{v}"/>' for k, v in tags.items()]
        lines.append("  </node>")
    for wid, (refs, tags) in WAYS.items():
        lines.append(f'  <way id="{wid}" version="1">')
        lines += [f'    <nd ref="{r}"/>' for r in refs]
        lines += [f'    <tag k="{k}" v="{v}"/>' for k, v in tags.items()]
        lines.append("  </way>")
    lines.append("</osm>")
    path.write_text("\n".join(lines), encoding="utf-8")


def parse_agr(data: bytes) -> dict:
    magic, version, nn, ne, ns, nnames = struct.unpack_from("<4sIIIII", data, 0)
    bbox = struct.unpack_from("<iiii", data, 24)
    built_at, = struct.unpack_from("<Q", data, 40)
    region = data[48:112].rstrip(b"\0").decode("ascii")
    off = 128
    nodes = [struct.unpack_from("<ii", data, off + 8 * i) for i in range(nn)]
    off += 8 * nn
    first = list(struct.unpack_from(f"<{nn + 1}I", data, off))
    off += 4 * (nn + 1)
    edges = []
    for i in range(ne):
        to, length, way, name, sstart, scount, speed, rc, flags, r0, r1 = struct.unpack_from(
            "<IIQIIHBBBBH", data, off + 32 * i)
        edges.append(dict(to=to, length_dm=length, way=way, name=name, shape_start=sstart,
                          shape_count=scount, maxspeed=speed, road_class=rc, flags=flags, r0=r0, r1=r1))
    off += 32 * ne
    shape = [struct.unpack_from("<ii", data, off + 8 * i) for i in range(ns)]
    off += 8 * ns
    names = []
    for _ in range(nnames):
        ln, = struct.unpack_from("<H", data, off)
        names.append(data[off + 2: off + 2 + ln].decode("utf-8"))
        off += 2 + ln
    assert off == len(data), "trailing bytes after names section"
    for e_idx, e in enumerate(edges):
        e["from"] = next(n for n in range(nn) if first[n] <= e_idx < first[n + 1])
    return dict(magic=magic, version=version, nodes=nodes, first=first, edges=edges, shape=shape,
                names=names, bbox=bbox, built_at=built_at, region=region)


def e7(deg: float) -> int:
    return int(round(deg * 1e7))


@pytest.fixture(scope="module")
def graph(tmp_path_factory):
    d = tmp_path_factory.mktemp("agr")
    osm = d / "test.osm"
    out = d / "test.agr"
    write_osm(osm)
    res = subprocess.run([sys.executable, str(SCRIPT), str(osm), str(out), "--region", "test-region"],
                         capture_output=True, text=True,
                         env={**os.environ, "SOURCE_DATE_EPOCH": "1760000000"})
    assert res.returncode == 0, res.stderr
    assert "nodes=5 edges=9" in res.stdout
    g = parse_agr(out.read_bytes())
    # Map OSM node id -> graph node index by coordinates.
    by_coord = {(e7(lat), e7(lon)): nid for nid, (lat, lon, _) in NODES.items()}
    g["osm_of"] = [by_coord[n] for n in g["nodes"]]
    g["idx_of"] = {osm_id: i for i, osm_id in enumerate(g["osm_of"])}
    return g


def edge(g, frm, to, way):
    matches = [e for e in g["edges"]
               if g["osm_of"][e["from"]] == frm and g["osm_of"][e["to"]] == to and e["way"] == way]
    assert len(matches) == 1, f"expected one edge {frm}->{to} on way {way}, got {matches}"
    return matches[0]


def name_of(g, e):
    return None if e["name"] == 0xFFFFFFFF else g["names"][e["name"]]


def shape_osm_ids(g, e):
    pts = g["shape"][e["shape_start"]: e["shape_start"] + e["shape_count"]]
    by_coord = {(e7(lat), e7(lon)): nid for nid, (lat, lon, _) in NODES.items()}
    return [by_coord[p] for p in pts]


def test_header(graph):
    assert graph["magic"] == b"AGR1"
    assert graph["version"] == 2
    assert graph["region"] == "test-region"
    assert graph["built_at"] == 1760000000
    assert graph["bbox"] == (e7(39.998), e7(-83.000), e7(40.001), e7(-82.997))


def test_counts_and_island_dropped(graph):
    assert sorted(graph["osm_of"]) == [1, 3, 5, 6, 8]
    assert len(graph["edges"]) == 9
    assert len(graph["shape"]) == 4
    assert sorted(graph["names"]) == ["Back Lane", "Elm Street", "Main Street", "Spur Road", "Turnpike"]
    assert all(e["way"] not in (500, 700, 800) for e in graph["edges"])


def test_csr(graph):
    first = graph["first"]
    assert first[0] == 0 and first[-1] == len(graph["edges"])
    assert all(first[i] <= first[i + 1] for i in range(len(first) - 1))
    out_degree = {graph["osm_of"][n]: first[n + 1] - first[n] for n in range(len(graph["nodes"]))}
    # 1: Elm->3. 3: Elm->1, Main->5, roundabout->6. 5: Turnpike->1, Back Lane->8.
    # 6: roundabout->3, Spur->8. 8: Spur->6.
    assert out_degree == {1: 1, 3: 3, 5: 2, 6: 2, 8: 1}
    for e in graph["edges"]:
        assert e["r0"] == 0 and e["r1"] == 0


def test_oneway_directions(graph):
    g = graph
    elm_f = edge(g, 1, 3, 100)
    elm_b = edge(g, 3, 1, 100)
    assert elm_f["flags"] & build_graph.FLAG_FORWARD
    assert not elm_b["flags"] & build_graph.FLAG_FORWARD
    assert shape_osm_ids(g, elm_f) == [2] and shape_osm_ids(g, elm_b) == [2]

    main = edge(g, 3, 5, 200)
    assert main["flags"] & build_graph.FLAG_FORWARD
    assert shape_osm_ids(g, main) == [4]
    assert not [e for e in g["edges"] if e["way"] == 200 and g["osm_of"][e["from"]] == 5]

    turnpike = edge(g, 5, 1, 300)
    assert turnpike["flags"] & build_graph.FLAG_FORWARD
    assert not [e for e in g["edges"] if e["way"] == 300 and g["osm_of"][e["from"]] == 1]

    back = edge(g, 5, 8, 600)
    assert not back["flags"] & build_graph.FLAG_FORWARD
    assert not [e for e in g["edges"] if e["way"] == 600 and g["osm_of"][e["from"]] == 8]

    r1 = edge(g, 3, 6, 400)
    r2 = edge(g, 6, 3, 400)
    assert r1["shape_count"] == 0 and shape_osm_ids(g, r2) == [7]
    for r in (r1, r2):
        assert r["flags"] & build_graph.FLAG_FORWARD and r["flags"] & build_graph.FLAG_ROUNDABOUT
    assert len([e for e in g["edges"] if e["way"] == 400]) == 2


def test_attributes(graph):
    g = graph
    t = edge(g, 5, 1, 300)
    assert t["maxspeed"] == 105
    assert t["road_class"] == 0
    assert t["flags"] & build_graph.FLAG_TOLL
    assert name_of(g, t) == "Turnpike"

    main = edge(g, 3, 5, 200)
    assert main["maxspeed"] == 50 and main["road_class"] == 2
    assert not main["flags"] & build_graph.FLAG_TOLL
    assert name_of(g, main) == "Main Street"

    spur = edge(g, 6, 8, 450)
    assert spur["flags"] & build_graph.FLAG_UNPAVED and spur["road_class"] == 6
    assert name_of(g, edge(g, 3, 6, 400)) is None
    assert edge(g, 3, 6, 400)["road_class"] == 4
    assert edge(g, 5, 8, 600)["road_class"] == 3
    assert edge(g, 1, 3, 100)["maxspeed"] == 0


def test_lengths(graph):
    g = graph
    expected_m = 2 * 6371008.8 * math.asin(math.cos(math.radians(40.0)) * math.sin(math.radians(0.002) / 2))
    elm = edge(g, 1, 3, 100)
    assert abs(elm["length_dm"] - expected_m * 10) <= 2
    assert edge(g, 3, 1, 100)["length_dm"] == elm["length_dm"]


def test_traffic_controls(graph):
    g = graph
    sig = build_graph.FLAG_SIGNALS
    stop = build_graph.FLAG_STOP
    assert edge(g, 1, 3, 100)["flags"] & sig
    assert edge(g, 6, 3, 400)["flags"] & sig
    assert not edge(g, 3, 5, 200)["flags"] & sig
    assert not edge(g, 3, 1, 100)["flags"] & sig
    assert not edge(g, 3, 6, 400)["flags"] & sig
    assert edge(g, 3, 6, 400)["flags"] & stop
    assert edge(g, 8, 6, 450)["flags"] & stop
    assert not edge(g, 6, 8, 450)["flags"] & stop
    assert not edge(g, 6, 3, 400)["flags"] & stop


def test_link_flag(tmp_path):
    osm = tmp_path / "link.osm"
    out = tmp_path / "link.agr"
    osm.write_text(
        '<?xml version="1.0"?><osm version="0.6">'
        '<node id="1" lat="40.0" lon="-83.0"/><node id="2" lat="40.0" lon="-83.001"/>'
        '<way id="9"><nd ref="1"/><nd ref="2"/><tag k="highway" v="primary_link"/></way>'
        '</osm>', encoding="utf-8")
    assert build_graph.main([str(osm), str(out), "--region", "x"]) == 0
    g = parse_agr(out.read_bytes())
    assert len(g["edges"]) == 2
    for e in g["edges"]:
        assert e["flags"] & build_graph.FLAG_LINK and e["road_class"] == 2


@pytest.mark.parametrize("raw,kph", [
    ("55 mph", 89), ("65 mph", 105), ("50", 50), ("25mph", 40), ("80 km/h", 80),
    ("none", 0), ("US:urban", 0), (None, 0), ("50;70", 50), ("signals", 0),
])
def test_parse_maxspeed(raw, kph):
    assert build_graph.parse_maxspeed(raw) == kph


def test_kept_components_deep_chain():
    # A long cycle would blow Python's recursion limit with a recursive Tarjan.
    n = 200_000
    first = list(range(n + 1))
    to = [(i + 1) % n for i in range(n)]
    assert len(build_graph.kept_components(n, first, to, build_graph.MIN_COMPONENT_NODES)) == n


def test_largest_component_kept_even_below_threshold():
    # Two 2-cycles and a 3-cycle; with a threshold above all of them only the largest survives.
    first = [0, 1, 2, 3, 4, 5, 6, 7]
    to = [1, 0, 3, 2, 5, 6, 4]
    assert sorted(build_graph.kept_components(7, first, to, 10)) == [4, 5, 6]
    assert sorted(build_graph.kept_components(7, first, to, 2)) == list(range(7))


def test_island_above_min_component_is_kept(tmp_path):
    osm = tmp_path / "islands.osm"
    out = tmp_path / "islands.agr"
    write_osm(osm)
    assert build_graph.main([str(osm), str(out), "--region", "x", "--min-component", "2"]) == 0
    g = parse_agr(out.read_bytes())
    by_coord = {(e7(lat), e7(lon)): nid for nid, (lat, lon, _) in NODES.items()}
    assert sorted(by_coord[n] for n in g["nodes"]) == [1, 3, 5, 6, 8, 20, 21]
    assert len(g["edges"]) == 11
    assert sum(1 for e in g["edges"] if e["way"] == 500) == 2
    assert "Island Road" in g["names"]


def test_island_below_min_component_is_dropped(tmp_path):
    osm = tmp_path / "islands.osm"
    out = tmp_path / "islands.agr"
    write_osm(osm)
    assert build_graph.main([str(osm), str(out), "--region", "x", "--min-component", "3"]) == 0
    g = parse_agr(out.read_bytes())
    assert len(g["nodes"]) == 5
    assert all(e["way"] != 500 for e in g["edges"])


def test_graph_too_large_for_the_phone_is_refused(tmp_path, monkeypatch):
    # The phone maps the whole file into one ByteBuffer (max 2^31-1 bytes); a bigger graph must
    # fail at build time instead of being published and then rejected on every phone.
    osm = tmp_path / "big.osm"
    out = tmp_path / "big.agr"
    write_osm(osm)
    monkeypatch.setattr(build_graph, "MAX_FILE_BYTES", 100)
    with pytest.raises(SystemExit):
        build_graph.main([str(osm), str(out), "--region", "x"])
    assert not out.exists()
    assert not (tmp_path / "big.agr.tmp").exists()


def test_full_region_name_is_stored(tmp_path):
    osm = tmp_path / "r.osm"
    out = tmp_path / "r.agr"
    osm.write_text(
        '<?xml version="1.0"?><osm version="0.6">'
        '<node id="1" lat="40.0" lon="-83.0"/><node id="2" lat="40.0" lon="-83.001"/>'
        '<way id="9"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/></way>'
        '</osm>', encoding="utf-8")
    slug = "north-america-us-north-carolina"
    assert build_graph.main([str(osm), str(out), "--region", slug]) == 0
    data = out.read_bytes()
    assert struct.unpack_from("<I", data, 4)[0] == 2
    assert parse_agr(data)["region"] == slug


def test_region_longer_than_header_is_refused(tmp_path):
    osm = tmp_path / "r.osm"
    osm.write_text('<?xml version="1.0"?><osm version="0.6"></osm>', encoding="utf-8")
    with pytest.raises(SystemExit):
        build_graph.main([str(osm), str(tmp_path / "r.agr"), "--region", "a" * 65])


def test_region_list_matches_the_app():
    """The maps workflow builds exactly the regions the app offers for download."""
    here = Path(__file__).resolve().parent
    listed = [l.strip() for l in (here / "us-regions.txt").read_text().splitlines() if l.strip()]
    store = (here.parents[1] / "android/app/src/main/java/com/xat/aegis/nav/GraphStore.kt").read_text()
    offered = ["north-america/us/" + m for m in re.findall(r'us\("([a-z-]+)"', store)]
    assert listed == offered
    assert len(set(listed)) == len(listed)
