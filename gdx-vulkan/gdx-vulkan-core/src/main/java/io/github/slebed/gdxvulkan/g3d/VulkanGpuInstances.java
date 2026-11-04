package io.github.slebed.gdxvulkan.g3d;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import io.github.slebed.gdxvulkan.CommandStream;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPipelineDesc;
import io.github.slebed.gdxvulkan.GdxVkResources;
import io.github.slebed.gdxvulkan.GdxVkSampler;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GPU-driven instancing and clustered lighting (plan, Phase 5): up to hundreds of thousands of copies of one stock {@code Mesh}, their transforms
 * living in GPU memory where a compute shader of yours moves them, drawn with one indirect call after the GPU has culled them against the camera's
 * frustum, and lit by hundreds of point lights that a second compute pass has sorted into view-space clusters.
 *
 * <p>Everything is in one resource buffer (layout in {@code gpu_world.glsl}): a header the CPU rewrites each frame (camera, counts, offsets), the
 * instances ({@link #INSTANCE_FLOATS} floats each: position and scale, rotation quaternion, four floats of your own state), the visible list and
 * indirect draw command the cull pass writes, the lights, the cluster lists, and a region of {@code userUints} that is yours. Write your
 * simulation as a {@link VulkanComputeShader} over {@link #buffer()}, queue it before {@link #render}, and it runs ahead of the cull and the draw
 * in the same frame with the barriers in place.
 *
 * <p>Per frame the CPU does: rewrite a 224-byte header, queue three dispatches (reset, cull, light cull), and record one bind, one push and one
 * indirect draw. Nothing scales with the instance count and nothing allocates. The view is a perspective camera; the swapchain is the target
 * (the cluster lookup assumes its top-down pixel rows).
 */
public class VulkanGpuInstances implements Disposable {
    public static final int INSTANCE_FLOATS = 12, LIGHT_FLOATS = 8;
    /** Clusters in x, y and depth, and the most lights one cluster keeps (more are dropped). */
    public static final int CLUSTERS_X = 16, CLUSTERS_Y = 9, CLUSTERS_Z = 24, MAX_LIGHTS_PER_CLUSTER = 32;
    private static final int HEADER_UINTS = 56;

    private final GdxVkFrame frame;
    private final GdxVkContext context;
    private final GdxVkResources resources;
    private final VulkanGL20 gl;
    private final boolean tierA;
    private final StockMeshAccess meshes;
    private final Mesh mesh;
    private final Texture texture;
    private final int maxInstances, maxLights, userUints;
    private final int offInstances, offVisible, offIndirect, offLights, offClusters, offUser, totalUints;
    private final int buffer;
    private final float boundingRadius;
    private final VulkanComputeShader cull, lightCull;
    private final ByteBuffer header = ByteBuffer.allocate(HEADER_UINTS * 4).order(ByteOrder.LITTLE_ENDIAN);
    private final byte[] headerBytes = new byte[HEADER_UINTS * 4];
    private int instanceCount, lightCount;
    private final float[] pushIndices = new float[2];
    private final float[] pushReset = VulkanComputeShader.ints(VulkanComputeShader.newPush(), 0, 0, 0, 0);
    private final float[] pushCull = VulkanComputeShader.ints(VulkanComputeShader.newPush(), 1, 0, 0, 0);
    private final float[] pushLights = VulkanComputeShader.newPush();
    private int pipelineId = -1;
    private Object pipelineOwner;

    /**
     * @param mesh a stock mesh with position, normal and (to texture it) texture coordinates, centred on its origin
     * @param texture drawn on every instance (modulated by a per-instance tint), or null
     * @param userUints size in 32-bit words of the region after the cluster lists that your own compute shaders may use
     */
    public VulkanGpuInstances (GdxVkFrame frame, Mesh mesh, Texture texture, int maxInstances, int maxLights, int userUints) {
        if (mesh.getNumIndices() == 0) throw new IllegalArgumentException("the mesh needs indices");
        this.frame = frame;
        this.context = frame.context();
        this.resources = context.resources();
        this.gl = frame.gl();
        this.tierA = frame.isTierA();
        this.meshes = new StockMeshAccess(gl);
        this.mesh = mesh;
        this.texture = texture;
        this.maxInstances = maxInstances;
        this.maxLights = Math.max(maxLights, 1);
        this.userUints = userUints;

        final int clusters = CLUSTERS_X * CLUSTERS_Y * CLUSTERS_Z;
        offInstances = HEADER_UINTS;
        offVisible = offInstances + maxInstances * INSTANCE_FLOATS;
        offIndirect = offVisible + maxInstances;
        offLights = offIndirect + 8;
        offClusters = offLights + this.maxLights * LIGHT_FLOATS;
        offUser = offClusters + clusters * (1 + MAX_LIGHTS_PER_CLUSTER);
        totalUints = offUser + userUints;
        buffer = resources.createBuffer((long)totalUints * 4);

        final BoundingBox box = mesh.calculateBoundingBox();
        float r = 0f;
        for (int i = 0; i < 8; i++) {
            final float x = (i & 1) != 0 ? box.max.x : box.min.x, y = (i & 2) != 0 ? box.max.y : box.min.y, z = (i & 4) != 0 ? box.max.z : box.min.z;
            r = Math.max(r, (float)Math.sqrt(x * x + y * y + z * z));
        }
        boundingRadius = r;
        cull = new VulkanComputeShader(frame, "gpu_cull", "gpu_cull.comp");
        lightCull = new VulkanComputeShader(frame, "light_cull", "light_cull.comp");
    }

    /** The resource buffer: bind it as storage in your own compute shaders. */
    public int buffer () { return buffer; }

    /** Where the instances start, in 32-bit words from the start of the buffer ({@link #INSTANCE_FLOATS} words each). */
    public int instanceOffsetUints () { return offInstances; }

    /** Where your own region starts, in 32-bit words, and how big it is. */
    public int userOffsetUints () { return offUser; }

    public int userUints () { return userUints; }

    public int lightOffsetUints () { return offLights; }

    public int maxInstances () { return maxInstances; }

    public int instanceCount () { return instanceCount; }

    /** Instance data, {@link #INSTANCE_FLOATS} floats each: x, y, z, scale, qx, qy, qz, qw, then four of your own. Queues the upload. */
    public void setInstances (float[] data, int count) {
        if (count > maxInstances) throw new IllegalArgumentException("more than " + maxInstances + " instances");
        instanceCount = count;
        write(offInstances, data, count * INSTANCE_FLOATS);
    }

    /** Point lights, {@link #LIGHT_FLOATS} floats each: x, y, z, radius, r, g, b, intensity. Queues the upload. */
    public void setLights (float[] data, int count) {
        if (count > maxLights) throw new IllegalArgumentException("more than " + maxLights + " lights");
        lightCount = count;
        if (count > 0) write(offLights, data, count * LIGHT_FLOATS);
    }

    private void write (int offsetUints, float[] data, int floats) {
        final ByteBuffer b = ByteBuffer.allocate(floats * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.asFloatBuffer().put(data, 0, floats);
        resources.writeBuffer(buffer, (long)offsetUints * 4, b.array(), 0, floats * 4);
    }

    /**
     * Records this frame's work: the header, the cull and light-cull dispatches (queued after any dispatches you queued, so your simulation runs
     * first), and the indirect draw. Call it where you would call {@code ModelBatch.render}, inside the frame, with the GL depth state you want.
     */
    public void render (Camera camera, int screenWidth, int screenHeight) {
        writeHeader(camera, screenWidth, screenHeight);

        cull.dispatch(1, pushReset, buffer); // mode 0: reset the indirect command
        cull.dispatch(VulkanComputeShader.groups(Math.max(instanceCount, 1), 256), pushCull, buffer);
        lightCull.dispatch(VulkanComputeShader.groups(CLUSTERS_X * CLUSTERS_Y * CLUSTERS_Z, 64), pushLights, buffer);

        final CommandStream s = frame.stream();
        gl.glEnable(GL20.GL_DEPTH_TEST);
        gl.glDepthFunc(GL20.GL_LEQUAL);
        gl.glDepthMask(true);
        gl.glEnable(GL20.GL_CULL_FACE);
        gl.glCullFace(GL20.GL_BACK);
        s.bindPipeline(pipeline());
        s.bindStorage(buffer);
        int slot = 0;
        if (texture != null && hasTexCoords()) {
            gl.glActiveTexture(GL20.GL_TEXTURE0);
            texture.bind();
            final int id = gl.nativeTexture(texture.getTextureObjectHandle());
            if (id < 0) throw new GdxRuntimeException("gdx-vulkan: texture has no storage (nothing was uploaded to it)");
            if (tierA) slot = gl.slotOf(id);
            else s.bindTexture(id);
        } else if (!tierA) {
            s.bindTexture(whiteTexture());
        }
        pushIndices[0] = Float.intBitsToFloat(0);
        pushIndices[1] = Float.intBitsToFloat(slot);
        s.pushRange(20, pushIndices, 0, 2);
        meshes.bind(frame, mesh);
        s.drawIndexedIndirect(buffer, (long)offIndirect * 4, 1, 20);
        gl.glDisable(GL20.GL_DEPTH_TEST);
        gl.glDisable(GL20.GL_CULL_FACE);
    }

    private boolean hasTexCoords () {
        return (mesh.getVertexAttributes().getMask() & Usage.TextureCoordinates) != 0;
    }

    private void writeHeader (Camera camera, int screenWidth, int screenHeight) {
        header.clear();
        for (int i = 0; i < 16; i++) header.putFloat(camera.combined.val[i]);
        for (int i = 0; i < 16; i++) header.putFloat(camera.view.val[i]);
        header.putFloat(camera.position.x).putFloat(camera.position.y).putFloat(camera.position.z).putFloat(camera.near);
        header.putFloat(camera.far).putFloat(camera.projection.val[Matrix4.M00]).putFloat(camera.projection.val[Matrix4.M11]).putFloat(boundingRadius);
        header.putInt(instanceCount).putInt(lightCount).putInt(screenWidth).putInt(screenHeight);
        header.putInt(CLUSTERS_X).putInt(CLUSTERS_Y).putInt(CLUSTERS_Z).putInt(MAX_LIGHTS_PER_CLUSTER);
        header.putInt(offInstances).putInt(offVisible).putInt(offIndirect).putInt(offLights);
        header.putInt(offClusters).putInt(mesh.getNumIndices()).putInt(offUser).putInt(0);
        header.flip();
        header.get(headerBytes, 0, header.limit());
        resources.writeBuffer(buffer, 0, headerBytes, 0, header.limit());
    }

    /** The draw pipeline on the frame's current presenter; built again only when the presenter was replaced (no allocation per frame). */
    private int pipeline () {
        final Object presenter = frame.presenter();
        if (presenter != pipelineOwner) {
            pipelineId = createPipeline();
            pipelineOwner = presenter;
        }
        return pipelineId;
    }

    private int createPipeline () {
        final VertexAttributes va = mesh.getVertexAttributes();
        final GdxVkPipelineDesc d = new GdxVkPipelineDesc();
        d.stride = va.vertexSize;
        boolean hasNormal = false;
        for (int i = 0; i < va.size(); i++) {
            final VertexAttribute a = va.get(i);
            if (a.usage == Usage.Position) d.attribute(0, GdxVkPipelineDesc.FORMAT_R32G32B32_SFLOAT, a.offset);
            else if (a.usage == Usage.Normal) {
                d.attribute(1, GdxVkPipelineDesc.FORMAT_R32G32B32_SFLOAT, a.offset);
                hasNormal = true;
            } else if (a.usage == Usage.TextureCoordinates && a.unit == 0) d.attribute(3, GdxVkPipelineDesc.FORMAT_R32G32_SFLOAT, a.offset);
        }
        if (!hasNormal) throw new GdxRuntimeException("gdx-vulkan: VulkanGpuInstances needs a mesh with normals");
        d.cullMode = GdxVkPipelineDesc.CULL_BACK;
        final boolean tex = texture != null && hasTexCoords();
        d.specConstants = new int[] {tex ? 1 : 0};
        return frame.pipeline("gpuinst|" + va.vertexSize + "|" + (tex ? "t" : "-") + "|" + va.getMask(), "gpuinstances", "gpu_lit_v" + (hasTexCoords() ? 1 : 0) + ".vert", "gpu_lit.frag", d);
    }

    private static final java.util.Map<GdxVkContext, Integer> WHITE = new java.util.WeakHashMap<GdxVkContext, Integer>();

    private int whiteTexture () {
        synchronized (WHITE) {
            Integer id = WHITE.get(context);
            if (id == null) {
                id = resources.createTexture(1, 1, new byte[] {-1, -1, -1, -1}, new GdxVkSampler());
                WHITE.put(context, id);
            }
            return id;
        }
    }

    /** How many instances the last finished frame's cull pass kept (a readback that stalls the GPU: for tests and diagnostics). */
    public int readVisibleCount () {
        final byte[] b = new byte[4];
        resources.readBuffer(buffer, (long)offIndirect * 4 + 4, b, 0, 4);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** The indirect draw command the last finished frame's cull pass wrote: indexCount, instanceCount, firstIndex, vertexOffset, firstInstance (diagnostics). */
    public int[] readIndirect () {
        final byte[] b = new byte[20];
        resources.readBuffer(buffer, (long)offIndirect * 4, b, 0, 20);
        final int[] out = new int[5];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out);
        return out;
    }

    /** The visible instance indices of the last finished frame (diagnostics). */
    public int[] readVisible (int count) {
        final byte[] b = new byte[count * 4];
        resources.readBuffer(buffer, (long)offVisible * 4, b, 0, b.length);
        final int[] out = new int[count];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out);
        return out;
    }

    /** The light indices cluster {@code cluster} holds after the last finished frame's light cull (diagnostics). */
    public int[] readCluster (int cluster) {
        final byte[] b = new byte[(1 + MAX_LIGHTS_PER_CLUSTER) * 4];
        resources.readBuffer(buffer, (long)(offClusters + cluster * (1 + MAX_LIGHTS_PER_CLUSTER)) * 4, b, 0, b.length);
        final java.nio.IntBuffer ib = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        final int n = ib.get(0);
        final int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = ib.get(1 + i);
        return out;
    }

    /** Bytes of user data, read back (diagnostics). */
    public void readUser (int wordOffset, byte[] out, int length) {
        resources.readBuffer(buffer, (long)(offUser + wordOffset) * 4, out, 0, length);
    }

    @Override public void dispose () {
        resources.destroyBuffer(buffer);
    }
}
