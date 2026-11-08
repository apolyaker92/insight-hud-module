#!/bin/sh
# Compiles and runs the pure-Java checks (no Android SDK needed).
set -e
cd "$(dirname "$0")/.."
out="${TMPDIR:-/tmp}/hud-tests"
rm -rf "$out"
mkdir -p "$out"
javac -d "$out" src/com/andrew/insighthud/ObdProtocol.java src/com/andrew/insighthud/SimulatedElm.java \
    src/com/andrew/insighthud/AutoBrightness.java src/com/andrew/insighthud/Alerts.java src/com/andrew/insighthud/AutoStopTracker.java src/com/andrew/insighthud/SoundPolicy.java src/com/andrew/insighthud/SpeedSource.java \
    test/com/andrew/insighthud/ObdTests.java
java -cp "$out" com.andrew.insighthud.ObdTests
