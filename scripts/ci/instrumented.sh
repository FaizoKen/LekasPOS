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
# The log is streamed while the tests run: a run that froze the emulator (adb answered nothing
# afterwards) left an empty log when it was read only at the end.
adb logcat -v time > "$OUT/logcat-api$api.txt" 2>/dev/null &
logcat_pid=$!
timeout "$TEST_TIMEOUT" adb shell am instrument -w -e notClass com.lekaspos.perf.PerfSuiteTest -e timeout_msec "$TEST_TIMEOUT_MS" \
  com.lekaspos.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$raw"
status=${PIPESTATUS[0]}
tr -d '\r' < "$raw" > "$OUT/instrumented-api$api.txt"

if [ "$status" -eq 124 ]; then
  echo "Instrumented tests did not finish within $TEST_TIMEOUT"
fi
kill "$logcat_pid" 2>/dev/null || true

# Screenshots from ScreenshotsTest (layout review in both languages): external app files, or
# internal files through run-as (debug build) where the emulator has no external storage.
shots="$OUT/screens-api$api"
# Bounded: a frozen emulator answers nothing, and the job's own limit cut the upload of what was there.
timeout 120 adb pull /sdcard/Android/data/com.lekaspos.app.debug/files/screens "$shots" > /dev/null 2>&1 || true
if [ ! -d "$shots" ]; then
  mkdir -p "$shots"
  for f in $(timeout 60 adb shell run-as com.lekaspos.app.debug ls files/screens 2>/dev/null | tr -d '\r'); do
    timeout 60 adb exec-out run-as com.lekaspos.app.debug cat "files/screens/$f" > "$shots/$f"
  done
fi

# The shop ManagerFixtureTest made (sync folder + both tills' backup files): the manager web page's tests
# read it after this script (ci.yml, scripts/manager/fixture.test.mjs, D-082). One flat folder.
fixture="$OUT/manager-fixture-api$api"
timeout 120 adb pull /sdcard/Android/data/com.lekaspos.app.debug/files/manager-fixture "$fixture" > /dev/null 2>&1 || true
if [ ! -d "$fixture" ] || [ -z "$(ls -A "$fixture" 2>/dev/null)" ]; then
  mkdir -p "$fixture"
  for f in $(timeout 60 adb shell run-as com.lekaspos.app.debug ls files/manager-fixture 2>/dev/null | tr -d '\r'); do
    timeout 60 adb exec-out run-as com.lekaspos.app.debug cat "files/manager-fixture/$f" > "$fixture/$f"
  done
fi
echo "Manager fixture: $(ls "$fixture" 2>/dev/null | wc -l) files"

# The manager web page changes that shop (D-083): scripts/manager/web-edits.mjs saves a manager's day of changes into
# the folder with the page's own code; the new files go to the app's internal files (run-as: written as the app), and
# ManagerEditsTest has both tills read them with the app's own sync. Their backup files afterwards come back here for
# scripts/manager/fixture.test.mjs. Any failure fails the job.
edits_ok=1
pkg=com.lekaspos.app.debug
if [ -f "$fixture/till-a.lekasbak" ]; then
  if node scripts/manager/web-edits.mjs "$fixture"; then
    timeout 60 adb shell run-as $pkg mkdir -p files/web-edits
    files=$(node -e "console.log(JSON.parse(require('fs').readFileSync(process.argv[1], 'utf8')).files.join(' '))" "$fixture/web-edits.json")
    for f in $files web-edits.json; do
      timeout 60 adb exec-in "run-as $pkg sh -c 'cat > files/web-edits/$f'" < "$fixture/$f" || edits_ok=0
    done
    echo "Web page's changes on the emulator: $(timeout 60 adb shell run-as $pkg ls files/web-edits | wc -l) files"
    edits_raw="$OUT/manager-edits-api$api.raw.txt"
    timeout 15m adb shell am instrument -w -e class com.lekaspos.sync.ManagerEditsTest -e timeout_msec "$TEST_TIMEOUT_MS" \
      $pkg.test/androidx.test.runner.AndroidJUnitRunner | tee "$edits_raw"
    tr -d '\r' < "$edits_raw" > "$OUT/manager-edits-api$api.txt"
    grep -q '^OK (1 test)' "$OUT/manager-edits-api$api.txt" || edits_ok=0
    for name in till-a-edited.lekasbak till-b-edited.lekasbak; do
      timeout 120 adb pull "/sdcard/Android/data/$pkg/files/manager-fixture/$name" "$fixture/$name" > /dev/null 2>&1 || true
      if [ ! -s "$fixture/$name" ]; then
        timeout 120 adb exec-out run-as $pkg cat "files/manager-fixture/$name" > "$fixture/$name"
      fi
      [ -s "$fixture/$name" ] || edits_ok=0
    done
  else
    edits_ok=0
  fi
  echo "Manager web page's changes read by the tills: $([ "$edits_ok" -eq 1 ] && echo yes || echo FAILED)"
fi

# A leak test that failed saved a heap dump (files/leaks, internal): print who holds the leaked
# objects with LeakCanary's shark-cli (a CI-only diagnostic, never part of the app). The dump
# itself is large and not kept.
leaks="$OUT/leaks-api$api"
for f in $(timeout 60 adb shell run-as com.lekaspos.app.debug ls files/leaks 2>/dev/null | tr -d '\r'); do
  mkdir -p "$leaks"
  timeout 300 adb exec-out run-as com.lekaspos.app.debug cat "files/leaks/$f" > "$leaks/$f"
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

if grep -q '^OK (' "$OUT/instrumented-api$api.txt" && [ "$edits_ok" -eq 1 ]; then
  summary=$(grep '^OK (' "$OUT/instrumented-api$api.txt")
  echo "### Instrumented tests, API $api: $summary; the web page's changes read by both tills" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  exit 0
fi
echo "### Instrumented tests, API $api: FAILED" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
exit 1
