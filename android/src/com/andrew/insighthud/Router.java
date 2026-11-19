package com.andrew.insighthud;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

final class Router {
    interface Tiles {
        RouteTile tile(int level, int x, int y);
    }

    static final double LOCAL_DEG = 0.25, HIGH_DEG = 1.0;
    static final double DETAIL_METERS = 10_000;
    static final double ALL_DETAIL_METERS = 40_000;
    static final double FAR_TRIP_METERS = 120_000;
    static final int FAR_CLASS = 2;
    static final double FASTEST_METERS_PER_SECOND = 30.0;
    static final int MAX_NODES = 4_000_000;
    private static final double INF = Double.POSITIVE_INFINITY;

    static final class Result {
        String error;
        double[] lat, lon;
        List<RouteTrack.StepSpec> steps = new ArrayList<>();
        double seconds, meters;
    }

    private static final class Node {
        final long id;
        final int lat, lon;
        double g = INF;
        Node parent;
        RouteTile tile;
        int edge;
        int arrive;
        boolean closed;

        Node(long id, int lat, int lon) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static final class Entry {
        final Node node;
        final double g, f;

        Entry(Node node, double g, double f) {
            this.node = node;
            this.g = g;
            this.f = f;
        }
    }

    private static final class Snap {
        RouteTile tile;
        int edge;
        double fraction, distance, projLat, projLon, bearing;
    }

    private static final class EdgeRef {
        final RouteTile tile;
        final int edge;

        EdgeRef(RouteTile tile, int edge) {
            this.tile = tile;
            this.edge = edge;
        }
    }

    private final Tiles tiles;
    private final Map<Long, Node> nodes = new HashMap<>();

    Router(Tiles tiles) {
        this.tiles = tiles;
    }

    Result route(double fromLat, double fromLon, double heading, double toLat, double toLon) {
        Result result = new Result();
        nodes.clear();
        List<Snap> starts = snap(fromLat, fromLon, heading);
        if (starts.isEmpty()) return fail(result, "No road near the start");
        List<Snap> goals = snap(toLat, toLon, Double.NaN);
        if (goals.isEmpty()) return fail(result, "No road near the destination");

        double straight = meters(fromLat, fromLon, toLat, toLon);
        PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        for (Snap s : starts) {
            Node node = node(s.tile.target[s.edge], s.tile.targetLat[s.edge], s.tile.targetLon[s.edge]);
            double g = (1 - s.fraction) * s.tile.cost[s.edge];
            if (g < node.g) {
                node.g = g;
                node.parent = null;
                node.tile = s.tile;
                node.edge = s.edge;
                node.arrive = s.tile.bearingEnd[s.edge] & 255;
                open.add(new Entry(node, g, g + heuristic(node, toLat, toLon)));
            }
        }
        Map<Long, Snap> goalAt = new HashMap<>();
        for (Snap g : goals) goalAt.put(g.tile.id[g.tile.startNodeOf(g.edge)], g);

        double bestTotal = INF;
        Node bestNode = null;
        Snap bestGoal = null;
        int expanded = 0;
        while (!open.isEmpty()) {
            Entry entry = open.poll();
            Node u = entry.node;
            if (u.closed || entry.g > u.g) continue;
            if (entry.f >= bestTotal) break;
            u.closed = true;
            if (++expanded > MAX_NODES) return fail(result, "Route too long to search");
            Snap goal = goalAt.get(u.id);
            if (goal != null && !(u.parent != null && goal.tile.banned(u.parent.id, u.id, goal.tile.target[goal.edge]))) {
                double total = u.g + goal.fraction * goal.tile.cost[goal.edge];
                if (total < bestTotal) {
                    bestTotal = total;
                    bestNode = u;
                    bestGoal = goal;
                }
            }
            double degrees = u.lat * 1e-7, lonDegrees = u.lon * 1e-7;
            boolean detail = straight < ALL_DETAIL_METERS || meters(degrees, lonDegrees, fromLat, fromLon) < DETAIL_METERS
                    || meters(degrees, lonDegrees, toLat, toLon) < DETAIL_METERS;
            int level = detail ? 0 : 1;
            RouteTile tile = tileAt(level, degrees, lonDegrees);
            if (tile == null) continue;
            int index = tile.nodeIndex(u.id);
            if (index < 0) continue;
            long before = u.parent == null ? Long.MIN_VALUE : u.parent.id;
            int worst = !detail && straight > FAR_TRIP_METERS ? FAR_CLASS : 255;
            for (int e = tile.first[index]; e < tile.first[index + 1]; e++) {
                if ((tile.roadClass[e] & 255) > worst) continue;
                long target = tile.target[e];
                if (before != Long.MIN_VALUE && tile.banned(before, u.id, target)) continue;
                double turn = 0;
                if (u.tile != null) {
                    double t = signedTurn(u.arrive, tile.bearingStart[e] & 255);
                    turn = Math.abs(t) > 160 ? 60 : t < -45 ? 6 : t > 45 ? 3 : 0;
                }
                double g = u.g + tile.cost[e] + turn;
                Node v = node(target, tile.targetLat[e], tile.targetLon[e]);
                if (g < v.g && !v.closed) {
                    v.g = g;
                    v.parent = u;
                    v.tile = tile;
                    v.edge = e;
                    v.arrive = tile.bearingEnd[e] & 255;
                    open.add(new Entry(v, g, g + heuristic(v, toLat, toLon)));
                }
            }
        }
        if (bestNode == null) return fail(result, "No route found");
        build(result, starts, bestNode, bestGoal, bestTotal);
        return result;
    }

    private static Result fail(Result result, String message) {
        result.error = message;
        return result;
    }

    private Node node(long id, int lat, int lon) {
        Node node = nodes.get(id);
        if (node == null) {
            node = new Node(id, lat, lon);
            nodes.put(id, node);
        }
        return node;
    }

    private RouteTile tileAt(int level, double lat, double lon) {
        double size = level == 0 ? LOCAL_DEG : HIGH_DEG;
        return tiles.tile(level, (int) Math.floor((lon + 180.0) / size), (int) Math.floor((lat + 90.0) / size));
    }

    private static double heuristic(Node node, double lat, double lon) {
        return meters(node.lat * 1e-7, node.lon * 1e-7, lat, lon) / FASTEST_METERS_PER_SECOND;
    }

    static double meters(double lat1, double lon1, double lat2, double lon2) {
        double dy = (lat2 - lat1) * 110_574, dx = (lon2 - lon1) * 111_320 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.hypot(dx, dy);
    }

    static double signedTurn(int in, int out) {
        double delta = (((out - in) * 360.0 / 256.0) % 360.0 + 360.0) % 360.0;
        return delta > 180 ? delta - 360 : delta;
    }

    private double[][] polyline(RouteTile tile, int edge) {
        int start = tile.startNodeOf(edge);
        int shapeFrom = tile.shapeFirst[edge], shapeTo = tile.shapeFirst[edge + 1];
        double[][] points = new double[shapeTo - shapeFrom + 2][];
        points[0] = new double[]{tile.lat[start] * 1e-7, tile.lon[start] * 1e-7};
        for (int i = shapeFrom; i < shapeTo; i++) {
            points[1 + i - shapeFrom] = new double[]{tile.shape[2 * i] * 1e-7, tile.shape[2 * i + 1] * 1e-7};
        }
        points[points.length - 1] = new double[]{tile.targetLat[edge] * 1e-7, tile.targetLon[edge] * 1e-7};
        return points;
    }

    private List<Snap> snap(double lat, double lon, double heading) {
        double margin = 0.005;
        int x0 = (int) Math.floor((lon - margin + 180.0) / LOCAL_DEG), x1 = (int) Math.floor((lon + margin + 180.0) / LOCAL_DEG);
        int y0 = (int) Math.floor((lat - margin + 90.0) / LOCAL_DEG), y1 = (int) Math.floor((lat + margin + 90.0) / LOCAL_DEG);
        List<Snap> found = new ArrayList<>();
        double best = INF;
        double box = margin * 1e7;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                RouteTile tile = tiles.tile(0, x, y);
                if (tile == null) continue;
                for (int n = 0; n < tile.nodeCount(); n++) {
                    for (int e = tile.first[n]; e < tile.first[n + 1]; e++) {
                        if (!near(tile, n, e, lat * 1e7, lon * 1e7, box)) continue;
                        Snap s = project(tile, e, lat, lon);
                        if (s.distance < best) best = s.distance;
                        found.add(s);
                    }
                }
            }
        }
        List<Snap> close = new ArrayList<>();
        for (Snap s : found) if (s.distance <= best + 6 && s.distance <= 400) close.add(s);
        if (!Double.isNaN(heading)) {
            List<Snap> aligned = new ArrayList<>();
            for (Snap s : close) {
                double difference = Math.abs(((s.bearing - heading) % 360 + 540) % 360 - 180);
                if (difference <= 100) aligned.add(s);
            }
            if (!aligned.isEmpty()) close = aligned;
        }
        if (close.size() > 8) close = new ArrayList<>(close.subList(0, 8));
        return close;
    }

    private static boolean near(RouteTile tile, int node, int edge, double lat, double lon, double box) {
        double minLat = Math.min(tile.lat[node], tile.targetLat[edge]), maxLat = Math.max(tile.lat[node], tile.targetLat[edge]);
        double minLon = Math.min(tile.lon[node], tile.targetLon[edge]), maxLon = Math.max(tile.lon[node], tile.targetLon[edge]);
        for (int i = tile.shapeFirst[edge]; i < tile.shapeFirst[edge + 1]; i++) {
            minLat = Math.min(minLat, tile.shape[2 * i]);
            maxLat = Math.max(maxLat, tile.shape[2 * i]);
            minLon = Math.min(minLon, tile.shape[2 * i + 1]);
            maxLon = Math.max(maxLon, tile.shape[2 * i + 1]);
        }
        return maxLat >= lat - box && minLat <= lat + box && maxLon >= lon - box && minLon <= lon + box;
    }

    private Snap project(RouteTile tile, int edge, double lat, double lon) {
        double[][] line = polyline(tile, edge);
        double metersPerLon = 111_320 * Math.cos(Math.toRadians(lat));
        double px = lon * metersPerLon, py = lat * 110_574;
        double total = 0, bestDistance = INF, bestAlong = 0, bestBearing = 0, bestLat = line[0][0], bestLon = line[0][1];
        double[] segmentStart = new double[line.length];
        for (int i = 0; i + 1 < line.length; i++) {
            segmentStart[i] = total;
            total += Math.hypot((line[i + 1][1] - line[i][1]) * metersPerLon, (line[i + 1][0] - line[i][0]) * 110_574);
        }
        for (int i = 0; i + 1 < line.length; i++) {
            double ax = line[i][1] * metersPerLon, ay = line[i][0] * 110_574;
            double dx = line[i + 1][1] * metersPerLon - ax, dy = line[i + 1][0] * 110_574 - ay;
            double n = dx * dx + dy * dy;
            double t = n == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / n));
            double d = Math.hypot(ax + t * dx - px, ay + t * dy - py);
            if (d < bestDistance) {
                bestDistance = d;
                bestAlong = segmentStart[i] + t * Math.sqrt(n);
                bestBearing = (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360;
                bestLat = line[i][0] + t * (line[i + 1][0] - line[i][0]);
                bestLon = line[i][1] + t * (line[i + 1][1] - line[i][1]);
            }
        }
        Snap snap = new Snap();
        snap.tile = tile;
        snap.edge = edge;
        snap.distance = bestDistance;
        snap.fraction = total == 0 ? 0 : bestAlong / total;
        snap.projLat = bestLat;
        snap.projLon = bestLon;
        snap.bearing = bestBearing;
        return snap;
    }

    private void build(Result result, List<Snap> starts, Node end, Snap goal, double total) {
        List<Node> chain = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) chain.add(0, n);
        Snap start = null;
        for (Snap s : starts) {
            if (s.tile == chain.get(0).tile && s.edge == chain.get(0).edge) start = s;
        }
        List<double[]> points = new ArrayList<>();
        List<EdgeRef> refs = new ArrayList<>();
        List<Double> ends = new ArrayList<>();
        double along = 0;

        double[][] first = polyline(start.tile, start.edge);
        points.add(new double[]{start.projLat, start.projLon});
        int skip = segmentAfter(first, start);
        for (int i = skip; i < first.length; i++) along = add(points, first[i], along);
        along = add(points, first[first.length - 1], along);
        refs.add(new EdgeRef(start.tile, start.edge));
        ends.add(along);
        for (int k = 1; k < chain.size(); k++) {
            Node n = chain.get(k);
            double[][] line = polyline(n.tile, n.edge);
            for (int i = 1; i < line.length; i++) along = add(points, line[i], along);
            refs.add(new EdgeRef(n.tile, n.edge));
            ends.add(along);
        }
        double[][] last = polyline(goal.tile, goal.edge);
        int upTo = segmentAfter(last, goal);
        for (int i = 1; i < upTo; i++) along = add(points, last[i], along);
        along = add(points, new double[]{goal.projLat, goal.projLon}, along);
        refs.add(new EdgeRef(goal.tile, goal.edge));
        ends.add(along);

        result.lat = new double[points.size()];
        result.lon = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            result.lat[i] = points.get(i)[0];
            result.lon[i] = points.get(i)[1];
        }
        result.meters = along;
        result.seconds = total;
        steps(result.steps, refs, ends);
    }

    private static int segmentAfter(double[][] line, Snap snap) {
        double metersPerLon = 111_320 * Math.cos(Math.toRadians(snap.projLat));
        double total = 0;
        double[] cumulative = new double[line.length];
        for (int i = 1; i < line.length; i++) {
            total += Math.hypot((line[i][1] - line[i - 1][1]) * metersPerLon, (line[i][0] - line[i - 1][0]) * 110_574);
            cumulative[i] = total;
        }
        double at = snap.fraction * total;
        for (int i = 1; i < line.length; i++) if (cumulative[i] > at + 0.01) return i;
        return line.length;
    }

    private static double add(List<double[]> points, double[] point, double along) {
        double[] previous = points.get(points.size() - 1);
        double step = meters(previous[0], previous[1], point[0], point[1]);
        if (step < 0.05) return along;
        points.add(point);
        return along + step;
    }

    private static void steps(List<RouteTrack.StepSpec> out, List<EdgeRef> refs, List<Double> ends) {
        boolean roundabout = false;
        double roundAt = 0;
        for (int i = 1; i < refs.size(); i++) {
            RouteTile ta = refs.get(i - 1).tile, tb = refs.get(i).tile;
            int a = refs.get(i - 1).edge, b = refs.get(i).edge;
            double at = ends.get(i - 1);
            boolean aRound = (ta.flags[a] & 2) != 0, bRound = (tb.flags[b] & 2) != 0;
            if (bRound && !aRound) {
                roundabout = true;
                roundAt = at;
                continue;
            }
            if (aRound && !bRound) {
                if (roundabout) {
                    RouteTrack.StepSpec spec = new RouteTrack.StepSpec();
                    spec.type = "roundabout";
                    spec.street = streetFrom(refs, i);
                    spec.atMeters = roundAt;
                    out.add(spec);
                }
                roundabout = false;
                continue;
            }
            if (aRound) continue;
            double turn = signedTurn(ta.bearingEnd[a] & 255, tb.bearingStart[b] & 255);
            String nameA = ta.nameOf(a), nameB = tb.nameOf(b);
            boolean sameName = !nameB.isEmpty() && nameB.equals(nameA);
            boolean exit = (tb.flags[b] & 1) != 0 && (ta.flags[a] & 1) == 0 && (ta.roadClass[a] & 255) <= 1;
            double size = Math.abs(turn);
            if (!(exit || size >= 35 || (!sameName && !nameB.isEmpty() && size >= 20))) continue;
            RouteTrack.StepSpec spec = new RouteTrack.StepSpec();
            spec.modifier = modifier(turn);
            spec.street = streetFrom(refs, i);
            spec.atMeters = at;
            String[] ramp = exit ? tb.exitOf(b) : null;
            if (ramp != null) spec.instruction = exitInstruction(ramp[0], ramp[1]);
            if (exit && size < 20) spec.modifier = turn >= 0 ? "slight right" : "slight left";
            out.add(spec);
        }
    }

    static String exitInstruction(String number, String toward) {
        String text = number.isEmpty() ? "Take the exit" : "Take exit " + number;
        return toward.isEmpty() ? text : text + " toward " + toward;
    }

    private static String streetFrom(List<EdgeRef> refs, int from) {
        for (int i = from; i < refs.size() && i < from + 6; i++) {
            String name = refs.get(i).tile.nameOf(refs.get(i).edge);
            if (!name.isEmpty()) return name;
        }
        return "";
    }

    private static String modifier(double turn) {
        double a = Math.abs(turn);
        boolean right = turn >= 0;
        if (a < 20) return "straight";
        if (a < 50) return right ? "slight right" : "slight left";
        if (a < 125) return right ? "right" : "left";
        if (a < 165) return right ? "sharp right" : "sharp left";
        return "uturn";
    }
}
