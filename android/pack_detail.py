"""Build a detailed pack from one Geofabrik PBF: roads with street names, rail and water, plus the data
for road info on the HUD: speed limits, the road you are on, the nearest town and the nearest fuel.

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
                                                      "amenity")))
    source_date = processor.header.get("osmosis_replication_timestamp")
    for item in processor:
        tags = item.tags
        if isinstance(item, osmium.osm.Node):
            place, amenity = tags.get("place"), tags.get("amenity")
            if place in PLACE_RANK and tags.get("name") and inside(item.location):
                x, y = project(item.location.lat, item.location.lon)
                places[int(x), int(y), tags["name"].upper()[:28], PLACE_RANK[place]] = (x, y)
            elif amenity == "fuel" and inside(item.location):
                x, y = project(item.location.lat, item.location.lon)
                pois.add((int(x), int(y), 1, (tags.get("brand") or tags.get("name") or "FUEL").upper()[:24], x, y))
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
            "source_date": source_date, "extracts": source.name, "detail": "streets,rail,water,limits,places,fuel",
            **geographic_bounds(database),
        }
        with database:
            database.executemany("INSERT INTO metadata VALUES (?, ?)", metadata.items())
    temporary.replace(output)
    print(f"{output.name}: {output.stat().st_size:,} bytes, {len(ways)} way rows, {len(places)} places, "
          f"{len(pois)} fuel", flush=True)


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
