# Insight HUD Module 2.3

An Android HUD for a 2001 Honda Insight (ZE1), built for an Essential PH-1 running LineageOS. The display features segmented MPH, a rolling RPM trace, and gauges for coolant temperature, intake air temperature, OBD voltage, and engine load.

## In the car

- Set Insight HUD as the phone's default Home app.
- Power the phone from an ignition-switched USB charger. The HUD detects external power even when the phone has reached its charge limit.
- Pair a compatible classic Bluetooth OBD-II adapter in Android, then select it under BT SETUP in the HUD header. Demo mode can preview the dashboard without a connection.
- Use MIRROR for a reflected display and DIM for night viewing.
- Disable Android's charging sound if you want only the HUD chime. The HUD holds a fixed landscape orientation.
- Use SHOW MAP for the offline map, or SHOW GAUGES to switch back. Tap the map to grant precise foreground location or open Android Location settings. The HUD requests GPS while the real map is visible and accessory power is on. After 20 seconds without a usable fix it shows SAT NOT AVAILABLE over the map. DEMO uses a fictional map and simulated gauges.
- Pair a Bluetooth keyboard or keypad and assign F1 to View, F2 to Heading, F3 to Range, and F4 to Dim. The map header also has touch controls for range and heading. During a heading transition, the target caption briefly shows the current coordinates, or simulated coordinates in Demo.
- At accessory power-off, the HUD shows a trip report for a drive with live OBD readings. Open TRIP LOG while parked to see the latest 30 trips. Estimated mileage integrates nearby OBD speed samples and skips gaps. Demo reports are never saved.
- Thermal protection dims the display and pauses GPS at a battery reading of 42 °C or Android's severe thermal status. At 46 °C, a battery overheat alert, or Android's critical status, it also disconnects OBD and shows a warning before releasing the screen. Normal operation resumes after cooldown. Android manages charging and device-level thermal shutdown.

On power-up the HUD plays its startup animation and chime and reconnects to the selected adapter. It ignores interruptions shorter than three seconds. A sustained loss closes the Bluetooth OBD session, runs a short standby animation, and blanks the display. Android then sleeps according to its screen timeout. Automatic wake on reconnect depends on the phone's charger-wake behavior. While powered, the HUD can display over Android's lock screen.

## Build the Android app

Run `./android/build.ps1 -SdkDirectory <path> -BuildDirectory ./work/hud-build` from the repository root in PowerShell with a JDK on PATH. The script expects Android 35's `android.jar` under `android-35` and the Windows build tools, including `lib/d8.jar` and `lib/apksigner.jar`, under `android-15` in that SDK directory. It creates a local development signing key if one is missing. Keep the same signing key for in-place updates.

## Gauges and testing without a car

The right panel shows throttle position, engine load and an estimated MPG (from calculated load, RPM and the Insight's displacement, assuming a stoichiometric mixture, so treat it as relative feedback). Only gauges the car has actually reported appear. Raw read-only requests can be logged for discovery with `adb shell am start -n com.andrew.insighthud/.HudActivity --es obd_probe 2101,2201`; replies appear in logcat under `HudObdProbe`.

Choose the simulated adapter under BT SETUP / Choose paired OBD adapter to drive the real polling and parsing code from a scripted drive cycle. Run `sh android/test/run-tests.sh` for the plain-Java parser and simulator checks (no Android SDK needed).

## Offline road data

The included Albany road database and optional East Coast road pack are derived from [© OpenStreetMap contributors](https://www.openstreetmap.org/copyright) and available under the [Open Database License](https://opendatacommons.org/licenses/odbl/1-0/). The bundled Albany fallback covers 42.49°–42.85° N and 74.03°–73.49° W.

The East Coast pack covers the 14 Atlantic coastal states from Maine through Florida, plus Vermont, Pennsylvania, and Washington, DC. Copy the pack into the app's external files directory as `hud-roads.db` and relaunch the HUD. On the PH-1 the directory is `/sdcard/Android/data/com.andrew.insighthud/files/`. The pack is separate from the APK and repository. The metadata records its source snapshot and extracts.

Use `android/pack_pbf_roads.py` with Pyosmium to convert one [Geofabrik PBF extract](https://download.geofabrik.de/north-america/us.html) at a time. Run it again with `--merge` and the converted databases to build a combined road pack. Pass `--name` and choose a default view with `--center LAT LON`.

Use `android/pack_roads.py` to turn an Overpass JSON road export with `out geom` into an indexed road database. Pass `--bounds S W N E --center LAT LON --name NAME`. When bundling a new database in the APK, update the copied database filename in `OfflineRoadMap.java` so existing installs pick up the new pack.

## Print the cradle

Open `mount/ph1_cradle_complete_plate.stl` in Bambu Studio. The plate has the cradle and both keepers. Assembly uses two M3 × 8 mm screws and two M3 hex nuts. Edit the dimensions or export individual parts from `mount/ph1_cradle.scad` in OpenSCAD.
