package com.andrew.insighthud;

import android.location.Location;
import android.util.Log;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One CSV per drive in the app's external files folder (logs/drive-DATE.csv), a row a second while the car is
 * powered and readings are arriving. A new file starts after a long gap or when the HUD parks.
 */
final class DriveLog {
    private static final String TAG = "HudDriveLog";
    private static final long MIN_GAP_MS = 1000;
    private static final long NEW_FILE_GAP_MS = 5 * 60_000;
    private static final String HEADER = "time,lat,lon,gps_mph,obd_mph,rpm,throttle,load,coolant_f,intake_f,mpg,limit_mph,road\n";

    private final File folder;
    private BufferedWriter out;
    private long lastSampleAt;
    private int sinceFlush;
    private boolean broken;

    DriveLog(File folder) {
        this.folder = folder;
    }

    /** Records at most one row a second. {@code now} is any monotonic clock in milliseconds. */
    void sample(long now, ObdBluetooth.Readings readings, Location gps, Double gpsMph,
                OfflineRoadMap.RoadInfo info) {
        if (broken || readings == null || now - lastSampleAt < MIN_GAP_MS) return;
        if (out != null && now - lastSampleAt > NEW_FILE_GAP_MS) close();
        lastSampleAt = now;
        try {
            if (out == null) open();
            StringBuilder row = new StringBuilder(160);
            row.append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date())).append(',');
            if (gps != null) {
                row.append(String.format(Locale.US, "%.6f,%.6f", gps.getLatitude(), gps.getLongitude()));
            } else {
                row.append(',');
            }
            row.append(',').append(number(gpsMph)).append(',').append(number(readings.speedMph))
                    .append(',').append(number(readings.rpm)).append(',').append(number(readings.throttle))
                    .append(',').append(number(readings.load)).append(',').append(number(readings.coolantF))
                    .append(',').append(number(readings.intakeF)).append(',').append(number(readings.mpg))
                    .append(',').append(info == null || info.limitMph <= 0 ? "" : String.valueOf(info.limitMph))
                    .append(',').append(info == null || info.road == null ? "" : info.road.replace(',', ' '))
                    .append('\n');
            out.write(row.toString());
            if (++sinceFlush >= 5) {
                out.flush();
                sinceFlush = 0;
            }
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Drive log stopped", error);
            broken = true;
            close();
        }
    }

    private void open() throws IOException {
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create " + folder);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        out = new BufferedWriter(new FileWriter(new File(folder, "drive-" + stamp + ".csv")));
        out.write(HEADER);
        sinceFlush = 0;
    }

    private static String number(Double value) {
        return value == null ? "" : String.format(Locale.US, "%.1f", value);
    }

    void close() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
            // Nothing more to do with a log that will not close.
        }
        out = null;
    }
}
