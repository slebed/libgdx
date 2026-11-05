#!/usr/bin/env bash
# Runs the demo app (gdx-vulkan-android/demoapp) on one phone: each demo for a while, printing the numbers it shows and saving a screenshot of each.
#
#   tools/demos-test.sh <adb serial> [seconds per demo (20)] [extra "am start" arguments, e.g. --ez forceTierB true]
#
# Screenshots go to gdx-vulkan-android/demoapp/build/screens/<serial>-demo<N>.png. Whatever happens, the phone is sent back to its home screen and the app is
# stopped when this script ends. Needs the debug APK (gradlew :demoapp:assembleDebug in gdx-vulkan-android). Run it on phones that are connected by USB.
set -u
SERIAL=${1:?usage: tools/demos-test.sh <adb serial> [seconds per demo] [am start arguments]}
SECONDS_EACH=${2:-20}
shift
shift 2>/dev/null
PKG=io.github.slebed.gdxvulkan.demos
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="$HERE/../gdx-vulkan-android/demoapp/build/outputs/apk/debug/demoapp-debug.apk"
SHOTS="$HERE/../gdx-vulkan-android/demoapp/build/screens"
adb_() { adb -s "$SERIAL" "$@"; }

go_home() {
  adb_ shell am force-stop "$PKG" >/dev/null 2>&1
  adb_ shell input keyevent KEYCODE_HOME >/dev/null 2>&1
}
trap go_home EXIT

[ -f "$APK" ] || (cd "$HERE/../gdx-vulkan-android" && ./gradlew :demoapp:assembleDebug)
mkdir -p "$SHOTS"
adb_ install -r "$APK" | tail -1
adb_ shell input keyevent KEYCODE_WAKEUP
adb_ shell wm dismiss-keyguard
status=0
for n in 1 2 3; do
  adb_ shell am force-stop "$PKG"
  adb_ shell am start -n "$PKG/io.github.slebed.gdxvulkan.demoapp.DemosActivity" --ei start "$n" --ei logSeconds 2 --ez vsync false "$@" >/dev/null
  sleep "$SECONDS_EACH"
  adb_ exec-out screencap -p > "$SHOTS/$SERIAL-demo$n.png"
  LOG="$(adb_ shell run-as "$PKG" cat files/gdxvk-demos.log 2>/dev/null)"
  if [ -z "$LOG" ]; then echo "demo $n: no log (crashed?)"; status=1; continue; fi
  echo "demo $n:"
  printf '%s\n' "$LOG" | tail -1 | cut -c1-400
done
exit $status
