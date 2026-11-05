# Adoption notes

A log of moving real libGDX games over to gdx-vulkan: what had to change, what broke, what was faster or slower. One section per project; keep it factual and dated. The
README's "Moving an existing game over" section is corrected from what is written here.

## How to try a project

1. Copy the project, build it once as it is and record the frame time (`Gdx.graphics.getDeltaTime()` averaged over a minute), the CPU time per frame and the GPU time if the device has a timer query.
2. Change the application class (`Lwjgl3Application` to `VulkanDesktopApplication`, `AndroidApplication` to `VulkanAndroidApplication`) and nothing else. Run it. Everything the stock classes use runs through the GL shim.
3. List what failed, with the message (the shim names the unsupported call or the GLSL line).
4. Rename the hot classes (`SpriteBatch` to `VulkanSpriteBatch`, and so on) and measure again.
5. List the custom GLSL the project uses, and which of it was refused or slow to build.
6. Compare with `tools/compare-demos.sh`-style numbers on the same machine: frame time, CPU per frame, GPU time, and the first 10 seconds (shader builds at run time).

## Template

### <project>, <date>, <commit of gdx-vulkan>
- Machine / phone, GPU, driver:
- Renderer before: libGDX <version>, <backend>
- Changes needed to run: 
- What broke, and what fixed it:
- Custom GLSL (files, anything refused):
- Frame time, CPU per frame, GPU time: stock / shim only / with the Vulkan classes
- First-run cost (shader builds, pipeline builds):
- Visual differences against stock (screenshots):
- Verdict:
