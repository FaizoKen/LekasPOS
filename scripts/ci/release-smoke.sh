#!/usr/bin/env bash
# CI: the release (R8) build must start and keep running, fresh and as an upgrade — the
# instrumented tests only exercise the debug build. Installs the previous tester build (if
# PREV_APK_URL is set), opens it, installs the new release APK over it, opens the selling screen
# and waits RUN_SECONDS (background jobs start ~1.5 s after the screen is usable). Fails with
# the crash log if the app died. Then does the same once more after "Clear data" (fresh install).
# Env: APKS (default "apks"), OUT (default "results"), PREV_APK_URL, RUN_SECONDS (default 20).
set -uo pipefail

APKS=${APKS:-apks}
OUT=${OUT:-results}
RUN_SECONDS=${RUN_SECONDS:-20}
PKG=com.lekaspos.app
ACT=$PKG/com.lekaspos.ui.sell.SellActivity
mkdir -p "$OUT"
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
failed=0

app_pid() { adb shell ps | tr -d '\r' | awk -v p="$PKG" '$NF == p { print $2 }' | head -1; }

# Opens the selling screen, waits, reports whether the process is still alive.
run_app() {
  local label=$1
  adb logcat -c
  adb logcat -b crash -c 2>/dev/null || true
  adb shell am start -W -n "$ACT" > /dev/null
  sleep "$RUN_SECONDS"
  local pid
  pid=$(app_pid)
  adb logcat -d -v time > "$OUT/release-smoke-$label-api$api.txt" 2>/dev/null || true
  if [ -z "$pid" ] || grep -q "FATAL EXCEPTION" "$OUT/release-smoke-$label-api$api.txt"; then
    echo "Release smoke ($label, API $api): the app died"
    grep -A 40 "FATAL EXCEPTION" "$OUT/release-smoke-$label-api$api.txt" | head -80
    echo "### Release smoke ($label), API $api: CRASHED" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
    failed=1
  else
    echo "Release smoke ($label, API $api): running after $RUN_SECONDS s"
    echo "### Release smoke ($label), API $api: ok" >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
  fi
  adb shell am force-stop "$PKG"
}

adb uninstall "$PKG" > /dev/null 2>&1 || true
if [ -n "${PREV_APK_URL:-}" ] && curl -fsSL -o "$OUT/prev.apk" "$PREV_APK_URL"; then
  adb install -r "$OUT/prev.apk" > /dev/null && run_app previous
fi
adb install -r "$APKS/app-release.apk"
run_app upgrade
adb shell pm clear "$PKG" > /dev/null
run_app fresh
exit $failed
