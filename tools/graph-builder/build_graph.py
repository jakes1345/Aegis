#!/usr/bin/env python3
"""Build an AGR1 road graph (see the navigation spec, section 2) from an OSM extract.

usage: build_graph.py <input.osm.pbf|.osm> <output.agr> --region <slug> [--index flex_mem]
"""
from __future__ import annotations

import argparse
import math
import os
import re
import struct
import sys
import time
from array import array

import numpy as np
import osmium

MAGIC = b"AGR1"
VERSION = 2
HEADER_SIZE = 128
REGION_BYTES = 64
UNNAMED = 0xFFFFFFFF
MAX_SHAPE = 0xFFFF
UNDEFINED_COORD = 2147483647  # libosmium's Location::undefined_coordinate
EARTH_RADIUS_M = 6371008.8
# The phone memory-maps the file into a single ByteBuffer, which Java caps at Integer.MAX_VALUE.
MAX_FILE_BYTES = 0x7FFFFFFF
CHUNK = 1 << 22  # elements per vectorised batch; bounds temporary arrays on state-sized inputs
# Strongly connected components at least this big are kept alongside the largest one, so islands
# and ferry-only towns (Hawaii's islands, Juneau, Vieques) stay routable while parking-lot
# fragments and one-way dead ends are still dropped.
MIN_COMPONENT_NODES = 200

FLAG_TOLL = 1
FLAG_ROUNDABOUT = 2
FLAG_LINK = 4
FLAG_UNPAVED = 8
FLAG_FORWARD = 16
FLAG_SIGNALS = 32
FLAG_STOP = 64

ROAD_CLASS = {
    "motorway": 0, "motorway_link": 0,
    "trunk": 1, "trunk_link": 1,
    "primary": 2, "primary_link": 2,
    "secondary": 3, "secondary_link": 3,
    "tertiary": 4, "tertiary_link": 4,
    "unclassified": 5,
    "residential": 6,
    "living_street": 7, "service": 7,
}
EXCLUDED_SERVICE = {"parking_aisle", "driveway"}
NO_ACCESS = {"no", "private"}
ALLOW_ACCESS = {"yes", "permissive", "destination", "designated", "customers", "delivery"}
UNPAVED_SURFACES = {
    "unpaved", "gravel", "fine_gravel", "dirt", "ground", "earth", "compacted", "sand",
    "grass", "mud", "pebblestone", "rock", "rocks", "salt", "snow", "ice", "woodchips",
    "grass_paver", "clay",
}
EDGE_DTYPE = np.dtype({
    "names": ["to", "lengthDm", "wayId", "nameIdx", "shapeStart", "shapeCount",
              "maxspeed", "roadClass", "flags", "r0", "r1"],
    "formats": ["<u4", "<u4", "<u8", "<u4", "<u4", "<u2", "u1", "u1", "u1", "u1", "<u2"],
    "offsets": [0, 4, 8, 16, 20, 24, 26, 27, 28, 29, 30],
    "itemsize": 32,
})
_SPEED_RE = re.compile(r"^\s*([0-9]+(?:\.[0-9]+)?)\s*(mph|km/h|kmh|kph|knots)?\s*$")


def parse_maxspeed(value: str | None) -> int:
    """km/h rounded, 0 when absent or not numeric ("none", "signals", "US:urban", ...)."""
    if not value:
        return 0
    first = value.split(";")[0]
    m = _SPEED_RE.match(first.lower())
    if not m:
        return 0
    v = float(m.group(1))
    unit = m.group(2)
    if unit == "mph":
        v *= 1.609344
    elif unit == "knots":
        v *= 1.852
    kph = int(math.floor(v + 0.5))
    return max(0, min(255, kph))


def parse_oneway(tags, highway: str) -> int | None:
    """1 = along way order only, -1 = against it only, 0 = both, None = not routable."""
    ow = tags.get("oneway")
    if ow is not None:
        ow = ow.lower()
        if ow in ("yes", "true", "1"):
            return 1
        if ow in ("-1", "reverse"):
            return -1
        if ow in ("no", "false", "0"):
            return 0
        if ow in ("reversible", "alternating"):
            # Direction changes by time of day and is not described in the tags.
            return None
    if tags.get("junction") in ("roundabout", "circular"):
        return 1
    if highway in ("motorway", "motorway_link"):
        return 1
    return 0


def motor_access_allowed(tags) -> bool:
    mv = tags.get("motor_vehicle")
    car = tags.get("motorcar")
    specific = car if car is not None else mv
    if specific is not None:
        if specific in NO_ACCESS:
            return False
        if specific in ALLOW_ACCESS:
            return True
    if tags.get("vehicle") in NO_ACCESS:
        return False
    if tags.get("access") in NO_ACCESS:
        return False
    return True


def node_control_flags(tags) -> tuple[int, int]:
    """(flag, direction) where direction is 0 both, 1 way-forward only, -1 way-backward only."""
    hw = tags.get("highway")
    flag = 0
    if hw == "traffic_signals" or (hw == "crossing" and tags.get("crossing") == "traffic_signals"):
        flag = FLAG_SIGNALS
        d = tags.get("traffic_signals:direction", tags.get("direction"))
    elif hw in ("stop", "give_way"):
        flag = FLAG_STOP
        d = tags.get("direction")
    else:
        return 0, 0
    if d == "forward":
        return flag, 1
    if d == "backward":
        return flag, -1
    return flag, 0


class WayStore:
    """Compact columnar storage for drivable way pieces (a piece = run of nodes with known locations)."""

    def __init__(self) -> None:
        self.refs = array("q")
        self.lat = array("i")
        self.lon = array("i")
        self.piece_len = array("I")
        self.way_id = array("Q")
        self.oneway = array("b")
        self.road_class = array("B")
        self.flags = array("B")
        self.speed_fwd = array("B")
        self.speed_bwd = array("B")
        self.name_idx = array("I")
        self.names: list[str] = []
        self._name_lookup: dict[str, int] = {}
        self.node_flags: dict[int, tuple[int, int]] = {}

    def name_index(self, name: str | None) -> int:
        if not name:
            return UNNAMED
        idx = self._name_lookup.get(name)
        if idx is None:
            idx = len(self.names)
            self.names.append(name)
            self._name_lookup[name] = idx
        return idx

    def add_node(self, node) -> None:
        flag, direction = node_control_flags(node.tags)
        if flag:
            prev = self.node_flags.get(node.id)
            if prev is not None and prev[1] != direction:
                direction = 0
            self.node_flags[node.id] = (flag | (prev[0] if prev else 0), direction)

    def add_way(self, way) -> None:
        tags = way.tags
        highway = tags.get("highway")
        rc = ROAD_CLASS.get(highway)
        if rc is None:
            return
        if highway == "service" and tags.get("service") in EXCLUDED_SERVICE:
            return
        if tags.get("area") == "yes":
            return
        if not motor_access_allowed(tags):
            return
        oneway = parse_oneway(tags, highway)
        if oneway is None:
            return

        flags = 0
        toll_car = tags.get("toll:motorcar")
        if toll_car == "yes" or (toll_car != "no" and tags.get("toll") == "yes"):
            flags |= FLAG_TOLL
        if tags.get("junction") in ("roundabout", "circular"):
            flags |= FLAG_ROUNDABOUT
        if highway.endswith("_link"):
            flags |= FLAG_LINK
        if tags.get("surface") in UNPAVED_SURFACES:
            flags |= FLAG_UNPAVED

        base = parse_maxspeed(tags.get("maxspeed"))
        fwd = parse_maxspeed(tags.get("maxspeed:forward")) or base
        bwd = parse_maxspeed(tags.get("maxspeed:backward")) or base
        name_idx = self.name_index(tags.get("name") or tags.get("ref"))
        wid = way.id

        refs, lat, lon = self.refs, self.lat, self.lon
        run = 0
        last_ref = None
        for n in way.nodes:
            x = n.x
            if x == UNDEFINED_COORD:
                # Node missing from the extract: end the current piece here.
                if run:
                    self._close_piece(run, wid, oneway, rc, flags, fwd, bwd, name_idx)
                run = 0
                last_ref = None
                continue
            ref = n.ref
            if ref == last_ref:
                continue
            refs.append(ref)
            lat.append(n.y)
            lon.append(x)
            last_ref = ref
            run += 1
        if run:
            self._close_piece(run, wid, oneway, rc, flags, fwd, bwd, name_idx)

    def _close_piece(self, run, wid, oneway, rc, flags, fwd, bwd, name_idx) -> None:
        if run < 2:
            del self.refs[-run:]
            del self.lat[-run:]
            del self.lon[-run:]
            return
        self.piece_len.append(run)
        self.way_id.append(wid)
        self.oneway.append(oneway)
        self.road_class.append(rc)
        self.flags.append(flags)
        self.speed_fwd.append(fwd)
        self.speed_bwd.append(bwd)
        self.name_idx.append(name_idx)


def read_osm(path: str, index: str) -> WayStore:
    store = WayStore()
    fp = (osmium.FileProcessor(path, osmium.osm.NODE | osmium.osm.WAY)
          .with_locations(index)
          .with_filter(osmium.filter.KeyFilter("highway")))
    for obj in fp:
        if obj.is_way():
            store.add_way(obj)
        elif obj.is_node():
            store.add_node(obj)
    return store


def haversine_steps(lat_e7: np.ndarray, lon_e7: np.ndarray) -> np.ndarray:
    """Metres between consecutive points (len n-1)."""
    lat = np.radians(lat_e7.astype(np.float64) * 1e-7)
    lon = np.radians(lon_e7.astype(np.float64) * 1e-7)
    dlat = lat[1:] - lat[:-1]
    dlon = lon[1:] - lon[:-1]
    a = np.sin(dlat * 0.5) ** 2 + np.cos(lat[:-1]) * np.cos(lat[1:]) * np.sin(dlon * 0.5) ** 2
    return 2.0 * EARTH_RADIUS_M * np.arcsin(np.sqrt(np.minimum(a, 1.0)))


def cumulative_length(lat_e7: np.ndarray, lon_e7: np.ndarray) -> np.ndarray:
    """cum[i] = metres along the point list up to point i; chunked to bound float temporaries."""
    n = len(lat_e7)
    cum = np.zeros(n, dtype=np.float64)
    total = 0.0
    for s in range(0, max(n - 1, 0), CHUNK):
        e = min(n, s + CHUNK + 1)
        c = np.cumsum(haversine_steps(lat_e7[s:e], lon_e7[s:e]))
        c += total
        cum[s + 1:e] = c
        total = float(c[-1])
    return cum


def kept_components(n: int, first: list[int], to: list[int], min_nodes: int) -> list[int]:
    """Iterative Tarjan; returns the node ids of every strongly connected component with at least
    min_nodes nodes, plus the largest component whatever its size."""
    index = [-1] * n
    low = [0] * n
    on_stack = [False] * n
    next_edge = [0] * n
    stack: list[int] = []
    kept: list[int] = []
    best: list[int] = []
    counter = 0
    for root in range(n):
        if index[root] != -1:
            continue
        index[root] = low[root] = counter
        counter += 1
        stack.append(root)
        on_stack[root] = True
        next_edge[root] = first[root]
        call = [root]
        while call:
            v = call[-1]
            e = next_edge[v]
            end = first[v + 1]
            descended = False
            while e < end:
                w = to[e]
                e += 1
                if index[w] == -1:
                    next_edge[v] = e
                    index[w] = low[w] = counter
                    counter += 1
                    stack.append(w)
                    on_stack[w] = True
                    next_edge[w] = first[w]
                    call.append(w)
                    descended = True
                    break
                if on_stack[w] and index[w] < low[v]:
                    low[v] = index[w]
            if descended:
                continue
            next_edge[v] = e
            call.pop()
            if call:
                u = call[-1]
                if low[v] < low[u]:
                    low[u] = low[v]
            if low[v] == index[v]:
                comp = []
                while True:
                    w = stack.pop()
                    on_stack[w] = False
                    comp.append(w)
                    if w == v:
                        break
                if len(comp) >= min_nodes:
                    kept.extend(comp)
                if len(comp) > len(best):
                    best = comp
    if len(best) < min_nodes:
        kept.extend(best)
    return kept


def morton_order(lat_e7: np.ndarray, lon_e7: np.ndarray) -> np.ndarray:
    """Permutation that sorts points along a Z-order curve, so nearby nodes sit nearby in the file."""
    def spread(v: np.ndarray) -> np.ndarray:
        v = v.astype(np.uint64) & np.uint64(0xFFFFFFFF)
        v = (v | (v << np.uint64(16))) & np.uint64(0x0000FFFF0000FFFF)
        v = (v | (v << np.uint64(8))) & np.uint64(0x00FF00FF00FF00FF)
        v = (v | (v << np.uint64(4))) & np.uint64(0x0F0F0F0F0F0F0F0F)
        v = (v | (v << np.uint64(2))) & np.uint64(0x3333333333333333)
        v = (v | (v << np.uint64(1))) & np.uint64(0x5555555555555555)
        return v
    y = (lat_e7.astype(np.int64) + 900_000_000) >> 1
    x = (lon_e7.astype(np.int64) + 1_800_000_000) >> 1
    code = spread(x) | (spread(y) << np.uint64(1))
    return np.argsort(code, kind="stable")




def build(store: WayStore, region: str, built_at: int, out_path: str,
          min_component: int = MIN_COMPONENT_NODES) -> dict:
    """Turn the collected ways into an AGR1 file at out_path; returns summary stats."""
    refs = np.frombuffer(store.refs, dtype=np.int64)
    plat = np.frombuffer(store.lat, dtype=np.int32)
    plon = np.frombuffer(store.lon, dtype=np.int32)
    plen = np.frombuffer(store.piece_len, dtype=np.uint32).astype(np.int64)
    npieces = len(plen)
    if npieces == 0:
        raise SystemExit("no drivable ways found in input")
    pstart = np.zeros(npieces, dtype=np.int64)
    np.cumsum(plen[:-1], out=pstart[1:])
    pend = pstart + plen - 1
    piece_of = np.repeat(np.arange(npieces, dtype=np.int32), plen)
    del plen

    # Graph nodes: piece endpoints plus any node referenced more than once overall.
    uniq, counts = np.unique(refs, return_counts=True)
    is_graph_id = counts >= 2
    del counts
    ref_slot = np.empty(len(refs), dtype=np.int32)
    for s in range(0, len(refs), CHUNK):
        ref_slot[s:s + CHUNK] = np.searchsorted(uniq, refs[s:s + CHUNK])
    is_graph_id[ref_slot[pstart]] = True
    is_graph_id[ref_slot[pend]] = True
    del pstart, pend

    # Per-node traffic controls, split by which travel direction along the way they apply to.
    ctrl_fwd = np.zeros(len(uniq), dtype=np.uint8)
    ctrl_bwd = np.zeros(len(uniq), dtype=np.uint8)
    if store.node_flags:
        ids = np.fromiter(store.node_flags.keys(), dtype=np.int64, count=len(store.node_flags))
        vals = np.array(list(store.node_flags.values()), dtype=np.int64).reshape(-1, 2)
        slot = np.searchsorted(uniq, ids)
        present = slot < len(uniq)
        present[present] = uniq[slot[present]] == ids[present]
        slot, fl, dr = slot[present], vals[present, 0].astype(np.uint8), vals[present, 1]
        ctrl_fwd[slot] = np.where(dr >= 0, fl, 0)
        ctrl_bwd[slot] = np.where(dr <= 0, fl, 0)
    del uniq

    # Split segments that would overflow the u16 shape count by promoting interior nodes.
    while True:
        gp = np.flatnonzero(is_graph_id[ref_slot])
        same = piece_of[gp[:-1]] == piece_of[gp[1:]]
        too_long = np.flatnonzero(same & ((gp[1:] - gp[:-1] - 1) > MAX_SHAPE))
        if len(too_long) == 0:
            break
        for k in too_long:
            for pos in range(int(gp[k]) + MAX_SHAPE + 1, int(gp[k + 1]), MAX_SHAPE + 1):
                is_graph_id[ref_slot[pos]] = True

    uniq_to_graph = (np.cumsum(is_graph_id, dtype=np.int64) - 1).astype(np.int32)
    n_all = int(is_graph_id.sum())
    del is_graph_id
    seg_a = gp[:-1][same]
    seg_b = gp[1:][same]
    del gp, same
    from_node = uniq_to_graph[ref_slot[seg_a]]
    to_node = uniq_to_graph[ref_slot[seg_b]]
    keep = from_node != to_node
    seg_a, seg_b = seg_a[keep], seg_b[keep]
    from_node, to_node = from_node[keep], to_node[keep]
    del keep, uniq_to_graph
    seg_piece = piece_of[seg_a]
    del piece_of

    node_lat_all = np.zeros(n_all, dtype=np.int32)
    node_lon_all = np.zeros(n_all, dtype=np.int32)
    node_lat_all[from_node] = plat[seg_a]
    node_lon_all[from_node] = plon[seg_a]
    node_lat_all[to_node] = plat[seg_b]
    node_lon_all[to_node] = plon[seg_b]

    cum = cumulative_length(plat, plon)
    seg_len_dm = np.minimum(np.rint((cum[seg_b] - cum[seg_a]) * 10.0), 0xFFFFFFFF).astype(np.uint32)
    del cum

    # Controls met by forward travel lie in (a, b]; by backward travel in [a, b).
    n_seg = len(seg_a)
    seg_ctrl_fwd = np.zeros(n_seg, dtype=np.uint8)
    seg_ctrl_bwd = np.zeros(n_seg, dtype=np.uint8)
    pos_ctrl = np.flatnonzero((ctrl_fwd | ctrl_bwd)[ref_slot])
    if len(pos_ctrl) and n_seg:
        k = np.searchsorted(seg_a, pos_ctrl, side="left") - 1
        ok = k >= 0
        ok[ok] = pos_ctrl[ok] <= seg_b[k[ok]]
        np.bitwise_or.at(seg_ctrl_fwd, k[ok], ctrl_fwd[ref_slot[pos_ctrl[ok]]])
        k = np.searchsorted(seg_a, pos_ctrl, side="right") - 1
        ok = k >= 0
        ok[ok] = pos_ctrl[ok] < seg_b[k[ok]]
        np.bitwise_or.at(seg_ctrl_bwd, k[ok], ctrl_bwd[ref_slot[pos_ctrl[ok]]])
    del ref_slot, ctrl_fwd, ctrl_bwd, pos_ctrl

    oneway = np.frombuffer(store.oneway, dtype=np.int8)[seg_piece]
    f = oneway >= 0
    b = oneway <= 0
    del oneway

    def both(fwd_vals: np.ndarray, bwd_vals: np.ndarray) -> np.ndarray:
        return np.concatenate([fwd_vals[f], bwd_vals[b]])

    def per_seg(arr: array, dtype) -> np.ndarray:
        return np.frombuffer(arr, dtype=dtype)[seg_piece]

    e_from = both(from_node, to_node)
    e_to = both(to_node, from_node)
    e_len = both(seg_len_dm, seg_len_dm)
    v = per_seg(store.way_id, np.uint64)
    e_way = both(v, v)
    v = per_seg(store.name_idx, np.uint32)
    e_name = both(v, v)
    v = per_seg(store.road_class, np.uint8)
    e_class = both(v, v)
    e_speed = both(per_seg(store.speed_fwd, np.uint8), per_seg(store.speed_bwd, np.uint8))
    v = per_seg(store.flags, np.uint8)
    e_flags = both(v | np.uint8(FLAG_FORWARD) | seg_ctrl_fwd, v | seg_ctrl_bwd)
    # Forward edges walk the way's points upward from a+1, backward edges downward from b-1.
    e_shape_pos = both(seg_a + 1, seg_b - 1)
    v = seg_b - seg_a - 1
    e_shape_n = both(v, v)
    del v, from_node, to_node, seg_len_dm, seg_ctrl_fwd, seg_ctrl_bwd, seg_a, seg_b
    n_candidates = len(e_from)
    if n_candidates == 0:
        raise SystemExit("no routable edges found in input")

    order = np.argsort(e_from, kind="stable")
    first_all = np.zeros(n_all + 1, dtype=np.int64)
    np.cumsum(np.bincount(e_from, minlength=n_all), out=first_all[1:])
    first_list = first_all.tolist()
    to_list = e_to[order].tolist()
    del order, first_all
    comp = kept_components(n_all, first_list, to_list, min_component)
    del first_list, to_list
    in_comp = np.zeros(n_all, dtype=bool)
    in_comp[np.array(comp, dtype=np.int64)] = True
    del comp

    kept_nodes = np.flatnonzero(in_comp)
    perm = morton_order(node_lat_all[kept_nodes], node_lon_all[kept_nodes])
    ordered_nodes = kept_nodes[perm]
    del kept_nodes, perm
    node_count = len(ordered_nodes)
    new_id = np.full(n_all, -1, dtype=np.int32)
    new_id[ordered_nodes] = np.arange(node_count, dtype=np.int32)
    node_lat = node_lat_all[ordered_nodes]
    node_lon = node_lon_all[ordered_nodes]
    del node_lat_all, node_lon_all, ordered_nodes

    sel = np.flatnonzero(in_comp[e_from] & in_comp[e_to])
    del in_comp
    nf = new_id[e_from[sel]]
    nt = new_id[e_to[sel]]
    by_from = np.lexsort((nt, nf))
    eorder = sel[by_from]
    edge_from = nf[by_from]
    edge_to = nt[by_from]
    del sel, nf, nt, by_from, e_from, e_to, new_id
    edge_count = len(eorder)
    if edge_count > 0xFFFFFFFF:
        raise SystemExit("graph too large for AGR1 u32 edge offsets")
    first_edge = np.zeros(node_count + 1, dtype=np.int64)
    np.cumsum(np.bincount(edge_from, minlength=node_count), out=first_edge[1:])
    del edge_from

    shape_n = e_shape_n[eorder]
    shape_start = np.zeros(edge_count, dtype=np.int64)
    if edge_count:
        np.cumsum(shape_n[:-1], out=shape_start[1:])
    shape_total = int(shape_n.sum())
    if shape_total > 0xFFFFFFFF:
        raise SystemExit("graph too large for AGR1 u32 shape offsets")

    # Names: keep only those referenced by surviving edges, renumbered densely.
    name_sorted = e_name[eorder]
    used = np.unique(name_sorted)
    used = used[used != UNNAMED].astype(np.int64)
    remap = np.full(len(store.names) + 1, UNNAMED, dtype=np.uint32)
    remap[used] = np.arange(len(used), dtype=np.uint32)
    name_sorted = remap[np.where(name_sorted == UNNAMED, len(store.names), name_sorted).astype(np.int64)]
    names = [store.names[int(i)] for i in used]
    del remap, used, e_name

    lat_min, lon_min = int(node_lat.min()), int(node_lon.min())
    lat_max, lon_max = int(node_lat.max()), int(node_lon.max())

    with open(out_path, "wb") as fh:
        fh.write(b"\0" * HEADER_SIZE)
        nodes = np.empty((node_count, 2), dtype="<i4")
        nodes[:, 0] = node_lat
        nodes[:, 1] = node_lon
        fh.write(nodes.tobytes())
        del nodes, node_lat, node_lon
        fh.write(first_edge.astype("<u4").tobytes())
        del first_edge

        for s in range(0, edge_count, CHUNK):
            idx = eorder[s:s + CHUNK]
            rec = np.zeros(len(idx), dtype=EDGE_DTYPE)
            rec["to"] = edge_to[s:s + CHUNK]
            rec["lengthDm"] = e_len[idx]
            rec["wayId"] = e_way[idx]
            rec["nameIdx"] = name_sorted[s:s + CHUNK]
            rec["shapeStart"] = shape_start[s:s + CHUNK]
            rec["shapeCount"] = shape_n[s:s + CHUNK]
            rec["maxspeed"] = e_speed[idx]
            rec["roadClass"] = e_class[idx]
            rec["flags"] = e_flags[idx]
            fh.write(rec.tobytes())
        del rec, edge_to, e_len, e_way, name_sorted, e_speed, e_class

        # Shape points, gathered in bounded chunks of edges.
        forward = (e_flags[eorder] & FLAG_FORWARD) != 0
        pos0 = e_shape_pos[eorder]
        del e_flags, e_shape_pos, e_shape_n, eorder
        bounds = np.searchsorted(shape_start, np.arange(0, shape_total, CHUNK, dtype=np.int64), side="right") - 1
        bounds = np.unique(np.append(np.maximum(bounds, 0), edge_count))
        for lo, hi in zip(bounds[:-1].tolist(), bounds[1:].tolist()):
            counts = shape_n[lo:hi]
            total = int(counts.sum())
            if total == 0:
                continue
            local = np.repeat(shape_start[lo:hi] - shape_start[lo], counts)
            step = np.repeat(np.where(forward[lo:hi], 1, -1).astype(np.int64), counts)
            pos = np.repeat(pos0[lo:hi], counts) + step * (np.arange(total, dtype=np.int64) - local)
            pts = np.empty((total, 2), dtype="<i4")
            pts[:, 0] = plat[pos]
            pts[:, 1] = plon[pos]
            lat_min = min(lat_min, int(pts[:, 0].min()))
            lat_max = max(lat_max, int(pts[:, 0].max()))
            lon_min = min(lon_min, int(pts[:, 1].min()))
            lon_max = max(lon_max, int(pts[:, 1].max()))
            fh.write(pts.tobytes())

        for name in names:
            raw = _truncate_utf8(name.encode("utf-8"), 0xFFFF)
            fh.write(struct.pack("<H", len(raw)))
            fh.write(raw)

        slug = region.encode("ascii").ljust(REGION_BYTES, b"\0")
        header = struct.pack("<4sIIIII iiii Q 64s 16x", MAGIC, VERSION, node_count, edge_count,
                             shape_total, len(names), lat_min, lon_min, lat_max, lon_max,
                             built_at, slug)
        assert len(header) == HEADER_SIZE
        fh.seek(0)
        fh.write(header)
        fh.seek(0, os.SEEK_END)
        size = fh.tell()
    if size > MAX_FILE_BYTES:
        raise SystemExit(f"graph is {size} bytes; the phone cannot map more than {MAX_FILE_BYTES}")

    return {
        "nodes": node_count,
        "edges": edge_count,
        "shape": shape_total,
        "names": len(names),
        "candidate_nodes": n_all,
        "candidate_edges": n_candidates,
        "bytes": size,
    }


def _truncate_utf8(raw: bytes, limit: int) -> bytes:
    if len(raw) <= limit:
        return raw
    # Dropping a partial trailing character keeps the result valid UTF-8.
    return raw[:limit].decode("utf-8", "ignore").encode("utf-8")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Build an AGR1 road graph from OSM data.")
    ap.add_argument("input", help=".osm.pbf or .osm file")
    ap.add_argument("output", help="output .agr path")
    ap.add_argument("--region", required=True, help="region slug, e.g. north-america-us-ohio")
    ap.add_argument("--index", default="flex_mem",
                    help="osmium node location index (flex_mem, sparse_file_array,<file>, ...)")
    ap.add_argument("--min-component", type=int, default=MIN_COMPONENT_NODES,
                    help="keep every strongly connected component with at least this many nodes "
                         f"(the largest is always kept; default {MIN_COMPONENT_NODES})")
    args = ap.parse_args(argv)

    try:
        args.region.encode("ascii")
    except UnicodeEncodeError:
        ap.error("--region must be ASCII")
    if len(args.region) > REGION_BYTES:
        ap.error(f"--region is longer than {REGION_BYTES} characters")
    if args.min_component < 1:
        ap.error("--min-component must be at least 1")

    t0 = time.time()
    store = read_osm(args.input, args.index)
    print(f"read {len(store.piece_len)} drivable way pieces, {len(store.refs)} node refs, "
          f"{len(store.node_flags)} traffic-control nodes in {time.time() - t0:.1f}s", file=sys.stderr)
    built_at = int(os.environ.get("SOURCE_DATE_EPOCH", time.time()))
    tmp = args.output + ".tmp"
    try:
        stats = build(store, args.region, built_at, tmp, args.min_component)
    except BaseException:
        if os.path.exists(tmp):
            os.remove(tmp)
        raise
    os.replace(tmp, args.output)
    print(f"nodes={stats['nodes']} edges={stats['edges']} shape={stats['shape']} "
          f"names={stats['names']} bytes={stats['bytes']} "
          f"(SCCs >= {args.min_component} nodes kept {stats['nodes']}/{stats['candidate_nodes']} nodes, "
          f"{stats['edges']}/{stats['candidate_edges']} edges) in {time.time() - t0:.1f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
