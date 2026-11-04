#!/usr/bin/env bash
# Stress test 4 (plan, Phase 4): rotate portrait/landscape and background/foreground the app N times in a row while textures stream in and out
# of an AssetManager, reading the picture back after every step. Prints the app's own verdict (LIFECYCLE PASS/FAIL) and exits 0 on PASS.
#
#   tools/stress4.sh [cycles=50] [--tier-b] [--no-prerotate] [--no-stream]
#
# Needs an adb device. Each cycle takes about 5 seconds (the covering activity stays up 1.5 s so Android really destroys the surface).
# Split-screen is not driven by this script: adb cannot toggle it reliably; do a few by hand while it runs.
set -euo pipefail

CYCLES=50
EXTRA=()
for a in "$@"; do
  case "$a" in
    --tier-b) EXTRA+=(--ez forceTierB true) ;;
    --no-prerotate) EXTRA+=(--ez preRotate false) ;;
    --no-stream) EXTRA+=(--ez stream false) ;;
    ''|*[!0-9]*) echo "unknown argument: $a" >&2; exit 2 ;;
    *) CYCLES="$a" ;;
  esac
done

PKG=io.github.slebed.gdxvulkan.testapp
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="$HERE/../gdx-vulkan-android/testapp/build/outputs/apk/debug/testapp-debug.apk"

adb get-state >/dev/null 2>&1 || { echo "no adb device connected" >&2; exit 2; }

# However this ends, send the phone back to its home screen and stop the test app, so the person holding it can see the test is over.
go_home() {
  adb shell am force-stop "$PKG" >/dev/null 2>&1
  adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
}
trap go_home EXIT
[ -f "$APK" ] || (cd "$HERE/../gdx-vulkan-android" && ./gradlew :testapp:assembleDebug)
adb install -r "$APK" >/dev/null
adb shell run-as "$PKG" rm -f files/gdxvk-test.log
adb shell am force-stop "$PKG"
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am start -n "$PKG/.LifecycleActivity" --ei cycles "$CYCLES" ${EXTRA[@]+"${EXTRA[@]}"} >/dev/null

deadline=$(( $(date +%s) + CYCLES * 12 + 60 ))
verdict=""
while [ "$(date +%s)" -lt "$deadline" ]; do
  log="$(adb shell run-as "$PKG" cat files/gdxvk-test.log 2>/dev/null || true)"
  verdict="$(printf '%s\n' "$log" | grep -E 'LIFECYCLE (PASS|FAIL)' | tail -1 || true)"
  [ -n "$verdict" ] && break
  sleep 10
done

printf '%s\n' "$log" | grep -E 'device=|^I cycle|^E ' | tail -30
echo "----"
if [ -z "$verdict" ]; then echo "no verdict within the time limit"; exit 1; fi
echo "$verdict"
case "$verdict" in *PASS*) exit 0 ;; *) exit 1 ;; esac
