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

    /** Packs segments (ax, ay, bx, by, ...) as the road pack does: little-endian uint16 fractions of a tile. */
    private static byte[] segments(double... values) {
        byte[] bytes = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            int fixed = (int) Math.round(values[i] * 65535);
            bytes[i * 2] = (byte) fixed;
            bytes[i * 2 + 1] = (byte) (fixed >> 8);
        }
        return bytes;
    }

    private static final double TEST_LAT = 42.6, TEST_LON = -73.8;

    /** Lat/lon for a point this many metres east and north of the test origin. */
    private static double[] at(double east, double north) {
        return new double[]{TEST_LAT + north / 110_574, TEST_LON + east / (111_320 * Math.cos(Math.toRadians(TEST_LAT)))};
    }

    /** East 400 m, then north 400 m, a point every 20 m: a single left turn at 400 m. */
    private static RouteTrack lRoute(java.util.List<RouteTrack.StepSpec> specs, double seconds) {
        java.util.List<double[]> points = new java.util.ArrayList<>();
        for (int e = 0; e <= 400; e += 20) points.add(at(e, 0));
        for (int n = 20; n <= 400; n += 20) points.add(at(400, n));
        double[] lat = new double[points.size()], lon = new double[points.size()];
        for (int i = 0; i < lat.length; i++) { lat[i] = points.get(i)[0]; lon[i] = points.get(i)[1]; }
        return new RouteTrack("Test Place", lat, lon, specs, seconds);
    }

    private static void move(RouteTrack route, double east, double north, long now) {
        double[] p = at(east, north);
        route.update(p[0], p[1], now);
    }

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

        // A horizontal road across the middle of a tile, and a vertical one down its right side.
        byte[] roads = segments(0, 0.5, 1, 0.5, 0.9, 0, 0.9, 1);
        check("a point on a road is zero away", RoadLookup.nearestSquared(roads, 0.3, 0.5) < 1e-9);
        check("distance is to the nearest road", Math.abs(Math.sqrt(RoadLookup.nearestSquared(roads, 0.3, 0.45)) - 0.05) < 1e-4
                && Math.abs(Math.sqrt(RoadLookup.nearestSquared(roads, 0.88, 0.1)) - 0.02) < 1e-4);
        check("distance past a segment's end measures to the end point",
                Math.abs(Math.sqrt(RoadLookup.nearestSquared(segments(0.2, 0.5, 0.4, 0.5), 0.5, 0.5)) - 0.1) < 1e-4);
        check("no segments means no road", Double.isInfinite(RoadLookup.nearestSquared(new byte[0], 0.5, 0.5)));
        double paces = RoadLookup.metersPerTile(42.6, 1 << 14);
        check("a z14 tile is about 1.8 km at Albany's latitude", paces > 1750 && paces < 1900);
        check("35 m is a small part of a tile", 35 / paces < 0.02);
        check("compass: east", "E".equals(RoadLookup.bearing(1, 0)));
        check("compass: north is up the screen", "N".equals(RoadLookup.bearing(0, -1)));
        check("compass: south-west", "SW".equals(RoadLookup.bearing(-1, 1)));
        check("miles", Math.abs(RoadLookup.metersToMiles(1609.344) - 1) < 1e-9);
        check("bearing east is 90", Math.abs(RoadLookup.bearingDegrees(1, 0) - 90) < 1e-9);
        check("bearing north-west is 315", Math.abs(RoadLookup.bearingDegrees(-1, -1) - 315) < 1e-9);
        check("signals, stops, crossings and curves must sit on the road, cameras may be beside it",
                RoadLookup.onRoadMeters(RoadLookup.STOP) > 0 && RoadLookup.onRoadMeters(RoadLookup.STOP) < 10
                        && RoadLookup.onRoadMeters(RoadLookup.CAMERA) > RoadLookup.onRoadMeters(RoadLookup.STOP)
                        && RoadLookup.onRoadMeters(RoadLookup.EXIT) == 0);
        Alerts quiet = new Alerts();
        quiet.raise("OVER LIMIT 35", Alerts.INFO, 1000, true);
        Alerts.Alert silentOne = quiet.tick(1000, false);
        check("a silent alert still shows but is flagged silent", silentOne != null && silentOne.silent);
        Alerts loud = new Alerts();
        loud.raise("OBD LINKED", Alerts.INFO, 1000);
        check("an ordinary alert is not silent", !loud.tick(1000, false).silent);
        check("negative bearings wrap", Math.abs(RoadLookup.normalizeBearing(-150) - 210) < 1e-9
                && Math.abs(RoadLookup.normalizeBearing(370) - 10) < 1e-9);
        check("hazards outrank signals outrank exits", RoadLookup.aheadGroup(RoadLookup.CROSSING) == 0
                && RoadLookup.aheadGroup(RoadLookup.CURVE) == 0 && RoadLookup.aheadGroup(RoadLookup.STOP) == 1
                && RoadLookup.aheadGroup(RoadLookup.EXIT) == 2);
        check("angle difference wraps", Math.abs(RoadLookup.angleDifference(350, 10) - 20) < 1e-9
                && Math.abs(RoadLookup.angleDifference(90, 270) - 180) < 1e-9);
        check("a signal 100 m dead ahead at 30 mph counts",
                RoadLookup.aheadEligible(RoadLookup.SIGNAL, "", 100, 90, 90, 30));
        check("a signal behind the car does not", !RoadLookup.aheadEligible(RoadLookup.SIGNAL, "", 100, 270, 90, 30));
        check("a signal off to the side does not", !RoadLookup.aheadEligible(RoadLookup.SIGNAL, "", 100, 140, 90, 30));
        check("a signal too far to matter at 30 mph does not",
                !RoadLookup.aheadEligible(RoadLookup.SIGNAL, "", 400, 90, 90, 30));
        check("the same signal does at 60 mph", RoadLookup.aheadEligible(RoadLookup.SIGNAL, "", 300, 90, 90, 60));
        check("a signal underfoot is ignored", !RoadLookup.aheadEligible(RoadLookup.SIGNAL, "", 10, 90, 90, 30));
        check("nothing counts without a course or when crawling",
                !RoadLookup.aheadEligible(RoadLookup.SIGNAL, "", 100, 90, Double.NaN, 30)
                        && !RoadLookup.aheadEligible(RoadLookup.SIGNAL, "", 100, 90, 90, 3));
        check("exits and rest areas only count at highway speed",
                !RoadLookup.aheadEligible(RoadLookup.EXIT, "EXIT 5", 500, 90, 90, 30)
                        && RoadLookup.aheadEligible(RoadLookup.EXIT, "EXIT 5", 500, 90, 90, 60)
                        && RoadLookup.aheadEligible(RoadLookup.REST, "REST AREA", 1000, 90, 90, 60));
        check("a curve only warns when going faster than it advises",
                RoadLookup.aheadEligible(RoadLookup.CURVE, "35", 100, 90, 90, 45)
                        && !RoadLookup.aheadEligible(RoadLookup.CURVE, "35", 100, 90, 90, 34)
                        && !RoadLookup.aheadEligible(RoadLookup.CURVE, "x", 100, 90, 90, 45));

        // Route lines and following them.
        double[][] decoded = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5);
        check("a published polyline decodes to its three points", decoded[0].length == 3
                && Math.abs(decoded[0][0] - 38.5) < 1e-9 && Math.abs(decoded[1][0] + 120.2) < 1e-9
                && Math.abs(decoded[0][1] - 40.7) < 1e-9 && Math.abs(decoded[1][1] + 120.95) < 1e-9
                && Math.abs(decoded[0][2] - 43.252) < 1e-9 && Math.abs(decoded[1][2] + 126.453) < 1e-9);
        check("precision 6 scales the same data", Math.abs(Polyline.decode("_p~iF~ps|U", 6)[0][0] - 3.85) < 1e-9);
        check("a bad polyline gives no points, not a crash", Polyline.decode("!!!", 5)[0].length == 0
                && Polyline.decode(null, 5)[0].length == 0);

        java.util.List<RouteTrack.StepSpec> specs = new java.util.ArrayList<>();
        RouteTrack.StepSpec turn = new RouteTrack.StepSpec();
        turn.street = "Main St";
        turn.atMeters = 400;
        specs.add(turn);
        RouteTrack route = lRoute(specs, 120);
        check("the route is 800 m long", Math.abs(route.lengthMeters - 800) < 8);
        check("a left turn is read from the geometry", route.steps[0].kind == RouteTrack.LEFT);
        check("an arrival is added at the end", route.steps[route.steps.length - 1].kind == RouteTrack.ARRIVE);
        check("a step's label says what to do", "TURN LEFT ONTO MAIN ST".equals(route.steps[0].label()));
        move(route, 100, 0, 1000);
        check("100 m along: 700 m left, the turn 300 m ahead", Math.abs(route.remainingMeters() - 700) < 8
                && Math.abs(route.metersToNext() - 300) < 8 && route.nextStep().kind == RouteTrack.LEFT);
        check("seconds left scale with distance", Math.abs(route.remainingSeconds() - 105) < 2);
        move(route, 395, 0, 2000);
        check("just before the turn it is 5 m away", route.metersToNext() < 12 && route.nextStep() != null);
        move(route, 400, 30, 3000);
        check("after the turn only the arrival is left", route.nextStep() != null
                && route.nextStep().kind == RouteTrack.ARRIVE
                && Math.abs(route.metersToNext() - route.remainingMeters()) < 1e-6);
        check("not off route while on it", !route.offRoute(3000) && route.offMeters() < 5);
        move(route, 400, 390, 4000);
        check("within 30 m of the end is arrival", route.arrived());

        RouteTrack lost = lRoute(null, 0);
        move(lost, 100, 0, 1000);
        move(lost, 100, -80, 2000);
        check("80 m off the line is measured", Math.abs(lost.offMeters() - 80) < 3);
        check("off route is not declared at once", !lost.offRoute(3000));
        check("but is after a few seconds", lost.offRoute(7000));
        move(lost, 150, 2, 8000);
        check("back on the line clears it", !lost.offRoute(8000) && Math.abs(lost.alongMeters() - 150) < 8);

        RouteTrack manual = lRoute(null, 0);
        RouteTrack.StepSpec given = new RouteTrack.StepSpec();
        given.modifier = "right";
        given.type = "turn";
        given.instruction = "Turn right at the lights";
        given.lat = at(400, 0)[0];
        given.lon = at(400, 0)[1];
        java.util.List<RouteTrack.StepSpec> givenList = new java.util.ArrayList<>();
        givenList.add(given);
        RouteTrack located = lRoute(givenList, 0);
        check("a step given by position snaps onto the line", Math.abs(located.steps[0].atMeters - 400) < 8);
        check("a stated modifier beats the geometry", located.steps[0].kind == RouteTrack.RIGHT);
        check("a supplied instruction is shown in capitals", "TURN RIGHT AT THE LIGHTS".equals(located.steps[0].label()));
        check("turn thresholds", RouteTrack.turnKind(5) == RouteTrack.STRAIGHT
                && RouteTrack.turnKind(35) == RouteTrack.SLIGHT_RIGHT && RouteTrack.turnKind(-90) == RouteTrack.LEFT
                && RouteTrack.turnKind(140) == RouteTrack.SHARP_RIGHT && RouteTrack.turnKind(-175) == RouteTrack.UTURN);

        // ---- offline router ----
        RouteTile gridTile = RouteTile.decode(java.util.Base64.getDecoder().decode(RouterTestData.TILE));
        check("tile decodes", gridTile.nodeCount() == 16 && gridTile.edgeCount() == 45);
        check("tile edge names", gridTile.nameOf(0).startsWith("Row") || gridTile.nameOf(0).startsWith("Col"));
        Router router = new Router((level, x, y) -> level == 0 && x == 428 && y == 528 ? gridTile : null);
        double[] n00 = {42.001, -72.999}, n01 = {42.001, -72.998}, n02 = {42.001, -72.997};
        double[] n22 = {42.003, -72.997}, n30 = {42.004, -72.999}, n31 = {42.004, -72.998};
        double[] n33 = {42.004, -72.996}, n03 = {42.001, -72.996};
        Router.Result straightNorth = router.route(n01[0], n01[1], Double.NaN, n31[0], n31[1]);
        check("straight route found", straightNorth.error == null);
        check("straight route length", Math.abs(straightNorth.meters - 333) < 15);
        check("straight route has no manoeuvres", straightNorth.steps.isEmpty());
        Router.Result corner = router.route(n00[0], n00[1], Double.NaN, n22[0], n22[1]);
        check("corner route found", corner.error == null && Math.abs(corner.meters - 386) < 20);
        check("one turn, preferring right to left", corner.steps.size() == 1 && "right".equals(corner.steps.get(0).modifier)
                && "Row 2".equals(corner.steps.get(0).street) && Math.abs(corner.steps.get(0).atMeters - 222) < 20);
        RouteTrack built = new RouteTrack("", corner.lat, corner.lon, corner.steps, corner.seconds);
        check("router output becomes a track", built.steps.length == 2 && built.steps[0].kind == RouteTrack.RIGHT
                && built.steps[1].kind == RouteTrack.ARRIVE);
        Router.Result banned = router.route(n00[0], n00[1], Double.NaN, n30[0], n30[1]);
        check("a banned turn forces a detour", banned.error == null && banned.meters > 400 && banned.meters < 700);
        Router.Result oneWay = router.route(n33[0], n33[1], Double.NaN, n03[0], n03[1]);
        check("a one-way street is respected", oneWay.error == null && oneWay.meters > 450 && oneWay.meters < 560);
        double midLat = 42.0025, midLon = -72.998;
        Router.Result facingNorth = router.route(midLat, midLon, 0, n31[0], n31[1]);
        Router.Result facingSouth = router.route(midLat, midLon, 180, n31[0], n31[1]);
        check("heading picks the side of a two-way road", facingNorth.error == null && facingSouth.error == null
                && facingNorth.meters < 200 && facingSouth.meters > facingNorth.meters + 100);
        Router.Result nowhere = router.route(43.0, -72.0, Double.NaN, n31[0], n31[1]);
        check("no road near the start is reported", "No road near the start".equals(nowhere.error));
        check("a route to the same place is tiny", router.route(n01[0], n01[1], Double.NaN, n01[0], n01[1]).meters < 5
                || router.route(n01[0], n01[1], Double.NaN, n01[0], n01[1]).error != null);

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
