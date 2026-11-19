package com.andrew.insighthud;

import java.util.Locale;

final class SoundPolicy {
    enum Kind {
        UI,
        INFO,
        STARTUP,
        WARNING,
        CRITICAL,
        FEEDBACK
    }

    static final String[] MODE_NAMES = {"ALL", "ALERTS", "MUTE"};
    static final float[] LEVELS = {0.25f, 0.5f, 0.75f, 1.0f};
    static final float DARK_LEVEL = 0.12f;
    private static final float NIGHT_FACTOR = 0.55f;

    int mode;
    int level = LEVELS.length - 1;
    boolean nightQuiet = true;

    float gain(Kind kind, boolean dark) {
        if (mode == 2) return 0f;
        if (mode == 1 && kind != Kind.WARNING && kind != Kind.CRITICAL && kind != Kind.FEEDBACK) return 0f;
        float gain = LEVELS[level];
        if (nightQuiet && dark) gain *= NIGHT_FACTOR;
        return gain;
    }

    String label(boolean dark) {
        if (mode == 2) return "MUTE";
        String text = MODE_NAMES[mode] + " " + Math.round(LEVELS[level] * 100) + "%";
        return nightQuiet && dark ? text + " NIGHT" : text;
    }

    static String describe(float gain) {
        return String.format(Locale.US, "%.2f", gain);
    }
}
