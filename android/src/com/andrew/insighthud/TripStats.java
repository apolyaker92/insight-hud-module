package com.andrew.insighthud;

import android.os.Bundle;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

final class TripStats {
    private static final long MAX_SPEED_GAP_MS = 6000;
    private long startedAt = -1;
    private long lastSpeedAt = -1;
    private double lastSpeed;
    private double miles;
    private boolean measuredDistance;
    private double gallons;
    private double peakRpm = Double.NaN;
    private double peakCoolant = Double.NaN;

    void sample(ObdBluetooth.Readings reading, long now) {
        if (startedAt < 0) {
            boolean running = reading.rpm != null && reading.rpm > 0;
            boolean moving = reading.speedMph != null && reading.speedMph > 0;
            if (!running && !moving) return;
            startedAt = now;
        }
        if (reading.rpm != null) peakRpm = peak(peakRpm, reading.rpm);
        if (reading.coolantF != null) peakCoolant = peak(peakCoolant, reading.coolantF);
        if (reading.speedMph == null) {
            disconnect();
        } else {
            long gap = now - lastSpeedAt;
            if (lastSpeedAt >= 0 && gap > 0 && gap <= MAX_SPEED_GAP_MS) {
                miles += (lastSpeed + reading.speedMph) * 0.5 * gap / 3600000;
                if (reading.fuelGph != null) gallons += reading.fuelGph * gap / 3600000;
                measuredDistance = true;
            }
            lastSpeed = reading.speedMph;
            lastSpeedAt = now;
        }
    }

    private double peak(double previous, double value) {
        return Double.isNaN(previous) ? value : Math.max(previous, value);
    }

    void disconnect() { lastSpeedAt = -1; }

    Bundle save() {
        Bundle state = new Bundle();
        state.putLong("start", startedAt);
        state.putDouble("miles", miles);
        state.putDouble("gallons", gallons);
        state.putBoolean("distance", measuredDistance);
        state.putDouble("rpm", peakRpm);
        state.putDouble("coolant", peakCoolant);
        return state;
    }

    void restore(Bundle state) {
        if (state == null) return;
        startedAt = state.getLong("start", -1);
        miles = state.getDouble("miles");
        gallons = state.getDouble("gallons");
        measuredDistance = state.getBoolean("distance");
        peakRpm = state.getDouble("rpm", Double.NaN);
        peakCoolant = state.getDouble("coolant", Double.NaN);
    }

    JSONObject finish(long now, long endedAt) {
        if (startedAt < 0) return null;
        return report(endedAt, Math.max(0, (now - startedAt) / 1000),
                measuredDistance ? miles : Double.NaN, peakRpm, peakCoolant,
                measuredDistance && gallons > 0.01 ? miles / gallons : Double.NaN);
    }

    static JSONObject report(long endedAt, long seconds, double miles, double rpm, double coolant, double mpg) {
        JSONObject result = new JSONObject();
        try {
            result.put("ended", endedAt);
            result.put("seconds", seconds);
            if (!Double.isNaN(miles)) result.put("miles", miles);
            if (!Double.isNaN(rpm)) result.put("rpm", rpm);
            if (!Double.isNaN(coolant)) result.put("coolant", coolant);
            if (!Double.isNaN(mpg)) result.put("mpg", mpg);
        } catch (JSONException unexpected) {
            throw new IllegalStateException(unexpected);
        }
        return result;
    }

    static String time(JSONObject report) {
        long seconds = report.optLong("seconds");
        return String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    static String value(JSONObject report, String key, String format) {
        return report.has(key) ? String.format(Locale.US, format, report.optDouble(key)) : "--";
    }
}
