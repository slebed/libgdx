package io.github.slebed.gdxvulkan;

/**
 * The run-time GLSL compiler (glslang, built into the native library): preprocessing and compiling Vulkan GLSL to SPIR-V. It needs no Vulkan device. The
 * {@code GlslTranslator} is its main user; the methods are public so tools and tests can reach it too.
 */
public final class GlslCompiler {
    private GlslCompiler () {}

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
