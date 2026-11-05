package io.github.slebed.gdxvulkan.glsl;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.badlogic.gdx.Gdx;
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

    /**
     * Where compiled programs are kept so the next launch does not translate or compile them (see {@code ShaderCache}). The default is the
     * {@code gdxvk-shader-cache} folder in the application's local storage ({@code Gdx.files.local}); set it to another folder, or to null to turn the cache off.
     * Set it before creating programs.
     */
    public static File cacheDirectory;
    private static boolean cacheDirectoryChosen;

    /** Chooses the cache folder explicitly (null: no cache) and stops the default from being looked up. */
    public static void setCacheDirectory (File directory) {
        cacheDirectory = directory;
        cacheDirectoryChosen = true;
    }
    /** How many programs were created from the cache instead of being compiled (for tests and diagnostics). */
    public static int cacheHits;

    /** Set by the {@code recordShaderBundle} task: every program built is saved in this folder, to be shipped in the jar. */
    private static final File RECORD = System.getProperty("gdxvulkan.recordShaders") == null ? null : new File(System.getProperty("gdxvulkan.recordShaders"));
    /** How many programs came from the bundle compiled with the library (for tests and diagnostics). */
    private static volatile int bundleHits;

    public static int bundledShaderHits () {
        return bundleHits;
    }

    private static File cacheDirectory () {
        if (!cacheDirectoryChosen) {
            cacheDirectoryChosen = true;
            if (RECORD != null) {
                cacheDirectory = RECORD;
                return cacheDirectory;
            }
            if (cacheDirectory == null && Gdx.files != null) {
                try {
                    cacheDirectory = Gdx.files.local("gdxvk-shader-cache").file();
                } catch (RuntimeException e) {
                    cacheDirectory = null; // a files implementation without a local file
                }
            }
        }
        return cacheDirectory;
    }

    private static int nextId;

    private final int id = nextId++;
    private final GdxVkFrame frame;
    private final Translation t;
    private final byte[] vertexSpirv, fragmentSpirv;
    private final String log;
    private final boolean compiled;
    private boolean disposed;
    private final boolean thinLineNudge;

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
        this(frame, vertexShader, fragmentShader, prependVertexCode, prependFragmentCode);
    }

    /**
     * For the GL shim: stock {@code ShaderProgram} has already put its prepend text in front of the sources it hands to {@code glShaderSource}, so the
     * program is made from them as they are.
     */
    public static VulkanShaderProgram fromAssembledSources (GdxVkFrame frame, String vertexShader, String fragmentShader) {
        return new VulkanShaderProgram(frame, vertexShader, fragmentShader, "", "");
    }

    private VulkanShaderProgram (GdxVkFrame frame, String vertexShader, String fragmentShader, String prependVertex, String prependFragment) {
        if (vertexShader == null) throw new IllegalArgumentException("vertex shader must not be null");
        if (fragmentShader == null) throw new IllegalArgumentException("fragment shader must not be null");
        this.frame = frame;
        final Built built = obtain(frame.isTierA(), "program" + id, vertexShader, fragmentShader, prependVertex, prependFragment, false);
        t = built.translation != null ? built.translation : new Translation();
        vertexSpirv = built.vertex;
        fragmentSpirv = built.fragment;
        compiled = built.ok;
        thinLineNudge = "shape".equals(built.known);
        log = built.log;
        ringWords = new int[Math.max(1, t.blockBytes / 4)];
        samplerUnit = new int[t.samplers.size()];
    }

    /** What building a program produces: the translation and both SPIR-V modules, or the reason there are none. */
    private static final class Built {
        Translation translation;
        byte[] vertex, fragment;
        String log = "";
        boolean ok;
        String known; // the name of the dedicated shader this stands for, null for a translated program
    }

    /** Programs made ahead of time by {@link #prepareAsync}, by cache key: taken by the constructor when the same source is asked for. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Built> prepared = new java.util.concurrent.ConcurrentHashMap<String, Built>();
    /** How many programs were created from a {@link #prepareAsync} result (for tests and diagnostics). */
    public static volatile int preparedHits;
    private static java.util.concurrent.ExecutorService worker;

    /**
     * Translates and compiles in the background, so a program that is needed later (the next level's shaders, for instance) does not cost a frame when it is
     * created: the constructor then finds the result and takes it. Safe to call from any thread; the work runs on one worker thread. The future's value is the
     * log, empty when the program compiled, otherwise the reason it did not. The sources and the {@code prepend...Code} statics are read now, not later.
     */
    public static java.util.concurrent.Future<String> prepareAsync (String vertexShader, String fragmentShader) {
        return prepareAsync(GdxVkFrame.current(), vertexShader, fragmentShader);
    }

    public static synchronized java.util.concurrent.Future<String> prepareAsync (final GdxVkFrame frame, final String vertexShader, final String fragmentShader) {
        if (vertexShader == null || fragmentShader == null) throw new IllegalArgumentException("shaders must not be null");
        final boolean bindless = frame.isTierA();
        final String prependV = prependVertexCode, prependF = prependFragmentCode;
        if (worker == null) {
            worker = java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread (Runnable r) {
                    final Thread thread = new Thread(r, "gdxvk-shader-compiler");
                    thread.setDaemon(true);
                    return thread;
                }
            });
        }
        return worker.submit(new java.util.concurrent.Callable<String>() {
            @Override public String call () {
                return obtain(bindless, "prepared", vertexShader, fragmentShader, prependV, prependF, true).log;
            }
        });
    }

    /**
     * Starts the compiler on the background thread with a tiny shader, so its first use (about 150 ms of start-up inside glslang) is not paid by the first real
     * program. Call it early, at the start of {@code create()}.
     */
    public static java.util.concurrent.Future<String> warmUp () {
        return warmUp(GdxVkFrame.current());
    }

    public static java.util.concurrent.Future<String> warmUp (GdxVkFrame frame) {
        return prepareAsync(frame, "attribute vec4 a_position;\nvoid main() { gl_Position = a_position; }\n",
            "#ifdef GL_ES\nprecision mediump float;\n#endif\nvoid main() { gl_FragColor = vec4(1.0); }\n");
    }

    /** The result for this source: from memory (prepared ahead), from the disk cache, or built now. {@code keep} remembers a success for the constructor. */
    private static Built obtain (boolean bindless, String name, String vertexShader, String fragmentShader, String prependV, String prependF, boolean keep) {
        final String key = ShaderCache.key(bindless, prependV, vertexShader, prependF, fragmentShader);
        final Built ready = keep ? prepared.get(key) : prepared.remove(key);
        if (ready != null) {
            if (!keep) preparedHits++;
            return ready;
        }
        final KnownShaders.Match known = KnownShaders.find(prependV + vertexShader, prependF + fragmentShader, bindless);
        if (known != null) { // libGDX's own sprite and shape shaders: the dedicated Vulkan ones, no translation
            KnownShaders.hits++;
            final Built b = new Built();
            b.translation = known.table;
            b.vertex = known.vertex;
            b.fragment = known.fragment;
            b.known = known.name;
            b.ok = true;
            return b;
        }
        final File cache = cacheDirectory();
        // Programs compiled when the library was built (the variants of libGDX's own DefaultShader and DepthShader). Not while recording a new bundle.
        final ShaderCache.Entry bundled = RECORD == null ? ShaderCache.loadBundled(key) : null;
        final ShaderCache.Entry cached = bundled != null ? bundled : cache == null ? null : ShaderCache.load(cache, key);
        if (bundled != null) bundleHits++;
        final Built built = new Built();
        if (cached != null) {
            cacheHits++;
            built.translation = cached.translation;
            built.vertex = cached.vertex;
            built.fragment = cached.fragment;
            built.ok = true;
        } else {
            try {
                built.translation = GlslTranslator.translate(name, vertexShader, fragmentShader, prependV, prependF, bindless);
                final GlslCompiler.Result v = GlslCompiler.compile(GlslCompiler.Stage.VERTEX, "vertex shader", built.translation.vertex);
                final GlslCompiler.Result f = GlslCompiler.compile(GlslCompiler.Stage.FRAGMENT, "fragment shader", built.translation.fragment);
                built.log = (v.ok() ? "" : "Vertex shader failed to compile:\n" + v.log + "\n") + (f.ok() ? "" : "Fragment shader failed to compile:\n" + f.log + "\n");
                if (v.ok() && f.ok()) {
                    built.vertex = bytes(v.spirv);
                    built.fragment = bytes(f.spirv);
                    built.ok = true;
                    if (cache != null) ShaderCache.save(cache, key, built.translation, built.vertex, built.fragment);
                }
            } catch (GlslTranslationException e) {
                built.log = e.getMessage() + "\n";
            }
        }
        if (keep && built.ok) prepared.put(key, built);
        return built;
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

    private int[] attributeLocations;

    /** The vertex input locations of the program's attributes, as an array to walk without an iterator. */
    public int[] attributeLocations () {
        if (attributeLocations == null) {
            final int[] out = new int[t.attributes.size()];
            for (int i = 0; i < out.length; i++) out[i] = t.attributes.get(i).location;
            attributeLocations = out;
        }
        return attributeLocations;
    }

    /** A number for this program, unique in the process: what the GL shim keys its pipelines by. */
    public int id () {
        return id;
    }

    /** Whether the shader has an attribute at this vertex input location. */
    public boolean hasAttributeLocation (int location) {
        for (Attribute a : t.attributes) if (a.location == location) return true;
        return false;
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
        return uniformLocation(name) >= 0;
    }

    // --------------------------------------------------------------------------------------------------------------------------- setters

    /** A location is {@code uniformIndex * 1024 + element}: location + 1 is the next element of an array, as in OpenGL. A sampler's index follows the uniforms'. */
    private static final int ELEMENTS = 1024;

    /** Stock's {@code bind()}: nothing to do, the program is used by the batch it was given to. */
    public void bind () {
    }

    public void begin () {
    }

    public void end () {
    }

    /**
     * An index for a uniform to use with the setters that take a location; -1 when the shader does not read it. With {@link #pedantic} a missing name throws, as
     * in stock.
     */
    public int fetchUniformLocation (String name) {
        return fetchUniformLocation(name, pedantic);
    }

    public int fetchUniformLocation (String name, boolean pedantic) {
        final int location = uniformLocation(name);
        if (location == -1 && pedantic) throw new IllegalArgumentException("no uniform with name '" + name + "' in shader");
        return location;
    }

    /** What {@code glGetUniformLocation} answers: the name of a uniform, or of one element of an array ({@code u_palette[2]}); -1 when the shader does not read it. */
    public int uniformLocation (String name) {
        for (int i = 0; i < t.uniforms.size(); i++) { // a member of a struct is named in full, like u_dirLights[1].color
            final Uniform u = t.uniforms.get(i);
            if (u.active && u.name.equals(name) && u.arraySize == 0) return i * ELEMENTS;
        }
        String base = name;
        int element = 0;
        final int br = name.indexOf('[');
        if (br > 0 && name.endsWith("]")) {
            try {
                element = Integer.parseInt(name.substring(br + 1, name.length() - 1).trim());
            } catch (NumberFormatException e) {
                return -1;
            }
            base = name.substring(0, br);
        }
        for (int i = 0; i < t.uniforms.size(); i++) {
            final Uniform u = t.uniforms.get(i);
            if (u.active && u.name.equals(base) && element < Math.max(1, u.arraySize)) return i * ELEMENTS + element;
        }
        for (int i = 0; i < t.samplers.size(); i++) {
            final Sampler sm = t.samplers.get(i);
            if (sm.active && sm.name.equals(base) && element == 0) return (t.uniforms.size() + i) * ELEMENTS;
        }
        return -1;
    }

    /** The element of the array a location names, set by {@link #uniformAt} for the write that follows. */
    private int elementBase;

    /** The uniform a location names (not a sampler); remembers the element for the write that follows. */
    private Uniform uniformAt (int location) {
        final int index = location / ELEMENTS;
        if (index >= t.uniforms.size()) {
            final Sampler sm = t.samplers.get(index - t.uniforms.size());
            throw new GdxRuntimeException("the uniform '" + sm.name + "' is a sampler: set it with setUniformi, to the number of a texture unit");
        }
        elementBase = location % ELEMENTS;
        return t.uniforms.get(index);
    }

    // The by-name and by-location setters of stock's ShaderProgram. A value of the wrong kind throws, naming the uniform.

    public void setUniformi (String name, int value) {
        setUniformi(fetchUniformLocation(name), value);
    }

    public void setUniformi (int location, int value) {
        if (location < 0) return;
        final int index = location / ELEMENTS;
        if (index >= t.uniforms.size()) { // a sampler: the value is its texture unit
            samplerUnit[index - t.uniforms.size()] = value;
            return;
        }
        final Uniform u = uniformAt(location);
        requireKind(u, "int", 1);
        putWord(u, 0, 0, value);
    }

    public void setUniformi (String name, int v1, int v2) {
        setUniformInts(fetchUniformLocation(name), "ivec2", v1, v2);
    }

    public void setUniformi (String name, int v1, int v2, int v3) {
        setUniformInts(fetchUniformLocation(name), "ivec3", v1, v2, v3);
    }

    public void setUniformi (String name, int v1, int v2, int v3, int v4) {
        setUniformInts(fetchUniformLocation(name), "ivec4", v1, v2, v3, v4);
    }

    public void setUniformi (int location, int v1, int v2) {
        setUniformInts(location, "ivec2", v1, v2);
    }

    public void setUniformi (int location, int v1, int v2, int v3) {
        setUniformInts(location, "ivec3", v1, v2, v3);
    }

    public void setUniformi (int location, int v1, int v2, int v3, int v4) {
        setUniformInts(location, "ivec4", v1, v2, v3, v4);
    }

    private void setUniformInts (int location, String type, int... v) {
        if (location < 0) return;
        final Uniform u = uniformAt(location);
        requireKind(u, type, v.length);
        for (int i = 0; i < v.length; i++) putWord(u, 0, i, v[i]);
    }

    public void setUniformf (String name, float value) {
        setUniformf(fetchUniformLocation(name), value);
    }

    public void setUniformf (int location, float value) {
        if (location < 0) return;
        final Uniform u = uniformAt(location);
        requireKind(u, "float", 1);
        putWord(u, 0, 0, Float.floatToRawIntBits(value));
    }

    public void setUniformf (String name, float v1, float v2) {
        setUniformFloats(fetchUniformLocation(name), "vec2", v1, v2);
    }

    public void setUniformf (String name, float v1, float v2, float v3) {
        setUniformFloats(fetchUniformLocation(name), "vec3", v1, v2, v3);
    }

    public void setUniformf (String name, float v1, float v2, float v3, float v4) {
        setUniformFloats(fetchUniformLocation(name), "vec4", v1, v2, v3, v4);
    }

    public void setUniformf (int location, float v1, float v2) {
        setUniformFloats(location, "vec2", v1, v2);
    }

    public void setUniformf (int location, float v1, float v2, float v3) {
        setUniformFloats(location, "vec3", v1, v2, v3);
    }

    public void setUniformf (int location, float v1, float v2, float v3, float v4) {
        setUniformFloats(location, "vec4", v1, v2, v3, v4);
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

    private void setUniformFloats (int location, String type, float... v) {
        if (location < 0) return;
        final Uniform u = uniformAt(location);
        requireKind(u, type, v.length);
        for (int i = 0; i < v.length; i++) putWord(u, 0, i, Float.floatToRawIntBits(v[i]));
    }

    /** Sets an array of floats ({@code float u[n]}), or one {@code float}. */
    public void setUniform1fv (String name, float[] values, int offset, int length) {
        setFloats(fetchUniformLocation(name), 1, length, values, offset);
    }

    /** Sets an array of {@code vec2}: {@code length} counts floats, two per element. */
    public void setUniform2fv (String name, float[] values, int offset, int length) {
        setFloats(fetchUniformLocation(name), 2, length / 2, values, offset);
    }

    public void setUniform3fv (String name, float[] values, int offset, int length) {
        setFloats(fetchUniformLocation(name), 3, length / 3, values, offset);
    }

    public void setUniform4fv (String name, float[] values, int offset, int length) {
        setFloats(fetchUniformLocation(name), 4, length / 4, values, offset);
    }

    public void setUniformMatrix (String name, Matrix4 matrix) {
        setUniformMatrix(name, matrix, false);
    }

    public void setUniformMatrix (String name, Matrix4 matrix, boolean transpose) {
        setMatrices(fetchUniformLocation(name), 4, 1, transpose, matrix.val, 0);
    }

    public void setUniformMatrix (String name, Matrix3 matrix) {
        setUniformMatrix(name, matrix, false);
    }

    public void setUniformMatrix (String name, Matrix3 matrix, boolean transpose) {
        setMatrices(fetchUniformLocation(name), 3, 1, transpose, matrix.val, 0);
    }

    /** Sets a {@code mat4} (16 floats) or an array of them ({@code length} counts floats). The values are column-major, as in {@code Matrix4.val}. */
    public void setUniformMatrix4fv (String name, float[] values, int offset, int length) {
        setMatrices(fetchUniformLocation(name), 4, length / 16, false, values, offset);
    }

    public void setUniformMatrix4fv (String name, float[] values, int offset, int length, boolean transpose) {
        setMatrices(fetchUniformLocation(name), 4, length / 16, transpose, values, offset);
    }

    public void setUniformMatrix3fv (String name, float[] values, int offset, int length) {
        setMatrices(fetchUniformLocation(name), 3, length / 9, false, values, offset);
    }

    public void setUniformMatrix2fv (String name, float[] values, int offset, int length) {
        setMatrices(fetchUniformLocation(name), 2, length / 4, false, values, offset);
    }

    // The calls of OpenGL's glUniform family, by location: what the GL shim forwards. {@code count} elements, from the element the location names.

    private final java.util.HashSet<String> warned = new java.util.HashSet<String>();

    /** {@code glUniform1f..4fv}: {@code count} values of {@code components} floats each, read from {@code v} at {@code offset}. */
    public void setFloats (int location, int components, int count, float[] v, int offset) {
        if (location < 0) return;
        final Uniform u = uniformAt(location);
        final String type = components == 1 ? "float" : "vec" + components;
        if (!u.type.equals(type) && !(u.isBool() && components == 1)) {
            // OpenGL raises INVALID_OPERATION and leaves the uniform as it was (libGDX never looks at the error), so a game that sets a vec3 with glUniform4f runs on OpenGL and its
            // value is simply not set. Same here, with a note once per uniform.
            if (warned.add(u.name)) System.err.println("[gdx-vulkan] the uniform '" + u.name + "' is " + u.type + " and was set with floats in groups of " + components + ": OpenGL ignores that, so does gdx-vulkan");
            return;
        }
        checkCount(u, count);
        for (int e = 0; e < count; e++) {
            for (int c = 0; c < components; c++) putWord(u, e, c, Float.floatToRawIntBits(v[offset + e * components + c]));
        }
    }

    /** {@code glUniform1i..4iv}: integers; a sampler's value is the number of its texture unit. */
    public void setInts (int location, int components, int count, int[] v, int offset) {
        if (location < 0) return;
        final int index = location / ELEMENTS;
        if (index >= t.uniforms.size()) {
            samplerUnit[index - t.uniforms.size()] = v[offset];
            return;
        }
        final Uniform u = uniformAt(location);
        final String type = components == 1 ? "int" : "ivec" + components;
        requireKind(u, type, components);
        checkCount(u, count);
        for (int e = 0; e < count; e++) {
            for (int c = 0; c < components; c++) putWord(u, e, c, v[offset + e * components + c]);
        }
    }

    /** {@code glUniformMatrix2fv..4fv}: {@code count} square matrices of size {@code n}, column-major unless {@code transpose}. */
    public void setMatrices (int location, int n, int count, boolean transpose, float[] v, int offset) {
        if (location < 0) return;
        final Uniform u = uniformAt(location);
        if (!u.type.equals("mat" + n)) throw new GdxRuntimeException("the uniform '" + u.name + "' is " + u.type + ", not mat" + n);
        checkCount(u, count);
        for (int m = 0; m < count; m++) writeMatrix(u, m, v, offset + m * n * n, n, transpose);
    }

    private void checkCount (Uniform u, int count) {
        final int room = Math.max(1, u.arraySize) - elementBase;
        if (count > room) throw new GdxRuntimeException("the uniform '" + u.name + "' has room for " + room + " more element" + (room == 1 ? "" : "s") + " there, not " + count);
    }

    // ------------------------------------------------------------------------------------------------------- what the shader declares (the GL shim)

    /** GL's type enums for the uniform and attribute types. */
    public static int glType (String type) {
        switch (type) {
        case "float": return 0x1406;
        case "int": return 0x1404;
        case "bool": return 0x8B56;
        case "vec2": return 0x8B50;
        case "vec3": return 0x8B51;
        case "vec4": return 0x8B52;
        case "ivec2": return 0x8B53;
        case "ivec3": return 0x8B54;
        case "ivec4": return 0x8B55;
        case "mat2": return 0x8B5A;
        case "mat3": return 0x8B5B;
        case "mat4": return 0x8B5C;
        case "sampler2D": return 0x8B5E;
        default: return 0;
        }
    }

    /** The uniforms the shader reads, and its samplers, in the order {@code glGetActiveUniform} lists them. */
    public int activeUniformCount () {
        int n = 0;
        for (Uniform u : t.uniforms) if (u.active) n++;
        for (Sampler sm : t.samplers) if (sm.active) n++;
        return n;
    }

    private Object activeUniform (int index) {
        for (Uniform u : t.uniforms) {
            if (!u.active) continue;
            if (index-- == 0) return u;
        }
        for (Sampler sm : t.samplers) {
            if (!sm.active) continue;
            if (index-- == 0) return sm;
        }
        throw new IndexOutOfBoundsException("no active uniform " + index);
    }

    /** The name as GL reports it: an array is listed as {@code name[0]}. */
    public String activeUniformName (int index) {
        final Object o = activeUniform(index);
        if (o instanceof Sampler) return ((Sampler)o).name;
        final Uniform u = (Uniform)o;
        return u.arraySize > 0 ? u.name + "[0]" : u.name;
    }

    public int activeUniformType (int index) {
        final Object o = activeUniform(index);
        return o instanceof Sampler ? glType("sampler2D") : glType(((Uniform)o).type);
    }

    public int activeUniformSize (int index) {
        final Object o = activeUniform(index);
        return o instanceof Sampler ? 1 : Math.max(1, ((Uniform)o).arraySize);
    }

    public int activeAttributeCount () {
        return t.attributes.size();
    }

    public String activeAttributeName (int index) {
        return t.attributes.get(index).name;
    }

    public int activeAttributeType (int index) {
        return glType(t.attributes.get(index).type);
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
        final int at = u.offset + (element + elementBase) * u.arrayStride + component * 4;
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
        final int base = u.offset + (element + elementBase) * u.arrayStride;
        final int stride = u.columnStride;
        final int[] words = u.push ? pushWords : ringWords;
        boolean changed = false;
        for (int col = 0; col < n; col++) {
            for (int row = 0; row < n; row++) {
                final int word = Float.floatToRawIntBits(transpose ? src[srcOffset + row * n + col] : src[srcOffset + col * n + row]);
                final int i = (base + col * stride + row * 4) >> 2;
                if (words[i] != word) {
                    words[i] = word;
                    changed = true;
                }
            }
        }
        if (changed && !u.push) ringVersion++;
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

    private int fragTargetLocation = -2;
    private final float[] fragTarget = new float[3];

    /**
     * For a program that reads {@code gl_FragCoord}: the size of the screen and whether the draw goes to it (an offscreen target keeps OpenGL's layout and needs no
     * correction). The translated shader turns Vulkan's top-left origin into OpenGL's bottom-left one with this.
     */
    public void setFragTarget (boolean screen, int width, int height) {
        if (fragTargetLocation == -2) fragTargetLocation = uniformLocation("gdx_fragTarget");
        if (fragTargetLocation < 0) return;
        fragTarget[0] = width;
        fragTarget[1] = height;
        fragTarget[2] = screen ? 1f : 0f;
        setFloats(fragTargetLocation, 3, 1, fragTarget, 0);
    }

    /** Whether this is the dedicated shape shader, which takes a sub-pixel nudge for lines and points in the push word at byte 96 (see {@code shape.vert}). */
    public boolean hasThinLineNudge () {
        return thinLineNudge;
    }

    /** Writes a float into the push constants at a byte offset, for the words of a dedicated shader that no uniform names. */
    public void setPushFloat (int byteOffset, float value) {
        pushWords[byteOffset >> 2] = Float.floatToRawIntBits(value);
    }

    /** How many programs were replaced by a dedicated shader because their source is one of libGDX's own (for tests and diagnostics). */
    public static int knownShaderHits () {
        return KnownShaders.hits;
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
        prepareDraw(s, unitZeroTexture);
        for (int i = 0; i < 32; i++) block32[i] = Float.intBitsToFloat(pushWords[i]);
    }

    /**
     * {@link #prepareDraw(CommandStream, float[], int)} without the conversion: the push constants are left in the program's own word array, which {@code CommandStream.pushConstantWords}
     * sends as it is. Returns that array (do not keep or change it).
     */
    public int[] prepareDrawWords (CommandStream s, int unitZeroTexture) {
        prepareDraw(s, unitZeroTexture);
        return pushWords;
    }

    private void prepareDraw (CommandStream s, int unitZeroTexture) {
        final VulkanGL20 gl = frame.gl();
        final boolean bindless = t.bindless;
        for (int i = 0; i < t.samplers.size(); i++) {
            final Sampler sampler = t.samplers.get(i);
            if (!sampler.active) continue;
            final int unit = samplerUnit[i];
            final int handle = unit == 0 ? unitZeroTexture : gl.boundTexture(unit);
            int nativeId = gl.nativeTexture(handle);
            if (nativeId < 0) nativeId = gl.defaultTexture(); // as OpenGL does: nothing bound, or a texture with no storage, reads (0, 0, 0, 1)
            if (bindless) pushWords[sampler.slotOffset >> 2] = gl.slotOf(nativeId);
            else s.bindTexture(nativeId);
        }
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
