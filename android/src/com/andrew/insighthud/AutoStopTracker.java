package com.andrew.insighthud;

/**
 * Spots the Insight's idle stop and restart from RPM. A stop only counts when the car is standing still,
 * the engine had already been running, and zero RPM holds for a moment; a key-off (power lost) or a
 * missing reading never counts, and the first start of a trip is not a "restart".
 */
final class AutoStopTracker {
    enum Event { NONE, STOP, RESTART }

    private static final double RUNNING_RPM = 300;
    private static final double STANDSTILL_MPH = 1.5;
    private static final long STOP_CONFIRM_MS = 600;

    private boolean everRan;
    private boolean running;
    private boolean autoStopped;
    private long zeroSince;

    Event update(Double rpm, Double speedMph, boolean powered, long now) {
        if (rpm == null || !powered) return Event.NONE;
        if (rpm >= RUNNING_RPM) {
            zeroSince = 0;
            running = true;
            everRan = true;
            if (autoStopped) {
                autoStopped = false;
                return Event.RESTART;
            }
            return Event.NONE;
        }
        if (!everRan || !running) return Event.NONE;
        if (speedMph != null && speedMph >= STANDSTILL_MPH) {
            zeroSince = 0;
            return Event.NONE;
        }
        if (zeroSince == 0) zeroSince = now;
        if (now - zeroSince >= STOP_CONFIRM_MS) {
            running = false;
            autoStopped = true;
            return Event.STOP;
        }
        return Event.NONE;
    }

    void reset() {
        everRan = false;
        running = false;
        autoStopped = false;
        zeroSince = 0;
    }
}
