package com.andrew.insighthud;

import android.bluetooth.BluetoothDevice;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class KeyboardBattery {
    private final HudActivity host;

    KeyboardBattery(HudActivity host) {
        this.host = host;
    }

    void close() {
        gattQueue.clear();
        closeGattLink();
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
        if (host.bluetooth == null || !host.hasBluetoothPermission() || now - gattReadAt < 60_000
                || gattLink != null || !gattQueue.isEmpty()) return;
        gattReadAt = now;
        try {
            for (BluetoothDevice device : host.bluetooth.getBondedDevices()) {
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
        if (device == null || host.isDestroyed()) return;
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
                host.main.removeCallbacks(this);
                closeGattLink();
                readNextBatteryOverGatt();
            }
        };
        android.bluetooth.BluetoothGattCallback callback = new android.bluetooth.BluetoothGattCallback() {
            @Override public void onConnectionStateChange(android.bluetooth.BluetoothGatt gatt, int status, int state) {
                Log.i("HudKbd", tag + " GATT state=" + state + " status=" + status);
                if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) gatt.discoverServices();
                else host.main.post(finish);
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
                    host.main.post(() -> {
                        if (host.quickOpen) host.refreshQuick();
                        host.hud.invalidate();
                    });
                }
                gatt.disconnect();
            }
        };
        try {
            gattFinish = finish;
            gattLink = device.connectGatt(host.getApplicationContext(), false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gattLink == null) {
                finish.run();
                return;
            }
            host.main.postDelayed(finish, 12_000); // never hold the link open if the keyboard is asleep or out of range
        } catch (SecurityException refused) {
            finish.run();
        }
    }

    void closeGattLink() {
        if (gattFinish != null) host.main.removeCallbacks(gattFinish);
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

    List<String[]> peripheralRows() {
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
            if (host.bluetooth == null || !host.hasBluetoothPermission()) return levels;
            for (BluetoothDevice device : host.bluetooth.getBondedDevices()) {
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
}
