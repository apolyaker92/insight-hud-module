package com.andrew.insighthud;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class NavService {
    private final HudActivity host;

    NavService(HudActivity host) {
        this.host = host;
    }

    void stop() {
        if (navServer != null) navServer.stop();
        if (navAdvertisement != null) {
            android.net.nsd.NsdManager manager = host.getSystemService(android.net.nsd.NsdManager.class);
            try {
                if (manager != null) manager.unregisterService(navAdvertisement);
            } catch (IllegalArgumentException alreadyUnregistered) {
                // Registration can fail before the activity is destroyed.
            }
            navAdvertisement = null;
        }
    }

    static final int NAV_PORT = 8765;

    NavServer navServer;

    String navToken() {
        String token = host.preferences.getString("navToken", null);
        if (token == null) token = newNavToken();
        return token;
    }

    android.net.nsd.NsdManager.RegistrationListener navAdvertisement;

    private void advertiseNavigation() {
        android.net.nsd.NsdManager manager = host.getSystemService(android.net.nsd.NsdManager.class);
        if (manager == null) return;
        android.net.nsd.NsdServiceInfo service = new android.net.nsd.NsdServiceInfo();
        service.setServiceName("Insight HUD");
        service.setServiceType("_insighthud._tcp.");
        service.setPort(NAV_PORT);
        navAdvertisement = new android.net.nsd.NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(android.net.nsd.NsdServiceInfo info) { }
            @Override public void onServiceUnregistered(android.net.nsd.NsdServiceInfo info) { }
            @Override public void onRegistrationFailed(android.net.nsd.NsdServiceInfo info, int error) {
                android.util.Log.w("HudNav", "Bonjour registration failed: " + error);
            }
            @Override public void onUnregistrationFailed(android.net.nsd.NsdServiceInfo info, int error) {
                android.util.Log.w("HudNav", "Bonjour unregistration failed: " + error);
            }
        };
        manager.registerService(service, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, navAdvertisement);
    }

    String newNavToken() {
        byte[] bytes = new byte[4];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder token = new StringBuilder();
        for (byte b : bytes) token.append(String.format(Locale.US, "%02x", b & 0xff));
        host.preferences.edit().putString("navToken", token.toString()).apply();
        return token.toString();
    }

    void startNavServer() {
        if (!host.navEnabled || navServer != null) return;
        navServer = new NavServer(NAV_PORT, this::navToken, new NavServer.Handler() {
            @Override public String status() {
                JSONObject json = new JSONObject();
                try {
                    RouteTrack route = host.nav;
                    json.put("app", "insight-hud").put("navigating", route != null);
                    if (route != null) {
                        RouteTrack.Step next = route.nextStep();
                        json.put("destination", route.name).put("remaining_m", Math.round(route.remainingMeters()))
                                .put("next", next == null ? "ARRIVE" : next.label())
                                .put("next_m", Math.round(route.metersToNext()))
                                .put("off_route", route.offRoute(SystemClock.uptimeMillis()))
                                .put("arrived", route.arrived());
                    }
                    json.put("gps_fix", host.gpsFix != null);
                    json.put("view", !host.mapPreview ? "gauges" : host.fullMap ? "full" : "split");
                    json.put("mirror", host.mirror);
                    json.put("brightness", host.brightnessMode);
                    json.put("saved_places", host.savedPlaces());
                } catch (JSONException impossible) {
                    // Only a null key could throw here.
                }
                return json.toString();
            }

            @Override public String route(JSONObject body, int precision) {
                RouteTrack track;
                try {
                    track = RouteParser.parse(body, precision, "DESTINATION");
                } catch (IllegalArgumentException bad) {
                    return bad.getMessage();
                }
                host.main.post(() -> host.startRoute(track));
                return null;
            }

            @Override public String destination(JSONObject body) {
                double lat = body.optDouble("lat", Double.NaN), lon = body.optDouble("lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
                    return "A destination needs lat and lon";
                }
                return host.routeTo(lat, lon, body.optString("name", ""), false);
            }

            @Override public void cancel() {
                host.main.post(host::cancelRoute);
            }

            @Override public String trips() {
                return "{\"trips\":" + host.tripHistory() + "}";
            }

            @Override public String tracks(String id) {
                java.io.File external = host.getExternalFilesDir(null);
                return DriveLog.trackJson(external == null ? null : new java.io.File(external, "logs"), id);
            }

            @Override public String settings(JSONObject body) {
                Object view = body.opt("view");
                Object mirrored = body.opt("mirror");
                Object brightness = body.opt("brightness");
                JSONArray places = body.optJSONArray("saved_places");
                if (body.has("saved_places")) {
                    if (body.length() != 1 || places == null) return "Saved places must be a list";
                    for (int i = 0; i < places.length(); i++) {
                        JSONObject place = places.optJSONObject(i);
                        if (place == null || !(place.opt("name") instanceof String)
                                || place.optString("name").trim().isEmpty() || place.optString("name").length() > 80
                                || !(place.opt("lat") instanceof Number) || !(place.opt("lon") instanceof Number)
                                || Math.abs(place.optDouble("lat")) > 90 || Math.abs(place.optDouble("lon")) > 180) {
                            return "Each saved place needs a name and valid coordinates";
                        }
                    }
                    host.preferences.edit().putString("savedPlaces", places.toString()).apply();
                    host.main.post(() -> host.hud.invalidate());
                    return null;
                }
                if (body.has("view") && !"gauges".equals(view) && !"split".equals(view) && !"full".equals(view)) {
                    return "View must be gauges, split or full";
                }
                if (body.has("mirror") && !(mirrored instanceof Boolean)) return "Mirror must be a boolean";
                if (body.has("brightness") && (!(brightness instanceof Integer)
                        || (int) brightness < 0 || (int) brightness >= host.BRIGHTNESS_NAMES.length)) {
                    return "Brightness must be an integer from 0 to 3";
                }
                if (body.length() != (view == null ? 0 : 1) + (mirrored == null ? 0 : 1)
                        + (brightness == null ? 0 : 1) || body.length() == 0) return "Unknown or missing settings";
                host.main.post(() -> {
                    if (view != null) host.setHudView(!"gauges".equals(view), "full".equals(view));
                    if (mirrored != null) host.setMirror((boolean) mirrored);
                    if (brightness != null) host.setBrightnessMode((int) brightness);
                    host.hud.invalidate();
                });
                return null;
            }
        });
        navServer.start();
        advertiseNavigation();
    }
}
