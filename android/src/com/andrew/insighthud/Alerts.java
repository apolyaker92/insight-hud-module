package com.andrew.insighthud;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Queue for short status messages shown as a banner. Higher levels jump the queue and cut off lower
 * ones, repeats of the same text are ignored for a while, and an alert only starts its timer once it
 * is actually on screen (so nothing expires unseen behind the start animation).
 */
final class Alerts {
    static final int INFO = 0;
    static final int WARN = 1;
    static final int CRITICAL = 2;

    private static final long[] DURATION_MS = {3500, 6000, 9000};
    private static final long REPEAT_BLOCK_MS = 30_000;
    private static final long STALE_MS = 20_000;

    static final class Alert {
        final String text;
        final int level;
        final long raisedAt;
        long shownAt; // 0 until it first appears

        Alert(String text, int level, long raisedAt) {
            this.text = text;
            this.level = level;
            this.raisedAt = raisedAt;
        }
    }

    private final List<Alert> waiting = new ArrayList<>();
    private final Map<String, Long> lastRaised = new HashMap<>();
    private Alert current;
    private boolean started;
    private long lastTick;

    /** Returns false when the same text was raised recently and is being ignored. */
    boolean raise(String text, int level, long now) {
        Long last = lastRaised.get(text);
        if (last != null && now - last < REPEAT_BLOCK_MS) return false;
        lastRaised.put(text, now);
        Alert alert = new Alert(text, level, now);
        if (current != null && level > current.level) current = null; // a worse problem cuts in
        waiting.add(alert);
        return true;
    }

    /**
     * Advances the queue and returns what should be on screen, or null. While {@code held} (another
     * full-screen state owns the display) nothing new starts and the clock for the current one waits.
     */
    Alert tick(long now, boolean held) {
        // While another screen owns the display, an alert that was already showing waits with its time paused.
        if (held && current != null && current.shownAt != 0 && lastTick != 0) current.shownAt += now - lastTick;
        lastTick = now;
        if (current != null && current.shownAt != 0 && now - current.shownAt >= DURATION_MS[current.level]) {
            current = null;
        }
        for (int index = waiting.size() - 1; index >= 0; index--) {
            if (now - waiting.get(index).raisedAt > STALE_MS) waiting.remove(index);
        }
        if (current == null && !held && !waiting.isEmpty()) {
            int best = 0;
            for (int index = 1; index < waiting.size(); index++) {
                if (waiting.get(index).level > waiting.get(best).level) best = index;
            }
            current = waiting.remove(best);
        }
        started = false;
        if (current != null && current.shownAt == 0) {
            if (held) return null;
            current.shownAt = now;
            started = true;
        }
        return held || current == null ? null : current;
    }

    /** True once, right after an alert first appeared (use it to play its sound). */
    boolean justStarted() {
        return started;
    }

    boolean busy() {
        return current != null || !waiting.isEmpty();
    }

    /** Lets the same text be raised again immediately (used by the test hook and on a new trip). */
    void forget() {
        lastRaised.clear();
    }

    void clear() {
        waiting.clear();
        current = null;
        lastRaised.clear();
    }
}
