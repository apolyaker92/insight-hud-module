#!/bin/sh
# Usage: sh android/build.sh <sdk-dir> [platform] [keystore]
# Needs a JDK on PATH. The SDK must have build-tools and platforms/android-<platform>.
set -e
here="$(cd "$(dirname "$0")" && pwd)"
sdk="${1:?usage: build.sh <sdk-dir> [platform] [keystore]}"
platform="${2:-36}"
keystore="${3:-$sdk/hud-development.keystore}"
tools="$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)"
jar="$sdk/platforms/android-$platform/android.jar"
build="${BUILD_DIR:-$here/../work/hud-build}"
rm -rf "$build"
mkdir -p "$build/classes" "$build/dex" "$build/resources"

"$tools/aapt" package -f -M "$here/AndroidManifest.xml" -S "$here/res" -J "$build/resources" -I "$jar" -F "$build/unsigned.apk"
javac --release 8 -classpath "$jar" -d "$build/classes" $(find "$here/src" "$build/resources" -name '*.java')
jar cf "$build/classes.jar" -C "$build/classes" .
"$tools/d8" --lib "$jar" --min-api 28 --output "$build/dex" "$build/classes.jar"
jar uf "$build/unsigned.apk" -C "$build/dex" classes.dex
"$tools/zipalign" -f 4 "$build/unsigned.apk" "$build/aligned.apk"

if [ ! -f "$keystore" ]; then
    keytool -genkeypair -keystore "$keystore" -storepass android -keypass android -alias insight-hud \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Insight HUD Development"
fi
"$tools/apksigner" sign --ks "$keystore" --ks-pass pass:android --key-pass pass:android --out "$here/insight-hud.apk" "$build/aligned.apk"
"$tools/apksigner" verify --verbose "$here/insight-hud.apk"
echo "Built $here/insight-hud.apk"
