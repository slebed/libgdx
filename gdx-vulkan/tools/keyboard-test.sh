#!/usr/bin/env bash
# Runs the on-screen keyboard check (KeyboardActivity) on one phone and prints its log lines.
#
#   tools/keyboard-test.sh <adb serial>
#
# The activity shows the system keyboard through Gdx.input.setOnscreenKeyboardVisible, checks that the system reports it, that a key sent to the surface arrives as
# libGDX input, and hides it again: "KEYBOARD PASS" or "KEYBOARD FAIL". Whatever happens, the phone is sent back to its home screen and the test app is stopped when this
# script ends. Needs the debug APK (gradlew :testapp:assembleDebug in gdx-vulkan-android). Run it on phones that are connected by USB.
set -u
SERIAL=${1:?usage: tools/keyboard-test.sh <adb serial>}
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
adb_ shell am start -n "$PKG/.KeyboardActivity" >/dev/null

for _ in $(seq 1 30); do
  if adb_ shell run-as "$PKG" cat files/gdxvk-test.log 2>/dev/null | grep -qE "KEYBOARD (PASS|FAIL)"; then break; fi
  sleep 2
done
LOG="$(adb_ shell run-as "$PKG" cat files/gdxvk-test.log 2>/dev/null)"
printf '%s\n' "$LOG" | grep -E "keyboard|KEYBOARD|^E " | cut -c1-240
printf '%s\n' "$LOG" | grep -q "KEYBOARD PASS"
