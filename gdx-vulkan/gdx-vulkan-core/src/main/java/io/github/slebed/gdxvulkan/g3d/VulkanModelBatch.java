package io.github.slebed.gdxvulkan.g3d;

import com.badlogic.gdx.Gdx;
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
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPipelineDesc;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.util.HashMap;
import java.util.Map;

/**
 * The 3D batch (plan, Phase 5): the stock {@code ModelBatch} API on Vulkan. It takes stock {@code RenderableProvider}s ({@code ModelInstance},
 * ...), sorts them with the stock sorter, and draws them with a port of libGDX's default shader (per-vertex lighting with the ambient cubemap,
 * directional and point lights, specular, diffuse and emissive, opacity and alpha test, the same material-to-state rules as {@code DefaultShader}),
 * so a scene looks as it does on OpenGL.
 *
 * <p>Per flush, one record per renderable (transform, normal matrix, material) and one header (camera, lights) go to Set 0's storage binding
 * through a {@code Set0Data} command; the draws pick their record by an index in the push constants. Meshes come from {@link StockMeshAccess}.
 * The vertex shader is one of eight (a mesh has or lacks normals, colours and texture coordinates); the material and lighting flags are
 * specialization constants, so one pipeline per distinct material shape.
 *
 * <p>Supported: position, normal, colour (packed or unpacked) and the first texture coordinate set; diffuse colour and texture, specular
 * colour and shininess, emissive colour, blending (with opacity), alpha test, cull face and depth-test attributes; an ambient light, up to
 * {@link #MAX_DIRECTIONAL_LIGHTS} directional and {@link #MAX_POINT_LIGHTS} point lights (the stock configuration is 2 and 5; further lights
 * go into the ambient cubemap as stock does). Not yet: bones, spot lights, fog, shadows, normal/specular/emissive textures, reflection.
 *
 * <p>Like the other Vulkan batches it holds a {@link GdxVkFrame} and belongs to the render thread.
 */
public class VulkanModelBatch implements Disposable {
    public static final int MAX_DIRECTIONAL_LIGHTS = 4, MAX_POINT_LIGHTS = 8;

    // Layout of the data blob in vec4 units; mirrors model_default_vertex.glsl.
    private static final int H_PROJVIEW = 0, H_CAMERA = 4, H_AMBIENT = 5, H_DIR = 11, H_POINT = 19, H_FOG = 35, HEADER = 36, RECORD = 12;

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
    private final StockMeshAccess meshes;
    private final RenderableSorter sorter;
    private final Config config;
    private final RenderablePool pool = new RenderablePool();
    private final Array<Renderable> renderables = new Array<Renderable>();
    private Camera camera;
    private boolean drawing;

    private final Map<String, Integer> pipelineIds = new HashMap<String, Integer>();
    private static final Matrix4 IDENTITY = new Matrix4();
    private final Matrix3 tmpM3 = new Matrix3();
    private final Vector3 tmpV = new Vector3();
    private final AmbientCubemap cubemap = new AmbientCubemap();
    private final float[] pushIndices = new float[2];
    private int[] words = new int[(HEADER + 64 * RECORD) * 4];

    // GL state as DefaultShader's RenderContext keeps it, so only changes reach the stream.
    private int cullFace, depthFunc;
    private boolean blending, depthMask;
    private int blendSrc, blendDst;

    public VulkanModelBatch (GdxVkFrame frame) {
        this(frame, new DefaultRenderableSorter(), new Config());
    }

    public VulkanModelBatch (GdxVkFrame frame, RenderableSorter sorter) {
        this(frame, sorter, new Config());
    }

    public VulkanModelBatch (GdxVkFrame frame, RenderableSorter sorter, Config config) {
        if (config.numDirectionalLights > MAX_DIRECTIONAL_LIGHTS || config.numPointLights > MAX_POINT_LIGHTS) {
            throw new IllegalArgumentException("at most " + MAX_DIRECTIONAL_LIGHTS + " directional and " + MAX_POINT_LIGHTS + " point lights");
        }
        this.frame = frame;
        this.gl = frame.gl();
        this.tierA = frame.isTierA();
        this.meshes = new StockMeshAccess(gl);
        this.sorter = sorter;
        this.config = config;
    }

    // ------------------------------------------------------------------------------------------------------------ the ModelBatch API

    public void begin (final Camera cam) {
        if (camera != null) throw new GdxRuntimeException("Call end() first.");
        camera = cam;
        drawing = true;
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
        drawing = false;
    }

    public void render (final Renderable renderable) {
        renderables.add(renderable);
    }

    public void render (final RenderableProvider renderableProvider) {
        final int offset = renderables.size;
        renderableProvider.getRenderables(renderables, pool);
        for (int i = offset; i < renderables.size; i++) renderables.get(i).meshPart.update();
    }

    public void render (final RenderableProvider renderableProvider, final Environment environment) {
        final int offset = renderables.size;
        renderableProvider.getRenderables(renderables, pool);
        for (int i = offset; i < renderables.size; i++) {
            Renderable renderable = renderables.get(i);
            renderable.environment = environment;
            renderable.meshPart.update();
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

    private static final java.util.Map<io.github.slebed.gdxvulkan.GdxVkContext, Integer> WHITE =
        new java.util.WeakHashMap<io.github.slebed.gdxvulkan.GdxVkContext, Integer>();

    /** A 1 x 1 white texture, bound for draws that sample nothing (Tier B has exactly one texture set bound at a time). One per context. */
    private int whiteTexture () {
        synchronized (WHITE) {
            Integer id = WHITE.get(frame.context());
            if (id == null) {
                id = frame.context().resources().createTexture(1, 1, new byte[] {-1, -1, -1, -1}, new io.github.slebed.gdxvulkan.GdxVkSampler());
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

    /** The header of a group: camera, ambient cubemap and lights, as DefaultShader's setters compute them. */
    private void writeHeader (Environment env, Renderable first) {
        putMatrix4(H_PROJVIEW, camera.combined);
        putVec4(H_CAMERA, camera.position.x, camera.position.y, camera.position.z, 1.1881f / (camera.far * camera.far));

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
        putVec4(H_FOG, 0, 0, 0, 0);
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
        putVec4(at + 10, shininess != null ? shininess.value : 20f, blend != null ? blend.opacity : 1f, alphaTest != null ? alphaTest.value : 0f, 0);

        final TextureAttribute tex = (TextureAttribute)attr(r, TextureAttribute.Diffuse);
        if (tex != null) putVec4(at + 11, tex.offsetU, tex.offsetV, tex.scaleU, tex.scaleV);
        else putVec4(at + 11, 0, 0, 1, 1);
    }

    private void drawGroup (Array<Renderable> list, int from, int to, Environment env) {
        // The records of the renderables that will draw (a degenerate transform is skipped, as stock does).
        int count = 0, boneVec4s = 0;
        for (int i = from; i < to; i++) {
            final Renderable r = list.get(i);
            if (r.worldTransform.det3x3() == 0) continue;
            count++;
            if (r.bones != null) boneVec4s += r.bones.length * 4;
        }
        if (count == 0) return;
        final int vec4s = HEADER + count * RECORD + boneVec4s;
        ensure(vec4s * 4);
        Renderable first = null;
        for (int i = from; i < to && first == null; i++) if (list.get(i).worldTransform.det3x3() != 0) first = list.get(i);
        writeHeader(env, first);
        int k = 0, boneAt = HEADER + count * RECORD;
        for (int i = from; i < to; i++) {
            final Renderable r = list.get(i);
            if (r.worldTransform.det3x3() == 0) continue;
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
        for (int i = from; i < to; i++) {
            final Renderable r = list.get(i);
            if (r.worldTransform.det3x3() == 0) continue;
            final int record = HEADER + k * RECORD;
            k++;
            applyMaterialState(r);
            final Mesh mesh = r.meshPart.mesh;
            final int pipeline = pipelineFor(r, mesh);
            if (pipeline != lastPipeline) {
                s.bindPipeline(pipeline);
                lastPipeline = pipeline;
            }
            int slot = 0;
            final TextureAttribute tex = (TextureAttribute)attr(r, TextureAttribute.Diffuse);
            if (tex == null || (mesh.getVertexAttributes().getMask() & Usage.TextureCoordinates) == 0) {
                if (!tierA) s.bindTexture(whiteTexture()); // Tier B shaders reference the texture binding, so every draw needs one bound
            } else {
                final Texture t = bindTextureState(tex.textureDescription);
                final int nativeId = gl.nativeTexture(t.getTextureObjectHandle());
                if (nativeId < 0) throw new GdxRuntimeException("gdx-vulkan: texture has no storage (nothing was uploaded to it)");
                if (tierA) slot = gl.slotOf(nativeId);
                else s.bindTexture(nativeId);
            }
            pushIndices[0] = Float.intBitsToFloat(record);
            pushIndices[1] = Float.intBitsToFloat(slot);
            s.pushRange(20, pushIndices, 0, 2); // u_indices.xy
            if (mesh != lastMesh) {
                meshes.bind(frame, mesh);
                lastMesh = mesh;
            }
            if (mesh.getNumIndices() > 0) s.drawIndexed(r.meshPart.size, r.meshPart.offset, 0);
            else s.draw(r.meshPart.size, r.meshPart.offset);
        }
    }

    /** DefaultShader.bindMaterial: blending, cull face and depth test from the material's attributes, defaults otherwise. */
    private void applyMaterialState (Renderable r) {
        int cull = GL20.GL_BACK;
        int depth = GL20.GL_LEQUAL;
        boolean mask = true;
        boolean blended = false;
        int src = GL20.GL_SRC_ALPHA, dst = GL20.GL_ONE_MINUS_SRC_ALPHA;
        if (r.material != null) {
            for (final Attribute a : r.material) {
                final long t = a.type;
                if (BlendingAttribute.is(t)) {
                    blended = true;
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
                d.attribute(1, GdxVkPipelineDesc.FORMAT_R32G32B32_SFLOAT, a.offset);
                mask |= 1;
                layout.append('N').append(a.offset).append(' ');
                break;
            case Usage.ColorPacked:
                d.attribute(2, GdxVkPipelineDesc.FORMAT_R8G8B8A8_UNORM, a.offset);
                mask |= 2;
                layout.append("Cp").append(a.offset).append(' ');
                break;
            case Usage.ColorUnpacked:
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
            default: // tangents, ...: not read by this shader
                break;
            }
        }
        layout.append('/').append(va.vertexSize);

        final ColorAttribute diffuse = (ColorAttribute)attr(r, ColorAttribute.Diffuse);
        final TextureAttribute diffuseTex = (TextureAttribute)attr(r, TextureAttribute.Diffuse);
        final ColorAttribute specular = (ColorAttribute)attr(r, ColorAttribute.Specular);
        final ColorAttribute emissive = (ColorAttribute)attr(r, ColorAttribute.Emissive);
        final BlendingAttribute blend = (BlendingAttribute)attr(r, BlendingAttribute.Type);
        final FloatAttribute alphaTest = (FloatAttribute)attr(r, FloatAttribute.AlphaTest);
        final FloatAttribute shininess = (FloatAttribute)attr(r, FloatAttribute.Shininess);
        if (r.bones == null) bones = 0; // a skinned mesh drawn without bones is drawn as it is in the bind pose, as stock does
        final boolean lighting = r.environment != null && (mask & 1) != 0;
        final boolean tex = diffuseTex != null && (mask & 4) != 0;
        final boolean spec = lighting && specular != null;

        // Specialization constants 1..11 of model_default_v*.vert / model_default.frag.
        d.specConstants = new int[] {lighting ? 1 : 0, tex ? 1 : 0, diffuse != null ? 1 : 0, spec ? 1 : 0, emissive != null ? 1 : 0,
            blend != null ? 1 : 0, blend != null && alphaTest != null ? 1 : 0, shininess != null ? 1 : 0,
            config.numDirectionalLights, config.numPointLights, (mask & 2) != 0 ? 1 : 0};
        d.topology = topology(r.meshPart.primitiveType);
        d.cullMode = GdxVkPipelineDesc.CULL_BACK; // replaced by the GL cull state the batch sets
        if (gl.isBlendEnabled()) {
            final int[] f = gl.blendFunctions();
            d.blend = true;
            d.srcColor = f[0];
            d.dstColor = f[1];
            d.srcAlpha = f[2];
            d.dstAlpha = f[3];
        }
        final StringBuilder key = new StringBuilder("model|").append(bones).append('|').append(layout).append('|').append(d.topology).append('|').append(java.util.Arrays.toString(d.specConstants));
        if (d.blend) key.append("|b").append(d.srcColor).append(',').append(d.dstColor).append(',').append(d.srcAlpha).append(',').append(d.dstAlpha);
        final String k = key.toString();
        Integer id = pipelineIds.get(k);
        if (id == null || !frameKnows(k)) {
            id = frame.pipeline(k, "model:" + mask + "b" + bones, "model_default_v" + mask + (bones > 0 ? "b" + bones : "") + ".vert", "model_default.frag", d);
            pipelineIds.put(k, id);
        }
        return id;
    }

    /** The frame forgets its pipelines when its presenter is replaced (Android surface loss); then ours are stale too. */
    private int knownPresenterEpoch = -1;
    private boolean frameKnows (String key) {
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
