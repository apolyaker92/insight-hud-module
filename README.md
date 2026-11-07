# Insight HUD Module 2.3

An Android HUD for a 2001 Honda Insight (ZE1), built for an Essential PH-1 running LineageOS. The display features segmented MPH, a rolling RPM trace, and gauges for coolant temperature, intake air temperature, OBD voltage, and engine load.

## In the car

- Set Insight HUD as the phone's default Home app.
- Power the phone from an ignition-switched USB charger. The HUD detects external power even when the phone has reached its charge limit.
- Pair a compatible classic Bluetooth OBD-II adapter in Android, then select it in the HUD's LINK menu. Demo mode can preview the dashboard without a connection.
- Use MIRROR for a reflected display and DIM for night viewing.
- Disable Android's charging sound if you want only the HUD chime. The HUD holds a fixed landscape orientation.

On power-up the HUD plays its startup animation and chime and reconnects to the selected adapter. It ignores interruptions shorter than three seconds. A sustained loss closes the Bluetooth OBD session, runs a short standby animation, and blanks the display. Android then sleeps according to its screen timeout. Automatic wake on reconnect depends on the phone's charger-wake behavior. While powered, the HUD can display over Android's lock screen.

## Build the Android app

Run `./android/build.ps1 -SdkDirectory <path> -BuildDirectory ./work/hud-build` from the repository root in PowerShell with a JDK on PATH. The script expects Android 35's `android.jar` under `android-35` and the Windows build tools, including `lib/d8.jar` and `lib/apksigner.jar`, under `android-15` in that SDK directory. It creates a local development signing key if one is missing. Keep the same signing key for in-place updates.

## Print the cradle

Open `mount/ph1_cradle_complete_plate.stl` in Bambu Studio. The plate has the cradle and both keepers. Assembly uses two M3 × 8 mm screws and two M3 hex nuts. Edit the dimensions or export individual parts from `mount/ph1_cradle.scad` in OpenSCAD.
