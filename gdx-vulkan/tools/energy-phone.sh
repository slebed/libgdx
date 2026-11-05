#!/usr/bin/env bash
# Battery power of the same demo on stock libGDX (OpenGL ES) and on gdx-vulkan, on a phone that is NOT plugged in (wireless adb), at the display's frame rate (vsync on) so
# that both backends do the same work per second. Reads the battery's current and voltage every half second on the phone itself for a minute, after a warm-up, and prints the
# mean power. The screen is on and the same for both; what differs is the CPU, the GPU and the memory traffic of the app. Rounds alternate the order.
#
#   tools/energy-phone.sh <adb serial> <sprites|boids|shadows> [boids/sprites (10000)] [seconds measured (60)] [rounds (2)]
#
# Environment: MODE=0|1|2 (sprites), HUD=true|false. The phone ends on its home screen. Refuses to run when the phone reports it is charging.
set -u
SERIAL=${1:?usage: tools/energy-phone.sh <adb serial> sprites|boids|shadows [count] [seconds] [rounds]}
DEMO=${2:?sprites, boids or shadows}
COUNT=${3:-10000}
SECS=${4:-60}
ROUNDS=${5:-2}
PKG=io.github.slebed.gdxvulkan.demos
HERE="$(cd "$(dirname "$0")" && pwd)"
APK="$HERE/../gdx-vulkan-android/demoapp/build/outputs/apk/debug/demoapp-debug.apk"
adb_() { adb -s "$SERIAL" "$@"; }
go_home() { adb_ shell am force-stop "$PKG" >/dev/null 2>&1; adb_ shell input keyevent KEYCODE_HOME >/dev/null 2>&1; }
trap go_home EXIT

if adb_ shell dumpsys battery | grep -qE "(AC|USB|Wireless) powered: true"; then echo "the phone is charging: unplug it (wireless adb) to measure power"; exit 2; fi
[ -f "$APK" ] || (cd "$HERE/../gdx-vulkan-android" && ./gradlew :demoapp:assembleDebug)
adb_ install -r "$APK" | tail -1 >/dev/null
adb_ shell input keyevent KEYCODE_WAKEUP
adb_ shell wm dismiss-keyguard

one() { # one <gl|vulkan> -> mean battery power in mW
  local which=$1 activity extra
  adb_ shell am force-stop "$PKG"
  if [ "$which" = gl ]; then
    activity=GlDemosActivity
    extra="--es demo $(case $DEMO in boids) echo boids;; sprites) echo sprites;; *) echo shadows-stock;; esac)"
  else
    activity=DemosActivity
    extra="--ei start $(case $DEMO in sprites) echo 1;; boids) echo 2;; *) echo 3;; esac)"
  fi
  adb_ shell am start -n "$PKG/io.github.slebed.gdxvulkan.demoapp.$activity" $extra --ei boids "$COUNT" --ei sprites "$COUNT" --ei mode "${MODE:-0}" --es hud "${HUD:-true}" --ez vsync true --ei logSeconds 2 >/dev/null
  sleep 15 # warm-up: the JIT, the GPU clock
  local n=$((SECS * 2))
  adb_ shell "i=0; while [ \$i -lt $n ]; do echo \$(cat /sys/class/power_supply/battery/current_now) \$(cat /sys/class/power_supply/battery/voltage_now); sleep 0.5; i=\$((i+1)); done" |
    awk '{ c = $1 < 0 ? -$1 : $1; s += c / 1e6 * ($2 / 1e6); n++ } END { if (n) printf "%.0f", s / n * 1000 }'
  local last
  last="$(adb_ shell run-as "$PKG" cat files/gdxvk-demos.log 2>/dev/null | tail -1 | sed 's/^.*frame: //' | cut -c1-60)"
  printf ' mW   (%s)\n' "$last" >&2
}

echo "device: $(adb_ shell getprop ro.product.model | tr -d '\r'), $DEMO, $COUNT, vsync on, battery $(adb_ shell dumpsys battery | grep level | tr -d ' ')"
for r in $(seq 1 "$ROUNDS"); do
  order="gl vulkan"; [ $((r % 2)) -eq 0 ] && order="vulkan gl"
  for which in $order; do
    printf '%-7s round %s: ' "$which" "$r"
    one "$which"
    adb_ shell am force-stop "$PKG"; sleep 20 # cool down between runs
  done
done
