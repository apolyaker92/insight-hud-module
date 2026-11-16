package com.andrew.insighthud;

/**
 * Where the car probably is between GPS fixes. Fixes arrive about once a second, which at highway speed is a jump of
 * 25-30 m on the map. This projects the last fix forward along its course at its speed, and when the next fix lands it
 * eases from where the marker was to the new projection instead of jumping. Pure arithmetic, so it is tested directly.
 */
final class PositionEstimator {
    static final double MIN_SPEED_MPS = 2.0;       // slower than this the car is treated as stopped
    static final double MAX_PROJECT_SECONDS = 3.0;  // never project further than this past a fix
    static final double SETTLE_SECONDS = 0.6;       // how long the marker takes to ease onto a new fix
    static final double SNAP_METERS = 150;          // a bigger difference than this is a real jump: no easing

    private boolean has;
    private double lat, lon, speed, course; // the latest fix; course in degrees, NaN when unknown
    private long fixMs;
    private double easeNorth, easeEast;     // metres still to ease out, measured at easeFrom
    private long easeFrom;

    void clear() {
        has = false;
    }

    boolean has() {
        return has;
    }

    /** Takes a new fix. Times are on one clock (elapsed-realtime milliseconds); fixMs is when the fix was taken. */
    void fix(double latitude, double longitude, double speedMps, double courseDegrees, long fixMs, long nowMs) {
        double shownLat = 0, shownLon = 0;
        boolean wasShowing = has;
        if (wasShowing) {
            double[] shown = at(nowMs);
            shownLat = shown[0];
            shownLon = shown[1];
        }
        lat = latitude;
        lon = longitude;
        speed = Double.isNaN(speedMps) ? 0 : speedMps;
        course = courseDegrees;
        this.fixMs = fixMs;
        has = true;
        easeNorth = easeEast = 0;
        easeFrom = nowMs;
        if (wasShowing) {
            double[] projected = project(nowMs);
            double north = (shownLat - projected[0]) * 110_574;
            double east = (shownLon - projected[1]) * 111_320 * Math.cos(Math.toRadians(projected[0]));
            if (Math.hypot(north, east) <= SNAP_METERS) {
                easeNorth = north;
                easeEast = east;
            }
        }
    }

    /** The estimated position {lat, lon} at a time, or null before the first fix. */
    double[] at(long nowMs) {
        if (!has) return null;
        double[] p = project(nowMs);
        double remaining = 1 - (nowMs - easeFrom) / (SETTLE_SECONDS * 1000.0);
        if (remaining > 0 && (easeNorth != 0 || easeEast != 0)) {
            p[0] += easeNorth * remaining / 110_574;
            p[1] += easeEast * remaining / (111_320 * Math.cos(Math.toRadians(p[0])));
        }
        return p;
    }

    /** True while the position is still being projected forward, so the screen should keep redrawing. */
    boolean moving(long nowMs) {
        return has && speed >= MIN_SPEED_MPS && !Double.isNaN(course)
                && (nowMs - fixMs) / 1000.0 < MAX_PROJECT_SECONDS + SETTLE_SECONDS;
    }

    private double[] project(long nowMs) {
        double seconds = Math.max(0, Math.min(MAX_PROJECT_SECONDS, (nowMs - fixMs) / 1000.0));
        if (speed < MIN_SPEED_MPS || Double.isNaN(course) || seconds == 0) return new double[]{lat, lon};
        double meters = speed * seconds;
        double north = meters * Math.cos(Math.toRadians(course));
        double east = meters * Math.sin(Math.toRadians(course));
        double newLat = lat + north / 110_574;
        return new double[]{newLat, lon + east / (111_320 * Math.cos(Math.toRadians(newLat)))};
    }
}
