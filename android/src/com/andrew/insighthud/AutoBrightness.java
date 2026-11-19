package com.andrew.insighthud;

final class AutoBrightness {
    private static final float[] LOG_LUX = {0f, 1f, 2f, 3f, 4f, 4.5f};
    private static final float[] LEVEL = {0.04f, 0.08f, 0.20f, 0.45f, 0.85f, 1.00f};
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
