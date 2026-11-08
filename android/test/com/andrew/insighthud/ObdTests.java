package com.andrew.insighthud;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Plain-Java checks for the OBD parsing and the simulated adapter. Run with android/test/run-tests.sh. */
public final class ObdTests {
    private static int failures;
    private static long fakeNow;

    private static void check(String name, boolean condition) {
        System.out.println((condition ? "PASS  " : "FAIL  ") + name);
        if (!condition) failures++;
    }

    private static String ask(InputStream in, OutputStream out, String request) throws IOException {
        out.write((request + "\r").getBytes(StandardCharsets.US_ASCII));
        StringBuilder reply = new StringBuilder();
        while (in.available() > 0) {
            int next = in.read();
            if (next == '>') break;
            reply.append((char) next);
        }
        return reply.toString().toUpperCase(Locale.US);
    }

    public static void main(String[] args) throws Exception {
        check("rpm parses", Math.abs(ObdProtocol.engineRpm("41 0C 1A F8") - 1726) < 0.01);
        check("speed parses", Math.abs(ObdProtocol.speedMph("41 0D 4A") - 46.0) < 0.5);
        check("throttle parses", Math.abs(ObdProtocol.throttle("41 11 80") - 50.2) < 0.2);
        check("garbage gives null", ObdProtocol.speedMph("NO DATA") == null);
        check("echo and noise lines are skipped",
                ObdProtocol.engineRpm("SEARCHING...\nBUS INIT: OK\n41 0C 0B B8") == 750.0);

        Double gph = ObdProtocol.fuelGallonsPerHour(42d, 2400d);
        Double mpg = ObdProtocol.estimatedMpg(46d, gph);
        check("cruise estimate is Insight-plausible", mpg != null && mpg > 40 && mpg < 70);
        check("no mpg while stopped", ObdProtocol.estimatedMpg(0d, gph) == null);
        check("no fuel when the engine is off", ObdProtocol.fuelGallonsPerHour(15d, 0d) == null);

        SimulatedElm sim = new SimulatedElm(() -> fakeNow);
        InputStream in = sim.input();
        OutputStream out = sim.output();
        check("setup commands answer OK", ask(in, out, "ATE0").contains("OK"));
        String first = ask(in, out, "0100");
        check("first 0100 shows a bus init", first.contains("BUS INIT"));
        byte[] supported = ObdProtocol.payload(first, 0, 4);
        check("0100 bitmap parses", supported != null);
        check("throttle advertised", ObdProtocol.supports(supported, 0x11));
        check("load advertised", ObdProtocol.supports(supported, 0x04));
        check("rpm and speed advertised", ObdProtocol.supports(supported, 0x0C) && ObdProtocol.supports(supported, 0x0D));
        check("intake air not advertised", !ObdProtocol.supports(supported, 0x0F));
        check("MAF not advertised", !ObdProtocol.supports(supported, 0x10));
        check("unsupported PID says NO DATA", ask(in, out, "010F").contains("NO DATA"));
        check("Honda IMA request says NO DATA", ask(in, out, "2101").contains("NO DATA"));

        fakeNow = 40_000;
        check("cruise speed from the simulator", Math.abs(ObdProtocol.speedMph(ask(in, out, "010D")) - 45.0) < 1);
        Double rpm = ObdProtocol.engineRpm(ask(in, out, "010C"));
        check("cruise rpm is believable", rpm != null && rpm > 1500 && rpm < 3500);
        Double load = ObdProtocol.engineLoad(ask(in, out, "0104"));
        Double simMpg = ObdProtocol.estimatedMpg(45d, ObdProtocol.fuelGallonsPerHour(load, rpm));
        check("simulated cruise mpg is plausible", simMpg != null && simMpg > 30 && simMpg < 90);

        fakeNow = 5_000;
        Double idleRpm = ObdProtocol.engineRpm(ask(in, out, "010C"));
        check("auto-stop shows engine off at a stop", idleRpm != null && idleRpm == 0.0);

        sim.close();
        boolean threw = false;
        try { in.available(); } catch (IOException expected) { threw = true; }
        check("closed adapter throws like a dropped link", threw);

        check("pitch dark sits at the floor", AutoBrightness.levelFor(0f) == 0.04f);
        check("dusk is dim", AutoBrightness.levelFor(30f) < 0.2f);
        check("overcast day is bright", AutoBrightness.levelFor(10_000f) > 0.8f);
        check("direct sun is full", AutoBrightness.levelFor(100_000f) == 1.0f);
        boolean rising = true;
        float last = 0;
        for (float lux = 1; lux < 100_000; lux *= 1.3f) {
            float level = AutoBrightness.levelFor(lux);
            rising &= level >= last;
            last = level;
        }
        check("brightness never falls as light rises", rising);
        AutoBrightness smoother = new AutoBrightness();
        float dark = smoother.update(2f);
        float afterOneSunnySample = smoother.update(50_000f);
        float afterManySamples = afterOneSunnySample;
        for (int i = 0; i < 30; i++) afterManySamples = smoother.update(50_000f);
        check("first sample is taken as-is", dark == AutoBrightness.levelFor(2f));
        check("a single bright flash does not jump to full", afterOneSunnySample < 0.6f);
        check("sustained light does reach full", afterManySamples > 0.95f);

        Alerts queue = new Alerts();
        check("no alert when nothing raised", queue.tick(0, false) == null);
        check("an alert is accepted", queue.raise("OBD LINKED", Alerts.INFO, 1000));
        Alerts.Alert shown = queue.tick(1000, false);
        check("it appears and reports starting", shown != null && shown.text.equals("OBD LINKED") && queue.justStarted());
        check("it is not reported as starting again", queue.tick(1100, false) != null && !queue.justStarted());
        check("info expires after a few seconds", queue.tick(1000 + 3600, false) == null);
        check("the same text is ignored at once", !queue.raise("OBD LINKED", Alerts.INFO, 5000));
        check("but allowed after a while", queue.raise("OBD LINKED", Alerts.INFO, 40_000));
        queue.clear();
        queue.raise("GPS LOCKED", Alerts.INFO, 0);
        queue.raise("OBD LINK LOST", Alerts.WARN, 0);
        check("the worse alert shows first", queue.tick(10, false).text.equals("OBD LINK LOST"));
        queue.raise("COOLANT HIGH", Alerts.CRITICAL, 20);
        check("a critical alert cuts in", queue.tick(30, false).text.equals("COOLANT HIGH"));
        check("the interrupted warning does not replay forever",
                queue.tick(30 + 9100, false) == null || queue.tick(30 + 9100, false).level <= Alerts.WARN);
        queue.clear();
        queue.raise("GPS LOCKED", Alerts.INFO, 0);
        check("nothing starts while the display is held", queue.tick(100, true) == null);
        Alerts.Alert afterHold = queue.tick(200, false);
        check("it starts once the hold ends", afterHold != null && afterHold.shownAt == 200);
        queue.clear();
        queue.raise("OLD NEWS", Alerts.INFO, 0);
        check("stale alerts are dropped unseen", queue.tick(25_000, false) == null);

        queue.clear();
        queue.raise("OBD LINKED", Alerts.INFO, 0);
        queue.tick(10, false);
        queue.tick(1000, true);
        queue.tick(9000, true);
        check("a shown alert waits out a long hold", queue.tick(9100, false) != null);
        check("and still expires afterwards", queue.tick(9100 + 3600, false) == null);

        AutoStopTracker engine = new AutoStopTracker();
        check("a cold ignition-on (no rpm yet) is silent", engine.update(0d, 0d, true, 0) == AutoStopTracker.Event.NONE);
        check("the first start is not a restart", engine.update(850d, 0d, true, 1000) == AutoStopTracker.Event.NONE);
        check("a single zero reading is not enough", engine.update(0d, 0d, true, 2000) == AutoStopTracker.Event.NONE);
        check("zero rpm that holds at a standstill is an auto-stop", engine.update(0d, 0d, true, 2700) == AutoStopTracker.Event.STOP);
        check("it only fires once", engine.update(0d, 0d, true, 3500) == AutoStopTracker.Event.NONE);
        check("rpm returning is a restart", engine.update(800d, 0d, true, 4000) == AutoStopTracker.Event.RESTART);
        check("and only once", engine.update(900d, 3d, true, 4500) == AutoStopTracker.Event.NONE);
        AutoStopTracker moving = new AutoStopTracker();
        moving.update(2000d, 40d, true, 0);
        moving.update(0d, 40d, true, 1000);
        check("zero rpm while moving is not an auto-stop", moving.update(0d, 40d, true, 2000) == AutoStopTracker.Event.NONE);
        AutoStopTracker keyOff = new AutoStopTracker();
        keyOff.update(850d, 0d, true, 0);
        keyOff.update(0d, 0d, true, 1000);
        check("power loss cancels it (key-off)", keyOff.update(0d, 0d, false, 2000) == AutoStopTracker.Event.NONE);
        AutoStopTracker blip = new AutoStopTracker();
        blip.update(850d, 0d, true, 0);
        blip.update(0d, 0d, true, 100);
        check("a brief dropout that recovers stays silent", blip.update(850d, 0d, true, 300) == AutoStopTracker.Event.NONE);
        check("missing readings are ignored", new AutoStopTracker().update(null, null, true, 0) == AutoStopTracker.Event.NONE);

        SoundPolicy policy = new SoundPolicy();
        check("full volume plays everything at 1.0", policy.gain(SoundPolicy.Kind.UI, false) == 1.0f
                && policy.gain(SoundPolicy.Kind.CRITICAL, false) == 1.0f);
        policy.level = 1;
        check("volume steps scale every sound", policy.gain(SoundPolicy.Kind.INFO, false) == 0.5f);
        policy.mode = 2;
        boolean allSilent = true;
        for (SoundPolicy.Kind kind : SoundPolicy.Kind.values()) allSilent &= policy.gain(kind, false) == 0f;
        check("mute silences every kind", allSilent);
        policy.mode = 1;
        check("alerts-only keeps warnings", policy.gain(SoundPolicy.Kind.WARNING, false) > 0f);
        check("alerts-only keeps critical", policy.gain(SoundPolicy.Kind.CRITICAL, false) > 0f);
        check("alerts-only drops clicks, info and chimes", policy.gain(SoundPolicy.Kind.UI, false) == 0f
                && policy.gain(SoundPolicy.Kind.INFO, false) == 0f && policy.gain(SoundPolicy.Kind.STARTUP, false) == 0f);
        check("a volume change is still audible in alerts-only", policy.gain(SoundPolicy.Kind.FEEDBACK, false) > 0f);
        policy.mode = 0;
        policy.level = 3;
        check("night quiet lowers sound in the dark", policy.gain(SoundPolicy.Kind.UI, true) < 1.0f);
        policy.nightQuiet = false;
        check("and is off when disabled", policy.gain(SoundPolicy.Kind.UI, true) == 1.0f);
        policy.nightQuiet = true;
        check("labels read clearly", policy.label(false).equals("ALL 100%") && policy.label(true).equals("ALL 100% NIGHT"));
        policy.mode = 2;
        check("mute label", policy.label(false).equals("MUTE"));

        check("gps speed converts m/s to mph", Math.abs(SpeedSource.gpsMph(true, 20f, 500) - 44.7) < 0.1);
        check("gps jitter at a standstill reads zero", SpeedSource.gpsMph(true, 0.3f, 500) == 0.0);
        check("a fix without speed is ignored", SpeedSource.gpsMph(false, 20f, 500) == null);
        check("an old fix is ignored", SpeedSource.gpsMph(true, 20f, SpeedSource.GPS_MAX_AGE_MS + 1) == null);
        check("the car's speed wins over gps", SpeedSource.choose(31d, 33d) == 31d);
        check("gps fills in when the car has none", SpeedSource.choose(null, 33d) == 33d);
        check("nothing when neither has speed", SpeedSource.choose(null, null) == null);

        check("search tries the Insight protocol first", EcuSearch.order(null)[0].equals("ATSP3"));
        check("search remembers the protocol that worked", EcuSearch.order("ATSP0")[0].equals("ATSP0")
                && EcuSearch.order("ATSP0").length == 4 && EcuSearch.order("ATSP0")[1].equals("ATSP3"));
        SimulatedElm fussy = new SimulatedElm(() -> fakeNow);
        fussy.failBusInits = 1;
        InputStream fussyIn = fussy.input();
        OutputStream fussyOut = fussy.output();
        EcuSearch.Result retried = EcuSearch.find((cmd, timeout) -> ask(fussyIn, fussyOut, cmd), null);
        check("a failed first bus init is retried on the same protocol", retried.protocol.equals("ATSP3"));
        SimulatedElm autoOnly = new SimulatedElm(() -> fakeNow);
        autoOnly.onlyAnswersOn = "ATSP0";
        InputStream autoIn = autoOnly.input();
        OutputStream autoOut = autoOnly.output();
        EcuSearch.Result fallback = EcuSearch.find((cmd, timeout) -> ask(autoIn, autoOut, cmd), null);
        check("it falls back to auto-detect when ISO 9141 will not answer",
                fallback.protocol.equals("ATSP0") && ObdProtocol.supports(fallback.supported, 0x0C));
        SimulatedElm dead = new SimulatedElm(() -> fakeNow);
        dead.onlyAnswersOn = "NONE";
        InputStream deadIn = dead.input();
        OutputStream deadOut = dead.output();
        boolean gaveUp = false;
        try {
            EcuSearch.find((cmd, timeout) -> ask(deadIn, deadOut, cmd), null);
        } catch (IOException expected) {
            gaveUp = true;
        }
        check("it gives up cleanly when nothing answers", gaveUp);

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
