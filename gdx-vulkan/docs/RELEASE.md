# Release checklist

CI (GitHub Actions, lavapipe, Mesa pinned) gates every push. It cannot run a real GPU or a phone, so a release adds the manual runs below. Record the
results (device, driver, commit) in the release notes; do not tick a box for a run that was not done on that commit.

## 1. Before anything else
- [ ] `git status` clean, on the commit being released; CI green on that commit
- [ ] `gdxVersion` in `gradle.properties` and `gdx-vulkan-android/gradle.properties` names a libGDX release on Maven Central (see the bump note below)
- [ ] `docs/CHECKLIST.md` counts match the items (a ticked box means built **and verified**)
- [ ] `README.md` status table and measured numbers match what you are about to claim

## 2. Windows, real GPUs (each GPU, each tier)
Index 0 and 1 depend on the machine; the soak prints the device name it used.
- [ ] `gradlew check -Dgdxvulkan.requireDriver=true -Dgdxvulkan.requireGl=true -Dgdxvulkan.device=N`
- [ ] the same with `-Dgdxvulkan.forceTierB=true`
- [ ] Validation and synchronization validation reported nothing (a message fails the test)
- [ ] Gate 2 (7 scenes), Gate 3 (2 scenes) and Gate 5 ran, none skipped (`requireGl` makes a skip a failure), each also with libGDX's own classes (the second pass in each test)
- [ ] `BundledShadersTest` passed (if not: `gradlew :gdx-vulkan-desktop:recordShaderBundle`, commit the new bundle)
- [ ] `gradlew :gdx-vulkan-desktop:soakTest -Dgdxvulkan.soakSeconds=600 -Dgdxvulkan.device=N` per GPU and tier: flat memory, 60 fps, no hazards
- [ ] `gradlew :gdx-vulkan-desktop:stress2`, `stress3`, `stressInstanced`, `stressShim`: compare with the numbers in `README.md`; a regression over 10% needs an explanation; `stressShim` must pass (stock `SpriteBatch` within 1.5x)
- [ ] Demos: `gradlew :gdx-vulkan-demos:demos` by hand: each demo draws and its text reads well at the window size; C starts the OpenGL version beside it; both show the same fields. Compare the numbers with the Demo measurements in `README.md`

## 3. Linux
- [ ] `docker build -t gdx-vulkan-ci ci` and `ci/run-checks.sh` (see `ci/README.md`), both tiers, exact goldens
- [ ] Mesa pin unchanged. If it must change: regenerate the goldens with the workflow's `update_goldens` input, review the image diffs, commit them in their own commit

## 4. Android device (one arm64 phone at least)
```
cd gdx-vulkan-android
gradlew :testapp:assembleDebug && adb install -r testapp/build/outputs/apk/debug/testapp-debug.apk
adb shell am start -n io.github.slebed.gdxvulkan.testapp/.MainActivity --ez suite true [--ez forceTierB true]
adb shell run-as io.github.slebed.gdxvulkan.testapp cat files/gdxvk-test.log      # DEVICESUITE PASS, 30 checks (on a phone whose 3D shaders do not build, skip the 3D checks with `--es only`)
adb shell am start -n io.github.slebed.gdxvulkan.testapp/.LifecycleActivity --ei cycles 100 [--ez forceTierB true]
adb shell run-as io.github.slebed.gdxvulkan.testapp cat files/gdxvk-test.log      # LIFECYCLE PASS
```
- [ ] Device suite passes in both tiers, including the stock-class checks (`stockClassesOnTheShim`, `gate3StockClassesOnTheShim`, `gate5StockModelBatchOnTheShim`, `stockSpriteBatchAgainstGenerated`)
- [ ] Lifecycle run passes in both tiers: zero failures, native heap flat after warm-up, surface recreation well under 50 ms
- [ ] 10-minute soak (`--ei soakSeconds 600`) in both tiers when the release touches memory, the staging ring or deletion
- [ ] Pre-rotation: rotate the phone in all four orientations by hand and look at it (`adb exec-out screencap -p`), or run the lifecycle run with `--ez preRotate false` and compare

## 5. Publish
- [ ] Tag `vX.Y.Z` on the commit; release notes: what changed, what was run where, known gaps (copy from `README.md`)
- [ ] `NOTICE` still lists everything vendored and generated

## Bumping libGDX
1.14.3 is not on Maven Central yet (`curl -sI https://repo1.maven.org/maven2/com/badlogicgames/gdx/gdx/1.14.3/gdx-1.14.3.pom` answers 404).
When it is: change `gdxVersion` in both `gradle.properties` files, re-run `tools/generate_gl20_base.py` and the batch generators against the new
`gdx-<version>-sources.jar` (see `tools/README.md`), and diff the generated files: a changed stock `SpriteBatch.draw` or `GL20` is exactly what the
Gate 2 oracle exists to catch. Then run section 2 to 4 in full.
