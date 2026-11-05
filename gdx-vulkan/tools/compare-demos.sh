#!/usr/bin/env bash
# Runs one demo on stock libGDX (OpenGL) and on gdx-vulkan, same scene, same settings, and prints the numbers side by side: frame time, CPU per frame (all threads),
# render-thread CPU, GPU time, and the GPU's power draw (nvidia-smi, when there is one). Run each at vsync off (what the machine can do) and vsync on (the same work
# at 60 fps: the busy time and power then say which backend does it with less).
#
#   tools/compare-demos.sh <demo: sprites|boids|shadows> [boids or sprites] [frames (3000)] [vsync: false|true]   (sprites: MODE=0|1|2 in the environment)
#   (old form) tools/compare-demos.sh <demo: boids|shadows> [boids] [frames (3000)] [vsync: false|true]
#
# Output lines: "<backend> | frame | CPU | render thread | GPU | GPU power".
set -u
DEMO=${1:?usage: tools/compare-demos.sh sprites|boids|shadows [count] [frames] [vsync]}
BOIDS=${2:-10000}
FRAMES=${3:-3000}
VSYNC=${4:-false}
HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"
OUT="${TMPDIR:-${TEMP:-/tmp}}/gdxvk-compare"
mkdir -p "$OUT"

power() { # mean GPU power draw in watts over a run: samples nvidia-smi every 0.5 s into $1
  command -v nvidia-smi >/dev/null 2>&1 || return 0
  while true; do nvidia-smi --query-gpu=power.draw --format=csv,noheader,nounits >> "$1" 2>/dev/null; sleep 0.5; done
}
mean() { awk '{ if ($1+0 > 0) { s += $1; n++ } } END { if (n) printf "%.1f W", s / n; else printf "n/a" }' "$1" 2>/dev/null; }

run() { # run <name> <gradle task and arguments...>
  local name=$1; shift
  local log="$OUT/$name.log" watts="$OUT/$name.watts"
  rm -f "$watts"
  power "$watts" & local sampler=$!
  sleep 1
  ./gradlew -q "$@" -Ddemo.boids="$BOIDS" -Ddemo.vsync="$VSYNC" -Ddemo.width=1280 -Ddemo.height=720 -Ddemo.frames="$FRAMES" -Ddemo.screenshot="$OUT/$name.png" > "$log" 2>&1
  kill "$sampler" 2>/dev/null; wait "$sampler" 2>/dev/null
  # drop the first third of the power samples: start-up and JIT warm-up
  [ -f "$watts" ] && tail -n +"$(( $(wc -l < "$watts") / 3 + 1 ))" "$watts" > "$watts.steady"
  printf '%-10s | %s | GPU power %s\n' "$name" "$(grep '^\[demo\]' "$log" | sed 's/^.*stock libGDX *| //; s/^.*gdx-vulkan *| //' | cut -c1-300)" "$(mean "$watts.steady")"
}

case "$DEMO" in
  sprites)
    run opengl :gdx-vulkan-demos:demoGl -Ddemo=sprites -Ddemo.sprites="$BOIDS" -Ddemo.mode="${MODE:-0}"
    run vulkan :gdx-vulkan-demos:demos -Ddemo.start=1 -Ddemo.sprites="$BOIDS" -Ddemo.mode="${MODE:-0}"
    ;;
  boids)
    run opengl :gdx-vulkan-demos:demoGl -Ddemo=boids
    run vulkan :gdx-vulkan-demos:demos -Ddemo.start=2
    ;;
  shadows)
    run opengl :gdx-vulkan-demos:demoGl -Ddemo=shadows-stock
    run vulkan :gdx-vulkan-demos:demos -Ddemo.start=3
    ;;
  *) echo "unknown demo $DEMO"; exit 1 ;;
esac
