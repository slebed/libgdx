package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

import io.github.slebed.gdxvulkan.GlslCompiler;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.GlslTranslationException;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.Translation;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.Uniform;

/**
 * The stock GLSL translator (docs/SHADER_TRANSLATOR.md) on the shaders libGDX ships and on a custom one: the translation compiles with glslang on both code
 * paths, the uniforms land where the design says, and errors keep the line numbers of the original source. No GPU needed.
 */
class GlslTranslatorTest {
    private static String resource (String name) throws IOException {
        try (InputStream in = GlslTranslatorTest.class.getResourceAsStream("/stockshaders/" + name)) {
            assertNotNull(in, name);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    /** Both shaders of a translation must compile; the message carries the translated source when one does not, to find the cause. */
    private static void assertCompiles (Translation t, String label) {
        final GlslCompiler.Result v = GlslCompiler.compile(GlslCompiler.Stage.VERTEX, label + ".vert", t.vertex);
        assertTrue(v.ok(), label + " vertex:\n" + v.log + "\n--- translated source ---\n" + t.vertex);
        final GlslCompiler.Result f = GlslCompiler.compile(GlslCompiler.Stage.FRAGMENT, label + ".frag", t.fragment);
        assertTrue(f.ok(), label + " fragment:\n" + f.log + "\n--- translated source ---\n" + t.fragment);
    }

    @Test void theSpriteBatchShaderLivesInPushConstants () throws Exception {
        for (boolean bindless : new boolean[] {true, false}) {
            final Translation t = GlslTranslator.translate("spritebatch", resource("spritebatch.vert"), resource("spritebatch.frag"), null, null, bindless);
            assertCompiles(t, "spritebatch/" + (bindless ? "modern" : "compat"));
            final Uniform proj = t.uniform("u_projTrans");
            assertTrue(proj.active && proj.push, "u_projTrans is read and in the push constants");
            assertEquals(0, proj.offset);
            assertEquals(64, proj.size);
            assertEquals(0, t.blockBytes, "nothing needs the uniform ring");
            assertEquals(bindless ? 68 : 64, t.pushBytes);
            assertEquals(bindless ? 64 : -1, t.sampler("u_texture").slotOffset, "the texture slot follows the matrix on the modern path");
            assertEquals(0, t.attribute("a_position").location);
            assertEquals(1, t.attribute("a_color").location);
            assertEquals(2, t.attribute("a_texCoord0").location);
            assertTrue(t.vertex.contains("gl_Position = gdx_vk_clip("), "the clip fix is applied");
            assertFalse(t.fragment.contains("texture2D"), "texture2D is renamed");
        }
    }

    @Test void theShapeRendererShadersTranslate () throws Exception {
        final Translation t = GlslTranslator.translate("shape", resource("shaperenderer.vert"), resource("shaperenderer.frag"), null, null, true);
        assertCompiles(t, "shaperenderer");
        assertEquals(0, t.samplers.size());
        assertEquals(64, t.pushBytes);
        final Translation tex = GlslTranslator.translate("immediate", resource("immediate_textured.vert"), resource("immediate_textured.frag"), null, null, true);
        assertCompiles(tex, "immediate_textured");
        assertEquals(64, tex.sampler("u_sampler0").slotOffset);
    }

    @Test void aFragmentThatWritesNoColourOutputsZeroAndFragCoordIsTheOpenGlOne () throws Exception {
        final String vert = "attribute vec3 a_position;\nuniform mat4 u_proj;\nvoid main() {\n    gl_Position = u_proj * vec4(a_position, 1.0);\n}\n";
        final String frag = "uniform vec2 u_resolution;\nvoid main() {\n    vec2 uv = gl_FragCoord.xy / u_resolution;\n    if (uv.x > 0.5) gl_FragColor = vec4(1.0);\n}\n";
        final Translation t = GlslTranslator.translate("nocolour", vert, frag, null, null, true);
        assertCompiles(t, "nocolour");
        assertTrue(t.fragment.contains("gdx_FragColor = vec4(0.0);"), "the output starts at zero, as it does in practice on OpenGL:\n" + t.fragment);
        assertTrue(t.fragment.contains("gdx_FragCoordGL(gdx_fragTarget)"), "gl_FragCoord goes through the OpenGL-origin function:\n" + t.fragment);
        assertFalse(t.fragment.matches("(?s).*[^_]gl_FragCoord\\.xy.*"), "no direct read is left");
    }

    @Test void aMatrixVaryingTakesAsManyLocationsAsItHasColumns () throws Exception {
        // out mat3 is three locations: the next varying must not land on the second (a real terrain shader of a game did, and glslang refused it).
        final String vert = "#version 330 core\nin vec3 a_position;\nuniform mat4 u_proj;\nout mat3 v_tbn;\nout vec4 v_pos;\nout vec2 v_uv;\n"
            + "void main() {\n    v_tbn = mat3(1.0);\n    v_pos = vec4(a_position, 1.0);\n    v_uv = a_position.xy;\n    gl_Position = u_proj * v_pos;\n}\n";
        final String frag = "#version 330 core\nin mat3 v_tbn;\nin vec4 v_pos;\nin vec2 v_uv;\nout vec4 fragColor;\n"
            + "void main() {\n    fragColor = vec4(v_tbn * v_pos.xyz, v_uv.x);\n}\n";
        final Translation t = GlslTranslator.translate("matrixvarying", vert, frag, null, null, true);
        assertTrue(t.vertex.contains("layout(location = 0) out mat3 v_tbn") && t.vertex.contains("layout(location = 3) out vec4 v_pos")
            && t.vertex.contains("layout(location = 4) out vec2 v_uv"), t.vertex);
        assertCompiles(t, "matrixvarying/modern");
    }

    @Test void aCustomShaderWithArraysBoolsAndTwoSamplers () throws Exception {
        final String masked = "#define MASKED\n";
        final Translation t = GlslTranslator.translate("custom", resource("custom.vert"), resource("custom.frag"), null, masked, true);
        assertCompiles(t, "custom/modern");

        final Uniform palette = t.uniform("u_palette");
        assertTrue(palette.active && !palette.push, "an array goes to the uniform ring");
        assertEquals(0, palette.offset);
        assertEquals(16, palette.arrayStride, "std140 array stride for a vec4");
        assertEquals(64, t.blockBytes);

        assertEquals(0, t.uniform("u_worldView").offset);
        assertEquals(64, t.uniform("u_time").offset, "u_time is declared in both shaders and kept once");
        assertEquals(68, t.uniform("u_paletteIndex").offset);
        final Uniform flip = t.uniform("u_flip");
        assertTrue(flip.isBool());
        assertEquals(72, flip.offset);
        assertTrue(t.vertex.contains("#define u_flip (gdx_b_u_flip != 0)"), "a bool is an int in the block, read through a macro");
        assertEquals(76, t.sampler("u_diffuse").slotOffset);
        assertEquals(80, t.sampler("u_mask").slotOffset);
        assertEquals(84, t.pushBytes);

        // Two textures in one draw cannot be bound on the compatibility path: said clearly, before any compile.
        final GlslTranslationException e = assertThrows(GlslTranslationException.class,
            () -> GlslTranslator.translate("custom", resource("custom.vert"), resource("custom.frag"), null, masked, false));
        assertTrue(e.getMessage().contains("2 textures") && e.getMessage().contains("one texture per draw"), e.getMessage());

        // Without the flag u_mask is never read: one sampler, which the compatibility path takes.
        final Translation single = GlslTranslator.translate("custom", resource("custom.vert"), resource("custom.frag"), null, null, false);
        assertCompiles(single, "custom/compat");
        assertFalse(single.sampler("u_mask").active);
    }

    @Test void theModelShadersTranslateWithAndWithoutLights () throws Exception {
        final String flags = "#define normalFlag\n#define diffuseTextureFlag\n#define diffuseColorFlag\n";
        final Translation t = GlslTranslator.translate("default", resource("default.vertex.glsl"), resource("default.fragment.glsl"), flags, flags, true);
        assertCompiles(t, "default/unlit");

        final Translation depth = GlslTranslator.translate("depth", resource("depth.vertex.glsl"), resource("depth.fragment.glsl"), null, null, true);
        assertCompiles(depth, "depth");

        // DefaultShader's lights are uniform arrays of structs: the block holds the structs (std140), each member is a uniform of its own named like GL names it.
        final String lit = flags + "#define lightingFlag\n#define numDirectionalLights 2\n#define numPointLights 1\n#define numSpotLights 0\n";
        for (boolean bindless : new boolean[] {true, false}) {
            final Translation l = GlslTranslator.translate("default", resource("default.vertex.glsl"), resource("default.fragment.glsl"), lit, lit, bindless);
            assertCompiles(l, "default/lit/" + (bindless ? "modern" : "compat"));
            final Uniform c0 = l.uniform("u_dirLights[0].color"), d0 = l.uniform("u_dirLights[0].direction");
            final Uniform c1 = l.uniform("u_dirLights[1].color"), p0 = l.uniform("u_pointLights[0].position");
            assertNotNull(c0);
            assertNotNull(p0);
            assertTrue(c0.active && !c0.push);
            assertEquals(c0.offset + 16, d0.offset, "members follow std140: a vec3 takes a 16-byte slot");
            assertEquals(c0.offset + 32, c1.offset, "the array stride of this struct is 32 bytes");
            assertEquals(0, p0.offset % 16 == 0 ? 0 : 1, "the point light starts on 16 bytes");
            assertTrue(l.vertex.contains("struct DirectionalLight") && l.fragment.contains("struct DirectionalLight"), "the struct is declared once in each stage's prelude");
            assertEquals(1, l.fragment.split("struct DirectionalLight", -1).length - 1, "and only once");
        }
    }

    @Test void errorsKeepTheLineNumbersOfTheOriginalSource () {
        final String vertex = "attribute vec4 a_position;\nuniform mat4 u_projTrans;\nvoid main() {\n    gl_Position = u_projTrans * a_position;\n}\n";
        final String fragment = "uniform sampler2D u_texture;\nvarying vec2 v_uv;\nvoid main() {\n    gl_FragColor = texture2D(u_texture, v_uv) * oops;\n}\n";
        // v_uv is not in the vertex shader: OpenGL links that and reads zero, so the translation does too (with a note on stderr); the error in the code stays an error.
        final Translation lenient = GlslTranslator.translate("bad", vertex, fragment, null, null, true);
        assertTrue(lenient.fragment.contains("vec2 v_uv"), "v_uv is a zero global");

        // A genuine error in the code: the compiler's message names line 4 of the fragment source, after the translation moved and rewrote declarations.
        final String vertex2 = "attribute vec4 a_position;\nvarying vec2 v_uv;\nvoid main() {\n    v_uv = a_position.xy;\n    gl_Position = a_position;\n}\n";
        final Translation t = GlslTranslator.translate("bad", vertex2, fragment, null, null, true);
        final GlslCompiler.Result r = GlslCompiler.compile(GlslCompiler.Stage.FRAGMENT, "bad.frag", t.fragment);
        assertFalse(r.ok());
        assertTrue(r.log.contains("bad.frag:4") && r.log.contains("oops"), r.log);

        // The prepend text counts as lines, as it does in stock's ShaderProgram log.
        final Translation p = GlslTranslator.translate("bad", vertex2, fragment, null, "#define A\n#define B\n", true);
        final GlslCompiler.Result r2 = GlslCompiler.compile(GlslCompiler.Stage.FRAGMENT, "bad.frag", p.fragment);
        assertTrue(r2.log.contains("bad.frag:6"), r2.log);
    }

    @Test void unsupportedConstructsAreRefusedWithTheirLine () {
        final String vertex = "attribute vec4 a_position;\nvoid main() { gl_Position = a_position; }\n";
        final String cube = "uniform samplerCube u_env;\nvoid main() {\n    gl_FragColor = textureCube(u_env, vec3(0.0));\n}\n";
        final GlslTranslationException e = assertThrows(GlslTranslationException.class, () -> GlslTranslator.translate("cube", vertex, cube, null, null, true));
        assertTrue(e.getMessage().contains("cube.frag:1") && e.getMessage().contains("samplerCube"), e.getMessage());
    }
}
