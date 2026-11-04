package io.github.slebed.gdxvulkan;

/**
 * The run-time GLSL compiler (glslang, built into the native library): preprocessing and compiling Vulkan GLSL to SPIR-V. It needs no Vulkan device. The
 * {@code GlslTranslator} is its main user; the methods are public so tools and tests can reach it too.
 */
public final class GlslCompiler {
    private GlslCompiler () {}

    private static final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * How many times the native compiler was asked to preprocess or compile in this process. A test reads it before and after a piece of work to show that the
     * work did not use the run-time compiler: the library's own shaders are compiled at build time, and a cached program needs no compiler at all.
     */
    public static int callCount () {
        return calls.get();
    }

    private static final java.util.concurrent.atomic.AtomicBoolean warmingUp = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Starts the compiler up on a background thread, once per process: glslang builds its built-in tables on first use, which takes about 0.15 s on a PC and 0.6 to 1.1 s
     * on the phones measured, and the first shader a game translates would pay it on the render thread. {@code GdxVkFrame} calls this when it is created, so by the time a
     * level loads its shaders the cost is gone. It compiles two tiny shaders through the native entry points, not through {@link #compile}, so {@link #callCount} (which
     * tests use to show the library's own shaders never need the compiler) does not see it. Turn it off with {@code -Dgdxvulkan.shaderWarmUp=false}.
     */
    public static void warmUp () {
        if (!Boolean.parseBoolean(System.getProperty("gdxvulkan.shaderWarmUp", "true")) || !warmingUp.compareAndSet(false, true)) return;
        final Thread thread = new Thread(new Runnable() {
            @Override public void run () {
                try {
                    GdxVk.loadNatives();
                    if (!GdxVkNative.nativeGlslAvailable()) return;
                    GdxVkNative.nativeGlslCompile(Stage.VERTEX.code, "warm-up.vert", "#version 450\nlayout(location = 0) in vec4 p;\nvoid main() { gl_Position = p; }\n");
                    GdxVkNative.nativeGlslCompile(Stage.FRAGMENT.code, "warm-up.frag", "#version 450\nlayout(location = 0) out vec4 c;\nvoid main() { c = vec4(1.0); }\n");
                } catch (Throwable ignored) { // the compiler is optional here: a program that needs it reports its own error
                }
            }
        }, "gdxvk-glslang-warm-up");
        thread.setDaemon(true);
        thread.start();
    }

    /** The pipeline stage of a source. */
    public enum Stage {
        VERTEX(0), FRAGMENT(1), COMPUTE(2);

        final int code;

        Stage (int code) {
            this.code = code;
        }
    }

    /** The outcome of a compile: {@code spirv} is null when it failed, and {@code log} has the compiler's messages either way. */
    public static final class Result {
        public final int[] spirv;
        public final String log;

        Result (int[] spirv, String log) {
            this.spirv = spirv;
            this.log = log == null ? "" : log;
        }

        public boolean ok () {
            return spirv != null;
        }
    }

    /** The outcome of preprocessing: {@code text} is null when it failed. */
    public static final class Preprocessed {
        public final String text;
        public final String log;

        Preprocessed (String text, String log) {
            this.text = text;
            this.log = log == null ? "" : log;
        }

        public boolean ok () {
            return text != null;
        }
    }

    /** Whether this build of the native library has the compiler. */
    public static boolean isAvailable () {
        GdxVk.loadNatives();
        return GdxVkNative.nativeGlslAvailable();
    }

    /**
     * Resolves {@code #ifdef}, {@code #define} and macros; the output keeps {@code #line} directives, so later messages refer to the lines of {@code source}.
     * @param name shown in messages in front of the line number
     */
    public static Preprocessed preprocess (Stage stage, String name, String source) {
        GdxVk.loadNatives();
        final String[] r = GdxVkNative.nativeGlslPreprocess(stage.code, name, source);
        return new Preprocessed(r[0], r[1]);
    }

    /** Compiles Vulkan GLSL ({@code #version 450}) to SPIR-V. */
    public static Result compile (Stage stage, String name, String source) {
        GdxVk.loadNatives();
        final Object[] r = GdxVkNative.nativeGlslCompile(stage.code, name, source);
        return new Result((int[])r[0], (String)r[1]);
    }
}
