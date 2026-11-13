package com.andrew.insighthud;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** The routing graph on the phone: hud-route.db, read a tile at a time with a few kept in memory. */
final class RouteDatabase implements Router.Tiles {
    private static final String TAG = "HudRoute";
    private static final int KEEP = 40; // more than one long search touches, or tiles are decoded again and again
    private static final int SLICE = 512 * 1024;
    private static final RouteTile MISSING = new RouteTile(new long[0], new int[0], new int[0], new int[]{0},
            new long[0], new int[0], new int[0], new float[0], new float[0], new byte[0], new byte[0], new byte[0],
            new byte[0], new int[0], new int[]{0}, new int[0], new long[0], new long[0], new long[0], new byte[0]);

    private final SQLiteDatabase database;
    private final Map<String, RouteTile> cache = new LinkedHashMap<String, RouteTile>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RouteTile> eldest) {
            return size() > KEEP;
        }
    };

    private RouteDatabase(SQLiteDatabase database) {
        this.database = database;
    }

    static RouteDatabase open(java.io.File file) {
        if (!file.isFile()) return null;
        try {
            return new RouteDatabase(SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY));
        } catch (RuntimeException broken) {
            Log.w(TAG, "Could not open " + file, broken);
            return null;
        }
    }

    void close() {
        database.close();
    }

    @Override
    public synchronized RouteTile tile(int level, int x, int y) {
        String key = level + ":" + x + ":" + y;
        RouteTile tile = cache.get(key);
        if (tile == null) {
            tile = MISSING;
            try {
                byte[] blob = blob(level, x, y);
                if (blob != null) tile = RouteTile.decode(blob);
            } catch (IOException | RuntimeException broken) {
                Log.w(TAG, "Bad tile " + key, broken);
            }
            cache.put(key, tile);
        }
        return tile == MISSING ? null : tile;
    }

    /** A tile's bytes, fetched in slices because one row can be bigger than a cursor window (about 2 MB). */
    private byte[] blob(int level, int x, int y) {
        String[] where = {String.valueOf(level), String.valueOf(x), String.valueOf(y)};
        int size;
        try (Cursor cursor = database.rawQuery("SELECT length(data) FROM tiles WHERE level = ? AND x = ? AND y = ?", where)) {
            if (!cursor.moveToFirst()) return null;
            size = cursor.getInt(0);
        }
        byte[] blob = new byte[size];
        for (int offset = 0; offset < size; offset += SLICE) {
            try (Cursor cursor = database.rawQuery("SELECT substr(data, " + (offset + 1) + ", " + SLICE
                    + ") FROM tiles WHERE level = ? AND x = ? AND y = ?", where)) {
                if (!cursor.moveToFirst()) return null;
                byte[] part = cursor.getBlob(0);
                System.arraycopy(part, 0, blob, offset, part.length);
            }
        }
        return blob;
    }
}
