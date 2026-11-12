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
    private static final int KEEP = 12;
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
            try (Cursor cursor = database.rawQuery("SELECT data FROM tiles WHERE level = ? AND x = ? AND y = ?",
                    new String[]{String.valueOf(level), String.valueOf(x), String.valueOf(y)})) {
                if (cursor.moveToFirst()) tile = RouteTile.decode(cursor.getBlob(0));
            } catch (IOException | RuntimeException broken) {
                Log.w(TAG, "Bad tile " + key, broken);
            }
            cache.put(key, tile);
        }
        return tile == MISSING ? null : tile;
    }
}
