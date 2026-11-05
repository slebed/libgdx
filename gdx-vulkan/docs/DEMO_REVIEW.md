# Demo review

For the owner's review of the demos (TODO.md section 4): what to run, what to look at, what is known to be weak. Nothing here has been signed off; tick the boxes as you go.

## Run them

```
gradlew :gdx-vulkan-demos:demos                      # the demo app: 1 to 4 start a demo, C starts the stock OpenGL one beside it (not in demo 4), Esc comes back
gradlew :gdx-vulkan-demos:demoGl -Ddemo=sprites      # one demo on stock libGDX and OpenGL alone (also boids, shadows-stock)
cd gdx-vulkan-android && gradlew :demoapp:assembleDebug     # the phone app; tools/demos-test.sh <serial> runs the four demos on a phone
```

Useful switches: `-Ddemo.hud=false` (no text), `-Ddemo.sprites=N`, `-Ddemo.mode=0|1|2`, `-Ddemo.boids=N`, `-Ddemo.lights=true`, `-Ddemo.width=`, `-Ddemo.height=`, `-Ddemo.vsync=true`.
Screenshots are in `docs/images/`.

## Code (are they clean and copyable?)

| File | Lines | What to check |
|---|---|---|
| `SpritesDemo.java` | about 165 | the one line that differs between the backends (`Backend.vulkan ? new VulkanSpriteBatch() : new SpriteBatch()`); the three modes |
| `GpuBoidsDemo.java`, `BoidSwarm.java` | about 160, 85 | the compute-driven API (`VulkanGpuInstances`, `VulkanComputeShader`) as a user would write it |
| `GpuBoidsOpenGlDemo.java` | about 230 | what OpenGL compute needs by hand; the same work as the Vulkan version |
| `StockShadowsDemo.java`, `CascadedShadowsDemo.java`, `ShadowWorld.java` | about 100 each | the stock shadow recipe, and what cascades change in the code (three places) |
| `Hud.java`, `FrameStats.java`, `Backend.java` | about 190, 70, 50 | plumbing for the text and the numbers; not meant to be copied |
| `DemoMenu.java`, `Controls.java` | about 110, 30 | the menu and the touch controls |

- [ ] Each demo is readable top to bottom without knowing the library.
- [ ] The demo classes contain nothing that exists only for measuring (the measuring is in `Hud`, `FrameStats`, `Backend`).

## Looks (the on-screen text and the scenes)

- [ ] The text is readable at your screen size (it is sized from the window height: a font of about 1/36 of it) and does not hide the scene.
- [ ] The description panel (top left) is too long in the boids demo (six lines); it is the one to shorten if you want less text.
- [ ] The scenes look good enough to show: the sprites are flat discs, the boids are cones on a dark background, the shadow road is plain colours. Say what you want changed.
- [ ] The phone layout (landscape, big buttons, tap for Space and L) works with your hand.

## Known weak spots

- The sprite demo's scattered mode looks the same in every mode (the sprites are random); only the numbers differ. A screenshot cannot show the point.
- The boid demo is dark by design (a dark sky); with the 500 lights it is more colourful but is no longer a like-for-like picture.
- The on-screen text costs gdx-vulkan about twice what it costs OpenGL (0.4 against 0.2 ms on the REDMAGIC): see README "Demo measurements". Use `-Ddemo.hud=false` for the scene alone.
- There is no demo of a normal game scene (a Scene2D UI over 2D art), of framebuffer post-processing, or of 3D with a skinned model. `TODO.md` section 4 lists the ideas.

## Questions for you

1. Should the first demo in the menu be the sprites (the 20x case) or the boids (the GPU-driven showpiece)?
2. Do you want a fifth demo with a real game scene (Scene2D UI, a tile map, particles) so that the stock-class path through the shim is shown on something familiar?
3. Is the `Press C` side-by-side workflow what you want, or should the comparison be one number per demo printed in a table (as `tools/compare-demos.sh` does)?
