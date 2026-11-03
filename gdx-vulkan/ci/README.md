# gdx-vulkan CI

`.github/workflows/gdx-vulkan.yml` builds `ci/Dockerfile` and runs `ci/run-checks.sh` in it. The script builds the natives,
then runs `gradlew check` twice, Tier A and `-Dgdxvulkan.forceTierB=true`, on **lavapipe** under **Xvfb** with
`VK_LAYER_KHRONOS_validation` and synchronization validation on. Any validation message, or any golden mismatch, fails the run.

## Run it locally (Docker)

```
docker build -t gdx-vulkan-ci ci
docker run --rm -v <path-to>/gdx-vulkan:/src:ro -v <out-dir>:/out -v gdxvk-gradle:/root/.gradle gdx-vulkan-ci bash /src/ci/run-checks.sh
```
`/out` receives test reports and, on a golden mismatch, `golden-diff/<scene>.actual.png` and `.diff.png`.

## Goldens

`gdx-vulkan-desktop/src/test/resources/goldens/*.png` are exact lavapipe renders (plan, Core decision 8).
- On lavapipe they must match **exactly**. On a real GPU (RTX 2080, Galaxy S20) the same files are compared with a tolerance
  (`GoldenImages.GPU_CHANNEL_THRESHOLD`, `GPU_MAX_DIFF_SHARE`), because rasterizers differ at triangle edges.
- Both tiers must produce the same image, so there is one golden per scene.
- `GoldenImages` refuses to write a golden from anything but lavapipe.

## Changing the Mesa pin

Goldens depend on the lavapipe build. `ci/mesa-version.txt` holds the exact `mesa-vulkan-drivers` package version they were made
with, and the job fails when the image installs a different one (Ubuntu's archive moves on). To update:
1. Run the workflow manually with `update_goldens`, or locally add `-e UPDATE_GOLDENS=1 -e SKIP_MESA_PIN=1` to the `docker run`
   above. The regenerated PNGs and `mesa-version.actual.txt` land in `/out` (the workflow uploads them as an artifact).
2. Look at the images. Commit the PNGs and put the new version in `ci/mesa-version.txt`.

The base image is pinned by digest in the Dockerfile; bump it deliberately.

## Not covered here

Windows (RTX 5080/2080) and Android (Galaxy S20) runs are manual: GitHub's hosted runners have no GPU and emulator Vulkan is too
flaky to gate on. Run `gradlew check -Dgdxvulkan.requireDriver=true` (and `-Dgdxvulkan.forceTierB=true`) on a desktop GPU.
