package com.andrew.insighthud;

final class SpeedSource {
    static final long GPS_MAX_AGE_MS = 3000;
    private static final double MPH_PER_METRE_PER_SECOND = 2.23694;
    private static final double STOPPED_MPH = 1.0;

    private SpeedSource() { }

    static Double gpsMph(boolean hasSpeed, float metresPerSecond, long ageMs) {
        if (!hasSpeed || ageMs < 0 || ageMs > GPS_MAX_AGE_MS || Float.isNaN(metresPerSecond) || metresPerSecond < 0) {
            return null;
        }
        double mph = metresPerSecond * MPH_PER_METRE_PER_SECOND;
        return mph < STOPPED_MPH ? 0.0 : mph;
    }

    static Double choose(Double obdMph, Double gpsMph) {
        return obdMph != null ? obdMph : gpsMph;
    }
}
