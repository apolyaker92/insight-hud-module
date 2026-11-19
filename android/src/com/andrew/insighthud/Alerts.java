package com.andrew.insighthud;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        final boolean silent;
        final long raisedAt;
        long shownAt;

        Alert(String text, int level, long raisedAt, boolean silent) {
            this.text = text;
            this.level = level;
            this.silent = silent;
            this.raisedAt = raisedAt;
        }
    }

    private final List<Alert> waiting = new ArrayList<>();
    private final Map<String, Long> lastRaised = new HashMap<>();
    private Alert current;
    private boolean started;
    private long lastTick;

    boolean raise(String text, int level, long now) {
        return raise(text, level, now, false);
    }

    boolean raise(String text, int level, long now, boolean silent) {
        Long last = lastRaised.get(text);
        if (last != null && now - last < REPEAT_BLOCK_MS) return false;
        lastRaised.put(text, now);
        Alert alert = new Alert(text, level, now, silent);
        if (current != null && level > current.level) current = null;
        waiting.add(alert);
        return true;
    }

    Alert tick(long now, boolean held) {
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

    boolean justStarted() {
        return started;
    }

    boolean busy() {
        return current != null || !waiting.isEmpty();
    }

    void forget() {
        lastRaised.clear();
    }

    void clear() {
        waiting.clear();
        current = null;
        lastRaised.clear();
    }
}
