package io.github.slebed.gdxvulkan.gl;

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.IntMap;

import io.github.slebed.gdxvulkan.CommandStream;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPipelineDesc;
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
    }

    private final VulkanGL20 gl;
    private final IntMap<Shader> shaders = new IntMap<Shader>();
    private final IntMap<Program> programs = new IntMap<Program>();
    private final Pointer[] pointers = new Pointer[MAX_ATTRIBUTES];
    private final float[] push = new float[32];
    private final StringBuilder key = new StringBuilder(128);
    private final Map<String, Integer> pipelines = new HashMap<String, Integer>();
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

    void enableVertexAttrib (int index, boolean enabled) {
        pointer(index).enabled = enabled;
    }

    void vertexAttribPointer (int index, int size, int type, boolean normalized, int stride, int offset, int arrayBuffer) {
        final Pointer p = pointer(index);
        if (arrayBuffer == 0) {
            throw new GdxRuntimeException("gdx-vulkan: glVertexAttribPointer without an array buffer bound: vertex data in client memory (a Mesh of the VertexArray type) is not supported; use a Mesh with a vertex buffer object");
        }
        p.size = size;
        p.type = type;
        p.normalized = normalized;
        p.stride = stride;
        p.offset = offset;
        p.buffer = arrayBuffer;
    }

    private Pointer pointer (int index) {
        if (index < 0 || index >= MAX_ATTRIBUTES) throw new GdxRuntimeException("gdx-vulkan: vertex attribute " + index + " is out of range");
        return pointers[index];
    }

    // ----------------------------------------------------------------------------------------------------------------------------- draws

    void drawArrays (int mode, int first, int count) {
        draw(mode, count, first, false, 0, 0, 0);
    }

    void drawElements (int mode, int count, int type, int offset, int elementBuffer) {
        if (elementBuffer == 0) {
            throw new GdxRuntimeException("gdx-vulkan: glDrawElements without an element buffer bound: index data in client memory (a Mesh of the VertexArray type) is not supported; use a Mesh with an index buffer object");
        }
        draw(mode, count, 0, true, type, offset, elementBuffer);
    }

    private void draw (int mode, int count, int first, boolean indexed, int indexType, int indexOffset, int elementBuffer) {
        if (count <= 0) return;
        final VulkanShaderProgram program = inUse("a draw");
        final GdxVkFrame frame = gl.attachedFrame();
        final CommandStream s = gl.stream();
        if (frame == null || s == null) throw new GdxRuntimeException("gdx-vulkan: a draw outside a frame (frame.begin() was not called)");

        // The layout of the vertex data: every enabled array the program reads, from one buffer.
        int buffer = 0, stride = 0;
        key.setLength(0);
        key.append(mode).append('|');
        final boolean blend = gl.isBlendEnabled();
        if (blend) {
            final int[] f = gl.blendFunctions();
            key.append(f[0]).append(',').append(f[1]).append(',').append(f[2]).append(',').append(f[3]);
        } else {
            key.append('o');
        }
        key.append('|');
        int enabledReadByProgram = 0;
        for (int i = 0; i < MAX_ATTRIBUTES; i++) {
            final Pointer p = pointers[i];
            if (!p.enabled || p.size == 0) continue;
            if (!program.hasAttributeLocation(i)) continue; // an array the program does not read
            if (buffer != 0 && p.buffer != buffer) {
                throw new GdxRuntimeException("gdx-vulkan: the vertex attributes come from more than one buffer; interleave them in one (stock Mesh does)");
            }
            buffer = p.buffer;
            stride = p.stride != 0 ? p.stride : p.size * typeBytes(p.type);
            key.append(i).append(':').append(p.size).append(':').append(p.type).append(p.normalized ? 'n' : 'r').append(':').append(p.offset).append(';');
            enabledReadByProgram++;
        }
        if (enabledReadByProgram == 0) throw new GdxRuntimeException("gdx-vulkan: a draw with the program using no enabled vertex attribute");
        key.append(stride);
        final String variant = key.toString();
        Integer pipeline = pipelines.get(program.key() + "|" + variant);
        if (pipeline == null) {
            final GdxVkPipelineDesc d = new GdxVkPipelineDesc();
            d.stride = stride;
            d.topology = topology(mode);
            d.cullMode = GdxVkPipelineDesc.CULL_NONE; // GL's cull state is an ordered operation
            if (blend) {
                final int[] f = gl.blendFunctions();
                d.blend = true;
                d.srcColor = f[0];
                d.dstColor = f[1];
                d.srcAlpha = f[2];
                d.dstAlpha = f[3];
            }
            for (int i = 0; i < MAX_ATTRIBUTES; i++) {
                final Pointer p = pointers[i];
                if (!p.enabled || p.size == 0 || !program.hasAttributeLocation(i)) continue;
                d.attribute(i, format(p), p.offset);
            }
            pipeline = program.pipeline("gl|" + variant, d);
            pipelines.put(program.key() + "|" + variant, pipeline);
        }

        final int nativeVertices = gl.nativeBuffer(buffer);
        if (nativeVertices < 0) throw new GdxRuntimeException("gdx-vulkan: the vertex buffer has no data");
        if (program.hasThinLineNudge()) { // as VulkanImmediateModeRenderer does: lines and points move 1/256 pixel the way GL resolves a tie
            final boolean thin = mode == 0x0000 || mode == 0x0001 || mode == 0x0003;
            program.setPushFloat(96, thin ? -2f / 256f / Math.max(1, frame.height()) : 0f);
        }
        s.bindPipeline(pipeline);
        program.prepareDraw(s, push, gl.boundTexture(0));
        s.pushConstants(push);
        s.bindVertexBuffer(nativeVertices, 0);
        if (indexed) {
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
