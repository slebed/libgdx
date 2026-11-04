package io.github.slebed.gdxvulkan.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
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
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight;
import com.badlogic.gdx.utils.UBJsonReader;
import com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader;
import com.badlogic.gdx.graphics.g3d.utils.AnimationController;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;

import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.g3d.VulkanModelBatch;

/**
 * The Gate 5 scenes (plan, Phase 5), written once against the stock {@code ModelBatch} API ({@link ModelApi}), so the same code drives stock
 * libGDX on OpenGL (the reference, GLSL default shader) and {@link VulkanModelBatch}. Models come from stock {@code ModelBuilder}.
 *
 * <ul>
 * <li>{@code gate5_models}: a ground plate, a specular box, a textured sphere, an emissive cylinder, a translucent cone and a vertex-coloured box,
 * lit by an ambient light, two directional lights and two point lights.</li>
 * </ul>
 */
public final class Gate5Scenes {
    public static final int SIZE = 256;
    public static final float[] BACKGROUND = {0.05f, 0.06f, 0.10f, 1f};

    /** The slice of ModelBatch the scenes use. */
    public interface ModelApi {
        void begin(com.badlogic.gdx.graphics.Camera camera);

        void render(Iterable<ModelInstance> instances, Environment environment);

        void render(Iterable<ModelInstance> instances);

        void end();

        void dispose();

        /** The batch for the shadow pass (stock: ModelBatch with a DepthShaderProvider). */
        ModelApi depth();

        /** How many draws were instanced (Vulkan only; -1 on stock). */
        default int instancedDraws() { return -1; }
    }

    public interface Scene {
        String name();

        /** False for scenes that need more than one texture per draw (specular/emissive textures, shadow maps): Tier B cannot sample them. */
        default boolean runsOnTierB() { return true; }

        /** False for a scene that shows a feature stock's default shader does not have (normal mapping): it has a lavapipe golden and its own check, no GL reference. */
        default boolean hasStockReference() { return true; }

        /** An extra check on the Vulkan picture (RGBA, row 0 at the top), for scenes whose point is something a golden alone cannot say. */
        default void verify(byte[] rgba, int width, int height) {}

        /** @param data the bundled test assets' data directory */
        void prepare(FileHandle data) throws Exception;

        void draw(ModelApi api, int width, int height) throws Exception;

        void dispose();
    }

    private Gate5Scenes() {}

    public static Scene[] all() {
        Scene[] all = {new Models(), new Knight(), new FogInstances(), new Textures(), new Shadows(), new NormalMap(), new CascadedShadows()};
        final String only = System.getenv("GDXVK_G5_ONLY"); // diagnosis: run one scene
        if (only == null) return all;
        for (Scene sc : all) if (sc.name().contains(only)) return new Scene[] {sc};
        return all;
    }

    /** Stock ModelBatch on OpenGL. */
    public static ModelApi stock() {
        return stockApi(new ModelBatch());
    }

    private static ModelApi stockApi(final ModelBatch b) {
        return new ModelApi() {
            public void begin(com.badlogic.gdx.graphics.Camera c) { b.begin(c); }
            public void render(Iterable<ModelInstance> p, Environment e) { b.render(p, e); }
            public void render(Iterable<ModelInstance> p) { b.render(p); }
            public void end() { b.end(); }
            public void dispose() { b.dispose(); }
            public ModelApi depth() { return stockApi(new ModelBatch(new com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider())); }
        };
    }

    public static ModelApi vulkan(GdxVkFrame frame) {
        return vulkanApi(frame, new VulkanModelBatch(frame), false);
    }

    /** Vulkan with the instancing sorter: identical renderables become instanced draws. */
    public static ModelApi vulkanInstancing(GdxVkFrame frame) {
        return vulkanApi(frame, new VulkanModelBatch(frame, new io.github.slebed.gdxvulkan.g3d.InstancingSorter()), false);
    }

    private static ModelApi vulkanApi(final GdxVkFrame frame, final VulkanModelBatch b, final boolean isDepth) {
        return new ModelApi() {
            public void begin(com.badlogic.gdx.graphics.Camera c) { b.begin(c); }
            public void render(Iterable<ModelInstance> p, Environment e) { b.render(p, e); }
            public void render(Iterable<ModelInstance> p) { b.render(p); }
            public void end() { b.end(); }
            public void dispose() { b.dispose(); }
            public ModelApi depth() { return vulkanApi(frame, VulkanModelBatch.depth(frame), true); }
            public int instancedDraws() { return b.getInstancedDrawCalls(); }
        };
    }

    private static final class Models implements Scene {
        private final Array<Model> models = new Array<Model>();
        private final Array<ModelInstance> instances = new Array<ModelInstance>();
        private Texture checker;
        private Environment environment;

        @Override public String name() { return "gate5_models"; }

        @Override public void prepare(FileHandle data) {
            Pixmap pm = new Pixmap(8, 8, Pixmap.Format.RGBA8888);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    pm.setColor(((x + y) & 1) == 0 ? Color.WHITE : new Color(0.9f, 0.55f, 0.1f, 1f));
                    pm.drawPixel(x, y);
                }
            }
            checker = new Texture(pm);
            checker.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            pm.dispose();

            final int usage = Usage.Position | Usage.Normal;
            ModelBuilder mb = new ModelBuilder();

            Model ground = mb.createBox(9f, 0.2f, 9f, new Material(ColorAttribute.createDiffuse(0.5f, 0.5f, 0.46f, 1f)), usage);
            Model box = mb.createBox(1.4f, 1.4f, 1.4f, new Material(ColorAttribute.createDiffuse(0.85f, 0.12f, 0.1f, 1f),
                ColorAttribute.createSpecular(1f, 1f, 1f, 1f), FloatAttribute.createShininess(40f)), usage);
            Model sphere = mb.createSphere(1.5f, 1.5f, 1.5f, 24, 16, new Material(TextureAttribute.createDiffuse(checker)),
                usage | Usage.TextureCoordinates);
            Model cylinder = mb.createCylinder(1f, 1.6f, 1f, 20, new Material(ColorAttribute.createDiffuse(0.2f, 0.3f, 0.9f, 1f),
                ColorAttribute.createEmissive(0.12f, 0f, 0.18f, 1f)), usage);
            Model cone = mb.createCone(1.3f, 1.7f, 1.3f, 20, new Material(ColorAttribute.createDiffuse(0.2f, 0.85f, 0.3f, 1f),
                new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, 0.5f)), usage);

            mb.begin();
            MeshPartBuilder part = mb.part("coloured", GL20.GL_TRIANGLES, Usage.Position | Usage.Normal | Usage.ColorUnpacked, new Material());
            part.setColor(new Color(1f, 0.6f, 0.1f, 1f));
            part.box(1.2f, 1.2f, 1.2f);
            Model coloured = mb.end();

            models.addAll(ground, box, sphere, cylinder, cone, coloured);
            instances.add(new ModelInstance(ground, 0f, -1.1f, 0f));
            instances.add(new ModelInstance(box, -1.8f, -0.3f, 0.3f));
            instances.add(new ModelInstance(sphere, 0.4f, -0.25f, -1.4f));
            instances.add(new ModelInstance(cylinder, 2.2f, -0.2f, 0.4f));
            instances.add(new ModelInstance(coloured, -0.3f, -0.4f, 2.0f));
            instances.get(instances.size - 1).transform.rotate(0f, 1f, 0f, 35f);
            instances.add(new ModelInstance(cone, 0.9f, -0.2f, 0.9f)); // drawn after the opaque ones by the sorter: blended

            environment = new Environment();
            environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.22f, 0.22f, 0.28f, 1f));
            environment.add(new DirectionalLight().set(0.85f, 0.8f, 0.72f, -1f, -0.8f, -0.4f));
            environment.add(new DirectionalLight().set(0.15f, 0.2f, 0.35f, 0.6f, -0.3f, 0.5f));
            environment.add(new PointLight().set(1f, 0.35f, 0.2f, 1.6f, 0.6f, 1.6f, 3f));
            environment.add(new PointLight().set(0.2f, 0.4f, 1f, -2.2f, 1.0f, 1.2f, 2.5f));
        }

        @Override public void draw(ModelApi api, int width, int height) {
            Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
            PerspectiveCamera camera = new PerspectiveCamera(60f, width, height);
            camera.position.set(3.6f, 2.6f, 5.2f);
            camera.lookAt(0f, -0.3f, 0f);
            camera.near = 0.5f;
            camera.far = 30f;
            camera.update();
            api.begin(camera);
            api.render(instances, environment);
            api.end();
        }

        @Override public void dispose() {
            for (Model m : models) m.dispose();
            models.clear();
            instances.clear();
            if (checker != null) checker.dispose();
            checker = null;
        }
    }

    /** libGDX's animated knight (g3db) through the unmodified G3dModelLoader, in a fixed pose of its first animation. */
    private static final class Knight implements Scene {
        private Model model;
        private ModelInstance instance;
        private Environment environment;
        private final Vector3 center = new Vector3();
        private float radius = 1f;

        @Override public String name() { return "gate5_knight"; }

        @Override public void prepare(FileHandle data) {
            model = new G3dModelLoader(new UBJsonReader()).loadModel(data.child("g3d/knight.g3db"));
            instance = new ModelInstance(model);
            if (model.animations.size > 0) {
                AnimationController controller = new AnimationController(instance);
                controller.setAnimation(model.animations.first().id);
                controller.update(0.4f); // a pose, the same on every backend
            }
            BoundingBox box = new BoundingBox();
            instance.calculateBoundingBox(box);
            box.getCenter(center);
            radius = box.getDimensions(new Vector3()).len() / 2f;

            environment = new Environment();
            environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.35f, 0.35f, 0.4f, 1f));
            environment.add(new DirectionalLight().set(0.8f, 0.78f, 0.7f, -0.7f, -0.8f, -0.6f));
            environment.add(new DirectionalLight().set(0.2f, 0.25f, 0.4f, 0.8f, -0.2f, 0.5f));
        }

        @Override public void draw(ModelApi api, int width, int height) {
            Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
            PerspectiveCamera camera = new PerspectiveCamera(45f, width, height);
            camera.position.set(center.x + radius * 0.8f, center.y + radius * 0.3f, center.z + radius * 2.4f);
            camera.lookAt(center);
            camera.near = radius * 0.1f;
            camera.far = radius * 12f;
            camera.update();
            api.begin(camera);
            api.render(java.util.Collections.singletonList(instance), environment);
            api.end();
        }

        @Override public void dispose() {
            if (model != null) model.dispose();
            model = null;
            instance = null;
        }
    }

    /** A receding grid of identical boxes in fog: instanced draws on Vulkan, stock's per-object draws on GL, same picture. */
    private static final class FogInstances implements Scene {
        private Model box, ground;
        private final Array<ModelInstance> instances = new Array<ModelInstance>();
        private Environment environment;

        @Override public String name() { return "gate5_fog"; }

        @Override public void prepare(FileHandle data) {
            final int usage = Usage.Position | Usage.Normal;
            ModelBuilder mb = new ModelBuilder();
            box = mb.createBox(1f, 1f, 1f, new Material(ColorAttribute.createDiffuse(0.8f, 0.45f, 0.2f, 1f)), usage);
            ground = mb.createBox(30f, 0.1f, 60f, new Material(ColorAttribute.createDiffuse(0.4f, 0.5f, 0.35f, 1f)), usage);
            instances.add(new ModelInstance(ground, 0f, -0.55f, -20f));
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 5; col++) {
                    instances.add(new ModelInstance(box, (col - 2) * 2.2f, 0f, -row * 4f));
                }
            }
            environment = new Environment();
            environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.3f, 0.3f, 0.35f, 1f));
            environment.set(new ColorAttribute(ColorAttribute.Fog, 0.45f, 0.5f, 0.6f, 1f));
            environment.add(new DirectionalLight().set(0.85f, 0.8f, 0.75f, -0.5f, -1f, -0.4f));
        }

        @Override public void draw(ModelApi api, int width, int height) {
            Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
            PerspectiveCamera camera = new PerspectiveCamera(55f, width, height);
            camera.position.set(0f, 3f, 9f);
            camera.lookAt(0f, 0f, -14f);
            camera.near = 0.5f;
            camera.far = 40f;
            camera.update();
            api.begin(camera);
            api.render(instances, environment);
            api.end();
        }

        @Override public void dispose() {
            if (box != null) box.dispose();
            if (ground != null) ground.dispose();
            box = ground = null;
            instances.clear();
        }
    }

    /** Specular and emissive textures with their UV transforms. Needs a second texture per draw: Tier A. */
    private static final class Textures implements Scene {
        private Model model;
        private final Array<ModelInstance> instances = new Array<ModelInstance>();
        private Texture specular, emissive, diffuse;
        private Environment environment;

        @Override public String name() { return "gate5_textures"; }

        @Override public boolean runsOnTierB() { return false; }

        private static Texture pattern(int n, boolean stripes) {
            Pixmap pm = new Pixmap(n, n, Pixmap.Format.RGBA8888);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    if (stripes) pm.setColor(((x / 2) & 1) == 0 ? new Color(1f, 0.2f, 0.9f, 1f) : Color.BLACK);
                    else pm.setColor(((x / 2 + y / 2) & 1) == 0 ? Color.WHITE : new Color(0.1f, 0.1f, 0.1f, 1f));
                    pm.drawPixel(x, y);
                }
            }
            Texture t = new Texture(pm);
            t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            pm.dispose();
            return t;
        }

        @Override public void prepare(FileHandle data) {
            specular = pattern(8, false);
            emissive = pattern(8, true);
            diffuse = pattern(4, false);
            final int usage = Usage.Position | Usage.Normal | Usage.TextureCoordinates;
            ModelBuilder mb = new ModelBuilder();
            TextureAttribute spec = TextureAttribute.createSpecular(specular);
            spec.scaleU = spec.scaleV = 2f; // UV transform
            model = mb.createBox(1.6f, 1.6f, 1.6f, new Material(ColorAttribute.createDiffuse(0.55f, 0.6f, 0.7f, 1f), TextureAttribute.createDiffuse(diffuse),
                ColorAttribute.createSpecular(1f, 1f, 1f, 1f), spec, FloatAttribute.createShininess(24f), TextureAttribute.createEmissive(emissive)), usage);
            instances.add(new ModelInstance(model, -1.1f, 0f, 0f));
            instances.add(new ModelInstance(model, 1.3f, 0.2f, -0.6f));
            instances.get(1).transform.rotate(0f, 1f, 0f, 40f);
            environment = new Environment();
            environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.2f, 0.2f, 0.25f, 1f));
            environment.add(new DirectionalLight().set(0.9f, 0.9f, 0.85f, -0.6f, -0.5f, -1f));
            environment.add(new DirectionalLight().set(0.3f, 0.3f, 0.4f, 0.8f, -0.1f, -0.3f));
        }

        @Override public void draw(ModelApi api, int width, int height) {
            Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
            PerspectiveCamera camera = new PerspectiveCamera(50f, width, height);
            camera.position.set(1f, 1.4f, 5f);
            camera.lookAt(0.1f, 0f, 0f);
            camera.near = 0.5f;
            camera.far = 20f;
            camera.update();
            api.begin(camera);
            api.render(instances, environment);
            api.end();
        }

        @Override public void dispose() {
            if (model != null) model.dispose();
            if (specular != null) specular.dispose();
            if (emissive != null) emissive.dispose();
            if (diffuse != null) diffuse.dispose();
            model = null;
            specular = emissive = diffuse = null;
            instances.clear();
        }
    }

    /** A directional shadow light: a depth pass into its framebuffer, then the lit pass samples it. Needs a second texture per draw: Tier A. */
    private static final class Shadows implements Scene {
        private Model floor, box, sphere, cone;
        private final Array<ModelInstance> instances = new Array<ModelInstance>();
        private DirectionalShadowLight shadowLight;
        private Environment environment;

        @Override public String name() { return "gate5_shadows"; }

        @Override public boolean runsOnTierB() { return false; }

        @Override public void prepare(FileHandle data) {
            final int usage = Usage.Position | Usage.Normal;
            ModelBuilder mb = new ModelBuilder();
            floor = mb.createBox(10f, 0.2f, 10f, new Material(ColorAttribute.createDiffuse(0.7f, 0.7f, 0.65f, 1f)), usage);
            box = mb.createBox(1.4f, 1.4f, 1.4f, new Material(ColorAttribute.createDiffuse(0.85f, 0.2f, 0.15f, 1f)), usage);
            sphere = mb.createSphere(1.4f, 1.4f, 1.4f, 20, 14, new Material(ColorAttribute.createDiffuse(0.2f, 0.4f, 0.9f, 1f)), usage);
            cone = mb.createCone(1.2f, 1.8f, 1.2f, 16, new Material(ColorAttribute.createDiffuse(0.2f, 0.8f, 0.3f, 1f)), usage);
            instances.add(new ModelInstance(floor, 0f, -0.1f, 0f));
            instances.add(new ModelInstance(box, -1.8f, 0.8f, 0.2f));
            instances.add(new ModelInstance(sphere, 0.6f, 1.2f, -0.8f));
            instances.add(new ModelInstance(cone, 1.8f, 0.9f, 1.0f));

            shadowLight = new DirectionalShadowLight(1024, 1024, 14f, 14f, 1f, 30f);
            shadowLight.set(0.85f, 0.82f, 0.75f, -0.6f, -1f, -0.5f);
            environment = new Environment();
            environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.28f, 0.28f, 0.33f, 1f));
            environment.add(shadowLight);
            environment.shadowMap = shadowLight;
        }

        @Override public void draw(ModelApi api, int width, int height) {
            shadowLight.begin(Vector3.Zero, new Vector3(0f, 0f, -1f));
            ModelApi shadowPass = api.depth();
            shadowPass.begin(shadowLight.getCamera());
            shadowPass.render(instances);
            shadowPass.end();
            shadowPass.dispose();
            shadowLight.end();

            Gdx.gl.glViewport(0, 0, width, height);
            Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
            PerspectiveCamera camera = new PerspectiveCamera(55f, width, height);
            camera.position.set(4.6f, 4.8f, 6.4f);
            camera.lookAt(0f, 0.3f, 0f);
            camera.near = 0.5f;
            camera.far = 40f;
            camera.update();
            api.begin(camera);
            api.render(instances, environment);
            api.end();
        }

        @Override public void dispose() {
            if (shadowLight != null) shadowLight.dispose();
            if (floor != null) floor.dispose();
            if (box != null) box.dispose();
            if (sphere != null) sphere.dispose();
            if (cone != null) cone.dispose();
            shadowLight = null;
            floor = box = sphere = cone = null;
            instances.clear();
        }
    }

    /** Normal mapping (beyond stock's default shader): two identical boxes, the left one with a normal map under a grazing light. Tier A. */
    private static final class NormalMap implements Scene {
        private Model model;
        private Texture normalMap;
        private final Array<ModelInstance> instances = new Array<ModelInstance>();
        private Environment environment;

        @Override public String name() { return "gate5_normalmap"; }

        @Override public boolean runsOnTierB() { return false; }

        @Override public boolean hasStockReference() { return false; }

        @Override public void prepare(FileHandle data) {
            // A normal map of a grid of round bumps: the normal tilts away from the top of each bump.
            final int n = 64;
            Pixmap pm = new Pixmap(n, n, Pixmap.Format.RGBA8888);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    final double u = x / (double)n, v = y / (double)n;
                    final double dhdu = 0.5 * 8 * Math.PI * Math.cos(8 * Math.PI * u) * Math.sin(8 * Math.PI * v);
                    final double dhdv = 0.5 * 8 * Math.PI * Math.sin(8 * Math.PI * u) * Math.cos(8 * Math.PI * v);
                    final double s = 0.18;
                    double nx = -dhdu * s, ny = -dhdv * s, nz = 1;
                    final double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                    pm.setColor((float)(nx / len * 0.5 + 0.5), (float)(ny / len * 0.5 + 0.5), (float)(nz / len * 0.5 + 0.5), 1f);
                    pm.drawPixel(x, y);
                }
            }
            normalMap = new Texture(pm);
            normalMap.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            normalMap.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            pm.dispose();

            final int usage = Usage.Position | Usage.Normal | Usage.TextureCoordinates;
            ModelBuilder mb = new ModelBuilder();
            TextureAttribute normal = TextureAttribute.createNormal(normalMap);
            normal.scaleU = normal.scaleV = 2f;
            Model mapped = mb.createBox(2.2f, 2.2f, 2.2f, new Material(ColorAttribute.createDiffuse(0.7f, 0.7f, 0.72f, 1f), normal), usage);
            Model plain = mb.createBox(2.2f, 2.2f, 2.2f, new Material(ColorAttribute.createDiffuse(0.7f, 0.7f, 0.72f, 1f)), usage);
            // One model owning both, so there is one thing to dispose.
            model = mapped;
            instances.add(new ModelInstance(mapped, -1.5f, 0f, 0f));
            plain2 = plain;
            instances.add(new ModelInstance(plain, 1.5f, 0f, 0f));
            environment = new Environment();
            environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.12f, 0.12f, 0.14f, 1f));
            environment.add(new DirectionalLight().set(0.9f, 0.88f, 0.8f, -1f, -0.35f, -0.5f));
            environment.add(new DirectionalLight().set(0.25f, 0.3f, 0.45f, 0.8f, 0.2f, -0.6f));
        }

        private Model plain2;

        @Override public void draw(ModelApi api, int width, int height) {
            Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
            PerspectiveCamera camera = new PerspectiveCamera(45f, width, height);
            camera.position.set(0f, 0.6f, 7.5f);
            camera.lookAt(0f, 0f, 0f);
            camera.near = 0.5f;
            camera.far = 30f;
            camera.update();
            api.begin(camera);
            api.render(instances, environment);
            api.end();
        }

        /** The mapped box's lit face varies a lot more than the plain box's flat-lit face. */
        @Override public void verify(byte[] px, int w, int h) {
            final double mapped = lumaVariance(px, w, h, w * 0.20, w * 0.45), plain = lumaVariance(px, w, h, w * 0.55, w * 0.80);
            if (!(mapped > 2.5 * plain + 20)) {
                throw new AssertionError("normal mapping should add detail: luminance variance " + mapped + " (mapped box) against " + plain + " (plain box)");
            }
        }

        private static double lumaVariance(byte[] px, int w, int h, double x0, double x1) {
            double sum = 0, sum2 = 0;
            int n = 0;
            for (int y = (int)(h * 0.42); y < (int)(h * 0.58); y++) {
                for (int x = (int)x0; x < (int)x1; x++) {
                    final int o = (y * w + x) * 4;
                    final double l = 0.299 * (px[o] & 255) + 0.587 * (px[o + 1] & 255) + 0.114 * (px[o + 2] & 255);
                    sum += l;
                    sum2 += l * l;
                    n++;
                }
            }
            final double mean = sum / n;
            return sum2 / n - mean * mean;
        }

        @Override public void dispose() {
            if (model != null) model.dispose();
            if (plain2 != null) plain2.dispose();
            if (normalMap != null) normalMap.dispose();
            model = plain2 = null;
            normalMap = null;
            instances.clear();
        }
    }

    /** Cascaded shadow maps (beyond stock, which has one map): columns down a long road, near and far, all shadowed. Tier A. */
    private static final class CascadedShadows implements Scene {
        private Model floor, column;
        private final Array<ModelInstance> instances = new Array<ModelInstance>();
        private io.github.slebed.gdxvulkan.g3d.VulkanCascadedShadowLight light;
        private Environment environment;

        @Override public String name() { return "gate5_cascades"; }

        @Override public boolean runsOnTierB() { return false; }

        @Override public boolean hasStockReference() { return false; }

        @Override public void prepare(FileHandle data) {
            final int usage = Usage.Position | Usage.Normal;
            ModelBuilder mb = new ModelBuilder();
            floor = mb.createBox(60f, 0.2f, 120f, new Material(ColorAttribute.createDiffuse(0.75f, 0.75f, 0.7f, 1f)), usage);
            column = mb.createBox(1.2f, 4f, 1.2f, new Material(ColorAttribute.createDiffuse(0.85f, 0.3f, 0.2f, 1f)), usage);
            instances.add(new ModelInstance(floor, 0f, -0.1f, -30f));
            for (int i = 0; i < 8; i++) {
                final float z = -3f - i * i * 1.1f - i * 2f; // spreading out with distance: the far ones are small and need a cascade of their own
                instances.add(new ModelInstance(column, -3.5f, 2f, z));
                instances.add(new ModelInstance(column, 3.5f, 2f, z - 2f));
            }
            light = new io.github.slebed.gdxvulkan.g3d.VulkanCascadedShadowLight(1024, 3, 0.5f, 70f, 0.7f, 40f);
            light.set(0.9f, 0.86f, 0.78f, -0.55f, -1f, -0.35f);
            environment = new Environment();
            environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.3f, 0.3f, 0.35f, 1f));
            environment.add(light);
            environment.shadowMap = light;
        }

        @Override public void draw(ModelApi api, int width, int height) {
            PerspectiveCamera camera = new PerspectiveCamera(55f, width, height);
            camera.position.set(0f, 4.5f, 8f);
            camera.lookAt(0f, 0.5f, -30f);
            camera.near = 0.5f;
            camera.far = 80f;
            camera.update();

            light.update(camera);
            ModelApi shadowPass = api.depth();
            for (int i = 0; i < light.cascades(); i++) {
                light.begin(i);
                shadowPass.begin(light.camera(i));
                shadowPass.render(instances);
                shadowPass.end();
                light.end(i);
            }
            shadowPass.dispose();

            Gdx.gl.glViewport(0, 0, width, height);
            Gdx.gl.glClear(GL20.GL_DEPTH_BUFFER_BIT);
            api.begin(camera);
            api.render(instances, environment);
            api.end();
        }

        /** Shadows must reach the far columns too: both a near and a far band of the road show lit and shadowed ground. */
        @Override public void verify(byte[] px, int w, int h) {
            final int[] bands = {(int)(h * 0.80), (int)(h * 0.50)}; // nearer ground, farther ground (row 0 is the top)
            for (int band : bands) {
                float lo = 255, hi = 0;
                for (int y = band - 6; y < band + 6; y++) {
                    for (int x = 0; x < w; x++) {
                        final int o = (y * w + x) * 4;
                        if (Math.abs((px[o] & 255) - (px[o + 2] & 255)) > 40) continue; // not the grey-beige ground (a column)
                        final float l = 0.299f * (px[o] & 255) + 0.587f * (px[o + 1] & 255) + 0.114f * (px[o + 2] & 255);
                        if (l < 5) continue; // background
                        lo = Math.min(lo, l);
                        hi = Math.max(hi, l);
                    }
                }
                if (!(hi > 1.5f * lo)) throw new AssertionError("no shadow on the ground in the band at row " + band + ": luminance " + lo + " to " + hi);
            }
        }

        @Override public void dispose() {
            if (light != null) light.dispose();
            if (floor != null) floor.dispose();
            if (column != null) column.dispose();
            light = null;
            floor = column = null;
            instances.clear();
        }
    }
}
