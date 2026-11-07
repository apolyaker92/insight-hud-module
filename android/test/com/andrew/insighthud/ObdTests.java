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

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
