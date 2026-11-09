package com.andrew.insighthud;

/** Pure geometry for road info: how far a GPS fix is from stored road segments, and which way something lies. */
final class RoadLookup {
    /** A fix farther than this from every stored road is treated as off the mapped roads. */
    static final double ROAD_MATCH_METERS = 35;
    private static final double EARTH_CIRCUMFERENCE_M = 40_075_016.686;
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private RoadLookup() { }

    /** Ground size of one z14 tile at a latitude. */
    static double metersPerTile(double latitude, int world) {
        return EARTH_CIRCUMFERENCE_M / world * Math.cos(Math.toRadians(latitude));
    }

    /**
     * Squared distance, in tile units, from a point to the nearest segment of a road blob. The blob is the pack's
     * little-endian uint16 format: four values per segment (ax, ay, bx, by), each a fraction of a tile.
     */
    static double nearestSquared(byte[] geometry, double px, double py) {
        double best = Double.POSITIVE_INFINITY;
        for (int offset = 0; offset + 8 <= geometry.length; offset += 8) {
            double ax = unsigned(geometry, offset) / 65535.0;
            double ay = unsigned(geometry, offset + 2) / 65535.0;
            double bx = unsigned(geometry, offset + 4) / 65535.0;
            double by = unsigned(geometry, offset + 6) / 65535.0;
            double dx = bx - ax;
            double dy = by - ay;
            double length = dx * dx + dy * dy;
            double t = length == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / length));
            double cx = ax + t * dx - px;
            double cy = ay + t * dy - py;
            best = Math.min(best, cx * cx + cy * cy);
        }
        return best;
    }

    private static int unsigned(byte[] data, int offset) {
        return (data[offset] & 0xff) | (data[offset + 1] & 0xff) << 8;
    }

    /** Compass point for an offset in tile space, where y grows southward. */
    static String bearing(double dx, double dy) {
        double degrees = Math.toDegrees(Math.atan2(dx, -dy));
        return COMPASS[(int) Math.round(((degrees % 360) + 360) % 360 / 45) % 8];
    }

    static double metersToMiles(double meters) {
        return meters / 1609.344;
    }
}
