#!/usr/bin/env bash
# Runs inside the CI image (ci/Dockerfile), on lavapipe under Xvfb. Same script for GitHub Actions and local Docker runs.
#
#   /src  the gdx-vulkan project, mounted read-only (it is copied to /work so build output never touches it)
#   /out  results: test reports, golden diffs, and (UPDATE_GOLDENS=1) regenerated goldens
#
# Environment:
#   UPDATE_GOLDENS=1   regenerate src/test/resources/goldens on lavapipe (Tier A) instead of checking; no other tests run
#   SKIP_MESA_PIN=1    do not fail when Mesa differs from ci/mesa-version.txt (needed to bootstrap a new pin)
set -euo pipefail

SRC=${SRC:-/src}
OUT=${OUT:-/out}
mkdir -p "$OUT"

MESA=$(dpkg-query -W -f='${Version}' mesa-vulkan-drivers)
PIN=$(tr -d '[:space:]' < "$SRC/ci/mesa-version.txt")
echo "Mesa (lavapipe) version: $MESA   pinned: $PIN"
echo "$MESA" > "$OUT/mesa-version.actual.txt"
if [ "$MESA" != "$PIN" ] && [ "${SKIP_MESA_PIN:-0}" != "1" ]; then
    echo "::error::Mesa is $MESA but ci/mesa-version.txt pins $PIN. Goldens are exact lavapipe renders: regenerate them" \
         "(ci/README.md), then update the pin." >&2
    exit 3
fi

# Build from a copy so Windows/macOS build directories in the mount are never reused.
rm -rf /work/project && mkdir -p /work/project
tar -C "$SRC" --exclude=build --exclude=.gradle --exclude=.idea --exclude=local.properties -cf - . | tar -C /work/project -xf -
cd /work/project
chmod +x gradlew

ICD=$(ls /usr/share/vulkan/icd.d/lvp_icd*.json | head -n 1)
export VK_DRIVER_FILES="$ICD" VK_ICD_FILENAMES="$ICD"   # lavapipe only, whatever else is installed
# Synchronization validation is switched on per Gradle test task (see gdx-vulkan-desktop/build.gradle), not globally here.
export GRADLE_OPTS="-Dorg.gradle.daemon=false"
echo "Vulkan ICD: $ICD"
xvfb-run -a -s "-screen 0 1280x1024x24" vulkaninfo --summary | grep -E "deviceName|apiVersion|driverInfo" || true

collect() {
    mkdir -p "$OUT/reports"
    for m in gdx-vulkan-core gdx-vulkan-desktop; do
        [ -d "$m/build/reports/tests" ] && cp -r "$m/build/reports/tests" "$OUT/reports/$m" || true
        [ -d "$m/build/test-results" ] && cp -r "$m/build/test-results" "$OUT/reports/$m-results" || true
    done
    [ -d gdx-vulkan-desktop/build/golden-diff ] && cp -r gdx-vulkan-desktop/build/golden-diff "$OUT/golden-diff" || true
}
trap collect EXIT

run() { xvfb-run -a -s "-screen 0 1280x1024x24" ./gradlew --console=plain --no-daemon "$@"; }

if [ "${UPDATE_GOLDENS:-0}" = "1" ]; then
    run :gdx-vulkan-desktop:test --tests '*GoldenTest*' -Dgdxvulkan.requireDriver=true -Dgdxvulkan.updateGoldens=true
    rm -rf "$OUT/goldens" && mkdir -p "$OUT/goldens"
    cp gdx-vulkan-desktop/src/test/resources/goldens/*.png "$OUT/goldens/"
    echo "Regenerated goldens copied to $OUT/goldens (Mesa $MESA)."
    exit 0
fi

for tier in false true; do
    echo "================ forceTierB=$tier ================"
    run check --rerun-tasks -Dgdxvulkan.requireDriver=true -Dgdxvulkan.forceTierB=$tier
done
echo "All checks passed on lavapipe (Mesa $MESA), Tier A and Tier B."
