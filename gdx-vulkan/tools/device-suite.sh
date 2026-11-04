#!/usr/bin/env bash
# Runs the Android device suite on one phone and prints the interesting lines of its log.
#
#   tools/device-suite.sh <adb serial> [extra "am start" arguments]
#
#   tools/device-suite.sh RFCN90XWQ5E                                   # everything, vsync off, 6 s stress runs
#   tools/device-suite.sh RFCN90XWQ5E --es only stress2,shaderPrograms  # only the checks whose names contain these words
#   tools/device-suite.sh RFCN90XWQ5E --ez forceTierB true              # the compatibility path on a modern phone
#
# Whatever happens, the phone is sent back to its home screen and the test app is stopped when this script ends, so the person holding the phone can
# see that the test is over. Needs the debug APK (gradlew :testapp:assembleDebug in gdx-vulkan-android). Run it on phones that are connected by USB.
set -u
SERIAL=${1:?usage: tools/device-suite.sh <adb serial> [am start arguments]}
shift
PKG=io.github.slebed.gdxvulkan.testapp
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="$HERE/../gdx-vulkan-android/testapp/build/outputs/apk/debug/testapp-debug.apk"
adb_() { adb -s "$SERIAL" "$@"; }

go_home() {
  adb_ shell am force-stop "$PKG" >/dev/null 2>&1
  adb_ shell input keyevent KEYCODE_HOME >/dev/null 2>&1
}
trap go_home EXIT

[ -f "$APK" ] || (cd "$HERE/../gdx-vulkan-android" && ./gradlew :testapp:assembleDebug)
adb_ install -r "$APK" | tail -1
adb_ shell input keyevent KEYCODE_WAKEUP
adb_ shell wm dismiss-keyguard
adb_ shell am force-stop "$PKG"
adb_ shell run-as "$PKG" rm -f files/gdxvk-test.log
adb_ shell am start -n "$PKG/.MainActivity" --ez suite true --ez vsync false --ei stressSeconds 6 "$@" >/dev/null

for _ in $(seq 1 180); do
  if adb_ shell run-as "$PKG" cat files/gdxvk-test.log 2>/dev/null | grep -qE "DEVICESUITE (PASS|FAIL)"; then break; fi
  sleep 5
done
LOG="$(adb_ shell run-as "$PKG" cat files/gdxvk-test.log 2>/dev/null)"
printf '%s\n' "$LOG" | grep -E "^I device=|DEVICESUITE (PASS|FAIL)|^E FAIL|shader programs|stress2 [0-9]+ textures, [0-9]+ sprites|instanced 1000000|stress 5|gate5 scene" | cut -c1-260
printf '%s\n' "$LOG" | grep -qE "DEVICESUITE PASS"
