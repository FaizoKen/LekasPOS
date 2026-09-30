#!/usr/bin/env bash
# CI: installs the debug + test APKs on the running emulator and runs every instrumented test
# except the perf suite. Fails the job if any test fails.
# Progress is streamed (no buffering filter in the pipe). If the run takes longer than
# TEST_TIMEOUT, the app's thread stacks are dumped (traces-api<N>.txt) to show what hangs.
# Env: APKS (folder with the APKs, default "apks"), OUT (results folder, default "results"),
#      TEST_TIMEOUT (default 30m).
set -uo pipefail

APKS=${APKS:-apks}
OUT=${OUT:-results}
TEST_TIMEOUT=${TEST_TIMEOUT:-30m}
mkdir -p "$OUT"

adb install -r -t "$APKS/app-debug.apk"
adb install -r -t "$APKS/app-debug-androidTest.apk"

api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "Running instrumented tests on API $api (limit $TEST_TIMEOUT)"
raw="$OUT/instrumented-api$api.raw.txt"
timeout "$TEST_TIMEOUT" adb shell am instrument -w -e notClass com.lekaspos.perf.PerfSuiteTest \
  com.lekaspos.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$raw"
status=${PIPESTATUS[0]}
tr -d '\r' < "$raw" > "$OUT/instrumented-api$api.txt"

if [ "$status" -eq 124 ]; then
  echo "Instrumented tests did not finish within $TEST_TIMEOUT: dumping thread stacks"
  adb root > /dev/null 2>&1 || true
  sleep 2
  timeout 60 adb wait-for-device || true
  sleep 2
  pid=$(adb shell ps | tr -d '\r' | awk '$NF == "com.lekaspos.app.debug" { print $2 }' | head -1)
  if [ -n "$pid" ]; then
    adb shell kill -3 "$pid" || true
    sleep 10
    adb shell cat /data/anr/traces.txt > "$OUT/traces-api$api.txt" 2>/dev/null || true
    # The stacks of the test and database threads, in the job log.
    grep -n -A 25 -E '^"(Instr|db-writer|DefaultDispatcher|main)' "$OUT/traces-api$api.txt" | head -300 || true
  fi
fi
adb logcat -d -v time > "$OUT/logcat-api$api.txt" 2>/dev/null || true

if grep -q '^OK (' "$OUT/instrumented-api$api.txt"; then
  summary=$(grep '^OK (' "$OUT/instrumented-api$api.txt")
  echo "### Instrumented tests, API $api: $summary" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  exit 0
fi
echo "### Instrumented tests, API $api: FAILED" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
exit 1
