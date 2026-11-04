package io.github.slebed.gdxvulkan.g3d;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.glutils.IndexArray;
import com.badlogic.gdx.graphics.glutils.IndexBufferObject;
import com.badlogic.gdx.graphics.glutils.IndexBufferObjectSubData;
import com.badlogic.gdx.graphics.glutils.IndexData;
import com.badlogic.gdx.graphics.glutils.VertexArray;
import com.badlogic.gdx.graphics.glutils.VertexBufferObject;
import com.badlogic.gdx.graphics.glutils.VertexBufferObjectSubData;
import com.badlogic.gdx.graphics.glutils.VertexData;
import com.badlogic.gdx.utils.GdxRuntimeException;

import io.github.slebed.gdxvulkan.CommandStream;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Feeds stock {@code Mesh} data to Vulkan (plan, Core decision 5). Stock {@code VertexBufferObject} and {@code IndexBufferObject} only call
 * {@code glGenBuffer} when constructed; {@code setVertices} and {@code setIndices} fill a CPU-side buffer and raise {@code isDirty}, and
 * {@code glBufferData} runs inside {@code bind(ShaderProgram)}, which a Vulkan batch never calls. So the batch moves the data: before a draw,
 * {@link #bind} reads each data object's GL buffer name and dirty flag, uploads what changed into the shim's native buffer for that name
 * (a new native buffer when the old one was already drawn this frame: copy-on-write), clears the flag, and records the binds.
 *
 * <p>Those two fields are private or package-private in libGDX 1.14.2, so they are read by reflection; {@code StockMeshAccessTest} pins their
 * names and types, and fails on a libGDX version that renames them. {@code Mesh.dispose()} calls {@code glDeleteBuffer(handle)}, which the
 * shim turns into the release of the native buffer. Meshes built on {@code VertexArray} / {@code IndexArray} have no GL buffer: their data is
 * copied into the per-frame ring on every draw.
 *
 * <p>Not thread-safe: use it from the render thread, like the shim.
 */
public final class StockMeshAccess {
    private final VulkanGL20 gl;
    private final Field meshVertices, meshIndices;
    private final Map<Class<?>, Field[]> bufferFields = new HashMap<Class<?>, Field[]>(); // class -> {bufferHandle, isDirty, byteBuffer}

    public StockMeshAccess(VulkanGL20 gl) {
        this.gl = gl;
        try {
            meshVertices = Mesh.class.getDeclaredField("vertices");
            meshIndices = Mesh.class.getDeclaredField("indices");
            meshVertices.setAccessible(true);
            meshIndices.setAccessible(true);
        } catch (Exception e) {
            throw new GdxRuntimeException("gdx-vulkan: this libGDX version's Mesh has no vertices/indices fields (" + e + ")", e);
        }
        // Resolve the data classes now, so a renamed field fails at construction and not in the middle of a frame.
        fieldsOf(VertexBufferObject.class);
        fieldsOf(VertexBufferObjectSubData.class);
        fieldsOf(IndexBufferObject.class);
        fieldsOf(IndexBufferObjectSubData.class);
    }

    /** {@code {bufferHandle, isDirty, byteBuffer}} of a stock data class; the names are pinned by the unit test. */
    Field[] fieldsOf(Class<?> type) {
        Field[] f = bufferFields.get(type);
        if (f == null) {
            try {
                f = new Field[] {type.getDeclaredField("bufferHandle"), type.getDeclaredField("isDirty"), type.getDeclaredField("byteBuffer")};
                for (Field x : f) x.setAccessible(true);
            } catch (Exception e) {
                throw new GdxRuntimeException("gdx-vulkan: " + type.getSimpleName() + " no longer has bufferHandle/isDirty/byteBuffer (" + e + "): the libGDX version is not supported", e);
            }
            bufferFields.put(type, f);
        }
        return f;
    }

    private static boolean isGlBackedVertexData(VertexData v) {
        return v instanceof VertexBufferObject || v instanceof VertexBufferObjectSubData;
    }

    private static boolean isGlBackedIndexData(IndexData d) {
        return d instanceof IndexBufferObject || d instanceof IndexBufferObjectSubData;
    }

    /**
     * Uploads the data object's bytes when it is dirty or has never been uploaded, then clears its flag.
     * @return the native buffer id, marked as read by this frame's draws
     */
    private int sync(Object data) {
        final Field[] f = fieldsOf(data.getClass());
        try {
            final int handle = f[0].getInt(data);
            if (handle == 0) throw new GdxRuntimeException("gdx-vulkan: the mesh data was disposed");
            final boolean dirty = f[1].getBoolean(data);
            final ByteBuffer bytes = (ByteBuffer)f[2].get(data);
            if (dirty || gl.bufferSize(handle) == 0) {
                // The size is the typed buffer's limit, not the byte buffer's: loaders fill the data through getBuffer(true) and stock only
                // applies the limit to the byte buffer inside bind(ShaderProgram), which a Vulkan batch never calls.
                final int n = data instanceof VertexData ? ((VertexData)data).getBuffer(false).limit() * 4 : ((IndexData)data).getBuffer(false).limit() * 2;
                gl.uploadBuffer(handle, bytes, n);
                f[1].setBoolean(data, false);
            }
            return gl.nativeBuffer(handle);
        } catch (IllegalAccessException e) {
            throw new GdxRuntimeException("gdx-vulkan: cannot read stock mesh data", e);
        }
    }

    /**
     * Native vertex buffer of a mesh (uploading it if it changed), or -1 for a vertex-array mesh, which has none.
     */
    public int vertexBuffer(Mesh mesh) {
        final VertexData v = vertexData(mesh);
        return isGlBackedVertexData(v) ? sync(v) : -1;
    }

    /** Native index buffer of a mesh (uploading it if it changed), or -1 for an index-array mesh or one without indices. */
    public int indexBuffer(Mesh mesh) {
        if (mesh.getNumIndices() == 0) return -1;
        final IndexData d = indexData(mesh);
        return isGlBackedIndexData(d) ? sync(d) : -1;
    }

    public VertexData vertexData(Mesh mesh) {
        try {
            return (VertexData)meshVertices.get(mesh);
        } catch (IllegalAccessException e) {
            throw new GdxRuntimeException(e);
        }
    }

    public IndexData indexData(Mesh mesh) {
        try {
            return (IndexData)meshIndices.get(mesh);
        } catch (IllegalAccessException e) {
            throw new GdxRuntimeException(e);
        }
    }

    /**
     * Binds a mesh's vertex data, and its index data when it has any, into the frame's command stream. Returns the number of indices (or of
     * vertices, when the mesh has no indices): the count to pass to {@code drawIndexed} or {@code draw}.
     */
    public int bind(GdxVkFrame frame, Mesh mesh) {
        final CommandStream s = frame.stream();
        final VertexData v = vertexData(mesh);
        if (isGlBackedVertexData(v)) {
            final int vb = sync(v);
            if (vb < 0) {
                throw new GdxRuntimeException("gdx-vulkan: the mesh has no vertex data to draw (" + mesh.getNumVertices() + " vertices, " + mesh.getNumIndices()
                    + " indices, " + v.getClass().getSimpleName() + ")");
            }
            s.bindVertexBuffer(vb, 0);
        } else if (v instanceof VertexArray) {
            final int bytes = mesh.getNumVertices() * mesh.getVertexSize();
            final long a = frame.presenter().allocVertex(bytes);
            final int block = GdxVkPresenter.blockOf(a);
            final long offset = GdxVkPresenter.offsetOf(a);
            copyFloats(frame, block, offset, mesh.getVerticesBuffer(false), bytes);
            frame.presenter().flushVertices(block, offset, bytes);
            s.bindVertex(block, offset);
        } else {
            throw new GdxRuntimeException("gdx-vulkan: unsupported vertex data " + v.getClass().getSimpleName());
        }
        final int numIndices = mesh.getNumIndices();
        if (numIndices == 0) return mesh.getNumVertices();
        final IndexData d = indexData(mesh);
        if (isGlBackedIndexData(d)) {
            final int ib = sync(d);
            if (ib < 0) throw new GdxRuntimeException("gdx-vulkan: the mesh has no index data to draw (" + numIndices + " indices)");
            s.bindIndexBuffer(ib, 0, CommandStream.INDEX_U16);
        } else if (d instanceof IndexArray) {
            final int bytes = numIndices * 2;
            final long a = frame.presenter().allocVertex(bytes);
            final int block = GdxVkPresenter.blockOf(a);
            final long offset = GdxVkPresenter.offsetOf(a);
            final ByteBuffer out = frame.vertexBlock(block).duplicate().order(ByteOrder.nativeOrder());
            out.position((int)offset);
            out.asShortBuffer().put(mesh.getIndicesBuffer(false).duplicate());
            frame.presenter().flushVertices(block, offset, bytes);
            s.bindIndexRing(block, offset, CommandStream.INDEX_U16);
        } else {
            throw new GdxRuntimeException("gdx-vulkan: unsupported index data " + d.getClass().getSimpleName());
        }
        return numIndices;
    }

    private static void copyFloats(GdxVkFrame frame, int block, long offset, java.nio.FloatBuffer src, int bytes) {
        final ByteBuffer out = frame.vertexBlock(block).duplicate().order(ByteOrder.nativeOrder());
        out.position((int)offset);
        final java.nio.FloatBuffer s = src.duplicate();
        s.position(0);
        s.limit(bytes / 4);
        out.asFloatBuffer().put(s);
    }
}
