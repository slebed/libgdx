package io.github.slebed.gdxvulkan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Loads the SPIR-V that the {@code compileShaders} Gradle task puts on the classpath under {@code /shaders/}. */
public final class GdxVkShaders {
    private GdxVkShaders() {}

    /** @param name file name without the .spv suffix, e.g. {@code "triangle.vert"} */
    public static byte[] load(String name) {
        String res = "/shaders/" + name + ".spv";
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
