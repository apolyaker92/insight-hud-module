"""Merge several hud-route.db files (one per state) into one, so routes can cross borders.

Tiles that appear in more than one input are decoded, their edges combined (an edge present in both is kept once) and
re-encoded. Usage: python merge_route_graphs.py OUT.db IN1.db IN2.db ... [--name NAME]
"""
import argparse
import sqlite3
import sys
import time
from pathlib import Path

from build_route_graph import decode_tile, encode_tile


def tile_edges(tile):
    """The edges of a decoded tile as the tuples encode_tile takes, and its banned turns."""
    names = tile["names"]
    edges = []
    ids = tile["id"]
    for i in range(len(ids)):
        for e in range(int(tile["first"][i]), int(tile["first"][i + 1])):
            offset = int(tile["name"][e])
            name = names[offset:names.index(b"\0", offset)].decode("utf-8") if offset >= 0 else ""
            lo, hi = int(tile["shape_first"][e]), int(tile["shape_first"][e + 1])
            shape = [(int(tile["shape"][2 * k]), int(tile["shape"][2 * k + 1])) for k in range(lo, hi)]
            edges.append((int(ids[i]), int(tile["lat"][i]), int(tile["lon"][i]), int(tile["target"][e]),
                          int(tile["tlat"][e]), int(tile["tlon"][e]), float(tile["cost"][e]), float(tile["length"][e]),
                          int(tile["cls"][e]), int(tile["flags"][e]), int(tile["bs"][e]), int(tile["be"][e]),
                          name, shape))
    banned = {(int(b), int(v), int(a)) for v, b, a in zip(tile["via"], tile["before"], tile["after"])}
    return edges, banned


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("output", type=Path)
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--name", default="Merged")
    args = parser.parse_args()
    started = time.time()
    sources = [sqlite3.connect(path) for path in args.inputs]
    keys = set()
    for source in sources:
        keys.update(source.execute("SELECT level, x, y FROM tiles"))
    temporary = args.output.with_suffix(".tmp")
    temporary.unlink(missing_ok=True)
    out = sqlite3.connect(temporary)
    out.executescript("""
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE tiles (level INTEGER, x INTEGER, y INTEGER, data BLOB, PRIMARY KEY (level, x, y)) WITHOUT ROWID;
    """)
    total_edges = 0
    for count, key in enumerate(sorted(keys), 1):
        seen = {}
        banned = set()
        for source in sources:
            row = source.execute("SELECT data FROM tiles WHERE level = ? AND x = ? AND y = ?", key).fetchone()
            if row is None:
                continue
            edges, bans = tile_edges(decode_tile(row[0]))
            banned |= bans
            for edge in edges:
                seen.setdefault((edge[0], edge[3], round(edge[7])), edge)
        total_edges += len(seen)
        out.execute("INSERT INTO tiles VALUES (?, ?, ?, ?)", (*key, encode_tile(list(seen.values()), banned)))
        if count % 200 == 0:
            print(f"{count}/{len(keys)} tiles  ({time.time() - started:.0f}s)", flush=True)
    first = dict(sources[0].execute("SELECT key, value FROM meta"))
    first.update({"name": args.name, "edges": str(total_edges), "built": time.strftime("%Y-%m-%d"),
                  "sources": ", ".join(p.stem for p in args.inputs)})
    out.executemany("INSERT INTO meta VALUES (?, ?)", list(first.items()))
    out.commit()
    out.close()
    temporary.replace(args.output)
    print(f"{args.output.name}: {len(keys)} tiles, {total_edges} edges, {args.output.stat().st_size:,} bytes "
          f"({time.time() - started:.0f}s)")


if __name__ == "__main__":
    sys.exit(main())
