package com.andrew.insighthud;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Classic Bluetooth SPP transport for ELM327 adapters and the ZE1 ISO 9141-2 profile. */
final class ObdBluetooth {
    private static final UUID SERIAL_PORT = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private volatile Session current;
    private Future<?> job;
    private volatile String[] pendingProbe;
    private static final String PROBE_TAG = "HudObdProbe";

    interface Listener {
        void onUpdate(String status, Readings readings);
    }

    static final class Readings {
        final Double speedMph;
        final Double rpm;
        final Double coolantF;
        final Double intakeF;
        final Double voltage;
        final Double load;
        final Double throttle;
        /** Estimated fuel flow in gallons per hour, and the estimated MPG derived from it. */
        final Double fuelGph;
        final Double mpg;

        Readings(Double speedMph, Double rpm, Double coolantF, Double intakeF, Double voltage, Double load) {
            this(speedMph, rpm, coolantF, intakeF, voltage, load, null, null, null);
        }

        Readings(Double speedMph, Double rpm, Double coolantF, Double intakeF, Double voltage, Double load,
                 Double throttle, Double fuelGph, Double mpg) {
            this.throttle = throttle;
            this.fuelGph = fuelGph;
            this.mpg = mpg;
            this.speedMph = speedMph;
            this.rpm = rpm;
            this.coolantF = coolantF;
            this.intakeF = intakeF;
            this.voltage = voltage;
            this.load = load;
        }

        /** The same readings with the speed replaced (used when the speed comes from GPS). */
        Readings withSpeed(Double mph) {
            return new Readings(mph, rpm, coolantF, intakeF, voltage, load, throttle, fuelGph, mpg);
        }
    }

    private static final class Session {
        final String address;
        volatile boolean cancelled;
        volatile Closeable closer;
        volatile InputStream input;
        volatile OutputStream output;

        Session(String address) { this.address = address; }

        void closeSocket() {
            Closeable open = closer;
            if (open != null) {
                try { open.close(); } catch (IOException ignored) { }
            }
        }
    }

    ObdBluetooth(BluetoothAdapter adapter, Listener listener) {
        this.adapter = adapter;
        this.listener = listener;
    }

    void start(String address) {
        stop();
        Session session = new Session(address);
        current = session;
        job = worker.submit(() -> run(session));
    }

    /** Queues read-only raw requests (already validated by the caller); results go to logcat. */
    void probe(String[] requests) {
        pendingProbe = requests;
    }

    /** True while a session for this adapter address is active (connecting, linked or retrying). */
    boolean isRunning(String address) {
        Session session = current;
        return session != null && !session.cancelled && session.address.equals(address)
                && job != null && !job.isDone();
    }

    void stop() {
        Session old = current;
        current = null;
        if (old != null) {
            old.cancelled = true;
            old.closeSocket();
        }
        if (job != null) job.cancel(true);
    }

    void destroy() {
        stop();
        worker.shutdownNow();
        timer.shutdownNow();
    }

    /** Closes the socket after the delay; a destroyed transport ends the session instead of throwing. */
    private ScheduledFuture<?> schedule(Session session, long delayMs) throws InterruptedException {
        try {
            return timer.schedule(session::closeSocket, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException destroyed) {
            throw new InterruptedException();
        }
    }

    private void publish(Session session, String status, Readings readings) {
        main.post(() -> {
            if (current == session && !session.cancelled) listener.onUpdate(status, readings);
        });
    }

    private void run(Session session) {
        while (!session.cancelled && !Thread.currentThread().isInterrupted()) {
            try {
                boolean simulated = SimulatedElm.ADDRESS.equals(session.address);
                if (!simulated && !adapter.isEnabled()) {
                    publish(session, "BLUETOOTH OFF", null);
                    Thread.sleep(1000);
                    continue;
                }
                if (simulated) {
                    publish(session, "SIM CONNECTING", null);
                    SimulatedElm sim = new SimulatedElm(SystemClock::elapsedRealtime);
                    session.closer = sim;
                    session.input = sim.input();
                    session.output = sim.output();
                } else {
                    BluetoothDevice device = adapter.getRemoteDevice(session.address);
                    if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                        publish(session, "PAIR ADAPTER", null);
                        Thread.sleep(5000);
                        continue;
                    }
                    publish(session, "BT CONNECTING", null);
                    BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SERIAL_PORT);
                    session.closer = socket;
                    if (session.cancelled) throw new InterruptedException();
                    ScheduledFuture<?> connectTimeout = schedule(session, 12000);
                    try {
                        socket.connect();
                    } finally {
                        connectTimeout.cancel(false);
                    }
                    session.input = socket.getInputStream();
                    session.output = socket.getOutputStream();
                }
                publish(session, "ADAPTER SETUP", null);
                command(session, "ATZ", 5000);
                for (String setup : new String[]{"ATE0", "ATL0", "ATS1", "ATH0", "ATST64"}) {
                    if (!command(session, setup, 2000).contains("OK")) throw new IOException("Adapter setup failed");
                }
                publish(session, "ECU SEARCH", null);
                EcuSearch.Result ecu = EcuSearch.find((request, timeout) -> command(session, request, timeout),
                        workingProtocol);
                if (!ecu.protocol.equals(workingProtocol)) Log.i("HudObd", "ECU answered on " + ecu.protocol);
                workingProtocol = ecu.protocol;
                poll(session, ecu.supported);
            } catch (SecurityException denied) {
                publish(session, "BT PERMISSION", null);
                return;
            } catch (IOException | IllegalArgumentException failed) {
                publish(session, "LINK LOST / RETRY", null);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                session.closeSocket();
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void poll(Session session, byte[] supported) throws IOException, InterruptedException {
        int emptyCycles = 0;
        boolean speedSupported = ObdProtocol.supports(supported, 0x0D);
        boolean rpmSupported = ObdProtocol.supports(supported, 0x0C);
        boolean throttleSupported = ObdProtocol.supports(supported, 0x11);
        boolean hasInputs = speedSupported || rpmSupported || ObdProtocol.supports(supported, 5)
                || ObdProtocol.supports(supported, 15) || ObdProtocol.supports(supported, 4) || throttleSupported;
        // The car's link only manages a few requests a second, so rpm and speed go out every pass, throttle and
        // load every MEDIUM_EVERY passes, and the slow-moving readings only every SLOW_EVERY_MS.
        Double coolant = null, intake = null, voltage = null, load = null, throttle = null;
        long slowDueAt = 0, windowStart = SystemClock.elapsedRealtime();
        int passes = 0, sinceMedium = MEDIUM_EVERY;
        while (!session.cancelled) {
            long passStart = SystemClock.elapsedRealtime();
            String[] probe = pendingProbe;
            if (probe != null) {
                pendingProbe = null;
                for (String request : probe) {
                    String reply = command(session, request, 4000).trim().replaceAll("[\r\n]+", " | ");
                    Log.i(PROBE_TAG, request + " -> " + reply);
                }
            }
            boolean slowDue = passStart >= slowDueAt;
            if (slowDue) {
                slowDueAt = passStart + SLOW_EVERY_MS;
                coolant = ObdProtocol.supports(supported, 5)
                        ? ObdProtocol.temperatureF(command(session, "0105", 2000), 5) : null;
                intake = ObdProtocol.supports(supported, 15)
                        ? ObdProtocol.temperatureF(command(session, "010F", 2000), 15) : null;
                voltage = ObdProtocol.voltage(command(session, "ATRV", 2000));
            }
            boolean mediumDue = ++sinceMedium >= MEDIUM_EVERY;
            if (mediumDue) {
                sinceMedium = 0;
                load = ObdProtocol.supports(supported, 4)
                        ? ObdProtocol.engineLoad(command(session, "0104", 2000)) : null;
                throttle = throttleSupported ? ObdProtocol.throttle(command(session, "0111", 2000)) : null;
            }
            Double rpm = rpmSupported ? ObdProtocol.engineRpm(command(session, "010C", 2000)) : null;
            Double speed = speedSupported ? ObdProtocol.speedMph(command(session, "010D", 2000)) : null;
            Double fuelGph = ObdProtocol.fuelGallonsPerHour(load, rpm);
            Double mpg = ObdProtocol.estimatedMpg(speed, fuelGph);
            boolean answered = speed != null || rpm != null
                    || (slowDue && (coolant != null || intake != null))
                    || (mediumDue && (load != null || throttle != null));
            if (hasInputs && !answered) emptyCycles++;
            else emptyCycles = 0;
            if (emptyCycles >= 3) throw new IOException("ECU stopped responding");
            publish(session, session.address.equals(SimulatedElm.ADDRESS) ? "SIM LIVE" : "BT LIVE",
                    new Readings(speed, rpm, coolant, intake, voltage, load, throttle, fuelGph, mpg));
            passes++;
            long now = SystemClock.elapsedRealtime();
            if (now - windowStart >= 10_000) {
                Log.i("HudObdRate", String.format(Locale.US, "%.1f updates/s (speed+rpm)",
                        passes * 1000.0 / (now - windowStart)));
                passes = 0;
                windowStart = now;
            }
            // The adapter normally paces this loop; the floor only stops an instant (simulated) one spinning.
            long spare = MIN_PASS_MS - (now - passStart);
            if (spare > 0) Thread.sleep(spare);
        }
    }

    /** The protocol the ECU last answered on; tried first on reconnects. */
    private volatile String workingProtocol;
    private static final long SLOW_EVERY_MS = 10_000;
    private static final int MEDIUM_EVERY = 4;
    private static final long MIN_PASS_MS = 150;

    private String command(Session session, String request, long timeoutMs) throws IOException, InterruptedException {
        if (session.cancelled) throw new InterruptedException();
        ScheduledFuture<?> timeout = schedule(session, timeoutMs);
        try {
            InputStream input = session.input;
            OutputStream output = session.output;
            int drained = 0;
            while (input.available() > 0 && drained++ < 8192) input.read();
            output.write((request + "\r").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            StringBuilder response = new StringBuilder();
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            while (!session.cancelled && SystemClock.elapsedRealtime() < deadline) {
                if (input.available() == 0) {
                    Thread.sleep(20);
                    continue;
                }
                int next = input.read();
                if (next < 0) throw new IOException("Bluetooth disconnected");
                if (next == '>') return response.toString().toUpperCase(Locale.US);
                response.append((char) next);
                if (response.length() > 8192) throw new IOException("Invalid adapter response");
            }
            if (session.cancelled) throw new InterruptedException();
            throw new IOException("Adapter response timed out");
        } finally {
            timeout.cancel(false);
        }
    }
}
