package com.andrew.insighthud;

import android.content.Context;
import android.util.Log;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;

/** Uses the system tethering API on the HUD's Android 15 LineageOS build. */
final class HotspotPower {
    private static final String TAG = "HudHotspot";
    private static final int WIFI = 0;

    private HotspotPower() { }

    static void set(Context context, boolean on) {
        try {
            Object manager = context.getSystemService("tethering");
            if (manager == null) return;
            Class<?> type = manager.getClass();
            if (on) {
                Class<?> builderType = Class.forName("android.net.TetheringManager$TetheringRequest$Builder");
                Object builder = builderType.getConstructor(int.class).newInstance(WIFI);
                Object request = builderType.getMethod("build").invoke(builder);
                Class<?> requestType = Class.forName("android.net.TetheringManager$TetheringRequest");
                Class<?> callbackType = Class.forName("android.net.TetheringManager$StartTetheringCallback");
                Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                        (proxy, method, args) -> {
                            if (method.getName().equals("onTetheringFailed")) Log.w(TAG, "Start failed: " + args[0]);
                            if (method.getName().equals("onTetheringStarted")) Log.i(TAG, "Hotspot started");
                            return null;
                        });
                type.getMethod("startTethering", requestType, Executor.class, callbackType)
                        .invoke(manager, request, context.getMainExecutor(), callback);
            } else {
                type.getMethod("stopTethering", int.class).invoke(manager, WIFI);
                Log.i(TAG, "Hotspot stop requested");
            }
        } catch (ReflectiveOperationException | RuntimeException failed) {
            Log.w(TAG, "Unable to switch hotspot", failed);
        }
    }
}
