package io.github.slebed.gdxvulkan.gl;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.IntMap;

import io.github.slebed.gdxvulkan.CommandStream;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPipelineDesc;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.glsl.VulkanShaderProgram;

/**
 * The shader-program half of the GL shim: what stock {@code ShaderProgram} and {@code Mesh} ask OpenGL for, answered with {@link VulkanShaderProgram}s. A
 * stock {@code ShaderProgram} creates shaders, attaches and links them, lists and looks up their attributes and uniforms, sets uniforms, and a stock {@code Mesh}
 * binds its buffers, points attributes at them and draws; all of it lands here, so stock {@code SpriteBatch}, {@code ShapeRenderer}, {@code Stage},
 * {@code ModelBatch} and every custom {@code ShaderProgram} run unchanged.
 *
 * <p>Compilation happens at {@code glLinkProgram}: the two sources are needed together to match the varyings. {@code glCompileShader} only records that the
 * shader was given, so a mistake in a shader shows as a link failure with the compiler's message in the program's log; stock reports that the same way
 * (the program is not compiled and {@code getLog()} says why).
 */
final class StockPrograms {
    private static final int GL_VERTEX_SHADER = 0x8B31, GL_FRAGMENT_SHADER = 0x8B30;
    private static final int GL_COMPILE_STATUS = 0x8B81, GL_LINK_STATUS = 0x8B82, GL_INFO_LOG_LENGTH = 0x8B84, GL_ATTACHED_SHADERS = 0x8B85,
        GL_ACTIVE_UNIFORMS = 0x8B86, GL_ACTIVE_UNIFORM_MAX_LENGTH = 0x8B87, GL_ACTIVE_ATTRIBUTES = 0x8B89, GL_ACTIVE_ATTRIBUTE_MAX_LENGTH = 0x8B8A,
        GL_SHADER_TYPE = 0x8B4F, GL_DELETE_STATUS = 0x8B80, GL_VALIDATE_STATUS = 0x8B83;
    private static final int GL_BYTE = 0x1400, GL_UNSIGNED_BYTE = 0x1401, GL_SHORT = 0x1402, GL_UNSIGNED_SHORT = 0x1403, GL_INT = 0x1404, GL_UNSIGNED_INT = 0x1405,
        GL_FLOAT = 0x1406;
    private static final int MAX_ATTRIBUTES = 16;

    private static final class Shader {
        final int type;
        String source = "";
        boolean sourced, deleted;

        Shader (int type) {
            this.type = type;
        }
    }

    private static final class Program {
        Shader vertex, fragment;
        VulkanShaderProgram program;
        String log = "";
        boolean linked, deleted;
    }

    /** One {@code glVertexAttribPointer}: where the attribute's data is, and what it looks like. */
    private static final class Pointer {
        boolean enabled;
        int size, type, stride, offset, buffer;
        boolean normalized;
        /** Vertex data in memory (a Mesh of the VertexArray type): the buffer, and {@code offset} is its position when the pointer was set. buffer is CLIENT then. */
        Buffer client;
    }

    private static final int CLIENT = -1;

    private final VulkanGL20 gl;
    private final IntMap<Shader> shaders = new IntMap<Shader>();
    private final IntMap<Program> programs = new IntMap<Program>();
    private final Pointer[] pointers = new Pointer[MAX_ATTRIBUTES];
    /** The constant value of each attribute (glVertexAttrib*), 4 floats each; the GL default is (0, 0, 0, 1). */
    private final float[] generic = new float[MAX_ATTRIBUTES * 4];
    private final int[] constantLocations = new int[MAX_ATTRIBUTES];
    // The vertex buffers one draw reads: the GL buffer of each binding and its stride, and the binding of each attribute (stock Mesh uses one buffer; a game may use several).
    private static final int MAX_BINDINGS = 14;
    private final int[] bindingBuffer = new int[MAX_BINDINGS], bindingStride = new int[MAX_BINDINGS], attributeBinding = new int[MAX_ATTRIBUTES];

    {
        for (int i = 0; i < MAX_ATTRIBUTES; i++) generic[i * 4 + 3] = 1f;
    }
    private final float[] push = new float[32];
    private final com.badlogic.gdx.utils.LongMap<Integer> pipelines = new com.badlogic.gdx.utils.LongMap<Integer>();
    private int nextName = 1;
    private Program current;

    StockPrograms (VulkanGL20 gl) {
        this.gl = gl;
        for (int i = 0; i < pointers.length; i++) pointers[i] = new Pointer();
    }

    // -------------------------------------------------------------------------------------------------------------------------- shaders

    int createShader (int type) {
        if (type != GL_VERTEX_SHADER && type != GL_FRAGMENT_SHADER) throw new GdxRuntimeException("gdx-vulkan: glCreateShader(0x" + Integer.toHexString(type) + "): only vertex and fragment shaders are supported");
        final int name = nextName++;
        shaders.put(name, new Shader(type));
        return name;
    }

    void shaderSource (int shader, String source) {
        shader(shader).source = source == null ? "" : source;
        shader(shader).sourced = true;
    }

    /** The compile happens at link, where both stages are known; a shader that was given a source counts as compiled. */
    void compileShader (int shader) {
        shader(shader);
    }

    void getShaderiv (int shader, int pname, IntBuffer params) {
        final Shader s = shader(shader);
        final int value;
        switch (pname) {
        case GL_COMPILE_STATUS: value = s.sourced ? 1 : 0; break;
        case GL_SHADER_TYPE: value = s.type; break;
        case GL_DELETE_STATUS: value = s.deleted ? 1 : 0; break;
        case GL_INFO_LOG_LENGTH: value = 0; break;
        default: value = 0; break;
        }
        params.put(params.position(), value);
    }

    String getShaderInfoLog (int shader) {
        shader(shader);
        return "";
    }

    void deleteShader (int shader) {
        shaders.remove(shader);
    }

    private Shader shader (int name) {
        final Shader s = shaders.get(name);
        if (s == null) throw new GdxRuntimeException("gdx-vulkan: no shader with the name " + name);
        return s;
    }

    // ------------------------------------------------------------------------------------------------------------------------- programs

    int createProgram () {
        final int name = nextName++;
        programs.put(name, new Program());
        return name;
    }

    void attachShader (int program, int shader) {
        final Program p = program(program);
        final Shader s = shader(shader);
        if (s.type == GL_VERTEX_SHADER) p.vertex = s;
        else p.fragment = s;
    }

    void linkProgram (int program) {
        final Program p = program(program);
        p.linked = false;
        p.program = null;
        if (p.vertex == null || p.fragment == null) {
            p.log = "the program needs a vertex shader and a fragment shader";
            return;
        }
        final GdxVkFrame frame = gl.attachedFrame();
        if (frame == null) throw new GdxRuntimeException("gdx-vulkan: a shader program was linked before the frame exists; create it in ApplicationListener.create() or later");
        final VulkanShaderProgram built = VulkanShaderProgram.fromAssembledSources(frame, p.vertex.source, p.fragment.source);
        p.program = built;
        p.linked = built.isCompiled();
        p.log = built.getLog();
    }

    void getProgramiv (int program, int pname, IntBuffer params) {
        final Program p = program(program);
        final int value;
        switch (pname) {
        case GL_LINK_STATUS: value = p.linked ? 1 : 0; break;
        case GL_ACTIVE_UNIFORMS: value = p.linked ? p.program.activeUniformCount() : 0; break;
        case GL_ACTIVE_ATTRIBUTES: value = p.linked ? p.program.activeAttributeCount() : 0; break;
        case GL_ACTIVE_UNIFORM_MAX_LENGTH: value = p.linked ? longest(p.program, true) : 0; break;
        case GL_ACTIVE_ATTRIBUTE_MAX_LENGTH: value = p.linked ? longest(p.program, false) : 0; break;
        case GL_INFO_LOG_LENGTH: value = p.log.length(); break;
        case GL_ATTACHED_SHADERS: value = (p.vertex != null ? 1 : 0) + (p.fragment != null ? 1 : 0); break;
        case GL_DELETE_STATUS: value = p.deleted ? 1 : 0; break;
        case GL_VALIDATE_STATUS: value = p.linked ? 1 : 0; break;
        default: value = 0; break;
        }
        params.put(params.position(), value);
    }

    private static int longest (VulkanShaderProgram program, boolean uniforms) {
        int n = 0;
        final int count = uniforms ? program.activeUniformCount() : program.activeAttributeCount();
        for (int i = 0; i < count; i++) n = Math.max(n, (uniforms ? program.activeUniformName(i) : program.activeAttributeName(i)).length() + 1);
        return n;
    }

    String getProgramInfoLog (int program) {
        return program(program).log;
    }

    void deleteProgram (int program) {
        final Program p = programs.remove(program);
        if (p == current) current = null;
    }

    void useProgram (int program) {
        current = program == 0 ? null : program(program);
    }

    String getActiveAttrib (int program, int index, IntBuffer size, IntBuffer type) {
        final VulkanShaderProgram p = linked(program);
        size.put(size.position(), 1);
        type.put(type.position(), p.activeAttributeType(index));
        return p.activeAttributeName(index);
    }

    String getActiveUniform (int program, int index, IntBuffer size, IntBuffer type) {
        final VulkanShaderProgram p = linked(program);
        size.put(size.position(), p.activeUniformSize(index));
        type.put(type.position(), p.activeUniformType(index));
        return p.activeUniformName(index);
    }

    int getAttribLocation (int program, String name) {
        return linked(program).getAttributeLocation(name);
    }

    int getUniformLocation (int program, String name) {
        return linked(program).uniformLocation(name);
    }

    private VulkanShaderProgram linked (int name) {
        final Program p = program(name);
        if (!p.linked) throw new GdxRuntimeException("gdx-vulkan: the program " + name + " did not link: " + p.log);
        return p.program;
    }

    private Program program (int name) {
        final Program p = programs.get(name);
        if (p == null) throw new GdxRuntimeException("gdx-vulkan: no program with the name " + name);
        return p;
    }

    // ------------------------------------------------------------------------------------------------------------------------- uniforms

    private VulkanShaderProgram inUse (String call) {
        if (current == null || !current.linked) throw new GdxRuntimeException("gdx-vulkan: " + call + " with no linked program in use (glUseProgram)");
        return current.program;
    }

    void uniformFloats (int location, int components, int count, float[] v, int offset) {
        inUse("glUniform").setFloats(location, components, count, v, offset);
    }

    void uniformFloats (int location, int components, int count, FloatBuffer v) {
        final float[] tmp = new float[count * components];
        final int p = v.position();
        for (int i = 0; i < tmp.length; i++) tmp[i] = v.get(p + i);
        uniformFloats(location, components, count, tmp, 0);
    }

    void uniformInts (int location, int components, int count, int[] v, int offset) {
        inUse("glUniform").setInts(location, components, count, v, offset);
    }

    void uniformInts (int location, int components, int count, IntBuffer v) {
        final int[] tmp = new int[count * components];
        final int p = v.position();
        for (int i = 0; i < tmp.length; i++) tmp[i] = v.get(p + i);
        uniformInts(location, components, count, tmp, 0);
    }

    void uniformMatrices (int location, int n, int count, boolean transpose, float[] v, int offset) {
        inUse("glUniformMatrix").setMatrices(location, n, count, transpose, v, offset);
    }

    void uniformMatrices (int location, int n, int count, boolean transpose, FloatBuffer v) {
        final float[] tmp = new float[count * n * n];
        final int p = v.position();
        for (int i = 0; i < tmp.length; i++) tmp[i] = v.get(p + i);
        uniformMatrices(location, n, count, transpose, tmp, 0);
    }

    // ------------------------------------------------------------------------------------------------------------------ vertex attributes

    void vertexAttrib (int index, float x, float y, float z, float w) {
        if (index < 0 || index >= MAX_ATTRIBUTES) throw new GdxRuntimeException("gdx-vulkan: vertex attribute " + index + " is out of range");
        final int b = index * 4;
        generic[b] = x;
        generic[b + 1] = y;
        generic[b + 2] = z;
        generic[b + 3] = w;
    }

    void enableVertexAttrib (int index, boolean enabled) {
        pointer(index).enabled = enabled;
    }

    void vertexAttribPointer (int index, int size, int type, boolean normalized, int stride, int offset, int arrayBuffer) {
        final Pointer p = pointer(index);
        if (arrayBuffer == 0) {
            throw new GdxRuntimeException("gdx-vulkan: glVertexAttribPointer with an offset and no array buffer bound");
        }
        p.size = size;
        p.type = type;
        p.normalized = normalized;
        p.stride = stride;
        p.offset = offset;
        p.buffer = arrayBuffer;
        p.client = null;
    }

    /** {@code glVertexAttribPointer} with the data in memory: the vertex bytes are copied into the frame's vertex ring by the draw that reads them. */
    void vertexAttribPointerClient (int index, int size, int type, boolean normalized, int stride, Buffer data) {
        if (!(data instanceof ByteBuffer) && !(data instanceof FloatBuffer)) {
            throw new GdxRuntimeException("gdx-vulkan: glVertexAttribPointer with a " + data.getClass().getSimpleName() + " in memory: a ByteBuffer or a FloatBuffer is supported (Mesh passes one of them)");
        }
        final Pointer p = pointer(index);
        p.size = size;
        p.type = type;
        p.normalized = normalized;
        p.stride = stride;
        p.offset = data.position() * (data instanceof FloatBuffer ? 4 : 1); // the attribute's offset inside the vertex, in bytes: the position is moved before each call
        p.buffer = CLIENT;
        p.client = data;
    }

    private Pointer pointer (int index) {
        if (index < 0 || index >= MAX_ATTRIBUTES) throw new GdxRuntimeException("gdx-vulkan: vertex attribute " + index + " is out of range");
        return pointers[index];
    }

    // ----------------------------------------------------------------------------------------------------------------------------- draws

    void drawArrays (int mode, int first, int count) {
        draw(mode, count, first, false, 0, 0, 0, null);
    }

    void drawElements (int mode, int count, int type, int offset, int elementBuffer) {
        if (elementBuffer == 0) {
            throw new GdxRuntimeException("gdx-vulkan: glDrawElements without an element buffer bound: index data in client memory (a Mesh of the VertexArray type) is not supported; use a Mesh with an index buffer object");
        }
        draw(mode, count, 0, true, type, offset, elementBuffer, null);
    }

    /** {@code glDrawElements} with the indices in memory (the {@code count} elements from the buffer's position). */
    void drawElementsClient (int mode, int count, int type, Buffer indices) {
        draw(mode, count, 0, true, type, 0, 0, indices);
    }

    private void draw (int mode, int count, int first, boolean indexed, int indexType, int indexOffset, int elementBuffer, Buffer clientIndices) {
        if (count <= 0) return;
        final VulkanShaderProgram program = inUse("a draw");
        final GdxVkFrame frame = gl.attachedFrame();
        final CommandStream s = gl.stream();
        if (frame == null || s == null) throw new GdxRuntimeException("gdx-vulkan: a draw outside a frame (frame.begin() was not called)");

        // The layout of the vertex data: every enabled array the program reads, from one buffer. The pipeline is found by a 64-bit signature of what makes
        // pipelines differ (the program, the primitive type, the blend state and each attribute's format, offset and stride): nothing is built as a string or allocated per draw.
        int buffer = 0, stride = 0, bindingCount = 0;
        Buffer client = null;
        int clientBytes = Integer.MAX_VALUE;
        long signature = mix(program.id(), mode);
        final boolean blend = gl.isBlendEnabled();
        if (blend) {
            final int[] f = gl.blendFunctions();
            signature = mix(mix(mix(mix(signature, 1), f[0]), f[1]), mix(f[2], f[3]));
        }
        final int[] locations = program.attributeLocations();
        int enabledReadByProgram = 0, constants = 0;
        for (int k = 0; k < locations.length; k++) {
            final int i = locations[k];
            if (i < 0 || i >= MAX_ATTRIBUTES) continue;
            final Pointer p = pointers[i];
            if (!p.enabled || p.size == 0) { // no array: the attribute's constant value (glVertexAttrib*, or the GL default 0, 0, 0, 1)
                constantLocations[constants++] = i;
                signature = mix(signature, 0x100 + i);
                continue;
            }
            // One binding per buffer (in-memory pointers are all CLIENT: Mesh passes a FloatBuffer and a ByteBuffer, two views of the one array).
            final int attributeStride = p.stride != 0 ? p.stride : p.size * typeBytes(p.type);
            int b = -1;
            for (int j = 0; j < bindingCount; j++) if (bindingBuffer[j] == p.buffer) {
                b = j;
                break;
            }
            if (b < 0) {
                if (bindingCount == MAX_BINDINGS) throw new GdxRuntimeException("gdx-vulkan: a draw reads more than " + MAX_BINDINGS + " vertex buffers");
                b = bindingCount++;
                bindingBuffer[b] = p.buffer;
                bindingStride[b] = attributeStride;
            } else if (bindingStride[b] != attributeStride) {
                throw new GdxRuntimeException("gdx-vulkan: attributes of one vertex buffer with different strides (" + bindingStride[b] + " and " + attributeStride + ")");
            }
            attributeBinding[i] = b;
            if (p.client != null) {
                if (client == null) client = p.client;
                clientBytes = Math.min(clientBytes, p.client instanceof FloatBuffer ? ((FloatBuffer)p.client).limit() * 4 : ((ByteBuffer)p.client).limit());
            }
            buffer = p.buffer;
            stride = attributeStride;
            signature = mix(mix(mix(mix(signature, i), (p.size << 20) ^ (p.type << 1) ^ (p.normalized ? 1 : 0)), ((long)p.offset << 32) ^ attributeStride), b);
            enabledReadByProgram++;
        }
        if (enabledReadByProgram == 0) throw new GdxRuntimeException("gdx-vulkan: a draw with the program using no enabled vertex attribute");
        if (client != null && bindingCount > 1) throw new GdxRuntimeException("gdx-vulkan: vertex data in memory and in buffers in one draw: use one kind");
        Integer pipeline = pipelines.get(signature);
        if (pipeline == null) {
            final StringBuilder variant = new StringBuilder("gl|").append(mode).append('|');
            final GdxVkPipelineDesc d = new GdxVkPipelineDesc();
            d.stride = bindingStride[0];
            for (int b = 1; b < bindingCount; b++) d.bindingStride(b, bindingStride[b]);
            d.topology = topology(mode);
            d.cullMode = GdxVkPipelineDesc.CULL_NONE; // GL's cull state is an ordered operation
            if (blend) {
                final int[] f = gl.blendFunctions();
                d.blend = true;
                d.srcColor = f[0];
                d.dstColor = f[1];
                d.srcAlpha = f[2];
                d.dstAlpha = f[3];
                variant.append(f[0]).append(',').append(f[1]).append(',').append(f[2]).append(',').append(f[3]);
            } else {
                variant.append('o');
            }
            variant.append('|');
            int constant = 0;
            for (int k = 0; k < locations.length; k++) {
                final int i = locations[k];
                if (i < 0 || i >= MAX_ATTRIBUTES) continue;
                final Pointer p = pointers[i];
                if (!p.enabled || p.size == 0) {
                    d.constantAttribute(i, 16 * constant++);
                    variant.append('c').append(i).append(';');
                    continue;
                }
                d.attribute(i, format(p), p.offset, attributeBinding[i]);
                variant.append(i).append(':').append(p.size).append(':').append(p.type).append(p.normalized ? 'n' : 'r').append(':').append(p.offset).append('@').append(attributeBinding[i]).append(';');
            }
            for (int b = 0; b < bindingCount; b++) variant.append('/').append(bindingStride[b]);
            pipeline = program.pipeline(variant.toString(), d);
            pipelines.put(signature, pipeline);
        }

        // Data in memory goes through the frame's ring: the vertices up to the highest one this draw reads, and the indices of the draw.
        int nativeVertices = -1;
        int maxVertex = 0;
        if (indexed && clientIndices != null) maxVertex = maxIndex(clientIndices, count, indexType);
        else if (!indexed) maxVertex = first + count - 1;
        if (client == null) {
            nativeVertices = gl.nativeBuffer(bindingBuffer[0]);
            if (nativeVertices < 0) throw new GdxRuntimeException("gdx-vulkan: the vertex buffer has no data");
        } else if (indexed && clientIndices == null) {
            throw new GdxRuntimeException("gdx-vulkan: vertices in memory drawn with an index buffer object: use vertex and index data of the same kind");
        }
        if (program.hasThinLineNudge()) { // as VulkanImmediateModeRenderer does: lines and points move 1/256 pixel the way GL resolves a tie
            final boolean thin = mode == 0x0000 || mode == 0x0001 || mode == 0x0003;
            program.setPushFloat(96, thin ? -2f / 256f / Math.max(1, frame.height()) : 0f);
        }
        s.bindPipeline(pipeline);
        s.pushConstantWords(program.prepareDrawWords(s, gl.boundTexture(0)));
        if (client != null) {
            final int bytes = Math.min((maxVertex + 1) * stride, clientBytes);
            final long a = frame.presenter().allocVertex(bytes);
            final int block = GdxVkPresenter.blockOf(a);
            final long offset = GdxVkPresenter.offsetOf(a);
            final ByteBuffer out = frame.vertexBlock(block).duplicate().order(ByteOrder.nativeOrder());
            out.position((int)offset);
            if (client instanceof FloatBuffer) {
                final FloatBuffer from = ((FloatBuffer)client).duplicate();
                from.position(0);
                from.limit(bytes / 4);
                out.asFloatBuffer().put(from);
            } else {
                final ByteBuffer from = ((ByteBuffer)client).duplicate();
                from.position(0);
                from.limit(bytes);
                out.put(from);
            }
            frame.presenter().flushVertices(block, offset, bytes);
            s.bindVertex(block, offset);
        } else {
            s.bindVertexBuffer(nativeVertices, 0);
            for (int b = 1; b < bindingCount; b++) {
                final int other = gl.nativeBuffer(bindingBuffer[b]);
                if (other < 0) throw new GdxRuntimeException("gdx-vulkan: a vertex buffer has no data");
                s.bindVertexBufferAt(b, other, 0);
            }
        }
        if (constants > 0) { // the values of the attributes without an array: one vec4 each, in the order the pipeline lists them
            final long a = frame.presenter().allocVertex(constants * 16);
            final int block = GdxVkPresenter.blockOf(a);
            final long offset = GdxVkPresenter.offsetOf(a);
            final ByteBuffer raw = frame.vertexBlock(block).duplicate().order(ByteOrder.nativeOrder());
            raw.position((int)offset);
            final FloatBuffer out = raw.asFloatBuffer();
            for (int c = 0; c < constants; c++) out.put(generic, constantLocations[c] * 4, 4);
            frame.presenter().flushVertices(block, offset, constants * 16);
            s.bindConstants(block, offset);
        }
        if (indexed && clientIndices != null) {
            final boolean wide = indexType == GL_UNSIGNED_INT;
            final int bytes = count * (wide ? 4 : 2);
            final long a = frame.presenter().allocVertex(bytes);
            final int block = GdxVkPresenter.blockOf(a);
            final long offset = GdxVkPresenter.offsetOf(a);
            final ByteBuffer out = frame.vertexBlock(block).duplicate().order(ByteOrder.nativeOrder());
            out.position((int)offset);
            if (wide) {
                final IntBuffer in = ((IntBuffer)clientIndices).duplicate();
                in.limit(in.position() + count);
                out.asIntBuffer().put(in);
            } else {
                final ShortBuffer in = ((ShortBuffer)clientIndices).duplicate();
                in.limit(in.position() + count);
                out.asShortBuffer().put(in);
            }
            frame.presenter().flushVertices(block, offset, bytes);
            s.bindIndexRing(block, offset, wide ? CommandStream.INDEX_U32 : CommandStream.INDEX_U16);
            s.drawIndexed(count, 0, 0);
        } else if (indexed) {
            final int nativeIndices = gl.nativeBuffer(elementBuffer);
            if (nativeIndices < 0) throw new GdxRuntimeException("gdx-vulkan: the index buffer has no data");
            if (indexType != GL_UNSIGNED_SHORT && indexType != GL_UNSIGNED_INT) {
                throw new GdxRuntimeException("gdx-vulkan: glDrawElements with index type 0x" + Integer.toHexString(indexType) + ": only unsigned short and unsigned int");
            }
            s.bindIndexBuffer(nativeIndices, indexOffset, indexType == GL_UNSIGNED_SHORT ? CommandStream.INDEX_U16 : CommandStream.INDEX_U32);
            s.drawIndexed(count, 0, 0);
        } else {
            s.draw(count, first);
        }
    }

    /** Mixes a value into a 64-bit signature (splitmix-style), so that equal state gives equal numbers and different state almost never does. */
    private static long mix (long h, long v) {
        long x = h ^ (v + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2));
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }

    /** The highest vertex index among the {@code count} indices from the buffer's position. */
    private static int maxIndex (Buffer indices, int count, int type) {
        int max = 0;
        if (type == GL_UNSIGNED_INT && indices instanceof IntBuffer) {
            final IntBuffer b = (IntBuffer)indices;
            for (int i = 0, p = b.position(); i < count; i++) max = Math.max(max, b.get(p + i));
        } else if (type == GL_UNSIGNED_SHORT && indices instanceof ShortBuffer) {
            final ShortBuffer b = (ShortBuffer)indices;
            for (int i = 0, p = b.position(); i < count; i++) max = Math.max(max, b.get(p + i) & 0xFFFF);
        } else {
            throw new GdxRuntimeException("gdx-vulkan: glDrawElements with " + indices.getClass().getSimpleName() + " of type 0x" + Integer.toHexString(type)
                + " in memory: unsigned short indices in a ShortBuffer, or unsigned int in an IntBuffer");
        }
        return max;
    }

    private static int topology (int mode) {
        switch (mode) {
        case 0x0000: return GdxVkPipelineDesc.POINTS;
        case 0x0001: return GdxVkPipelineDesc.LINES;
        case 0x0003: return GdxVkPipelineDesc.LINE_STRIP;
        case 0x0004: return GdxVkPipelineDesc.TRIANGLES;
        case 0x0005: return GdxVkPipelineDesc.TRIANGLE_STRIP;
        case 0x0006: return GdxVkPipelineDesc.TRIANGLE_FAN;
        default: throw new GdxRuntimeException("gdx-vulkan: unsupported primitive type 0x" + Integer.toHexString(mode) + " (GL_LINE_LOOP has no Vulkan equivalent)");
        }
    }

    private static int typeBytes (int type) {
        switch (type) {
        case GL_BYTE: case GL_UNSIGNED_BYTE: return 1;
        case GL_SHORT: case GL_UNSIGNED_SHORT: return 2;
        default: return 4;
        }
    }

    /** The Vulkan format of a vertex attribute: what stock libGDX uses (floats, and packed colours as four normalized bytes). */
    private static int format (Pointer p) {
        if (p.type == GL_FLOAT) {
            switch (p.size) {
            case 1: return 100;  // R32_SFLOAT
            case 2: return GdxVkPipelineDesc.FORMAT_R32G32_SFLOAT;
            case 3: return GdxVkPipelineDesc.FORMAT_R32G32B32_SFLOAT;
            default: return GdxVkPipelineDesc.FORMAT_R32G32B32A32_SFLOAT;
            }
        }
        if (p.type == GL_UNSIGNED_BYTE && p.normalized) {
            switch (p.size) {
            case 1: return 9;   // R8_UNORM
            case 2: return 16;  // R8G8_UNORM
            case 3: return 23;  // R8G8B8_UNORM
            default: return GdxVkPipelineDesc.FORMAT_R8G8B8A8_UNORM;
            }
        }
        throw new GdxRuntimeException("gdx-vulkan: vertex attribute of type 0x" + Integer.toHexString(p.type) + ", size " + p.size + (p.normalized ? ", normalized" : "")
            + " is not supported (floats and normalized unsigned bytes are)");
    }

    /** Frees what belongs to a frame that is going away (the pipeline ids are the frame's). */
    void frameReplaced () {
        pipelines.clear();
    }
}
