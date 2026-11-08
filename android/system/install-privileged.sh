#!/bin/sh
# Installs the built HUD as a privileged system app so it can switch Bluetooth off while parked.
# Needs: adb root, an unlocked/dm-verity-free system partition (PH-1 LineageOS). Redo after every LineageOS update.
# Usage: sh install-privileged.sh [serial]
set -e
cd "$(dirname "$0")"
A="adb${1:+ -s $1}"
export MSYS_NO_PATHCONV=1
$A root >/dev/null; sleep 2
$A install -r ../insight-hud.apk
$A shell "mount -o remount,rw / && mkdir -p /system/priv-app/InsightHud"
$A push ../insight-hud.apk /system/priv-app/InsightHud/InsightHud.apk
$A push privapp-permissions-insighthud.xml /system/etc/permissions/privapp-permissions-insighthud.xml
$A shell "chmod 755 /system/priv-app/InsightHud; chmod 644 /system/priv-app/InsightHud/InsightHud.apk /system/etc/permissions/privapp-permissions-insighthud.xml; chown -R root:root /system/priv-app/InsightHud; chcon -R u:object_r:system_file:s0 /system/priv-app/InsightHud /system/etc/permissions/privapp-permissions-insighthud.xml; sync; mount -o remount,ro /"
echo "Rebooting; after boot, 'dumpsys package com.andrew.insighthud' should show PRIVILEGED."
$A reboot
