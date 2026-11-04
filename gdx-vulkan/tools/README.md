# tools

Generators that copy code from stock libGDX so the comparison with stock libGDX stays honest (see docs/PLAN.md, Core decision 8). The generated files are
checked in; run these only to regenerate them, for instance after bumping the libGDX version. They need Python 3 and the libGDX sources, which are in
the `gdx-<version>-sources.jar` on Maven Central (the same version as `gdxVersion` in `gdx-vulkan-android/gradle.properties`, currently 1.14.2):

```
curl -LO https://repo1.maven.org/maven2/com/badlogicgames/gdx/gdx/1.14.2/gdx-1.14.2-sources.jar
unzip -q gdx-1.14.2-sources.jar -d libgdx-src
S=libgdx-src/com/badlogic/gdx
J=gdx-vulkan-core/src/main/java/io/github/slebed/gdxvulkan

python tools/generate_gl20_base.py $S/graphics/GL20.java > $J/gl/VulkanGL20Base.java
python tools/generate_spritebatch_draws.py $S/graphics/g2d/SpriteBatch.java > $J/g2d/VulkanSpriteBatchDraws.java
python tools/generate_polygonbatch_draws.py $S/graphics/g2d/PolygonSpriteBatch.java > $J/g2d/VulkanPolygonSpriteBatchDraws.java
python tools/generate_shaperenderer.py $S/graphics/glutils/ShapeRenderer.java > $J/g2d/VulkanShapeRenderer.java
```

Then run the tests: the Gate 2 and Gate 3 scenes compare the result with stock libGDX on OpenGL, so a stock change that matters shows up there.