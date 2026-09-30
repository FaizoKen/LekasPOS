#!/usr/bin/env bash
# CI: performance run on the running emulator (see references/performance.md):
#   1. PerfSuiteTest (debug build, no UI) — query-plan checks enforced, timings reported;
#   2. the release build's in-app runner (Diagnostics autorun) — the real-device path;
#   3. cold start of the release build (10 launches).
# Usage: scripts/ci/perf.sh [TINY|QUICK|FULL]
# Env: APKS (default "apks"), OUT (default "perf-results").
set -uo pipefail

SCALE=${1:-QUICK}
APKS=${APKS:-apks}
OUT=${OUT:-perf-results}
PKG=com.lekaspos.app
DEBUG_PKG=com.lekaspos.app.debug
mkdir -p "$OUT"
summary=${GITHUB_STEP_SUMMARY:-/dev/null}
status=0

adb root >/dev/null 2>&1 || true   # AOSP images: lets us pull reports from /data/data
sleep 3
adb wait-for-device
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')

adb install -r -t "$APKS/app-debug.apk"
adb install -r -t "$APKS/app-debug-androidTest.apk"
adb install -r "$APKS/app-release.apk"

app_pid() { # toybox pidof (API 23+), else toolbox ps (API 21-22). Old adb shells merge stderr
  # into stdout ("/system/bin/sh: pidof: not found"), so only a number counts as a PID.
  local p
  p=$(adb shell pidof "$1" 2>/dev/null | tr -d '\r' | awk '{print $1}')
  case "$p" in '' | *[!0-9]*) p=$(adb shell ps 2>/dev/null | tr -d '\r' | awk -v n="$1" '$NF==n {print $2; exit}') ;; esac
  case "$p" in '' | *[!0-9]*) p="" ;; esac
  echo "$p"
}

echo "## Performance, API $api, scale $SCALE" >> "$summary"

# 1. Instrumented suite (debug build, no UI).
adb shell am instrument -w -e class com.lekaspos.perf.PerfSuiteTest -e perfScale "$SCALE" \
  com.lekaspos.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$OUT/instrumented-api$api.txt"
# The test writes to external app storage when present (newer images), else internal storage.
adb pull "/sdcard/Android/data/$DEBUG_PKG/files/perf/instrumented-$SCALE.json" "$OUT/instrumented-$SCALE-api$api.json" >/dev/null 2>&1 ||
  adb pull "/data/data/$DEBUG_PKG/files/perf/instrumented-$SCALE.json" "$OUT/instrumented-$SCALE-api$api.json" >/dev/null 2>&1 || true
if grep -q '^OK (' "$OUT/instrumented-api$api.txt"; then
  echo "- Instrumented suite (debug, no UI): **passed** (query plans enforced)" >> "$summary"
else
  echo "- Instrumented suite (debug, no UI): **FAILED**" >> "$summary"
  status=1
fi

# 2. Release build through the in-app runner.
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/com.lekaspos.ui.diag.DiagnosticsActivity" --es autorun "$SCALE" >/dev/null
pid=""
for _ in $(seq 1 30); do sleep 1; pid=$(app_pid "$PKG"); [ -n "$pid" ] && break; done
echo "Release suite running in pid ${pid:-<none>}"
done_line=""
deadline=$(( $(date +%s) + 90 * 60 ))
[ -z "$pid" ] && deadline=0   # the app did not start: skip waiting, report below
while [ "$(date +%s)" -lt "$deadline" ]; do
  sleep 15
  done_line=$(adb logcat -d -v brief -s LekasPerf:I 2>/dev/null | tr -d '\r' | grep -E "\(\s*$pid\): DONE" | tail -1)
  [ -n "$done_line" ] && break
  if [ "$(app_pid "$PKG")" != "$pid" ]; then
    echo "Release app process $pid died" | tee -a "$OUT/release-crash-api$api.txt"
    adb logcat -d -b crash -v time >> "$OUT/release-crash-api$api.txt" 2>/dev/null || true
    break
  fi
done
if [ -n "$done_line" ]; then
  echo "$done_line"
  remote=$(echo "$done_line" | awk '{print $NF}')
  case "$remote" in
    *.json)
      adb pull "$remote" "$OUT/release-$SCALE-api$api.json" >/dev/null 2>&1 || true
      adb pull "${remote%.json}.txt" "$OUT/release-$SCALE-api$api.txt" >/dev/null 2>&1 || true ;;
  esac
  if [ ! -s "$OUT/release-$SCALE-api$api.txt" ]; then
    adb logcat -d -v brief -s LekasPerf:I | tr -d '\r' | grep -E "\(\s*$pid\)" | sed -E 's/^.*LekasPerf\(\s*[0-9]+\): ?//' > "$OUT/release-$SCALE-api$api.txt"
  fi
  { echo '```'; cat "$OUT/release-$SCALE-api$api.txt"; echo '```'; } >> "$summary"
  case "$done_line" in *"DONE PASS"*) ;; *) status=1 ;; esac
else
  echo "- Release suite: **no result** (see release-crash-api$api.txt)" >> "$summary"
  status=1
fi

# 3. Cold start of the release build: launch-to-first-frame and launch-to-usable (reportFullyDrawn).
act="$PKG/com.lekaspos.ui.sell.SellActivity"
: > "$OUT/startup-api$api.txt"
# A fresh install shows the first-run welcome screen once (it marks itself done when it opens);
# get it out of the way first so it does not take part in the timed launches.
adb shell am start -W -n "$act" > /dev/null
sleep 6
adb shell input keyevent KEYCODE_BACK
sleep 2
for i in $(seq 1 10); do
  adb shell am force-stop "$PKG"
  sleep 2
  total=$(adb shell am start -S -W -n "$act" | tr -d '\r' | awk '/TotalTime/ {print $2}')
  sleep 3
  drawn=$(adb logcat -d -v brief 2>/dev/null | tr -d '\r' | grep "Fully drawn $act" | tail -1 |
    sed -E 's/.*\+(([0-9]+)s)?([0-9]+)ms.*/\2 \3/' | awk '{ if (NF == 2) print $1 * 1000 + $2; else print $1 }')
  echo "run $i: first_frame_ms=$total fully_drawn_ms=$drawn" | tee -a "$OUT/startup-api$api.txt"
done
median() { sort -n | awk '{a[NR]=$1} END { if (NR) print a[int((NR + 1) / 2)] }'; }
ff=$(sed -E 's/.*first_frame_ms=([0-9]+).*/\1/' "$OUT/startup-api$api.txt" | grep -E '^[0-9]+$' | median)
fd=$(sed -E 's/.*fully_drawn_ms=([0-9]+).*/\1/' "$OUT/startup-api$api.txt" | grep -E '^[0-9]+$' | median)
echo "- Cold start (release, median of 10): first frame **${ff:-?} ms**, usable **${fd:-?} ms** (budget 2000 ms)" >> "$summary"
if [ -n "$fd" ] && [ "$fd" -ge 2000 ]; then status=1; fi

exit $status
