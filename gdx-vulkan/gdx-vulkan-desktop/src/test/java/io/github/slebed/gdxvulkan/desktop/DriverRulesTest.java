package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Rules the shaders keep because a driver needs them (docs/DRIVER_NOTES.md). A driver's compiler cannot be run here, so the rule is checked in the source: a
 * test that only a phone could fail would be found by the next phone run, weeks later.
 *
 * <p>Adreno 650: a vertex shader whose reads of the data buffer ({@code gdx_data[...]}) have an address built from a push constant is refused when several of
 * the reads feed arithmetic with per-vertex data ({@code VK_ERROR_INITIALIZATION_FAILED}, "GPUOBJ_ALLOC Invalid argument"). The model shaders find their record
 * from {@code gl_InstanceIndex} instead, which a draw's first instance sets.
 */
class DriverRulesTest {
    private static final File SHADERS = new File("../gdx-vulkan-core/src/main/shaders");

    @Test void aBufferReadsAddressInTheModelShadersNeverCarriesAPushConstant () throws IOException {
        assumeTrue(SHADERS.isDirectory(), "the shader sources are not next to the test: " + SHADERS.getAbsolutePath());
        final File[] files = SHADERS.listFiles((dir, name) -> name.startsWith("model_") && (name.endsWith(".glsl") || name.endsWith(".vert") || name.endsWith(".frag")));
        assertTrue(files != null && files.length > 10, "the model shaders were found");
        final Pattern read = Pattern.compile("gdx_data\\s*\\[([^\\]]*)\\]");
        final List<String> offenders = new ArrayList<String>();
        for (File f : files) {
            final String source = stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            final Matcher m = read.matcher(source);
            while (m.find()) {
                if (m.group(1).contains("gdx_pc")) offenders.add(f.getName() + ": gdx_data[" + m.group(1) + "]");
            }
            // The record offset used to be a push constant (u_indices.x) that the shader added to its reads through a local variable, so the reads themselves showed no push constant.
            if (source.contains("u_indices.x")) offenders.add(f.getName() + ": uses u_indices.x (the pushed record offset)");
        }
        assertTrue(offenders.isEmpty(), "buffer reads with a push constant in the address (the Adreno 650's compiler refuses them; use gl_InstanceIndex, see docs/DRIVER_NOTES.md): " + offenders);
    }

    private static String stripComments (String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }
}
