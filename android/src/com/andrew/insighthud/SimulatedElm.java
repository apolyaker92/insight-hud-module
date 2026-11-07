package com.andrew.insighthud;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * A scripted ELM327 on a ZE1-like ECU, for testing the real polling and parsing path without a car.
 * It answers only the PIDs that Insight owners report working over a plain adapter; everything else
 * (including Honda IMA requests) replies NO DATA. The values follow a looping drive cycle.
 */
final class SimulatedElm implements Closeable {
    static final String ADDRESS = "SIM:ELM327";
    static final String NAME = "SIMULATED ADAPTER";
    private static final double CYCLE_SECONDS = 90;

    interface Clock { long millis(); }

    private final Clock clock;
    private final long startedAt;
    private final StringBuilder command = new StringBuilder();
    private final StringBuilder replies = new StringBuilder();
    private int readPosition;
    private boolean closed;
    private boolean initialised;

    SimulatedElm(Clock clock) {
        this.clock = clock;
        this.startedAt = clock.millis();
    }

    InputStream input() {
        return new InputStream() {
            @Override public int available() throws IOException {
                synchronized (SimulatedElm.this) {
                    ensureOpen();
                    return replies.length() - readPosition;
                }
            }

            @Override public int read() throws IOException {
                synchronized (SimulatedElm.this) {
                    ensureOpen();
                    if (readPosition >= replies.length()) return -1;
                    int next = replies.charAt(readPosition++);
                    if (readPosition == replies.length()) {
                        replies.setLength(0);
                        readPosition = 0;
                    }
                    return next;
                }
            }
        };
    }

    OutputStream output() {
        return new OutputStream() {
            @Override public void write(int value) throws IOException {
                synchronized (SimulatedElm.this) {
                    ensureOpen();
                    if (value == '\r') {
                        String request = command.toString();
                        command.setLength(0);
                        replies.append(respond(request));
                    } else if (value != '\n') {
                        command.append((char) value);
                    }
                }
            }
        };
    }

    @Override public synchronized void close() {
        closed = true;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Simulated adapter closed");
    }

    private static String reply(String... lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line).append('\r');
        return text.append("\r>").toString();
    }

    private String respond(String raw) {
        String request = raw.replace(" ", "").toUpperCase(Locale.US);
        if (request.startsWith("AT")) {
            if (request.equals("ATZ")) {
                initialised = false;
                return reply("", "ELM327 v1.5");
            }
            if (request.equals("ATRV")) {
                double volts = 13.6 + 0.2 * Math.sin(seconds());
                return reply(String.format(Locale.US, "%.1fV", volts));
            }
            return reply("OK");
        }
        if (request.equals("0100")) {
            if (!initialised) {
                initialised = true;
                return reply("SEARCHING...", "BUS INIT: OK", "41 00 1E 1C 90 00");
            }
            return reply("41 00 1E 1C 90 00");
        }
        if (request.equals("0120") || request.equals("0140") || request.equals("0160")) {
            return reply("41 " + request.substring(2) + " 00 00 00 00");
        }
        Drive drive = drive(seconds());
        switch (request) {
            case "0104":
                return reply("41 04 " + hex(drive.throttleLoad * 255 / 100));
            case "0105":
                return reply("41 05 " + hex(drive.coolantF() > 0 ? (drive.coolantF() - 32) / 1.8 + 40 : 40));
            case "0106":
            case "0107":
                return reply("41 " + request.substring(2) + " " + hex(128 + 3 * Math.sin(seconds() / 5)));
            case "010C":
                return reply("41 0C " + hex((int) (drive.rpm * 4) / 256) + " " + hex((int) (drive.rpm * 4) % 256));
            case "010D":
                return reply("41 0D " + hex(drive.mph * 1.609344));
            case "010E":
                return reply("41 0E " + hex((12 + 4 * Math.sin(seconds() / 3) + 64) * 2));
            case "0111":
                return reply("41 11 " + hex(drive.throttle * 255 / 100));
            case "0114":
                return reply("41 14 " + hex(90 + 40 * Math.sin(seconds() * 2)) + " FF");
            default:
                // Unsupported PIDs and Honda-specific modes (IMA) are not answered.
                return request.matches("[0-9A-F]+") ? reply("NO DATA") : reply("?");
        }
    }

    private double seconds() {
        return (clock.millis() - startedAt) / 1000.0;
    }

    private static String hex(double value) {
        int rounded = (int) Math.round(Math.max(0, Math.min(255, value)));
        return String.format(Locale.US, "%02X", rounded);
    }

    /** One simulated moment of the drive cycle. */
    static final class Drive {
        final double mph, rpm, throttle, throttleLoad;
        private final double elapsed;

        Drive(double elapsed, double mph, double rpm, double throttle, double load) {
            this.elapsed = elapsed;
            this.mph = mph;
            this.rpm = rpm;
            this.throttle = throttle;
            this.throttleLoad = load;
        }

        double coolantF() {
            return Math.min(190, 110 + elapsed * 0.9);
        }
    }

    private static double lerp(double t, double start, double end, double from, double to) {
        double fraction = Math.max(0, Math.min(1, (t - start) / (end - start)));
        return from + (to - from) * fraction;
    }

    static Drive drive(double total) {
        double t = total % CYCLE_SECONDS;
        double mph, throttle;
        if (t < 8) { mph = 0; throttle = 0; }
        else if (t < 28) { mph = lerp(t, 8, 28, 0, 45); throttle = 55; }
        else if (t < 55) { mph = 45; throttle = 22; }
        else if (t < 68) { mph = lerp(t, 55, 68, 45, 62); throttle = 45; }
        else if (t < 78) { mph = 62; throttle = 25; }
        else { mph = lerp(t, 78, 90, 62, 0); throttle = 0; }
        // Auto-stop: the engine is off while standing, and a CVT keeps revs low.
        double rpm = mph < 1 && t < 8 && t > 2 ? 0 : mph < 1 ? 820 : Math.max(950, 900 + mph * 22 + throttle * 6);
        double load = mph < 1 ? 15 : Math.min(95, 14 + throttle * 1.1 + mph * 0.25);
        return new Drive(total, mph, rpm, throttle, load);
    }
}
