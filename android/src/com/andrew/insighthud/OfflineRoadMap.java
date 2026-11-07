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

/** Loads only the road tiles around the display, independently of the GPS provider. */
final class OfflineRoadMap {
    private static final String TAG = "HudOfflineMap";
    private static final int ZOOM = 14;
    private static final int WORLD = 1 << ZOOM;
    private static final int TILE_RADIUS = 2;
    private static final String EXTERNAL_PACK = "hud-roads.db";

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
        final Path[] roads = {new Path(), new Path(), new Path()};
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

    private final Context context;
    private final Runnable changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SQLiteDatabase database; // Owned by the worker.
    private boolean compactGeometry;
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
                compactGeometry = loaded.compactGeometry;
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
        if (external != null) {
            File pack = new File(external, EXTERNAL_PACK);
            if (pack.isFile()) {
                try {
                    return readRegion(pack);
                } catch (RuntimeException invalid) {
                    Log.w(TAG, "Using bundled roads because the installed map pack could not be opened", invalid);
                }
            }
        }
        return readRegion(bundledPack());
    }

    private Region readRegion(File file) {
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
            Region loaded = new Region(metadata);
            database = candidate;
            return loaded;
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
        Scene result = new Scene(x, y);
        String[] args = {String.valueOf(x - TILE_RADIUS), String.valueOf(x + TILE_RADIUS),
                String.valueOf(y - TILE_RADIUS), String.valueOf(y + TILE_RADIUS)};
        String tileRange = " WHERE x BETWEEN ? AND ? AND y BETWEEN ? AND ?";
        try (Cursor cursor = database.rawQuery("SELECT x, y, kind, geometry FROM roads" + tileRange, args)) {
            while (cursor.moveToNext()) {
                int dx = cursor.getInt(0) - x;
                int dy = cursor.getInt(1) - y;
                Path path = result.roads[cursor.getInt(2)];
                ByteBuffer geometry = ByteBuffer.wrap(cursor.getBlob(3)).order(ByteOrder.LITTLE_ENDIAN);
                int segmentBytes = compactGeometry ? 8 : 16;
                while (geometry.remaining() >= segmentBytes) {
                    path.moveTo(dx + readOffset(geometry), dy + readOffset(geometry));
                    path.lineTo(dx + readOffset(geometry), dy + readOffset(geometry));
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

    private float readOffset(ByteBuffer geometry) {
        return compactGeometry ? (geometry.getShort() & 0xffff) / 65535f : geometry.getFloat();
    }

    void close() {
        closed = true;
        worker.execute(() -> {
            if (database != null) database.close();
        });
        worker.shutdown();
    }
}
