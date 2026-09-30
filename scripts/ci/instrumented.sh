#!/usr/bin/env bash
# CI: installs the debug + test APKs on the running emulator and runs every instrumented test
# except the perf suite. Fails the job if any test fails.
# Progress is streamed (no buffering filter in the pipe). A test that takes longer than
# TEST_TIMEOUT_MS fails with the stack of where it was stuck, and the run goes on; the whole
# run is stopped after TEST_TIMEOUT.
# Env: APKS (folder with the APKs, default "apks"), OUT (results folder, default "results"),
#      TEST_TIMEOUT (default 30m), TEST_TIMEOUT_MS (per test, default 300000).
set -uo pipefail

APKS=${APKS:-apks}
OUT=${OUT:-results}
TEST_TIMEOUT=${TEST_TIMEOUT:-30m}
TEST_TIMEOUT_MS=${TEST_TIMEOUT_MS:-300000}
mkdir -p "$OUT"

adb install -r -t "$APKS/app-debug.apk"
adb install -r -t "$APKS/app-debug-androidTest.apk"

api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "Running instrumented tests on API $api (limit $TEST_TIMEOUT)"
raw="$OUT/instrumented-api$api.raw.txt"
timeout "$TEST_TIMEOUT" adb shell am instrument -w -e notClass com.lekaspos.perf.PerfSuiteTest -e timeout_msec "$TEST_TIMEOUT_MS" \
  com.lekaspos.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$raw"
status=${PIPESTATUS[0]}
tr -d '\r' < "$raw" > "$OUT/instrumented-api$api.txt"

if [ "$status" -eq 124 ]; then
  echo "Instrumented tests did not finish within $TEST_TIMEOUT"
fi
adb logcat -d -v time > "$OUT/logcat-api$api.txt" 2>/dev/null || true
# Screenshots from ScreenshotsTest (layout review in both languages).
adb pull /sdcard/Android/data/com.lekaspos.app.debug/files/screens "$OUT/screens-api$api" > /dev/null 2>&1 || true

if grep -q '^OK (' "$OUT/instrumented-api$api.txt"; then
  summary=$(grep '^OK (' "$OUT/instrumented-api$api.txt")
  echo "### Instrumented tests, API $api: $summary" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  exit 0
fi
echo "### Instrumented tests, API $api: FAILED" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
exit 1
