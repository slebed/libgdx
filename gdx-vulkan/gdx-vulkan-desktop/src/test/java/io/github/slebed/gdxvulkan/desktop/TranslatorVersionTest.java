package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

import org.junit.jupiter.api.Test;

/**
 * The shader cache and the shader bundle are named by a hash that includes {@code ShaderCache.VERSIONS}; a change to the translator that is not followed by a new version
 * leaves stale translations on disk (a real afternoon of looking at old output). This fails when {@code GlslTranslator.java} changes: bump {@code VERSIONS} in
 * {@code ShaderCache}, run {@code gradlew :gdx-vulkan-desktop:recordShaderBundle}, and put the new hash in {@code src/test/resources/translator-source.sha256}.
 */
class TranslatorVersionTest {
    @Test void theTranslatorSourceMatchesTheVersionItWasCachedUnder () throws IOException, java.security.NoSuchAlgorithmException {
        Path source = Paths.get("../gdx-vulkan-core/src/main/java/io/github/slebed/gdxvulkan/glsl/GlslTranslator.java");
        if (!Files.exists(source)) source = Paths.get("gdx-vulkan-core/src/main/java/io/github/slebed/gdxvulkan/glsl/GlslTranslator.java");
        final String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\r\n", "\n");
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        final StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b));
        final String expected = new String(Files.readAllBytes(Paths.get("src/test/resources/translator-source.sha256")), StandardCharsets.UTF_8).trim();
        assertEquals(expected, hex.toString(), "GlslTranslator.java changed: bump VERSIONS in ShaderCache, run recordShaderBundle, and update src/test/resources/translator-source.sha256 to this value");
    }
}
