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

# Screenshots from ScreenshotsTest (layout review in both languages): external app files, or
# internal files through run-as (debug build) where the emulator has no external storage.
shots="$OUT/screens-api$api"
adb pull /sdcard/Android/data/com.lekaspos.app.debug/files/screens "$shots" > /dev/null 2>&1 || true
if [ ! -d "$shots" ]; then
  mkdir -p "$shots"
  for f in $(adb shell run-as com.lekaspos.app.debug ls files/screens 2>/dev/null | tr -d '\r'); do
    adb exec-out run-as com.lekaspos.app.debug cat "files/screens/$f" > "$shots/$f"
  done
fi

# A leak test that failed saved a heap dump (files/leaks, internal): print who holds the leaked
# objects with LeakCanary's shark-cli (a CI-only diagnostic, never part of the app). The dump
# itself is large and not kept.
leaks="$OUT/leaks-api$api"
for f in $(adb shell run-as com.lekaspos.app.debug ls files/leaks 2>/dev/null | tr -d '\r'); do
  mkdir -p "$leaks"
  adb exec-out run-as com.lekaspos.app.debug cat "files/leaks/$f" > "$leaks/$f"
done
if ls "$leaks"/*.hprof > /dev/null 2>&1; then
  curl -sSfL -o /tmp/shark.zip https://github.com/square/leakcanary/releases/download/v2.14/shark-cli-2.14.zip \
    && unzip -q -o /tmp/shark.zip -d /tmp/shark
  shark=$(find /tmp/shark -path '*/bin/shark-cli' -type f | head -1)
  for h in "$leaks"/*.hprof; do
    if [ -n "$shark" ]; then
      echo "Analysing $h"
      timeout 10m "$shark" --hprof "$h" analyze > "${h%.hprof}.txt" 2>&1 || true
      grep -n "LEAK\|│\|├\|╰\|┬" "${h%.hprof}.txt" | head -60 || true
    fi
    rm -f "$h"
  done
fi

if grep -q '^OK (' "$OUT/instrumented-api$api.txt"; then
  summary=$(grep '^OK (' "$OUT/instrumented-api$api.txt")
  echo "### Instrumented tests, API $api: $summary" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  exit 0
fi
echo "### Instrumented tests, API $api: FAILED" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
exit 1
