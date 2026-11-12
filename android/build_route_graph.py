"""Build the HUD's offline routing graph from one OpenStreetMap PBF extract.

The output is a SQLite file (hud-route.db) of tiles. Every drivable way is cut into edges at its intersections;
each edge is directed, with a travel time in seconds, a length, a road class, a name and its shape. Two levels are
written: level 0 holds every road in 0.25 degree tiles, level 1 only the main roads (motorway to secondary) in
1 degree tiles, so a long trip can cross the country on the big roads and only read local streets near its ends.

Tile blob (zlib-deflated, little-endian), once inflated:
    4 bytes  magic "HRT2"
    int32 x5 node count N, edge count E, shape point count S, restriction count R, name bytes B
    nodes     int64[N] id (ascending), int32[N] lat, int32[N] lon (degrees * 1e7), int32[N+1] first edge
    edges     (sorted by start node) int64[E] target id, int32[E] target lat, int32[E] target lon,
              float32[E] cost seconds, float32[E] length metres, uint8[E] class, uint8[E] flags,
              uint8[E] bearing at start, uint8[E] bearing at end (360 degrees over 0-255),
              int32[E] name offset (-1 for none), int32[E+1] first shape point
    shape     int32[2*S] interior points of each edge as lat, lon pairs
    banned    int64[R] via node, int64[R] node before it, int64[R] node after it
    names     B bytes of NUL-terminated UTF-8
Class: 0 motorway, 1 trunk, 2 primary, 3 secondary, 4 tertiary, 5 unclassified, 6 residential, 7 service.
Flags: 1 link road, 2 roundabout, 4 toll, 8 destination-only access.
"""

import argparse
import math
import sqlite3
import struct
import sys
import time
import zlib
from collections import defaultdict
from pathlib import Path

import numpy as np

LOCAL_DEG = 0.25
HIGH_DEG = 1.0
MAIN_CLASSES = 3          # classes 0..3 go in the main-road level
MAGIC = b"HRT2"

HIGHWAY_CLASS = {
    "motorway": 0, "motorway_link": 0, "trunk": 1, "trunk_link": 1, "primary": 2, "primary_link": 2,
    "secondary": 3, "secondary_link": 3, "tertiary": 4, "tertiary_link": 4, "unclassified": 5, "road": 5,
    "residential": 6, "living_street": 6, "service": 7,
}
# Average speeds, in mph, for roads with no posted limit: lower than the limits to allow for junctions and traffic.
DEFAULT_MPH = {0: 60, 1: 50, 2: 40, 3: 35, 4: 30, 5: 28, 6: 22, 7: 10}
BAD_SERVICE = {"parking_aisle", "driveway", "drive-through", "emergency_access", "drive_through"}
NO_ACCESS = {"no", "private", "agricultural", "forestry", "delivery", "customers"}
SIGNAL_DELAY = 10.0
STOP_DELAY = 4.0
SERVICE_PENALTY = 2.0
DESTINATION_PENALTY = 2.0

SPEED = None  # set from pack_detail.parse_maxspeed on first use


def parse_speed(value):
    global SPEED
    if SPEED is None:
        from pack_detail import parse_maxspeed
        SPEED = parse_maxspeed
    return SPEED(value)


def way_info(tags):
    """The routing attributes of a way, or None when cars cannot use it."""
    highway = tags.get("highway")
    cls = HIGHWAY_CLASS.get(highway)
    if cls is None:
        return None
    if tags.get("area") == "yes" or tags.get("highway") == "construction":
        return None
    if highway == "service" and tags.get("service") in BAD_SERVICE:
        return None
    access = None
    for key in ("motorcar", "motor_vehicle", "vehicle", "access"):
        value = tags.get(key)
        if value:
            access = value
            break
    destination = access == "destination"
    if access in NO_ACCESS:
        return None
    junction = tags.get("junction")
    roundabout = junction in ("roundabout", "circular")
    oneway = tags.get("oneway", "")
    direction = 0
    if oneway in ("yes", "true", "1"):
        direction = 1
    elif oneway in ("-1", "reverse"):
        direction = -1
    elif oneway != "no" and (roundabout or highway in ("motorway", "motorway_link")):
        direction = 1
    ref = tags.get("ref", "").split(";")[0].strip()
    name = tags.get("name", "").split(";")[0].strip()
    label = (ref or name) if cls <= 2 else (name or ref)
    mph = parse_speed(tags.get("maxspeed"))
    link = highway.endswith("_link")
    if mph:
        speed = mph * (0.9 if cls <= 3 else 0.8)
    else:
        speed = DEFAULT_MPH[cls] * (0.75 if link else 1.0)
    flags = (1 if link else 0) | (2 if roundabout else 0) | (4 if tags.get("toll") == "yes" else 0) \
        | (8 if destination else 0)
    penalty = (SERVICE_PENALTY if cls == 7 else 1.0) * (DESTINATION_PENALTY if destination else 1.0)
    return {"cls": cls, "dir": direction, "name": label[:40], "mps": max(2.0, speed * 0.44704),
            "flags": flags, "penalty": penalty}


def bearing_byte(lat1, lon1, lat2, lon2):
    dy = (lat2 - lat1) * 110574.0
    dx = (lon2 - lon1) * 111320.0 * math.cos(math.radians((lat1 + lat2) / 2e7 * 1e0)) if False else \
        (lon2 - lon1) * 111320.0 * math.cos(math.radians((lat1 + lat2) / 2.0 / 1e7))
    degrees = math.degrees(math.atan2(dx, dy)) % 360.0
    return int(degrees / 360.0 * 256.0) & 255


class Way:
    __slots__ = ("id", "refs", "lat", "lon", "info")

    def __init__(self, way_id, refs, lat, lon, info):
        self.id, self.refs, self.lat, self.lon, self.info = way_id, refs, lat, lon, info


def read_ways(pbf, bounds):
    """Every drivable way as arrays of node ids and positions, plus the nodes that carry signals or stop signs."""
    import osmium

    south, west, north, east = bounds
    ways = []
    signals, stops = set(), set()
    bits = osmium.osm.osm_entity_bits
    processor = (osmium.FileProcessor(pbf).with_locations()
                 .with_filter(osmium.filter.EntityFilter(bits.NODE | bits.WAY))
                 .with_filter(osmium.filter.KeyFilter("highway")))
    for item in processor:
        tags = item.tags
        if isinstance(item, osmium.osm.Node):
            highway = tags.get("highway")
            if highway == "traffic_signals":
                signals.add(item.id)
            elif highway == "stop":
                stops.add(item.id)
            continue
        info = way_info(tags)
        if info is None:
            continue
        count = len(item.nodes)
        refs = np.empty(count, dtype=np.int64)
        lat = np.empty(count, dtype=np.int32)
        lon = np.empty(count, dtype=np.int32)
        valid = True
        inside = False
        for i, node in enumerate(item.nodes):
            location = node.location
            if not location.valid():
                valid = False
                break
            refs[i] = node.ref
            lat[i] = int(round(location.lat * 1e7))
            lon[i] = int(round(location.lon * 1e7))
            if south <= location.lat <= north and west <= location.lon <= east:
                inside = True
        if valid and inside and count >= 2:
            ways.append(Way(item.id, refs, lat, lon, info))
    return ways, signals, stops


def read_restrictions(pbf):
    """(kind, from way, via node, to way) for each simple turn restriction."""
    import osmium

    found = []
    bits = osmium.osm.osm_entity_bits
    processor = (osmium.FileProcessor(pbf)
                 .with_filter(osmium.filter.EntityFilter(bits.RELATION))
                 .with_filter(osmium.filter.KeyFilter("restriction")))
    for relation in processor:
        tags = relation.tags
        kind = tags.get("restriction", "")
        if tags.get("type") != "restriction" or not kind:
            continue
        if tags.get("except") and "motorcar" in tags.get("except", ""):
            continue
        origin = via = target = None
        for member in relation.members:
            role = member.role
            if role == "from" and member.type == "w":
                origin = member.ref
            elif role == "via" and member.type == "n":
                via = member.ref
            elif role == "to" and member.type == "w":
                target = member.ref
        if origin is not None and via is not None and target is not None:
            found.append((kind, origin, via, target))
    return found


def signed_turn(bearing_in, bearing_out):
    """Degrees of turn, positive to the right, from two 0-255 bearings."""
    delta = ((bearing_out - bearing_in) * 360.0 / 256.0) % 360.0
    return delta - 360.0 if delta > 180.0 else delta


def banned_turns(restrictions, incoming, outgoing, via_out):
    """Resolve restrictions to (node before, via, node after) triples using the edges actually built."""
    banned = set()
    for kind, origin, via, target in restrictions:
        ins = incoming.get((origin, via), [])
        outs = outgoing.get((target, via), [])
        if not ins or not outs:
            continue
        for start_id, arrive in ins:
            for end_id, depart in outs:
                turn = signed_turn(arrive, depart)
                if kind.startswith("only_"):
                    wanted = kind[5:]
                    if not matches(wanted, turn):
                        continue
                    for other_end, _other in via_out.get(via, []):
                        if other_end != end_id:
                            banned.add((start_id, via, other_end))
                else:
                    wanted = kind[3:] if kind.startswith("no_") else kind
                    if matches(wanted, turn) or wanted not in ("left_turn", "right_turn", "straight_on", "u_turn"):
                        banned.add((start_id, via, end_id))
    return banned


def matches(wanted, turn):
    if wanted == "left_turn":
        return -165.0 < turn < -20.0
    if wanted == "right_turn":
        return 20.0 < turn < 165.0
    if wanted == "straight_on":
        return abs(turn) <= 30.0
    if wanted == "u_turn":
        return abs(turn) >= 150.0
    return True


def build_edges(ways, signals, stops, restrictions):
    counts_source = np.concatenate([w.refs for w in ways]) if ways else np.empty(0, dtype=np.int64)
    values, counts = np.unique(counts_source, return_counts=True)
    inter = set(values[counts >= 2].tolist())
    via_nodes = {via for _k, _o, via, _t in restrictions}
    inter_array = None
    edges = []
    incoming, outgoing = defaultdict(list), defaultdict(list)
    via_out = defaultdict(list)
    ways_by_id = {}
    inter_sorted = np.array(sorted(inter), dtype=np.int64)
    for way in ways:
        refs = way.refs
        position = np.searchsorted(inter_sorted, refs)
        position[position >= len(inter_sorted)] = len(inter_sorted) - 1 if len(inter_sorted) else 0
        is_inter = (inter_sorted[position] == refs) if len(inter_sorted) else np.zeros(len(refs), dtype=bool)
        is_inter[0] = True
        is_inter[-1] = True
        for i in np.flatnonzero(np.isin(refs, list(via_nodes))) if via_nodes else []:
            is_inter[i] = True
        cuts = np.flatnonzero(is_inter)
        info = way.info
        for a, b in zip(cuts[:-1], cuts[1:]):
            lat = way.lat[a:b + 1].astype(np.int64)
            lon = way.lon[a:b + 1].astype(np.int64)
            mean_lat = float(lat.mean()) / 1e7
            dy = np.diff(lat) * 1e-7 * 110574.0
            dx = np.diff(lon) * 1e-7 * 111320.0 * math.cos(math.radians(mean_lat))
            length = float(np.hypot(dx, dy).sum())
            if length < 0.5:
                continue
            start_id, end_id = int(refs[a]), int(refs[b])
            interior = [(int(lat[k]), int(lon[k])) for k in range(1, len(lat) - 1)]
            out_bearing = bearing_byte(lat[0], lon[0], lat[1], lon[1])
            in_bearing = bearing_byte(lat[-2], lon[-2], lat[-1], lon[-1])
            base = length / info["mps"] * info["penalty"]
            for forward in (True, False):
                if info["dir"] == 1 and not forward or info["dir"] == -1 and forward:
                    continue
                if forward:
                    s, e = start_id, end_id
                    s_lat, s_lon, e_lat, e_lon = int(lat[0]), int(lon[0]), int(lat[-1]), int(lon[-1])
                    shape, bear_start, bear_end = interior, out_bearing, in_bearing
                else:
                    s, e = end_id, start_id
                    s_lat, s_lon, e_lat, e_lon = int(lat[-1]), int(lon[-1]), int(lat[0]), int(lon[0])
                    shape = interior[::-1]
                    bear_start, bear_end = (in_bearing + 128) & 255, (out_bearing + 128) & 255
                delay = SIGNAL_DELAY if e in signals else STOP_DELAY if e in stops else 0.0
                edges.append((s, s_lat, s_lon, e, e_lat, e_lon, base + delay, length, info["cls"], info["flags"],
                              bear_start, bear_end, info["name"], shape))
                incoming[(way.id, e)].append((s, bear_end))
                outgoing[(way.id, s)].append((e, bear_start))
                if s in via_nodes:
                    via_out[s].append((e, bear_start))
    return edges, banned_turns(restrictions, incoming, outgoing, via_out)


def tile_key(lat, lon, size):
    return int(math.floor((lon / 1e7 + 180.0) / size)), int(math.floor((lat / 1e7 + 90.0) / size))


def encode_tile(edges, banned):
    """One tile blob from its edges (all starting in the tile) and the restrictions at its nodes."""
    edges = sorted(edges, key=lambda e: e[0])
    node_ids, node_lat, node_lon, first = [], [], [], []
    names, name_offsets, name_bytes = {}, [], bytearray()
    shape_pts, shape_first = [], [0]
    columns = {"target": [], "tlat": [], "tlon": [], "cost": [], "length": [], "cls": [], "flags": [],
               "bs": [], "be": [], "name": []}
    last = None
    for index, edge in enumerate(edges):
        s, s_lat, s_lon, e, e_lat, e_lon, cost, length, cls, flags, bs, be, name, shape = edge
        if s != last:
            node_ids.append(s)
            node_lat.append(s_lat)
            node_lon.append(s_lon)
            first.append(index)
            last = s
        columns["target"].append(e)
        columns["tlat"].append(e_lat)
        columns["tlon"].append(e_lon)
        columns["cost"].append(cost)
        columns["length"].append(length)
        columns["cls"].append(cls)
        columns["flags"].append(flags)
        columns["bs"].append(bs)
        columns["be"].append(be)
        if name:
            if name not in names:
                names[name] = len(name_bytes)
                name_bytes.extend(name.encode("utf-8") + b"\0")
            columns["name"].append(names[name])
        else:
            columns["name"].append(-1)
        for point in shape:
            shape_pts.extend(point)
        shape_first.append(len(shape_pts) // 2)
    first.append(len(edges))
    nodes_set = set(node_ids)
    mine = sorted(t for t in banned if t[1] in nodes_set)
    out = bytearray(MAGIC)
    out += struct.pack("<5i", len(node_ids), len(edges), len(shape_pts) // 2, len(mine), len(name_bytes))
    out += np.array(node_ids, dtype="<i8").tobytes()
    out += np.array(node_lat, dtype="<i4").tobytes() + np.array(node_lon, dtype="<i4").tobytes()
    out += np.array(first, dtype="<i4").tobytes()
    out += np.array(columns["target"], dtype="<i8").tobytes()
    out += np.array(columns["tlat"], dtype="<i4").tobytes() + np.array(columns["tlon"], dtype="<i4").tobytes()
    out += np.array(columns["cost"], dtype="<f4").tobytes() + np.array(columns["length"], dtype="<f4").tobytes()
    out += np.array(columns["cls"], dtype="u1").tobytes() + np.array(columns["flags"], dtype="u1").tobytes()
    out += np.array(columns["bs"], dtype="u1").tobytes() + np.array(columns["be"], dtype="u1").tobytes()
    out += np.array(columns["name"], dtype="<i4").tobytes()
    out += np.array(shape_first, dtype="<i4").tobytes()
    out += np.array(shape_pts, dtype="<i4").tobytes()
    out += np.array([t[1] for t in mine], dtype="<i8").tobytes()
    out += np.array([t[0] for t in mine], dtype="<i8").tobytes()
    out += np.array([t[2] for t in mine], dtype="<i8").tobytes()
    out += bytes(name_bytes)
    return zlib.compress(bytes(out), 6)


def decode_tile(blob):
    """Python reader for the format above (used by tests and by the merge step)."""
    raw = zlib.decompress(blob)
    assert raw[:4] == MAGIC
    n, e, s, r, b = struct.unpack_from("<5i", raw, 4)
    pos = 24

    def take(dtype, count):
        nonlocal pos
        array = np.frombuffer(raw, dtype=dtype, count=count, offset=pos)
        pos += array.nbytes
        return array

    tile = {"id": take("<i8", n), "lat": take("<i4", n), "lon": take("<i4", n), "first": take("<i4", n + 1),
            "target": take("<i8", e), "tlat": take("<i4", e), "tlon": take("<i4", e), "cost": take("<f4", e),
            "length": take("<f4", e), "cls": take("u1", e), "flags": take("u1", e), "bs": take("u1", e),
            "be": take("u1", e), "name": take("<i4", e), "shape_first": take("<i4", e + 1),
            "shape": take("<i4", 2 * s), "via": take("<i8", r), "before": take("<i8", r), "after": take("<i8", r)}
    tile["names"] = raw[pos:pos + b]
    return tile


def write_database(output, edges, banned, name, bounds):
    local, high = defaultdict(list), defaultdict(list)
    for edge in edges:
        local[tile_key(edge[1], edge[2], LOCAL_DEG)].append(edge)
        if edge[8] <= MAIN_CLASSES:
            high[tile_key(edge[1], edge[2], HIGH_DEG)].append(edge)
    temporary = output.with_suffix(".tmp")
    temporary.unlink(missing_ok=True)
    database = sqlite3.connect(temporary)
    database.executescript("""
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE tiles (level INTEGER, x INTEGER, y INTEGER, data BLOB, PRIMARY KEY (level, x, y)) WITHOUT ROWID;
    """)
    with database:
        for level, groups in ((0, local), (1, high)):
            for (x, y), group in groups.items():
                database.execute("INSERT INTO tiles VALUES (?, ?, ?, ?)", (level, x, y, encode_tile(group, banned)))
        meta = {"name": name, "format": "HRT2", "local_deg": LOCAL_DEG, "high_deg": HIGH_DEG,
                "south": bounds[0], "west": bounds[1], "north": bounds[2], "east": bounds[3],
                "built": time.strftime("%Y-%m-%d"), "edges": len(edges), "banned_turns": len(banned)}
        database.executemany("INSERT INTO meta VALUES (?, ?)", [(k, str(v)) for k, v in meta.items()])
    database.close()
    temporary.replace(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("output", type=Path)
    parser.add_argument("source", type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--bounds", nargs=4, type=float, default=(-90, -180, 90, 180), metavar=("S", "W", "N", "E"))
    args = parser.parse_args()
    started = time.time()
    ways, signals, stops = read_ways(args.source, args.bounds)
    print(f"{len(ways)} ways, {len(signals)} signals, {len(stops)} stops  ({time.time() - started:.0f}s)", flush=True)
    restrictions = read_restrictions(args.source)
    print(f"{len(restrictions)} restrictions  ({time.time() - started:.0f}s)", flush=True)
    edges, banned = build_edges(ways, signals, stops, restrictions)
    print(f"{len(edges)} edges, {len(banned)} banned turns  ({time.time() - started:.0f}s)", flush=True)
    write_database(args.output, edges, banned, args.name, args.bounds)
    print(f"{args.output.name}: {args.output.stat().st_size:,} bytes  ({time.time() - started:.0f}s)", flush=True)


if __name__ == "__main__":
    sys.exit(main())
