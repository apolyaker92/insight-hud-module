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
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.text.DateFormat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class HudActivity extends Activity {
    SharedPreferences preferences;
    HudView hud;
    volatile boolean mirror;
    boolean demo;
    volatile boolean mapPreview;
    volatile boolean fullMap;
    int mapReadout;
    private static final String[] READOUT_NAMES = {"FULL", "SPEED", "OFF"};
    static final String[] PALETTE_NAMES = {"GREEN", "AMBER", "BLUE"};
    private boolean roadInfoOn;
    private OfflineRoadMap.RoadInfo roadInfo;
    private long lastLookupAt;
    private double lastLookupLat, lastLookupLon;
    private long overSince, lastOverAlertAt;
    private DriveLog driveLog;

    RouteTrack nav;
    double[] navTileX, navTileY;
    int navVersion;
    boolean navEnabled = true;
    private long navArrivedAt;
    private boolean offRouteAnnounced;
    private RouteDatabase routeDb;
    private boolean routeDbTried;
    private final java.util.concurrent.ExecutorService routing = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean routingBusy;
    private final java.util.concurrent.atomic.AtomicInteger routeGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private double[] navGoal;
    private String navGoalName = "";
    static final long RESUME_ROUTE_TIMEOUT_MS = 5000;
    RouteTrack parkedRoute;
    private double[] parkedRouteGoal;
    private String parkedRouteGoalName;
    long resumeRouteDeadline;
    final Runnable resumeRouteTimeout = () -> answerResumeRoute(false);
    private long lastRerouteAt;

    final KeyboardBattery keyboards = new KeyboardBattery(this);
    final QuickPanel quick = new QuickPanel(this);
    private final NavService navService = new NavService(this);






    private synchronized RouteDatabase routeDatabase() {
        if (routeDb == null && !routeDbTried) {
            routeDbTried = true;
            java.io.File external = getExternalFilesDir(null);
            if (external != null) routeDb = RouteDatabase.open(new java.io.File(external, "hud-route.db"));
        }
        return routeDb;
    }

    synchronized String routeTo(double lat, double lon, String name, boolean reroute) {
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

    void startRoute(RouteTrack track) {
        routeGeneration.incrementAndGet();
        navGoal = null;
        startRoute(track, true);
    }

    void startRoute(RouteTrack track, boolean announce) {
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

    boolean cancelPrompt;
    static final long CANCEL_PROMPT_MS = 10_000;
    long cancelPromptDeadline;
    final Runnable cancelPromptTimeout = () -> dismissCancelPrompt();

    private void showCancelPrompt() {
        if (nav == null) return;
        cancelPrompt = true;
        cancelPromptDeadline = SystemClock.uptimeMillis() + CANCEL_PROMPT_MS;
        main.removeCallbacks(cancelPromptTimeout);
        main.postDelayed(cancelPromptTimeout, CANCEL_PROMPT_MS);
        hud.invalidate();
    }

    void dismissCancelPrompt() {
        cancelPrompt = false;
        main.removeCallbacks(cancelPromptTimeout);
        hud.invalidate();
    }

    void cancelRoute() {
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

    void answerResumeRoute(boolean resume) {
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

    static final double THEN_METERS = 450;

    double navAhead() {
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
                        out.append("http://").append(address.getHostAddress()).append(':').append(navService.NAV_PORT)
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
                .setMessage("Address:\n" + navAddresses() + "\nPairing code: " + navService.navToken()
                        + "\n\nSend it in an X-HUD-Token header, or add ?token=" + navService.navToken() + " to the address."
                        + "\n\nPOST /route with a route, POST /cancel to stop, GET /status to check.")
                .setPositiveButton("OK", null)
                .setNegativeButton("NEW CODE", (dialog, which) -> {
                    navService.newNavToken();
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
    boolean headingUp;
    int mapRange;
    static final String[] RANGE_NAMES = {"NORMAL", "WIDE", "CLOSE"};
    static final float[] RANGE_ZOOM = {1.4f, 1.05f, 3.0f};
    private TripStats trip = new TripStats();
    JSONObject shutdownReport;
    private long accessoryLostAt;
    long mapPreviewStartedAt;
    OfflineRoadMap roadMap;
    private LocationManager locationManager;
    Location gpsFix;
    private Location lastMapFix;
    final PositionEstimator estimator = new PositionEstimator();
    private final Location estimatedFix = new Location("estimate");
    float gpsCourse = Float.NaN;
    private boolean gpsRegistered;
    boolean gpsUnavailable;
    boolean gpsTestActive;
    private static final int GPS_PERMISSION = 24;
    private static final long GPS_STALE_MS = 12000;
    private static final long GPS_UNAVAILABLE_MS = 20000;
    private static final long GPS_TEST_MS = 5 * 60 * 1000;
    private static final long GPS_RETRY_MS = 5000;
    volatile int brightnessMode;
    static final String[] BRIGHTNESS_NAMES = {"AUTO", "HIGH", "MED", "LOW"};
    private static final float[] BRIGHTNESS_LEVELS = {0f, 1.0f, 0.45f, 0.08f};
    private SensorManager sensors;
    private Sensor lightSensor;
    private boolean lightRegistered;
    float autoLevel = 0.45f;
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
    boolean powered;
    private boolean powerKnown;
    boolean accessoryActive;
    boolean startupPending;
    boolean parkedBlank;
    int battery = -1;
    float batteryTemperature;
    private boolean batteryOverheat;
    private PowerManager.OnThermalStatusChangedListener thermalListener;
    int systemThermalStatus;
    int thermalLevel;
    boolean thermalBlank;
    long thermalStartedAt;
    static final int THERMAL_NORMAL = 0;
    static final int THERMAL_LIMIT = 1;
    static final int THERMAL_HOLD = 2;
    static final long THERMAL_WARNING_MS = 4500;
    long startupStartedAt;
    long shutdownStartedAt;
    private MediaPlayer startupSound;
    private SoundPool switchSounds;
    volatile int switchSoundId, shutdownSoundId, switchLoadId, shutdownLoadId;
    private volatile int lockHeadingSoundId, lockNorthSoundId, lockHeadingLoadId, lockNorthLoadId;
    private volatile int zoomInSoundId, zoomOutSoundId, zoomInLoadId, zoomOutLoadId;
    static final long STARTUP_DURATION_MS = 2800;
    private static final long POWER_LOSS_DELAY_MS = 3000;
    private static final long SHUTDOWN_DURATION_MS = 1200;
    private static final long TRIP_REPORT_MS = 4200;
    final Handler main = new Handler(Looper.getMainLooper());
    BluetoothAdapter bluetooth;
    private ObdBluetooth obd;
    ObdBluetooth.Readings readings;
    private static final OfflineRoadMap.RoadInfo DEMO_ROAD_INFO =
            new OfflineRoadMap.RoadInfo("DEMO AVENUE", 35, "DEMOVILLE", "DEMO FUEL", 1.2, "NE",
                    RoadLookup.SIGNAL, "", 300);
    static final ObdBluetooth.Readings DEMO_READINGS = new ObdBluetooth.Readings(46d, 2400d, 188d, 85d, 13.8d, 42d, 28d, 0.87d, 52d);
    static final ObdBluetooth.Readings EMPTY_READINGS = new ObdBluetooth.Readings(null, null, null, null, null, null);
    static final float[] DEMO_RPM = {820, 880, 1100, 1550, 2250, 3250, 4500, 5000, 3550, 2950,
            3050, 3450, 4200, 4700, 3150, 2680, 2530, 2420, 2450, 2400};
    final boolean[] seenGauge = new boolean[4];
    final Alerts alerts = new Alerts();
    private final AutoStopTracker autoStop = new AutoStopTracker();
    final SoundPolicy sounds = new SoundPolicy();
    private volatile int autoStopLoadId, autoStartLoadId;
    private static final double WARM_F = 175;
    private static final double HOT_F = 230;   // coolant warning; the dash gauge remains authoritative
    private boolean linkLive, failureAnnounced, setupAnnounced, sawCool, warmAnnounced, hotAnnounced, fixAnnounced;
    private int failedAttempts;
    private volatile int alertInfoLoadId, alertWarnLoadId, alertCriticalLoadId;
    private final java.util.Set<Integer> loadedSamples =
            java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>());
    final float[] rpmHistory = new float[DEMO_RPM.length];
    int rpmNext;
    int rpmCount;
    String linkStatus = "SELECT BT ADAPTER";
    boolean foreground;
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
        navService.startNavServer();
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
        keyboards.close();
        if (Build.VERSION.SDK_INT >= 29 && thermalListener != null) {
            getSystemService(PowerManager.class).removeThermalStatusListener(thermalListener);
        }
        if (lightRegistered) sensors.unregisterListener(lightListener);
        if (obd != null) obd.destroy();
        switchSounds.release();
        switchSounds = null;
        roadMap.close();
        if (driveLog != null) driveLog.close();
        navService.stop();
        routing.shutdownNow();
        main.removeCallbacks(quick.f1Hold);
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

    boolean ambientDark() {
        return lightSensor != null && autoLevel <= SoundPolicy.DARK_LEVEL;
    }

    void playSound(int id, float base, SoundPolicy.Kind kind) {
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

    void playAlertSound(int level) {
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

    long shutdownDuration() {
        return shutdownReport == null ? SHUTDOWN_DURATION_MS : TRIP_REPORT_MS;
    }

    JSONArray tripHistory() {
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

    void enterStandbyPower(boolean standby) {
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

    void releaseStartupSound() {
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

    long startupClock(long now) {
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

    boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    boolean hasPreciseLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    boolean gpsEnabled() {
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

    Location mapPosition(OfflineRoadMap.Region region) {
        if (lastMapFix == null || !region.contains(lastMapFix.getLatitude(), lastMapFix.getLongitude())) return null;
        double[] estimate = estimator.at(SystemClock.elapsedRealtime());
        if (estimate == null) return lastMapFix;
        estimatedFix.setLatitude(estimate[0]);
        estimatedFix.setLongitude(estimate[1]);
        return estimatedFix;
    }

    boolean fixInRegion(OfflineRoadMap.Region region) {
        return gpsFix != null && region.contains(gpsFix.getLatitude(), gpsFix.getLongitude());
    }

    boolean fullMapActive() {
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

    OfflineRoadMap.RoadInfo shownRoadInfo() {
        if (!roadInfoOn) return null;
        return demo ? DEMO_ROAD_INFO : gpsFix == null ? null : roadInfo;
    }

    private void announceAhead(OfflineRoadMap.RoadInfo info) {
        if (info == null || demo) return;
        if (info.aheadKind == RoadLookup.CROSSING) raiseAlert("RAIL CROSSING AHEAD", Alerts.WARN);
        else if (info.aheadKind == RoadLookup.CAMERA) raiseAlert("CAMERA AHEAD", Alerts.INFO);
        else if (info.aheadKind == RoadLookup.CURVE) raiseAlert("CURVE AHEAD", Alerts.INFO);
    }

    static String aheadLine(OfflineRoadMap.RoadInfo info) {
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

    Double gpsSpeedMph() {
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

    String wholeReading(Double value, String missing) {
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
        if (keyCode == KeyEvent.KEYCODE_F1) return quick.handleF1(event);
        if (quick.quickOpen && (keyCode == KeyEvent.KEYCODE_F2 || keyCode == KeyEvent.KEYCODE_F3 || keyCode == KeyEvent.KEYCODE_F4)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) quick.quickKey(keyCode);
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

    JSONArray openPlaces;
    int selectedPlace;

    JSONArray savedPlaces() {
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

    void chooseSavedPlace() {
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

    void setMirror(boolean enabled) {
        mirror = enabled;
        preferences.edit().putBoolean("mirror", mirror).apply();
    }

    void setBrightnessMode(int mode) {
        brightnessMode = mode;
        preferences.edit().putInt("brightnessMode", brightnessMode).apply();
        updateLightSensor();
        applyBrightness();
    }

    void setHudView(boolean map, boolean full) {
        if (map && !mapPreview) mapPreviewStartedAt = SystemClock.uptimeMillis();
        mapPreview = map;
        fullMap = full;
        preferences.edit().putBoolean("mapPreview", mapPreview).putBoolean("fullMap", fullMap).apply();
        updateMap();
    }






    void routeToNearestGas() {
        Location from = gpsFix;
        if (from == null) {
            quick.quickGasNote = "NO GPS FIX";
            return;
        }
        RouteDatabase graph = routeDatabase();
        if (graph == null) {
            quick.quickGasNote = "NO ROUTE DATA";
            return;
        }
        if (routingBusy) {
            quick.quickGasNote = "BUSY";
            return;
        }
        quick.quickGasNote = "SEARCHING";
        double fromLat = from.getLatitude(), fromLon = from.getLongitude();
        double heading = from.hasBearing() && from.hasSpeed() && from.getSpeed() > 2.5 ? from.getBearing() : Double.NaN;
        roadMap.nearestFuel(fromLat, fromLon, 4, stations -> {
            if (stations.isEmpty()) {
                quick.quickGasNote = "NONE NEARBY";
                hud.invalidate();
                return;
            }
            if (routingBusy) {
                quick.quickGasNote = "BUSY";
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
                        quick.quickGasNote = "NO ROUTE";
                        raiseAlert("NO ROUTE TO GAS", Alerts.WARN);
                        hud.invalidate();
                        return;
                    }
                    String name = station.name.isEmpty() ? "GAS STATION" : station.name;
                    startRoute(new RouteTrack(name, chosen.lat, chosen.lon, chosen.steps, chosen.seconds), true);
                    navGoal = new double[]{station.lat, station.lon};
                    navGoalName = name;
                    quick.closeQuick();
                });
            });
        });
        hud.invalidate();
    }


    void activateControl(int control) {
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
}
