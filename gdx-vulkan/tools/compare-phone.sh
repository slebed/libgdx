#!/usr/bin/env bash
# The same demo on stock libGDX (OpenGL ES) and on gdx-vulkan, on one phone, one after the other, and the numbers it shows side by side.
#
#   tools/compare-phone.sh <adb serial> <sprites|boids|shadows> [boid count (10000)] [seconds per run (30)] [vsync: false|true (false)] [rounds (2)]
#
# vsync false: how much the phone can do (frame rate, CPU per frame). vsync true: the same work at the display's rate; the CPU time per frame (and, where the GL
# side can measure it, the GPU time) then says which backend does it with less, which is what battery life follows. Rounds alternate the order (GL first, then
# Vulkan first) so that warm-up and heat do not favour either. Needs the debug APK; run it on phones connected by USB. The phone ends on its home screen.
set -u
SERIAL=${1:?usage: tools/compare-phone.sh <adb serial> sprites|boids|shadows [boids] [seconds] [vsync] [rounds]}
DEMO=${2:?boids or shadows}
BOIDS=${3:-10000}
SECS=${4:-30}
VSYNC=${5:-false}
ROUNDS=${6:-2}
PKG=io.github.slebed.gdxvulkan.demos
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="$HERE/../gdx-vulkan-android/demoapp/build/outputs/apk/debug/demoapp-debug.apk"
adb_() { adb -s "$SERIAL" "$@"; }
go_home() { adb_ shell am force-stop "$PKG" >/dev/null 2>&1; adb_ shell input keyevent KEYCODE_HOME >/dev/null 2>&1; }
trap go_home EXIT

[ -f "$APK" ] || (cd "$HERE/../gdx-vulkan-android" && ./gradlew :demoapp:assembleDebug)
adb_ install -r "$APK" | tail -1 >/dev/null
adb_ shell input keyevent KEYCODE_WAKEUP
adb_ shell wm dismiss-keyguard

one() { # one <gl|vulkan> -> prints the last logged line, reduced
  local which=$1 activity extra
  adb_ shell am force-stop "$PKG"
  if [ "$which" = gl ]; then
    activity=GlDemosActivity
    extra="--es demo $(case $DEMO in boids) echo boids;; sprites) echo sprites;; *) echo shadows-stock;; esac)"
  else
    activity=DemosActivity
    extra="--ei start $(case $DEMO in sprites) echo 1;; boids) echo 2;; *) echo 3;; esac)"
  fi
  adb_ shell am start -n "$PKG/io.github.slebed.gdxvulkan.demoapp.$activity" $extra --ei boids "$BOIDS" --ei sprites "$BOIDS" --ei mode "${MODE:-0}" --es hud "${HUD:-true}" --ez vsync "$VSYNC" --ei logSeconds 2 >/dev/null
  sleep "$SECS"
  adb_ shell run-as "$PKG" cat files/gdxvk-demos.log 2>/dev/null | tail -1 | sed 's/^\[demo\] //' | awk -F' [|] ' -v w="$which" '{ printf "%-7s %s | %s | %s | %s | %s\n", w, $3, $4, $5, $6, $2 }' | cut -c1-260
  adb_ shell am force-stop "$PKG"
  sleep 3 # let the phone cool a moment between runs
}

echo "device: $(adb_ shell getprop ro.product.model | tr -d '\r'), $DEMO, $BOIDS boids, vsync $VSYNC"
for r in $(seq 1 "$ROUNDS"); do
  if [ $((r % 2)) -eq 1 ]; then one gl; one vulkan; else one vulkan; one gl; fi
done
