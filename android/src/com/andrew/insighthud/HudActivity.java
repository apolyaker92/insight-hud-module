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

/** Dedicated HUD launcher with classic Bluetooth OBD and an explicitly labeled display preview. */
public final class HudActivity extends Activity {
    private SharedPreferences preferences;
    private HudView hud;
    private boolean mirror;
    private boolean demo;
    private boolean mapPreview;
    private boolean fullMap;
    private boolean headingUp;
    private int mapRange;
    private static final String[] RANGE_NAMES = {"NORMAL", "WIDE", "CLOSE"};
    private static final float[] RANGE_ZOOM = {1.4f, 1.05f, 1.9f};
    private TripStats trip = new TripStats();
    private JSONObject shutdownReport;
    private long accessoryLostAt;
    private long mapPreviewStartedAt;
    private OfflineRoadMap roadMap;
    private LocationManager locationManager;
    private Location gpsFix;
    private Location lastMapFix;
    private float gpsCourse = Float.NaN;
    private boolean gpsRegistered;
    private boolean gpsUnavailable;
    private boolean gpsTestActive;
    private static final int GPS_PERMISSION = 24;
    private static final long GPS_STALE_MS = 12000;
    private static final long GPS_UNAVAILABLE_MS = 20000;
    private static final long GPS_TEST_MS = 5 * 60 * 1000;
    private static final long GPS_RETRY_MS = 5000;
    private int brightnessMode; // 0 auto (light sensor), 1 high, 2 medium, 3 low
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
    private int buttonsMode; // 0 auto (hide with a keypad), 1 always show, 2 always hide
    private boolean keypadPresent;
    private InputManager inputManager;
    private final InputManager.InputDeviceListener inputListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int id) { updateKeypad(); }
        @Override public void onInputDeviceRemoved(int id) { updateKeypad(); }
        @Override public void onInputDeviceChanged(int id) { updateKeypad(); }
    };
    private static final String[] BUTTON_MODE_NAMES = {"AUTO", "ALWAYS SHOW", "ALWAYS HIDE"};
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
    private static final ObdBluetooth.Readings DEMO_READINGS = new ObdBluetooth.Readings(46d, 2400d, 188d, 85d, 13.8d, 42d, 28d, 0.87d, 52d);
    private static final ObdBluetooth.Readings EMPTY_READINGS = new ObdBluetooth.Readings(null, null, null, null, null, null);
    private static final float[] DEMO_RPM = {820, 880, 1100, 1550, 2250, 3250, 4500, 5000, 3550, 2950,
            3050, 3450, 4200, 4700, 3150, 2680, 2530, 2420, 2450, 2400};
    // Gauges the car has actually reported this session: throttle, load, MPG inputs, intake air.
    private final boolean[] seenGauge = new boolean[4];
    private final Alerts alerts = new Alerts();
    private final AutoStopTracker autoStop = new AutoStopTracker();
    private final SoundPolicy sounds = new SoundPolicy();
    private volatile int autoStopLoadId, autoStartLoadId;
    private static final double WARM_F = 175;  // "operating temperature reached"
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
            if (location.hasBearing() && location.hasSpeed() && location.getSpeed() >= 2f) {
                gpsCourse = location.getBearing();
            }
            main.removeCallbacks(expireGpsFix);
            main.postDelayed(expireGpsFix, GPS_STALE_MS - Math.max(0, age));
            refreshMapTiles();
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
                : preferences.getBoolean("dim", false) ? 3 : 0; // The old NIGHT toggle becomes LOW.
        sensors = getSystemService(SensorManager.class);
        lightSensor = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        buttonsMode = Math.max(0, Math.min(2, preferences.getInt("buttonsMode", 0)));
        sounds.mode = Math.max(0, Math.min(SoundPolicy.MODE_NAMES.length - 1, preferences.getInt("soundMode", 0)));
        sounds.level = Math.max(0, Math.min(SoundPolicy.LEVELS.length - 1,
                preferences.getInt("soundLevel", SoundPolicy.LEVELS.length - 1)));
        sounds.nightQuiet = preferences.getBoolean("nightQuiet", true);
        applyOrientation();
        mapPreview = preferences.getBoolean("mapPreview", false);
        fullMap = preferences.getBoolean("fullMap", false);
        headingUp = preferences.getBoolean("headingUp", false);
        mapRange = Math.max(0, Math.min(RANGE_NAMES.length - 1, preferences.getInt("mapRange", 0)));
        mapPreviewStartedAt = SystemClock.uptimeMillis();
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setStatusBarColor(Color.BLACK);
        hud = new HudView(this);
        setContentView(hud);
        inputManager = getSystemService(InputManager.class);
        if (inputManager != null) inputManager.registerInputDeviceListener(inputListener, main);
        updateKeypad();
        locationManager = getSystemService(LocationManager.class);
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
        }
        Intent battery = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            boolean plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (state != null && plugged && state.getBoolean("accessoryActive")) {
                powerKnown = powered = accessoryActive = true;
                trip.restore(state.getBundle("trip"));
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
        if (Build.VERSION.SDK_INT >= 29 && thermalListener != null) {
            getSystemService(PowerManager.class).removeThermalStatusListener(thermalListener);
        }
        if (inputManager != null) inputManager.unregisterInputDeviceListener(inputListener);
        if (lightRegistered) sensors.unregisterListener(lightListener);
        if (obd != null) obd.destroy();
        switchSounds.release();
        switchSounds = null;
        roadMap.close();
        unregisterReceiver(batteryReceiver);
        super.onDestroy();
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        main.post(preloadSoundLater);
        updateLightSensor();
        startTripAnimation();
        connectSavedAdapter();
        updateMap();
    }

    private void raiseAlert(String text, int level) {
        if (hud == null || parkedBlank || thermalBlank) return;
        if (alerts.raise(text, level, SystemClock.uptimeMillis())) hud.invalidate();
    }

    private boolean ambientDark() {
        return lightSensor != null && autoLevel <= SoundPolicy.DARK_LEVEL;
    }

    /** Every effect goes through here so mode, volume and night-quiet apply to all of them. */
    private void playSound(int id, float base, SoundPolicy.Kind kind) {
        if (switchSounds == null || id == 0) return;
        float gain = sounds.gain(kind, ambientDark());
        if (gain <= 0f) return;
        float volume = Math.min(1f, base * gain);
        switchSounds.play(id, volume, volume, 1, 0, 1);
    }

    /** Landscape either way up, for a phone mounted with its top edge on the left or the right. */
    private void applyOrientation() {
        setRequestedOrientation(preferences.getBoolean("flipScreen", true)
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    private void saveSoundSettings() {
        preferences.edit().putInt("soundMode", sounds.mode).putInt("soundLevel", sounds.level)
                .putBoolean("nightQuiet", sounds.nightQuiet).apply();
        playSound(switchSoundId, 0.5f, SoundPolicy.Kind.FEEDBACK); // lets you hear the new level
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
            demo = false; // A preview left on must not hide the real drive.
            shutdownReport = null;
            shutdownStartedAt = 0;
            parkedBlank = false;
            enterStandbyPower(false);
            setBluetoothPower(true);
            startupPending = true;
            updateScreenPower();
            startTripAnimation();
            if (foreground) connectSavedAdapter();
            updateMap();
        } else if (!plugged && accessoryActive) {
            accessoryLostAt = SystemClock.elapsedRealtime();
            main.postDelayed(confirmPowerLoss, POWER_LOSS_DELAY_MS);
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
        if (next == THERMAL_HOLD) playAlertSound(Alerts.CRITICAL); // the hold screen takes over the banner
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
        accessoryActive = false;
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

    /** While parked and blank nothing should draw power: backlight off, light sensor and chime player released. */
    private void enterStandbyPower(boolean standby) {
        applyBrightness();
        updateLightSensor();
        if (standby) {
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
        foreground = false;
        updateLightSensor();
        updateMap();
        releaseStartupSound();
        // Dialogs, permission prompts and Android settings stop the activity; keep the
        // OBD link and trip sampling alive for the drive. Otherwise release the adapter.
        if (!keepObdInBackground() && obd != null) {
            obd.stop();
            main.removeCallbacks(staleReadings);
            updateReadings(null);
        }
        super.onStop();
    }

    /** Loads the chime ahead of time so power-up never waits on file decoding. */
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

    /**
     * Milliseconds into the start animation. While the chime plays this is the audio's own position,
     * which already includes the output delay, so the picture follows what is heard; otherwise wall time.
     */
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
                // Until audio is actually coming out, hold the picture at zero (but never past 400 ms).
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
            Log.i("HudBt", (on ? "enable" : "disable") + " requested, accepted=" + done);
        } catch (SecurityException refused) {
            Log.i("HudBt", "not allowed to switch Bluetooth (not a privileged app)");
        }
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
        if (gpsTestActive && (!liveMap || accessoryActive)) setGpsTest(false);
        if (liveMap) {
            roadMap.open();
            refreshMapTiles();
        }
        boolean wantGps = liveMap && (accessoryActive || gpsTestActive)
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
        return lastMapFix != null && region.contains(lastMapFix.getLatitude(), lastMapFix.getLongitude())
                ? lastMapFix : null;
    }

    private boolean fixInRegion(OfflineRoadMap.Region region) {
        return gpsFix != null && region.contains(gpsFix.getLatitude(), gpsFix.getLongitude());
    }

    /** A keypad that provides F1-F4 replaces the on-screen buttons. */
    private void updateKeypad() {
        boolean found = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()
                    || (device.getSources() & InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD) continue;
            boolean[] keys = device.hasKeys(KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2,
                    KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4);
            if (keys[0] && keys[1] && keys[2] && keys[3]) {
                found = true;
                break;
            }
        }
        if (found != keypadPresent) {
            keypadPresent = found;
            if (hud != null) hud.invalidate();
        }
    }

    /** The map takes the whole panel, with speed and rpm laid over its corner. */
    private boolean fullMapActive() {
        return fullMap && mapPreview && thermalLevel == THERMAL_NORMAL;
    }

    private boolean controlsHidden() {
        return buttonsMode == 2 || (buttonsMode == 0 && keypadPresent);
    }

    private static final Pattern PROBE_REQUEST = Pattern.compile("(01|02|09|21|22)[0-9A-F]{2,6}");

    /** Read-only raw OBD requests from `am start --es obd_probe 2101,2102`; replies go to logcat. */
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

    /** GPS speed in mph, or null when GPS is off, has no fix, or the fix is too old. */
    private Double gpsSpeedMph() {
        Location fix = gpsFix;
        if (fix == null || !gpsRegistered) return null;
        long age = (SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos()) / 1000000;
        return SpeedSource.gpsMph(fix.hasSpeed(), fix.getSpeed(), age);
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
        if (accessoryActive && powered && !demo) trip.sample(update, SystemClock.elapsedRealtime());
        rpmHistory[rpmNext] = update.rpm == null ? Float.NaN : update.rpm.floatValue();
        rpmNext = (rpmNext + 1) % rpmHistory.length;
        rpmCount = Math.min(rpmCount + 1, rpmHistory.length);
    }

    private void showLinkMenu() {
        if (!hasBluetoothPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 23);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("OBD LINK / " + linkStatus)
                .setItems(new String[]{"Choose paired OBD adapter", "Pair device / enable Bluetooth",
                        "Forget saved adapter", "Android settings",
                        "Bottom buttons: " + BUTTON_MODE_NAMES[buttonsMode], "Toggle mirror", "Toggle demo", "Trip log",
                        "Brightness: " + BRIGHTNESS_NAMES[brightnessMode],
                        "Sound: " + SoundPolicy.MODE_NAMES[sounds.mode],
                        "Volume: " + Math.round(SoundPolicy.LEVELS[sounds.level] * 100) + "%",
                        "Night quiet: " + (sounds.nightQuiet ? "ON" : "OFF"),
                        "Bluetooth off when parked: " + (preferences.getBoolean("btParkOff", true) ? "ON" : "OFF"),
                        "Flip screen 180: " + (preferences.getBoolean("flipScreen", true) ? "ON" : "OFF"),
                        "Full screen map: " + (fullMap ? "ON" : "OFF")}, (dialog, choice) -> {
                    switch (choice) {
                        case 0:
                            chooseAdapter();
                            break;
                        case 1:
                            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                            break;
                        case 2:
                            preferences.edit().remove("adapterAddress").remove("adapterName").apply();
                            connectSavedAdapter();
                            break;
                        case 3:
                            startActivity(new Intent(Settings.ACTION_SETTINGS));
                            break;
                        case 4:
                            buttonsMode = (buttonsMode + 1) % BUTTON_MODE_NAMES.length;
                            preferences.edit().putInt("buttonsMode", buttonsMode).apply();
                            hud.invalidate();
                            break;
                        case 5:
                            activateControl(0);
                            break;
                        case 6:
                            activateControl(1);
                            break;
                        case 7:
                            showTripHistory();
                            break;
                        case 8:
                            activateControl(2);
                            break;
                        case 9:
                            cycleSoundMode();
                            break;
                        case 10:
                            cycleVolume();
                            break;
                        case 11:
                            toggleNightQuiet();
                            break;
                        case 12:
                            preferences.edit().putBoolean("btParkOff", !preferences.getBoolean("btParkOff", true)).apply();
                            break;
                        case 13:
                            preferences.edit().putBoolean("flipScreen", !preferences.getBoolean("flipScreen", true)).apply();
                            applyOrientation();
                            break;
                        case 14:
                            activateControl(9);
                            break;
                    }
                }).setNegativeButton("CLOSE", null).show();
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
        if (keyCode == KeyEvent.KEYCODE_F5 || keyCode == KeyEvent.KEYCODE_F6) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                    && hasWindowFocus() && !parkedBlank && shutdownStartedAt == 0 && thermalLevel != THERMAL_HOLD) {
                if (keyCode == KeyEvent.KEYCODE_F5) cycleSoundMode();
                else cycleVolume();
            }
            return true;
        }
        int control;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_F1: control = 4; break;
            case KeyEvent.KEYCODE_F2: control = 6; break;
            case KeyEvent.KEYCODE_F3: control = 7; break;
            case KeyEvent.KEYCODE_F4: control = 2; break;
            default: return super.dispatchKeyEvent(event);
        }
        if ((control == 6 || control == 7) && !mapPreview) return true; // Heading and range only act on the map.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && hasWindowFocus() && !parkedBlank && shutdownStartedAt == 0 && thermalLevel != THERMAL_HOLD
                && (startupStartedAt == 0 || startupClock(SystemClock.uptimeMillis()) >= STARTUP_DURATION_MS)) {
            activateControl(control);
        }
        return true;
    }

    private void activateControl(int control) {
        boolean skipClick = false;
        switch (control) {
            case 0:
                mirror = !mirror;
                preferences.edit().putBoolean("mirror", mirror).apply();
                break;
            case 1:
                demo = !demo;
                main.removeCallbacks(staleReadings);
                connectSavedAdapter();
                updateMap();
                break;
            case 2:
                brightnessMode = (brightnessMode + 1) % BRIGHTNESS_NAMES.length;
                preferences.edit().putInt("brightnessMode", brightnessMode).apply();
                updateLightSensor();
                applyBrightness();
                break;
            case 3:
                showTripHistory();
                break;
            case 4:
                mapPreview = !mapPreview;
                mapPreviewStartedAt = SystemClock.uptimeMillis();
                preferences.edit().putBoolean("mapPreview", mapPreview).apply();
                updateMap();
                break;
            case 5:
                enableMapLocation();
                break;
            case 6:
                headingUp = !headingUp;
                preferences.edit().putBoolean("headingUp", headingUp).apply();
                // The map's two-step lock-then-swing only runs when a course is known; sound matches it.
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
                // Sound follows the map's zoom animation, so it only plays while the map is showing.
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
            default:
                return;
        }
        if (!skipClick) playSound(switchSoundId, 0.42f, SoundPolicy.Kind.UI);
        hud.invalidate();
    }

    private final class HudView extends View {
        private static final int AMBER = 0xFFFFB64F;
        private static final int MUTED = 0xFFB1874B;
        private static final int LINE = 0xFF61451F;
        private static final int UNLIT = 0xFF1D160A;
        private static final int CONTROL_LEFT = 48;
        private static final int CONTROL_STEP = 240;
        private static final int CONTROL_WIDTH = 224;
        private static final int CONTROL_COUNT = 5;
        private static final float MAP_LEFT_SPLIT = 650;
        private static final float MAP_LEFT_FULL = 65;
        private float mapLeft() { return fullMapActive() ? MAP_LEFT_FULL : MAP_LEFT_SPLIT; }
        private static final float MAP_TOP = 131;
        private static final float MAP_RIGHT = 1220;
        private float mapBottom() { return 448 + extra(); }
        private float mapCenterX() { return (mapLeft() + MAP_RIGHT) / 2; }
        private float mapCenterY() { return (MAP_TOP + mapBottom()) / 2; }
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

        /** Rows freed when the on-screen buttons are hidden; the frame grows by this much. */
        private float extra() { return controlsHidden() ? 60 : 0; }

        HudView(Context context) {
            super(context);
            // The PH-1's legacy GPU driver drops cached text on state changes.
            // Rasterize this static instrument only when its data changes.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setContentDescription("Insight HUD Module 2.3. Speed, coolant, and RPM history on the left. Gauges or an offline road map on the right. Bluetooth setup is in the header. Bottom controls include the trip log.");
            setFocusable(true);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (parkedBlank || thermalBlank) {
                canvas.drawColor(Color.BLACK);
                return;
            }
            canvas.drawColor(0xFF080602);
            scale = Math.min(getWidth() / 1280f, getHeight() / 640f);
            left = (getWidth() - 1280 * scale) / 2;
            top = (getHeight() - 640 * scale) / 2;
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
            else drawGauges(canvas, now);
            boolean alertHeld = thermalHold || shuttingDown || starting || startupPending;
            Alerts.Alert alert = alerts.tick(now, alertHeld);
            if (alert != null) {
                if (alerts.justStarted()) playAlertSound(alert.level);
                drawAlert(canvas, alert, now);
            }
            canvas.restore();
            if (!starting && !shuttingDown && !thermalHold && extra() == 0) drawControls(canvas);
            canvas.restore();
            if (alerts.busy() && isShown()) postInvalidateDelayed(60);
            if ((thermalHold || starting || shuttingDown
                    || (thermalLevel == THERMAL_LIMIT && now - thermalStartedAt < THERMAL_WARNING_MS))
                    && isShown()) postInvalidateDelayed(starting ? 33 : 80);
            else if (thermalLevel == THERMAL_NORMAL && mapPreview && foreground && hasWindowFocus()) {
                if (now - cameraStartedAt < cameraDuration || now - rangeStartedAt < 500
                        || (lockStartedAt != 0 && now - lockStartedAt < TARGET_CAPTION_MS)) postInvalidateDelayed(32);
                else if (demo && powered) postInvalidateDelayed(100);
            }
        }

        /** A banner that takes over the header: calm for info, flashing for warnings and critical alerts. */
        private void drawAlert(Canvas canvas, Alerts.Alert alert, long now) {
            long age = now - alert.shownAt;
            boolean flashing = alert.level > Alerts.INFO;
            boolean inverted = flashing && age / (alert.level == Alerts.CRITICAL ? 220 : 420) % 2 == 0 && age < 4000;
            int fill = inverted ? AMBER : 0xFF080602;
            int ink = inverted ? 0xFF080602 : AMBER;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            canvas.drawRect(41, 35, 1239, 87, paint);
            border(canvas, 40, 34, 1240, 88);
            String prefix = alert.level == Alerts.CRITICAL ? "!! " : alert.level == Alerts.WARN ? "! " : "> ";
            float slide = Math.min(1f, age / 180f); // text eases in from the left
            text(canvas, prefix + alert.text, 640 - (1 - slide) * 80, 72, 30, ink, true, Paint.Align.CENTER);
        }

        private void drawThermalWarning(Canvas canvas, long now) {
            border(canvas, 40, 34, 1240, 552 + extra());
            text(canvas, "INSIGHT / HUD MODULE 2.3", 60, 69, 17, MUTED, false);
            text(canvas, "THERMAL PROTECTION", 1220, 69, 17, AMBER, false, Paint.Align.RIGHT);
            line(canvas, 40, 88, 1240, 88, LINE);
            long elapsed = now - thermalStartedAt;
            int color = elapsed % 1200 < 700 ? 0xFFFF8A45 : AMBER;
            text(canvas, "OVERHEATING", 640, 254, 61, color, true, Paint.Align.CENTER);
            text(canvas, "BATTERY " + Math.round(batteryTemperature) + " °C", 640, 307,
                    25, AMBER, false, Paint.Align.CENTER);
            text(canvas, "GPS AND OBD PAUSED", 640, 358, 18, MUTED, false, Paint.Align.CENTER);
            drawThermalTicker(canvas, 330, 950, 422, elapsed, "COOL PHONE BEFORE USE   /   HUD ENTERING STANDBY");
        }

        private void drawThermalLimit(Canvas canvas, long now) {
            text(canvas, "03  THERMAL LIMIT", mapLeft(), 118, 19, AMBER, false);
            border(canvas, mapLeft(), MAP_TOP, MAP_RIGHT, mapBottom());
            text(canvas, "PHONE RUNNING HOT", mapCenterX(), 235, 27, AMBER, true, Paint.Align.CENTER);
            text(canvas, "BATTERY " + Math.round(batteryTemperature) + " °C", mapCenterX(), 283,
                    20, AMBER, false, Paint.Align.CENTER);
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
                text(canvas, message, x, y, 16, AMBER, false);
            } else {
                text(canvas, message, (left + right) / 2, y, 16, AMBER, false, Paint.Align.CENTER);
            }
            canvas.restore();
        }

        private void drawShutdown(Canvas canvas, long elapsed) {
            border(canvas, 40, 34, 1240, 552 + extra());
            text(canvas, "INSIGHT / HUD MODULE 2.3", 60, 69, 17, MUTED, false);
            line(canvas, 40, 88, 1240, 88, LINE);
            if (shutdownReport == null) {
                text(canvas, "ACCESSORY POWER OFF", 640, 270, 42, AMBER, true, Paint.Align.CENTER);
                text(canvas, "HUD STANDBY", 640, 325, 24, MUTED, false, Paint.Align.CENTER);
            } else {
                text(canvas, shutdownReport.optLong("ended") == 0 ? "TRIP REPORT / DEMO" : "TRIP REPORT / SAVED",
                        640, 171, 35, AMBER, true, Paint.Align.CENTER);
                reportMetric(canvas, 210, "ELAPSED", TripStats.time(shutdownReport));
                reportMetric(canvas, 495, "EST. MILES", TripStats.value(shutdownReport, "miles", "%.1f"));
                reportMetric(canvas, 785, "PEAK RPM", TripStats.value(shutdownReport, "rpm", "%.0f"));
                reportMetric(canvas, 1070, "PEAK COOLANT", TripStats.value(shutdownReport, "coolant", "%.0f") + " °F");
                text(canvas, "ACCESSORY POWER OFF / HUD STANDBY", 640, 415, 16, MUTED, false, Paint.Align.CENTER);
            }
            int litBlocks = 40 - (int) (elapsed * 40 / shutdownDuration());
            for (int index = 0; index < 40; index++) {
                paint.setColor(index < litBlocks ? AMBER : UNLIT);
                rectangle.set(65 + index * 29, 481, 85 + index * 29, 496);
                canvas.drawRect(rectangle, paint);
            }
        }

        private void reportMetric(Canvas canvas, int x, String name, String value) {
            text(canvas, name, x, 258, 17, MUTED, false, Paint.Align.CENTER);
            text(canvas, value, x, 320, 35, AMBER, true, Paint.Align.CENTER);
        }

        private void drawStartup(Canvas canvas, long elapsed) {
            border(canvas, 40, 34, 1240, 552 + extra());
            text(canvas, "INSIGHT / HUD MODULE 2.3", 60, 69, 17, MUTED, false);
            text(canvas, "SYSTEM START", 1220, 69, 17, AMBER, false, Paint.Align.RIGHT);
            line(canvas, 40, 88, 1240, 88, LINE);

            if (elapsed < 800) {
                int letters = Math.min(7, 1 + (int) (elapsed / 65));
                text(canvas, "INSIGHT".substring(0, letters), 640, 285, 76, AMBER, true, Paint.Align.CENTER);
                text(canvas, "HUD MODULE", 640, 334, 21, MUTED, false, Paint.Align.CENTER);
                number(canvas, "2.3", 598, 360, 32);
            } else if (elapsed < 1400) {
                text(canvas, "DISPLAY SEGMENT TEST", 65, 137, 20, AMBER, false);
                number(canvas, "888", 65, 177, 119);
                number(canvas, "88", 670, 177, 52);
                number(canvas, "88.8", 670, 311, 52);
                text(canvas, "TEST PATTERN", 1208, 225, 16, MUTED, false, Paint.Align.RIGHT);
                text(canvas, "ALL SEGMENTS", 1208, 359, 16, MUTED, false, Paint.Align.RIGHT);
            } else {
                text(canvas, "INSIGHT", 65, 169, 44, AMBER, true);
                text(canvas, "HUD MODULE 2.3", 65, 202, 16, MUTED, false);
                String[] checks = {
                    "MODULE 2.3 ............ ONLINE",
                    "PROFILE ............... ZE1 / 2001",
                    powered ? "POWER ................. EXTERNAL" : "POWER ................. BATTERY",
                    "OBD LINK .............. " + linkStatus
                };
                int visibleChecks = Math.min(checks.length, 1 + (int) ((elapsed - 1400) / 180));
                for (int index = 0; index < visibleChecks; index++) {
                    text(canvas, checks[index], 65, 254 + index * 37, 19, AMBER, false);
                }
                if (elapsed >= 2250) {
                    text(canvas, "HUD MODULE ARMED", 1210, 435, 24, AMBER, true, Paint.Align.RIGHT);
                }
            }

            line(canvas, 60, 462, 1220, 462, LINE);
            int litBlocks = Math.min(40, (int) (elapsed * 40 / STARTUP_DURATION_MS) + 1);
            for (int index = 0; index < 40; index++) {
                paint.setColor(index < litBlocks ? AMBER : UNLIT);
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
            text(canvas, "INSIGHT", 60, 69, 24, AMBER, true);
            text(canvas, "HUD MODULE 2.3", 230, 68, 16, MUTED, false);
            text(canvas, demo ? "[ DEMO - SIMULATED ]" : "[ " + linkStatus + " ]",
                    1115, 68, 16, AMBER, false, Paint.Align.RIGHT);
            border(canvas, 1134, 46, 1220, 77);
            text(canvas, "BT SETUP", 1177, 67, 13, MUTED, false, Paint.Align.CENTER);
            line(canvas, 40, 88, 1240, 88, LINE);
            boolean full = fullMapActive();
            if (!full) text(canvas, "01  VEHICLE SPEED", 65, 127, 19, AMBER, false);
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
                text(canvas, "MPH", 477, 332, 32, AMBER, false);
                if (speedFromGps) text(canvas, "GPS", 477, 358, 14, MUTED, false);
                drawWarmup(canvas, displayed.coolantF);
                drawRpmTrace(canvas, displayed.rpm);
            }

            float footer = 480 + extra();
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
            if (full && thermalLevel == THERMAL_NORMAL) drawMapReadouts(canvas, displayed, speed, speedFromGps);

            line(canvas, 40, footer, 1240, footer, LINE);
            text(canvas, "ZE1 / MODULE 2.3", 60, footer + 30, 15, AMBER, false);
            text(canvas, "BRIGHT " + BRIGHTNESS_NAMES[brightnessMode] + (brightnessMode == 0
                    ? "  " + Math.round(autoLevel * 100) + "%" : "")
                    + "    SND " + sounds.label(ambientDark()), 640, footer + 56, 12, MUTED, false,
                    Paint.Align.CENTER);
            String adapterName = preferences.getString("adapterName", "NO ADAPTER SELECTED");
            if (adapterName.length() > 28) adapterName = adapterName.substring(0, 28);
            text(canvas, "BT / " + adapterName.toUpperCase(Locale.US), 60, footer + 56, 12, MUTED, false);
            String phoneState = (battery < 0 ? "--" : String.valueOf(battery)) + "%  /  "
                    + Math.round(batteryTemperature) + " C";
            text(canvas, "PHONE " + phoneState, 1220, footer + 31, 14, MUTED, false, Paint.Align.RIGHT);
            text(canvas, powered ? "EXT POWER" : "BATTERY POWER", 1220, footer + 56, 12, MUTED, false, Paint.Align.RIGHT);
        }

        /** Throttle, load, estimated MPG and (as a fallback) intake air; only gauges the car reports. */
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
            text(canvas, wholeReading(coolant, "--") + "°F", 573, 331, 14, AMBER, false, Paint.Align.CENTER);
            // This is a display scale, not an engine warning threshold.
            int lit = coolant == null ? 0 : Math.max(0, (int) Math.round((coolant - 100) / 90 * 10));
            for (int index = 0; index < 10; index++) {
                paint.setColor(index < lit ? AMBER : UNLIT);
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

        private void drawMapMode(Canvas canvas) {
            border(canvas, MAP_RANGE_LEFT, 96, MAP_MODE_LEFT - 8, 124);
            text(canvas, RANGE_NAMES[mapRange], (MAP_RANGE_LEFT + MAP_MODE_LEFT - 8) / 2,
                    115, 13, AMBER, false, Paint.Align.CENTER);
            border(canvas, MAP_MODE_LEFT, 96, MAP_RIGHT, 124);
            text(canvas, headingUp ? "HEADING UP" : "NORTH UP", (MAP_MODE_LEFT + MAP_RIGHT) / 2,
                    115, 13, AMBER, false, Paint.Align.CENTER);
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
            text(canvas, "03  MOVING MAP", mapLeft(), 118, 19, AMBER, false);
            text(canvas, "SIMULATED", MAP_RANGE_LEFT - 12, 118, 14, MUTED, false, Paint.Align.RIGHT);
            drawMapMode(canvas);
            border(canvas, mapLeft(), MAP_TOP, MAP_RIGHT, mapBottom());
            float progress = (now - mapPreviewStartedAt) % 24000 / 24000f;
            sampleDrive.getPosTan(progress * sampleDrive.getLength(), marker, direction);
            float course = 90 + (float) Math.toDegrees(Math.atan2(direction[1], direction[0]));
            updateMapCamera(now, course);
            float anchorX = mapCenterX() + (marker[0] - 285) * 0.58f * (1 - cameraLead);
            float anchorY = mapCenterY() + (marker[1] - 160) * 0.58f * (1 - cameraLead)
                    + FORWARD_OFFSET * cameraLead;
            setMapTransform(marker[0], marker[1], 1, anchorX, anchorY);
            canvas.save();
            canvas.clipRect(mapLeft() + 1, MAP_TOP + 1, MAP_RIGHT - 1, mapBottom() - 1);
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
            demoRoadLabel(canvas, "ROUTE 08", 420, 76, anchorX, anchorY, AMBER);
            demoRoadLabel(canvas, "RIVER RD", 43, 179, anchorX, anchorY, MUTED);
            demoRoadLabel(canvas, "DEPOT AVE", 390, 306, anchorX, anchorY, MUTED);
            drawMapMarker(canvas, anchorX, anchorY, course, now,
                    42.607 + (160 - marker[1]) * 0.00003, -73.700 + (marker[0] - 285) * 0.00004);
            canvas.restore();
            text(canvas, "DEMO ROADS / GPS INACTIVE", mapLeft(), 469 + extra(), 12, MUTED, false);
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
            text(canvas, "03  OFFLINE MAP", mapLeft(), 118, 19, AMBER, false);
            text(canvas, region == null ? "ALBANY REGION" : region.name,
                    MAP_RANGE_LEFT - 12, 118, 11, MUTED, false, Paint.Align.RIGHT);
            drawMapMode(canvas);
            border(canvas, mapLeft(), MAP_TOP, MAP_RIGHT, mapBottom());
                        String status = mapStatus(region);
            if (headingUp && "GPS FIX".equals(status) && Float.isNaN(gpsCourse)) status = "GPS FIX / WAITING FOR COURSE";
            text(canvas, status, mapLeft(), 469 + extra(), 12, AMBER, false);
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
            canvas.clipRect(mapLeft() + 1, MAP_TOP + 1, MAP_RIGHT - 1, mapBottom() - 1);
            canvas.save();
            canvas.concat(mapTransform);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(LINE);
            float strokeScale = MAP_TILE_PIXELS * rangeZoom * (1 + cameraLead * 0.23f);
            paint.setStrokeWidth(1.2f / strokeScale);
            canvas.drawPath(scene.roads[0], paint);
            paint.setColor(MUTED);
            paint.setStrokeWidth(2.1f / strokeScale);
            canvas.drawPath(scene.roads[1], paint);
            paint.setColor(AMBER);
            paint.setStrokeWidth(3.1f / strokeScale);
            canvas.drawPath(scene.roads[2], paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);
            canvas.restore();

            // Keep the reflected display legible by separating labels from each other and the marker.
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
                        || y < MAP_TOP + 16 || y > mapBottom() - 18) continue;
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
                paint.setColor(0xFF080602);
                canvas.drawRect(x - halfWidth, y - 14, x + halfWidth, y + 4, paint);
                text(canvas, label.name, x, y, 13, AMBER, false, Paint.Align.CENTER);
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

        /** Speed and rpm over the corner of a full-screen map, on a dark plate so the roads never fight the digits. */
        private void drawMapReadouts(Canvas canvas, ObdBluetooth.Readings displayed, String speed, boolean fromGps) {
            float plateLeft = mapLeft() + 1;
            float plateRight = plateLeft + 330;
            float plateBottom = mapBottom() - 1;
            float plateTop = plateBottom - 128;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xEB080602);
            canvas.drawRect(plateLeft, plateTop, plateRight, plateBottom, paint);
            line(canvas, plateLeft, plateTop, plateRight, plateTop, LINE);
            line(canvas, plateRight, plateTop, plateRight, plateBottom, LINE);
            text(canvas, speed, plateLeft + 196, plateBottom - 34, 84, AMBER, true, Paint.Align.RIGHT);
            text(canvas, fromGps ? "MPH GPS" : "MPH", plateLeft + 210, plateBottom - 36, 22, AMBER, false);
            text(canvas, wholeReading(displayed.rpm, "----") + " RPM", plateLeft + 210, plateBottom - 64,
                    18, MUTED, false);
        }

        private void drawSatelliteWarning(Canvas canvas) {
            float left = mapCenterX() - 174;
            float top = mapCenterY() - 30;
            paint.setColor(0xF2080602);
            canvas.drawRect(left, top, left + 348, top + 60, paint);
            border(canvas, left, top, left + 348, top + 60);
            text(canvas, "SAT NOT AVAILABLE", mapCenterX(), mapCenterY() + 7,
                    24, AMBER, true, Paint.Align.CENTER);
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
            paint.setColor(0xFF080602);
            canvas.drawCircle(0, 0, 19, paint);
            long elapsed = now - lockStartedAt;
            if (lockStartedAt != 0 && elapsed < MAP_TRANSITION_MS) {
                float fade = Math.min(1, (MAP_TRANSITION_MS - elapsed) / 370f);
                int reticle = (Math.round(fade * 255) << 24) | (AMBER & 0xFFFFFF);
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
            paint.setColor(AMBER);
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
            float top = Math.max(MAP_TOP + 4 - y, Math.min(-53, mapBottom() - 52 - y));
            paint.setColor((Math.round(opacity * 235) << 24) | 0x080602);
            canvas.drawRect(left, top, left + 176, top + 44, paint);
            line(canvas, left, top, left + 176, top, alpha | (MUTED & 0xFFFFFF));
            line(canvas, side * 28, -8, side * 42, top + 34, alpha | (MUTED & 0xFFFFFF));
            text(canvas, demo ? "TARGET ZE1 / SIM" : "TARGET ZE1 / LOCK",
                    left + 8, top + 17, 13, alpha | (AMBER & 0xFFFFFF), true);
            String coordinates = String.format(Locale.US, "%.4f%s  %.4f%s", Math.abs(latitude),
                    latitude >= 0 ? "N" : "S", Math.abs(longitude), longitude >= 0 ? "E" : "W");
            text(canvas, coordinates, left + 8, top + 34, 12, alpha | (AMBER & 0xFFFFFF), false);
        }

        private void drawRpmTrace(Canvas canvas, Double rpm) {
            line(canvas, 65, 350, 590, 350, LINE);
            text(canvas, "02  ENGINE RPM", 65, 372, 15, AMBER, false);
            text(canvas, wholeReading(rpm, "----") + " RPM", 590, 372, 15, AMBER, true, Paint.Align.RIGHT);
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
                    paint.setColor(AMBER);
                    canvas.drawCircle(x, y, 3, paint);
                }
            }
            paint.setColor(AMBER);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.5f);
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void metric(Canvas canvas, String label, Double value, String unit, float y, int minimum, int maximum) {
            text(canvas, label, 650, y, 17, AMBER, false);
            String digits = unit.equals("V")
                    ? (value == null ? "--.-" : String.format(Locale.US, "%.1f", value))
                    : wholeReading(value, "--");
            number(canvas, digits, 658, y + 16, 29);
            text(canvas, unit, 1210, y + 67, 22, AMBER, false, Paint.Align.RIGHT);
            int segments = 16;
            int lit = value == null ? 0 : (int) Math.round(segments * (value - minimum) / (maximum - minimum));
            for (int index = 0; index < segments; index++) {
                paint.setColor(index < lit ? AMBER : UNLIT);
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
                    paint.setColor(AMBER);
                    float dot = width * 0.16f;
                    canvas.drawRect(x, y + width * 1.88f - dot, x + dot, y + width * 1.88f, paint);
                    x += width * 0.4f;
                } else {
                    digit(canvas, character, x, y, width, AMBER);
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

        private void drawControls(Canvas canvas) {
            String[] labels = {mirror ? "MIRROR  ON" : "MIRROR  OFF", demo ? "DEMO  ON" : "DEMO  OFF",
                    "BRIGHT  " + BRIGHTNESS_NAMES[brightnessMode], "TRIP LOG", mapPreview ? "SHOW GAUGES" : "SHOW MAP"};
            for (int index = 0; index < labels.length; index++) {
                float x = CONTROL_LEFT + index * CONTROL_STEP;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.5f);
                paint.setColor(pressedControl == index ? AMBER : LINE);
                rectangle.set(x, 570, x + CONTROL_WIDTH, 620);
                canvas.drawRect(rectangle, paint);
                paint.setStyle(Paint.Style.FILL);
                text(canvas, labels[index], x + CONTROL_WIDTH / 2f, 601, 15,
                        index == 1 && demo ? AMBER : MUTED, false, Paint.Align.CENTER);
            }
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
                    && y >= 88 && y < MAP_TOP) return 7;
            if (thermalLevel == THERMAL_NORMAL && mapPreview && mapX >= MAP_MODE_LEFT - 7 && mapX <= MAP_RIGHT + 4
                    && y >= 88 && y < MAP_TOP) return 6;
            if (thermalLevel == THERMAL_NORMAL && mapPreview && !demo && mapX >= mapLeft() && mapX <= MAP_RIGHT
                    && y >= MAP_TOP && y <= 480 + extra()
                    && (!hasPreciseLocation() || !gpsEnabled() || (!powered && !accessoryActive))) return 5;
            if (thermalLevel == THERMAL_NORMAL && mapPreview && mapX >= mapLeft() && mapX <= MAP_RIGHT
                    && y >= MAP_TOP && y <= 480 + extra()) return 9;
            if (extra() > 0 || y < 570 || y > 620 || x < CONTROL_LEFT) return -1;
            int index = (int) ((x - CONTROL_LEFT) / CONTROL_STEP);
            if (index >= CONTROL_COUNT || x > CONTROL_LEFT + index * CONTROL_STEP + CONTROL_WIDTH) return -1;
            return index;
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
