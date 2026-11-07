package com.andrew.insighthud;

import java.util.Locale;

/**
 * Decides how loud each kind of sound should be: a mode (all, warnings only, mute), a volume step, and an
 * optional drop when it is dark outside. Everything that plays goes through {@link #gain}.
 */
final class SoundPolicy {
    enum Kind {
        /** Clicks, map lock and zoom, auto-stop and restart. */
        UI,
        /** Info alerts such as "OBD LINKED". */
        INFO,
        /** The start and shutdown chimes. */
        STARTUP,
        /** Warning alerts. */
        WARNING,
        /** Critical alerts. */
        CRITICAL,
        /** The blip that confirms a volume or mode change. */
        FEEDBACK
    }

    static final String[] MODE_NAMES = {"ALL", "ALERTS", "MUTE"};
    static final float[] LEVELS = {0.25f, 0.5f, 0.75f, 1.0f};
    /** Below this ambient level (see AutoBrightness) it counts as dark: roughly dusk and later. */
    static final float DARK_LEVEL = 0.12f;
    private static final float NIGHT_FACTOR = 0.55f;

    int mode;        // 0 all, 1 warnings and critical only, 2 mute
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
