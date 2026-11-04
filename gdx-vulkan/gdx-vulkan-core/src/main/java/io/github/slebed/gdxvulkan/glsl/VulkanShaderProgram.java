package io.github.slebed.gdxvulkan.glsl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import io.github.slebed.gdxvulkan.CommandStream;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkPipelineDesc;
import io.github.slebed.gdxvulkan.GlslCompiler;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.Attribute;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.GlslTranslationException;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.Sampler;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.Translation;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.Uniform;

/**
 * A shader program written in libGDX's GLSL (the {@code attribute} / {@code varying} / {@code uniform} dialect of {@code ShaderProgram}), run by gdx-vulkan: the
 * same constructors and uniform setters as the stock class, so a custom shader moves over by changing the class name. The source is translated to Vulkan GLSL
 * and compiled once, when the program is created ({@link GlslTranslator}, {@code docs/SHADER_TRANSLATOR.md}); setting a uniform afterwards only writes a few
 * bytes into the program's own storage, which reaches the GPU as push constants or one uniform block when the next draw uses it.
 *
 * <p>Give the program to a batch with {@code setShaderProgram}. Use it on the thread that renders, after the context exists (in {@code create()} or later).
 * A shader that fails to translate or compile does not throw: like stock, {@link #isCompiled()} is false and {@link #getLog()} says why.
 */
public class VulkanShaderProgram implements Disposable {
    /** Text put in front of every vertex shader, like {@code ShaderProgram.prependVertexCode}: {@code #define}s that switch variants. */
    public static String prependVertexCode = "";
    /** Text put in front of every fragment shader. */
    public static String prependFragmentCode = "";
    /** When true (the default, as in stock) setting a uniform the shader does not read throws; when false it is ignored. */
    public static boolean pedantic = true;

    private static int nextId;

    private final int id = nextId++;
    private final GdxVkFrame frame;
    private final Translation t;
    private final byte[] vertexSpirv, fragmentSpirv;
    private final String log;
    private final boolean compiled;
    private boolean disposed;

    private final int[] pushWords = new int[32];
    private final int[] ringWords;
    private final int[] samplerUnit;
    private int ringVersion;
    private long ringBoundFrame = -1, ringBoundGeneration = -1;
    private int ringBoundVersion = -1;

    /** Compiles into the running application's frame ({@link GdxVkFrame#current()}). */
    public VulkanShaderProgram (String vertexShader, String fragmentShader) {
        this(GdxVkFrame.current(), vertexShader, fragmentShader);
    }

    public VulkanShaderProgram (FileHandle vertexShader, FileHandle fragmentShader) {
        this(vertexShader.readString(), fragmentShader.readString());
    }

    public VulkanShaderProgram (GdxVkFrame frame, String vertexShader, String fragmentShader) {
        if (vertexShader == null) throw new IllegalArgumentException("vertex shader must not be null");
        if (fragmentShader == null) throw new IllegalArgumentException("fragment shader must not be null");
        this.frame = frame;
        Translation translation = null;
        byte[] vert = null, frag = null;
        String message = "";
        boolean ok = false;
        try {
            translation = GlslTranslator.translate("program" + id, vertexShader, fragmentShader, prependVertexCode, prependFragmentCode, frame.isTierA());
            final GlslCompiler.Result v = GlslCompiler.compile(GlslCompiler.Stage.VERTEX, "vertex shader", translation.vertex);
            final GlslCompiler.Result f = GlslCompiler.compile(GlslCompiler.Stage.FRAGMENT, "fragment shader", translation.fragment);
            message = (v.ok() ? "" : "Vertex shader failed to compile:\n" + v.log + "\n") + (f.ok() ? "" : "Fragment shader failed to compile:\n" + f.log + "\n");
            if (v.ok() && f.ok()) {
                vert = bytes(v.spirv);
                frag = bytes(f.spirv);
                ok = true;
            }
        } catch (GlslTranslationException e) {
            message = e.getMessage() + "\n";
        }
        t = translation != null ? translation : new Translation();
        vertexSpirv = vert;
        fragmentSpirv = frag;
        compiled = ok;
        log = message;
        ringWords = new int[Math.max(1, t.blockBytes / 4)];
        samplerUnit = new int[t.samplers.size()];
    }

    private static byte[] bytes (int[] words) {
        final ByteBuffer b = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.asIntBuffer().put(words);
        return b.array();
    }

    // ---------------------------------------------------------------------------------------------------------------------------- queries

    /** Whether the program translated and compiled. */
    public boolean isCompiled () {
        return compiled;
    }

    /** The messages of translation and compilation; empty when there was nothing to say. Line numbers are those of your source. */
    public String getLog () {
        return log;
    }

    public String[] getAttributes () {
        final String[] names = new String[t.attributes.size()];
        for (int i = 0; i < names.length; i++) names[i] = t.attributes.get(i).name;
        return names;
    }

    public boolean hasAttribute (String name) {
        return t.attribute(name) != null;
    }

    /** The vertex input location of an attribute, -1 when the shader has none by that name. Locations follow the order of declaration. */
    public int getAttributeLocation (String name) {
        final Attribute a = t.attribute(name);
        return a == null ? -1 : a.location;
    }

    /** The GLSL type of an attribute ({@code vec2}, {@code vec4}, ...), null when the shader has none by that name. */
    public String getAttributeType (String name) {
        final Attribute a = t.attribute(name);
        return a == null ? null : a.type;
    }

    /** The names of the uniforms the shader reads, samplers included. */
    public String[] getUniforms () {
        final java.util.List<String> names = new java.util.ArrayList<String>();
        for (Uniform u : t.uniforms) if (u.active) names.add(u.name);
        for (Sampler s : t.samplers) if (s.active) names.add(s.name);
        return names.toArray(new String[0]);
    }

    public boolean hasUniform (String name) {
        return fetchUniformLocation(name, false) >= 0;
    }

    /**
     * An index for a uniform to use with the setters that take a location; -1 when the shader does not read it. With {@link #pedantic} a missing name throws, as
     * in stock.
     */
    public int fetchUniformLocation (String name) {
        return fetchUniformLocation(name, pedantic);
    }

    public int fetchUniformLocation (String name, boolean pedantic) {
        int location = -1;
        for (int i = 0; i < t.uniforms.size(); i++) {
            if (t.uniforms.get(i).name.equals(name) && t.uniforms.get(i).active) location = i;
        }
        if (location < 0) {
            for (int i = 0; i < t.samplers.size(); i++) {
                if (t.samplers.get(i).name.equals(name) && t.samplers.get(i).active) location = t.uniforms.size() + i;
            }
        }
        if (location == -1 && pedantic) throw new IllegalArgumentException("no uniform with name '" + name + "' in shader");
        return location;
    }

    // --------------------------------------------------------------------------------------------------------------------------- setters

    /** Stock's {@code bind()}: nothing to do, the program is used by the batch it was given to. */
    public void bind () {
    }

    public void begin () {
    }

    public void end () {
    }

    public void setUniformi (String name, int value) {
        setUniformi(fetchUniformLocation(name), value);
    }

    public void setUniformi (int location, int value) {
        if (location < 0) return;
        if (location >= t.uniforms.size()) { // a sampler: the value is its texture unit
            samplerUnit[location - t.uniforms.size()] = value;
            return;
        }
        final Uniform u = t.uniforms.get(location);
        requireKind(u, "int", 1);
        putInt(u, 0, value);
    }

    public void setUniformi (String name, int v1, int v2) {
        setUniformInts(name, "ivec2", v1, v2);
    }

    public void setUniformi (String name, int v1, int v2, int v3) {
        setUniformInts(name, "ivec3", v1, v2, v3);
    }

    public void setUniformi (String name, int v1, int v2, int v3, int v4) {
        setUniformInts(name, "ivec4", v1, v2, v3, v4);
    }

    private void setUniformInts (String name, String type, int... v) {
        final int location = fetchUniformLocation(name);
        if (location < 0) return;
        final Uniform u = t.uniforms.get(location);
        requireKind(u, type, v.length);
        for (int i = 0; i < v.length; i++) putInt(u, i, v[i]);
    }

    public void setUniformf (String name, float value) {
        setUniformf(fetchUniformLocation(name), value);
    }

    public void setUniformf (int location, float value) {
        if (location < 0 || location >= t.uniforms.size()) return;
        final Uniform u = t.uniforms.get(location);
        requireKind(u, "float", 1);
        putFloat(u, 0, value);
    }

    public void setUniformf (String name, float v1, float v2) {
        setUniformFloats(name, "vec2", v1, v2);
    }

    public void setUniformf (String name, float v1, float v2, float v3) {
        setUniformFloats(name, "vec3", v1, v2, v3);
    }

    public void setUniformf (String name, float v1, float v2, float v3, float v4) {
        setUniformFloats(name, "vec4", v1, v2, v3, v4);
    }

    public void setUniformf (String name, Vector2 values) {
        setUniformf(name, values.x, values.y);
    }

    public void setUniformf (String name, Vector3 values) {
        setUniformf(name, values.x, values.y, values.z);
    }

    public void setUniformf (String name, Color values) {
        setUniformf(name, values.r, values.g, values.b, values.a);
    }

    private void setUniformFloats (String name, String type, float... v) {
        final int location = fetchUniformLocation(name);
        if (location < 0) return;
        final Uniform u = t.uniforms.get(location);
        requireKind(u, type, v.length);
        for (int i = 0; i < v.length; i++) putFloat(u, i, v[i]);
    }

    /** Sets an array of floats ({@code float u[n]}), or one {@code float}. */
    public void setUniform1fv (String name, float[] values, int offset, int length) {
        setUniformArray(name, "float", 1, values, offset, length);
    }

    /** Sets an array of {@code vec2}: {@code length} counts floats, two per element. */
    public void setUniform2fv (String name, float[] values, int offset, int length) {
        setUniformArray(name, "vec2", 2, values, offset, length);
    }

    public void setUniform3fv (String name, float[] values, int offset, int length) {
        setUniformArray(name, "vec3", 3, values, offset, length);
    }

    public void setUniform4fv (String name, float[] values, int offset, int length) {
        setUniformArray(name, "vec4", 4, values, offset, length);
    }

    private void setUniformArray (String name, String type, int components, float[] values, int offset, int length) {
        final int location = fetchUniformLocation(name);
        if (location < 0) return;
        final Uniform u = t.uniforms.get(location);
        if (!u.type.equals(type)) throw new GdxRuntimeException("the uniform '" + name + "' is " + u.type + ", not " + type);
        if (length % components != 0) throw new GdxRuntimeException("the uniform '" + name + "' takes " + components + " floats per element, not " + length + " in all");
        final int elements = length / components;
        final int capacity = Math.max(1, u.arraySize);
        if (elements > capacity) throw new GdxRuntimeException("the uniform '" + name + "' holds " + capacity + " elements, not " + elements);
        for (int e = 0; e < elements; e++) {
            for (int c = 0; c < components; c++) putFloatAt(u, e, c, values[offset + e * components + c]);
        }
    }

    public void setUniformMatrix (String name, Matrix4 matrix) {
        setUniformMatrix(name, matrix, false);
    }

    public void setUniformMatrix (String name, Matrix4 matrix, boolean transpose) {
        setUniformMatrix4fv(name, matrix.val, 0, 16, transpose);
    }

    public void setUniformMatrix (String name, Matrix3 matrix) {
        setUniformMatrix (name, matrix, false);
    }

    public void setUniformMatrix (String name, Matrix3 matrix, boolean transpose) {
        final int location = fetchUniformLocation(name);
        if (location < 0) return;
        final Uniform u = t.uniforms.get(location);
        requireKind(u, "mat3", 9);
        writeMatrix(u, 0, matrix.val, 0, 3, transpose);
    }

    /** Sets a {@code mat4} (16 floats) or an array of them ({@code length} counts floats). The values are column-major, as in {@code Matrix4.val}. */
    public void setUniformMatrix4fv (String name, float[] values, int offset, int length) {
        setUniformMatrix4fv(name, values, offset, length, false);
    }

    public void setUniformMatrix4fv (String name, float[] values, int offset, int length, boolean transpose) {
        final int location = fetchUniformLocation(name);
        if (location < 0) return;
        final Uniform u = t.uniforms.get(location);
        if (!u.type.equals("mat4")) throw new GdxRuntimeException("the uniform '" + name + "' is " + u.type + ", not mat4");
        if (length % 16 != 0) throw new GdxRuntimeException("a mat4 takes 16 floats, not " + length);
        final int count = length / 16;
        if (count > Math.max(1, u.arraySize)) throw new GdxRuntimeException("the uniform '" + name + "' holds " + Math.max(1, u.arraySize) + " matrices, not " + count);
        for (int m = 0; m < count; m++) writeMatrix(u, m, values, offset + m * 16, 4, transpose);
    }

    public void setUniformMatrix3fv (String name, float[] values, int offset, int length) {
        final int location = fetchUniformLocation(name);
        if (location < 0) return;
        final Uniform u = t.uniforms.get(location);
        if (!u.type.equals("mat3")) throw new GdxRuntimeException("the uniform '" + name + "' is " + u.type + ", not mat3");
        for (int m = 0; m < length / 9; m++) writeMatrix(u, m, values, offset + m * 9, 3, false);
    }

    public void setUniformMatrix2fv (String name, float[] values, int offset, int length) {
        final int location = fetchUniformLocation(name);
        if (location < 0) return;
        final Uniform u = t.uniforms.get(location);
        if (!u.type.equals("mat2")) throw new GdxRuntimeException("the uniform '" + name + "' is " + u.type + ", not mat2");
        for (int m = 0; m < length / 4; m++) writeMatrix(u, m, values, offset + m * 4, 2, false);
    }

    // ------------------------------------------------------------------------------------------------------------------------- the bytes

    private static void requireKind (Uniform u, String type, int values) {
        final boolean ok = u.type.equals(type) || (type.equals("int") && u.type.equals("bool"));
        if (!ok) throw new GdxRuntimeException("the uniform '" + u.name + "' is " + u.type + ", it cannot be set with " + values + " value" + (values == 1 ? "" : "s") + " of that kind");
    }

    private void putInt (Uniform u, int component, int value) {
        putWord(u, 0, component, value);
    }

    private void putFloat (Uniform u, int component, float value) {
        putWord(u, 0, component, Float.floatToRawIntBits(value));
    }

    private void putFloatAt (Uniform u, int element, int component, float value) {
        putWord(u, element, component, Float.floatToRawIntBits(value));
    }

    /** Writes one 32-bit word of a uniform: element {@code element} of an array, component {@code component} of a vector. */
    private void putWord (Uniform u, int element, int component, int word) {
        final int at = u.offset + element * u.arrayStride + component * 4;
        write(u, at, word);
    }

    private void write (Uniform u, int byteOffset, int word) {
        final int[] words = u.push ? pushWords : ringWords;
        final int i = byteOffset >> 2;
        if (words[i] == word) return;
        words[i] = word;
        if (!u.push) ringVersion++;
    }

    /** Writes a square matrix column by column; the storage pads each column (a mat3's columns are 16 bytes apart). */
    private void writeMatrix (Uniform u, int element, float[] src, int srcOffset, int n, boolean transpose) {
        final int base = u.offset + element * u.arrayStride;
        final int stride = u.columnStride;
        for (int col = 0; col < n; col++) {
            for (int row = 0; row < n; row++) {
                final float value = transpose ? src[srcOffset + row * n + col] : src[srcOffset + col * n + row];
                write(u, base + col * stride + row * 4, Float.floatToRawIntBits(value));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- used by the batches

    /** Whether the batches may draw with this program. */
    public void requireCompiled () {
        if (!compiled) throw new GdxRuntimeException("this shader program did not compile:\n" + log);
    }

    /** The program's translated tables: attributes, uniforms and where they live. */
    public Translation translation () {
        return t;
    }

    /** Identifies the program in the frame's pipeline cache. */
    public String key () {
        return "program" + id;
    }

    /** How many colour outputs the fragment shader writes: the framebuffer it draws into needs as many attachments. */
    public int colorOutputs () {
        return t.colorOutputs;
    }

    /** The pipeline for this program with the given vertex layout and blend state, built on first use. */
    public int pipeline (String variant, GdxVkPipelineDesc desc) {
        requireCompiled();
        desc.colorOutputs = t.colorOutputs;
        return frame.pipeline(key() + "|" + variant, key(), vertexSpirv, fragmentSpirv, desc);
    }

    /**
     * Gets the program ready for the next draw: binds the texture of each sampler (slot in the push constants on the modern path, the one bound descriptor on
     * the compatibility path), and returns the push constants in {@code block32}. Binds the uniform block first when it changed since this program bound it.
     *
     * @param unitZeroTexture the GL handle of the texture the batch is drawing with, which is the one at texture unit 0 as in stock
     */
    public void prepareDraw (CommandStream s, float[] block32, int unitZeroTexture) {
        final VulkanGL20 gl = frame.gl();
        final boolean bindless = t.bindless;
        for (int i = 0; i < t.samplers.size(); i++) {
            final Sampler sampler = t.samplers.get(i);
            if (!sampler.active) continue;
            final int unit = samplerUnit[i];
            final int handle = unit == 0 ? unitZeroTexture : gl.boundTexture(unit);
            final int nativeId = gl.nativeTexture(handle);
            if (nativeId < 0) throw new GdxRuntimeException("the texture for sampler '" + sampler.name + "' (unit " + unit + ") has no storage");
            if (bindless) pushWords[sampler.slotOffset >> 2] = gl.slotOf(nativeId);
            else s.bindTexture(nativeId);
        }
        for (int i = 0; i < 32; i++) block32[i] = Float.intBitsToFloat(pushWords[i]);
        if (t.blockBytes > 0) {
            // Set 0 data lives for the frame's command stream; rebind when the frame, the data, or somebody else's Set 0 binding changed.
            final long generation = s.set0Generation();
            if (ringBoundFrame != frame.frameNumber() || ringBoundVersion != ringVersion || ringBoundGeneration != generation) {
                s.set0Data(ringWords, t.blockBytes, 0);
                ringBoundFrame = frame.frameNumber();
                ringBoundVersion = ringVersion;
                ringBoundGeneration = s.set0Generation();
            }
        }
    }

    @Override public void dispose () {
        disposed = true; // the SPIR-V and pipelines belong to the frame's cache; nothing native is held per program
    }

    public boolean isDisposed () {
        return disposed;
    }
}
