#!/usr/bin/env bash
# CI: tablet layout check. On a tablet-sized emulator turned to landscape (the usual counter
# setup), opens every screen (ScreensSmokeTest) and saves the main screens in both languages
# (ScreenshotsTest). Fails if a screen does not open. Screenshots go to $OUT/screens-tablet.
# Env: APKS (default "apks"), OUT (default "results").
set -uo pipefail

APKS=${APKS:-apks}
OUT=${OUT:-results}
PKG=com.lekaspos.app.debug
mkdir -p "$OUT"

adb install -r -t "$APKS/app-debug.apk"
adb install -r -t "$APKS/app-debug-androidTest.apk"

# Landscape, fixed: rotate only when the screen is naturally portrait (pixel_tablet is landscape).
size=$(adb shell wm size | tr -d '\r' | awk '/Physical/ {print $3}')
w=${size%x*}; h=${size#*x}
rotation=0; [ "${h:-0}" -gt "${w:-0}" ] && rotation=1
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation $rotation
adb shell wm size | tee "$OUT/tablet-display.txt"
adb shell wm density | tee -a "$OUT/tablet-display.txt"

adb shell am instrument -w \
  -e class com.lekaspos.ui.ScreensSmokeTest,com.lekaspos.ui.ScreenshotsTest -e timeout_msec 300000 \
  $PKG.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/tablet-tests.raw.txt"
tr -d '\r' < "$OUT/tablet-tests.raw.txt" > "$OUT/tablet-tests.txt"

shots="$OUT/screens-tablet"
adb pull /sdcard/Android/data/$PKG/files/screens "$shots" > /dev/null 2>&1 || true
if [ ! -d "$shots" ]; then
  mkdir -p "$shots"
  for f in $(adb shell run-as $PKG ls files/screens 2>/dev/null | tr -d '\r'); do
    adb exec-out run-as $PKG cat "files/screens/$f" > "$shots/$f"
  done
fi

if grep -q '^OK (' "$OUT/tablet-tests.txt"; then
  echo "### Tablet (landscape): $(grep '^OK (' "$OUT/tablet-tests.txt")" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  exit 0
fi
echo "### Tablet (landscape): FAILED" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
exit 1
