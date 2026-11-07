"""Convert an Overpass road-geometry export into an offline HUD map."""

import argparse
import json
import math
import sqlite3
import struct
from collections import defaultdict
from contextlib import closing
from pathlib import Path

ZOOM = 14
WORLD = 1 << ZOOM
HIGHWAYS = {"motorway", "trunk"}
ARTERIALS = {"primary", "secondary", "tertiary"}


def project(lat, lon):
    x = (lon + 180) / 360 * WORLD
    y = (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * WORLD
    return x, y


def clip(ax, ay, bx, by, x, y):
    """Clip a segment to a unit square in tile coordinates."""
    dx, dy = bx - ax, by - ay
    first, last = 0.0, 1.0
    for start, delta, edge in ((ax, dx, x), (ay, dy, y)):
        if delta == 0:
            if not edge <= start < edge + 1:
                return None
            continue
        enter, leave = sorted(((edge - start) / delta, (edge + 1 - start) / delta))
        first, last = max(first, enter), min(last, leave)
    if first >= last:
        return None
    return ax + first * dx - x, ay + first * dy - y, ax + last * dx - x, ay + last * dy - y


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--bounds", nargs=4, type=float, required=True, metavar=("S", "W", "N", "E"))
    parser.add_argument("--center", nargs=2, type=float, required=True, metavar=("LAT", "LON"))
    parser.add_argument("--name", required=True)
    args = parser.parse_args()

    source = json.loads(args.source.read_text(encoding="utf-8"))
    if source.get("remark") or not source.get("elements"):
        raise ValueError("Incomplete or empty Overpass response")
    south, west, north, east = args.bounds
    left, top = map(math.floor, project(north, west))
    right, bottom = map(math.floor, project(south, east))
    geometry = defaultdict(bytearray)
    labels = {}
    for way in source["elements"]:
        tags = way.get("tags", {})
        category = tags.get("highway", "").removesuffix("_link")
        kind = 2 if category in HIGHWAYS else 1 if category in ARTERIALS else 0
        name = (tags.get("ref") or tags.get("name", "")).split(";")[0].strip().upper()[:24]
        label_road = kind and name and not tags.get("highway", "").endswith("_link")
        points = [project(p["lat"], p["lon"]) for p in way.get("geometry", [])]
        for (ax, ay), (bx, by) in zip(points, points[1:]):
            for x in range(max(left, math.floor(min(ax, bx))), min(right, math.floor(max(ax, bx))) + 1):
                for y in range(max(top, math.floor(min(ay, by))), min(bottom, math.floor(max(ay, by))) + 1):
                    segment = clip(ax, ay, bx, by, x, y)
                    if segment is not None:
                        geometry[x, y, kind].extend(struct.pack("<ffff", *segment))
            if label_road:
                mid_x, mid_y = (ax + bx) / 2, (ay + by) / 2
                tile_x, tile_y = math.floor(mid_x), math.floor(mid_y)
                if left <= tile_x <= right and top <= tile_y <= bottom:
                    key = tile_x, tile_y, name
                    length = math.hypot(bx - ax, by - ay)
                    if key not in labels or labels[key][-1] < length:
                        labels[key] = mid_x - tile_x, mid_y - tile_y, kind, length

    if not geometry:
        raise ValueError("No road geometry in the requested bounds")
    temporary = args.output.with_suffix(".tmp")
    temporary.unlink(missing_ok=True)
    with closing(sqlite3.connect(temporary)) as database, database:
        database.executescript("""
            CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE roads (x INTEGER, y INTEGER, kind INTEGER, geometry BLOB,
                PRIMARY KEY (x, y, kind)) WITHOUT ROWID;
            CREATE TABLE labels (x INTEGER, y INTEGER, name TEXT, offset_x REAL,
                offset_y REAL, kind INTEGER, PRIMARY KEY (x, y, name)) WITHOUT ROWID;
        """)
        metadata = {
            "name": args.name, "zoom": ZOOM, "center_lat": args.center[0], "center_lon": args.center[1],
            "south": south, "west": west, "north": north, "east": east,
            "source": "© OpenStreetMap contributors", "license": "https://www.openstreetmap.org/copyright",
            "source_date": source.get("osm3s", {}).get("timestamp_osm_base", ""),
        }
        database.executemany("INSERT INTO metadata VALUES (?, ?)", metadata.items())
        database.executemany("INSERT INTO roads VALUES (?, ?, ?, ?)",
                             ((*key, data) for key, data in geometry.items()))
        database.executemany("INSERT INTO labels VALUES (?, ?, ?, ?, ?, ?)",
                             ((*key, *value[:3]) for key, value in labels.items()))
    temporary.replace(args.output)
    print(f"{len(geometry)} tile layers, {len(labels)} labels, {args.output.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
