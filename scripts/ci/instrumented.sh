#!/usr/bin/env bash
# CI: installs the debug + test APKs on the running emulator and runs every instrumented test
# except the perf suite. Fails the job if any test fails.
# Env: APKS (folder with the APKs, default "apks"), OUT (results folder, default "results").
set -uo pipefail

APKS=${APKS:-apks}
OUT=${OUT:-results}
mkdir -p "$OUT"

adb install -r -t "$APKS/app-debug.apk"
adb install -r -t "$APKS/app-debug-androidTest.apk"

api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "Running instrumented tests on API $api"
adb shell am instrument -w -e notClass com.lekaspos.perf.PerfSuiteTest \
  com.lekaspos.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$OUT/instrumented-api$api.txt"
adb logcat -d -v time > "$OUT/logcat-api$api.txt" 2>/dev/null || true

if grep -q '^OK (' "$OUT/instrumented-api$api.txt"; then
  summary=$(grep '^OK (' "$OUT/instrumented-api$api.txt")
  echo "### Instrumented tests, API $api: $summary" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  exit 0
fi
echo "### Instrumented tests, API $api: FAILED" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
exit 1
