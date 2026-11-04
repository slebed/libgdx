package io.github.slebed.gdxvulkan.desktop;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.environment.PointLight;
import com.badlogic.gdx.graphics.g3d.environment.SpotLight;
import com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.utils.Array;

/**
 * Draws libGDX's stock {@code ModelBatch} (DefaultShader) and the shadow-depth {@code ModelBatch} (DepthShader) through a matrix of materials, vertex layouts and
 * lights, one combination per frame, so that every program they ask for is translated and compiled and, with {@code -Dgdxvulkan.recordShaders=dir}, saved. The
 * {@code recordShaderBundle} Gradle task runs it for both code paths and puts the files in {@code gdx-vulkan-core/src/main/resources/shaders/bundle}, where
 * {@code VulkanShaderProgram} finds them instead of running the translator and the compiler. Run it again after changing the translator or the compiler version.
 */
public final class RecordShaderBundleMain {
    private RecordShaderBundleMain () {}

    static final class Recorder extends ApplicationAdapter {
        final Array<Runnable> jobs = new Array<Runnable>();
        int frame, failures;
        int compilerCallsAtStart, compilerCallsAtEnd, combinations;
        String firstFailure;
        PerspectiveCamera camera;
        ModelBatch models, depth;
        final Array<Model> modelsToDispose = new Array<Model>();
        Texture texture;

        @Override public void create () {
            compilerCallsAtStart = io.github.slebed.gdxvulkan.GlslCompiler.callCount();
            camera = new PerspectiveCamera(60f, 128, 128);
            camera.position.set(2.5f, 2f, 4f);
            camera.lookAt(0f, 0f, 0f);
            camera.near = 0.1f;
            camera.far = 50f;
            camera.update();
            models = new ModelBatch();
            depth = new ModelBatch(new DepthShaderProvider());
            final Pixmap pm = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
            pm.setColor(1f, 0.5f, 0.25f, 1f);
            pm.fill();
            texture = new Texture(pm);
            pm.dispose();

            final Array<Environment> environments = new Array<Environment>();
            environments.add(null);
            environments.add(env(true, 0, 0, 0, false));
            environments.add(env(true, 1, 0, 0, false));
            environments.add(env(true, 2, 0, 0, false));
            environments.add(env(true, 1, 1, 0, false));
            environments.add(env(true, 1, 0, 1, false));
            environments.add(env(true, 0, 2, 0, false));
            environments.add(env(true, 1, 0, 0, true));
            environments.add(env(false, 0, 0, 0, true));

            final int a = Usage.Position | Usage.Normal;
            final int b = a | Usage.TextureCoordinates;
            final int c = b | Usage.ColorPacked;
            final Array<Material> plain = new Array<Material>();
            plain.add(new Material(ColorAttribute.createDiffuse(0.8f, 0.3f, 0.2f, 1f)));
            plain.add(new Material(ColorAttribute.createDiffuse(0.8f, 0.3f, 0.2f, 1f), ColorAttribute.createSpecular(1f, 1f, 1f, 1f), FloatAttribute.createShininess(30f)));
            plain.add(new Material(ColorAttribute.createDiffuse(0.8f, 0.3f, 0.2f, 0.5f), new BlendingAttribute(true, 0.5f)));
            final Array<Material> textured = new Array<Material>();
            textured.add(new Material(TextureAttribute.createDiffuse(texture)));
            textured.add(new Material(ColorAttribute.createDiffuse(0.8f, 0.8f, 0.8f, 1f), TextureAttribute.createDiffuse(texture)));
            textured.add(new Material(TextureAttribute.createDiffuse(texture), ColorAttribute.createSpecular(1f, 1f, 1f, 1f), FloatAttribute.createShininess(20f),
                ColorAttribute.createEmissive(0.1f, 0f, 0f, 1f)));
            textured.add(new Material(TextureAttribute.createDiffuse(texture), FloatAttribute.createAlphaTest(0.5f), new BlendingAttribute(true, 1f)));

            final ModelBuilder mb = new ModelBuilder();
            addKnight(environments);
            for (int usage : new int[] {a, b, c}) {
                for (Material m : plain) add(mb, usage, m, environments);
            }
            for (int usage : new int[] {b, c}) {
                for (Material m : textured) add(mb, usage, m, environments);
            }
        }

        /** The skinned knight (libGDX's test asset, in test-assets): the bone-weight variants of the shaders, with every environment and the shadow pass. */
        private void addKnight (Array<Environment> environments) {
            final String assets = System.getProperty("gdxvulkan.assetsDir");
            if (assets == null) return;
            final com.badlogic.gdx.files.FileHandle file = new com.badlogic.gdx.files.FileHandle(new java.io.File(assets, "data/g3d/knight.g3db"));
            if (!file.exists()) return;
            final Model knight = new com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader(new com.badlogic.gdx.utils.UBJsonReader()).loadModel(file);
            modelsToDispose.add(knight);
            final ModelInstance instance = new ModelInstance(knight);
            if (knight.animations.size > 0) {
                final com.badlogic.gdx.graphics.g3d.utils.AnimationController controller = new com.badlogic.gdx.graphics.g3d.utils.AnimationController(instance);
                controller.setAnimation(knight.animations.first().id);
                controller.update(0.4f);
            }
            instance.transform.scl(0.03f); // the knight is large: keep it in front of the camera
            for (final Environment e : environments) {
                jobs.add(new Runnable() {
                    @Override public void run () {
                        models.begin(camera);
                        models.render(instance, e);
                        models.end();
                    }
                });
            }
            jobs.add(new Runnable() {
                @Override public void run () {
                    depth.begin(camera);
                    depth.render(instance);
                    depth.end();
                }
            });
        }

        private void add (ModelBuilder mb, int usage, Material material, Array<Environment> environments) {
            final Model model = mb.createBox(1f, 1f, 1f, material, usage);
            modelsToDispose.add(model);
            final ModelInstance instance = new ModelInstance(model);
            for (final Environment e : environments) {
                jobs.add(new Runnable() {
                    @Override public void run () {
                        models.begin(camera);
                        models.render(instance, e);
                        models.end();
                    }
                });
            }
            jobs.add(new Runnable() { // the shadow pass of the same renderable
                @Override public void run () {
                    depth.begin(camera);
                    depth.render(instance);
                    depth.end();
                }
            });
        }

        private static Environment env (boolean ambient, int directional, int point, int spot, boolean fog) {
            final Environment e = new Environment();
            if (ambient) e.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.2f, 0.2f, 0.25f, 1f));
            for (int i = 0; i < directional; i++) e.add(new DirectionalLight().set(0.8f, 0.8f, 0.7f, -0.5f - i * 0.2f, -1f, -0.4f));
            for (int i = 0; i < point; i++) e.add(new PointLight().set(0.9f, 0.4f, 0.4f, 1f + i, 2f, 1f, 5f));
            for (int i = 0; i < spot; i++) e.add(new SpotLight().set(0.4f, 0.9f, 0.4f, 0f, 3f, 0f, 0f, -1f, 0f, 5f, 30f, 1f));
            if (fog) e.set(new ColorAttribute(ColorAttribute.Fog, 0.3f, 0.3f, 0.4f, 1f));
            return e;
        }

        @Override public void render () {
            if (frame >= jobs.size) {
                compilerCallsAtEnd = io.github.slebed.gdxvulkan.GlslCompiler.callCount();
                combinations = jobs.size;
                System.out.println("recorded " + jobs.size + " combinations, " + failures + " failed");
                Gdx.app.exit();
                return;
            }
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            try {
                jobs.get(frame).run();
            } catch (RuntimeException e) {
                failures++;
                if (firstFailure == null) firstFailure = String.valueOf(e);
                System.out.println("combination " + frame + " failed: " + e);
            }
            frame++;
        }

        @Override public void dispose () {
            for (Model m : modelsToDispose) m.dispose();
            models.dispose();
            depth.dispose();
            texture.dispose();
        }
    }

    public static void main (String[] args) {
        final VulkanDesktopConfiguration config = new VulkanDesktopConfiguration();
        config.width = 128;
        config.height = 128;
        config.resizable = false;
        config.disableAudio = true;
        config.vsync = false;
        config.depthBuffer = true;
        new VulkanDesktopApplication(new Recorder(), config);
    }
}
