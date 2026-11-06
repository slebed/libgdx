package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;

import io.github.slebed.gdxvulkan.GlslCompiler;

/**
 * A library built without the run-time compiler ({@code -Pgdxvulkan.noCompiler=true}, 0.55 MB instead of 2.5 MB) says so clearly when something needs it. Skipped on a normal build.
 * The rest of the suite ({@code SpriteBatchTest}, {@code BundledShadersTest}, {@code StockClassesTest}, ...) shows that everything that ships as SPIR-V still works without it.
 */
class NoCompilerTest {
    @Test void aBuildWithoutTheCompilerReportsIt () {
        final GlslCompiler.Result r = GlslCompiler.compile(GlslCompiler.Stage.FRAGMENT, "probe.frag",
            "#version 450\nlayout(location = 0) out vec4 c;\nvoid main() { c = vec4(1.0); }\n");
        assumeFalse(r.ok(), "this build has the run-time compiler");
        assertFalse(r.ok());
        assertTrue(r.log.contains("no run-time GLSL compiler"), r.log);
    }
}
