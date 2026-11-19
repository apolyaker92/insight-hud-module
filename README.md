# Insight HUD Module 2.3

An Android HUD for a 2001 Honda Insight (ZE1), built for an Essential PH-1 running LineageOS. The display features segmented MPH, a rolling RPM trace, and gauges for coolant temperature, intake air temperature, OBD voltage, and engine load.

## Repository layout

| Path | What's in it |
| :--- | :--- |
| `android/src/` | The HUD app (Java, no Gradle) |
| `android/res/` | Resources, sounds and the bundled Albany road database |
| `android/test/` | Plain-Java checks, run with `sh android/test/run-tests.sh` on any OS |
| `android/tools/` | Python tools that build and merge the road and routing databases, and their tests |
| `android/system/` | Script that installs the HUD as a privileged system app |
| `ios/` | The SwiftUI companion app |
| `mount/` | The phone cradle (OpenSCAD source and a print plate) |

## In the car

- Set Insight HUD as the phone's default Home app.
- Power the phone from an ignition-switched USB charger. The HUD detects external power even when the phone has reached its charge limit.
- Pair a compatible classic Bluetooth OBD-II adapter in Android, then select it under BT SETUP in the HUD header. Demo mode can preview the dashboard without a connection.
- Use MIRROR for a reflected display and DIM for night viewing.
- Disable Android's charging sound if you want only the HUD chime. The HUD holds a fixed landscape orientation.
- Use SHOW MAP for the offline map, or SHOW GAUGES to switch back. Tap the map to grant precise foreground location or open Android Location settings. The HUD requests GPS while the real map is visible and accessory power is on. After 20 seconds without a usable fix it shows SAT NOT AVAILABLE over the map. DEMO uses a fictional map and simulated gauges.
- Pair a Bluetooth keyboard or keypad and assign F1 to View (full-screen map with speed, then the split map, then speed with the gauges), F2 to Heading, F3 to Range, and F4 to Dim. There is no on-screen button row: touch the BT SETUP box for the settings (view, mirror, demo, brightness, trip log, sound, volume and the rest), tap the map to switch it between full screen and split, tap anywhere else in the frame to step through the views, and use the range and heading boxes on the map. Mirror, demo and brightness live in the settings menu. During a heading transition, the target caption briefly shows the current coordinates, or simulated coordinates in Demo.
- At accessory power-off, the HUD shows a trip report for a drive with live OBD readings. Open TRIP LOG while parked to see the latest 30 trips. Estimated mileage integrates nearby OBD speed samples and skips gaps. Demo reports are never saved.
- Thermal protection dims the display and pauses GPS at a battery reading of 42 °C or Android's severe thermal status. At 46 °C, a battery overheat alert, or Android's critical status, it also disconnects OBD and shows a warning before releasing the screen. Normal operation resumes after cooldown. Android manages charging and device-level thermal shutdown.

On power-up the HUD plays its startup animation and chime and reconnects to the selected adapter. It ignores interruptions shorter than three seconds. A sustained loss closes the Bluetooth OBD session, runs a short standby animation, and blanks the display. Android then sleeps according to its screen timeout. Automatic wake on reconnect depends on the phone's charger-wake behavior. While powered, the HUD can display over Android's lock screen.

## Build the Android app

On Windows, run `./android/build.ps1 -SdkDirectory <path> -BuildDirectory ./work/hud-build` from the repository root in PowerShell with a JDK on PATH. The script expects Android 35's `android.jar` under `android-35` and the Windows build tools, including `lib/d8.jar` and `lib/apksigner.jar`, under `android-15` in that SDK directory. It creates a local development signing key if one is missing. Keep the same signing key for in-place updates.

On macOS or Linux, run `sh android/build.sh <sdk-dir> [platform] [keystore]` instead. It does the same steps with the SDK's build tools and writes `android/insight-hud.apk`.

## Gauges and testing without a car

The right panel shows throttle position, engine load and an estimated MPG (from calculated load, RPM and the Insight's displacement, assuming a stoichiometric mixture, so treat it as relative feedback). Only gauges the car has actually reported appear. Raw read-only requests can be logged for discovery with `adb shell am start -n com.andrew.insighthud/.HudActivity --es obd_probe 2101,2201`; replies appear in logcat under `HudObdProbe`.

Choose the simulated adapter under BT SETUP / Choose paired OBD adapter to drive the real polling and parsing code from a scripted drive cycle. Run `sh android/test/run-tests.sh` for the plain-Java parser and simulator checks (no Android SDK needed).

## Road info

With a detailed map pack installed the HUD shows, from the GPS fix alone: the posted speed limit in a LIMIT box (solid when you are over it, and a quiet OVER LIMIT banner after a few seconds well over), the road and town you are on, the nearest fuel with its distance and direction, a compass heading and a clock in the header. It also looks ahead along your heading and shows the nearest thing worth knowing about: a RAIL CROSSING, a speed or red-light CAMERA or a CURVE (with the speed it advises) each raise a banner, while traffic signals, stop signs, highway exits and rest areas appear in the readout only. Turn it all off with Road info in BT SETUP. Every drive is also logged to `logs/drive-DATE.csv` in the app's external files folder (a row a second: time, position, GPS and OBD speed, rpm, throttle, load, temperatures, mpg, limit and road).

The limits, names, signals, cameras and the rest come from OpenStreetMap, so they are only as good as its volunteers' tagging; many rural roads have no limit, and a camera or crossing that nobody mapped will not warn.

## Navigation (route follower)

The HUD can follow a route that a phone sends it over Wi-Fi. BT SETUP > Navigation shows the HUD's address and a pairing code. POST a route to `http://ADDRESS:8765/route` with the code in an `X-HUD-Token` header (or `?token=CODE`); GET `/status` to check, POST `/cancel` to stop. The body is either the HUD's own JSON (`name`, `duration_s`, `polyline` + `polyline_precision` or `points`, `steps` with `instruction`/`street`/`type`/`modifier` and `at_m` or `location`) or an OSRM-style reply forwarded as received (add `?precision=6` when it was asked for with `geometries=polyline6`). The route is drawn over the map, and a strip shows the next turn's arrow, the distance, the instruction, the miles left and the arrival time; an OFF ROUTE banner appears after about 4 seconds more than 45 m from the line, and ARRIVED near the end. Turning a route into guidance happens on the HUD, so the phone only has to supply the line and steps.

### iPhone companion

Open `ios/InsightHUD.xcodeproj` in Xcode. The app targets iOS 17 or later. Select your development team under Signing & Capabilities to run it on a paired iPhone.

Turn on Wi-Fi hotspot on the Android HUD and join it from the iPhone's Wi-Fi settings. You can also connect both devices to the same Wi-Fi network. Enter the HUD's hotspot address (the iPhone's Wi-Fi Router address) and the pairing code shown under BT SETUP > Navigation, tap **Check connection**, and allow Local Network access. Search for a destination, preview driving directions, and tap **Upload directions**. Search and preview use Apple's online services, so the iPhone needs internet access. **Send destination for offline routing** uses the HUD's GPS and installed routing graph after you select a search result.

The app checks the saved connection when it becomes active and requests brief background time to finish an upload you started. Open the app after the iPhone joins the HUD hotspot. After upload, the HUD handles guidance while the iPhone is locked or the app is closed.

### Routing on the HUD (offline)

`POST /destination` with `{"lat":..,"lon":..,"name":".."}` makes the HUD work out the route itself from its GPS fix, with no internet, using `hud-route.db` in the app's files folder (`/sdcard/Android/data/com.andrew.insighthud/files/`). It reroutes on its own (about 8 seconds after leaving the route) and shows REROUTING. A 25 km Albany trip is found in about a second.

The graph comes from an OpenStreetMap extract: `python android/tools/build_route_graph.py OUT.db SRC.pbf --name NAME --bounds S W N E` (needs `pyosmium` and `numpy`). Edges carry travel times from posted or default speeds plus signal and stop delays, one-ways, and turn restrictions; the router adds turn penalties. Short trips search every road; long ones only main roads away from either end. The tile format is documented at the top of `build_route_graph.py`; `android/test/make_router_tile.py` writes the small grid the router tests use.

Android's per-app firewall must allow the HUD network access (Settings > Apps > Insight HUD > Wi-Fi data usage > Allow network access), and the HUD must be the foreground app to receive routes. The receiver listens on every network interface, so keep it on a network you trust; the pairing code is the only protection.

## Offline road data

The included Albany road database and optional East Coast road pack are derived from [© OpenStreetMap contributors](https://www.openstreetmap.org/copyright) and available under the [Open Database License](https://opendatacommons.org/licenses/odbl/1-0/). The bundled Albany fallback covers 42.49°–42.85° N and 74.03°–73.49° W.

The East Coast pack covers the 14 Atlantic coastal states from Maine through Florida, plus Vermont, Pennsylvania, and Washington, DC. Copy the pack into the app's external files directory as `hud-roads.db` and relaunch the HUD. On the PH-1 the directory is `/sdcard/Android/data/com.andrew.insighthud/files/`. The pack is separate from the APK and repository. The metadata records its source snapshot and extracts.

The whole US can be packed the same way: run `pack_detail.py` once per state PBF (`--bounds -90 -180 90 180`), then merge the state packs with `python pack_pbf_roads.py hud-roads.db --merge --name "UNITED STATES" --center 42.607 -73.700 *.db`. The 50 states plus DC come to about 4 GB, with street names, rail and water everywhere, and replace the East Coast pack as `hud-roads.db`.

For more detail around home, `android/tools/pack_detail.py` builds a local pack from one PBF and a bounding box: street names on every road, rail lines (dotted) and rivers, canals and larger lakes (dashed). Run `python pack_detail.py hud-local.db new-york.osm.pbf --name "CAPITAL DISTRICT" --bounds 42.2 -74.7 43.4 -73.1` (south west north east), then copy `hud-local.db` into the same external files directory as `hud-roads.db`. Inside its bounds the HUD draws from the local pack; outside, it falls back to the wide pack. The 13 MB Capital District pack covers Albany, Schenectady, Troy, Saratoga and the Hudson valley south to Catskill.

Use `android/tools/pack_pbf_roads.py` with Pyosmium to convert one [Geofabrik PBF extract](https://download.geofabrik.de/north-america/us.html) at a time. Run it again with `--merge` and the converted databases to build a combined road pack. Pass `--name` and choose a default view with `--center LAT LON`.

Use `android/tools/pack_roads.py` to turn an Overpass JSON road export with `out geom` into an indexed road database. Pass `--bounds S W N E --center LAT LON --name NAME`. When bundling a new database in the APK, update the copied database filename in `OfflineRoadMap.java` so existing installs pick up the new pack.

## Print the cradle

Open `mount/ph1_cradle_complete_plate.stl` in Bambu Studio. The plate has the cradle and both keepers. Assembly uses two M3 × 8 mm screws and two M3 hex nuts. Edit the dimensions or export individual parts from `mount/ph1_cradle.scad` in OpenSCAD.
