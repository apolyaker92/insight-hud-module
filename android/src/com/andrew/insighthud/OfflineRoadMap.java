package com.andrew.insighthud;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Loads only the road tiles around the display, independently of the GPS provider. */
final class OfflineRoadMap {
    private static final String TAG = "HudOfflineMap";
    private static final int ZOOM = 14;
    private static final int WORLD = 1 << ZOOM;
    private static final int TILE_RADIUS = 2;
    private static final String EXTERNAL_PACK = "hud-roads.db";
    /** A detailed pack for home turf; used inside its bounds, the wide pack elsewhere. */
    private static final String LOCAL_PACK = "hud-local.db";
    static final int KINDS = 5; // local, arterial, highway, rail, water

    static final class Region {
        final String name;
        final double latitude, longitude, south, west, north, east;
        final boolean compactGeometry;

        Region(Map<String, String> metadata) {
            if (!String.valueOf(ZOOM).equals(metadata.get("zoom"))) {
                throw new IllegalArgumentException("Unsupported offline map zoom");
            }
            String encoding = metadata.getOrDefault("encoding", "float32");
            if (!"float32".equals(encoding) && !"uint16".equals(encoding)) {
                throw new IllegalArgumentException("Unsupported offline map geometry");
            }
            compactGeometry = "uint16".equals(encoding);
            name = metadata.get("name");
            latitude = number(metadata, "center_lat");
            longitude = number(metadata, "center_lon");
            south = number(metadata, "south");
            west = number(metadata, "west");
            north = number(metadata, "north");
            east = number(metadata, "east");
            if (name == null || name.isEmpty() || !contains(latitude, longitude)
                    || south < -85 || north > 85 || west < -180 || east > 180) {
                throw new IllegalArgumentException("Invalid offline map bounds");
            }
        }

        private static double number(Map<String, String> metadata, String key) {
            return Double.parseDouble(metadata.get(key));
        }

        boolean contains(double lat, double lon) {
            return lat >= south && lat <= north && lon >= west && lon <= east;
        }
    }

    /** What the pack knows about where a fix is: the road, its posted limit, the town and the nearest fuel. */
    static final class RoadInfo {
        final String road;      // null when the fix is not on a mapped road
        final int limitMph;     // 0 when no limit is posted in the data
        final String place;
        final String fuelName;  // null when none is in range
        final double fuelMiles;
        final String fuelBearing;
        /** The nearest thing worth a warning on the road ahead (a RoadLookup point kind), or 0 for none. */
        final int aheadKind;
        final String aheadName;
        final double aheadMeters;

        RoadInfo(String road, int limitMph, String place, String fuelName, double fuelMiles, String fuelBearing) {
            this(road, limitMph, place, fuelName, fuelMiles, fuelBearing, 0, null, Double.NaN);
        }

        RoadInfo(String road, int limitMph, String place, String fuelName, double fuelMiles, String fuelBearing,
                 int aheadKind, String aheadName, double aheadMeters) {
            this.road = road;
            this.limitMph = limitMph;
            this.place = place;
            this.fuelName = fuelName;
            this.fuelMiles = fuelMiles;
            this.fuelBearing = fuelBearing;
            this.aheadKind = aheadKind;
            this.aheadName = aheadName;
            this.aheadMeters = aheadMeters;
        }
    }

    static final class Label {
        final String name;
        final float x, y;

        Label(String name, float x, float y) {
            this.name = name;
            this.x = x;
            this.y = y;
        }
    }

    static final class Scene {
        final int x, y;
        final Path[] roads = {new Path(), new Path(), new Path(), new Path(), new Path()};
        final List<Label> labels = new ArrayList<>();

        Scene(int x, int y) {
            this.x = x;
            this.y = y;
        }

        boolean covers(double viewX, double viewY) {
            return Math.abs(viewX - x - 0.5) <= 1 && Math.abs(viewY - y - 0.5) <= 1;
        }

        boolean hasRoads() {
            return !roads[0].isEmpty() || !roads[1].isEmpty() || !roads[2].isEmpty();
        }
    }

    /** One opened road database with the tile range it covers. */
    private static final class Pack {
        final SQLiteDatabase database;
        final Region region;
        final boolean compact;
        final boolean info; // has the ways, places and pois tables
        final int minX, maxX, minY, maxY;

        Pack(SQLiteDatabase database, Region region, boolean info) {
            this.database = database;
            this.region = region;
            this.compact = region.compactGeometry;
            this.info = info;
            minX = (int) Math.floor(tileX(region.west));
            maxX = (int) Math.floor(tileX(region.east));
            minY = (int) Math.floor(tileY(region.north));
            maxY = (int) Math.floor(tileY(region.south));
        }

        boolean covers(int x, int y, int radius) {
            return x - radius >= minX && x + radius <= maxX && y - radius >= minY && y + radius <= maxY;
        }
    }

    private final Context context;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Pack wide, local; // Owned by the worker.
    private boolean opening, closed, failed, sceneFailed;
    private static final long RETRY_MS = 5000;
    private int requestedX = -1, requestedY = -1;
    private Region region;
    private Scene scene;

    OfflineRoadMap(Context context, Runnable changed) {
        this.context = context.getApplicationContext();
        this.changed = changed;
    }

    Region region() { return region; }
    Scene scene() { return scene; }
    boolean failed() { return failed || sceneFailed; }

    static double tileX(double longitude) {
        return (longitude + 180) / 360 * WORLD;
    }

    static double tileY(double latitude) {
        double radians = Math.toRadians(Math.max(-85, Math.min(85, latitude)));
        return (1 - Math.log(Math.tan(Math.PI / 4 + radians / 2)) / Math.PI) / 2 * WORLD;
    }

    void open() {
        if (opening || closed || failed || region != null) return;
        opening = true;
        worker.execute(() -> {
            try {
                Region loaded = openRegion();
                main.post(() -> {
                    if (closed) return;
                    region = loaded;
                    changed.run();
                });
            } catch (IOException | RuntimeException error) {
                reportFailure(error);
            }
        });
    }

    void request(double viewX, double viewY) {
        if (region == null || failed || closed) return;
        int x = (int) Math.floor(viewX);
        int y = (int) Math.floor(viewY);
        if (requestedX == x && requestedY == y) return;
        requestedX = x;
        requestedY = y;
        worker.execute(() -> {
            try {
                Scene loaded = readScene(x, y);
                main.post(() -> {
                    if (closed || requestedX != x || requestedY != y) return;
                    scene = loaded;
                    sceneFailed = false;
                    changed.run();
                });
            } catch (RuntimeException error) {
                // A tile read can fail transiently (storage I/O); retry rather than disabling the map.
                Log.w(TAG, "Could not read offline road tiles", error);
                main.post(() -> {
                    if (closed || requestedX != x || requestedY != y) return;
                    sceneFailed = true;
                    changed.run();
                    main.postDelayed(() -> {
                        if (closed) return;
                        requestedX = requestedY = -1; // Forget the failed request so the next refresh retries it.
                        changed.run();
                    }, RETRY_MS);
                });
            }
        });
    }

    private void reportFailure(Exception error) {
        Log.w(TAG, "Could not read the offline road pack", error);
        main.post(() -> {
            if (closed) return;
            failed = true;
            changed.run();
        });
    }

    private Region openRegion() throws IOException {
        File external = context.getExternalFilesDir(null);
        Log.i(TAG, "Map pack folder " + external + " wide="
                + (external == null ? -1 : new File(external, EXTERNAL_PACK).length()));
        if (external != null) {
            File detailed = new File(external, LOCAL_PACK);
            if (detailed.isFile()) {
                try {
                    local = readPack(detailed);
                } catch (RuntimeException invalid) {
                    Log.w(TAG, "Ignoring the local map pack because it could not be opened", invalid);
                }
            }
            File pack = new File(external, EXTERNAL_PACK);
            if (pack.isFile()) {
                try {
                    wide = readPack(pack);
                    return wide.region;
                } catch (RuntimeException invalid) {
                    Log.w(TAG, "Using bundled roads because the installed map pack could not be opened", invalid);
                }
            }
        }
        wide = readPack(bundledPack());
        return wide.region;
    }

    private Pack readPack(File file) {
        SQLiteDatabase candidate = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            Map<String, String> metadata = new HashMap<>();
            try (Cursor cursor = candidate.rawQuery("SELECT key, value FROM metadata", null)) {
                while (cursor.moveToNext()) metadata.put(cursor.getString(0), cursor.getString(1));
            }
            try (Cursor roads = candidate.rawQuery("SELECT x, y, kind, geometry FROM roads LIMIT 0", null);
                 Cursor labels = candidate.rawQuery(
                         "SELECT x, y, name, offset_x, offset_y, kind FROM labels LIMIT 0", null)) {
                roads.getColumnCount();
                labels.getColumnCount();
            }
            boolean info;
            try (Cursor tables = candidate.rawQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN ('ways', 'places', 'pois')",
                    null)) {
                info = tables.moveToFirst() && tables.getInt(0) == 3;
            }
            return new Pack(candidate, new Region(metadata), info);
        } catch (RuntimeException error) {
            candidate.close();
            throw error;
        }
    }

    private File bundledPack() throws IOException {
        File file = new File(context.getFilesDir(), "albany-roads-v1.db");
        if (!file.isFile()) {
            AtomicFile target = new AtomicFile(file);
            try (InputStream source = context.getResources().openRawResource(R.raw.albany_roads)) {
                FileOutputStream output = target.startWrite();
                try {
                    byte[] buffer = new byte[32 * 1024];
                    for (int read; (read = source.read(buffer)) != -1;) output.write(buffer, 0, read);
                    target.finishWrite(output);
                } catch (IOException | RuntimeException error) {
                    target.failWrite(output);
                    throw error;
                }
            }
        }
        return file;
    }

    private Scene readScene(int x, int y) {
        Pack pack = local != null && local.covers(x, y, TILE_RADIUS) ? local : wide;
        SQLiteDatabase database = pack.database;
        boolean compactGeometry = pack.compact;
        Scene result = new Scene(x, y);
        String[] args = {String.valueOf(x - TILE_RADIUS), String.valueOf(x + TILE_RADIUS),
                String.valueOf(y - TILE_RADIUS), String.valueOf(y + TILE_RADIUS)};
        String tileRange = " WHERE x BETWEEN ? AND ? AND y BETWEEN ? AND ?";
        try (Cursor cursor = database.rawQuery("SELECT x, y, kind, geometry FROM roads" + tileRange, args)) {
            while (cursor.moveToNext()) {
                int dx = cursor.getInt(0) - x;
                int dy = cursor.getInt(1) - y;
                int kind = cursor.getInt(2);
                if (kind < 0 || kind >= KINDS) continue;
                Path path = result.roads[kind];
                ByteBuffer geometry = ByteBuffer.wrap(cursor.getBlob(3)).order(ByteOrder.LITTLE_ENDIAN);
                int segmentBytes = compactGeometry ? 8 : 16;
                while (geometry.remaining() >= segmentBytes) {
                    path.moveTo(dx + readOffset(geometry, compactGeometry), dy + readOffset(geometry, compactGeometry));
                    path.lineTo(dx + readOffset(geometry, compactGeometry), dy + readOffset(geometry, compactGeometry));
                }
            }
        }
        try (Cursor cursor = database.rawQuery(
                "SELECT x, y, name, offset_x, offset_y FROM labels" + tileRange + " ORDER BY kind DESC, name",
                args)) {
            while (cursor.moveToNext()) {
                result.labels.add(new Label(cursor.getString(2), cursor.getInt(0) - x + cursor.getFloat(3),
                        cursor.getInt(1) - y + cursor.getFloat(4)));
            }
        }
        return result;
    }

    /** Looks up the road under a fix off the main thread; the callback gets null when there is nothing to say. */
    void lookup(double latitude, double longitude, double course, double speedMph, Consumer<RoadInfo> done) {
        if (region == null || failed || closed) return;
        worker.execute(() -> {
            RoadInfo info = null;
            try {
                info = readInfo(latitude, longitude, course, speedMph);
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not look up the road", error);
            }
            RoadInfo result = info;
            main.post(() -> {
                if (!closed) done.accept(result);
            });
        });
    }

    private static final int PLACE_RADIUS = 6;   // tiles, roughly 11 km
    private static final int FUEL_RADIUS = 14;   // tiles, roughly 25 km
    private static final double PLACE_MAX_METERS = 9000;

    private RoadInfo readInfo(double latitude, double longitude, double course, double speedMph) {
        double fx = tileX(longitude);
        double fy = tileY(latitude);
        int tx = (int) Math.floor(fx);
        int ty = (int) Math.floor(fy);
        Pack pack = null;
        for (Pack candidate : new Pack[]{local, wide}) {
            if (candidate != null && candidate.info && candidate.covers(tx, ty, 1)) {
                pack = candidate;
                break;
            }
        }
        if (pack == null) return null;
        SQLiteDatabase database = pack.database;
        double meters = RoadLookup.metersPerTile(latitude, WORLD);

        String road = null;
        int limit = 0;
        double best = Double.POSITIVE_INFINITY;
        String[] around = {String.valueOf(tx - 1), String.valueOf(tx + 1), String.valueOf(ty - 1),
                String.valueOf(ty + 1)};
        try (Cursor cursor = database.rawQuery("SELECT x, y, name, mph, geometry FROM ways "
                + "WHERE x BETWEEN ? AND ? AND y BETWEEN ? AND ?", around)) {
            while (cursor.moveToNext()) {
                double distance = RoadLookup.nearestSquared(cursor.getBlob(4),
                        fx - cursor.getInt(0), fy - cursor.getInt(1));
                if (distance < best) {
                    best = distance;
                    road = cursor.getString(2);
                    limit = cursor.getInt(3);
                }
            }
        }
        if (Math.sqrt(best) * meters > RoadLookup.ROAD_MATCH_METERS) {
            road = null;
            limit = 0;
        } else if (road != null && road.isEmpty()) {
            road = null;
        }

        String place = null;
        double placeBest = Double.POSITIVE_INFINITY;
        int placeKind = 99;
        String[] nearby = {String.valueOf(tx - PLACE_RADIUS), String.valueOf(tx + PLACE_RADIUS),
                String.valueOf(ty - PLACE_RADIUS), String.valueOf(ty + PLACE_RADIUS)};
        try (Cursor cursor = database.rawQuery("SELECT name, kind, x, y FROM places "
                + "WHERE tx BETWEEN ? AND ? AND ty BETWEEN ? AND ?", nearby)) {
            while (cursor.moveToNext()) {
                int kind = Math.min(cursor.getInt(1), 3);
                double dx = cursor.getDouble(2) - fx;
                double dy = cursor.getDouble(3) - fy;
                double distance = Math.sqrt(dx * dx + dy * dy) * meters;
                if (distance > PLACE_MAX_METERS) continue;
                // A town or village within reach beats a nearer hamlet.
                boolean better = (kind <= 2) != (placeKind <= 2) ? kind <= 2 : distance < placeBest;
                if (better) {
                    place = cursor.getString(0);
                    placeBest = distance;
                    placeKind = kind;
                }
            }
        }

        String fuel = null;
        double fuelBest = Double.POSITIVE_INFINITY;
        String bearing = null;
        String[] farther = {String.valueOf(tx - FUEL_RADIUS), String.valueOf(tx + FUEL_RADIUS),
                String.valueOf(ty - FUEL_RADIUS), String.valueOf(ty + FUEL_RADIUS)};
        try (Cursor cursor = database.rawQuery("SELECT name, x, y FROM pois WHERE kind = 1 "
                + "AND tx BETWEEN ? AND ? AND ty BETWEEN ? AND ?", farther)) {
            while (cursor.moveToNext()) {
                double dx = cursor.getDouble(1) - fx;
                double dy = cursor.getDouble(2) - fy;
                double distance = Math.sqrt(dx * dx + dy * dy) * meters;
                if (distance < fuelBest) {
                    fuelBest = distance;
                    fuel = cursor.getString(0);
                    bearing = RoadLookup.bearing(dx, dy);
                }
            }
        }
        // The nearest eligible point in each priority group; the first group with anything wins.
        int[] groupKind = new int[3];
        String[] groupName = new String[3];
        double[] groupBest = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        if (!Double.isNaN(course) && speedMph >= 8) {
            String[] near = {String.valueOf(tx - 2), String.valueOf(tx + 2), String.valueOf(ty - 2),
                    String.valueOf(ty + 2)};
            try (Cursor cursor = database.rawQuery("SELECT kind, name, x, y FROM pois WHERE kind BETWEEN 2 AND 8 "
                    + "AND tx BETWEEN ? AND ? AND ty BETWEEN ? AND ?", near)) {
                while (cursor.moveToNext()) {
                    double dx = cursor.getDouble(2) - fx;
                    double dy = cursor.getDouble(3) - fy;
                    double distance = Math.sqrt(dx * dx + dy * dy) * meters;
                    int kind = cursor.getInt(0);
                    int group = RoadLookup.aheadGroup(kind);
                    if (distance < groupBest[group] && RoadLookup.aheadEligible(kind, cursor.getString(1), distance,
                            RoadLookup.bearingDegrees(dx, dy), course, speedMph)) {
                        groupBest[group] = distance;
                        groupKind[group] = kind;
                        groupName[group] = cursor.getString(1);
                    }
                }
            }
        }
        int chosen = groupKind[0] != 0 ? 0 : groupKind[1] != 0 ? 1 : groupKind[2] != 0 ? 2 : -1;
        return new RoadInfo(road, limit, place, fuel,
                fuel == null ? Double.NaN : RoadLookup.metersToMiles(fuelBest), bearing,
                chosen < 0 ? 0 : groupKind[chosen], chosen < 0 ? null : groupName[chosen],
                chosen < 0 ? Double.NaN : groupBest[chosen]);
    }

    private static float readOffset(ByteBuffer geometry, boolean compactGeometry) {
        return compactGeometry ? (geometry.getShort() & 0xffff) / 65535f : geometry.getFloat();
    }

    void close() {
        closed = true;
        worker.execute(() -> {
            if (wide != null) wide.database.close();
            if (local != null) local.database.close();
        });
        worker.shutdown();
    }
}
