package com.andrew.insighthud;

final class PositionEstimator {
    static final double MIN_SPEED_MPS = 2.0;
    static final double MAX_PROJECT_SECONDS = 3.0;
    static final double SETTLE_SECONDS = 0.6;
    static final double SNAP_METERS = 150;

    private boolean has;
    private double lat, lon, speed, course;
    private long fixMs;
    private double easeNorth, easeEast;
    private long easeFrom;

    void clear() {
        has = false;
    }

    boolean has() {
        return has;
    }

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
