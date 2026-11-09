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

    /** Compass bearing in degrees (0 north, clockwise) of an offset in tile space, where y grows southward. */
    static double bearingDegrees(double dx, double dy) {
        double degrees = Math.toDegrees(Math.atan2(dx, -dy));
        return ((degrees % 360) + 360) % 360;
    }

    static String compass(double degrees) {
        return COMPASS[(int) Math.round(((degrees % 360) + 360) % 360 / 45) % 8];
    }

    /** Compass point for an offset in tile space, where y grows southward. */
    static String bearing(double dx, double dy) {
        return compass(bearingDegrees(dx, dy));
    }

    /** A bearing wrapped into 0 to 360, for the -180 to 180 that Location.bearingTo gives. */
    static double normalizeBearing(double degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    /** Hazards (crossings, cameras, curves) outrank signals and stop signs, which outrank exits and rest areas. */
    static int aheadGroup(int kind) {
        switch (kind) {
            case CROSSING: case CAMERA: case CURVE: return 0;
            case SIGNAL: case STOP: return 1;
            default: return 2;
        }
    }

    /** Smallest angle between two compass bearings, 0 to 180. */
    static double angleDifference(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    // Point kinds in the pack's pois table.
    static final int FUEL = 1, SIGNAL = 2, STOP = 3, CROSSING = 4, CAMERA = 5, EXIT = 6, REST = 7, CURVE = 8;

    /** How far ahead, in metres, a kind of point is worth mentioning at a speed; grows with speed up to a cap. */
    static double aheadRange(int kind, double speedMph) {
        double mps = speedMph * 0.44704;
        switch (kind) {
            case SIGNAL: case STOP: return Math.min(450, Math.max(120, mps * 12));
            case CROSSING: case CAMERA: return Math.min(500, Math.max(150, mps * 15));
            case CURVE: return Math.min(400, Math.max(100, mps * 10));
            case EXIT: return Math.min(1200, Math.max(400, mps * 25));
            case REST: return Math.min(2500, Math.max(800, mps * 40));
            default: return 0;
        }
    }

    /**
     * How close, in metres, a point has to be to the road being driven to count as on it, or 0 for kinds that are
     * not checked. Stop signs on a side street and signals or crossings on a parallel road sit a few metres off the
     * road, so the tolerance is tight; a camera is mounted beside the road, so it gets more room.
     */
    static double onRoadMeters(int kind) {
        switch (kind) {
            case SIGNAL: case STOP: case CROSSING: return 7;
            case CURVE: return 6;
            case CAMERA: return 35;
            default: return 0;
        }
    }

    /** Half-width, in degrees, of the cone ahead that a kind of point has to sit in to count as on the road. */
    static double aheadCone(int kind) {
        switch (kind) {
            case CAMERA: return 30;
            case EXIT: return 35;
            case REST: return 40;
            default: return 25;
        }
    }

    /**
     * Whether a point is worth showing right now: far enough not to be underneath, near enough to matter, in front
     * of the car, and for the kinds that only matter at speed (exits, rest areas, curves) only when going that fast.
     * For a curve, {@code name} is the advised speed in mph.
     */
    static boolean aheadEligible(int kind, String name, double meters, double bearing, double course, double speedMph) {
        if (Double.isNaN(course) || speedMph < 8 || meters < 20 || meters > aheadRange(kind, speedMph)) return false;
        if (angleDifference(course, bearing) > aheadCone(kind)) return false;
        if (kind == EXIT || kind == REST) return speedMph >= 40;
        if (kind == CURVE) {
            try {
                return speedMph >= Integer.parseInt(name) + 4;
            } catch (NumberFormatException bad) {
                return false;
            }
        }
        return true;
    }

    static double metersToMiles(double meters) {
        return meters / 1609.344;
    }
}
