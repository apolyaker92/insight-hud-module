package com.andrew.insighthud;

import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.List;

final class QuickPanel {
    private final HudActivity host;

    QuickPanel(HudActivity host) {
        this.host = host;
    }

    private static final int F1_HOLD_MS = 600, QUICK_IDLE_MS = 12_000, QUICK_REFRESH_MS = 2000;
    boolean quickOpen;
    int quickRow;
    List<String[]> quickHealth = new ArrayList<>();
    String quickGasNote = "";
    private boolean f1Down, f1HoldFired;
    final Runnable f1Hold = () -> {
        f1HoldFired = true;
        if (quickOpen) closeQuick();
        else openQuick();
    };

    final Runnable quickIdle = this::closeQuick;
    private final Runnable quickTick = new Runnable() {
        @Override public void run() {
            if (!quickOpen) return;
            refreshQuick();
            host.hud.invalidate();
            host.main.postDelayed(this, QUICK_REFRESH_MS);
        }
    };

    boolean handleF1(KeyEvent event) {
        boolean usable = host.hasWindowFocus() && !host.parkedBlank && host.shutdownStartedAt == 0 && host.thermalLevel != host.THERMAL_HOLD
                && (host.startupStartedAt == 0 || host.startupClock(SystemClock.uptimeMillis()) >= host.STARTUP_DURATION_MS);
        if (!usable) return true;
        if (host.openPlaces != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) host.chooseSavedPlace();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                f1Down = true;
                f1HoldFired = false;
                host.main.removeCallbacks(f1Hold);
                host.main.postDelayed(f1Hold, F1_HOLD_MS);
            } else if (f1Down && !f1HoldFired) { // the keyboard's own auto-repeat means it is being held
                host.main.removeCallbacks(f1Hold);
                f1Hold.run();
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            host.main.removeCallbacks(f1Hold);
            if (f1Down && !f1HoldFired) {
                if (quickOpen) quickKey(KeyEvent.KEYCODE_F1);
                else host.activateControl(4);
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
        host.main.removeCallbacks(quickTick);
        host.main.postDelayed(quickTick, QUICK_REFRESH_MS);
        host.playSound(host.switchSoundId, 0.42f, SoundPolicy.Kind.UI);
        host.hud.invalidate();
    }

    void closeQuick() {
        if (!quickOpen) return;
        quickOpen = false;
        host.main.removeCallbacks(quickIdle);
        host.main.removeCallbacks(quickTick);
        host.playSound(host.switchSoundId, 0.3f, SoundPolicy.Kind.UI);
        host.hud.invalidate();
    }

    private void touchQuick() {
        host.main.removeCallbacks(quickIdle);
        host.main.postDelayed(quickIdle, QUICK_IDLE_MS);
    }

    void quickKey(int keyCode) {
        touchQuick();
        if (keyCode == KeyEvent.KEYCODE_F1) {
            quickRow = (quickRow + 1) % 3;
            host.playSound(host.switchSoundId, 0.3f, SoundPolicy.Kind.UI);
        } else if (keyCode == KeyEvent.KEYCODE_F3) {
            closeQuick();
            return;
        } else if (keyCode == KeyEvent.KEYCODE_F4 || quickRow == 0) {
            host.activateControl(2);
        } else if (quickRow == 2) {
            host.playSound(host.switchSoundId, 0.42f, SoundPolicy.Kind.UI);
            host.routeToNearestGas();
        } else {
            host.preferences.edit().putInt("palette", (host.preferences.getInt("palette", 0) + 1) % host.PALETTE_NAMES.length).apply();
            host.hud.applyPalette();
            host.playSound(host.switchSoundId, 0.42f, SoundPolicy.Kind.UI);
        }
        host.hud.invalidate();
    }

    void refreshQuick() {
        List<String[]> rows = new ArrayList<>();
        boolean low = host.battery >= 0 && host.battery < 20 && !host.powered;
        rows.add(new String[]{"PHONE BATTERY", host.battery < 0 ? "--" : host.battery + "%" + (host.powered ? "  CHARGING" : ""),
                low ? "warn" : ""});
        float c = host.batteryTemperature;
        String[] status = {"", "  WARM", "  HOT", "  HOT", "  CRITICAL", "  CRITICAL", "  CRITICAL"};
        int level = Math.max(0, Math.min(status.length - 1, host.systemThermalStatus));
        rows.add(new String[]{"PHONE TEMP", Math.round(c) + " C / " + Math.round(c * 9 / 5 + 32) + " F" + status[level],
                c >= 40 || host.systemThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ? "warn" : ""});
        rows.addAll(host.keyboards.peripheralRows());
        quickHealth = rows;
    }
}
