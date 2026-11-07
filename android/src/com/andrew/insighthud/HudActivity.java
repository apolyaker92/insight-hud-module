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
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Dedicated HUD launcher with classic Bluetooth OBD and an explicitly labeled display preview. */
public final class HudActivity extends Activity {
    private SharedPreferences preferences;
    private HudView hud;
    private boolean mirror;
    private boolean demo;
    private boolean mapPreview;
    private long mapPreviewStartedAt;
    private boolean dim;
    private boolean powered;
    private boolean powerKnown;
    private boolean accessoryActive;
    private boolean startupPending;
    private boolean parkedBlank;
    private int battery = -1;
    private float batteryTemperature;
    private long startupStartedAt;
    private long shutdownStartedAt;
    private MediaPlayer startupSound;
    private static final long STARTUP_DURATION_MS = 2800;
    private static final long POWER_LOSS_DELAY_MS = 3000;
    private static final long SHUTDOWN_DURATION_MS = 1200;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothAdapter bluetooth;
    private ObdBluetooth obd;
    private ObdBluetooth.Readings readings;
    private static final ObdBluetooth.Readings DEMO_READINGS = new ObdBluetooth.Readings(46d, 2400d, 188d, 85d, 13.8d, 42d);
    private static final ObdBluetooth.Readings EMPTY_READINGS = new ObdBluetooth.Readings(null, null, null, null, null, null);
    private static final float[] DEMO_RPM = {820, 880, 1100, 1550, 2250, 3250, 4500, 5000, 3550, 2950,
            3050, 3450, 4200, 4700, 3150, 2680, 2530, 2420, 2450, 2400};
    private final float[] rpmHistory = new float[DEMO_RPM.length];
    private int rpmNext;
    private int rpmCount;
    private String linkStatus = "SELECT BT ADAPTER";
    private boolean foreground;
    private final Runnable confirmPowerLoss = this::parkHud;
    private final Runnable finishShutdown = this::blankParkedHud;
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
        preferences = getSharedPreferences("hud", MODE_PRIVATE);
        mirror = preferences.getBoolean("mirror", false);
        dim = preferences.getBoolean("dim", false);
        mapPreview = preferences.getBoolean("mapPreview", false);
        mapPreviewStartedAt = SystemClock.uptimeMillis();
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setStatusBarColor(Color.BLACK);
        hud = new HudView(this);
        setContentView(hud);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bluetooth = manager == null ? null : manager.getAdapter();
        if (bluetooth != null) {
            obd = new ObdBluetooth(bluetooth, (status, update) -> {
                main.removeCallbacks(staleReadings);
                linkStatus = status;
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
                updateScreenPower();
            }
            batteryReceiver.onReceive(this, battery);
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
        if (obd != null) obd.destroy();
        unregisterReceiver(batteryReceiver);
        super.onDestroy();
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        startTripAnimation();
        connectSavedAdapter();
    }

    private void updateAccessoryPower(boolean plugged) {
        if (powerKnown && powered == plugged) return;
        powerKnown = true;
        powered = plugged;
        main.removeCallbacks(confirmPowerLoss);
        if (plugged && !accessoryActive) {
            main.removeCallbacks(finishShutdown);
            accessoryActive = true;
            shutdownStartedAt = 0;
            parkedBlank = false;
            startupPending = true;
            updateScreenPower();
            startTripAnimation();
            if (foreground) connectSavedAdapter();
        } else if (!plugged && accessoryActive) {
            main.postDelayed(confirmPowerLoss, POWER_LOSS_DELAY_MS);
        }
    }

    private void startTripAnimation() {
        if (startupPending && foreground && hasWindowFocus()
                && getSystemService(PowerManager.class).isInteractive()) {
            startupPending = false;
            releaseStartupSound();
            playStartupSound();
            hud.invalidate();
        }
    }

    private void parkHud() {
        accessoryActive = false;
        startupPending = false;
        startupStartedAt = 0;
        releaseStartupSound();
        main.removeCallbacks(staleReadings);
        connectSavedAdapter();
        if (foreground && getSystemService(PowerManager.class).isInteractive()) {
            shutdownStartedAt = SystemClock.uptimeMillis();
            main.postDelayed(finishShutdown, SHUTDOWN_DURATION_MS);
        } else {
            blankParkedHud();
        }
        hud.invalidate();
    }

    private void blankParkedHud() {
        shutdownStartedAt = 0;
        parkedBlank = true;
        updateScreenPower();
        hud.invalidate();
    }

    @Override protected void onStop() {
        foreground = false;
        releaseStartupSound();
        if (obd != null) obd.stop();
        main.removeCallbacks(staleReadings);
        updateReadings(null);
        super.onStop();
    }

    private void playStartupSound() {
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            startupSound = MediaPlayer.create(this, R.raw.startup_chime, attributes,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (startupSound != null) {
                startupSound.setOnCompletionListener(player -> releaseStartupSound());
                startupSound.setOnErrorListener((player, error, detail) -> {
                    releaseStartupSound();
                    return true;
                });
                startupSound.start();
            }
        } catch (IllegalStateException unavailable) {
            releaseStartupSound();
        } finally {
            startupStartedAt = SystemClock.uptimeMillis();
        }
    }

    private void releaseStartupSound() {
        if (startupSound != null) {
            startupSound.release();
            startupSound = null;
        }
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void connectSavedAdapter() {
        updateReadings(null);
        if (obd != null) obd.stop();
        String address = preferences.getString("adapterAddress", "");
        if (bluetooth == null) linkStatus = "BLUETOOTH UNAVAILABLE";
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

    private void updateReadings(ObdBluetooth.Readings update) {
        readings = update;
        if (update == null) {
            rpmNext = 0;
            rpmCount = 0;
            return;
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
        new AlertDialog.Builder(this)
                .setTitle("OBD LINK / " + linkStatus)
                .setItems(new String[]{"Choose paired OBD adapter", "Pair device / enable Bluetooth",
                        "Forget saved adapter", "Android settings"}, (dialog, choice) -> {
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
                    }
                }).setNegativeButton("CLOSE", null).show();
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
                        .setMessage("Plug the Veepeak into the car and switch the ignition on. Pair it in Android Bluetooth settings, then return here and choose it.")
                        .setPositiveButton("BLUETOOTH SETTINGS", (dialog, which) ->
                                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                        .setNegativeButton("CANCEL", null).show();
                return;
            }
            String[] labels = new String[paired.size()];
            for (int index = 0; index < paired.size(); index++) {
                BluetoothDevice device = paired.get(index);
                labels[index] = (device.getName() == null ? "Bluetooth device" : device.getName())
                        + "\n" + device.getAddress();
            }
            new AlertDialog.Builder(this).setTitle("SELECT OBD ADAPTER")
                    .setItems(labels, (dialog, index) -> {
                        BluetoothDevice device = paired.get(index);
                        preferences.edit().putString("adapterAddress", device.getAddress())
                                .putString("adapterName", device.getName() == null ? "OBD ADAPTER" : device.getName()).apply();
                        demo = false;
                        connectSavedAdapter();
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
        super.onSaveInstanceState(state);
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void updateScreenPower() {
        boolean drivingDisplay = accessoryActive || shutdownStartedAt != 0;
        setShowWhenLocked(drivingDisplay);
        if (drivingDisplay) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void applyBrightness() {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = dim ? 0.08f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(attributes);
    }

    private void activateControl(int control) {
        switch (control) {
            case 0:
                mirror = !mirror;
                preferences.edit().putBoolean("mirror", mirror).apply();
                break;
            case 1:
                demo = !demo;
                main.removeCallbacks(staleReadings);
                connectSavedAdapter();
                break;
            case 2:
                dim = !dim;
                preferences.edit().putBoolean("dim", dim).apply();
                applyBrightness();
                break;
            case 3:
                showLinkMenu();
                break;
            case 4:
                mapPreview = !mapPreview;
                mapPreviewStartedAt = SystemClock.uptimeMillis();
                preferences.edit().putBoolean("mapPreview", mapPreview).apply();
                break;
            default:
                return;
        }
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
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path sampleLoop = road(106, 258, 150, 225, 190, 155, 260, 131,
                340, 131, 380, 166, 416, 224, 384, 263, 315, 279, 205, 270, 106, 258);
        private final PathMeasure sampleDrive = new PathMeasure(sampleLoop, false);
        private final float[] marker = new float[2];
        private final float[] direction = new float[2];
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

        HudView(Context context) {
            super(context);
            // The PH-1's legacy GPU driver drops cached text on state changes.
            // Rasterize this static instrument only when its data changes.
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setContentDescription("Insight HUD Module 2.3. Speed and RPM history on the left. Gauges or a simulated road map on the right. Bottom controls toggle mirror, demo, night brightness, Bluetooth setup, and map preview.");
            setFocusable(true);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (parkedBlank) {
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
            long startupElapsed = now - startupStartedAt;
            boolean starting = startupStartedAt != 0 && startupElapsed < STARTUP_DURATION_MS;
            boolean shuttingDown = shutdownStartedAt != 0;
            if (shuttingDown) drawShutdown(canvas, now - shutdownStartedAt);
            else if (starting) drawStartup(canvas, startupElapsed);
            else drawGauges(canvas, now);
            canvas.restore();
            if (!starting && !shuttingDown) drawControls(canvas);
            canvas.restore();
            if ((starting || shuttingDown) && isShown()) postInvalidateDelayed(80);
            else if (mapPreview && foreground && powered && hasWindowFocus()) postInvalidateDelayed(100);
        }

        private void drawShutdown(Canvas canvas, long elapsed) {
            border(canvas, 40, 34, 1240, 552);
            text(canvas, "INSIGHT / HUD MODULE 2.3", 60, 69, 17, MUTED, false);
            line(canvas, 40, 88, 1240, 88, LINE);
            text(canvas, "ACCESSORY POWER OFF", 640, 270, 42, AMBER, true, Paint.Align.CENTER);
            text(canvas, "HUD STANDBY", 640, 325, 24, MUTED, false, Paint.Align.CENTER);
            int litBlocks = 40 - (int) (elapsed * 40 / SHUTDOWN_DURATION_MS);
            for (int index = 0; index < 40; index++) {
                paint.setColor(index < litBlocks ? AMBER : UNLIT);
                rectangle.set(65 + index * 29, 481, 85 + index * 29, 496);
                canvas.drawRect(rectangle, paint);
            }
        }

        private void drawStartup(Canvas canvas, long elapsed) {
            border(canvas, 40, 34, 1240, 552);
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
            border(canvas, 40, 34, 1240, 552);
            text(canvas, "INSIGHT", 60, 69, 24, AMBER, true);
            text(canvas, "HUD MODULE 2.3", 230, 68, 16, MUTED, false);
            text(canvas, demo ? "[ DEMO - SIMULATED ]" : "[ " + linkStatus + " ]",
                    1220, 68, 16, AMBER, false, Paint.Align.RIGHT);
            line(canvas, 40, 88, 1240, 88, LINE);
            text(canvas, "01  VEHICLE SPEED", 65, 127, 19, AMBER, false);
            String speed = wholeReading(displayed.speedMph, "---");
            float speedWidth = 105;
            number(canvas, speed, 65 + (3 - speed.length()) * speedWidth * 1.2f, 142, speedWidth);
            text(canvas, "MPH", 477, 332, 32, AMBER, false);
            drawRpmTrace(canvas, displayed.rpm);

            line(canvas, 620, 88, 620, 480, LINE);
            if (mapPreview) {
                drawMapPreview(canvas, now);
            } else {
                metric(canvas, "03  COOLANT TEMP", displayed.coolantF, "°F", 117, 100, 250);
                metric(canvas, "04  INTAKE AIR", displayed.intakeF, "°F", 211, 0, 150);
                metric(canvas, "05  OBD PORT VOLTAGE", displayed.voltage, "V", 305, 9, 16);
                metric(canvas, "06  ENGINE LOAD", displayed.load, "%", 399, 0, 100);
            }

            line(canvas, 40, 480, 1240, 480, LINE);
            text(canvas, "ZE1 / MODULE 2.3", 60, 510, 15, AMBER, false);
            String adapterName = preferences.getString("adapterName", "NO ADAPTER SELECTED");
            if (adapterName.length() > 28) adapterName = adapterName.substring(0, 28);
            text(canvas, "BT / " + adapterName.toUpperCase(Locale.US), 60, 536, 12, MUTED, false);
            String phoneState = (battery < 0 ? "--" : String.valueOf(battery)) + "%  /  "
                    + Math.round(batteryTemperature) + " C";
            text(canvas, "PHONE " + phoneState, 1220, 511, 14, MUTED, false, Paint.Align.RIGHT);
            text(canvas, powered ? "EXT POWER" : "BATTERY POWER", 1220, 536, 12, MUTED, false, Paint.Align.RIGHT);
        }

        private Path road(float... points) {
            Path road = new Path();
            road.moveTo(points[0], points[1]);
            for (int index = 2; index < points.length; index += 2) {
                road.lineTo(points[index], points[index + 1]);
            }
            return road;
        }

        private void drawMapPreview(Canvas canvas, long now) {
            text(canvas, "03  MOVING MAP", 650, 118, 19, AMBER, false);
            text(canvas, "SIMULATED", 1220, 118, 14, MUTED, false, Paint.Align.RIGHT);
            border(canvas, 650, 131, 1220, 448);
            canvas.save();
            canvas.clipRect(651, 132, 1219, 447);
            canvas.translate(650, 131);
            float progress = (now - mapPreviewStartedAt) % 24000 / 24000f;
            sampleDrive.getPosTan(progress * sampleDrive.getLength(), marker, direction);
            canvas.translate((285 - marker[0]) * 0.42f, (160 - marker[1]) * 0.42f);
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
            text(canvas, "ROUTE 08", 420, 76, 14, AMBER, false);
            text(canvas, "RIVER RD", 43, 179, 12, MUTED, false);
            text(canvas, "DEPOT AVE", 390, 306, 12, MUTED, false);
            canvas.translate(marker[0], marker[1]);
            canvas.rotate((float) Math.toDegrees(Math.atan2(direction[1], direction[0])));
            paint.setColor(0xFF080602);
            canvas.drawCircle(0, 0, 19, paint);
            path.reset();
            path.moveTo(16, 0);
            path.lineTo(-11, -10);
            path.lineTo(-6, 0);
            path.lineTo(-11, 10);
            path.close();
            paint.setColor(AMBER);
            canvas.drawPath(path, paint);
            canvas.restore();
            text(canvas, "DEMO ROADS / GPS INACTIVE", 650, 469, 12, MUTED, false);
            text(canvas, "N UP", 1220, 469, 12, MUTED, false, Paint.Align.RIGHT);
        }

        private void drawRpmTrace(Canvas canvas, Double rpm) {
            line(canvas, 65, 350, 590, 350, LINE);
            text(canvas, "02  ENGINE RPM", 65, 372, 15, AMBER, false);
            text(canvas, wholeReading(rpm, "----") + " RPM", 590, 372, 15, AMBER, true, Paint.Align.RIGHT);
            float chartLeft = 104;
            float chartRight = 590;
            float chartTop = 389;
            float chartBottom = 459;
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
                    dim ? "NIGHT  ON" : "NIGHT  OFF", "BLUETOOTH SETUP", mapPreview ? "SHOW GAUGES" : "SHOW MAP"};
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
            if (y < 570 || y > 620 || x < CONTROL_LEFT) return -1;
            int index = (int) ((x - CONTROL_LEFT) / CONTROL_STEP);
            if (index >= CONTROL_COUNT || x > CONTROL_LEFT + index * CONTROL_STEP + CONTROL_WIDTH) return -1;
            return index;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (shutdownStartedAt != 0) return true;
            if (parkedBlank) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    parkedBlank = false;
                    performClick();
                    invalidate();
                }
                return true;
            }
            if (startupStartedAt != 0 && SystemClock.uptimeMillis() - startupStartedAt < STARTUP_DURATION_MS) {
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
