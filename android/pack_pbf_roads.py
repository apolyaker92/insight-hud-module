"""Build or merge indexed HUD road packs from Geofabrik OSM PBF extracts."""

import argparse
import math
import sqlite3
import struct
from collections import defaultdict
from contextlib import closing
from pathlib import Path

from pack_roads import ARTERIALS, HIGHWAYS, WORLD, ZOOM, clip, project

LOCAL_ROADS = {"unclassified", "residential", "living_street"}
ROAD_CLASSES = HIGHWAYS | ARTERIALS | LOCAL_ROADS
SEGMENT = struct.Struct("<HHHH")
SCHEMA = """
    CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    CREATE TABLE roads (x INTEGER, y INTEGER, kind INTEGER, geometry BLOB,
        PRIMARY KEY (x, y, kind)) WITHOUT ROWID;
    CREATE TABLE labels (x INTEGER, y INTEGER, name TEXT, offset_x REAL,
        offset_y REAL, kind INTEGER, length REAL, PRIMARY KEY (x, y, name)) WITHOUT ROWID;
"""


def quantize(value):
    return min(65535, max(0, round(value * 65535)))


def add_segment(roads, kind, ax, ay, bx, by):
    tx, ty, ex, ey = int(ax), int(ay), int(bx), int(by)
    if tx == ex and ty == ey:
        coordinates = quantize(ax - tx), quantize(ay - ty), quantize(bx - tx), quantize(by - ty)
        if coordinates[:2] != coordinates[2:]:
            roads[tx, ty, kind].extend(SEGMENT.pack(*coordinates))
        return
    for x in range(min(tx, ex), max(tx, ex) + 1):
        for y in range(min(ty, ey), max(ty, ey) + 1):
            segment = clip(ax, ay, bx, by, x, y)
            if segment:
                coordinates = tuple(map(quantize, segment))
                if coordinates[:2] != coordinates[2:]:
                    roads[x, y, kind].extend(SEGMENT.pack(*coordinates))


def read_extract(source):
    import osmium

    roads = defaultdict(bytearray)
    labels = {}
    processor = (osmium.FileProcessor(source).with_locations()
                 .with_filter(osmium.filter.EntityFilter(osmium.osm.WAY))
                 .with_filter(osmium.filter.KeyFilter("highway")))
    source_date = processor.header.get("osmosis_replication_timestamp")
    for way in processor:
        highway = way.tags["highway"]
        category = highway.removesuffix("_link")
        if category not in ROAD_CLASSES:
            continue
        kind = 2 if category in HIGHWAYS else 1 if category in ARTERIALS else 0
        name = ((way.tags.get("ref") or way.tags.get("name", "")).split(";")[0].strip().upper()[:24]
                if kind and highway == category else "")
        previous = None
        for node in way.nodes:
            if not node.location.valid():
                previous = None
                continue
            point = project(node.lat, node.lon)
            if previous is not None:
                ax, ay = previous
                bx, by = point
                add_segment(roads, kind, ax, ay, bx, by)
                if name:
                    mx, my = (ax + bx) / 2, (ay + by) / 2
                    x, y = int(mx), int(my)
                    key = x, y, name
                    length = (bx - ax) ** 2 + (by - ay) ** 2
                    if key not in labels or labels[key][-1] < length:
                        labels[key] = mx - x, my - y, kind, length
            previous = point
    return roads, labels, source_date


def geographic_bounds(database):
    min_x, min_y, max_x, max_y = database.execute(
        "SELECT min(x), min(y), max(x), max(y) FROM roads"
    ).fetchone()
    if min_x is None:
        raise ValueError("Map contains no roads")

    def latitude(y):
        return math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / WORLD))))

    return {
        "west": min_x / WORLD * 360 - 180, "east": (max_x + 1) / WORLD * 360 - 180,
        "north": latitude(min_y), "south": latitude(max_y + 1),
    }


def merge_extract(database, source):
    database.execute("ATTACH DATABASE ? AS region", (source.resolve().as_uri() + "?mode=ro",))
    with database:
        database.execute("""
            INSERT INTO roads SELECT * FROM region.roads WHERE true
            ON CONFLICT (x, y, kind) DO UPDATE
                SET geometry = CAST(roads.geometry || excluded.geometry AS BLOB)
        """)
        database.execute("""
            INSERT INTO labels SELECT * FROM region.labels WHERE true
            ON CONFLICT (x, y, name) DO UPDATE SET offset_x=excluded.offset_x,
                offset_y=excluded.offset_y, kind=excluded.kind, length=excluded.length
            WHERE excluded.length > labels.length
        """)
        date = database.execute("SELECT value FROM region.metadata WHERE key='source_date'").fetchone()[0]
    database.execute("DETACH DATABASE region")
    return date


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("sources", nargs="+", type=Path)
    parser.add_argument("--merge", action="store_true", help="Merge already converted packs")
    parser.add_argument("--name", required=True)
    parser.add_argument("--center", nargs=2, type=float, default=(42.607, -73.700))
    args = parser.parse_args()
    if not args.merge and len(args.sources) != 1:
        parser.error("Convert one PBF per invocation, then merge the resulting packs")
    temporary = args.output.with_suffix(".tmp")
    temporary.unlink(missing_ok=True)
    with closing(sqlite3.connect(temporary, uri=True)) as database:
        database.executescript(SCHEMA)
        if args.merge:
            dates = [merge_extract(database, source) for source in args.sources]
            source_date = min(dates)
        else:
            roads, labels, source_date = read_extract(args.sources[0])
            with database:
                database.executemany("INSERT INTO roads VALUES (?, ?, ?, ?)",
                                     ((*key, data) for key, data in roads.items()))
                database.executemany("INSERT INTO labels VALUES (?, ?, ?, ?, ?, ?, ?)",
                                     ((*key, *value) for key, value in labels.items()))
        metadata = {
            "name": args.name, "zoom": ZOOM, "encoding": "uint16",
            "center_lat": args.center[0], "center_lon": args.center[1],
            "source": "© OpenStreetMap contributors", "license": "https://www.openstreetmap.org/copyright",
            "source_date": source_date, "extracts": ", ".join(source.name for source in args.sources),
            **geographic_bounds(database),
        }
        with database:
            database.executemany("INSERT INTO metadata VALUES (?, ?)", metadata.items())
    temporary.replace(args.output)
    print(f"{args.output.name}: {args.output.stat().st_size:,} bytes", flush=True)


if __name__ == "__main__":
    main()
