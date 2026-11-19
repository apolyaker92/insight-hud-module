package com.andrew.insighthud;

import android.location.Location;
import android.util.Log;
import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;

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

    static String trackJson(File folder, String id) {
        if (id == null) {
            String[] names = folder == null ? null : folder.list((dir, name) -> isDriveLog(name));
            JSONArray drives = new JSONArray();
            if (names != null) {
                java.util.Arrays.sort(names);
                for (String name : names) drives.put(name);
            }
            return "{\"drives\":" + drives + "}";
        }
        if (folder == null || !isDriveLog(id)) return null;
        JSONArray lines = new JSONArray();
        JSONArray line = new JSONArray();
        double lastLat = Double.NaN, lastLon = Double.NaN;
        long lastTime = 0;
        SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        try (BufferedReader input = new BufferedReader(new FileReader(new File(folder, id)))) {
            input.readLine();
            String row;
            while ((row = input.readLine()) != null) {
                String[] fields = row.split(",", 4);
                if (fields.length < 4) continue;
                try {
                    long time = timestamp.parse(fields[0]).getTime();
                    double lat = Double.parseDouble(fields[1]), lon = Double.parseDouble(fields[2]);
                    if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
                    if (time - lastTime > 20_000 || time < lastTime) {
                        if (line.length() > 1) lines.put(line);
                        line = new JSONArray();
                    }
                    lastTime = time;
                    if (line.length() == 0 || Math.abs(lastLat - lat) + Math.abs(lastLon - lon) >= 0.00006) {
                        line.put(new JSONArray().put(lat).put(lon));
                        lastLat = lat;
                        lastLon = lon;
                    }
                } catch (NumberFormatException | java.text.ParseException | org.json.JSONException ignored) {
                }
            }
            if (line.length() > 1) lines.put(line);
            return "{\"lines\":" + lines + "}";
        } catch (IOException failed) {
            return null;
        }
    }

    private static boolean isDriveLog(String name) {
        return name.matches("drive-[0-9]{8}-[0-9]{6}\\.csv");
    }

    void close() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
    }
}
