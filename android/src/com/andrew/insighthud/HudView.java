package com.andrew.insighthud;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.location.Location;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.DisplayCutout;
import android.view.WindowInsets;
import android.view.View;
import java.util.Date;
import java.util.Locale;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import org.json.JSONArray;
import org.json.JSONObject;

final class HudView extends View {
    private final HudActivity host;
    private int ACCENT, MUTED, LINE, UNLIT, BACKGROUND;

    private int ground(int alpha) {
        return (alpha << 24) | (BACKGROUND & 0xFFFFFF);
    }

    void applyPalette() {
        int palette = host.preferences.getInt("palette", 0);
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
    private float mapLeft() { return host.fullMapActive() ? MAP_LEFT_FULL : MAP_LEFT_SPLIT; }
    private float mapTop() { return host.fullMapActive() ? 94 : 131; }
    private static final float MAP_RIGHT = 1220;
    private float mapBottom() { return (host.fullMapActive() ? 514 : 448) + extra(); }
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
    private float rangeZoom;
    private float rangeFrom;
    private float rangeTo;
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

    HudView(HudActivity host) {
        super(host);
        this.host = host;
        rangeZoom = host.RANGE_ZOOM[host.mapRange];
        rangeFrom = rangeZoom;
        rangeTo = rangeZoom;
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
        if (host.parkedBlank || host.thermalBlank) {
            canvas.drawColor(Color.BLACK);
            return;
        }
        canvas.drawColor(BACKGROUND);
        fitFrame();
        canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);
        canvas.save();
        if (host.mirror) canvas.scale(-1, 1, 640, 280);
        long now = SystemClock.uptimeMillis();
        long startupElapsed = host.startupStartedAt == 0 ? now : host.startupClock(now);
        boolean starting = host.startupStartedAt != 0 && startupElapsed < host.STARTUP_DURATION_MS;
        boolean shuttingDown = host.shutdownStartedAt != 0;
        boolean thermalHold = host.thermalLevel == host.THERMAL_HOLD;
        if (thermalHold) drawThermalWarning(canvas, now);
        else if (shuttingDown) drawShutdown(canvas, now - host.shutdownStartedAt);
        else if (starting) drawStartup(canvas, startupElapsed);
        else {
            drawGauges(canvas, now);
            if (host.parkedRoute != null && host.accessoryActive && host.powered && !host.startupPending && hasWindowFocus()) {
                if (host.resumeRouteDeadline == 0) {
                    host.resumeRouteDeadline = now + host.RESUME_ROUTE_TIMEOUT_MS;
                    host.main.postAtTime(host.resumeRouteTimeout, host.resumeRouteDeadline);
                }
                drawResumeRoute(canvas, now);
                postInvalidateDelayed(100);
            } else if (host.cancelPrompt && host.nav != null) drawCancelRoute(canvas, now);
            else if (host.openPlaces != null) drawSavedPlaces(canvas);
        }
        if (host.quick.quickOpen && !thermalHold && !shuttingDown && !starting) drawQuickConfig(canvas);
        boolean alertHeld = thermalHold || shuttingDown || starting || host.startupPending;
        Alerts.Alert alert = host.alerts.tick(now, alertHeld);
        if (alert != null) {
            if (host.alerts.justStarted() && !alert.silent) host.playAlertSound(alert.level);
            drawAlert(canvas, alert, now);
        }
        canvas.restore();
        canvas.restore();
        if (host.alerts.busy() && isShown()) postInvalidateDelayed(60);
        if ((thermalHold || starting || shuttingDown
                || (host.thermalLevel == host.THERMAL_LIMIT && now - host.thermalStartedAt < host.THERMAL_WARNING_MS))
                && isShown()) postInvalidateDelayed(starting ? 33 : 80);
        else if (host.thermalLevel == host.THERMAL_NORMAL && host.mapPreview && host.foreground && hasWindowFocus()) {
            if (now - cameraStartedAt < cameraDuration || now - rangeStartedAt < 500
                    || (lockStartedAt != 0 && now - lockStartedAt < TARGET_CAPTION_MS)) postInvalidateDelayed(32);
            else if (host.estimator.moving(SystemClock.elapsedRealtime())) postInvalidateDelayed(50);
            else if (host.demo && host.powered) postInvalidateDelayed(100);
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
        float fraction = Math.max(0, host.resumeRouteDeadline - now) / (float) host.RESUME_ROUTE_TIMEOUT_MS;
        canvas.drawRect(PLACES_LEFT, 173, PLACES_LEFT + (PLACES_RIGHT - PLACES_LEFT) * fraction, 177, paint);
        text(canvas, "CONTINUE NAVIGATION?", 640, 234, 32, ACCENT, true, Paint.Align.CENTER);
        String name = host.parkedRoute.name.toUpperCase(Locale.US);
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
        float fraction = Math.max(0, Math.min(1, (host.cancelPromptDeadline - now) / (float) host.CANCEL_PROMPT_MS));
        canvas.drawRect(PLACES_LEFT, 173, PLACES_LEFT + (PLACES_RIGHT - PLACES_LEFT) * fraction, 177, paint);
        postInvalidateDelayed(100);
        text(canvas, "CANCEL NAVIGATION?", 640, 234, 32, ACCENT, true, Paint.Align.CENTER);
        String name = host.nav.name.isEmpty() ? "DESTINATION" : host.nav.name.toUpperCase(Locale.US);
        if (name.length() > 36) name = name.substring(0, 35) + "…";
        text(canvas, name, 640, 275, 22, MUTED, false, Paint.Align.CENTER);
        text(canvas, "[F1] CANCEL ROUTE", 457, 340, 23, ACCENT, true, Paint.Align.CENTER);
        text(canvas, "[F4] KEEP", 823, 340, 23, MUTED, true, Paint.Align.CENTER);
    }

    private int firstVisiblePlace() {
        return Math.max(0, Math.min(host.selectedPlace - 1, host.openPlaces.length() - VISIBLE_PLACES));
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
        text(canvas, (host.selectedPlace + 1) + " / " + host.openPlaces.length(), 939, 173,
                21, ACCENT, true, Paint.Align.RIGHT);
        paint.setColor(LINE);
        canvas.drawRect(339, 186, 941, 188, paint);

        int first = firstVisiblePlace();
        int end = Math.min(first + VISIBLE_PLACES, host.openPlaces.length());
        for (int i = first; i < end; i++) {
            JSONObject place = host.openPlaces.optJSONObject(i);
            if (place == null) continue;
            float y = PLACES_ROWS + (i - first) * PLACE_HEIGHT;
            boolean selected = i == host.selectedPlace;
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
        if (host.mirror) x = 1280 - x;
        if (x < PLACES_LEFT || x > PLACES_RIGHT || y < PLACES_TOP || y > PLACES_BOTTOM) {
            host.openPlaces = null;
        } else if (y >= PLACES_FOOTER) {
            if (x > 782) host.openPlaces = null;
            else if (x >= 500) host.chooseSavedPlace();
            else host.selectedPlace = (host.selectedPlace + 1) % host.openPlaces.length();
        } else if (y >= PLACES_ROWS) {
            int row = (int) ((y - PLACES_ROWS) / PLACE_HEIGHT);
            int index = firstVisiblePlace() + row;
            if (row < VISIBLE_PLACES && index < host.openPlaces.length()) host.selectedPlace = index;
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
        String[] values = {host.BRIGHTNESS_NAMES[host.brightnessMode],
                host.PALETTE_NAMES[Math.max(0, Math.min(host.PALETTE_NAMES.length - 1, host.preferences.getInt("palette", 0)))],
                host.quick.quickGasNote.isEmpty() ? "GO" : host.quick.quickGasNote};
        for (int i = 0; i < 3; i++) {
            float top = 118 + i * 124;
            boolean selected = host.quick.quickRow == i;
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
        for (String[] row : host.quick.quickHealth) {
            if (y > bottom - 90) break;
            text(canvas, row[0], 680, y, 20, MUTED, false);
            text(canvas, row[1], 680, y + 44, 38, row[2].isEmpty() ? ACCENT : 0xFFFF8A45, true);
            y += 86;
        }
        text(canvas, host.quick.quickRow == 2 ? "F1 NEXT     F2 GO     F3 CLOSE" : "F1 NEXT     F2 CHANGE     F3 CLOSE",
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
        long elapsed = now - host.thermalStartedAt;
        int color = elapsed % 1200 < 700 ? 0xFFFF8A45 : ACCENT;
        text(canvas, "OVERHEATING", 640, 254, 61, color, true, Paint.Align.CENTER);
        text(canvas, "BATTERY " + Math.round(host.batteryTemperature) + " °C", 640, 307,
                25, ACCENT, false, Paint.Align.CENTER);
        text(canvas, "GPS AND OBD PAUSED", 640, 358, 18, MUTED, false, Paint.Align.CENTER);
        drawThermalTicker(canvas, 330, 950, 422, elapsed, "COOL PHONE BEFORE USE   /   HUD ENTERING STANDBY");
    }

    private void drawThermalLimit(Canvas canvas, long now) {
        text(canvas, "03  THERMAL LIMIT", mapLeft(), 118, 19, ACCENT, false);
        border(canvas, mapLeft(), mapTop(), MAP_RIGHT, mapBottom());
        text(canvas, "PHONE RUNNING HOT", mapCenterX(), 235, 27, ACCENT, true, Paint.Align.CENTER);
        text(canvas, "BATTERY " + Math.round(host.batteryTemperature) + " °C", mapCenterX(), 283,
                20, ACCENT, false, Paint.Align.CENTER);
        drawThermalTicker(canvas, 680, 1190, 351, now - host.thermalStartedAt, "GPS PAUSED   /   SCREEN DIMMED");
    }

    private void drawThermalTicker(Canvas canvas, float left, float right, float y, long elapsed, String message) {
        line(canvas, left, y - 27, right, y - 27, LINE);
        line(canvas, left, y + 12, right, y + 12, LINE);
        canvas.save();
        canvas.clipRect(left + 2, y - 26, right - 2, y + 11);
        if (elapsed < host.THERMAL_WARNING_MS) {
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
        if (host.shutdownReport == null) {
            text(canvas, "ACCESSORY POWER OFF", 640, 270, 42, ACCENT, true, Paint.Align.CENTER);
            text(canvas, "HUD STANDBY", 640, 325, 24, MUTED, false, Paint.Align.CENTER);
        } else {
            text(canvas, host.shutdownReport.optLong("ended") == 0 ? "TRIP REPORT / DEMO" : "TRIP REPORT / SAVED",
                    640, 171, 35, ACCENT, true, Paint.Align.CENTER);
            reportMetric(canvas, 210, "ELAPSED", TripStats.time(host.shutdownReport));
            reportMetric(canvas, 495, "EST. MILES", TripStats.value(host.shutdownReport, "miles", "%.1f"));
            reportMetric(canvas, 785, "PEAK RPM", TripStats.value(host.shutdownReport, "rpm", "%.0f"));
            reportMetric(canvas, 1070, "PEAK COOLANT", TripStats.value(host.shutdownReport, "coolant", "%.0f") + " °F");
            text(canvas, "ACCESSORY POWER OFF / HUD STANDBY", 640, 415, 16, MUTED, false, Paint.Align.CENTER);
        }
        int litBlocks = 40 - (int) (elapsed * 40 / host.shutdownDuration());
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
                host.powered ? "POWER ................. EXTERNAL" : "POWER ................. BATTERY",
                "OBD LINK .............. " + host.linkStatus
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
        int litBlocks = Math.min(40, (int) (elapsed * 40 / host.STARTUP_DURATION_MS) + 1);
        for (int index = 0; index < 40; index++) {
            paint.setColor(index < litBlocks ? ACCENT : UNLIT);
            rectangle.set(65 + index * 29, 481, 85 + index * 29, 496);
            canvas.drawRect(rectangle, paint);
        }
        text(canvas, "ZE1 / 2001    DISPLAY INITIALIZATION", 65, 530, 13, MUTED, false);
        text(canvas, "TAP TO SKIP", 1210, 530, 13, MUTED, false, Paint.Align.RIGHT);
    }

    private void drawGauges(Canvas canvas, long now) {
        ObdBluetooth.Readings displayed = host.demo ? host.DEMO_READINGS : host.readings;
        if (displayed == null) displayed = host.EMPTY_READINGS;
        border(canvas, 40, 34, 1240, 552 + extra());
        text(canvas, "INSIGHT", 60, 69, 24, ACCENT, true);
        text(canvas, "HUD MODULE 2.3", 230, 68, 16, MUTED, false);
        text(canvas, clockText(), 640, 71, 30, ACCENT, true, Paint.Align.CENTER);
        String heading = headingText();
        if (heading != null) text(canvas, heading, 730, 69, 20, MUTED, false);
        text(canvas, host.demo ? "[ DEMO - SIMULATED ]" : "[ " + host.linkStatus + " ]",
                1115, 68, 16, ACCENT, false, Paint.Align.RIGHT);
        border(canvas, 1134, 46, 1220, 77);
        text(canvas, "BT SETUP", 1177, 67, 13, MUTED, false, Paint.Align.CENTER);
        line(canvas, 40, 88, 1240, 88, LINE);
        boolean full = host.fullMapActive();
        if (!full) text(canvas, "01  VEHICLE SPEED", 65, 127, 19, ACCENT, false);
        boolean speedFromGps = false;
        if (!host.demo && displayed.speedMph == null) {
            Double fromGps = host.gpsSpeedMph();
            if (fromGps != null) {
                displayed = displayed.withSpeed(fromGps);
                speedFromGps = true;
            }
        }
        String speed = host.wholeReading(displayed.speedMph, "---");
        if (!full) {
            float speedWidth = 105;
            number(canvas, speed, 65 + (3 - speed.length()) * speedWidth * 1.2f, 142, speedWidth);
            text(canvas, "MPH", 477, 332, 32, ACCENT, false);
            if (speedFromGps) text(canvas, "GPS", 477, 358, 14, MUTED, false);
            drawWarmup(canvas, displayed.coolantF);
            drawRpmTrace(canvas, displayed.rpm);
            OfflineRoadMap.RoadInfo info = host.shownRoadInfo();
            if (info != null) {
                text(canvas, roadLine(info, 24), 522, 127, 14, MUTED, false, Paint.Align.RIGHT);
                drawLimitBadge(canvas, 452, 168, 90, 84, info.limitMph, displayed.speedMph);
            }
        }

        float footer = (full ? 520 : 480) + extra();
        if (!full) line(canvas, 620, 88, 620, footer, LINE);
        if (host.thermalLevel == host.THERMAL_LIMIT) {
            drawThermalLimit(canvas, now);
        } else if (host.mapPreview && host.demo) {
            drawMapPreview(canvas, now);
        } else if (host.mapPreview) {
            drawOfflineMap(canvas, now);
        } else {
            drawMetrics(canvas, displayed);
        }
        if (host.mapPreview && !host.demo && host.thermalLevel == host.THERMAL_NORMAL && host.nav != null) drawNavStrip(canvas);
        if (full) {
            if (host.mapReadout != 2) drawMapReadouts(canvas, displayed, speed, speedFromGps, host.mapReadout == 1);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ground(0xF0));
            canvas.drawRect(MAP_RANGE_LEFT - 3, 95, MAP_RIGHT + 1, 125, paint);
            drawMapMode(canvas);
        }

        line(canvas, 40, footer, 1240, footer, LINE);
        String adapterName = host.preferences.getString("adapterName", "NO ADAPTER SELECTED");
        if (adapterName.length() > 28) adapterName = adapterName.substring(0, 28);
        String phoneState = (host.battery < 0 ? "--" : String.valueOf(host.battery)) + "%  /  "
                + Math.round(host.batteryTemperature) + " C";
        String brightText = "BRIGHT " + host.BRIGHTNESS_NAMES[host.brightnessMode] + (host.brightnessMode == 0
                ? "  " + Math.round(host.autoLevel * 100) + "%" : "")
                + "    SND " + host.sounds.label(host.ambientDark());
        if (full) {
            text(canvas, "BT / " + adapterName.toUpperCase(Locale.US), 60, footer + 25, 12, MUTED, false);
            text(canvas, brightText, 640, footer + 25, 12, MUTED, false, Paint.Align.CENTER);
            text(canvas, "PHONE " + phoneState + "   " + (host.powered ? "EXT POWER" : "BATTERY POWER"),
                    1220, footer + 25, 12, MUTED, false, Paint.Align.RIGHT);
        } else {
            OfflineRoadMap.RoadInfo fuelInfo = host.shownRoadInfo();
            if (fuelInfo != null && fuelInfo.fuelName != null) {
                text(canvas, fuelLine(fuelInfo), 640, footer + 30, 14, MUTED, false, Paint.Align.CENTER);
            }
            String ahead = host.aheadLine(host.shownRoadInfo());
            String guidance = !host.mapPreview ? navFooter() : null;
            if (ahead != null) text(canvas, ahead, 60, footer + 32, 20, ACCENT, true);
            else if (guidance != null) text(canvas, guidance, 60, footer + 32, 20, ACCENT, true);
            else text(canvas, "ZE1 / MODULE 2.3", 60, footer + 30, 15, ACCENT, false);
            text(canvas, brightText, 640, footer + 56, 12, MUTED, false, Paint.Align.CENTER);
            text(canvas, "BT / " + adapterName.toUpperCase(Locale.US), 60, footer + 56, 12, MUTED, false);
            text(canvas, "PHONE " + phoneState, 1220, footer + 31, 14, MUTED, false, Paint.Align.RIGHT);
            text(canvas, host.powered ? "EXT POWER" : "BATTERY POWER", 1220, footer + 56, 12, MUTED, false,
                    Paint.Align.RIGHT);
        }
    }

    private void drawMetrics(Canvas canvas, ObdBluetooth.Readings reading) {
        boolean anySeen = host.seenGauge[0] || host.seenGauge[1] || host.seenGauge[2] || host.seenGauge[3];
        int slot = 0;
        for (int id = 0; id < 4 && slot < 3; id++) {
            boolean show = host.demo || (anySeen ? host.seenGauge[id] : id < 3);
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
        text(canvas, host.wholeReading(coolant, "--") + "°F", 573, 331, 14, ACCENT, false, Paint.Align.CENTER);
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
        if (host.fullMapActive()) text(canvas, note, MAP_RIGHT - 10, mapBottom() - 10, 12, color, false, Paint.Align.RIGHT);
        else text(canvas, note, mapLeft(), 469 + extra(), 12, color, false);
    }

    private void drawMapMode(Canvas canvas) {
        border(canvas, MAP_RANGE_LEFT, 96, MAP_MODE_LEFT - 8, 124);
        text(canvas, host.RANGE_NAMES[host.mapRange], (MAP_RANGE_LEFT + MAP_MODE_LEFT - 8) / 2,
                115, 13, ACCENT, false, Paint.Align.CENTER);
        border(canvas, MAP_MODE_LEFT, 96, MAP_RIGHT, 124);
        text(canvas, host.headingUp ? "HEADING UP" : "NORTH UP", (MAP_MODE_LEFT + MAP_RIGHT) / 2,
                115, 13, ACCENT, false, Paint.Align.CENTER);
    }

    private float angleDelta(float from, float to) {
        return ((to - from) % 360 + 540) % 360 - 180;
    }

    private void updateMapCamera(long now, float course) {
        float rangeProgress = Math.min(1, (now - rangeStartedAt) / 500f);
        float rangeEase = rangeProgress * rangeProgress * (3 - 2 * rangeProgress);
        rangeZoom = rangeFrom + (rangeTo - rangeFrom) * rangeEase;
        if (rangeTo != host.RANGE_ZOOM[host.mapRange]) {
            rangeFrom = rangeZoom;
            rangeTo = host.RANGE_ZOOM[host.mapRange];
            rangeStartedAt = now;
        }
        sampleMapCamera(now);
        boolean modeChanged = cameraMode != host.headingUp;
        if (!modeChanged && cameraDuration == MAP_TRANSITION_MS
                && now - cameraStartedAt < MAP_TRANSITION_MS) return;
        boolean follow = host.headingUp && !Float.isNaN(course);
        float targetAngle = follow ? -course : 0;
        float targetLead = follow ? 1 : 0;
        if (!modeChanged && Math.abs(angleDelta(cameraTargetAngle, targetAngle)) < 1
                && cameraTargetLead == targetLead) return;
        cameraMode = host.headingUp;
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
        if (!host.fullMapActive()) {
            text(canvas, "03  MOVING MAP", mapLeft(), 118, 19, ACCENT, false);
            text(canvas, "SIMULATED", MAP_RANGE_LEFT - 12, 118, 14, MUTED, false, Paint.Align.RIGHT);
            drawMapMode(canvas);
        }
        border(canvas, mapLeft(), mapTop(), MAP_RIGHT, mapBottom());
        float progress = (now - host.mapPreviewStartedAt) % 24000 / 24000f;
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
        OfflineRoadMap.Region region = host.roadMap.region();
        if (!host.fullMapActive()) text(canvas, "03  OFFLINE MAP", mapLeft(), 118, 19, ACCENT, false);
        if (!host.fullMapActive()) {
            text(canvas, region == null ? "ALBANY REGION" : region.name,
                    MAP_RANGE_LEFT - 12, 118, 11, MUTED, false, Paint.Align.RIGHT);
            drawMapMode(canvas);
        }
        border(canvas, mapLeft(), mapTop(), MAP_RIGHT, mapBottom());
                    String status = mapStatus(region);
        if (host.headingUp && "GPS FIX".equals(status) && Float.isNaN(host.gpsCourse)) status = "GPS FIX / WAITING FOR COURSE";
        mapNote(canvas, status, ACCENT);
        if (region == null) {
            text(canvas, host.roadMap.failed() ? "MAP DATA UNAVAILABLE" : "LOADING LOCAL ROADS",
                    mapCenterX(), mapCenterY(), 19, MUTED, false, Paint.Align.CENTER);
            return;
        }
        boolean tracking = host.fixInRegion(region);
        float course = tracking ? host.gpsCourse : Float.NaN;
        updateMapCamera(now, course);
        Location position = host.mapPosition(region);
        double viewX = OfflineRoadMap.tileX(position == null ? region.longitude : position.getLongitude());
        double viewY = OfflineRoadMap.tileY(position == null ? region.latitude : position.getLatitude());
        OfflineRoadMap.Scene scene = host.roadMap.scene();
        if (scene == null || !scene.covers(viewX, viewY)) {
            text(canvas, host.roadMap.failed() ? "MAP DATA UNAVAILABLE" : "LOADING LOCAL ROADS",
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
        if (host.nav != null) drawRouteOverlay(canvas, scene, strokeScale);
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
                host.gpsFix.getLatitude(), host.gpsFix.getLongitude());
        canvas.restore();
        if (host.gpsUnavailable) drawSatelliteWarning(canvas);
    }

    private void drawMapReadouts(Canvas canvas, ObdBluetooth.Readings displayed, String speed, boolean fromGps,
                                 boolean compact) {
        OfflineRoadMap.RoadInfo info = host.shownRoadInfo();
        String ahead = host.aheadLine(info);
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
            text(canvas, host.wholeReading(displayed.rpm, "----") + " RPM", plateLeft + 210, base - 30, 18, MUTED, false);
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
        String pattern = android.text.format.DateFormat.is24HourFormat(host) ? "HH:mm" : "h:mm a";
        return new SimpleDateFormat(pattern, Locale.US).format(new Date());
    }

    private String headingText() {
        if (host.demo) return "NE 045\u00b0";
        if (host.gpsFix == null || Float.isNaN(host.gpsCourse)) return null;
        return RoadLookup.compass(host.gpsCourse) + String.format(Locale.US, " %03d\u00b0", Math.round(host.gpsCourse) % 360);
    }

    private String markerSource;
    private JSONArray markers = new JSONArray();

    private void drawSavedPlaceMarkers(Canvas canvas, OfflineRoadMap.Scene scene) {
        String source = host.preferences.getString("savedPlaces", "[]");
        if (!source.equals(markerSource)) {
            markers = host.savedPlaces();
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
        double along = host.nav.alongMeters();
        if (scene.x != routeSceneX || scene.y != routeSceneY || host.navVersion != routeDrawVersion
                || along != routeDrawAlong) {
            routeSceneX = scene.x;
            routeSceneY = scene.y;
            routeDrawVersion = host.navVersion;
            routeDrawAlong = along;
            routePath.reset();
            remainingRoutePath.reset();
            for (int i = 1; i < host.navTileX.length; i++) {
                double dx = host.navTileX[i] - scene.x, dy = host.navTileY[i] - scene.y;
                double px = host.navTileX[i - 1] - scene.x, py = host.navTileY[i - 1] - scene.y;
                if (Math.max(px, dx) < -4 || Math.min(px, dx) > 4
                        || Math.max(py, dy) < -4 || Math.min(py, dy) > 4) continue;
                routePath.moveTo((float) px, (float) py);
                routePath.lineTo((float) dx, (float) dy);
                double end = host.nav.metersAtPoint(i), start = host.nav.metersAtPoint(i - 1);
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
        int last = host.navTileX.length - 1;
        float dx = (float) (host.navTileX[last] - scene.x), dy = (float) (host.navTileY[last] - scene.y);
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
        RouteTrack route = host.nav;
        if (route == null) return null;
        RouteTrack.Step next = route.nextStep();
        String what = next == null ? "ARRIVE" : next.label();
        if (what.length() > 30) what = what.substring(0, 30);
        return navDistance(route.metersToNext(host.navAhead())) + "  " + what;
    }

    private void drawNavStrip(Canvas canvas) {
        RouteTrack route = host.nav;
        float left = mapLeft() + 8, top = mapTop() + 8;
        RouteTrack.Step following = route.stepAfterNext();
        boolean soon = following != null && route.metersBetweenNextAndAfter() <= host.THEN_METERS
                && route.nextStep() != null && following.kind != RouteTrack.ARRIVE;
        float width = host.fullMapActive() ? 540 : 440, height = soon ? 132 : 108;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ground(0xF5));
        canvas.drawRect(left, top, left + width, top + height, paint);
        border(canvas, left, top, left + width, top + height);
        RouteTrack.Step next = route.nextStep();
        drawTurnArrow(canvas, left + 56, top + 56, next == null ? RouteTrack.ARRIVE : next.kind);
        double ahead = host.navAhead();
        text(canvas, navDistance(route.metersToNext(ahead)), left + 112, top + 50, 42, ACCENT, true);
        String what = next == null ? "ARRIVE" + (route.name.isEmpty() ? "" : " AT " + route.name.toUpperCase(Locale.US))
                : next.label();
        int room = (int) ((width - 124) / 10);
        text(canvas, what.length() > room ? what.substring(0, room) : what, left + 112, top + 76, 17, MUTED, false);
        double seconds = route.remainingSeconds();
        String left2 = String.format(Locale.US, "%.1f MI", Math.max(0, route.remainingMeters() - ahead) / 1609.344);
        if (!Double.isNaN(seconds)) {
            String pattern = android.text.format.DateFormat.is24HourFormat(host) ? "HH:mm" : "h:mm a";
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
        if (!host.hasPreciseLocation()) return "TAP MAP FOR GPS";
        if (!host.gpsEnabled()) return "TAP MAP TO ENABLE GPS";
        if (!host.accessoryActive && !host.gpsTestActive) return "TAP MAP FOR GPS TEST";
        if (host.gpsUnavailable) return host.gpsTestActive ? "TEST / TAP TO STOP" : "";
        String status = host.gpsFix == null ? "ACQUIRING GPS"
                : region != null && !host.fixInRegion(region) ? "OUTSIDE MAP AREA" : "GPS FIX";
        return host.gpsTestActive ? "TEST / " + status + " / TAP TO STOP" : status;
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
        text(canvas, host.demo ? "TARGET ZE1 / SIM" : "TARGET ZE1 / LOCK",
                left + 8, top + 17, 13, alpha | (ACCENT & 0xFFFFFF), true);
        String coordinates = String.format(Locale.US, "%.4f%s  %.4f%s", Math.abs(latitude),
                latitude >= 0 ? "N" : "S", Math.abs(longitude), longitude >= 0 ? "E" : "W");
        text(canvas, coordinates, left + 8, top + 34, 12, alpha | (ACCENT & 0xFFFFFF), false);
    }

    private void drawRpmTrace(Canvas canvas, Double rpm) {
        line(canvas, 65, 350, 590, 350, LINE);
        text(canvas, "02  ENGINE RPM", 65, 372, 15, ACCENT, false);
        text(canvas, host.wholeReading(rpm, "----") + " RPM", 590, 372, 15, ACCENT, true, Paint.Align.RIGHT);
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

        int count = host.demo ? host.DEMO_RPM.length : host.rpmCount;
        float spacing = (chartRight - chartLeft) / (host.rpmHistory.length - 1);
        path.reset();
        boolean previousValid = false;
        for (int index = 0; index < count; index++) {
            float sample = host.demo ? host.DEMO_RPM[index]
                    : host.rpmHistory[(host.rpmNext + host.rpmHistory.length - count + index) % host.rpmHistory.length];
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
                : host.wholeReading(value, "--");
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
        float mapX = host.mirror ? 1280 - x : x;
        if (mapX >= 1126 && mapX <= 1228 && y >= 39 && y <= 85) return 8;
        if (host.thermalLevel == host.THERMAL_NORMAL && host.mapPreview && mapX >= MAP_RANGE_LEFT && mapX <= MAP_MODE_LEFT - 8
                && y >= 88 && y < (host.fullMapActive() ? 126 : 131)) return 7;
        if (host.thermalLevel == host.THERMAL_NORMAL && host.mapPreview && mapX >= MAP_MODE_LEFT - 7 && mapX <= MAP_RIGHT + 4
                && y >= 88 && y < (host.fullMapActive() ? 126 : 131)) return 6;
        if (host.thermalLevel == host.THERMAL_NORMAL && host.mapPreview && !host.demo && mapX >= mapLeft() && mapX <= MAP_RIGHT
                && y >= mapTop() && y <= (host.fullMapActive() ? 520 : 480) + extra()
                && (!host.hasPreciseLocation() || !host.gpsEnabled() || (!host.powered && !host.accessoryActive))) return 5;
        if (host.thermalLevel == host.THERMAL_NORMAL && host.mapPreview && mapX >= mapLeft() && mapX <= MAP_RIGHT
                && y >= mapTop() && y <= (host.fullMapActive() ? 520 : 480) + extra()) return 9;
        if (mapX >= 40 && mapX <= 1240 && y >= 88 && y <= 480 + extra()) return 4;
        return -1;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (host.shutdownStartedAt != 0 || host.thermalLevel == host.THERMAL_HOLD) return true;
        if (host.parkedBlank) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                host.parkedBlank = false;
                host.enterStandbyPower(false);
                performClick();
                invalidate();
            }
            return true;
        }
        if (host.startupStartedAt != 0 && host.startupClock(SystemClock.uptimeMillis()) < host.STARTUP_DURATION_MS) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                host.startupStartedAt = 0;
                host.releaseStartupSound();
                performClick();
                invalidate();
            }
            return true;
        }
        if (host.resumeRouteDeadline != 0) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float x = (event.getX() - left) / scale;
                float y = (event.getY() - top) / scale;
                if (host.mirror) x = 1280 - x;
                if (x >= PLACES_LEFT && x <= PLACES_RIGHT && y >= 300 && y <= 381) {
                    host.answerResumeRoute(x < 640);
                    performClick();
                }
            }
            return true;
        }
        if (host.cancelPrompt) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float x = (event.getX() - left) / scale;
                float y = (event.getY() - top) / scale;
                if (host.mirror) x = 1280 - x;
                if (x >= PLACES_LEFT && x <= PLACES_RIGHT && y >= 300 && y <= 381) {
                    if (x < 640) host.cancelRoute();
                    else host.dismissCancelPrompt();
                } else {
                    host.dismissCancelPrompt();
                }
                performClick();
            }
            return true;
        }
        if (host.openPlaces != null) {
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
                    host.activateControl(pressedControl);
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
