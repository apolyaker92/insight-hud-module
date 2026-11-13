package com.andrew.insighthud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A route to follow: a line, the manoeuvres along it, and where the car is on it. Pure geometry with no Android in
 * it, so it can be tested directly. Routes arrive from the phone (as a line plus steps) or from the offline router.
 */
final class RouteTrack {
    static final int STRAIGHT = 0, SLIGHT_RIGHT = 1, RIGHT = 2, SHARP_RIGHT = 3, UTURN = 4,
            SHARP_LEFT = 5, LEFT = 6, SLIGHT_LEFT = 7, ARRIVE = 8, DEPART = 9, ROUNDABOUT = 10;

    static final double OFF_ROUTE_METERS = 45;
    static final long OFF_ROUTE_MS = 4000;
    static final double ARRIVED_METERS = 30;

    /** One manoeuvre: what to do, on what street, and how far along the route it happens. */
    static final class Step {
        final int kind;
        final String street;
        final String instruction;
        final double atMeters;

        Step(int kind, String street, String instruction, double atMeters) {
            this.kind = kind;
            this.street = street == null ? "" : street;
            this.instruction = instruction == null ? "" : instruction;
            this.atMeters = atMeters;
        }

        /** The line to show: the supplied instruction, or one built from the kind and street. */
        String label() {
            if (!instruction.isEmpty()) return instruction.toUpperCase(Locale.US);
            String verb;
            switch (kind) {
                case SLIGHT_RIGHT: verb = "BEAR RIGHT"; break;
                case RIGHT: verb = "TURN RIGHT"; break;
                case SHARP_RIGHT: verb = "SHARP RIGHT"; break;
                case UTURN: verb = "MAKE A U-TURN"; break;
                case SHARP_LEFT: verb = "SHARP LEFT"; break;
                case LEFT: verb = "TURN LEFT"; break;
                case SLIGHT_LEFT: verb = "BEAR LEFT"; break;
                case ARRIVE: return "ARRIVE" + (street.isEmpty() ? "" : " AT " + street.toUpperCase(Locale.US));
                case ROUNDABOUT: verb = "ENTER ROUNDABOUT"; break;
                default: verb = "CONTINUE"; break;
            }
            return street.isEmpty() ? verb : verb + " ONTO " + street.toUpperCase(Locale.US);
        }
    }

    /** A step as it arrives from outside: any of the position fields may be missing (NaN). */
    static final class StepSpec {
        String instruction = "", street = "", type = "", modifier = "";
        double atMeters = Double.NaN, lat = Double.NaN, lon = Double.NaN;
    }

    final String name;
    private final double metersPerLon;
    final double durationSeconds;
    final double[] lat, lon;
    final double lengthMeters;
    final Step[] steps;
    private final double[] x, y, cumulative;

    // Where the car is.
    private int segment;
    private double along;
    private double offMeters;
    private long offSince;
    private boolean arrived;

    RouteTrack(String name, double[] lat, double[] lon, List<StepSpec> specs, double durationSeconds) {
        if (lat.length != lon.length || lat.length < 2) throw new IllegalArgumentException("A route needs two points");
        this.name = name == null ? "" : name;
        this.durationSeconds = durationSeconds;
        this.lat = lat;
        this.lon = lon;
        double meanLat = 0;
        for (double value : lat) meanLat += value;
        meanLat /= lat.length;
        double metersPerLon = 111_320 * Math.cos(Math.toRadians(meanLat));
        x = new double[lat.length];
        y = new double[lat.length];
        cumulative = new double[lat.length];
        for (int i = 0; i < lat.length; i++) {
            x[i] = lon[i] * metersPerLon;
            y[i] = lat[i] * 110_574;
            if (i > 0) cumulative[i] = cumulative[i - 1] + Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
        }
        lengthMeters = cumulative[lat.length - 1];
        this.metersPerLon = metersPerLon;
        this.steps = resolve(specs);
    }

    // ---- building the steps ----

    private Step[] resolve(List<StepSpec> specs) {
        List<Step> result = new ArrayList<>();
        if (specs != null) {
            for (StepSpec spec : specs) {
                double at = spec.atMeters;
                if (Double.isNaN(at) && !Double.isNaN(spec.lat) && !Double.isNaN(spec.lon)) at = snap(spec.lat, spec.lon);
                if (Double.isNaN(at)) continue;
                at = Math.max(0, Math.min(lengthMeters, at));
                result.add(new Step(kindFor(spec, at), spec.street, spec.instruction, at));
            }
        }
        result.sort((a, b) -> Double.compare(a.atMeters, b.atMeters));
        if (result.isEmpty() || result.get(result.size() - 1).kind != ARRIVE) {
            result.add(new Step(ARRIVE, name, "", lengthMeters));
        }
        return result.toArray(new Step[0]);
    }

    private int kindFor(StepSpec spec, double at) {
        String type = spec.type.toLowerCase(Locale.US);
        String modifier = spec.modifier.toLowerCase(Locale.US);
        if (type.equals("arrive")) return ARRIVE;
        if (type.equals("depart")) return DEPART;
        if (type.contains("roundabout") || type.contains("rotary")) return ROUNDABOUT;
        switch (modifier) {
            case "uturn": return UTURN;
            case "sharp right": return SHARP_RIGHT;
            case "right": return RIGHT;
            case "slight right": return SLIGHT_RIGHT;
            case "straight": return STRAIGHT;
            case "slight left": return SLIGHT_LEFT;
            case "left": return LEFT;
            case "sharp left": return SHARP_LEFT;
            default: return turnKind(turnDegrees(at));
        }
    }

    /** Distance along the route, in metres, of the point on it nearest a position. */
    double snap(double latitude, double longitude) {
        double px = longitude * metersPerLon, py = latitude * 110_574;
        double best = Double.POSITIVE_INFINITY, bestAlong = 0;
        for (int i = 0; i < x.length - 1; i++) {
            double t = fraction(i, px, py);
            double d = Math.hypot(x[i] + t * (x[i + 1] - x[i]) - px, y[i] + t * (y[i + 1] - y[i]) - py);
            if (d < best) {
                best = d;
                bestAlong = cumulative[i] + t * (cumulative[i + 1] - cumulative[i]);
            }
        }
        return bestAlong;
    }

    // ---- geometry helpers ----

    private double fraction(int i, double px, double py) {
        double dx = x[i + 1] - x[i], dy = y[i + 1] - y[i];
        double n = dx * dx + dy * dy;
        return n == 0 ? 0 : Math.max(0, Math.min(1, ((px - x[i]) * dx + (py - y[i]) * dy) / n));
    }

    /** The point a given distance along the route, as {x, y} in metres. */
    private double[] pointAt(double meters) {
        meters = Math.max(0, Math.min(lengthMeters, meters));
        int i = Arrays.binarySearch(cumulative, meters);
        if (i < 0) i = -i - 2;
        i = Math.max(0, Math.min(x.length - 2, i));
        double span = cumulative[i + 1] - cumulative[i];
        double t = span == 0 ? 0 : (meters - cumulative[i]) / span;
        return new double[]{x[i] + t * (x[i + 1] - x[i]), y[i] + t * (y[i + 1] - y[i])};
    }

    /** How far the route turns right (positive) or left (negative) at a point, from 30 m before to 30 m after. */
    double turnDegrees(double at) {
        double[] before = pointAt(at - 30), here = pointAt(at), after = pointAt(at + 30);
        double in = Math.toDegrees(Math.atan2(here[0] - before[0], here[1] - before[1]));
        double out = Math.toDegrees(Math.atan2(after[0] - here[0], after[1] - here[1]));
        double delta = (out - in) % 360;
        if (delta > 180) delta -= 360;
        if (delta < -180) delta += 360;
        return delta;
    }

    /** The arrow for a turn of this many degrees (positive is right). */
    static int turnKind(double degrees) {
        double a = Math.abs(degrees);
        boolean right = degrees >= 0;
        if (a < 20) return STRAIGHT;
        if (a < 50) return right ? SLIGHT_RIGHT : SLIGHT_LEFT;
        if (a < 125) return right ? RIGHT : LEFT;
        if (a < 165) return right ? SHARP_RIGHT : SHARP_LEFT;
        return UTURN;
    }

    // ---- following the car ----

    /** Moves the car along the route to the point nearest a GPS fix. */
    void update(double latitude, double longitude, long nowMs) {
        double px = longitude * metersPerLon, py = latitude * 110_574;
        int bestSegment = segment;
        double best = Double.POSITIVE_INFINITY, bestAlong = along;
        int last = x.length - 2;
        // Look a little behind and a good way ahead first; the car moves forward along its route.
        int from = Math.max(0, segment - 6);
        for (int i = from; i <= last; i++) {
            if (cumulative[i] > along + 800) break;
            double t = fraction(i, px, py);
            double d = Math.hypot(x[i] + t * (x[i + 1] - x[i]) - px, y[i] + t * (y[i + 1] - y[i]) - py);
            if (d < best) {
                best = d;
                bestSegment = i;
                bestAlong = cumulative[i] + t * (cumulative[i + 1] - cumulative[i]);
            }
        }
        if (best > OFF_ROUTE_METERS) { // lost or rejoined elsewhere: look at the whole route
            for (int i = 0; i <= last; i++) {
                double t = fraction(i, px, py);
                double d = Math.hypot(x[i] + t * (x[i + 1] - x[i]) - px, y[i] + t * (y[i + 1] - y[i]) - py);
                if (d < best - 15) {
                    best = d;
                    bestSegment = i;
                    bestAlong = cumulative[i] + t * (cumulative[i + 1] - cumulative[i]);
                }
            }
        }
        offMeters = best;
        if (best <= OFF_ROUTE_METERS) {
            segment = bestSegment;
            along = bestAlong;
            offSince = 0;
        } else if (offSince == 0) {
            offSince = nowMs;
        }
        if (lengthMeters - along <= ARRIVED_METERS) arrived = true;
    }

    boolean offRoute(long nowMs) {
        return offSince != 0 && nowMs - offSince >= OFF_ROUTE_MS;
    }

    boolean arrived() { return arrived; }

    double alongMeters() { return along; }
    double metersAtPoint(int index) { return cumulative[index]; }

    double offMeters() { return offMeters; }

    double remainingMeters() { return Math.max(0, lengthMeters - along); }

    /** The next manoeuvre still ahead of the car, or null once only the arrival is left. */
    Step nextStep() {
        for (Step step : steps) {
            if (step.kind == DEPART) continue;
            if (step.atMeters > along + 3) return step;
        }
        return null;
    }

    double metersToNext() {
        Step next = nextStep();
        return (next == null ? lengthMeters : next.atMeters) - along;
    }

    /** Estimated seconds left, scaling the route's own duration by the distance still to go. */
    double remainingSeconds() {
        return durationSeconds > 0 && lengthMeters > 0 ? durationSeconds * remainingMeters() / lengthMeters : Double.NaN;
    }
}
