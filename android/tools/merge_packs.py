"""Merge detailed packs from pack_detail.py (one per state) into a single pack."""

import argparse
import sqlite3
from contextlib import closing
from pathlib import Path

from pack_detail import EXTRA_SCHEMA
from pack_pbf_roads import SCHEMA, geographic_bounds
from pack_roads import ZOOM

DETAIL = "streets,rail,water,limits,places,fuel,ahead"


def merge(database, source):
    database.execute("ATTACH DATABASE ? AS part", (source.resolve().as_uri() + "?mode=ro",))
    with database:
        # Tiles that straddle a border arrive from both states: roads and labels combine, the rest keep the first copy.
        database.execute("""
            INSERT INTO roads SELECT * FROM part.roads WHERE true
            ON CONFLICT (x, y, kind) DO UPDATE SET geometry = CAST(roads.geometry || excluded.geometry AS BLOB)
        """)
        database.execute("""
            INSERT INTO labels SELECT * FROM part.labels WHERE true
            ON CONFLICT (x, y, name) DO UPDATE SET offset_x=excluded.offset_x,
                offset_y=excluded.offset_y, kind=excluded.kind, length=excluded.length
            WHERE excluded.length > labels.length
        """)
        database.execute("INSERT OR IGNORE INTO ways SELECT * FROM part.ways")
        database.execute("INSERT OR IGNORE INTO places SELECT * FROM part.places")
        database.execute("INSERT OR IGNORE INTO pois SELECT * FROM part.pois")
        date = database.execute("SELECT value FROM part.metadata WHERE key='source_date'").fetchone()[0]
    database.execute("DETACH DATABASE part")
    return date


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("sources", nargs="+", type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--center", nargs=2, type=float, default=(42.607, -73.700))
    args = parser.parse_args()
    temporary = args.output.with_suffix(".tmp")
    temporary.unlink(missing_ok=True)
    with closing(sqlite3.connect(temporary, uri=True)) as database:
        database.executescript(SCHEMA)
        database.executescript(EXTRA_SCHEMA)
        dates = [merge(database, source) for source in args.sources]
        metadata = {
            "name": args.name, "zoom": ZOOM, "encoding": "uint16",
            "center_lat": args.center[0], "center_lon": args.center[1],
            "source": "© OpenStreetMap contributors", "license": "https://www.openstreetmap.org/copyright",
            "source_date": min(dates), "extracts": ", ".join(source.name for source in args.sources),
            "detail": DETAIL, **geographic_bounds(database),
        }
        with database:
            database.executemany("INSERT INTO metadata VALUES (?, ?)", metadata.items())
    temporary.replace(args.output)
    print(f"{args.output.name}: {args.output.stat().st_size:,} bytes", flush=True)


if __name__ == "__main__":
    main()
