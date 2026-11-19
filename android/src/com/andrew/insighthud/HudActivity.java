package com.andrew.insighthud;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.input.InputManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaTimestamp;
import android.media.SoundPool;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.DisplayCutout;
import android.view.WindowInsets;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class HudActivity extends Activity {
    private SharedPreferences preferences;
    private HudView hud;
    private volatile boolean mirror;
    private boolean demo;
    private volatile boolean mapPreview;
    private volatile boolean fullMap;
    private int mapReadout;
    private static final String[] READOUT_NAMES = {"FULL", "SPEED", "OFF"};
    private static final String[] PALETTE_NAMES = {"GREEN", "AMBER", "BLUE"};
    private boolean roadInfoOn;
    private OfflineRoadMap.RoadInfo roadInfo;
    private long lastLookupAt;
    private double lastLookupLat, lastLookupLon;
    private long overSince, lastOverAlertAt;
    private DriveLog driveLog;

    private static final int NAV_PORT = 8765;
    private RouteTrack nav;
    private double[] navTileX, navTileY;
    private int navVersion;
    private NavServer navServer;
    private boolean navEnabled = true;
    private long navArrivedAt;
    private boolean offRouteAnnounced;
    private RouteDatabase routeDb;
    private boolean routeDbTried;
    private final java.util.concurrent.ExecutorService routing = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean routingBusy;
    private final java.util.concurrent.atomic.AtomicInteger routeGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private double[] navGoal;
    private String navGoalName = "";
    private static final long RESUME_ROUTE_TIMEOUT_MS = 5000;
    private RouteTrack parkedRoute;
    private double[] parkedRouteGoal;
    private String parkedRouteGoalName;
    private long resumeRouteDeadline;
    private final Runnable resumeRouteTimeout = () -> answerResumeRoute(false);
    private long lastRerouteAt;

    private static final int F1_HOLD_MS = 600, QUICK_IDLE_MS = 12_000, QUICK_REFRESH_MS = 2000;
    private boolean quickOpen;
    private int quickRow;
    private List<String[]> quickHealth = new ArrayList<>();
    private String quickGasNote = "";
    private boolean f1Down, f1HoldFired;
    private final Runnable f1Hold = () -> {
        f1HoldFired = true;
        if (quickOpen) closeQuick();
        else openQuick();
    };
    private final Runnable quickIdle = this::closeQuick;
    private final Runnable quickTick = new Runnable() {
        @Override public void run() {
            if (!quickOpen) return;
            refreshQuick();
            hud.invalidate();
            main.postDelayed(this, QUICK_REFRESH_MS);
        }
    };

    private String navToken() {
        String token = preferences.getString("navToken", null);
        if (token == null) token = newNavToken();
        return token;
    }

    private android.net.nsd.NsdManager.RegistrationListener navAdvertisement;

    private void advertiseNavigation() {
        android.net.nsd.NsdManager manager = getSystemService(android.net.nsd.NsdManager.class);
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

    private String newNavToken() {
        byte[] bytes = new byte[4];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder token = new StringBuilder();
        for (byte b : bytes) token.append(String.format(Locale.US, "%02x", b & 0xff));
        preferences.edit().putString("navToken", token.toString()).apply();
        return token.toString();
    }

    private void startNavServer() {
        if (!navEnabled || navServer != null) return;
        navServer = new NavServer(NAV_PORT, this::navToken, new NavServer.Handler() {
            @Override public String status() {
                JSONObject json = new JSONObject();
                try {
                    RouteTrack route = nav;
                    json.put("app", "insight-hud").put("navigating", route != null);
                    if (route != null) {
                        RouteTrack.Step next = route.nextStep();
                        json.put("destination", route.name).put("remaining_m", Math.round(route.remainingMeters()))
                                .put("next", next == null ? "ARRIVE" : next.label())
                                .put("next_m", Math.round(route.metersToNext()))
                                .put("off_route", route.offRoute(SystemClock.uptimeMillis()))
                                .put("arrived", route.arrived());
                    }
                    json.put("gps_fix", gpsFix != null);
                    json.put("view", !mapPreview ? "gauges" : fullMap ? "full" : "split");
                    json.put("mirror", mirror);
                    json.put("brightness", brightnessMode);
                    json.put("saved_places", savedPlaces());
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
                main.post(() -> startRoute(track));
                return null;
            }

            @Override public String destination(JSONObject body) {
                double lat = body.optDouble("lat", Double.NaN), lon = body.optDouble("lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
                    return "A destination needs lat and lon";
                }
                return routeTo(lat, lon, body.optString("name", ""), false);
            }

            @Override public void cancel() {
                main.post(HudActivity.this::cancelRoute);
            }

            @Override public String trips() {
                return "{\"trips\":" + tripHistory() + "}";
            }

            @Override public String tracks(String id) {
                java.io.File external = getExternalFilesDir(null);
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
                    preferences.edit().putString("savedPlaces", places.toString()).apply();
                    main.post(() -> hud.invalidate());
                    return null;
                }
                if (body.has("view") && !"gauges".equals(view) && !"split".equals(view) && !"full".equals(view)) {
                    return "View must be gauges, split or full";
                }
                if (body.has("mirror") && !(mirrored instanceof Boolean)) return "Mirror must be a boolean";
                if (body.has("brightness") && (!(brightness instanceof Integer)
                        || (int) brightness < 0 || (int) brightness >= BRIGHTNESS_NAMES.length)) {
                    return "Brightness must be an integer from 0 to 3";
                }
                if (body.length() != (view == null ? 0 : 1) + (mirrored == null ? 0 : 1)
                        + (brightness == null ? 0 : 1) || body.length() == 0) return "Unknown or missing settings";
                main.post(() -> {
                    if (view != null) setHudView(!"gauges".equals(view), "full".equals(view));
                    if (mirrored != null) setMirror((boolean) mirrored);
                    if (brightness != null) setBrightnessMode((int) brightness);
                    hud.invalidate();
                });
                return null;
            }
        });
        navServer.start();
        advertiseNavigation();
    }

    private synchronized RouteDatabase routeDatabase() {
        if (routeDb == null && !routeDbTried) {
            routeDbTried = true;
            java.io.File external = getExternalFilesDir(null);
            if (external != null) routeDb = RouteDatabase.open(new java.io.File(external, "hud-route.db"));
        }
        return routeDb;
    }

    private synchronized String routeTo(double lat, double lon, String name, boolean reroute) {
        RouteDatabase graph = routeDatabase();
        if (graph == null) return "No routing data on the HUD (hud-route.db)";
        Location from = gpsFix;
        if (from == null) return "No GPS fix yet";
        if (routingBusy) return "Already working out a route";
        int generation = routeGeneration.incrementAndGet();
        routingBusy = true;
        double heading = from.hasBearing() && from.hasSpeed() && from.getSpeed() > 2.5 ? from.getBearing() : Double.NaN;
        double fromLat = from.getLatitude(), fromLon = from.getLongitude();
        routing.execute(() -> {
            Router.Result result;
            try {
                result = new Router(graph).route(fromLat, fromLon, heading, lat, lon);
            } catch (RuntimeException | OutOfMemoryError broken) {
                result = new Router.Result();
                result.error = "Routing failed";
                android.util.Log.w("HudRoute", "Routing failed", broken);
            }
            Router.Result done = result;
            main.post(() -> {
                routingBusy = false;
                if (generation != routeGeneration.get()) return;
                if (done.error != null) {
                    raiseAlert(done.error.toUpperCase(Locale.US), Alerts.WARN);
                    return;
                }
                RouteTrack track = new RouteTrack(name, done.lat, done.lon, done.steps, done.seconds);
                startRoute(track, !reroute);
                navGoal = new double[]{lat, lon};
                navGoalName = name;
            });
        });
        return null;
    }

    private void startRoute(RouteTrack track) {
        routeGeneration.incrementAndGet();
        navGoal = null;
        startRoute(track, true);
    }

    private void startRoute(RouteTrack track, boolean announce) {
        clearResumeRoute();
        nav = track;
        navVersion++;
        navTileX = new double[track.lat.length];
        navTileY = new double[track.lat.length];
        for (int i = 0; i < track.lat.length; i++) {
            navTileX[i] = OfflineRoadMap.tileX(track.lon[i]);
            navTileY[i] = OfflineRoadMap.tileY(track.lat[i]);
        }
        navArrivedAt = 0;
        offRouteAnnounced = false;
        String name = track.name.isEmpty() ? "DESTINATION" : track.name.toUpperCase(Locale.US);
        raiseAlert(announce ? "ROUTE TO " + (name.length() > 24 ? name.substring(0, 24) : name) : "NEW ROUTE", Alerts.INFO);
        updateMap();
        if (gpsFix != null) navUpdate(gpsFix);
        hud.invalidate();
    }

    private boolean cancelPrompt;
    private static final long CANCEL_PROMPT_MS = 10_000;
    private long cancelPromptDeadline;
    private final Runnable cancelPromptTimeout = () -> dismissCancelPrompt();

    private void showCancelPrompt() {
        if (nav == null) return;
        cancelPrompt = true;
        cancelPromptDeadline = SystemClock.uptimeMillis() + CANCEL_PROMPT_MS;
        main.removeCallbacks(cancelPromptTimeout);
        main.postDelayed(cancelPromptTimeout, CANCEL_PROMPT_MS);
        hud.invalidate();
    }

    private void dismissCancelPrompt() {
        cancelPrompt = false;
        main.removeCallbacks(cancelPromptTimeout);
        hud.invalidate();
    }

    private void cancelRoute() {
        cancelPrompt = false;
        main.removeCallbacks(cancelPromptTimeout);
        clearResumeRoute();
        routeGeneration.incrementAndGet();
        nav = null;
        navGoal = null;
        navArrivedAt = 0;
        updateMap();
        hud.invalidate();
    }

    private void clearResumeRoute() {
        parkedRoute = null;
        parkedRouteGoal = null;
        parkedRouteGoalName = null;
        resumeRouteDeadline = 0;
        main.removeCallbacks(resumeRouteTimeout);
    }

    private void answerResumeRoute(boolean resume) {
        RouteTrack route = parkedRoute;
        if (route == null) return;
        if (resume && resumeRouteDeadline > SystemClock.uptimeMillis()) {
            navGoal = parkedRouteGoal;
            navGoalName = parkedRouteGoalName;
            startRoute(route, true);
        } else {
            clearResumeRoute();
            hud.invalidate();
        }
    }

    private static final double THEN_METERS = 450;

    private double navAhead() {
        long now = SystemClock.elapsedRealtime();
        if (lastMapFix == null || !estimator.moving(now)) return 0;
        double[] estimate = estimator.at(now);
        if (estimate == null) return 0;
        return Math.min(80, Router.meters(lastMapFix.getLatitude(), lastMapFix.getLongitude(), estimate[0], estimate[1]));
    }

    private void navUpdate(Location fix) {
        RouteTrack route = nav;
        if (route == null) return;
        long now = SystemClock.uptimeMillis();
        route.update(fix.getLatitude(), fix.getLongitude(), now);
        if (route.arrived()) {
            if (navArrivedAt == 0) {
                navArrivedAt = now;
                raiseAlert("ARRIVED", Alerts.INFO);
                main.postDelayed(() -> {
                    if (nav == route) cancelRoute();
                }, 20_000);
            }
        } else if (route.offRoute(now)) {
            if (!offRouteAnnounced) {
                offRouteAnnounced = true;
                raiseAlert(navGoal != null ? "REROUTING" : "OFF ROUTE", Alerts.WARN);
            }
            if (navGoal != null && !routingBusy && now - lastRerouteAt > 8000) {
                lastRerouteAt = now;
                routeTo(navGoal[0], navGoal[1], navGoalName, true);
            }
        } else {
            offRouteAnnounced = false;
        }
    }

    private String navAddresses() {
        StringBuilder out = new StringBuilder();
        try {
            for (java.net.NetworkInterface face : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!face.isUp() || face.isLoopback()) continue;
                for (java.net.InetAddress address : java.util.Collections.list(face.getInetAddresses())) {
                    if (address instanceof java.net.Inet4Address) {
                        out.append("http://").append(address.getHostAddress()).append(':').append(NAV_PORT)
                                .append("   (").append(face.getName()).append(")\n");
                    }
                }
            }
        } catch (java.net.SocketException none) {
            // Report no address below.
        }
        return out.length() == 0 ? "No network address yet: join a Wi-Fi network or turn the hotspot on.\n"
                : out.toString();
    }

    private void showNavPairing() {
        new AlertDialog.Builder(this)
                .setTitle("SEND ROUTES TO THE HUD")
                .setMessage("Address:\n" + navAddresses() + "\nPairing code: " + navToken()
                        + "\n\nSend it in an X-HUD-Token header, or add ?token=" + navToken() + " to the address."
                        + "\n\nPOST /route with a route, POST /cancel to stop, GET /status to check.")
                .setPositiveButton("OK", null)
                .setNegativeButton("NEW CODE", (dialog, which) -> {
                    newNavToken();
                    showNavPairing();
                }).show();
    }
    private Location previousFix;
    private float derivedSpeedMps = Float.NaN;
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (hud != null) hud.invalidate();
            scheduleClock();
        }
    };

    private void scheduleClock() {
        main.removeCallbacks(clockTick);
        main.postDelayed(clockTick, 60_000 - System.currentTimeMillis() % 60_000 + 50);
    }
    private boolean headingUp;
    private int mapRange;
    private static final String[] RANGE_NAMES = {"NORMAL", "WIDE", "CLOSE"};
    private static final float[] RANGE_ZOOM = {1.4f, 1.05f, 3.0f};
    private TripStats trip = new TripStats();
    private JSONObject shutdownReport;
    private long accessoryLostAt;
    private long mapPreviewStartedAt;
    private OfflineRoadMap roadMap;
    private LocationManager locationManager;
    private Location gpsFix;
    private Location lastMapFix;
    private final PositionEstimator estimator = new PositionEstimator();
    private final Location estimatedFix = new Location("estimate");
    private float gpsCourse = Float.NaN;
    private boolean gpsRegistered;
    private boolean gpsUnavailable;
    private boolean gpsTestActive;
    private static final int GPS_PERMISSION = 24;
    private static final long GPS_STALE_MS = 12000;
    private static final long GPS_UNAVAILABLE_MS = 20000;
    private static final long GPS_TEST_MS = 5 * 60 * 1000;
    private static final long GPS_RETRY_MS = 5000;
    private volatile int brightnessMode;
    private static final String[] BRIGHTNESS_NAMES = {"AUTO", "HIGH", "MED", "LOW"};
    private static final float[] BRIGHTNESS_LEVELS = {0f, 1.0f, 0.45f, 0.08f};
    private SensorManager sensors;
    private Sensor lightSensor;
    private boolean lightRegistered;
    private float autoLevel = 0.45f;
    private final AutoBrightness autoBrightness = new AutoBrightness();
    private final SensorEventListener lightListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            float level = autoBrightness.update(event.values[0]);
            if (Math.abs(level - autoLevel) >= 0.02f) {
                autoLevel = level;
                if (brightnessMode == 0) {
                    applyBrightness();
                    hud.invalidate();
                }
            }
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };
    private boolean powered;
    private boolean powerKnown;
    private boolean accessoryActive;
    private boolean startupPending;
    private boolean parkedBlank;
    private int battery = -1;
    private float batteryTemperature;
    private boolean batteryOverheat;
    private PowerManager.OnThermalStatusChangedListener thermalListener;
    private int systemThermalStatus;
    private int thermalLevel;
    private boolean thermalBlank;
    private long thermalStartedAt;
    private static final int THERMAL_NORMAL = 0;
    private static final int THERMAL_LIMIT = 1;
    private static final int THERMAL_HOLD = 2;
    private static final long THERMAL_WARNING_MS = 4500;
    private long startupStartedAt;
    private long shutdownStartedAt;
    private MediaPlayer startupSound;
    private SoundPool switchSounds;
    private volatile int switchSoundId, shutdownSoundId, switchLoadId, shutdownLoadId;
    private volatile int lockHeadingSoundId, lockNorthSoundId, lockHeadingLoadId, lockNorthLoadId;
    private volatile int zoomInSoundId, zoomOutSoundId, zoomInLoadId, zoomOutLoadId;
    private static final long STARTUP_DURATION_MS = 2800;
    private static final long POWER_LOSS_DELAY_MS = 3000;
    private static final long SHUTDOWN_DURATION_MS = 1200;
    private static final long TRIP_REPORT_MS = 4200;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothAdapter bluetooth;
    private ObdBluetooth obd;
    private ObdBluetooth.Readings readings;
    private static final OfflineRoadMap.RoadInfo DEMO_ROAD_INFO =
            new OfflineRoadMap.RoadInfo("DEMO AVENUE", 35, "DEMOVILLE", "DEMO FUEL", 1.2, "NE",
                    RoadLookup.SIGNAL, "", 300);
    private static final ObdBluetooth.Readings DEMO_READINGS = new ObdBluetooth.Readings(46d, 2400d, 188d, 85d, 13.8d, 42d, 28d, 0.87d, 52d);
    private static final ObdBluetooth.Readings EMPTY_READINGS = new ObdBluetooth.Readings(null, null, null, null, null, null);
    private static final float[] DEMO_RPM = {820, 880, 1100, 1550, 2250, 3250, 4500, 5000, 3550, 2950,
            3050, 3450, 4200, 4700, 3150, 2680, 2530, 2420, 2450, 2400};
    private final boolean[] seenGauge = new boolean[4];
    private final Alerts alerts = new Alerts();
    private final AutoStopTracker autoStop = new AutoStopTracker();
    private final SoundPolicy sounds = new SoundPolicy();
    private volatile int autoStopLoadId, autoStartLoadId;
    private static final double WARM_F = 175;
    private static final double HOT_F = 230;   // coolant warning; the dash gauge remains authoritative
    private boolean linkLive, failureAnnounced, setupAnnounced, sawCool, warmAnnounced, hotAnnounced, fixAnnounced;
    private int failedAttempts;
    private volatile int alertInfoLoadId, alertWarnLoadId, alertCriticalLoadId;
    private final java.util.Set<Integer> loadedSamples =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());
    private final float[] rpmHistory = new float[DEMO_RPM.length];
    private int rpmNext;
    private int rpmCount;
    private String linkStatus = "SELECT BT ADAPTER";
    private boolean foreground;
    private final Runnable confirmPowerLoss = this::parkHud;
    private final Runnable finishShutdown = this::blankParkedHud;
    private final Runnable finishThermalWarning = () -> {
        thermalBlank = true;
        updateScreenPower();
        hud.invalidate();
    };
    private final Runnable finishGpsTest = () -> {
        setGpsTest(false);
        updateMap();
        blankParkedHud();
    };
    private final Runnable expireGpsFix = () -> {
        gpsFix = null;
        previousFix = null;
        derivedSpeedMps = Float.NaN;
        roadInfo = null;
        lastLookupAt = 0;
        overSince = 0;
        gpsCourse = Float.NaN;
        refreshMapTiles();
        hud.invalidate();
    };
    private final Runnable retryGps = this::updateMap;
    private final Runnable showGpsUnavailable = () -> {
        gpsUnavailable = gpsRegistered && gpsEnabled();
        hud.invalidate();
    };
    private final LocationListener gpsListener = new LocationListener() {
        @Override public void onLocationChanged(Location location) {
            long age = (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1000000;
            if (!gpsRegistered || age >= GPS_STALE_MS || !location.hasAccuracy() || location.getAccuracy() > 100) return;
            gpsFix = location;
            lastMapFix = location;
            if (!fixAnnounced) {
                fixAnnounced = true;
                raiseAlert("GPS LOCKED", Alerts.INFO);
            }
            scheduleGpsAvailability(age);
            derivedSpeedMps = Float.NaN;
            if (previousFix != null) {
                float seconds = (location.getElapsedRealtimeNanos() - previousFix.getElapsedRealtimeNanos()) / 1e9f;
                float metres = previousFix.distanceTo(location);
                if (seconds > 0.3f && seconds < 6f && metres >= 5f) {
                    derivedSpeedMps = metres / seconds;
                    if (!location.hasBearing() && derivedSpeedMps >= 2f) {
                        gpsCourse = (float) RoadLookup.normalizeBearing(previousFix.bearingTo(location));
                    }
                }
            }
            previousFix = location;
            if (location.hasBearing() && location.hasSpeed() && location.getSpeed() >= 2f) {
                gpsCourse = location.getBearing();
            }
            float fixSpeed = location.hasSpeed() ? location.getSpeed() : derivedSpeedMps;
            estimator.fix(location.getLatitude(), location.getLongitude(), fixSpeed, gpsCourse,
                    location.getElapsedRealtimeNanos() / 1_000_000L, SystemClock.elapsedRealtime());
            main.removeCallbacks(expireGpsFix);
            main.postDelayed(expireGpsFix, GPS_STALE_MS - Math.max(0, age));
            refreshMapTiles();
            lookupRoadInfo(location);
            navUpdate(location);
            hud.invalidate();
        }

        @Override public void onProviderDisabled(String provider) {
            cancelGpsAvailability();
            main.removeCallbacks(expireGpsFix);
            expireGpsFix.run();
        }

        @Override public void onProviderEnabled(String provider) {
            if (gpsRegistered) scheduleGpsAvailability(0);
            hud.invalidate();
        }

        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    };
    private final Runnable staleReadings = () -> {
        updateReadings(null);
        linkStatus = "DATA STALE";
        hud.invalidate();
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            battery = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
            batteryTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
            batteryOverheat = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
                    == BatteryManager.BATTERY_HEALTH_OVERHEAT;
            updateThermalProtection();
            updateAccessoryPower(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
            hud.invalidate();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startupStartedAt = state == null ? 0 : state.getLong("startupStartedAt");
        startupPending = state != null && state.getBoolean("startupPending");
        parkedBlank = state != null && state.getBoolean("parkedBlank");
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        switchSounds = new SoundPool.Builder().setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .build();
        switchSounds.setOnLoadCompleteListener((pool, sample, status) -> {
            if (pool != switchSounds || status != 0) return;
            loadedSamples.add(sample);
            if (sample == switchLoadId) switchSoundId = sample;
            else if (sample == shutdownLoadId) shutdownSoundId = sample;
            else if (sample == lockHeadingLoadId) lockHeadingSoundId = sample;
            else if (sample == lockNorthLoadId) lockNorthSoundId = sample;
            else if (sample == zoomInLoadId) zoomInSoundId = sample;
            else if (sample == zoomOutLoadId) zoomOutSoundId = sample;
        });
        switchLoadId = switchSounds.load(this, R.raw.switch_click, 1);
        shutdownLoadId = switchSounds.load(this, R.raw.shutdown_chime, 1);
        lockHeadingLoadId = switchSounds.load(this, R.raw.lock_heading, 1);
        lockNorthLoadId = switchSounds.load(this, R.raw.lock_north, 1);
        zoomInLoadId = switchSounds.load(this, R.raw.zoom_in, 1);
        zoomOutLoadId = switchSounds.load(this, R.raw.zoom_out, 1);
        alertInfoLoadId = switchSounds.load(this, R.raw.alert_info, 1);
        alertWarnLoadId = switchSounds.load(this, R.raw.alert_warn, 1);
        alertCriticalLoadId = switchSounds.load(this, R.raw.alert_critical, 1);
        autoStopLoadId = switchSounds.load(this, R.raw.autostop, 1);
        autoStartLoadId = switchSounds.load(this, R.raw.autostart, 1);
        preferences = getSharedPreferences("hud", MODE_PRIVATE);
        mirror = preferences.getBoolean("mirror", false);
        brightnessMode = preferences.contains("brightnessMode")
                ? Math.max(0, Math.min(3, preferences.getInt("brightnessMode", 0)))
                : preferences.getBoolean("dim", false) ? 3 : 0;
        sensors = getSystemService(SensorManager.class);
        lightSensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        sounds.mode = Math.max(0, Math.min(SoundPolicy.MODE_NAMES.length - 1, preferences.getInt("soundMode", 0)));
        sounds.level = Math.max(0, Math.min(SoundPolicy.LEVELS.length - 1,
                preferences.getInt("soundLevel", SoundPolicy.LEVELS.length - 1)));
        sounds.nightQuiet = preferences.getBoolean("nightQuiet", true);
        applyOrientation();
        mapPreview = preferences.getBoolean("mapPreview", false);
        fullMap = preferences.getBoolean("fullMap", false);
        // Older versions stored this as a yes/no; no meant none.
        mapReadout = preferences.contains("mapReadoutMode") ? preferences.getInt("mapReadoutMode", 0)
                : preferences.getBoolean("mapReadout", true) ? 0 : 2;
        mapReadout = Math.max(0, Math.min(READOUT_NAMES.length - 1, mapReadout));
        roadInfoOn = preferences.getBoolean("roadInfo", true);
        headingUp = preferences.getBoolean("headingUp", false);
        mapRange = Math.max(0, Math.min(RANGE_NAMES.length - 1, preferences.getInt("mapRange", 0)));
        mapPreviewStartedAt = SystemClock.uptimeMillis();
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setStatusBarColor(Color.BLACK);
        hud = new HudView(this);
        // Some Bluetooth keyboards also register a mouse; keep Android's pointer from drawing over the HUD.
        hud.setPointerIcon(android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL));
        setContentView(hud);
        locationManager = getSystemService(LocationManager.class);
        navEnabled = preferences.getBoolean("navEnabled", true);
        startNavServer();
        roadMap = new OfflineRoadMap(this, () -> {
            refreshMapTiles();
            hud.invalidate();
        });
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bluetooth = manager == null ? null : manager.getAdapter();
        if (bluetooth != null) {
            obd = new ObdBluetooth(bluetooth, (status, update) -> {
                main.removeCallbacks(staleReadings);
                linkStatus = status;
                trackLink(status, update);
                updateReadings(update);
                if (update != null) main.postDelayed(staleReadings, 6000);
                hud.invalidate();
            });
            // With the car on and Bluetooth still off, ask for it again (the earlier request can be lost if it
            // arrives while Bluetooth is still switching off from the last parking).
            obd.whenBluetoothOff = () -> {
                if (accessoryActive && !parkedBlank) setBluetoothPower(true);
            };
        }
        Intent battery = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            boolean plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (state != null && plugged && state.getBoolean("accessoryActive")) {
                powerKnown = powered = accessoryActive = true;
                trip.restore(state.getBundle("trip"));
                setHotspotPower(true);
                updateScreenPower();
            }
            batteryReceiver.onReceive(this, battery);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager power = getSystemService(PowerManager.class);
            thermalListener = status -> {
                systemThermalStatus = status;
                updateThermalProtection();
            };
            power.addThermalStatusListener(getMainExecutor(), thermalListener);
            systemThermalStatus = power.getCurrentThermalStatus();
            updateThermalProtection();
        }
        applyBrightness();
        hideSystemBars();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            hideSystemBars();
            startTripAnimation();
            hud.invalidate();
        }
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        gattQueue.clear();
        closeGattLink();
        if (Build.VERSION.SDK_INT >= 29 && thermalListener != null) {
            getSystemService(PowerManager.class).removeThermalStatusListener(thermalListener);
        }
        if (lightRegistered) sensors.unregisterListener(lightListener);
        if (obd != null) obd.destroy();
        switchSounds.release();
        switchSounds = null;
        roadMap.close();
        if (driveLog != null) driveLog.close();
        if (navServer != null) navServer.stop();
        if (navAdvertisement != null) {
            android.net.nsd.NsdManager manager = getSystemService(android.net.nsd.NsdManager.class);
            try {
                if (manager != null) manager.unregisterService(navAdvertisement);
            } catch (IllegalArgumentException alreadyUnregistered) {
                // Registration can fail before the activity is destroyed.
            }
            navAdvertisement = null;
        }
        routing.shutdownNow();
        main.removeCallbacks(f1Hold);
        if (routeDb != null) routeDb.close();
        unregisterReceiver(batteryReceiver);
        super.onDestroy();
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        scheduleClock();
        main.post(preloadSoundLater);
        updateLightSensor();
        startTripAnimation();
        connectSavedAdapter();
        updateMap();
    }

    private void raiseAlert(String text, int level) {
        raiseAlert(text, level, false);
    }

    private void raiseAlert(String text, int level, boolean silent) {
        if (hud == null || parkedBlank || thermalBlank) return;
        if (alerts.raise(text, level, SystemClock.uptimeMillis(), silent)) hud.invalidate();
    }

    private boolean ambientDark() {
        return lightSensor != null && autoLevel <= SoundPolicy.DARK_LEVEL;
    }

    private void playSound(int id, float base, SoundPolicy.Kind kind) {
        if (switchSounds == null || id == 0) return;
        float gain = sounds.gain(kind, ambientDark());
        if (gain <= 0f) return;
        float volume = Math.min(1f, base * gain);
        switchSounds.play(id, volume, volume, 1, 0, 1);
    }

    private void applyOrientation() {
        setRequestedOrientation(preferences.getBoolean("flipScreen", true)
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    private void saveSoundSettings() {
        preferences.edit().putInt("soundMode", sounds.mode).putInt("soundLevel", sounds.level)
                .putBoolean("nightQuiet", sounds.nightQuiet).apply();
        playSound(switchSoundId, 0.5f, SoundPolicy.Kind.FEEDBACK);
        if (hud != null) hud.invalidate();
    }

    private void cycleSoundMode() {
        sounds.mode = (sounds.mode + 1) % SoundPolicy.MODE_NAMES.length;
        saveSoundSettings();
    }

    private void cycleVolume() {
        sounds.level = (sounds.level + 1) % SoundPolicy.LEVELS.length;
        saveSoundSettings();
    }

    private void toggleNightQuiet() {
        sounds.nightQuiet = !sounds.nightQuiet;
        updateLightSensor();
        saveSoundSettings();
    }

    private void playAlertSound(int level) {
        int id = level == Alerts.CRITICAL ? alertCriticalLoadId : level == Alerts.WARN ? alertWarnLoadId : alertInfoLoadId;
        if (switchSounds == null || !loadedSamples.contains(id)) return;
        float volume = level == Alerts.INFO ? 0.8f : 0.95f;
        playSound(id, volume, level == Alerts.CRITICAL ? SoundPolicy.Kind.CRITICAL
                : level == Alerts.WARN ? SoundPolicy.Kind.WARNING : SoundPolicy.Kind.INFO);
    }

    private void resetTripAlerts() {
        alerts.clear();
        sawCool = warmAnnounced = hotAnnounced = fixAnnounced = false;
    }

    private void trackLink(String status, ObdBluetooth.Readings update) {
        if (update != null) {
            if (!linkLive) {
                linkLive = true;
                failedAttempts = 0;
                failureAnnounced = false;
                raiseAlert("OBD LINKED", Alerts.INFO);
            }
            return;
        }
        if (status.startsWith("LINK LOST")) {
            if (linkLive) {
                linkLive = false;
                raiseAlert("OBD LINK LOST", Alerts.WARN);
            } else if (++failedAttempts >= 3 && !failureAnnounced) {
                failureAnnounced = true;
                raiseAlert("OBD CONNECTION FAILED", Alerts.WARN);
            }
        } else if (!setupAnnounced && (status.equals("BLUETOOTH OFF") || status.equals("PAIR ADAPTER"))) {
            setupAnnounced = true;
            raiseAlert(status.equals("BLUETOOTH OFF") ? "BLUETOOTH IS OFF" : "ADAPTER NOT PAIRED", Alerts.WARN);
        }
    }

    private void trackCoolant(Double coolant) {
        if (coolant == null || demo) return;
        if (coolant < WARM_F - 8) sawCool = true;
        if (sawCool && !warmAnnounced && coolant >= WARM_F) {
            warmAnnounced = true;
            raiseAlert("OPERATING TEMP REACHED", Alerts.INFO);
        }
        if (coolant >= HOT_F && !hotAnnounced) {
            hotAnnounced = true;
            raiseAlert("COOLANT HIGH", Alerts.CRITICAL);
        } else if (coolant < HOT_F - 10) {
            hotAnnounced = false;
        }
    }

    private void updateAccessoryPower(boolean plugged) {
        if (powerKnown && powered == plugged) return;
        powerKnown = true;
        powered = plugged;
        main.removeCallbacks(confirmPowerLoss);
        if (plugged && !accessoryActive) {
            main.removeCallbacks(finishShutdown);
            accessoryActive = true;
            resetTripAlerts();
            demo = false;
            shutdownReport = null;
            shutdownStartedAt = 0;
            parkedBlank = false;
            enterStandbyPower(false);
            setBluetoothPower(true);
            setHotspotPower(true);
            startupPending = true;
            updateScreenPower();
            startTripAnimation();
            if (foreground) connectSavedAdapter();
            updateMap();
        } else if (!plugged && accessoryActive) {
            accessoryLostAt = SystemClock.elapsedRealtime();
            main.postDelayed(confirmPowerLoss, POWER_LOSS_DELAY_MS);
        } else if (!plugged) {
            setHotspotPower(false);
        }
    }

    private void startTripAnimation() {
        if (thermalLevel != THERMAL_HOLD && startupPending && foreground && hasWindowFocus()
                && getSystemService(PowerManager.class).isInteractive()) {
            startupPending = false;
            playStartupSound();
            hud.invalidate();
        }
    }

    private void updateThermalProtection() {
        boolean severe = systemThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE;
        boolean critical = systemThermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL;
        int next;
        if (batteryOverheat || critical || batteryTemperature >= 46
                || (thermalLevel == THERMAL_HOLD && (severe || batteryTemperature > 40))) {
            next = THERMAL_HOLD;
        } else if (severe || batteryTemperature >= 42
                || (thermalLevel != THERMAL_NORMAL && batteryTemperature > 39)) {
            next = THERMAL_LIMIT;
        } else {
            next = THERMAL_NORMAL;
        }
        if (next == thermalLevel) return;
        boolean reconnect = thermalLevel == THERMAL_HOLD || next == THERMAL_HOLD;
        int previousLevel = thermalLevel;
        thermalLevel = next;
        if (next == THERMAL_HOLD) playAlertSound(Alerts.CRITICAL);
        else if (next == THERMAL_LIMIT && previousLevel == THERMAL_NORMAL) raiseAlert("PHONE HOT / DIMMING", Alerts.WARN);
        else if (next == THERMAL_NORMAL) raiseAlert("THERMAL NORMAL", Alerts.INFO);
        thermalBlank = false;
        thermalStartedAt = SystemClock.uptimeMillis();
        main.removeCallbacks(finishThermalWarning);
        if (next == THERMAL_HOLD) {
            startupStartedAt = 0;
            releaseStartupSound();
            main.postDelayed(finishThermalWarning, THERMAL_WARNING_MS);
        }
        if (reconnect) connectSavedAdapter();
        updateMap();
        applyBrightness();
        updateScreenPower();
        startTripAnimation();
        hud.invalidate();
    }

    private void parkHud() {
        openPlaces = null;
        cancelPrompt = false;
        main.removeCallbacks(cancelPromptTimeout);
        accessoryActive = false;
        routeGeneration.incrementAndGet();
        if (nav != null && !nav.arrived()) {
            parkedRoute = nav;
            parkedRouteGoal = navGoal;
            parkedRouteGoalName = navGoalName;
        }
        nav = null;
        navGoal = null;
        navArrivedAt = 0;
        resumeRouteDeadline = 0;
        main.removeCallbacks(resumeRouteTimeout);
        setHotspotPower(false);
        long lostWallClock = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - accessoryLostAt);
        shutdownReport = trip.finish(accessoryLostAt, lostWallClock);
        trip = new TripStats();
        if (shutdownReport != null) {
            saveTrip(shutdownReport);
        } else if (demo) {
            shutdownReport = TripStats.report(0, 1246, 12.4, 3470, 189, 52);
        }
        startupPending = false;
        startupStartedAt = 0;
        releaseStartupSound();
        main.removeCallbacks(staleReadings);
        connectSavedAdapter();
        updateMap();
        if (foreground && getSystemService(PowerManager.class).isInteractive()) {
            shutdownStartedAt = SystemClock.uptimeMillis();
            main.postDelayed(finishShutdown, shutdownDuration());
            playSound(shutdownSoundId, 0.9f, SoundPolicy.Kind.STARTUP);
        } else {
            blankParkedHud();
        }
        hud.invalidate();
    }

    private long shutdownDuration() {
        return shutdownReport == null ? SHUTDOWN_DURATION_MS : TRIP_REPORT_MS;
    }

    private JSONArray tripHistory() {
        try {
            return new JSONArray(preferences.getString("tripLog", "[]"));
        } catch (JSONException invalid) {
            return new JSONArray();
        }
    }

    private void saveTrip(JSONObject report) {
        JSONArray previous = tripHistory();
        JSONArray recent = new JSONArray().put(report);
        for (int index = 0; index < Math.min(previous.length(), 29); index++) {
            JSONObject item = previous.optJSONObject(index);
            if (item != null) recent.put(item);
        }
        preferences.edit().putString("tripLog", recent.toString()).apply();
    }

    private void showTripHistory() {
        if (readings != null && readings.speedMph != null && readings.speedMph > 0) return;
        JSONArray history = tripHistory();
        DateFormat date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        List<String> entries = new ArrayList<>();
        for (int index = 0; index < history.length(); index++) {
            JSONObject report = history.optJSONObject(index);
            if (report == null) continue;
            entries.add(date.format(new Date(report.optLong("ended"))) + "    " + TripStats.time(report)
                    + "\n" + TripStats.value(report, "miles", "%.1f") + " est. mi    "
                    + TripStats.value(report, "mpg", "%.0f") + " est. mpg    "
                    + TripStats.value(report, "rpm", "%.0f") + " peak rpm    "
                    + TripStats.value(report, "coolant", "%.0f") + " °F peak coolant");
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("TRIP LOG")
                .setPositiveButton("CLOSE", null);
        if (entries.isEmpty()) dialog.setMessage("Completed drives with live OBD data will appear here.");
        else dialog.setItems(entries.toArray(new String[0]), null);
        dialog.show();
    }

    private void blankParkedHud() {
        shutdownStartedAt = 0;
        parkedBlank = true;
        updateScreenPower();
        enterStandbyPower(true);
        if (!accessoryActive) setBluetoothPower(false);
        hud.invalidate();
    }

    private void enterStandbyPower(boolean standby) {
        applyBrightness();
        updateLightSensor();
        if (standby) {
            if (driveLog != null) driveLog.close();
            main.removeCallbacks(preloadSoundLater);
            if (startupSound != null) {
                startupSound.release();
                startupSound = null;
                startupPlaying = false;
            }
        } else if (foreground) {
            main.postDelayed(preloadSoundLater, 400);
        }
    }

    @Override protected void onStop() {
        openPlaces = null;
        foreground = false;
        main.removeCallbacks(clockTick);
        updateLightSensor();
        updateMap();
        releaseStartupSound();
        if (!keepObdInBackground() && obd != null) {
            obd.stop();
            main.removeCallbacks(staleReadings);
            updateReadings(null);
        }
        super.onStop();
    }

    private void preloadStartupSound() {
        if (startupSound != null) return;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            MediaPlayer player = MediaPlayer.create(this, R.raw.startup_chime, attributes,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (player == null) return;
            player.setOnCompletionListener(done -> releaseStartupSound());
            player.setOnErrorListener((failed, error, detail) -> {
                releaseStartupSound();
                return true;
            });
            startupSound = player;
        } catch (IllegalStateException unavailable) {
            releaseStartupSound();
        }
    }

    private final Runnable preloadSoundLater = this::preloadStartupSound;

    private void playStartupSound() {
        preloadStartupSound();
        MediaPlayer player = startupSound;
        float chimeGain = sounds.gain(SoundPolicy.Kind.STARTUP, ambientDark());
        if (player != null && chimeGain > 0f) {
            try {
                player.setVolume(chimeGain, chimeGain);
                if (startupPlaying) player.seekTo(0);
                player.start();
                startupPlaying = true;
                Log.i("HudSound", "chime started");
            } catch (IllegalStateException unavailable) {
                releaseStartupSound();
            }
        }
        startupClockLogged = false;
        startupStartedAt = SystemClock.uptimeMillis();
    }

    private void releaseStartupSound() {
        startupPlaying = false;
        if (startupSound != null) {
            startupSound.release();
            startupSound = null;
        }
        main.removeCallbacks(preloadSoundLater);
        if (foreground && !parkedBlank) main.postDelayed(preloadSoundLater, 400);
    }

    private boolean startupPlaying;
    private boolean startupClockLogged;

    private long startupClock(long now) {
        long wall = now - startupStartedAt;
        MediaPlayer player = startupSound;
        if (player != null && startupPlaying) {
            try {
                long audioMs = -1;
                MediaTimestamp stamp = player.getTimestamp();
                if (stamp != null && stamp.getMediaClockRate() > 0) {
                    long elapsedNanos = System.nanoTime() - stamp.getAnchorSytemNanoTime();
                    audioMs = stamp.getAnchorMediaTimeUs() / 1000
                            + (long) (elapsedNanos / 1e6 * stamp.getMediaClockRate());
                }
                if (audioMs < 0) audioMs = player.getCurrentPosition();
                if (audioMs > 0 || wall < 400) {
                    if (!startupClockLogged && wall > 1000) {
                        startupClockLogged = true;
                        Log.i("HudSound", "audio clock " + audioMs + " ms, wall clock " + wall + " ms");
                    }
                    return Math.max(0, audioMs);
                }
            } catch (IllegalStateException failed) {
                Log.i("HudSound", "clock error " + failed);
            }
        }
        return Math.max(0, wall);
    }

    /**
     * Switches the Bluetooth radio so it is off while parked. Android only lets privileged system apps do this;
     * anywhere else the call is refused and nothing changes (the link code still waits for Bluetooth to appear).
     */
    @SuppressWarnings("deprecation")
    private void setBluetoothPower(boolean on) {
        if (bluetooth == null || !hasBluetoothPermission()) return;
        if (!on && !preferences.getBoolean("btParkOff", true)) return;
        try {
            if (bluetooth.isEnabled() == on) return;
            boolean done = on ? bluetooth.enable() : bluetooth.disable();
            if (on && obd != null) obd.expectBluetooth(10_000);
            Log.i("HudBt", (on ? "enable" : "disable") + " requested, accepted=" + done);
        } catch (SecurityException refused) {
            Log.i("HudBt", "not allowed to switch Bluetooth (not a privileged app)");
        }
    }

    private void setHotspotPower(boolean on) {
        if (!preferences.getBoolean("hotspotWithIgnition", true)) return;
        if (checkSelfPermission("android.permission.TETHER_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
            Log.i("HudHotspot", "Ignition control needs privileged tethering permission");
            return;
        }
        HotspotPower.set(this, on);
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasPreciseLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean gpsEnabled() {
        return locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    private void enableMapLocation() {
        if (!hasPreciseLocation()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, GPS_PERMISSION);
        } else if (!gpsEnabled()) {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } else if (!powered && !accessoryActive) {
            setGpsTest(!gpsTestActive);
            updateMap();
            hud.invalidate();
        }
    }

    private void setGpsTest(boolean active) {
        gpsTestActive = active;
        main.removeCallbacks(finishGpsTest);
        if (active) main.postDelayed(finishGpsTest, GPS_TEST_MS);
        updateScreenPower();
    }

    private void updateMap() {
        boolean liveMap = foreground && mapPreview && !demo && thermalLevel == THERMAL_NORMAL;
        boolean wantInfo = foreground && (roadInfoOn || nav != null) && !demo && thermalLevel == THERMAL_NORMAL;
        if (gpsTestActive && (!liveMap || accessoryActive)) setGpsTest(false);
        if (liveMap || wantInfo) roadMap.open();
        if (liveMap) refreshMapTiles();
        boolean wantGps = (liveMap || wantInfo) && (accessoryActive || gpsTestActive)
                && hasPreciseLocation() && locationManager != null;
        if (wantGps == gpsRegistered) return;
        if (wantGps) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0,
                        gpsListener, Looper.getMainLooper());
                gpsRegistered = true;
                if (gpsEnabled()) scheduleGpsAvailability(0);
            } catch (IllegalArgumentException | SecurityException unavailable) {
                hud.invalidate();
                main.removeCallbacks(retryGps);
                main.postDelayed(retryGps, GPS_RETRY_MS);
            }
        } else {
            gpsRegistered = false;
            fixAnnounced = false;
            locationManager.removeUpdates(gpsListener);
            cancelGpsAvailability();
            main.removeCallbacks(expireGpsFix);
            expireGpsFix.run();
            if (liveMap) refreshMapTiles();
        }
    }

    private void scheduleGpsAvailability(long fixAge) {
        cancelGpsAvailability();
        main.postDelayed(showGpsUnavailable, GPS_UNAVAILABLE_MS - Math.max(0, fixAge));
    }

    private void cancelGpsAvailability() {
        main.removeCallbacks(showGpsUnavailable);
        gpsUnavailable = false;
    }

    private void refreshMapTiles() {
        OfflineRoadMap.Region region = roadMap.region();
        if (!foreground || !mapPreview || demo || region == null || thermalLevel != THERMAL_NORMAL) return;
        Location position = mapPosition(region);
        roadMap.request(OfflineRoadMap.tileX(position == null ? region.longitude : position.getLongitude()),
                OfflineRoadMap.tileY(position == null ? region.latitude : position.getLatitude()));
    }

    private Location mapPosition(OfflineRoadMap.Region region) {
        if (lastMapFix == null || !region.contains(lastMapFix.getLatitude(), lastMapFix.getLongitude())) return null;
        double[] estimate = estimator.at(SystemClock.elapsedRealtime());
        if (estimate == null) return lastMapFix;
        estimatedFix.setLatitude(estimate[0]);
        estimatedFix.setLongitude(estimate[1]);
        return estimatedFix;
    }

    private boolean fixInRegion(OfflineRoadMap.Region region) {
        return gpsFix != null && region.contains(gpsFix.getLatitude(), gpsFix.getLongitude());
    }

    private boolean fullMapActive() {
        return fullMap && mapPreview && thermalLevel == THERMAL_NORMAL;
    }

    private static final Pattern PROBE_REQUEST = Pattern.compile("(01|02|09|21|22)[0-9A-F]{2,6}");

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String preview = intent == null ? null : intent.getStringExtra("hud_alert");
        if (preview != null) {
            alerts.forget();
            switch (preview) {
                case "linked": raiseAlert("OBD LINKED", Alerts.INFO); break;
                case "lost": raiseAlert("OBD LINK LOST", Alerts.WARN); break;
                case "failed": raiseAlert("OBD CONNECTION FAILED", Alerts.WARN); break;
                case "warm": raiseAlert("OPERATING TEMP REACHED", Alerts.INFO); break;
                case "hot": raiseAlert("COOLANT HIGH", Alerts.CRITICAL); break;
                case "gps": raiseAlert("GPS LOCKED", Alerts.INFO); break;
                case "thermal": raiseAlert("PHONE HOT / DIMMING", Alerts.WARN); break;
                default: break;
            }
        }
        String requested = intent == null ? null : intent.getStringExtra("obd_probe");
        if (requested == null || obd == null) return;
        List<String> accepted = new ArrayList<>();
        for (String request : requested.toUpperCase(Locale.US).split(",")) {
            String trimmed = request.trim();
            if (PROBE_REQUEST.matcher(trimmed).matches() && accepted.size() < 32) accepted.add(trimmed);
        }
        if (!accepted.isEmpty()) obd.probe(accepted.toArray(new String[0]));
    }

    private boolean keepObdInBackground() {
        return accessoryActive && powered && !demo && thermalLevel != THERMAL_HOLD;
    }

    private void connectSavedAdapter() {
        String address = preferences.getString("adapterAddress", "");
        if (obd != null && obd.isRunning(address) && keepObdInBackground() && hasBluetoothPermission()) {
            hud.invalidate(); // Already linked; do not drop the session.
            return;
        }
        updateReadings(null);
        linkLive = failureAnnounced = setupAnnounced = false;
        autoStop.reset();
        failedAttempts = 0;
        java.util.Arrays.fill(seenGauge, false);
        if (obd != null) obd.stop();
        if (thermalLevel == THERMAL_HOLD) linkStatus = "THERMAL HOLD";
        else if (bluetooth == null) linkStatus = "BLUETOOTH UNAVAILABLE";
        else if (demo) linkStatus = "DEMO ACTIVE";
        else if (!accessoryActive) linkStatus = "ACCESSORY OFF";
        else if (!hasBluetoothPermission()) linkStatus = "BT PERMISSION NEEDED";
        else if (address.isEmpty()) linkStatus = "SELECT BT ADAPTER";
        else if (foreground) {
            linkStatus = "BT CONNECTING";
            obd.start(address);
        }
        hud.invalidate();
    }

    private void lookupRoadInfo(Location location) {
        if (!roadInfoOn || demo || roadMap == null) return;
        long now = SystemClock.uptimeMillis();
        float[] moved = new float[1];
        Location.distanceBetween(lastLookupLat, lastLookupLon, location.getLatitude(), location.getLongitude(), moved);
        if (lastLookupAt != 0 && (now - lastLookupAt < 1500 || (moved[0] < 15 && now - lastLookupAt < 8000))) return;
        lastLookupAt = now;
        lastLookupLat = location.getLatitude();
        lastLookupLon = location.getLongitude();
        Double speed = currentSpeedMph();
        roadMap.lookup(location.getLatitude(), location.getLongitude(),
                gpsCourse, speed == null ? 0 : speed, info -> {
            if (info != null && (roadInfo == null || roadInfo.aheadKind != info.aheadKind)) {
                Log.i("HudAhead", info.aheadKind == 0 ? "ahead: nothing" : "ahead: " + aheadLine(info) + " on "
                        + info.road);
            }
            roadInfo = info;
            checkOverLimit();
            announceAhead(info);
            hud.invalidate();
        });
    }

    private OfflineRoadMap.RoadInfo shownRoadInfo() {
        if (!roadInfoOn) return null;
        return demo ? DEMO_ROAD_INFO : gpsFix == null ? null : roadInfo;
    }

    private void announceAhead(OfflineRoadMap.RoadInfo info) {
        if (info == null || demo) return;
        if (info.aheadKind == RoadLookup.CROSSING) raiseAlert("RAIL CROSSING AHEAD", Alerts.WARN);
        else if (info.aheadKind == RoadLookup.CAMERA) raiseAlert("CAMERA AHEAD", Alerts.INFO);
        else if (info.aheadKind == RoadLookup.CURVE) raiseAlert("CURVE AHEAD", Alerts.INFO);
    }

    private static String aheadLine(OfflineRoadMap.RoadInfo info) {
        if (info == null || info.aheadKind == 0) return null;
        String what;
        switch (info.aheadKind) {
            case RoadLookup.SIGNAL: what = "SIGNAL"; break;
            case RoadLookup.STOP: what = "STOP SIGN"; break;
            case RoadLookup.CROSSING: what = "RAIL CROSSING"; break;
            case RoadLookup.CAMERA: what = info.aheadName + " CAMERA"; break;
            case RoadLookup.CURVE: what = "CURVE  ADVISED " + info.aheadName; break;
            default: what = info.aheadName == null ? "" : info.aheadName; break;
        }
        double feet = info.aheadMeters * 3.28084;
        String distance = feet < 800 ? Math.max(50, Math.round(feet / 50) * 50) + " FT"
                : String.format(Locale.US, "%.1f MI", info.aheadMeters / 1609.344);
        return what + "  " + distance;
    }

    private Double currentSpeedMph() {
        return readings != null && readings.speedMph != null ? readings.speedMph : gpsSpeedMph();
    }

    private void checkOverLimit() {
        OfflineRoadMap.RoadInfo info = roadInfo;
        Double speed = currentSpeedMph();
        if (demo || info == null || info.limitMph <= 0 || speed == null || speed < info.limitMph + 10) {
            overSince = 0;
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (overSince == 0) overSince = now;
        if (now - overSince >= 4000 && now - lastOverAlertAt >= 90_000) {
            lastOverAlertAt = now;
            raiseAlert("OVER LIMIT " + info.limitMph, Alerts.INFO, true);
        }
    }

    private Double gpsSpeedMph() {
        Location fix = gpsFix;
        if (fix == null || !gpsRegistered) return null;
        long age = (SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos()) / 1000000;
        boolean derived = !fix.hasSpeed() && !Float.isNaN(derivedSpeedMps);
        return SpeedSource.gpsMph(fix.hasSpeed() || derived, derived ? derivedSpeedMps : fix.getSpeed(), age);
    }

    private void updateReadings(ObdBluetooth.Readings update) {
        if (update != null && update.speedMph == null && !demo) {
            Double fromGps = gpsSpeedMph();
            if (fromGps != null) update = update.withSpeed(fromGps);
        }
        readings = update;
        if (update != null) {
            seenGauge[0] |= update.throttle != null;
            seenGauge[1] |= update.load != null;
            seenGauge[2] |= update.load != null && update.rpm != null;
            seenGauge[3] |= update.intakeF != null;
            trackCoolant(update.coolantF);
            if (accessoryActive && !demo) {
                AutoStopTracker.Event event = autoStop.update(update.rpm, update.speedMph, powered,
                        SystemClock.elapsedRealtime());
                if (event != AutoStopTracker.Event.NONE && !parkedBlank && !thermalBlank
                        && switchSounds != null) {
                    int id = event == AutoStopTracker.Event.STOP ? autoStopLoadId : autoStartLoadId;
                    if (loadedSamples.contains(id)) playSound(id, 0.55f, SoundPolicy.Kind.UI);
                    Log.i("HudSound", event == AutoStopTracker.Event.STOP ? "auto-stop" : "auto-restart");
                }
            }
        }
        if (update == null) {
            trip.disconnect();
            rpmNext = 0;
            rpmCount = 0;
            return;
        }
        if (accessoryActive && powered && !demo) {
            trip.sample(update, SystemClock.elapsedRealtime());
            if (driveLog == null) {
                java.io.File external = getExternalFilesDir(null);
                if (external != null) driveLog = new DriveLog(new java.io.File(external, "logs"));
            }
            if (driveLog != null) {
                driveLog.sample(SystemClock.elapsedRealtime(), update, gpsFix, gpsSpeedMph(), shownRoadInfo());
            }
            checkOverLimit();
        }
        rpmHistory[rpmNext] = update.rpm == null ? Float.NaN : update.rpm.floatValue();
        rpmNext = (rpmNext + 1) % rpmHistory.length;
        rpmCount = Math.min(rpmCount + 1, rpmHistory.length);
    }

    private void showLinkMenu() {
        if (!hasBluetoothPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 23);
            return;
        }
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("View: " + (!mapPreview ? "GAUGES" : fullMap ? "FULL MAP" : "SPLIT MAP"));
        actions.add(() -> activateControl(4));
        labels.add("Brightness: " + BRIGHTNESS_NAMES[brightnessMode]);
        actions.add(() -> activateControl(2));
        labels.add("Full map readout: " + READOUT_NAMES[mapReadout]);
        actions.add(() -> {
            mapReadout = (mapReadout + 1) % READOUT_NAMES.length;
            preferences.edit().putInt("mapReadoutMode", mapReadout).apply();
            hud.invalidate();
        });
        labels.add("Colors: " + PALETTE_NAMES[Math.max(0, Math.min(PALETTE_NAMES.length - 1,
                preferences.getInt("palette", 0)))]);
        actions.add(() -> {
            preferences.edit().putInt("palette", (preferences.getInt("palette", 0) + 1) % PALETTE_NAMES.length).apply();
            hud.applyPalette();
            hud.invalidate();
        });
        labels.add("Mirror: " + (mirror ? "ON" : "OFF"));
        actions.add(() -> activateControl(0));
        labels.add("Road info (limit, road, fuel): " + (roadInfoOn ? "ON" : "OFF"));
        actions.add(() -> {
            roadInfoOn = !roadInfoOn;
            preferences.edit().putBoolean("roadInfo", roadInfoOn).apply();
            roadInfo = null;
            lastLookupAt = 0;
            updateMap();
            hud.invalidate();
        });
        labels.add("Navigation: send routes to the HUD");
        actions.add(this::showNavPairing);
        if (nav != null) {
            labels.add("Cancel route to " + (nav.name.isEmpty() ? "destination" : nav.name));
            actions.add(this::cancelRoute);
        }
        labels.add("Sound: " + SoundPolicy.MODE_NAMES[sounds.mode]);
        actions.add(this::cycleSoundMode);
        labels.add("Volume: " + Math.round(SoundPolicy.LEVELS[sounds.level] * 100) + "%");
        actions.add(this::cycleVolume);
        labels.add("Night quiet: " + (sounds.nightQuiet ? "ON" : "OFF"));
        actions.add(this::toggleNightQuiet);
        labels.add("Trip log");
        actions.add(this::showTripHistory);
        labels.add("Demo: " + (demo ? "ON" : "OFF"));
        actions.add(() -> activateControl(1));
        labels.add("Flip screen 180: " + (preferences.getBoolean("flipScreen", true) ? "ON" : "OFF"));
        actions.add(() -> {
            preferences.edit().putBoolean("flipScreen", !preferences.getBoolean("flipScreen", true)).apply();
            applyOrientation();
        });
        labels.add("Bluetooth off when parked: " + (preferences.getBoolean("btParkOff", true) ? "ON" : "OFF"));
        actions.add(() -> preferences.edit().putBoolean("btParkOff", !preferences.getBoolean("btParkOff", true)).apply());
        labels.add("Hotspot follows ignition: " + (preferences.getBoolean("hotspotWithIgnition", true) ? "ON" : "OFF"));
        actions.add(() -> {
            boolean enabled = !preferences.getBoolean("hotspotWithIgnition", true);
            preferences.edit().putBoolean("hotspotWithIgnition", enabled).apply();
            if (enabled) setHotspotPower(accessoryActive);
        });
        labels.add("Choose paired OBD adapter");
        actions.add(this::chooseAdapter);
        labels.add("Pair device / enable Bluetooth");
        actions.add(() -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        labels.add("Forget saved adapter");
        actions.add(() -> {
            preferences.edit().remove("adapterAddress").remove("adapterName").apply();
            connectSavedAdapter();
        });
        labels.add("Android settings");
        actions.add(() -> startActivity(new Intent(Settings.ACTION_SETTINGS)));
        new AlertDialog.Builder(this)
                .setTitle("SETTINGS / " + linkStatus)
                .setItems(labels.toArray(new String[0]), (dialog, choice) -> actions.get(choice).run())
                .setNegativeButton("CLOSE", null).show();
    }

    private void selectAdapter(String address, String name) {
        preferences.edit().putString("adapterAddress", address).putString("adapterName", name).apply();
        demo = false;
        connectSavedAdapter();
    }

    private void chooseAdapter() {
        if (bluetooth == null) return;
        try {
            if (!bluetooth.isEnabled()) {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                return;
            }
            List<BluetoothDevice> paired = new ArrayList<>(bluetooth.getBondedDevices());
            paired.sort((first, second) -> first.getAddress().compareTo(second.getAddress()));
            if (paired.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("PAIR YOUR OBD ADAPTER")
                        .setNeutralButton("SIMULATED", (dialog, which) -> selectAdapter(SimulatedElm.ADDRESS, SimulatedElm.NAME))
                        .setMessage("Plug the Veepeak into the car and switch the ignition on. Pair it in Android Bluetooth settings, then return here and choose it.")
                        .setPositiveButton("BLUETOOTH SETTINGS", (dialog, which) ->
                                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                        .setNegativeButton("CANCEL", null).show();
                return;
            }
            String[] labels = new String[paired.size() + 1];
            labels[0] = "[SIMULATED ADAPTER]\nscripted drive, no hardware";
            for (int index = 0; index < paired.size(); index++) {
                BluetoothDevice device = paired.get(index);
                labels[index + 1] = (device.getName() == null ? "Bluetooth device" : device.getName())
                        + "\n" + device.getAddress();
            }
            new AlertDialog.Builder(this).setTitle("SELECT OBD ADAPTER")
                    .setItems(labels, (dialog, index) -> {
                        if (index == 0) {
                            selectAdapter(SimulatedElm.ADDRESS, SimulatedElm.NAME);
                            return;
                        }
                        BluetoothDevice device = paired.get(index - 1);
                        selectAdapter(device.getAddress(), device.getName() == null ? "OBD ADAPTER" : device.getName());
                    }).setNegativeButton("CANCEL", null).show();
        } catch (SecurityException denied) {
            linkStatus = "BT PERMISSION NEEDED";
            hud.invalidate();
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 23) {
            connectSavedAdapter();
            if (hasBluetoothPermission()) showLinkMenu();
        } else if (code == GPS_PERMISSION) {
            updateMap();
            hud.invalidate();
        }
    }

    private String wholeReading(Double value, String missing) {
        return value == null ? missing : String.valueOf(Math.round(value));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("startupStartedAt", startupStartedAt);
        state.putBoolean("accessoryActive", accessoryActive);
        state.putBoolean("startupPending", startupPending);
        state.putBoolean("parkedBlank", parkedBlank || shutdownStartedAt != 0);
        state.putBundle("trip", trip.save());
        super.onSaveInstanceState(state);
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void updateScreenPower() {
        boolean drivingDisplay = !thermalBlank && (accessoryActive || shutdownStartedAt != 0);
        setShowWhenLocked(drivingDisplay);
        if (drivingDisplay || gpsTestActive) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void applyBrightness() {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        if (parkedBlank) {
            attributes.screenBrightness = 0f;
            getWindow().setAttributes(attributes);
            return;
        }
        float base = brightnessMode == 0 ? autoLevel : BRIGHTNESS_LEVELS[brightnessMode];
        // Thermal protection always wins over the chosen level.
        attributes.screenBrightness = thermalLevel == THERMAL_HOLD ? 0.03f
                : thermalLevel == THERMAL_LIMIT ? Math.min(base, 0.18f) : base;
        getWindow().setAttributes(attributes);
    }

    private void updateLightSensor() {
        boolean wanted = foreground && !parkedBlank && (brightnessMode == 0 || sounds.nightQuiet) && lightSensor != null;
        if (wanted == lightRegistered) return;
        lightRegistered = wanted;
        if (wanted) {
            autoBrightness.reset();
            sensors.registerListener(lightListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            sensors.unregisterListener(lightListener);
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (resumeRouteDeadline != 0 && keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F4) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && hasWindowFocus()) {
                if (keyCode == KeyEvent.KEYCODE_F1) answerResumeRoute(true);
                if (keyCode == KeyEvent.KEYCODE_F2) answerResumeRoute(false);
            }
            return true;
        }
        if (cancelPrompt && keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F4) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && hasWindowFocus()) {
                if (keyCode == KeyEvent.KEYCODE_F1) cancelRoute();
                else dismissCancelPrompt();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_F5 || keyCode == KeyEvent.KEYCODE_F6) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                    && hasWindowFocus() && !parkedBlank && shutdownStartedAt == 0 && thermalLevel != THERMAL_HOLD) {
                if (keyCode == KeyEvent.KEYCODE_F5) cycleSoundMode();
                else cycleVolume();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_F1) return handleF1(event);
        if (quickOpen && (keyCode == KeyEvent.KEYCODE_F2 || keyCode == KeyEvent.KEYCODE_F3 || keyCode == KeyEvent.KEYCODE_F4)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) quickKey(keyCode);
            return true;
        }
        int control;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_F2: control = 6; break;
            case KeyEvent.KEYCODE_F3: control = 7; break;
            case KeyEvent.KEYCODE_F4: control = 10; break;
            default: return super.dispatchKeyEvent(event);
        }
        if ((control == 6 || control == 7) && !mapPreview && openPlaces == null) return true;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && hasWindowFocus() && !parkedBlank && shutdownStartedAt == 0 && thermalLevel != THERMAL_HOLD
                && (startupStartedAt == 0 || startupClock(SystemClock.uptimeMillis()) >= STARTUP_DURATION_MS)) {
            if (openPlaces == null) activateControl(control);
            else if (keyCode == KeyEvent.KEYCODE_F4) {
                selectedPlace = (selectedPlace + 1) % openPlaces.length();
                hud.invalidate();
            } else if (keyCode == KeyEvent.KEYCODE_F1) {
                chooseSavedPlace();
            } else if (keyCode == KeyEvent.KEYCODE_F2) {
                openPlaces = null;
                hud.invalidate();
            }
        }
        return true;
    }

    private JSONArray openPlaces;
    private int selectedPlace;

    private JSONArray savedPlaces() {
        try {
            return new JSONArray(preferences.getString("savedPlaces", "[]"));
        } catch (JSONException invalid) {
            return new JSONArray();
        }
    }

    private void showSavedPlaces() {
        JSONArray places = savedPlaces();
        if (places.length() == 0) {
            raiseAlert("ADD SAVED PLACES ON IPHONE", Alerts.INFO);
            hud.invalidate();
            return;
        }
        openPlaces = places;
        selectedPlace = 0;
        hud.invalidate();
    }

    private void chooseSavedPlace() {
        JSONObject place = openPlaces.optJSONObject(selectedPlace);
        openPlaces = null;
        if (place != null) routeToSavedPlace(place);
        hud.invalidate();
    }

    private void routeToSavedPlace(JSONObject place) {
        String problem = routeTo(place.optDouble("lat"), place.optDouble("lon"), place.optString("name"), false);
        if (problem != null) {
            raiseAlert(problem.toUpperCase(Locale.US), Alerts.WARN);
            hud.invalidate();
        } else if (!mapPreview || demo) {
            if (demo) {
                demo = false;
                connectSavedAdapter();
            }
            setHudView(true, true);
            hud.invalidate();
        }
    }

    private void setMirror(boolean enabled) {
        mirror = enabled;
        preferences.edit().putBoolean("mirror", mirror).apply();
    }

    private void setBrightnessMode(int mode) {
        brightnessMode = mode;
        preferences.edit().putInt("brightnessMode", brightnessMode).apply();
        updateLightSensor();
        applyBrightness();
    }

    private void setHudView(boolean map, boolean full) {
        if (map && !mapPreview) mapPreviewStartedAt = SystemClock.uptimeMillis();
        mapPreview = map;
        fullMap = full;
        preferences.edit().putBoolean("mapPreview", mapPreview).putBoolean("fullMap", fullMap).apply();
        updateMap();
    }

    private boolean handleF1(KeyEvent event) {
        boolean usable = hasWindowFocus() && !parkedBlank && shutdownStartedAt == 0 && thermalLevel != THERMAL_HOLD
                && (startupStartedAt == 0 || startupClock(SystemClock.uptimeMillis()) >= STARTUP_DURATION_MS);
        if (!usable) return true;
        if (openPlaces != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) chooseSavedPlace();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                f1Down = true;
                f1HoldFired = false;
                main.removeCallbacks(f1Hold);
                main.postDelayed(f1Hold, F1_HOLD_MS);
            } else if (f1Down && !f1HoldFired) { // the keyboard's own auto-repeat means it is being held
                main.removeCallbacks(f1Hold);
                f1Hold.run();
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            main.removeCallbacks(f1Hold);
            if (f1Down && !f1HoldFired) {
                if (quickOpen) quickKey(KeyEvent.KEYCODE_F1);
                else activateControl(4);
            }
            f1Down = false;
        }
        return true;
    }

    private void openQuick() {
        quickOpen = true;
        quickRow = 0;
        quickGasNote = "";
        refreshQuick();
        touchQuick();
        main.removeCallbacks(quickTick);
        main.postDelayed(quickTick, QUICK_REFRESH_MS);
        playSound(switchSoundId, 0.42f, SoundPolicy.Kind.UI);
        hud.invalidate();
    }

    private void closeQuick() {
        if (!quickOpen) return;
        quickOpen = false;
        main.removeCallbacks(quickIdle);
        main.removeCallbacks(quickTick);
        playSound(switchSoundId, 0.3f, SoundPolicy.Kind.UI);
        hud.invalidate();
    }

    private void touchQuick() {
        main.removeCallbacks(quickIdle);
        main.postDelayed(quickIdle, QUICK_IDLE_MS);
    }

    private void quickKey(int keyCode) {
        touchQuick();
        if (keyCode == KeyEvent.KEYCODE_F1) {
            quickRow = (quickRow + 1) % 3;
            playSound(switchSoundId, 0.3f, SoundPolicy.Kind.UI);
        } else if (keyCode == KeyEvent.KEYCODE_F3) {
            closeQuick();
            return;
        } else if (keyCode == KeyEvent.KEYCODE_F4 || quickRow == 0) {
            activateControl(2);
        } else if (quickRow == 2) {
            playSound(switchSoundId, 0.42f, SoundPolicy.Kind.UI);
            routeToNearestGas();
        } else {
            preferences.edit().putInt("palette", (preferences.getInt("palette", 0) + 1) % PALETTE_NAMES.length).apply();
            hud.applyPalette();
            playSound(switchSoundId, 0.42f, SoundPolicy.Kind.UI);
        }
        hud.invalidate();
    }

    private void routeToNearestGas() {
        Location from = gpsFix;
        if (from == null) {
            quickGasNote = "NO GPS FIX";
            return;
        }
        RouteDatabase graph = routeDatabase();
        if (graph == null) {
            quickGasNote = "NO ROUTE DATA";
            return;
        }
        if (routingBusy) {
            quickGasNote = "BUSY";
            return;
        }
        quickGasNote = "SEARCHING";
        double fromLat = from.getLatitude(), fromLon = from.getLongitude();
        double heading = from.hasBearing() && from.hasSpeed() && from.getSpeed() > 2.5 ? from.getBearing() : Double.NaN;
        roadMap.nearestFuel(fromLat, fromLon, 4, stations -> {
            if (stations.isEmpty()) {
                quickGasNote = "NONE NEARBY";
                hud.invalidate();
                return;
            }
            if (routingBusy) {
                quickGasNote = "BUSY";
                hud.invalidate();
                return;
            }
            routingBusy = true;
            routing.execute(() -> {
                Router.Result best = null;
                OfflineRoadMap.Fuel pick = null;
                for (OfflineRoadMap.Fuel station : stations) {
                    try {
                        Router.Result result = new Router(graph).route(fromLat, fromLon, heading, station.lat, station.lon);
                        if (result.error == null && (best == null || result.seconds < best.seconds)) {
                            best = result;
                            pick = station;
                        }
                    } catch (RuntimeException | OutOfMemoryError broken) {
                        android.util.Log.w("HudRoute", "Fuel route failed", broken);
                    }
                }
                Router.Result chosen = best;
                OfflineRoadMap.Fuel station = pick;
                main.post(() -> {
                    routingBusy = false;
                    if (chosen == null) {
                        quickGasNote = "NO ROUTE";
                        raiseAlert("NO ROUTE TO GAS", Alerts.WARN);
                        hud.invalidate();
                        return;
                    }
                    String name = station.name.isEmpty() ? "GAS STATION" : station.name;
                    startRoute(new RouteTrack(name, chosen.lat, chosen.lon, chosen.steps, chosen.seconds), true);
                    navGoal = new double[]{station.lat, station.lon};
                    navGoalName = name;
                    closeQuick();
                });
            });
        });
        hud.invalidate();
    }

    private void refreshQuick() {
        List<String[]> rows = new ArrayList<>();
        boolean low = battery >= 0 && battery < 20 && !powered;
        rows.add(new String[]{"PHONE BATTERY", battery < 0 ? "--" : battery + "%" + (powered ? "  CHARGING" : ""),
                low ? "warn" : ""});
        float c = batteryTemperature;
        String[] status = {"", "  WARM", "  HOT", "  HOT", "  CRITICAL", "  CRITICAL", "  CRITICAL"};
        int level = Math.max(0, Math.min(status.length - 1, systemThermalStatus));
        rows.add(new String[]{"PHONE TEMP", Math.round(c) + " C / " + Math.round(c * 9 / 5 + 32) + " F" + status[level],
                c >= 40 || systemThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ? "warn" : ""});
        rows.addAll(peripheralRows());
        quickHealth = rows;
    }

    private final java.util.Map<String, Integer> gattBatteryLevels = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> gattBatteryAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.ArrayDeque<BluetoothDevice> gattQueue = new java.util.ArrayDeque<>();
    private android.bluetooth.BluetoothGatt gattLink;
    private Runnable gattFinish;
    private long gattReadAt;
    private static final long GATT_STALE_MS = 10 * 60_000;
    private static final java.util.UUID BATTERY_SERVICE =
            java.util.UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final java.util.UUID BATTERY_LEVEL =
            java.util.UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    /**
     * Reads the standard GATT Battery Level of each paired Bluetooth keyboard, one device at a time and at most once
     * a minute. This works on any Android version and needs no hidden API. Readings expire after ten minutes so an
     * old value is never shown as current.
     */
    private void readKeyboardBatteriesOverGatt() {
        long now = SystemClock.elapsedRealtime();
        if (bluetooth == null || !hasBluetoothPermission() || now - gattReadAt < 60_000
                || gattLink != null || !gattQueue.isEmpty()) return;
        gattReadAt = now;
        try {
            for (BluetoothDevice device : bluetooth.getBondedDevices()) {
                if (device.getBluetoothClass() != null && device.getBluetoothClass().getMajorDeviceClass()
                        == android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL) gattQueue.add(device);
            }
        } catch (SecurityException refused) {
            Log.i("HudKbd", "Bluetooth battery read not allowed");
            return;
        }
        readNextBatteryOverGatt();
    }

    private void readNextBatteryOverGatt() {
        BluetoothDevice device = gattQueue.poll();
        if (device == null || isDestroyed()) return;
        final String name = device.getName();
        if (name == null) {
            readNextBatteryOverGatt();
            return;
        }
        final String tag = "kbd#" + Integer.toHexString(device.getAddress().hashCode());
        final Runnable finish = new Runnable() {
            private boolean done;
            @Override public void run() {
                if (done) return;
                done = true;
                main.removeCallbacks(this);
                closeGattLink();
                readNextBatteryOverGatt();
            }
        };
        android.bluetooth.BluetoothGattCallback callback = new android.bluetooth.BluetoothGattCallback() {
            @Override public void onConnectionStateChange(android.bluetooth.BluetoothGatt gatt, int status, int state) {
                Log.i("HudKbd", tag + " GATT state=" + state + " status=" + status);
                if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) gatt.discoverServices();
                else main.post(finish);
            }

            @Override public void onServicesDiscovered(android.bluetooth.BluetoothGatt gatt, int status) {
                android.bluetooth.BluetoothGattService service = gatt.getService(BATTERY_SERVICE);
                android.bluetooth.BluetoothGattCharacteristic level =
                        service == null ? null : service.getCharacteristic(BATTERY_LEVEL);
                Log.i("HudKbd", tag + " battery service " + (level == null ? "missing" : "found"));
                if (level == null || !gatt.readCharacteristic(level)) gatt.disconnect();
            }

            @Override public void onCharacteristicRead(android.bluetooth.BluetoothGatt gatt,
                    android.bluetooth.BluetoothGattCharacteristic characteristic, byte[] value, int status) {
                batteryRead(gatt, value, status);
            }

            @SuppressWarnings("deprecation")
            @Override public void onCharacteristicRead(android.bluetooth.BluetoothGatt gatt,
                    android.bluetooth.BluetoothGattCharacteristic characteristic, int status) {
                if (Build.VERSION.SDK_INT < 33) batteryRead(gatt, characteristic.getValue(), status);
            }

            private void batteryRead(android.bluetooth.BluetoothGatt gatt, byte[] value, int status) {
                if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS && value != null && value.length > 0
                        && (value[0] & 0xff) <= 100) {
                    gattBatteryLevels.put(name, value[0] & 0xff);
                    gattBatteryAt.put(name, SystemClock.elapsedRealtime());
                    main.post(() -> {
                        if (quickOpen) refreshQuick();
                        hud.invalidate();
                    });
                }
                gatt.disconnect();
            }
        };
        try {
            gattFinish = finish;
            gattLink = device.connectGatt(getApplicationContext(), false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gattLink == null) {
                finish.run();
                return;
            }
            main.postDelayed(finish, 12_000); // never hold the link open if the keyboard is asleep or out of range
        } catch (SecurityException refused) {
            finish.run();
        }
    }

    private void closeGattLink() {
        if (gattFinish != null) main.removeCallbacks(gattFinish);
        gattFinish = null;
        android.bluetooth.BluetoothGatt link = gattLink;
        gattLink = null;
        if (link != null) {
            try {
                link.close();
            } catch (SecurityException ignored) {
                // Permission was withdrawn; nothing left to release.
            }
        }
    }

    private List<String[]> peripheralRows() {
        List<String[]> rows = new ArrayList<>();
        java.util.Map<String, Integer> bluetoothLevels = new java.util.HashMap<>();
        long fresh = SystemClock.elapsedRealtime() - GATT_STALE_MS;
        for (java.util.Map.Entry<String, Integer> reading : gattBatteryLevels.entrySet()) {
            Long at = gattBatteryAt.get(reading.getKey());
            if (at != null && at >= fresh) bluetoothLevels.put(reading.getKey(), reading.getValue());
        }
        bluetoothLevels.putAll(bondedBatteryLevels());
        readKeyboardBatteriesOverGatt();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual() || device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                continue;
            }
            int percent = -1;
            if (Build.VERSION.SDK_INT >= 31) {
                android.hardware.BatteryState state = device.getBatteryState();
                if (state != null && state.isPresent() && !Float.isNaN(state.getCapacity())) {
                    percent = Math.round(state.getCapacity() * 100f);
                }
            }
            if (percent < 0 && bluetoothLevels.containsKey(device.getName())) percent = bluetoothLevels.get(device.getName());
            if (percent < 0 && bluetoothLevels.size() == 1) percent = bluetoothLevels.values().iterator().next();
            String name = device.getName() == null ? "KEYBOARD" : device.getName().toUpperCase(Locale.US);
            if (name.length() > 22) name = name.substring(0, 22);
            rows.add(new String[]{name, percent >= 0 ? percent + "%" : "CONNECTED", percent >= 0 && percent < 20 ? "warn" : ""});
            if (rows.size() >= 2) break;
        }
        if (rows.isEmpty()) rows.add(new String[]{"KEYBOARD", "NOT CONNECTED", ""});
        return rows;
    }

    /** Battery levels of paired Bluetooth keyboards, by name. The call is hidden API, so it may be refused. */
    private java.util.Map<String, Integer> bondedBatteryLevels() {
        java.util.Map<String, Integer> levels = new java.util.HashMap<>();
        try {
            if (bluetooth == null || !hasBluetoothPermission()) return levels;
            for (BluetoothDevice device : bluetooth.getBondedDevices()) {
                if (device.getBluetoothClass() == null || device.getBluetoothClass().getMajorDeviceClass()
                        != android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL) continue;
                int level = (Integer) BluetoothDevice.class.getMethod("getBatteryLevel").invoke(device);
                if (level >= 0 && level <= 100) levels.put(device.getName(), level);
            }
        } catch (ReflectiveOperationException | RuntimeException refused) {
            // No hidden API here: the keyboard just shows as connected.
        }
        return levels;
    }

    private void activateControl(int control) {
        boolean skipClick = false;
        switch (control) {
            case 0:
                setMirror(!mirror);
                break;
            case 1:
                demo = !demo;
                main.removeCallbacks(staleReadings);
                connectSavedAdapter();
                updateMap();
                break;
            case 2:
                setBrightnessMode((brightnessMode + 1) % BRIGHTNESS_NAMES.length);
                break;
            case 3:
                showTripHistory();
                break;
            case 4:
                setHudView(!mapPreview || fullMap, !mapPreview);
                break;
            case 5:
                enableMapLocation();
                break;
            case 6:
                headingUp = !headingUp;
                preferences.edit().putBoolean("headingUp", headingUp).apply();
                int lockSound = headingUp ? lockHeadingSoundId : lockNorthSoundId;
                if (mapPreview && thermalLevel == THERMAL_NORMAL && lockSound != 0
                        && (demo || (gpsFix != null && !Float.isNaN(gpsCourse)))) {
                    playSound(lockSound, 0.9f, SoundPolicy.Kind.UI);
                    skipClick = true;
                }
                break;
            case 7:
                float zoomBefore = RANGE_ZOOM[mapRange];
                mapRange = (mapRange + 1) % RANGE_NAMES.length;
                preferences.edit().putInt("mapRange", mapRange).apply();
                boolean zoomingIn = RANGE_ZOOM[mapRange] > zoomBefore;
                int zoomSound = zoomingIn ? zoomInSoundId : zoomOutSoundId;
                if (mapPreview && thermalLevel == THERMAL_NORMAL && zoomSound != 0) {
                    playSound(zoomSound, 0.85f, SoundPolicy.Kind.UI);
                    skipClick = true;
                }
                break;
            case 8:
                showLinkMenu();
                break;
            case 9:
                fullMap = !fullMap;
                preferences.edit().putBoolean("fullMap", fullMap).apply();
                break;
            case 10:
                if (nav != null) showCancelPrompt();
                else showSavedPlaces();
                break;
            default:
                return;
        }
        if (!skipClick) playSound(switchSoundId, 0.42f, SoundPolicy.Kind.UI);
        hud.invalidate();
    }

    private final class HudView extends View {
        private int ACCENT, MUTED, LINE, UNLIT, BACKGROUND;

        private int ground(int alpha) {
            return (alpha << 24) | (BACKGROUND & 0xFFFFFF);
        }

        void applyPalette() {
            int palette = preferences.getInt("palette", 0);
            if (palette == 0) {
                ACCENT = 0xFF4DFF88;
                MUTED = 0xFF3FB468;
                LINE = 0xFF1D5C36;
                UNLIT = 0xFF0A1D11;
                BACKGROUND = 0xFF020A05;
            } else if (palette == 2) {
                ACCENT = 0xFF4DB8FF;
                MUTED = 0xFF3F8FC9;
                LINE = 0xFF1D4E78;
                UNLIT = 0xFF0A1A2B;
                BACKGROUND = 0xFF020711;
            } else {
                ACCENT = 0xFFFFB64F;
                MUTED = 0xFFB1874B;
                LINE = 0xFF61451F;
                UNLIT = 0xFF1D160A;
                BACKGROUND = 0xFF080602;
            }
        }
        private static final float MAP_LEFT_SPLIT = 650;
        private static final float MAP_LEFT_FULL = 65;
        private float mapLeft() { return fullMapActive() ? MAP_LEFT_FULL : MAP_LEFT_SPLIT; }
        private float mapTop() { return fullMapActive() ? 94 : 131; }
        private static final float MAP_RIGHT = 1220;
        private float mapBottom() { return (fullMapActive() ? 514 : 448) + extra(); }
        private float mapCenterX() { return (mapLeft() + MAP_RIGHT) / 2; }
        private float mapCenterY() { return (mapTop() + mapBottom()) / 2; }
        private static final float MAP_TILE_PIXELS = 350;
        private static final float MAP_MODE_LEFT = 1092;
        private static final float MAP_RANGE_LEFT = 985;
        private static final float FORWARD_OFFSET = 82;
        private static final int MAP_LOCK_MS = 350;
        private static final int MAP_TRANSITION_MS = 1000;
        private static final int TARGET_CAPTION_MS = 1450;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Matrix mapTransform = new Matrix();
        private float dashScale;
        private DashPathEffect waterDash, railDash;
        private final float[] mapPoint = new float[2];
        private float cameraAngle;
        private float cameraLead;
        private float cameraStartAngle;
        private float cameraStartLead;
        private float cameraTargetAngle;
        private float cameraTargetLead;
        private long cameraStartedAt;
        private long lockStartedAt;
        private int cameraDuration;
        private boolean cameraMode;
        private float rangeZoom = RANGE_ZOOM[mapRange];
        private float rangeFrom = rangeZoom;
        private float rangeTo = rangeZoom;
        private long rangeStartedAt;
        private final Path sampleLoop = road(106, 258, 150, 225, 190, 155, 260, 131,
                340, 131, 380, 166, 416, 224, 384, 263, 315, 279, 205, 270, 106, 258);
        private final PathMeasure sampleDrive = new PathMeasure(sampleLoop, false);
        private final float[] marker = new float[2];
        private final float[] direction = new float[2];
        private final String[] labelNames = new String[6];
        private final float[] labelX = new float[6];
        private final float[] labelY = new float[6];
        private final float[] labelWidth = new float[6];
        private final Path[] sampleStreets = {
                road(-70, 26, 136, 53, 200, 82, 358, 70, 440, 28, 640, 9),
                road(-55, 100, 102, 115, 190, 155, 276, 183, 360, 188, 458, 166, 644, 155),
                road(-35, 196, 80, 188, 150, 225, 231, 238, 336, 229, 416, 224, 491, 264, 638, 285),
                road(-40, 320, 98, 300, 205, 270, 224, 348, 293, 400),
                road(19, -55, 50, 59, 45, 111, 80, 188, 69, 245, 106, 258, 98, 300, 108, 401),
                road(133, -55, 136, 53, 102, 115, 115, 164, 150, 225),
                road(238, -50, 200, 82, 260, 131, 276, 183, 231, 238, 205, 270),
                road(360, -52, 358, 70, 340, 131, 360, 188, 336, 229, 315, 279, 338, 396),
                road(473, -35, 440, 28, 428, 102, 458, 166, 416, 224),
                road(567, -35, 527, 72, 520, 159, 491, 264, 510, 405),
                road(224, 348, 315, 325, 384, 263, 432, 321, 510, 342, 633, 354)
        };
        private final Path sampleHighway = road(-60, 309, 58, 278, 106, 258, 150, 225,
                190, 155, 260, 131, 340, 131, 419, 117, 509, 90, 642, 53);
        private final RectF rectangle = new RectF();
        private final Typeface labelFont = Typeface.create("monospace", Typeface.NORMAL);
        private final Typeface boldFont = Typeface.create("monospace", Typeface.BOLD);
        private float scale = 1;
        private float left;
        private float top;
        private int pressedControl = -1;
        private final int[] digitSegments = {63, 6, 91, 79, 102, 109, 125, 7, 127, 111};

        private float extraRows = 60;
        private float extra() { return extraRows; }

        private static final float EDGE_PIXELS = 12;
        private static final float FRAME_LEFT = 40, FRAME_TOP = 34, FRAME_WIDTH = 1200, FRAME_BASE_HEIGHT = 518;
        private static final float MAX_EXTRA = 110;

        HudView(Context context) {
            super(context);
            // The PH-1's legacy GPU driver drops cached text on state changes.
            // Rasterize this static instrument only when its data changes.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            applyPalette();
            setContentDescription("Insight HUD Module 2.3. Speed, coolant, and RPM history on the left. Gauges or an offline road map on the right. Bluetooth setup, with the trip log and the other settings, is in the header. Tap the map for full screen, and the rest of the screen to change the view.");
            setFocusable(true);
        }

        private void fitFrame() {
            float insetLeft = 0, insetTop = 0, insetRight = 0, insetBottom = 0;
            WindowInsets insets = getRootWindowInsets();
            DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();
            if (cutout != null) {
                insetLeft = cutout.getSafeInsetLeft();
                insetTop = cutout.getSafeInsetTop();
                insetRight = cutout.getSafeInsetRight();
                insetBottom = cutout.getSafeInsetBottom();
            }
            float availWidth = Math.max(1, getWidth() - insetLeft - insetRight - 2 * EDGE_PIXELS);
            float availHeight = Math.max(1, getHeight() - insetTop - insetBottom - 2 * EDGE_PIXELS);
            scale = Math.min(availWidth / FRAME_WIDTH, availHeight / FRAME_BASE_HEIGHT);
            extraRows = Math.max(0, Math.min(MAX_EXTRA, availHeight / scale - FRAME_BASE_HEIGHT));
            float frameHeight = (FRAME_BASE_HEIGHT + extraRows) * scale;
            left = insetLeft + EDGE_PIXELS + (availWidth - FRAME_WIDTH * scale) / 2 - FRAME_LEFT * scale;
            top = insetTop + EDGE_PIXELS + (availHeight - frameHeight) / 2 - FRAME_TOP * scale;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (parkedBlank || thermalBlank) {
                canvas.drawColor(Color.BLACK);
                return;
            }
            canvas.drawColor(BACKGROUND);
            fitFrame();
            canvas.save();
            canvas.translate(left, top);
            canvas.scale(scale, scale);
            canvas.save();
            if (mirror) canvas.scale(-1, 1, 640, 280);
            long now = SystemClock.uptimeMillis();
            long startupElapsed = startupStartedAt == 0 ? now : startupClock(now);
            boolean starting = startupStartedAt != 0 && startupElapsed < STARTUP_DURATION_MS;
            boolean shuttingDown = shutdownStartedAt != 0;
            boolean thermalHold = thermalLevel == THERMAL_HOLD;
            if (thermalHold) drawThermalWarning(canvas, now);
            else if (shuttingDown) drawShutdown(canvas, now - shutdownStartedAt);
            else if (starting) drawStartup(canvas, startupElapsed);
            else {
                drawGauges(canvas, now);
                if (parkedRoute != null && accessoryActive && powered && !startupPending && hasWindowFocus()) {
                    if (resumeRouteDeadline == 0) {
                        resumeRouteDeadline = now + RESUME_ROUTE_TIMEOUT_MS;
                        main.postAtTime(resumeRouteTimeout, resumeRouteDeadline);
                    }
                    drawResumeRoute(canvas, now);
                    postInvalidateDelayed(100);
                } else if (cancelPrompt && nav != null) drawCancelRoute(canvas, now);
                else if (openPlaces != null) drawSavedPlaces(canvas);
            }
            if (quickOpen && !thermalHold && !shuttingDown && !starting) drawQuickConfig(canvas);
            boolean alertHeld = thermalHold || shuttingDown || starting || startupPending;
            Alerts.Alert alert = alerts.tick(now, alertHeld);
            if (alert != null) {
                if (alerts.justStarted() && !alert.silent) playAlertSound(alert.level);
                drawAlert(canvas, alert, now);
            }
            canvas.restore();
            canvas.restore();
            if (alerts.busy() && isShown()) postInvalidateDelayed(60);
            if ((thermalHold || starting || shuttingDown
                    || (thermalLevel == THERMAL_LIMIT && now - thermalStartedAt < THERMAL_WARNING_MS))
                    && isShown()) postInvalidateDelayed(starting ? 33 : 80);
            else if (thermalLevel == THERMAL_NORMAL && mapPreview && foreground && hasWindowFocus()) {
                if (now - cameraStartedAt < cameraDuration || now - rangeStartedAt < 500
                        || (lockStartedAt != 0 && now - lockStartedAt < TARGET_CAPTION_MS)) postInvalidateDelayed(32);
                else if (estimator.moving(SystemClock.elapsedRealtime())) postInvalidateDelayed(50);
                else if (demo && powered) postInvalidateDelayed(100);
            }
        }

        private static final float PLACES_LEFT = 309, PLACES_RIGHT = 971;
        private static final float PLACES_TOP = 101, PLACES_ROWS = 199, PLACE_HEIGHT = 60;
        private static final float PLACES_FOOTER = 451, PLACES_BOTTOM = 493;
        private static final int VISIBLE_PLACES = 4;

        private void drawResumeRoute(Canvas canvas, long now) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ground(0x96));
            canvas.drawRect(FRAME_LEFT, FRAME_TOP, FRAME_LEFT + FRAME_WIDTH,
                    FRAME_TOP + FRAME_BASE_HEIGHT + extraRows, paint);
            paint.setColor(BACKGROUND);
            canvas.drawRect(PLACES_LEFT, 173, PLACES_RIGHT, 381, paint);
            border(canvas, PLACES_LEFT, 173, PLACES_RIGHT, 381);
            paint.setColor(ACCENT);
            float fraction = Math.max(0, resumeRouteDeadline - now) / (float) RESUME_ROUTE_TIMEOUT_MS;
            canvas.drawRect(PLACES_LEFT, 173, PLACES_LEFT + (PLACES_RIGHT - PLACES_LEFT) * fraction, 177, paint);
            text(canvas, "CONTINUE NAVIGATION?", 640, 234, 32, ACCENT, true, Paint.Align.CENTER);
            String name = parkedRoute.name.toUpperCase(Locale.US);
            if (name.length() > 36) name = name.substring(0, 35) + "…";
            text(canvas, name, 640, 275, 22, MUTED, false, Paint.Align.CENTER);
            text(canvas, "[F1] YES", 457, 340, 23, ACCENT, true, Paint.Align.CENTER);
            text(canvas, "[F2] NO", 823, 340, 23, MUTED, true, Paint.Align.CENTER);
        }

        private void drawCancelRoute(Canvas canvas, long now) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ground(0x96));
            canvas.drawRect(FRAME_LEFT, FRAME_TOP, FRAME_LEFT + FRAME_WIDTH,
                    FRAME_TOP + FRAME_BASE_HEIGHT + extraRows, paint);
            paint.setColor(BACKGROUND);
            canvas.drawRect(PLACES_LEFT, 173, PLACES_RIGHT, 381, paint);
            border(canvas, PLACES_LEFT, 173, PLACES_RIGHT, 381);
            paint.setColor(ACCENT);
            float fraction = Math.max(0, Math.min(1, (cancelPromptDeadline - now) / (float) CANCEL_PROMPT_MS));
            canvas.drawRect(PLACES_LEFT, 173, PLACES_LEFT + (PLACES_RIGHT - PLACES_LEFT) * fraction, 177, paint);
            postInvalidateDelayed(100);
            text(canvas, "CANCEL NAVIGATION?", 640, 234, 32, ACCENT, true, Paint.Align.CENTER);
            String name = nav.name.isEmpty() ? "DESTINATION" : nav.name.toUpperCase(Locale.US);
            if (name.length() > 36) name = name.substring(0, 35) + "…";
            text(canvas, name, 640, 275, 22, MUTED, false, Paint.Align.CENTER);
            text(canvas, "[F1] CANCEL ROUTE", 457, 340, 23, ACCENT, true, Paint.Align.CENTER);
            text(canvas, "[F4] KEEP", 823, 340, 23, MUTED, true, Paint.Align.CENTER);
        }

        private int firstVisiblePlace() {
            return Math.max(0, Math.min(selectedPlace - 1, openPlaces.length() - VISIBLE_PLACES));
        }

        private void drawSavedPlaces(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ground(0x96));
            canvas.drawRect(FRAME_LEFT, FRAME_TOP, FRAME_LEFT + FRAME_WIDTH,
                    FRAME_TOP + FRAME_BASE_HEIGHT + extraRows, paint);
            paint.setColor(BACKGROUND);
            canvas.drawRect(PLACES_LEFT, PLACES_TOP, PLACES_RIGHT, PLACES_BOTTOM, paint);
            border(canvas, PLACES_LEFT, PLACES_TOP, PLACES_RIGHT, PLACES_BOTTOM);

            paint.setColor(ACCENT);
            canvas.drawRect(PLACES_LEFT, PLACES_TOP, PLACES_LEFT + 98, PLACES_TOP + 4, paint);
            text(canvas, "OFFLINE NAVIGATION", 339, 132, 14, MUTED, false);
            text(canvas, "SAVED PLACES", 339, 174, 34, ACCENT, true);
            text(canvas, (selectedPlace + 1) + " / " + openPlaces.length(), 939, 173,
                    21, ACCENT, true, Paint.Align.RIGHT);
            paint.setColor(LINE);
            canvas.drawRect(339, 186, 941, 188, paint);

            int first = firstVisiblePlace();
            int end = Math.min(first + VISIBLE_PLACES, openPlaces.length());
            for (int i = first; i < end; i++) {
                JSONObject place = openPlaces.optJSONObject(i);
                if (place == null) continue;
                float y = PLACES_ROWS + (i - first) * PLACE_HEIGHT;
                boolean selected = i == selectedPlace;
                paint.setColor(selected ? (ACCENT & 0xffffff) | 0x27000000 : ground(0xff));
                canvas.drawRect(329, y, 951, y + 52, paint);
                if (selected) {
                    paint.setColor(ACCENT);
                    canvas.drawRect(329, y, 334, y + 52, paint);
                }
                drawSavedPlaceIcon(canvas, 358, y + 26, place.optString("icon"));
                String name = place.optString("name").toUpperCase(Locale.US);
                paint.setTypeface(selected ? boldFont : labelFont);
                paint.setTextSize(24);
                if (paint.measureText(name) > 497) name = android.text.TextUtils.ellipsize(name,
                        new android.text.TextPaint(paint), 497, android.text.TextUtils.TruncateAt.END).toString();
                text(canvas, name, 390, y + 34, 24, selected ? ACCENT : MUTED, selected);
                if (selected) text(canvas, ">", 923, y + 34, 24, ACCENT, true);
            }

            paint.setColor(LINE);
            canvas.drawRect(339, PLACES_FOOTER, 941, PLACES_FOOTER + 2, paint);
            text(canvas, "[F4] NEXT", 345, 478, 17, MUTED, false);
            text(canvas, "[F1] GO", 640, 478, 17, ACCENT, true, Paint.Align.CENTER);
            text(canvas, "[F2] CANCEL", 938, 478, 17, MUTED, false, Paint.Align.RIGHT);
        }

        private void savedPlacesTap(float screenX, float screenY) {
            float x = (screenX - left) / scale;
            float y = (screenY - top) / scale;
            if (mirror) x = 1280 - x;
            if (x < PLACES_LEFT || x > PLACES_RIGHT || y < PLACES_TOP || y > PLACES_BOTTOM) {
                openPlaces = null;
            } else if (y >= PLACES_FOOTER) {
                if (x > 782) openPlaces = null;
                else if (x >= 500) chooseSavedPlace();
                else selectedPlace = (selectedPlace + 1) % openPlaces.length();
            } else if (y >= PLACES_ROWS) {
                int row = (int) ((y - PLACES_ROWS) / PLACE_HEIGHT);
                int index = firstVisiblePlace() + row;
                if (row < VISIBLE_PLACES && index < openPlaces.length()) selectedPlace = index;
            }
            invalidate();
        }

        private void drawQuickConfig(Canvas canvas) {
            float bottom = 552 + extra();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ground(255));
            canvas.drawRect(41, 35, 1239, bottom - 1, paint);
            border(canvas, 40, 34, 1240, bottom);
            text(canvas, "> QUICK CONFIG", 70, 72, 30, ACCENT, true);
            text(canvas, "HOLD F1 TO CLOSE", 1210, 72, 18, MUTED, false, Paint.Align.RIGHT);
            line(canvas, 40, 88, 1240, 88, LINE);
            line(canvas, 640, 112, 640, bottom - 70, LINE);
            String[] names = {"BRIGHTNESS", "COLOR", "NEAREST GAS"};
            String[] values = {BRIGHTNESS_NAMES[brightnessMode],
                    PALETTE_NAMES[Math.max(0, Math.min(PALETTE_NAMES.length - 1, preferences.getInt("palette", 0)))],
                    quickGasNote.isEmpty() ? "GO" : quickGasNote};
            for (int i = 0; i < 3; i++) {
                float top = 118 + i * 124;
                boolean selected = quickRow == i;
                if (selected) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(ACCENT);
                    canvas.drawRect(70, top, 600, top + 106, paint);
                } else {
                    border(canvas, 70, top, 600, top + 106);
                }
                text(canvas, names[i], 94, top + 36, 22, selected ? BACKGROUND : MUTED, false);
                text(canvas, values[i], 94, top + 88, 50, selected ? BACKGROUND : ACCENT, true);
            }
            text(canvas, "DEVICE HEALTH", 680, 146, 22, MUTED, false);
            float y = 190;
            for (String[] row : quickHealth) {
                if (y > bottom - 90) break;
                text(canvas, row[0], 680, y, 20, MUTED, false);
                text(canvas, row[1], 680, y + 44, 38, row[2].isEmpty() ? ACCENT : 0xFFFF8A45, true);
                y += 86;
            }
            text(canvas, quickRow == 2 ? "F1 NEXT     F2 GO     F3 CLOSE" : "F1 NEXT     F2 CHANGE     F3 CLOSE",
                    640, bottom - 28, 22, MUTED, false, Paint.Align.CENTER);
        }

        private void drawAlert(Canvas canvas, Alerts.Alert alert, long now) {
            long age = now - alert.shownAt;
            boolean flashing = alert.level > Alerts.INFO;
            boolean inverted = flashing && age / (alert.level == Alerts.CRITICAL ? 220 : 420) % 2 == 0 && age < 4000;
            int fill = inverted ? ACCENT : BACKGROUND;
            int ink = inverted ? BACKGROUND : ACCENT;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            canvas.drawRect(41, 35, 1239, 87, paint);
            border(canvas, 40, 34, 1240, 88);
            String prefix = alert.level == Alerts.CRITICAL ? "!! " : alert.level == Alerts.WARN ? "! " : "> ";
            float slide = Math.min(1f, age / 180f);
            text(canvas, prefix + alert.text, 640 - (1 - slide) * 80, 72, 30, ink, true, Paint.Align.CENTER);
        }

        private void drawThermalWarning(Canvas canvas, long now) {
            border(canvas, 40, 34, 1240, 552 + extra());
            text(canvas, "INSIGHT / HUD MODULE 2.3", 60, 69, 17, MUTED, false);
            text(canvas, "THERMAL PROTECTION", 1220, 69, 17, ACCENT, false, Paint.Align.RIGHT);
            line(canvas, 40, 88, 1240, 88, LINE);
            long elapsed = now - thermalStartedAt;
            int color = elapsed % 1200 < 700 ? 0xFFFF8A45 : ACCENT;
            text(canvas, "OVERHEATING", 640, 254, 61, color, true, Paint.Align.CENTER);
            text(canvas, "BATTERY " + Math.round(batteryTemperature) + " °C", 640, 307,
                    25, ACCENT, false, Paint.Align.CENTER);
            text(canvas, "GPS AND OBD PAUSED", 640, 358, 18, MUTED, false, Paint.Align.CENTER);
            drawThermalTicker(canvas, 330, 950, 422, elapsed, "COOL PHONE BEFORE USE   /   HUD ENTERING STANDBY");
        }

        private void drawThermalLimit(Canvas canvas, long now) {
            text(canvas, "03  THERMAL LIMIT", mapLeft(), 118, 19, ACCENT, false);
            border(canvas, mapLeft(), mapTop(), MAP_RIGHT, mapBottom());
            text(canvas, "PHONE RUNNING HOT", mapCenterX(), 235, 27, ACCENT, true, Paint.Align.CENTER);
            text(canvas, "BATTERY " + Math.round(batteryTemperature) + " °C", mapCenterX(), 283,
                    20, ACCENT, false, Paint.Align.CENTER);
            drawThermalTicker(canvas, 680, 1190, 351, now - thermalStartedAt, "GPS PAUSED   /   SCREEN DIMMED");
        }

        private void drawThermalTicker(Canvas canvas, float left, float right, float y, long elapsed, String message) {
            line(canvas, left, y - 27, right, y - 27, LINE);
            line(canvas, left, y + 12, right, y + 12, LINE);
            canvas.save();
            canvas.clipRect(left + 2, y - 26, right - 2, y + 11);
            if (elapsed < THERMAL_WARNING_MS) {
                float width = right - left;
                float x = left + width * 0.47f - elapsed * 0.094f;
                text(canvas, message, x, y, 16, ACCENT, false);
            } else {
                text(canvas, message, (left + right) / 2, y, 16, ACCENT, false, Paint.Align.CENTER);
            }
            canvas.restore();
        }

        private void drawShutdown(Canvas canvas, long elapsed) {
            border(canvas, 40, 34, 1240, 552 + extra());
            text(canvas, "INSIGHT / HUD MODULE 2.3", 60, 69, 17, MUTED, false);
            line(canvas, 40, 88, 1240, 88, LINE);
            if (shutdownReport == null) {
                text(canvas, "ACCESSORY POWER OFF", 640, 270, 42, ACCENT, true, Paint.Align.CENTER);
                text(canvas, "HUD STANDBY", 640, 325, 24, MUTED, false, Paint.Align.CENTER);
            } else {
                text(canvas, shutdownReport.optLong("ended") == 0 ? "TRIP REPORT / DEMO" : "TRIP REPORT / SAVED",
                        640, 171, 35, ACCENT, true, Paint.Align.CENTER);
                reportMetric(canvas, 210, "ELAPSED", TripStats.time(shutdownReport));
                reportMetric(canvas, 495, "EST. MILES", TripStats.value(shutdownReport, "miles", "%.1f"));
                reportMetric(canvas, 785, "PEAK RPM", TripStats.value(shutdownReport, "rpm", "%.0f"));
                reportMetric(canvas, 1070, "PEAK COOLANT", TripStats.value(shutdownReport, "coolant", "%.0f") + " °F");
                text(canvas, "ACCESSORY POWER OFF / HUD STANDBY", 640, 415, 16, MUTED, false, Paint.Align.CENTER);
            }
            int litBlocks = 40 - (int) (elapsed * 40 / shutdownDuration());
            for (int index = 0; index < 40; index++) {
                paint.setColor(index < litBlocks ? ACCENT : UNLIT);
                rectangle.set(65 + index * 29, 481, 85 + index * 29, 496);
                canvas.drawRect(rectangle, paint);
            }
        }

        private void reportMetric(Canvas canvas, int x, String name, String value) {
            text(canvas, name, x, 258, 17, MUTED, false, Paint.Align.CENTER);
            text(canvas, value, x, 320, 35, ACCENT, true, Paint.Align.CENTER);
        }

        private void drawStartup(Canvas canvas, long elapsed) {
            border(canvas, 40, 34, 1240, 552 + extra());
            text(canvas, "INSIGHT / HUD MODULE 2.3", 60, 69, 17, MUTED, false);
            text(canvas, "SYSTEM START", 1220, 69, 17, ACCENT, false, Paint.Align.RIGHT);
            line(canvas, 40, 88, 1240, 88, LINE);

            if (elapsed < 800) {
                int letters = Math.min(7, 1 + (int) (elapsed / 65));
                text(canvas, "INSIGHT".substring(0, letters), 640, 285, 76, ACCENT, true, Paint.Align.CENTER);
                text(canvas, "HUD MODULE", 640, 334, 21, MUTED, false, Paint.Align.CENTER);
                number(canvas, "2.3", 598, 360, 32);
            } else if (elapsed < 1400) {
                text(canvas, "DISPLAY SEGMENT TEST", 65, 137, 20, ACCENT, false);
                number(canvas, "888", 65, 177, 119);
                number(canvas, "88", 670, 177, 52);
                number(canvas, "88.8", 670, 311, 52);
                text(canvas, "TEST PATTERN", 1208, 225, 16, MUTED, false, Paint.Align.RIGHT);
                text(canvas, "ALL SEGMENTS", 1208, 359, 16, MUTED, false, Paint.Align.RIGHT);
            } else {
                text(canvas, "INSIGHT", 65, 169, 44, ACCENT, true);
                text(canvas, "HUD MODULE 2.3", 65, 202, 16, MUTED, false);
                String[] checks = {
                    "MODULE 2.3 ............ ONLINE",
                    "PROFILE ............... ZE1 / 2001",
                    powered ? "POWER ................. EXTERNAL" : "POWER ................. BATTERY",
                    "OBD LINK .............. " + linkStatus
                };
                int visibleChecks = Math.min(checks.length, 1 + (int) ((elapsed - 1400) / 180));
                for (int index = 0; index < visibleChecks; index++) {
                    text(canvas, checks[index], 65, 254 + index * 37, 19, ACCENT, false);
                }
                if (elapsed >= 2250) {
                    text(canvas, "HUD MODULE ARMED", 1210, 435, 24, ACCENT, true, Paint.Align.RIGHT);
                }
            }

            line(canvas, 60, 462, 1220, 462, LINE);
            int litBlocks = Math.min(40, (int) (elapsed * 40 / STARTUP_DURATION_MS) + 1);
            for (int index = 0; index < 40; index++) {
                paint.setColor(index < litBlocks ? ACCENT : UNLIT);
                rectangle.set(65 + index * 29, 481, 85 + index * 29, 496);
                canvas.drawRect(rectangle, paint);
            }
            text(canvas, "ZE1 / 2001    DISPLAY INITIALIZATION", 65, 530, 13, MUTED, false);
            text(canvas, "TAP TO SKIP", 1210, 530, 13, MUTED, false, Paint.Align.RIGHT);
        }

        private void drawGauges(Canvas canvas, long now) {
            ObdBluetooth.Readings displayed = demo ? DEMO_READINGS : readings;
            if (displayed == null) displayed = EMPTY_READINGS;
            border(canvas, 40, 34, 1240, 552 + extra());
            text(canvas, "INSIGHT", 60, 69, 24, ACCENT, true);
            text(canvas, "HUD MODULE 2.3", 230, 68, 16, MUTED, false);
            text(canvas, clockText(), 640, 71, 30, ACCENT, true, Paint.Align.CENTER);
            String heading = headingText();
            if (heading != null) text(canvas, heading, 730, 69, 20, MUTED, false);
            text(canvas, demo ? "[ DEMO - SIMULATED ]" : "[ " + linkStatus + " ]",
                    1115, 68, 16, ACCENT, false, Paint.Align.RIGHT);
            border(canvas, 1134, 46, 1220, 77);
            text(canvas, "BT SETUP", 1177, 67, 13, MUTED, false, Paint.Align.CENTER);
            line(canvas, 40, 88, 1240, 88, LINE);
            boolean full = fullMapActive();
            if (!full) text(canvas, "01  VEHICLE SPEED", 65, 127, 19, ACCENT, false);
            boolean speedFromGps = false;
            if (!demo && displayed.speedMph == null) {
                Double fromGps = gpsSpeedMph();
                if (fromGps != null) {
                    displayed = displayed.withSpeed(fromGps);
                    speedFromGps = true;
                }
            }
            String speed = wholeReading(displayed.speedMph, "---");
            if (!full) {
                float speedWidth = 105;
                number(canvas, speed, 65 + (3 - speed.length()) * speedWidth * 1.2f, 142, speedWidth);
                text(canvas, "MPH", 477, 332, 32, ACCENT, false);
                if (speedFromGps) text(canvas, "GPS", 477, 358, 14, MUTED, false);
                drawWarmup(canvas, displayed.coolantF);
                drawRpmTrace(canvas, displayed.rpm);
                OfflineRoadMap.RoadInfo info = shownRoadInfo();
                if (info != null) {
                    text(canvas, roadLine(info, 24), 522, 127, 14, MUTED, false, Paint.Align.RIGHT);
                    drawLimitBadge(canvas, 452, 168, 90, 84, info.limitMph, displayed.speedMph);
                }
            }

            float footer = (full ? 520 : 480) + extra();
            if (!full) line(canvas, 620, 88, 620, footer, LINE);
            if (thermalLevel == THERMAL_LIMIT) {
                drawThermalLimit(canvas, now);
            } else if (mapPreview && demo) {
                drawMapPreview(canvas, now);
            } else if (mapPreview) {
                drawOfflineMap(canvas, now);
            } else {
                drawMetrics(canvas, displayed);
            }
            if (mapPreview && !demo && thermalLevel == THERMAL_NORMAL && nav != null) drawNavStrip(canvas);
            if (full) {
                if (mapReadout != 2) drawMapReadouts(canvas, displayed, speed, speedFromGps, mapReadout == 1);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ground(0xF0));
                canvas.drawRect(MAP_RANGE_LEFT - 3, 95, MAP_RIGHT + 1, 125, paint);
                drawMapMode(canvas);
            }

            line(canvas, 40, footer, 1240, footer, LINE);
            String adapterName = preferences.getString("adapterName", "NO ADAPTER SELECTED");
            if (adapterName.length() > 28) adapterName = adapterName.substring(0, 28);
            String phoneState = (battery < 0 ? "--" : String.valueOf(battery)) + "%  /  "
                    + Math.round(batteryTemperature) + " C";
            String brightText = "BRIGHT " + BRIGHTNESS_NAMES[brightnessMode] + (brightnessMode == 0
                    ? "  " + Math.round(autoLevel * 100) + "%" : "")
                    + "    SND " + sounds.label(ambientDark());
            if (full) {
                text(canvas, "BT / " + adapterName.toUpperCase(Locale.US), 60, footer + 25, 12, MUTED, false);
                text(canvas, brightText, 640, footer + 25, 12, MUTED, false, Paint.Align.CENTER);
                text(canvas, "PHONE " + phoneState + "   " + (powered ? "EXT POWER" : "BATTERY POWER"),
                        1220, footer + 25, 12, MUTED, false, Paint.Align.RIGHT);
            } else {
                OfflineRoadMap.RoadInfo fuelInfo = shownRoadInfo();
                if (fuelInfo != null && fuelInfo.fuelName != null) {
                    text(canvas, fuelLine(fuelInfo), 640, footer + 30, 14, MUTED, false, Paint.Align.CENTER);
                }
                String ahead = aheadLine(shownRoadInfo());
                String guidance = !mapPreview ? navFooter() : null;
                if (ahead != null) text(canvas, ahead, 60, footer + 32, 20, ACCENT, true);
                else if (guidance != null) text(canvas, guidance, 60, footer + 32, 20, ACCENT, true);
                else text(canvas, "ZE1 / MODULE 2.3", 60, footer + 30, 15, ACCENT, false);
                text(canvas, brightText, 640, footer + 56, 12, MUTED, false, Paint.Align.CENTER);
                text(canvas, "BT / " + adapterName.toUpperCase(Locale.US), 60, footer + 56, 12, MUTED, false);
                text(canvas, "PHONE " + phoneState, 1220, footer + 31, 14, MUTED, false, Paint.Align.RIGHT);
                text(canvas, powered ? "EXT POWER" : "BATTERY POWER", 1220, footer + 56, 12, MUTED, false,
                        Paint.Align.RIGHT);
            }
        }

        private void drawMetrics(Canvas canvas, ObdBluetooth.Readings reading) {
            boolean anySeen = seenGauge[0] || seenGauge[1] || seenGauge[2] || seenGauge[3];
            int slot = 0;
            for (int id = 0; id < 4 && slot < 3; id++) {
                boolean show = demo || (anySeen ? seenGauge[id] : id < 3);
                if (!show) continue;
                float y = 126 + slot * (120 + extra() / 3);
                switch (id) {
                    case 0: metric(canvas, "03  THROTTLE", reading.throttle, "%", y, 0, 100); break;
                    case 1: metric(canvas, "04  ENGINE LOAD", reading.load, "%", y, 0, 100); break;
                    case 2: metric(canvas, "05  EST. MPG", reading.mpg, "MPG", y, 0, 80); break;
                    default: metric(canvas, "06  INTAKE AIR", reading.intakeF, "\u00b0F", y, 0, 150); break;
                }
                slot++;
            }
        }

        private void drawWarmup(Canvas canvas, Double coolant) {
            text(canvas, "COOLANT", 573, 127, 12, MUTED, false, Paint.Align.CENTER);
            text(canvas, wholeReading(coolant, "--") + "°F", 573, 331, 14, ACCENT, false, Paint.Align.CENTER);
            // This is a display scale, not an engine warning threshold.
            int lit = coolant == null ? 0 : Math.max(0, (int) Math.round((coolant - 100) / 90 * 10));
            for (int index = 0; index < 10; index++) {
                paint.setColor(index < lit ? ACCENT : UNLIT);
                float y = 297 - 16 * index;
                canvas.drawRect(561, y, 585, y + 10, paint);
            }
        }

        private Path road(float... points) {
            Path road = new Path();
            road.moveTo(points[0], points[1]);
            for (int index = 2; index < points.length; index += 2) {
                road.lineTo(points[index], points[index + 1]);
            }
            return road;
        }

        private void mapNote(Canvas canvas, String note, int color) {
            if (fullMapActive()) text(canvas, note, MAP_RIGHT - 10, mapBottom() - 10, 12, color, false, Paint.Align.RIGHT);
            else text(canvas, note, mapLeft(), 469 + extra(), 12, color, false);
        }

        private void drawMapMode(Canvas canvas) {
            border(canvas, MAP_RANGE_LEFT, 96, MAP_MODE_LEFT - 8, 124);
            text(canvas, RANGE_NAMES[mapRange], (MAP_RANGE_LEFT + MAP_MODE_LEFT - 8) / 2,
                    115, 13, ACCENT, false, Paint.Align.CENTER);
            border(canvas, MAP_MODE_LEFT, 96, MAP_RIGHT, 124);
            text(canvas, headingUp ? "HEADING UP" : "NORTH UP", (MAP_MODE_LEFT + MAP_RIGHT) / 2,
                    115, 13, ACCENT, false, Paint.Align.CENTER);
        }

        private float angleDelta(float from, float to) {
            return ((to - from) % 360 + 540) % 360 - 180;
        }

        private void updateMapCamera(long now, float course) {
            float rangeProgress = Math.min(1, (now - rangeStartedAt) / 500f);
            float rangeEase = rangeProgress * rangeProgress * (3 - 2 * rangeProgress);
            rangeZoom = rangeFrom + (rangeTo - rangeFrom) * rangeEase;
            if (rangeTo != RANGE_ZOOM[mapRange]) {
                rangeFrom = rangeZoom;
                rangeTo = RANGE_ZOOM[mapRange];
                rangeStartedAt = now;
            }
            sampleMapCamera(now);
            boolean modeChanged = cameraMode != headingUp;
            if (!modeChanged && cameraDuration == MAP_TRANSITION_MS
                    && now - cameraStartedAt < MAP_TRANSITION_MS) return;
            boolean follow = headingUp && !Float.isNaN(course);
            float targetAngle = follow ? -course : 0;
            float targetLead = follow ? 1 : 0;
            if (!modeChanged && Math.abs(angleDelta(cameraTargetAngle, targetAngle)) < 1
                    && cameraTargetLead == targetLead) return;
            cameraMode = headingUp;
            cameraStartAngle = cameraAngle;
            cameraStartLead = cameraLead;
            cameraTargetAngle = cameraAngle + angleDelta(cameraAngle, targetAngle);
            cameraTargetLead = targetLead;
            cameraStartedAt = now;
            cameraDuration = modeChanged && !Float.isNaN(course) ? MAP_TRANSITION_MS : 360;
            if (modeChanged) lockStartedAt = cameraDuration == MAP_TRANSITION_MS ? now : 0;
        }

        private void sampleMapCamera(long now) {
            if (cameraDuration == 0) return;
            int lock = cameraDuration == MAP_TRANSITION_MS ? MAP_LOCK_MS : 0;
            float t = Math.max(0, Math.min(1, (float) (now - cameraStartedAt - lock) / (cameraDuration - lock)));
            float eased = t * t * (3 - 2 * t);
            cameraAngle = cameraStartAngle + (cameraTargetAngle - cameraStartAngle) * eased;
            cameraLead = cameraStartLead + (cameraTargetLead - cameraStartLead) * eased;
        }

        private void setMapTransform(float x, float y, float pixelsPerUnit, float anchorX, float anchorY) {
            float zoom = rangeZoom * (1 + cameraLead * 0.23f);
            mapTransform.setTranslate(-x, -y);
            mapTransform.postScale(pixelsPerUnit * zoom, pixelsPerUnit * zoom);
            mapTransform.postRotate(cameraAngle);
            mapTransform.postTranslate(anchorX, anchorY);
        }

        private void drawMapPreview(Canvas canvas, long now) {
            if (!fullMapActive()) {
                text(canvas, "03  MOVING MAP", mapLeft(), 118, 19, ACCENT, false);
                text(canvas, "SIMULATED", MAP_RANGE_LEFT - 12, 118, 14, MUTED, false, Paint.Align.RIGHT);
                drawMapMode(canvas);
            }
            border(canvas, mapLeft(), mapTop(), MAP_RIGHT, mapBottom());
            float progress = (now - mapPreviewStartedAt) % 24000 / 24000f;
            sampleDrive.getPosTan(progress * sampleDrive.getLength(), marker, direction);
            float course = 90 + (float) Math.toDegrees(Math.atan2(direction[1], direction[0]));
            updateMapCamera(now, course);
            float anchorX = mapCenterX() + (marker[0] - 285) * 0.58f * (1 - cameraLead);
            float anchorY = mapCenterY() + (marker[1] - 160) * 0.58f * (1 - cameraLead)
                    + FORWARD_OFFSET * cameraLead;
            setMapTransform(marker[0], marker[1], 1, anchorX, anchorY);
            canvas.save();
            canvas.clipRect(mapLeft() + 1, mapTop() + 1, MAP_RIGHT - 1, mapBottom() - 1);
            canvas.save();
            canvas.concat(mapTransform);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            paint.setColor(LINE);
            for (Path street : sampleStreets) canvas.drawPath(street, paint);
            paint.setStrokeWidth(4);
            paint.setColor(MUTED);
            canvas.drawPath(sampleHighway, paint);
            paint.setStrokeWidth(2);
            paint.setColor(MUTED);
            canvas.drawPath(sampleLoop, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.restore();
            demoRoadLabel(canvas, "ROUTE 08", 420, 76, anchorX, anchorY, ACCENT);
            demoRoadLabel(canvas, "RIVER RD", 43, 179, anchorX, anchorY, MUTED);
            demoRoadLabel(canvas, "DEPOT AVE", 390, 306, anchorX, anchorY, MUTED);
            drawMapMarker(canvas, anchorX, anchorY, course, now,
                    42.607 + (160 - marker[1]) * 0.00003, -73.700 + (marker[0] - 285) * 0.00004);
            canvas.restore();
            mapNote(canvas, "DEMO ROADS / GPS INACTIVE", MUTED);
        }

        private void demoRoadLabel(Canvas canvas, String name, float x, float y,
                                   float anchorX, float anchorY, int color) {
            mapPoint[0] = x;
            mapPoint[1] = y;
            mapTransform.mapPoints(mapPoint);
            if (Math.abs(mapPoint[0] - anchorX) >= 45 || Math.abs(mapPoint[1] - anchorY) >= 27) {
                text(canvas, name, mapPoint[0], mapPoint[1], 12, color, false, Paint.Align.CENTER);
            }
        }

        private void drawOfflineMap(Canvas canvas, long now) {
            OfflineRoadMap.Region region = roadMap.region();
            if (!fullMapActive()) text(canvas, "03  OFFLINE MAP", mapLeft(), 118, 19, ACCENT, false);
            if (!fullMapActive()) {
                text(canvas, region == null ? "ALBANY REGION" : region.name,
                        MAP_RANGE_LEFT - 12, 118, 11, MUTED, false, Paint.Align.RIGHT);
                drawMapMode(canvas);
            }
            border(canvas, mapLeft(), mapTop(), MAP_RIGHT, mapBottom());
                        String status = mapStatus(region);
            if (headingUp && "GPS FIX".equals(status) && Float.isNaN(gpsCourse)) status = "GPS FIX / WAITING FOR COURSE";
            mapNote(canvas, status, ACCENT);
            if (region == null) {
                text(canvas, roadMap.failed() ? "MAP DATA UNAVAILABLE" : "LOADING LOCAL ROADS",
                        mapCenterX(), mapCenterY(), 19, MUTED, false, Paint.Align.CENTER);
                return;
            }
            boolean tracking = fixInRegion(region);
            float course = tracking ? gpsCourse : Float.NaN;
            updateMapCamera(now, course);
            Location position = mapPosition(region);
            double viewX = OfflineRoadMap.tileX(position == null ? region.longitude : position.getLongitude());
            double viewY = OfflineRoadMap.tileY(position == null ? region.latitude : position.getLatitude());
            OfflineRoadMap.Scene scene = roadMap.scene();
            if (scene == null || !scene.covers(viewX, viewY)) {
                text(canvas, roadMap.failed() ? "MAP DATA UNAVAILABLE" : "LOADING LOCAL ROADS",
                        mapCenterX(), mapCenterY(), 19, MUTED, false, Paint.Align.CENTER);
                return;
            }
            if (!scene.hasRoads()) {
                text(canvas, "NO OFFLINE ROADS HERE", mapCenterX(), mapCenterY(),
                        19, MUTED, false, Paint.Align.CENTER);
                return;
            }
            float anchorY = mapCenterY() + FORWARD_OFFSET * cameraLead;
            setMapTransform((float) (viewX - scene.x), (float) (viewY - scene.y),
                    MAP_TILE_PIXELS, mapCenterX(), anchorY);
            canvas.save();
            canvas.clipRect(mapLeft() + 1, mapTop() + 1, MAP_RIGHT - 1, mapBottom() - 1);
            canvas.save();
            canvas.concat(mapTransform);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(LINE);
            float strokeScale = MAP_TILE_PIXELS * rangeZoom * (1 + cameraLead * 0.23f);
            if (dashScale != strokeScale) {
                dashScale = strokeScale;
                waterDash = new DashPathEffect(new float[]{11 / strokeScale, 6 / strokeScale}, 0);
                railDash = new DashPathEffect(new float[]{2 / strokeScale, 6 / strokeScale}, 0);
            }
            paint.setStrokeWidth(1.5f / strokeScale);
            paint.setPathEffect(waterDash);
            canvas.drawPath(scene.roads[4], paint);
            paint.setPathEffect(railDash);
            canvas.drawPath(scene.roads[3], paint);
            paint.setPathEffect(null);
            paint.setStrokeWidth(1.2f / strokeScale);
            canvas.drawPath(scene.roads[0], paint);
            paint.setColor(MUTED);
            paint.setStrokeWidth(2.1f / strokeScale);
            canvas.drawPath(scene.roads[1], paint);
            paint.setColor(ACCENT);
            paint.setStrokeWidth(3.1f / strokeScale);
            canvas.drawPath(scene.roads[2], paint);
            if (nav != null) drawRouteOverlay(canvas, scene, strokeScale);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);
            canvas.restore();

            drawSavedPlaceMarkers(canvas, scene);

            int labelCount = 0;
            for (OfflineRoadMap.Label label : scene.labels) {
                mapPoint[0] = label.x;
                mapPoint[1] = label.y;
                mapTransform.mapPoints(mapPoint);
                float x = mapPoint[0];
                float y = mapPoint[1];
                paint.setTypeface(labelFont);
                paint.setTextSize(13);
                float halfWidth = paint.measureText(label.name) / 2 + 5;
                if (x - halfWidth < mapLeft() + 4 || x + halfWidth > MAP_RIGHT - 4
                        || y < mapTop() + 16 || y > mapBottom() - 18) continue;
                if (tracking && Math.abs(x - mapCenterX()) < halfWidth + 30 && Math.abs(y - anchorY) < 32) continue;
                boolean clear = true;
                for (int index = 0; index < labelCount; index++) {
                    if (labelNames[index].equals(label.name)
                            || (Math.abs(x - labelX[index]) < halfWidth + labelWidth[index]
                            && Math.abs(y - labelY[index]) < 22)) {
                        clear = false;
                        break;
                    }
                }
                if (!clear) continue;
                paint.setColor(BACKGROUND);
                canvas.drawRect(x - halfWidth, y - 14, x + halfWidth, y + 4, paint);
                text(canvas, label.name, x, y, 13, ACCENT, false, Paint.Align.CENTER);
                labelNames[labelCount] = label.name;
                labelX[labelCount] = x;
                labelY[labelCount] = y;
                labelWidth[labelCount++] = halfWidth;
                if (labelCount == labelNames.length) break;
            }
            if (tracking) drawMapMarker(canvas, mapCenterX(), anchorY, course, now,
                    gpsFix.getLatitude(), gpsFix.getLongitude());
            canvas.restore();
            if (gpsUnavailable) drawSatelliteWarning(canvas);
        }

        private void drawMapReadouts(Canvas canvas, ObdBluetooth.Readings displayed, String speed, boolean fromGps,
                                     boolean compact) {
            OfflineRoadMap.RoadInfo info = shownRoadInfo();
            String ahead = aheadLine(info);
            float plateLeft = mapLeft() + 1;
            float plateRight = plateLeft + (compact ? 420 : info != null ? 440 : 330);
            float plateBottom = mapBottom() - 1;
            float height = compact ? (ahead != null ? 150 : 118) : info != null ? (ahead != null ? 166 : 142) : 128;
            float plateTop = plateBottom - height;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(BACKGROUND);
            canvas.drawRect(plateLeft, plateTop, plateRight, plateBottom, paint);
            line(canvas, plateLeft, plateTop, plateRight, plateTop, LINE);
            line(canvas, plateRight, plateTop, plateRight, plateBottom, LINE);
            float base = plateBottom - (compact ? (ahead != null ? 44 : 16) : info != null ? (ahead != null ? 68 : 44) : 34);
            text(canvas, speed, plateLeft + 196, base, 84, ACCENT, true, Paint.Align.RIGHT);
            text(canvas, fromGps ? "MPH GPS" : "MPH", plateLeft + 210, base - 2, 22, ACCENT, false);
            if (!compact) {
                text(canvas, wholeReading(displayed.rpm, "----") + " RPM", plateLeft + 210, base - 30, 18, MUTED, false);
            }
            if (info != null) {
                text(canvas, roadLine(info, compact ? 38 : 42), plateLeft + 12, plateTop + 24, compact ? 18 : 15, MUTED, false);
                drawLimitBadge(canvas, plateRight - 112, plateTop + 34, 100, 76, info.limitMph, displayed.speedMph);
                if (!compact && info.fuelName != null) {
                    text(canvas, fuelLine(info), plateLeft + 12, plateBottom - 10, 12, MUTED, false);
                }
                if (ahead != null) text(canvas, ahead, plateLeft + 12, plateBottom - (compact ? 12 : 34), 20, ACCENT, true);
            }
        }

        private String clockText() {
            String pattern = android.text.format.DateFormat.is24HourFormat(HudActivity.this) ? "HH:mm" : "h:mm a";
            return new SimpleDateFormat(pattern, Locale.US).format(new Date());
        }

        private String headingText() {
            if (demo) return "NE 045\u00b0";
            if (gpsFix == null || Float.isNaN(gpsCourse)) return null;
            return RoadLookup.compass(gpsCourse) + String.format(Locale.US, " %03d\u00b0", Math.round(gpsCourse) % 360);
        }

        private String markerSource;
        private JSONArray markers = new JSONArray();

        private void drawSavedPlaceMarkers(Canvas canvas, OfflineRoadMap.Scene scene) {
            String source = preferences.getString("savedPlaces", "[]");
            if (!source.equals(markerSource)) {
                markers = savedPlaces();
                markerSource = source;
            }
            for (int i = 0; i < markers.length(); i++) {
                JSONObject place = markers.optJSONObject(i);
                if (place == null) continue;
                mapPoint[0] = (float) (OfflineRoadMap.tileX(place.optDouble("lon")) - scene.x);
                mapPoint[1] = (float) (OfflineRoadMap.tileY(place.optDouble("lat")) - scene.y);
                mapTransform.mapPoints(mapPoint);
                float x = mapPoint[0], y = mapPoint[1];
                if (x < mapLeft() + 15 || x > MAP_RIGHT - 15 || y < mapTop() + 15 || y > mapBottom() - 15) continue;
                drawSavedPlaceIcon(canvas, x, y, place.optString("icon"));
            }
        }

        private void drawSavedPlaceIcon(Canvas canvas, float x, float y, String icon) {
            canvas.save();
            canvas.translate(x, y);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(BACKGROUND);
            canvas.drawCircle(0, 0, 15, paint);
            paint.setColor(ACCENT);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            canvas.drawCircle(0, 0, 14, paint);
            paint.setStyle(Paint.Style.FILL);
            switch (icon) {
                case "home":
                    savedIconShape(canvas, -9, -1, 0, -9, 9, -1, 6, -1, 6, 7, -6, 7, -6, -1);
                    break;
                case "work":
                    canvas.drawRoundRect(-8, -4, 8, 7, 2, 2, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawRect(-3, -8, 3, -4, paint);
                    break;
                case "star":
                    savedIconShape(canvas, 0, -10, 3, -3, 10, -3, 5, 2, 7, 9, 0, 5, -7, 9, -5, 2, -10, -3, -3, -3);
                    break;
                case "fuel":
                    canvas.drawRect(-8, -8, 3, 8, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawLine(3, 5, 8, 5, paint);
                    canvas.drawLine(8, 5, 8, -4, paint);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(BACKGROUND);
                    canvas.drawRect(-6, -6, 1, -2, paint);
                    break;
                case "food":
                    paint.setStrokeWidth(2);
                    canvas.drawLine(-4, -2, -4, 8, paint);
                    canvas.drawLine(-7, -8, -7, -2, paint);
                    canvas.drawLine(-4, -8, -4, -2, paint);
                    canvas.drawLine(-1, -8, -1, -2, paint);
                    canvas.drawLine(-7, -2, -1, -2, paint);
                    canvas.drawOval(2, -8, 8, 0, paint);
                    canvas.drawLine(5, 0, 5, 8, paint);
                    break;
                default:
                    savedIconShape(canvas, -5, -1, 5, -1, 0, 8);
                    canvas.drawCircle(0, -3, 6, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            canvas.restore();
        }

        private void savedIconShape(Canvas canvas, float... points) {
            Path shape = new Path();
            shape.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) shape.lineTo(points[i], points[i + 1]);
            shape.close();
            canvas.drawPath(shape, paint);
        }

        private int routeSceneX, routeSceneY, routeDrawVersion = -1;
        private double routeDrawAlong;
        private final Path routePath = new Path();
        private final Path remainingRoutePath = new Path();

        private void drawRouteOverlay(Canvas canvas, OfflineRoadMap.Scene scene, float strokeScale) {
            double along = nav.alongMeters();
            if (scene.x != routeSceneX || scene.y != routeSceneY || navVersion != routeDrawVersion
                    || along != routeDrawAlong) {
                routeSceneX = scene.x;
                routeSceneY = scene.y;
                routeDrawVersion = navVersion;
                routeDrawAlong = along;
                routePath.reset();
                remainingRoutePath.reset();
                for (int i = 1; i < navTileX.length; i++) {
                    double dx = navTileX[i] - scene.x, dy = navTileY[i] - scene.y;
                    double px = navTileX[i - 1] - scene.x, py = navTileY[i - 1] - scene.y;
                    if (Math.max(px, dx) < -4 || Math.min(px, dx) > 4
                            || Math.max(py, dy) < -4 || Math.min(py, dy) > 4) continue;
                    routePath.moveTo((float) px, (float) py);
                    routePath.lineTo((float) dx, (float) dy);
                    double end = nav.metersAtPoint(i), start = nav.metersAtPoint(i - 1);
                    if (end > along) {
                        double t = along <= start ? 0 : (along - start) / (end - start);
                        remainingRoutePath.moveTo((float) (px + (dx - px) * t), (float) (py + (dy - py) * t));
                        remainingRoutePath.lineTo((float) dx, (float) dy);
                    }
                }
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(BACKGROUND);
            paint.setStrokeWidth(9f / strokeScale);
            canvas.drawPath(routePath, paint);
            paint.setColor(0x66FFFFFF);
            paint.setStrokeWidth(3f / strokeScale);
            canvas.drawPath(routePath, paint);
            paint.setColor(BACKGROUND);
            paint.setStrokeWidth(9f / strokeScale);
            canvas.drawPath(remainingRoutePath, paint);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(5f / strokeScale);
            canvas.drawPath(remainingRoutePath, paint);
            paint.setStrokeJoin(Paint.Join.MITER);
            int last = navTileX.length - 1;
            float dx = (float) (navTileX[last] - scene.x), dy = (float) (navTileY[last] - scene.y);
            if (Math.abs(dx) <= 4 && Math.abs(dy) <= 4) {
                paint.setColor(BACKGROUND);
                paint.setStrokeWidth(12f / strokeScale);
                canvas.drawCircle(dx, dy, 17f / strokeScale, paint);
                paint.setColor(Color.WHITE);
                paint.setStrokeWidth(5f / strokeScale);
                canvas.drawCircle(dx, dy, 17f / strokeScale, paint);
            }
        }

        private String navDistance(double meters) {
            double feet = meters * 3.28084;
            if (feet < 1000) return Math.max(50, Math.round(feet / 50) * 50) + " FT";
            return String.format(Locale.US, "%.1f MI", meters / 1609.344);
        }

        private String navFooter() {
            RouteTrack route = nav;
            if (route == null) return null;
            RouteTrack.Step next = route.nextStep();
            String what = next == null ? "ARRIVE" : next.label();
            if (what.length() > 30) what = what.substring(0, 30);
            return navDistance(route.metersToNext(navAhead())) + "  " + what;
        }

        private void drawNavStrip(Canvas canvas) {
            RouteTrack route = nav;
            float left = mapLeft() + 8, top = mapTop() + 8;
            RouteTrack.Step following = route.stepAfterNext();
            boolean soon = following != null && route.metersBetweenNextAndAfter() <= THEN_METERS
                    && route.nextStep() != null && following.kind != RouteTrack.ARRIVE;
            float width = fullMapActive() ? 540 : 440, height = soon ? 132 : 108;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ground(0xF5));
            canvas.drawRect(left, top, left + width, top + height, paint);
            border(canvas, left, top, left + width, top + height);
            RouteTrack.Step next = route.nextStep();
            drawTurnArrow(canvas, left + 56, top + 56, next == null ? RouteTrack.ARRIVE : next.kind);
            double ahead = navAhead();
            text(canvas, navDistance(route.metersToNext(ahead)), left + 112, top + 50, 42, ACCENT, true);
            String what = next == null ? "ARRIVE" + (route.name.isEmpty() ? "" : " AT " + route.name.toUpperCase(Locale.US))
                    : next.label();
            int room = (int) ((width - 124) / 10);
            text(canvas, what.length() > room ? what.substring(0, room) : what, left + 112, top + 76, 17, MUTED, false);
            double seconds = route.remainingSeconds();
            String left2 = String.format(Locale.US, "%.1f MI", Math.max(0, route.remainingMeters() - ahead) / 1609.344);
            if (!Double.isNaN(seconds)) {
                String pattern = android.text.format.DateFormat.is24HourFormat(HudActivity.this) ? "HH:mm" : "h:mm a";
                left2 += "   " + Math.max(1, Math.round(seconds / 60)) + " MIN   ETA "
                        + new SimpleDateFormat(pattern, Locale.US).format(new Date(System.currentTimeMillis() + (long) (seconds * 1000)));
            }
            text(canvas, left2, left + 112, top + 98, 14, MUTED, false);
            if (soon) {
                String then = "THEN " + following.label();
                int thenRoom = (int) ((width - 24) / 8.4);
                text(canvas, then.length() > thenRoom ? then.substring(0, thenRoom) : then, left + 14, top + 122, 14, ACCENT, false);
            }
        }

        private void drawTurnArrow(Canvas canvas, float cx, float cy, int kind) {
            canvas.save();
            canvas.translate(cx, cy);
            paint.setColor(ACCENT);
            switch (kind) {
                case RouteTrack.ARRIVE:
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(6);
                    canvas.drawCircle(0, 0, 22, paint);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(0, 0, 9, paint);
                    break;
                case RouteTrack.ROUNDABOUT:
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(6);
                    canvas.drawCircle(0, 8, 18, paint);
                    paint.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(0, -34);
                    path.lineTo(-12, -16);
                    path.lineTo(12, -16);
                    path.close();
                    canvas.drawPath(path, paint);
                    break;
                case RouteTrack.UTURN:
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(9);
                    path.reset();
                    path.moveTo(14, 34);
                    path.lineTo(14, -6);
                    path.arcTo(new RectF(-14, -34, 14, -6), 0, -180);
                    path.lineTo(-14, 8);
                    canvas.drawPath(path, paint);
                    paint.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(-14, 34);
                    path.lineTo(-27, 12);
                    path.lineTo(-1, 12);
                    path.close();
                    canvas.drawPath(path, paint);
                    break;
                default: {
                    float angle;
                    switch (kind) {
                        case RouteTrack.SLIGHT_RIGHT: angle = 35; break;
                        case RouteTrack.RIGHT: angle = 90; break;
                        case RouteTrack.SHARP_RIGHT: angle = 135; break;
                        case RouteTrack.SHARP_LEFT: angle = -135; break;
                        case RouteTrack.LEFT: angle = -90; break;
                        case RouteTrack.SLIGHT_LEFT: angle = -35; break;
                        default: angle = 0; break;
                    }
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRect(-5, 2, 5, 36, paint);
                    canvas.rotate(angle, 0, 0);
                    canvas.drawRect(-5, -26, 5, 5, paint);
                    path.reset();
                    path.moveTo(-17, -26);
                    path.lineTo(17, -26);
                    path.lineTo(0, -48);
                    path.close();
                    canvas.drawPath(path, paint);
                    break;
                }
            }
            canvas.restore();
        }

        private String roadLine(OfflineRoadMap.RoadInfo info, int max) {
            String road = info.road == null ? "" : info.road;
            String place = info.place == null ? "" : info.place;
            if (road.isEmpty() || place.isEmpty()) {
                String only = road.isEmpty() ? place : road;
                return only.length() > max ? only.substring(0, max) : only;
            }
            String tail = "  /  " + place;
            if (road.length() + tail.length() <= max) return road + tail;
            int room = max - tail.length();
            return room >= 10 ? road.substring(0, room) + tail : (road.length() > max ? road.substring(0, max) : road);
        }

        private String fuelLine(OfflineRoadMap.RoadInfo info) {
            String name = info.fuelName.length() > 18 ? info.fuelName.substring(0, 18) : info.fuelName;
            return String.format(Locale.US, "FUEL  %s  %.1f MI %s", name, info.fuelMiles, info.fuelBearing);
        }

        private void drawLimitBadge(Canvas canvas, float x, float y, float width, float height, int limit,
                                    Double speed) {
            boolean over = limit > 0 && speed != null && speed > limit;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(over ? ACCENT : ground(0xEB));
            canvas.drawRect(x, y, x + width, y + height, paint);
            border(canvas, x, y, x + width, y + height);
            int ink = over ? BACKGROUND : ACCENT;
            text(canvas, "LIMIT", x + width / 2, y + 20, 14, over ? ink : MUTED, false, Paint.Align.CENTER);
            text(canvas, limit > 0 ? String.valueOf(limit) : "--", x + width / 2, y + height - 14, height * 0.5f, ink,
                    true, Paint.Align.CENTER);
        }

        private void drawSatelliteWarning(Canvas canvas) {
            float left = mapCenterX() - 174;
            float top = mapCenterY() - 30;
            paint.setColor(ground(0xF2));
            canvas.drawRect(left, top, left + 348, top + 60, paint);
            border(canvas, left, top, left + 348, top + 60);
            text(canvas, "SAT NOT AVAILABLE", mapCenterX(), mapCenterY() + 7,
                    24, ACCENT, true, Paint.Align.CENTER);
        }

        private String mapStatus(OfflineRoadMap.Region region) {
            if (!hasPreciseLocation()) return "TAP MAP FOR GPS";
            if (!gpsEnabled()) return "TAP MAP TO ENABLE GPS";
            if (!accessoryActive && !gpsTestActive) return "TAP MAP FOR GPS TEST";
            if (gpsUnavailable) return gpsTestActive ? "TEST / TAP TO STOP" : "";
            String status = gpsFix == null ? "ACQUIRING GPS"
                    : region != null && !fixInRegion(region) ? "OUTSIDE MAP AREA" : "GPS FIX";
            return gpsTestActive ? "TEST / " + status + " / TAP TO STOP" : status;
        }

        private void drawMapMarker(Canvas canvas, float x, float y, float course, long now,
                                   double latitude, double longitude) {
            canvas.save();
            canvas.translate(x, y);
            paint.setColor(BACKGROUND);
            canvas.drawCircle(0, 0, 19, paint);
            long elapsed = now - lockStartedAt;
            if (lockStartedAt != 0 && elapsed < MAP_TRANSITION_MS) {
                float fade = Math.min(1, (MAP_TRANSITION_MS - elapsed) / 370f);
                int reticle = (Math.round(fade * 255) << 24) | (ACCENT & 0xFFFFFF);
                int radius = 22 + Math.max(0, MAP_LOCK_MS - (int) elapsed) / 12;
                for (int corner = 0; corner < 4; corner++) {
                    line(canvas, radius - 8, -radius, radius, -radius, reticle);
                    line(canvas, radius, -radius, radius, -radius + 8, reticle);
                    canvas.rotate(90);
                }
            }
            if (lockStartedAt != 0 && elapsed < TARGET_CAPTION_MS) {
                drawTargetCaption(canvas, x, y, latitude, longitude, elapsed);
            }
            paint.setColor(ACCENT);
            if (!Float.isNaN(course)) {
                canvas.rotate(course + cameraAngle);
                path.reset();
                path.moveTo(0, -16);
                path.lineTo(10, 10);
                path.lineTo(0, 6);
                path.lineTo(-10, 10);
                path.close();
                canvas.drawPath(path, paint);
            } else {
                canvas.drawCircle(0, 0, 8, paint);
            }
            canvas.restore();
        }

        private void drawTargetCaption(Canvas canvas, float x, float y, double latitude, double longitude, long elapsed) {
            float opacity = Math.min(1, (TARGET_CAPTION_MS - elapsed) / 320f);
            int alpha = Math.round(opacity * 255) << 24;
            float side = x + 219 < MAP_RIGHT ? 1 : -1;
            float left = side > 0 ? 42 : -218;
            float top = Math.max(mapTop() + 4 - y, Math.min(-53, mapBottom() - 52 - y));
            paint.setColor((Math.round(opacity * 235) << 24) | (BACKGROUND & 0xFFFFFF));
            canvas.drawRect(left, top, left + 176, top + 44, paint);
            line(canvas, left, top, left + 176, top, alpha | (MUTED & 0xFFFFFF));
            line(canvas, side * 28, -8, side * 42, top + 34, alpha | (MUTED & 0xFFFFFF));
            text(canvas, demo ? "TARGET ZE1 / SIM" : "TARGET ZE1 / LOCK",
                    left + 8, top + 17, 13, alpha | (ACCENT & 0xFFFFFF), true);
            String coordinates = String.format(Locale.US, "%.4f%s  %.4f%s", Math.abs(latitude),
                    latitude >= 0 ? "N" : "S", Math.abs(longitude), longitude >= 0 ? "E" : "W");
            text(canvas, coordinates, left + 8, top + 34, 12, alpha | (ACCENT & 0xFFFFFF), false);
        }

        private void drawRpmTrace(Canvas canvas, Double rpm) {
            line(canvas, 65, 350, 590, 350, LINE);
            text(canvas, "02  ENGINE RPM", 65, 372, 15, ACCENT, false);
            text(canvas, wholeReading(rpm, "----") + " RPM", 590, 372, 15, ACCENT, true, Paint.Align.RIGHT);
            float chartLeft = 104;
            float chartRight = 590;
            float chartTop = 389;
            float chartBottom = 459 + extra();
            text(canvas, "8K", 65, chartTop + 4, 12, MUTED, false);
            text(canvas, "0", 65, chartBottom, 12, MUTED, false);
            for (int row = 0; row <= 2; row++) {
                float y = chartTop + row * (chartBottom - chartTop) / 2;
                line(canvas, chartLeft, y, chartRight, y, LINE);
            }
            for (int column = 0; column <= 5; column++) {
                float x = chartLeft + column * (chartRight - chartLeft) / 5;
                line(canvas, x, chartTop, x, chartBottom, LINE);
            }

            int count = demo ? DEMO_RPM.length : rpmCount;
            float spacing = (chartRight - chartLeft) / (rpmHistory.length - 1);
            path.reset();
            boolean previousValid = false;
            for (int index = 0; index < count; index++) {
                float sample = demo ? DEMO_RPM[index]
                        : rpmHistory[(rpmNext + rpmHistory.length - count + index) % rpmHistory.length];
                if (Float.isNaN(sample)) {
                    previousValid = false;
                    continue;
                }
                float x = chartRight - (count - 1 - index) * spacing;
                float y = chartBottom - Math.min(sample / 8000f, 1f) * (chartBottom - chartTop);
                if (previousValid) path.lineTo(x, y);
                else path.moveTo(x, y);
                previousValid = true;
                if (index == count - 1) {
                    paint.setColor(ACCENT);
                    canvas.drawCircle(x, y, 3, paint);
                }
            }
            paint.setColor(ACCENT);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void metric(Canvas canvas, String label, Double value, String unit, float y, int minimum, int maximum) {
            text(canvas, label, 650, y, 17, ACCENT, false);
            String digits = unit.equals("V")
                    ? (value == null ? "--.-" : String.format(Locale.US, "%.1f", value))
                    : wholeReading(value, "--");
            number(canvas, digits, 658, y + 16, 29);
            text(canvas, unit, 1210, y + 67, 22, ACCENT, false, Paint.Align.RIGHT);
            int segments = 16;
            int lit = value == null ? 0 : (int) Math.round(segments * (value - minimum) / (maximum - minimum));
            for (int index = 0; index < segments; index++) {
                paint.setColor(index < lit ? ACCENT : UNLIT);
                float x = 850 + index * 20;
                canvas.drawRect(x, y + 25, x + 16, y + 45, paint);
            }
            text(canvas, String.valueOf(minimum), 850, y + 65, 12, MUTED, false);
            text(canvas, String.valueOf(maximum), 1166, y + 65, 12, MUTED, false, Paint.Align.RIGHT);
            line(canvas, 650, y + 80, 1220, y + 80, LINE);
        }

        private void number(Canvas canvas, String value, float x, float y, float width) {
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (character == '.') {
                    paint.setColor(ACCENT);
                    float dot = width * 0.16f;
                    canvas.drawRect(x, y + width * 1.88f - dot, x + dot, y + width * 1.88f, paint);
                    x += width * 0.4f;
                } else {
                    digit(canvas, character, x, y, width, ACCENT);
                    x += width * 1.2f;
                }
            }
        }

        private void border(Canvas canvas, float x1, float y1, float x2, float y2) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            paint.setColor(LINE);
            canvas.drawRect(x1, y1, x2, y2, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void digit(Canvas canvas, char character, float x, float y, float width, int color) {
            int mask = character == '-' ? 64 : digitSegments[character - '0'];
            float height = width * 1.88f;
            float thickness = width * 0.14f;
            horizontal(canvas, x + thickness, y, width - 2 * thickness, thickness, (mask & 1) != 0, color);
            vertical(canvas, x + width - thickness, y + thickness, height / 2 - 1.5f * thickness, thickness, (mask & 2) != 0, color);
            vertical(canvas, x + width - thickness, y + height / 2 + thickness / 2, height / 2 - 1.5f * thickness, thickness, (mask & 4) != 0, color);
            horizontal(canvas, x + thickness, y + height - thickness, width - 2 * thickness, thickness, (mask & 8) != 0, color);
            vertical(canvas, x, y + height / 2 + thickness / 2, height / 2 - 1.5f * thickness, thickness, (mask & 16) != 0, color);
            vertical(canvas, x, y + thickness, height / 2 - 1.5f * thickness, thickness, (mask & 32) != 0, color);
            horizontal(canvas, x + thickness, y + height / 2 - thickness / 2, width - 2 * thickness, thickness, (mask & 64) != 0, color);
        }

        private void horizontal(Canvas canvas, float x, float y, float width, float thickness, boolean lit, int color) {
            paint.setColor(lit ? color : UNLIT);
            path.reset();
            path.moveTo(x, y + thickness / 2);
            path.lineTo(x + thickness / 2, y);
            path.lineTo(x + width - thickness / 2, y);
            path.lineTo(x + width, y + thickness / 2);
            path.lineTo(x + width - thickness / 2, y + thickness);
            path.lineTo(x + thickness / 2, y + thickness);
            path.close();
            canvas.drawPath(path, paint);
        }

        private void vertical(Canvas canvas, float x, float y, float height, float thickness, boolean lit, int color) {
            paint.setColor(lit ? color : UNLIT);
            path.reset();
            path.moveTo(x + thickness / 2, y);
            path.lineTo(x + thickness, y + thickness / 2);
            path.lineTo(x + thickness, y + height - thickness / 2);
            path.lineTo(x + thickness / 2, y + height);
            path.lineTo(x, y + height - thickness / 2);
            path.lineTo(x, y + thickness / 2);
            path.close();
            canvas.drawPath(path, paint);
        }

        private void text(Canvas canvas, String value, float x, float y, float size, int color, boolean bold) {
            text(canvas, value, x, y, size, color, bold, Paint.Align.LEFT);
        }

        private void text(Canvas canvas, String value, float x, float y, float size, int color, boolean bold, Paint.Align align) {
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTypeface(bold ? boldFont : labelFont);
            paint.setTextAlign(align);
            canvas.drawText(value, x, y, paint);
        }

        private void line(Canvas canvas, float x1, float y1, float x2, float y2, int color) {
            paint.setColor(color);
            paint.setStrokeWidth(1);
            canvas.drawLine(x1, y1, x2, y2, paint);
        }

        private int controlAt(float screenX, float screenY) {
            float x = (screenX - left) / scale;
            float y = (screenY - top) / scale;
            float mapX = mirror ? 1280 - x : x;
            if (mapX >= 1126 && mapX <= 1228 && y >= 39 && y <= 85) return 8;
            if (thermalLevel == THERMAL_NORMAL && mapPreview && mapX >= MAP_RANGE_LEFT && mapX <= MAP_MODE_LEFT - 8
                    && y >= 88 && y < (fullMapActive() ? 126 : 131)) return 7;
            if (thermalLevel == THERMAL_NORMAL && mapPreview && mapX >= MAP_MODE_LEFT - 7 && mapX <= MAP_RIGHT + 4
                    && y >= 88 && y < (fullMapActive() ? 126 : 131)) return 6;
            if (thermalLevel == THERMAL_NORMAL && mapPreview && !demo && mapX >= mapLeft() && mapX <= MAP_RIGHT
                    && y >= mapTop() && y <= (fullMapActive() ? 520 : 480) + extra()
                    && (!hasPreciseLocation() || !gpsEnabled() || (!powered && !accessoryActive))) return 5;
            if (thermalLevel == THERMAL_NORMAL && mapPreview && mapX >= mapLeft() && mapX <= MAP_RIGHT
                    && y >= mapTop() && y <= (fullMapActive() ? 520 : 480) + extra()) return 9;
            if (mapX >= 40 && mapX <= 1240 && y >= 88 && y <= 480 + extra()) return 4;
            return -1;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (shutdownStartedAt != 0 || thermalLevel == THERMAL_HOLD) return true;
            if (parkedBlank) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    parkedBlank = false;
                    enterStandbyPower(false);
                    performClick();
                    invalidate();
                }
                return true;
            }
            if (startupStartedAt != 0 && startupClock(SystemClock.uptimeMillis()) < STARTUP_DURATION_MS) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    startupStartedAt = 0;
                    releaseStartupSound();
                    performClick();
                    invalidate();
                }
                return true;
            }
            if (resumeRouteDeadline != 0) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    float x = (event.getX() - left) / scale;
                    float y = (event.getY() - top) / scale;
                    if (mirror) x = 1280 - x;
                    if (x >= PLACES_LEFT && x <= PLACES_RIGHT && y >= 300 && y <= 381) {
                        answerResumeRoute(x < 640);
                        performClick();
                    }
                }
                return true;
            }
            if (cancelPrompt) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    float x = (event.getX() - left) / scale;
                    float y = (event.getY() - top) / scale;
                    if (mirror) x = 1280 - x;
                    if (x >= PLACES_LEFT && x <= PLACES_RIGHT && y >= 300 && y <= 381) {
                        if (x < 640) cancelRoute();
                        else dismissCancelPrompt();
                    } else {
                        dismissCancelPrompt();
                    }
                    performClick();
                }
                return true;
            }
            if (openPlaces != null) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    savedPlacesTap(event.getX(), event.getY());
                    performClick();
                }
                return true;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressedControl = controlAt(event.getX(), event.getY());
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    int releasedControl = controlAt(event.getX(), event.getY());
                    if (pressedControl >= 0 && releasedControl == pressedControl) {
                        performClick();
                        activateControl(pressedControl);
                    }
                    pressedControl = -1;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressedControl = -1;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
