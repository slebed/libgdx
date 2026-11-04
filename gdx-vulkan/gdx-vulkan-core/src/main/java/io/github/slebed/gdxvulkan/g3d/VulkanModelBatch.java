package io.github.slebed.gdxvulkan.g3d;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DirectionalLightsAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.PointLightsAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.AmbientCubemap;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.environment.PointLight;
import com.badlogic.gdx.graphics.g3d.environment.ShadowMap;
import com.badlogic.gdx.graphics.g3d.utils.DefaultRenderableSorter;
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter;
import com.badlogic.gdx.graphics.g3d.utils.TextureDescriptor;
import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FlushablePool;
import com.badlogic.gdx.utils.GdxRuntimeException;

import io.github.slebed.gdxvulkan.CommandStream;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPipelineDesc;
import io.github.slebed.gdxvulkan.GdxVkSampler;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.util.HashMap;
import java.util.Map;

/**
 * The 3D batch (plan, Phase 5): the stock {@code ModelBatch} API on Vulkan. It takes stock {@code RenderableProvider}s ({@code ModelInstance},
 * ...), sorts them with the stock sorter, and draws them with a port of libGDX's default shader (per-vertex lighting with the ambient cubemap,
 * directional and point lights, specular, diffuse, specular and emissive textures, fog, directional shadow maps, opacity and alpha test, the same
 * material-to-state rules as {@code DefaultShader}), so a scene looks as it does on OpenGL.
 *
 * <p>Per flush, one record per renderable (transform, normal matrix, material) and one header (camera, lights, fog, shadow map) go to Set 0's
 * storage binding through a {@code Set0Data} command; the draws pick their record by an index in the push constants, and identical renderables that
 * follow one another in the sorted order become one instanced draw. Meshes come from {@link StockMeshAccess}. The vertex shader is one of 40 (a mesh
 * has or lacks normals, colours and texture coordinates, and 0 to 4 bone weights); the material and lighting flags are specialization constants,
 * so there is one pipeline per distinct material shape.
 *
 * <p>{@link #depth} makes the batch for the shadow pass ({@code DepthShaderProvider}'s job in stock): it draws packed depth, with front-face
 * culling and the blended-texture alpha test, into the shadow light's framebuffer.
 *
 * <p>Supported: position, normal, colour (packed or unpacked), the first texture coordinate set and up to 4 bone weights; diffuse colour and
 * texture, specular colour and texture, shininess, emissive colour and texture, blending (with opacity), alpha test, cull face and depth-test
 * attributes; an ambient light, up to {@link #MAX_DIRECTIONAL_LIGHTS} directional and {@link #MAX_POINT_LIGHTS} point lights (the stock configuration
 * is 2 and 5; further lights go into the ambient cubemap as stock does), fog, one shadow map. Not supported: spot lights (stock's default shader has
 * none either), reflection, custom shaders. On Tier B (one texture per draw) only the diffuse texture is sampled: specular and emissive textures and
 * shadow maps are ignored there.
 *
 * <p>Like the other Vulkan batches it holds a {@link GdxVkFrame} and belongs to the render thread.
 */
public class VulkanModelBatch implements Disposable {
    public static final int MAX_DIRECTIONAL_LIGHTS = 4, MAX_POINT_LIGHTS = 8;

    // Layout of the data blob in vec4 units; mirrors model_default_vertex.glsl.
    private static final int H_PROJVIEW = 0, H_CAMERA = 4, H_AMBIENT = 5, H_DIR = 11, H_POINT = 19, H_FOG = 35, H_SHADOW = 36, H_SHADOWP = 40, H_CASCADE = 41, H_CASCADEP = 53, H_CASCADES = 54, HEADER = 55,
        RECORD = 15;

    /** The number of directional and point lights the shaders evaluate; the stock {@code DefaultShader.Config} defaults. */
    public static final class Config {
        public int numDirectionalLights = 2;
        public int numPointLights = 5;
    }

    protected static class RenderablePool extends FlushablePool<Renderable> {
        @Override protected Renderable newObject () {
            return new Renderable();
        }

        @Override public Renderable obtain () {
            Renderable renderable = super.obtain();
            renderable.environment = null;
            renderable.material = null;
            renderable.meshPart.set("", null, 0, 0, 0);
            renderable.shader = null;
            renderable.userData = null;
            return renderable;
        }
    }

    private final GdxVkFrame frame;
    private final VulkanGL20 gl;
    private final boolean tierA;
    private final boolean depthPass;
    private final StockMeshAccess meshes;
    private final RenderableSorter sorter;
    private final Config config;
    private final RenderablePool pool = new RenderablePool();
    private final Array<Renderable> renderables = new Array<Renderable>();
    private Camera camera;

    private final Map<String, Integer> pipelineIds = new HashMap<String, Integer>();
    private static final Matrix4 IDENTITY = new Matrix4();
    private final Matrix3 tmpM3 = new Matrix3();
    private final Vector3 tmpV = new Vector3();
    private final AmbientCubemap cubemap = new AmbientCubemap();
    private final float[] pushIndices = new float[4];
    private final float[] pushNormalSlot = new float[1];
    private int[] words = new int[(HEADER + 64 * RECORD) * 4];
    private int instancedDraws, draws;

    // GL state as DefaultShader's RenderContext keeps it, so only changes reach the stream.
    private int cullFace, depthFunc;
    private boolean blending, depthMask;
    private int blendSrc, blendDst;

    /** Like stock's {@code new ModelBatch()}: draws into the running application's frame ({@link GdxVkFrame#current()}). */
    public VulkanModelBatch () {
        this(GdxVkFrame.current(), new DefaultRenderableSorter(), new Config(), false);
    }

    public VulkanModelBatch (RenderableSorter sorter) {
        this(GdxVkFrame.current(), sorter, new Config(), false);
    }

    public VulkanModelBatch (RenderableSorter sorter, Config config) {
        this(GdxVkFrame.current(), sorter, config, false);
    }

    public VulkanModelBatch (GdxVkFrame frame) {
        this(frame, new DefaultRenderableSorter(), new Config(), false);
    }

    public VulkanModelBatch (GdxVkFrame frame, RenderableSorter sorter) {
        this(frame, sorter, new Config(), false);
    }

    public VulkanModelBatch (GdxVkFrame frame, RenderableSorter sorter, Config config) {
        this(frame, sorter, config, false);
    }

    /** The batch for the shadow pass: what stock does with {@code new ModelBatch(new DepthShaderProvider())}. */
    public static VulkanModelBatch depth (GdxVkFrame frame) {
        return new VulkanModelBatch(frame, new DefaultRenderableSorter(), new Config(), true);
    }

    private VulkanModelBatch (GdxVkFrame frame, RenderableSorter sorter, Config config, boolean depthPass) {
        if (config.numDirectionalLights > MAX_DIRECTIONAL_LIGHTS || config.numPointLights > MAX_POINT_LIGHTS) {
            throw new IllegalArgumentException("at most " + MAX_DIRECTIONAL_LIGHTS + " directional and " + MAX_POINT_LIGHTS + " point lights");
        }
        this.frame = frame;
        this.gl = frame.gl();
        this.tierA = frame.isTierA();
        this.depthPass = depthPass;
        if (!depthPass) frame.requireDepth(io.github.slebed.gdxvulkan.GdxVkPresenter.DEPTH); // stock's GL context has a depth buffer; so does this screen
        this.meshes = new StockMeshAccess(gl);
        this.sorter = sorter;
        this.config = config;
    }

    // ------------------------------------------------------------------------------------------------------------ the ModelBatch API

    public void begin (final Camera cam) {
        if (camera != null) throw new GdxRuntimeException("Call end() first.");
        camera = cam;
    }

    public void setCamera (final Camera cam) {
        if (camera == null) throw new GdxRuntimeException("Call begin() first.");
        if (renderables.size > 0) flush();
        camera = cam;
    }

    public Camera getCamera () {
        return camera;
    }

    public boolean isDrawing () {
        return camera != null;
    }

    /** Sorts and draws everything queued so far. */
    public void flush () {
        if (renderables.size == 0) return;
        sorter.sort(camera, renderables);
        draw(renderables);
        pool.flush();
        renderables.clear();
    }

    public void end () {
        flush();
        camera = null;
    }

    public void render (final Renderable renderable) {
        measure(renderable);
        renderables.add(renderable);
    }

    /**
     * The centre and radius the sorter and the culling use. A model's parts have them from the moment it was built (stock's ModelBatch relies on that and never
     * recomputes them); only a part that was put together by hand, with no bounds yet, is measured here, once. Walking every vertex of every part on every frame
     * took most of a frame's CPU time in a scene of a hundred objects.
     */
    private static void measure (final Renderable renderable) {
        if (renderable.meshPart.radius < 0f) renderable.meshPart.update();
    }

    public void render (final RenderableProvider renderableProvider) {
        final int offset = renderables.size;
        renderableProvider.getRenderables(renderables, pool);
        for (int i = offset; i < renderables.size; i++) measure(renderables.get(i));
    }

    public void render (final RenderableProvider renderableProvider, final Environment environment) {
        final int offset = renderables.size;
        renderableProvider.getRenderables(renderables, pool);
        for (int i = offset; i < renderables.size; i++) {
            Renderable renderable = renderables.get(i);
            renderable.environment = environment;
            measure(renderable);
        }
    }

    public <T extends RenderableProvider> void render (final Iterable<T> renderableProviders) {
        for (final RenderableProvider provider : renderableProviders) render(provider);
    }

    public <T extends RenderableProvider> void render (final Iterable<T> renderableProviders, final Environment environment) {
        for (final RenderableProvider provider : renderableProviders) render(provider, environment);
    }

    @Override public void dispose () {
        // Pipelines belong to the frame's presenter and the shared white texture to the context: nothing is owned here. (Disposing a batch
        // before the frame that used it is submitted, as callers do, must not pull anything out from under the recorded draws.)
    }

    /** Draw calls issued since the batch was made, and how many of them were instanced (diagnostics). */
    public int getDrawCalls () {
        return draws;
    }

    public int getInstancedDrawCalls () {
        return instancedDraws;
    }

    private static final java.util.Map<GdxVkContext, Integer> WHITE = new java.util.WeakHashMap<GdxVkContext, Integer>();

    /** A 1 x 1 white texture, bound for draws that sample nothing (Tier B has exactly one texture set bound at a time). One per context. */
    private int whiteTexture () {
        synchronized (WHITE) {
            Integer id = WHITE.get(frame.context());
            if (id == null) {
                id = frame.context().resources().createTexture(1, 1, new byte[] {-1, -1, -1, -1}, new GdxVkSampler());
                WHITE.put(frame.context(), id);
            }
            return id;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------ drawing

    private void draw (Array<Renderable> list) {
        beginState();
        int i = 0;
        while (i < list.size) {
            final Environment env = list.get(i).environment;
            int j = i + 1;
            while (j < list.size && list.get(j).environment == env) j++;
            drawGroup(list, i, j, env);
            i = j;
        }
        endState();
    }

    /** What RenderContext.begin does: known GL state at the start of a flush. */
    private void beginState () {
        gl.glDisable(GL20.GL_DEPTH_TEST);
        depthFunc = 0;
        gl.glDepthMask(true);
        depthMask = true;
        gl.glDisable(GL20.GL_BLEND);
        blending = false;
        gl.glDisable(GL20.GL_CULL_FACE);
        cullFace = blendSrc = blendDst = 0;
    }

    /** What RenderContext.end does. */
    private void endState () {
        if (depthFunc != 0) gl.glDisable(GL20.GL_DEPTH_TEST);
        if (blending) gl.glDisable(GL20.GL_BLEND);
        if (cullFace > 0) gl.glDisable(GL20.GL_CULL_FACE);
    }

    private void setBlending (boolean enabled, int src, int dst) {
        if (enabled != blending) {
            blending = enabled;
            if (enabled) gl.glEnable(GL20.GL_BLEND);
            else gl.glDisable(GL20.GL_BLEND);
        }
        if (enabled && (blendSrc != src || blendDst != dst)) {
            gl.glBlendFunc(src, dst);
            blendSrc = src;
            blendDst = dst;
        }
    }

    private void setCullFace (int face) {
        if (face != cullFace) {
            cullFace = face;
            if (face == GL20.GL_FRONT || face == GL20.GL_BACK || face == GL20.GL_FRONT_AND_BACK) {
                gl.glEnable(GL20.GL_CULL_FACE);
                gl.glCullFace(face);
            } else {
                gl.glDisable(GL20.GL_CULL_FACE);
            }
        }
    }

    private void setDepthTest (int func) {
        if (depthFunc != func) {
            depthFunc = func;
            if (func == 0) {
                gl.glDisable(GL20.GL_DEPTH_TEST);
            } else {
                gl.glEnable(GL20.GL_DEPTH_TEST);
                gl.glDepthFunc(func);
            }
        }
    }

    private void setDepthMask (boolean mask) {
        if (depthMask != mask) {
            gl.glDepthMask(mask);
            depthMask = mask;
        }
    }

    private static Attribute attr (Renderable r, long type) {
        Attribute a = r.material == null ? null : r.material.get(type);
        if (a == null && r.environment != null) a = r.environment.get(type);
        return a;
    }

    private void ensure (int n) {
        if (words.length < n) words = java.util.Arrays.copyOf(words, Math.max(n, words.length * 2));
    }

    private void putF (int at, float v) {
        words[at] = Float.floatToRawIntBits(v);
    }

    private void putVec4 (int vec4, float x, float y, float z, float w) {
        final int p = vec4 * 4;
        putF(p, x);
        putF(p + 1, y);
        putF(p + 2, z);
        putF(p + 3, w);
    }

    private void putColor (int vec4, Color c) {
        putVec4(vec4, c.r, c.g, c.b, c.a);
    }

    private void putMatrix4 (int vec4, Matrix4 m) {
        for (int k = 0; k < 16; k++) putF(vec4 * 4 + k, m.val[k]);
    }

    /** What the depth pass draws: not a degenerate transform, and a blended material only when its opacity reaches the alpha-test value. */
    private boolean visible (Renderable r) {
        if (r.worldTransform.det3x3() == 0) return false;
        if (depthPass) {
            final BlendingAttribute blend = (BlendingAttribute)attr(r, BlendingAttribute.Type);
            if (blend != null && blend.opacity < alphaTestValue(r)) return false;
        }
        return true;
    }

    /** The depth pass's alpha-test threshold: the material's, else DepthShader.Config's default of 0.5. */
    private static float alphaTestValue (Renderable r) {
        final FloatAttribute a = (FloatAttribute)attr(r, FloatAttribute.AlphaTest);
        return a != null ? a.value : 0.5f;
    }

    /** The header of a group: camera, ambient cubemap, lights, fog and the shadow map, as DefaultShader's setters compute them. */
    private void writeHeader (Environment env, Renderable first) {
        putMatrix4(H_PROJVIEW, camera.combined);
        putVec4(H_CAMERA, camera.position.x, camera.position.y, camera.position.z, 1.1881f / (camera.far * camera.far));
        if (depthPass) return;

        final boolean lit = env != null;
        if (!lit) {
            for (int k = 0; k < 6; k++) putVec4(H_AMBIENT + k, 1, 1, 1, 0);
        } else {
            first.worldTransform.getTranslation(tmpV);
            final ColorAttribute ambient = env.get(ColorAttribute.class, ColorAttribute.AmbientLight);
            if (ambient != null) cubemap.set(ambient.color);
            else cubemap.set(Color.BLACK);
            final DirectionalLightsAttribute dla = env.get(DirectionalLightsAttribute.class, DirectionalLightsAttribute.Type);
            if (dla != null) {
                for (int k = config.numDirectionalLights; k < dla.lights.size; k++) cubemap.add(dla.lights.get(k).color, dla.lights.get(k).direction);
            }
            final PointLightsAttribute pla = env.get(PointLightsAttribute.class, PointLightsAttribute.Type);
            if (pla != null) {
                for (int k = config.numPointLights; k < pla.lights.size; k++) {
                    final PointLight l = pla.lights.get(k);
                    cubemap.add(l.color, l.position, tmpV, l.intensity);
                }
            }
            cubemap.clamp();
            for (int k = 0; k < 6; k++) putVec4(H_AMBIENT + k, cubemap.data[k * 3], cubemap.data[k * 3 + 1], cubemap.data[k * 3 + 2], 0);

            for (int k = 0; k < MAX_DIRECTIONAL_LIGHTS; k++) {
                if (dla != null && k < dla.lights.size && k < config.numDirectionalLights) {
                    final DirectionalLight l = dla.lights.get(k);
                    putVec4(H_DIR + k * 2, l.color.r, l.color.g, l.color.b, 0);
                    putVec4(H_DIR + k * 2 + 1, l.direction.x, l.direction.y, l.direction.z, 0);
                } else {
                    putVec4(H_DIR + k * 2, 0, 0, 0, 0);
                    putVec4(H_DIR + k * 2 + 1, 0, -1, 0, 0);
                }
            }
            for (int k = 0; k < MAX_POINT_LIGHTS; k++) {
                if (pla != null && k < pla.lights.size && k < config.numPointLights) {
                    final PointLight l = pla.lights.get(k);
                    putVec4(H_POINT + k * 2, l.color.r * l.intensity, l.color.g * l.intensity, l.color.b * l.intensity, 0);
                    putVec4(H_POINT + k * 2 + 1, l.position.x, l.position.y, l.position.z, 0);
                } else {
                    putVec4(H_POINT + k * 2, 0, 0, 0, 0);
                    putVec4(H_POINT + k * 2 + 1, 0, 0, 0, 0);
                }
            }
        }
        final ColorAttribute fog = env == null ? null : env.get(ColorAttribute.class, ColorAttribute.Fog);
        if (fog != null) putColor(H_FOG, fog.color);
        else putVec4(H_FOG, 0, 0, 0, 0);

        final ShadowMap shadow = shadowOf(env);
        if (shadow != null) {
            putMatrix4(H_SHADOW, shadow.getProjViewTrans());
            final TextureDescriptor<Texture> desc = shadow.getDepthMap();
            final Texture t = bindTextureState(desc);
            final int nativeId = nativeTexture(t);
            putF(H_SHADOWP * 4, 1f / (2f * t.getWidth())); // u_shadowPCFOffset
            words[H_SHADOWP * 4 + 1] = gl.slotOf(nativeId);
            if (shadow instanceof VulkanCascadedShadowLight) {
                final VulkanCascadedShadowLight c = (VulkanCascadedShadowLight)shadow;
                for (int k = 1; k < c.cascades(); k++) {
                    putMatrix4(H_CASCADE + (k - 1) * 4, c.projViewTrans(k));
                    final Texture ct = bindTextureState(c.depthMap(k));
                    words[H_CASCADES * 4 + k - 1] = gl.slotOf(nativeTexture(ct));
                }
                putVec4(H_CASCADEP, c.split(0), c.split(1), c.split(2), 0);
            }
        } else {
            putVec4(H_SHADOWP, 0, 0, 0, 0);
        }
    }

    private int cascadesOf (Environment env) {
        final ShadowMap s = shadowOf(env);
        return s instanceof VulkanCascadedShadowLight ? ((VulkanCascadedShadowLight)s).cascades() : 1;
    }

    /** The environment's shadow map, where this tier can use it (the main shader would need a second texture on Tier B). */
    private ShadowMap shadowOf (Environment env) {
        return tierA && !depthPass && env != null ? env.shadowMap : null;
    }

    /** One renderable's record at vec4 {@code at}: transform, normal matrix, material colours and parameters. */
    private void writeRecord (int at, Renderable r) {
        putMatrix4(at, r.worldTransform);
        tmpM3.set(r.worldTransform).inv().transpose(); // as DefaultShader's normalMatrix setter
        final float[] n = tmpM3.val;
        putVec4(at + 4, n[0], n[1], n[2], 0);
        putVec4(at + 5, n[3], n[4], n[5], 0);
        putVec4(at + 6, n[6], n[7], n[8], 0);

        final ColorAttribute diffuse = (ColorAttribute)attr(r, ColorAttribute.Diffuse);
        if (diffuse != null) putColor(at + 7, diffuse.color);
        else putVec4(at + 7, 1, 1, 1, 1);
        final ColorAttribute specular = (ColorAttribute)attr(r, ColorAttribute.Specular);
        if (specular != null) putColor(at + 8, specular.color);
        else putVec4(at + 8, 0, 0, 0, 0);
        final ColorAttribute emissive = (ColorAttribute)attr(r, ColorAttribute.Emissive);
        if (emissive != null) putColor(at + 9, emissive.color);
        else putVec4(at + 9, 0, 0, 0, 0);

        final FloatAttribute shininess = (FloatAttribute)attr(r, FloatAttribute.Shininess);
        final BlendingAttribute blend = (BlendingAttribute)attr(r, BlendingAttribute.Type);
        final FloatAttribute alphaTest = (FloatAttribute)attr(r, FloatAttribute.AlphaTest);
        putVec4(at + 10, shininess != null ? shininess.value : 20f, blend != null ? blend.opacity : 1f,
            depthPass ? alphaTestValue(r) : alphaTest != null ? alphaTest.value : 0f, 0);

        putUV(at + 11, (TextureAttribute)attr(r, TextureAttribute.Diffuse));
        putUV(at + 12, (TextureAttribute)attr(r, TextureAttribute.Specular));
        putUV(at + 13, (TextureAttribute)attr(r, TextureAttribute.Emissive));
        putUV(at + 14, (TextureAttribute)attr(r, TextureAttribute.Normal));
    }

    private void putUV (int vec4, TextureAttribute t) {
        if (t != null) putVec4(vec4, t.offsetU, t.offsetV, t.scaleU, t.scaleV);
        else putVec4(vec4, 0, 0, 1, 1);
    }

    private void drawGroup (Array<Renderable> list, int from, int to, Environment env) {
        // The records of the renderables that will draw.
        int count = 0, boneVec4s = 0;
        Renderable first = null;
        for (int i = from; i < to; i++) {
            final Renderable r = list.get(i);
            if (!visible(r)) continue;
            if (first == null) first = r;
            count++;
            if (r.bones != null) boneVec4s += r.bones.length * 4;
        }
        if (count == 0) return;
        final int vec4s = HEADER + count * RECORD + boneVec4s;
        ensure(vec4s * 4);
        writeHeader(env, first);
        int k = 0, boneAt = HEADER + count * RECORD;
        for (int i = from; i < to; i++) {
            final Renderable r = list.get(i);
            if (!visible(r)) continue;
            writeRecord(HEADER + k * RECORD, r);
            if (r.bones != null) { // the bone matrices follow the records; the record names where its own start
                putF((HEADER + k * RECORD + 10) * 4 + 3, boneAt);
                for (int b = 0; b < r.bones.length; b++, boneAt += 4) {
                    if (r.bones[b] != null) putMatrix4(boneAt, r.bones[b]);
                    else putMatrix4(boneAt, IDENTITY);
                }
            }
            k++;
        }
        final CommandStream s = frame.stream();
        s.set0Data(words, 0, vec4s * 16);

        Mesh lastMesh = null;
        int lastPipeline = -1;
        k = 0;
        int i = from;
        while (i < to) {
            final Renderable r = list.get(i);
            if (!visible(r)) {
                i++;
                continue;
            }
            // Identical renderables that follow in the sorted order are one instanced draw: their records are consecutive.
            int run = 1;
            int j = i + 1;
            if (r.bones == null) {
                while (j < to) {
                    final Renderable n = list.get(j);
                    if (!visible(n)) {
                        j++;
                        continue;
                    }
                    if (!sameDraw(r, n)) break;
                    run++;
                    j++;
                }
            } else {
                j = i + 1;
            }
            final int record = HEADER + k * RECORD;
            k += run;
            i = j;

            applyMaterialState(r);
            final Mesh mesh = r.meshPart.mesh;
            final int pipeline = pipelineFor(r, mesh);
            if (pipeline != lastPipeline) {
                s.bindPipeline(pipeline);
                lastPipeline = pipeline;
            }
            final int[] slots = bindMaterialTextures(r, mesh, s);
            pushIndices[0] = Float.intBitsToFloat(record);
            pushIndices[1] = Float.intBitsToFloat(slots[0]);
            pushIndices[2] = Float.intBitsToFloat(slots[1]);
            pushIndices[3] = Float.intBitsToFloat(slots[2]);
            s.pushRange(20, pushIndices, 0, 4); // u_indices: record, diffuse, specular, emissive slot
            pushNormalSlot[0] = Float.intBitsToFloat(slots[3]);
            s.pushRange(24, pushNormalSlot, 0, 1); // u_custom0.x: the normal map's slot
            if (mesh != lastMesh) {
                meshes.bind(frame, mesh);
                lastMesh = mesh;
            }
            draws++;
            if (mesh.getNumIndices() > 0) {
                if (run > 1) {
                    instancedDraws++;
                    s.drawIndexedInstanced(r.meshPart.size, r.meshPart.offset, 0, run);
                } else {
                    s.drawIndexed(r.meshPart.size, r.meshPart.offset, 0);
                }
            } else {
                s.draw(r.meshPart.size, r.meshPart.offset);
            }
        }
    }

    /** Two renderables that one instanced draw can render: the same mesh part and material, no bones. */
    private boolean sameDraw (Renderable a, Renderable b) {
        if (b.bones != null || a.meshPart.mesh != b.meshPart.mesh || a.meshPart.offset != b.meshPart.offset || a.meshPart.size != b.meshPart.size
            || a.meshPart.primitiveType != b.meshPart.primitiveType || a.environment != b.environment) return false;
        if (a.meshPart.mesh.getNumIndices() == 0) return false;
        return a.material == b.material || (a.material != null && a.material.equals(b.material));
    }

    /** The texture slots (Tier A) or the one bound texture (Tier B) of a draw: diffuse, specular, emissive. */
    private final int[] slotScratch = new int[4];

    private int[] bindMaterialTextures (Renderable r, Mesh mesh, CommandStream s) {
        slotScratch[0] = slotScratch[1] = slotScratch[2] = slotScratch[3] = 0;
        final boolean uv = (mesh.getVertexAttributes().getMask() & Usage.TextureCoordinates) != 0;
        // The depth pass samples the diffuse texture only to alpha-test a blended material.
        final boolean wantsDiffuse = uv && (!depthPass || attr(r, BlendingAttribute.Type) != null);
        final TextureAttribute diffuse = wantsDiffuse ? (TextureAttribute)attr(r, TextureAttribute.Diffuse) : null;
        boolean boundOne = false;
        if (diffuse != null) {
            final Texture t = bindTextureState(diffuse.textureDescription);
            final int nativeId = nativeTexture(t);
            if (tierA) slotScratch[0] = gl.slotOf(nativeId);
            else {
                s.bindTexture(nativeId);
                boundOne = true;
            }
        }
        if (tierA && uv && !depthPass) {
            final TextureAttribute spec = (TextureAttribute)attr(r, TextureAttribute.Specular);
            if (spec != null) slotScratch[1] = gl.slotOf(nativeTexture(bindTextureState(spec.textureDescription)));
            final TextureAttribute emi = (TextureAttribute)attr(r, TextureAttribute.Emissive);
            if (emi != null) slotScratch[2] = gl.slotOf(nativeTexture(bindTextureState(emi.textureDescription)));
            final TextureAttribute nor = (TextureAttribute)attr(r, TextureAttribute.Normal);
            if (nor != null && r.environment != null && (mesh.getVertexAttributes().getMask() & Usage.Normal) != 0)
                slotScratch[3] = gl.slotOf(nativeTexture(bindTextureState(nor.textureDescription)));
        }
        if (!tierA && !boundOne) s.bindTexture(whiteTexture()); // Tier B shaders reference the texture binding, so every draw needs one bound
        return slotScratch;
    }

    private int nativeTexture (Texture t) {
        final int id = gl.nativeTexture(t.getTextureObjectHandle());
        if (id < 0) throw new GdxRuntimeException("gdx-vulkan: texture has no storage (nothing was uploaded to it)");
        return id;
    }

    /** DefaultShader.bindMaterial: blending, cull face and depth test from the material's attributes, defaults otherwise. */
    private void applyMaterialState (Renderable r) {
        int cull = depthPass ? GL20.GL_FRONT : GL20.GL_BACK; // DepthShader.Config.defaultCullFace
        int depth = GL20.GL_LEQUAL;
        boolean mask = true;
        boolean blended = false;
        int src = GL20.GL_SRC_ALPHA, dst = GL20.GL_ONE_MINUS_SRC_ALPHA;
        if (r.material != null) {
            for (final Attribute a : r.material) {
                final long t = a.type;
                if (BlendingAttribute.is(t)) {
                    blended = !depthPass; // the depth pass removes blending
                    src = ((BlendingAttribute)a).sourceFunction;
                    dst = ((BlendingAttribute)a).destFunction;
                } else if ((t & IntAttribute.CullFace) == IntAttribute.CullFace) {
                    cull = ((IntAttribute)a).value;
                } else if ((t & DepthTestAttribute.Type) == DepthTestAttribute.Type) {
                    final DepthTestAttribute d = (DepthTestAttribute)a;
                    depth = d.depthFunc;
                    mask = d.depthMask;
                }
            }
        }
        setBlending(blended, src, dst);
        setCullFace(cull);
        setDepthTest(depth);
        setDepthMask(mask);
    }

    /** Applies the texture's wrap and filter from the attribute's descriptor, as the stock texture binder does when it binds. */
    private Texture bindTextureState (TextureDescriptor<Texture> desc) {
        final Texture t = desc.texture;
        gl.glActiveTexture(GL20.GL_TEXTURE0);
        t.bind();
        t.unsafeSetWrap(desc.uWrap, desc.vWrap);
        t.unsafeSetFilter(desc.minFilter, desc.magFilter);
        return t;
    }

    // ------------------------------------------------------------------------------------------------------------------------ pipelines

    private int pipelineFor (Renderable r, Mesh mesh) {
        final VertexAttributes va = mesh.getVertexAttributes();
        int mask = 0; // bit 0 normal, 1 colour, 2 texture coordinates
        int bones = 0; // bone-weight attributes the mesh has (only used when the renderable carries bone matrices)
        final StringBuilder layout = new StringBuilder();
        final GdxVkPipelineDesc d = new GdxVkPipelineDesc();
        d.stride = va.vertexSize;
        for (int i = 0; i < va.size(); i++) {
            final VertexAttribute a = va.get(i);
            switch ((int)a.usage) {
            case Usage.Position:
                d.attribute(0, floatFormat(a.numComponents), a.offset);
                layout.append('P').append(a.numComponents).append('@').append(a.offset).append(' ');
                break;
            case Usage.Normal:
                if (depthPass) break; // depth does not read it
                d.attribute(1, GdxVkPipelineDesc.FORMAT_R32G32B32_SFLOAT, a.offset);
                mask |= 1;
                layout.append('N').append(a.offset).append(' ');
                break;
            case Usage.ColorPacked:
                if (depthPass) break;
                d.attribute(2, GdxVkPipelineDesc.FORMAT_R8G8B8A8_UNORM, a.offset);
                mask |= 2;
                layout.append("Cp").append(a.offset).append(' ');
                break;
            case Usage.ColorUnpacked:
                if (depthPass) break;
                d.attribute(2, floatFormat(a.numComponents), a.offset);
                mask |= 2;
                layout.append("Cu").append(a.numComponents).append('@').append(a.offset).append(' ');
                break;
            case Usage.TextureCoordinates:
                if (a.unit == 0) {
                    d.attribute(3, GdxVkPipelineDesc.FORMAT_R32G32_SFLOAT, a.offset);
                    mask |= 4;
                    layout.append('T').append(a.offset).append(' ');
                }
                break;
            case Usage.BoneWeight:
                if (a.unit > 3) throw new GdxRuntimeException("gdx-vulkan: at most 4 bone weights per vertex are supported (the mesh has unit " + a.unit + ")");
                d.attribute(4 + a.unit, GdxVkPipelineDesc.FORMAT_R32G32_SFLOAT, a.offset);
                bones = Math.max(bones, a.unit + 1);
                layout.append('B').append(a.unit).append('@').append(a.offset).append(' ');
                break;
            default: // tangents, ...: not read by these shaders
                break;
            }
        }
        layout.append('/').append(va.vertexSize);
        if (r.bones == null) bones = 0; // a skinned mesh drawn without bones is drawn as it is in the bind pose, as stock does

        d.topology = topology(r.meshPart.primitiveType);
        d.cullMode = GdxVkPipelineDesc.CULL_BACK; // replaced by the GL cull state the batch sets
        final StringBuilder key = new StringBuilder(depthPass ? "depth|" : "model|").append(bones).append('|').append(layout).append('|').append(d.topology);
        final String vert, name;
        if (depthPass) {
            final BlendingAttribute blend = (BlendingAttribute)attr(r, BlendingAttribute.Type);
            final boolean alphaTestTex = blend != null && attr(r, TextureAttribute.Diffuse) != null && (mask & 4) != 0;
            d.specConstants = new int[] {0, 0, 0, 0, 0, 0, alphaTestTex ? 1 : 0}; // id 7: C_ALPHA_TEST
            key.append(alphaTestTex ? "|a" : "");
            vert = "model_depth_u" + ((mask & 4) != 0 ? 1 : 0) + (bones > 0 ? "b" + bones : "") + ".vert";
            name = "depth:" + mask + "b" + bones;
        } else {
            final ColorAttribute diffuse = (ColorAttribute)attr(r, ColorAttribute.Diffuse);
            final TextureAttribute diffuseTex = (TextureAttribute)attr(r, TextureAttribute.Diffuse);
            final ColorAttribute specular = (ColorAttribute)attr(r, ColorAttribute.Specular);
            final TextureAttribute specularTex = (TextureAttribute)attr(r, TextureAttribute.Specular);
            final ColorAttribute emissive = (ColorAttribute)attr(r, ColorAttribute.Emissive);
            final TextureAttribute emissiveTex = (TextureAttribute)attr(r, TextureAttribute.Emissive);
            final BlendingAttribute blend = (BlendingAttribute)attr(r, BlendingAttribute.Type);
            final FloatAttribute alphaTest = (FloatAttribute)attr(r, FloatAttribute.AlphaTest);
            final FloatAttribute shininess = (FloatAttribute)attr(r, FloatAttribute.Shininess);
            final boolean uv = (mask & 4) != 0;
            final boolean lighting = r.environment != null && (mask & 1) != 0;
            final boolean tex = diffuseTex != null && uv;
            final boolean specTex = tierA && specularTex != null && uv;
            final boolean emiTex = tierA && emissiveTex != null && uv;
            final boolean specAny = lighting && (specular != null || specTex);
            final boolean fog = lighting && r.environment.get(ColorAttribute.Fog) != null;
            final boolean shadow = lighting && shadowOf(r.environment) != null;
            final TextureAttribute normalTex = (TextureAttribute)attr(r, TextureAttribute.Normal);
            final boolean normalMap = tierA && lighting && uv && normalTex != null;

            // Specialization constants 1..17 of model_default_v*.vert / model_default.frag (ids documented there).
            d.specConstants = new int[] {lighting ? 1 : 0, tex ? 1 : 0, diffuse != null ? 1 : 0, specAny ? 1 : 0, emissive != null ? 1 : 0,
                blend != null ? 1 : 0, blend != null && alphaTest != null ? 1 : 0, shininess != null ? 1 : 0,
                config.numDirectionalLights, config.numPointLights, (mask & 2) != 0 ? 1 : 0, fog ? 1 : 0, specTex ? 1 : 0, emiTex ? 1 : 0,
                specular != null ? 1 : 0, shadow ? 1 : 0, normalMap ? 1 : 0, shadow ? cascadesOf(r.environment) : 1};
            key.append('|').append(java.util.Arrays.toString(d.specConstants));
            vert = "model_default_v" + mask + (bones > 0 ? "b" + bones : "") + ".vert";
            name = "model:" + mask + "b" + bones;
        }
        if (gl.isBlendEnabled()) {
            final int[] f = gl.blendFunctions();
            d.blend = true;
            d.srcColor = f[0];
            d.dstColor = f[1];
            d.srcAlpha = f[2];
            d.dstAlpha = f[3];
            key.append("|b").append(d.srcColor).append(',').append(d.dstColor).append(',').append(d.srcAlpha).append(',').append(d.dstAlpha);
        }
        final String k = key.toString();
        Integer id = pipelineIds.get(k);
        if (id == null || !frameKnows()) {
            id = frame.pipeline(k, name, vert, depthPass ? "model_depth.frag" : "model_default.frag", d);
            pipelineIds.put(k, id);
        }
        return id;
    }

    /** The frame forgets its pipelines when its presenter is replaced (Android surface loss); then ours are stale too. */
    private int knownPresenterEpoch = -1;

    private boolean frameKnows () {
        final int epoch = System.identityHashCode(frame.presenter());
        if (epoch != knownPresenterEpoch) {
            pipelineIds.clear();
            knownPresenterEpoch = epoch;
            return false;
        }
        return true;
    }

    private static int floatFormat (int components) {
        switch (components) {
        case 1: return 100; // VK_FORMAT_R32_SFLOAT
        case 2: return GdxVkPipelineDesc.FORMAT_R32G32_SFLOAT;
        case 3: return GdxVkPipelineDesc.FORMAT_R32G32B32_SFLOAT;
        default: return GdxVkPipelineDesc.FORMAT_R32G32B32A32_SFLOAT;
        }
    }

    private static int topology (int glPrimitive) {
        switch (glPrimitive) {
        case GL20.GL_POINTS: return GdxVkPipelineDesc.POINTS;
        case GL20.GL_LINES: return GdxVkPipelineDesc.LINES;
        case GL20.GL_LINE_STRIP: return GdxVkPipelineDesc.LINE_STRIP;
        case GL20.GL_TRIANGLE_STRIP: return GdxVkPipelineDesc.TRIANGLE_STRIP;
        case GL20.GL_TRIANGLE_FAN: return GdxVkPipelineDesc.TRIANGLE_FAN;
        default: return GdxVkPipelineDesc.TRIANGLES;
        }
    }
}
