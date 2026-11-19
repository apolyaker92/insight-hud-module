package com.andrew.insighthud;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class RouteParser {
    private RouteParser() { }

    static RouteTrack parse(JSONObject body, int defaultPrecision, String fallbackName) {
        try {
            if (body.has("routes")) return fromOsrm(body, defaultPrecision, fallbackName);
            return fromHud(body, defaultPrecision, fallbackName);
        } catch (JSONException bad) {
            throw new IllegalArgumentException("Route JSON is malformed: " + bad.getMessage());
        }
    }

    private static RouteTrack fromHud(JSONObject body, int precision, String fallbackName) throws JSONException {
        double[][] line = line(body.optString("polyline", ""), body.optJSONArray("points"),
                body.optInt("polyline_precision", precision));
        List<RouteTrack.StepSpec> specs = new ArrayList<>();
        JSONArray steps = body.optJSONArray("steps");
        double running = 0;
        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i);
            RouteTrack.StepSpec spec = new RouteTrack.StepSpec();
            spec.instruction = step.optString("instruction", "");
            spec.street = step.optString("street", "");
            spec.type = step.optString("type", "");
            spec.modifier = step.optString("modifier", "");
            if (spec.instruction.isEmpty() && (step.has("exit") || step.has("toward"))) {
                spec.instruction = Router.exitInstruction(step.optString("exit", ""), step.optString("toward", ""));
            }
            if (step.has("at_m")) spec.atMeters = step.getDouble("at_m");
            JSONArray where = step.optJSONArray("location");
            if (where != null && where.length() >= 2) {
                spec.lat = where.getDouble(0);
                spec.lon = where.getDouble(1);
            }
            if (Double.isNaN(spec.atMeters) && Double.isNaN(spec.lat) && step.has("distance_m")) spec.atMeters = running;
            running += step.optDouble("distance_m", 0);
            specs.add(spec);
        }
        return new RouteTrack(body.optString("name", fallbackName), line[0], line[1], specs,
                body.optDouble("duration_s", 0));
    }

    private static RouteTrack fromOsrm(JSONObject body, int precision, String fallbackName) throws JSONException {
        JSONArray routes = body.getJSONArray("routes");
        if (routes.length() == 0) throw new IllegalArgumentException("The routing service found no route");
        JSONObject route = routes.getJSONObject(0);
        Object geometry = route.get("geometry");
        double[][] line;
        if (geometry instanceof String) {
            line = line((String) geometry, null, precision);
        } else {
            JSONArray coordinates = ((JSONObject) geometry).getJSONArray("coordinates");
            double[] lat = new double[coordinates.length()], lon = new double[coordinates.length()];
            for (int i = 0; i < lat.length; i++) {
                lon[i] = coordinates.getJSONArray(i).getDouble(0);
                lat[i] = coordinates.getJSONArray(i).getDouble(1);
            }
            line = new double[][]{lat, lon};
        }
        List<RouteTrack.StepSpec> specs = new ArrayList<>();
        JSONArray legs = route.optJSONArray("legs");
        double running = 0;
        for (int l = 0; legs != null && l < legs.length(); l++) {
            JSONArray steps = legs.getJSONObject(l).optJSONArray("steps");
            for (int i = 0; steps != null && i < steps.length(); i++) {
                JSONObject step = steps.getJSONObject(i);
                JSONObject maneuver = step.optJSONObject("maneuver");
                RouteTrack.StepSpec spec = new RouteTrack.StepSpec();
                spec.street = step.optString("name", "");
                spec.instruction = step.optString("instruction", "");
                if (maneuver != null) {
                    spec.type = maneuver.optString("type", "");
                    spec.modifier = maneuver.optString("modifier", "");
                    JSONArray where = maneuver.optJSONArray("location");
                    if (where != null && where.length() >= 2) {
                        spec.lon = where.getDouble(0);
                        spec.lat = where.getDouble(1);
                    }
                }
                if (spec.instruction.isEmpty() && spec.type.equals("off ramp")) {
                    String exit = step.optString("exits", "").split(";")[0].trim();
                    String toward = signedDestination(step.optString("destinations", ""));
                    if (!exit.isEmpty() || !toward.isEmpty()) spec.instruction = Router.exitInstruction(exit, toward);
                }
                if (Double.isNaN(spec.lat)) spec.atMeters = running;
                running += step.optDouble("distance", 0);
                specs.add(spec);
            }
        }
        String name = fallbackName;
        JSONArray waypoints = body.optJSONArray("waypoints");
        if (waypoints != null && waypoints.length() > 0) {
            String last = waypoints.getJSONObject(waypoints.length() - 1).optString("name", "");
            if (!last.isEmpty()) name = last;
        }
        return new RouteTrack(name, line[0], line[1], specs, route.optDouble("duration", 0));
    }

    private static double[][] line(String polyline, JSONArray points, int precision) throws JSONException {
        if (polyline != null && !polyline.isEmpty()) {
            double[][] decoded = Polyline.decode(polyline, precision == 6 ? 6 : 5);
            if (decoded[0].length < 2) throw new IllegalArgumentException("The route line has fewer than two points");
            return decoded;
        }
        if (points == null || points.length() < 2) throw new IllegalArgumentException("The route has no line");
        double[] lat = new double[points.length()], lon = new double[points.length()];
        for (int i = 0; i < lat.length; i++) {
            lat[i] = points.getJSONArray(i).getDouble(0);
            lon[i] = points.getJSONArray(i).getDouble(1);
        }
        return new double[][]{lat, lon};
    }

    static String signedDestination(String destinations) {
        String first = destinations.split(",")[0].trim();
        int colon = first.indexOf(':');
        String text = colon > 0 ? first.substring(0, colon).trim() : first;
        return text.length() > 30 ? text.substring(0, 30) : text;
    }
}
