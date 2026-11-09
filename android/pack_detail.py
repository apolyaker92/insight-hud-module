"""Build a detailed pack from one Geofabrik PBF: roads with street names, rail and water, plus the data
for road info on the HUD: speed limits, the road you are on, the nearest town and the nearest fuel, and the
points the HUD warns about ahead: signals, stop signs, rail crossings, cameras, highway exits, rest areas and
sharp curves.

Everything that is a point rides in the pois table, told apart by kind (see POI_KINDS).

Written to hud-roads.db (wide) or hud-local.db (one area). Merge several state packs with merge_packs.py.
"""

import argparse
import math
import re
import sqlite3
from collections import defaultdict
from contextlib import closing
from pathlib import Path

from pack_pbf_roads import SCHEMA, add_segment, geographic_bounds
from pack_roads import ARTERIALS, HIGHWAYS, ZOOM, project

LOCAL_ROADS = {"unclassified", "residential", "living_street"}
ROAD_CLASSES = HIGHWAYS | ARTERIALS | LOCAL_ROADS
RAIL, WATER = 3, 4
WATER_WAYS = {"river", "canal"}
MIN_POND = 0.05  # Closed water shorter than this many tiles across (about 250 m) is clutter.
PLACE_RANK = {"city": 0, "town": 1, "village": 2, "hamlet": 3}

EXTRA_SCHEMA = """
    CREATE TABLE ways (x INTEGER, y INTEGER, name TEXT, mph INTEGER, geometry BLOB,
        PRIMARY KEY (x, y, name, mph)) WITHOUT ROWID;
    CREATE TABLE places (tx INTEGER, ty INTEGER, name TEXT, kind INTEGER, x REAL, y REAL,
        PRIMARY KEY (tx, ty, name, kind)) WITHOUT ROWID;
    CREATE TABLE pois (tx INTEGER, ty INTEGER, kind INTEGER, name TEXT, x REAL, y REAL,
        PRIMARY KEY (tx, ty, kind, name, x, y)) WITHOUT ROWID;
"""

# pois.kind values. The HUD reads the same numbers (OfflineRoadMap).
FUEL, SIGNAL, STOP, CROSSING, CAMERA, EXIT, REST, CURVE = 1, 2, 3, 4, 5, 6, 7, 8
CURVE_MIN_TURN = 40.0      # degrees of turn within the look window
CURVE_WINDOW_M = 70.0      # how far either side of a vertex the turn is measured over
CURVE_SPACING_M = 200.0    # at most one curve warning per this much road
LATERAL_G = 0.22           # comfortable cornering, for the advised speed

SPEED = re.compile(r"^\s*(\d+(?:\.\d+)?)\s*(mph|km/h|kmh|kph)?\s*$", re.IGNORECASE)


def parse_maxspeed(value):
    """A posted limit in whole mph, or 0 when the tag has none that a driver would read as a number."""
    if not value:
        return 0
    match = SPEED.match(value.split(";")[0])
    if not match:
        return 0
    number = float(match.group(1))
    unit = (match.group(2) or "").lower()
    mph = number if unit == "mph" else number * 0.621371  # OSM's default unit is km/h
    mph = int(round(mph / 5.0) * 5) if unit != "mph" else int(round(mph))
    return mph if 5 <= mph <= 85 else 0


def meters_per_tile(latitude):
    return 40_075_016.686 / (1 << ZOOM) * math.cos(math.radians(latitude))


def curve_points(points, meters):
    """Sharp bends along one way. points are tile-space (x, y) or None; returns (x, y, advised_mph) tuples."""
    found = []
    run = []
    for point in points + [None]:
        if point is not None:
            run.append(point)
            continue
        if len(run) >= 3:
            found.extend(_run_curves(run, meters))
        run = []
    return found


def _run_curves(run, meters):
    xs = [p[0] * meters for p in run]
    ys = [p[1] * meters for p in run]
    cumulative = [0.0]
    for i in range(1, len(run)):
        cumulative.append(cumulative[-1] + math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]))
    turns = [0.0] * len(run)
    radii = [float("inf")] * len(run)
    for i in range(1, len(run) - 1):
        j = i - 1
        while j > 0 and cumulative[i] - cumulative[j] < CURVE_WINDOW_M:
            j -= 1
        k = i + 1
        while k < len(run) - 1 and cumulative[k] - cumulative[i] < CURVE_WINDOW_M:
            k += 1
        ax, ay = xs[i] - xs[j], ys[i] - ys[j]
        bx, by = xs[k] - xs[i], ys[k] - ys[i]
        if math.hypot(ax, ay) < 15 or math.hypot(bx, by) < 15:
            continue
        angle = abs(math.degrees(math.atan2(ax * by - ay * bx, ax * bx + ay * by)))
        turns[i] = angle
        chord = math.hypot(xs[k] - xs[j], ys[k] - ys[j])
        area2 = abs(ax * by - ay * bx)  # twice the triangle's area
        if area2 > 1e-6:
            radii[i] = math.hypot(ax, ay) * math.hypot(bx, by) * chord / (2 * area2)
    result = []
    last_at = -1e9
    for i in range(1, len(run) - 1):
        if turns[i] < CURVE_MIN_TURN:
            continue
        lo, hi = max(1, i - 3), min(len(run) - 2, i + 3)
        if turns[i] < max(turns[lo:hi + 1]) or cumulative[i] - last_at < CURVE_SPACING_M:
            continue
        last_at = cumulative[i]
        speed = math.sqrt(LATERAL_G * 9.81 * radii[i]) * 2.23694 if radii[i] != float("inf") else 70
        advised = int(min(70, max(10, round(speed / 5.0) * 5)))
        result.append((run[i][0], run[i][1], advised))
    return result


def point_feature(tags):
    """The (kind, name) a tagged point becomes, or None when it is not something the HUD warns about."""
    highway = tags.get("highway")
    if highway == "traffic_signals":
        return SIGNAL, ""
    if highway == "stop":
        return STOP, ""
    if tags.get("railway") == "level_crossing":
        return CROSSING, ""
    enforcement = tags.get("enforcement")
    if highway == "speed_camera" or enforcement in ("maxspeed", "average_speed", "traffic_signals", "red_light"):
        return CAMERA, "RED LIGHT" if enforcement in ("traffic_signals", "red_light") else "SPEED"
    if highway == "motorway_junction":
        ref = tags.get("ref", "").split(";")[0].strip()
        name = tags.get("name", "").split(";")[0].strip()
        label = " / ".join(part for part in ((f"EXIT {ref}" if ref else ""), name.upper()) if part)
        return (EXIT, label[:36]) if label else None
    if highway in ("rest_area", "services"):
        return REST, (tags.get("name") or ("REST AREA" if highway == "rest_area" else "SERVICES")).upper()[:28]
    return None


def classify(tags):
    """Return (kind, road category) for a way, or (None, None) when the pack does not draw it."""
    highway = tags.get("highway")
    if highway:
        category = highway.removesuffix("_link")
        if category not in ROAD_CLASSES:
            return None, None
        kind = 2 if category in HIGHWAYS else 1 if category in ARTERIALS else 0
        return kind, category if category == highway else None
    if tags.get("railway") == "rail" and not tags.get("service"):
        return RAIL, None
    if tags.get("waterway") in WATER_WAYS:
        return WATER, None
    if tags.get("natural") == "water":
        return WATER, None
    return None, None


def road_name(tags, kind):
    """What the HUD calls this road: the route number on highways, otherwise the street name."""
    ref = tags.get("ref", "").split(";")[0].strip()
    name = tags.get("name", "").split(";")[0].strip()
    chosen = (ref or name) if kind == 2 else (name or ref) if kind == 0 else (ref or name)
    return chosen.upper()[:24]


def read_extract(source, bounds):
    import osmium

    south, west, north, east = bounds
    roads = defaultdict(bytearray)
    ways = defaultdict(bytearray)
    labels = {}
    places = {}
    pois = set()

    def inside(location):
        return location.valid() and south <= location.lat <= north and west <= location.lon <= east

    bits = osmium.osm.osm_entity_bits
    processor = (osmium.FileProcessor(source).with_locations()
                 .with_filter(osmium.filter.EntityFilter(bits.NODE | bits.WAY))
                 .with_filter(osmium.filter.KeyFilter("highway", "railway", "waterway", "natural", "place",
                                                      "amenity", "enforcement")))
    source_date = processor.header.get("osmosis_replication_timestamp")
    for item in processor:
        tags = item.tags
        if isinstance(item, osmium.osm.Node):
            place, amenity = tags.get("place"), tags.get("amenity")
            feature = point_feature(tags) if inside(item.location) else None
            if feature is not None:
                x, y = project(item.location.lat, item.location.lon)
                pois.add((int(x), int(y), feature[0], feature[1], x, y))
            elif place in PLACE_RANK and tags.get("name") and inside(item.location):
                x, y = project(item.location.lat, item.location.lon)
                places[int(x), int(y), tags["name"].upper()[:28], PLACE_RANK[place]] = (x, y)
            elif amenity == "fuel" and inside(item.location):
                x, y = project(item.location.lat, item.location.lon)
                pois.add((int(x), int(y), 1, (tags.get("brand") or tags.get("name") or "FUEL").upper()[:24], x, y))
            continue
        if tags.get("highway") in ("rest_area", "services"):
            first = next((node.location for node in item.nodes if inside(node.location)), None)
            if first is not None:
                feature = point_feature(tags)
                x, y = project(first.lat, first.lon)
                pois.add((int(x), int(y), feature[0], feature[1], x, y))
            continue
        if tags.get("amenity") == "fuel" and not tags.get("highway"):
            first = next((node.location for node in item.nodes if inside(node.location)), None)
            if first is not None:
                x, y = project(first.lat, first.lon)
                pois.add((int(x), int(y), 1, (tags.get("brand") or tags.get("name") or "FUEL").upper()[:24], x, y))
            continue
        kind, category = classify(tags)
        if kind is None:
            continue
        is_road = kind < RAIL
        name = road_name(tags, kind) if is_road else ""
        label = name if category else ""  # Link ramps carry the main road's name but are not labelled.
        mph = parse_maxspeed(tags.get("maxspeed")) if is_road else 0
        points = []
        for node in item.nodes:
            location = node.location
            points.append(project(location.lat, location.lon) if inside(location) else None)
        if kind == WATER and tags.get("natural") == "water":
            if not item.is_closed():
                continue
            seen = [point for point in points if point]
            if not seen or max(max(p[0] for p in seen) - min(p[0] for p in seen),
                               max(p[1] for p in seen) - min(p[1] for p in seen)) < MIN_POND:
                continue
        if category and kind >= 1 and tags.get("junction") not in ("roundabout", "circular"):
            anchor = next((node.location for node in item.nodes if node.location.valid()), None)
            if anchor is not None:
                for x, y, advised in curve_points(points, meters_per_tile(anchor.lat)):
                    pois.add((int(x), int(y), CURVE, str(advised), x, y))
        previous = None
        for point in points:
            if point is not None and previous is not None:
                ax, ay = previous
                bx, by = point
                add_segment(roads, kind, ax, ay, bx, by)
                if is_road and (name or mph):
                    add_segment(ways, (name, mph), ax, ay, bx, by)
                if label:
                    mx, my = (ax + bx) / 2, (ay + by) / 2
                    x, y = int(mx), int(my)
                    key = x, y, label
                    length = (bx - ax) ** 2 + (by - ay) ** 2
                    if key not in labels or labels[key][-1] < length:
                        labels[key] = mx - x, my - y, kind, length
            previous = point
    return roads, ways, labels, places, pois, source_date


def write_pack(output, bounds, args_name, center, source):
    roads, ways, labels, places, pois, source_date = read_extract(source, bounds)
    temporary = output.with_suffix(".tmp")
    temporary.unlink(missing_ok=True)
    with closing(sqlite3.connect(temporary, uri=True)) as database:
        database.executescript(SCHEMA)
        database.executescript(EXTRA_SCHEMA)
        with database:
            database.executemany("INSERT INTO roads VALUES (?, ?, ?, ?)",
                                 ((*key, bytes(data)) for key, data in roads.items()))
            database.executemany("INSERT INTO labels VALUES (?, ?, ?, ?, ?, ?, ?)",
                                 ((*key, *value) for key, value in labels.items()))
            database.executemany("INSERT INTO ways VALUES (?, ?, ?, ?, ?)",
                                 ((x, y, name, mph, bytes(data)) for (x, y, (name, mph)), data in ways.items()))
            database.executemany("INSERT INTO places VALUES (?, ?, ?, ?, ?, ?)",
                                 ((tx, ty, name, kind, x, y) for (tx, ty, name, kind), (x, y) in places.items()))
            database.executemany("INSERT OR IGNORE INTO pois VALUES (?, ?, ?, ?, ?, ?)", pois)
        metadata = {
            "name": args_name, "zoom": ZOOM, "encoding": "uint16",
            "center_lat": center[0], "center_lon": center[1],
            "source": "© OpenStreetMap contributors", "license": "https://www.openstreetmap.org/copyright",
            "source_date": source_date, "extracts": source.name, "detail": "streets,rail,water,limits,places,fuel,ahead",
            **geographic_bounds(database),
        }
        with database:
            database.executemany("INSERT INTO metadata VALUES (?, ?)", metadata.items())
    temporary.replace(output)
    print(f"{output.name}: {output.stat().st_size:,} bytes, {len(ways)} way rows, {len(places)} places, "
          f"{len(pois)} points", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("source", type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--bounds", nargs=4, type=float, required=True, metavar=("S", "W", "N", "E"))
    parser.add_argument("--center", nargs=2, type=float, default=(42.607, -73.700))
    args = parser.parse_args()
    write_pack(args.output, args.bounds, args.name, args.center, args.source)


if __name__ == "__main__":
    main()
