package io.github.slebed.gdxvulkan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Loads the SPIR-V that the {@code compileShaders} Gradle task puts on the classpath under {@code /shaders/}. */
public final class GdxVkShaders {
    private GdxVkShaders() {}

    /** The Tier B build of a shader. @param name file name without the .spv suffix, e.g. {@code "triangle.vert"} */
    public static byte[] load(String name) {
        return load(name, false);
    }

    /** @param tierA the build compiled with GDX_TIER_A (bindless texture table) instead of the single-sampler Tier B build */
    public static byte[] load(String name, boolean tierA) {
        String res = "/shaders/" + name + (tierA ? ".a" : "") + ".spv";
        try (InputStream in = GdxVkShaders.class.getResourceAsStream(res)) {
            if (in == null) throw new GdxVkException("Shader resource not found: " + res);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0;) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            throw new GdxVkException("Could not read shader " + res + ": " + e.getMessage(), e);
        }
    }
}
