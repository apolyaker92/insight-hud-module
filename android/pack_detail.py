"""Build a detailed local pack from one Geofabrik PBF: every road with street names, plus rail and water.

The result is installed beside the wide road pack as hud-local.db. Inside its bounds the HUD draws from it;
outside, it falls back to the wide pack.
"""

import argparse
import sqlite3
from collections import defaultdict
from contextlib import closing
from pathlib import Path

from pack_pbf_roads import SCHEMA, add_segment, geographic_bounds
from pack_roads import ARTERIALS, HIGHWAYS, WORLD, ZOOM, project

LOCAL_ROADS = {"unclassified", "residential", "living_street"}
ROAD_CLASSES = HIGHWAYS | ARTERIALS | LOCAL_ROADS
RAIL, WATER = 3, 4
WATER_WAYS = {"river", "canal"}
MIN_POND = 0.05  # Closed water shorter than this many tiles across (about 250 m) is clutter.


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


def read_extract(source, bounds):
    import osmium

    south, west, north, east = bounds
    roads = defaultdict(bytearray)
    labels = {}
    processor = (osmium.FileProcessor(source).with_locations()
                 .with_filter(osmium.filter.EntityFilter(osmium.osm.WAY))
                 .with_filter(osmium.filter.KeyFilter("highway", "railway", "waterway", "natural")))
    source_date = processor.header.get("osmosis_replication_timestamp")
    for way in processor:
        kind, category = classify(way.tags)
        if kind is None:
            continue
        is_road = kind < RAIL
        name = (way.tags.get("ref") or way.tags.get("name", "")).split(";")[0].strip().upper()[:24] \
            if is_road and category else ""
        points = []
        for node in way.nodes:
            location = node.location
            inside = location.valid() and south <= location.lat <= north and west <= location.lon <= east
            points.append(project(location.lat, location.lon) if inside else None)
        if kind == WATER and way.tags.get("natural") == "water":
            if not way.is_closed():
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
                if name:
                    mx, my = (ax + bx) / 2, (ay + by) / 2
                    x, y = int(mx), int(my)
                    key = x, y, name
                    length = (bx - ax) ** 2 + (by - ay) ** 2
                    if key not in labels or labels[key][-1] < length:
                        labels[key] = mx - x, my - y, kind, length
            previous = point
    return roads, labels, source_date


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("source", type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--bounds", nargs=4, type=float, required=True, metavar=("S", "W", "N", "E"))
    parser.add_argument("--center", nargs=2, type=float, default=(42.607, -73.700))
    args = parser.parse_args()
    temporary = args.output.with_suffix(".tmp")
    temporary.unlink(missing_ok=True)
    with closing(sqlite3.connect(temporary, uri=True)) as database:
        database.executescript(SCHEMA)
        roads, labels, source_date = read_extract(args.source, args.bounds)
        with database:
            database.executemany("INSERT INTO roads VALUES (?, ?, ?, ?)",
                                 ((*key, bytes(data)) for key, data in roads.items()))
            database.executemany("INSERT INTO labels VALUES (?, ?, ?, ?, ?, ?, ?)",
                                 ((*key, *value) for key, value in labels.items()))
        metadata = {
            "name": args.name, "zoom": ZOOM, "encoding": "uint16",
            "center_lat": args.center[0], "center_lon": args.center[1],
            "source": "© OpenStreetMap contributors", "license": "https://www.openstreetmap.org/copyright",
            "source_date": source_date, "extracts": args.source.name, "detail": "streets,rail,water",
            **geographic_bounds(database),
        }
        with database:
            database.executemany("INSERT INTO metadata VALUES (?, ?)", metadata.items())
    temporary.replace(args.output)
    print(f"{args.output.name}: {args.output.stat().st_size:,} bytes", flush=True)


if __name__ == "__main__":
    main()
