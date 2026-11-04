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

        void end();

        void dispose();
    }

    public interface Scene {
        String name();

        /** @param data the bundled test assets' data directory */
        void prepare(FileHandle data) throws Exception;

        void draw(ModelApi api, int width, int height) throws Exception;

        void dispose();
    }

    private Gate5Scenes() {}

    public static Scene[] all() {
        Scene[] all = {new Models(), new Knight()};
        final String only = System.getenv("GDXVK_G5_ONLY"); // diagnosis: run one scene
        if (only == null) return all;
        for (Scene sc : all) if (sc.name().contains(only)) return new Scene[] {sc};
        return all;
    }

    /** Stock ModelBatch on OpenGL. */
    public static ModelApi stock() {
        final ModelBatch b = new ModelBatch();
        return new ModelApi() {
            public void begin(com.badlogic.gdx.graphics.Camera c) { b.begin(c); }
            public void render(Iterable<ModelInstance> p, Environment e) { b.render(p, e); }
            public void end() { b.end(); }
            public void dispose() { b.dispose(); }
        };
    }

    public static ModelApi vulkan(GdxVkFrame frame) {
        final VulkanModelBatch b = new VulkanModelBatch(frame);
        return new ModelApi() {
            public void begin(com.badlogic.gdx.graphics.Camera c) { b.begin(c); }
            public void render(Iterable<ModelInstance> p, Environment e) { b.render(p, e); }
            public void end() { b.end(); }
            public void dispose() { b.dispose(); }
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
}
