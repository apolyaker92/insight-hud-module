package com.andrew.insighthud;

/**
 * Maps ambient light to a screen brightness. The curve is deliberately steep at the top: a HUD
 * reflected off a windshield needs full brightness in daylight and a very low floor at night.
 * Tune the two tables below in the car; everything else follows from them.
 */
final class AutoBrightness {
    /** log10(lux) breakpoints: 1, 10, 100, 1 000, 10 000 and about 31 600 lux. */
    private static final float[] LOG_LUX = {0f, 1f, 2f, 3f, 4f, 4.5f};
    /** Window brightness (0..1) at each breakpoint. */
    private static final float[] LEVEL = {0.04f, 0.08f, 0.20f, 0.45f, 0.85f, 1.00f};
    /** Weight of each new sensor sample; lower is steadier but slower to react. */
    private static final float SMOOTHING = 0.2f;

    private float smoothedLogLux = Float.NaN;

    static float levelFor(float lux) {
        float position = (float) Math.log10(Math.max(1f, lux));
        if (position <= LOG_LUX[0]) return LEVEL[0];
        for (int index = 1; index < LOG_LUX.length; index++) {
            if (position <= LOG_LUX[index]) {
                float span = LOG_LUX[index] - LOG_LUX[index - 1];
                float fraction = (position - LOG_LUX[index - 1]) / span;
                return LEVEL[index - 1] + (LEVEL[index] - LEVEL[index - 1]) * fraction;
            }
        }
        return LEVEL[LEVEL.length - 1];
    }

    /** Feeds one sensor reading and returns the smoothed brightness; the first reading is taken as-is. */
    float update(float lux) {
        float position = (float) Math.log10(Math.max(1f, lux));
        smoothedLogLux = Float.isNaN(smoothedLogLux)
                ? position : smoothedLogLux + SMOOTHING * (position - smoothedLogLux);
        return levelFor((float) Math.pow(10, smoothedLogLux));
    }

    void reset() {
        smoothedLogLux = Float.NaN;
    }
}
