package io.github.slebed.gdxvulkan.glsl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import io.github.slebed.gdxvulkan.GdxVkShaders;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.Attribute;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.Sampler;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.Translation;
import io.github.slebed.gdxvulkan.glsl.GlslTranslator.Uniform;

/**
 * The shaders libGDX's own classes create ({@code SpriteBatch}, {@code PolygonSpriteBatch} and {@code ShapeRenderer}) are recognised by the hash of their source and
 * replaced by the library's dedicated Vulkan shaders, which are compiled when the library is built: the base classes never go through the run-time translator
 * (the translator is for shaders a game writes itself). The tables a translation would produce are written by hand here, for the layout the dedicated shaders have.
 */
final class KnownShaders {
    private KnownShaders () {}

    /** The dedicated replacement: SPIR-V of both stages and the tables. */
    static final class Match {
        final String name;
        final Translation table;
        final byte[] vertex, fragment;

        Match (String name, Translation table, byte[] vertex, byte[] fragment) {
            this.name = name;
            this.table = table;
            this.vertex = vertex;
            this.fragment = fragment;
        }
    }

    /** Hash of the whitespace-normalised vertex source and fragment source of {@code SpriteBatch.createDefaultShader()} (and the polygon batch's, which is the same text). */
    private static final String SPRITE = "f2c880fddad2a1b8ab70600550bae600bf976fd949460fbc716c2fad16bfd162/258c324e5a362566f25ae8b16c764a01bc9bab9608afeae35059f342bdde4422";
    /** The shaders of {@code ImmediateModeRenderer20} as {@code ShapeRenderer} makes them: position and colour, no normals, no texture coordinates. */
    private static final String SHAPE = "5e5eb81dec6bff0b5bb0fafaae53a65a4f82c0e239ba476aaf92328d450ec624/7bbe4fbe50b0c41bfd2a2b74f6c8e160ea82415b92a289db3b048a6a77a28e38";

    /** How many programs were replaced by a dedicated shader (for tests and diagnostics). */
    static volatile int hits;

    /** @param vertex the vertex source as {@code glShaderSource} gets it, prepend text included */
    static Match find (String vertex, String fragment, boolean bindless) {
        final String key = hash(vertex) + "/" + hash(fragment);
        if (key.equals(SPRITE)) return sprite(bindless);
        if (key.equals(SHAPE)) return shape(bindless);
        return null;
    }

    /** SHA-256 of the source with every run of white space reduced to one space, so line endings and indentation do not matter. */
    static String hash (String source) {
        try {
            final MessageDigest d = MessageDigest.getInstance("SHA-256");
            final byte[] b = d.digest(source.replaceAll("\\s+", " ").trim().getBytes(StandardCharsets.UTF_8));
            final StringBuilder s = new StringBuilder(64);
            for (byte x : b) s.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Uniform matrix (String name) {
        final Uniform u = new Uniform(name, "mat4", 0);
        u.active = true;
        u.push = true;
        u.offset = 0;
        u.size = 64;
        u.columnStride = 16;
        return u;
    }

    /** sprite_single: the push block of gdx_vk_common.glsl, with the transform at byte 0 and the texture slot in u_indices.y (byte 84). */
    private static Match sprite (boolean bindless) {
        final Translation t = new Translation();
        t.bindless = bindless;
        t.attributes.add(new Attribute("a_position", "vec4", 0));
        t.attributes.add(new Attribute("a_color", "vec4", 1));
        t.attributes.add(new Attribute("a_texCoord0", "vec2", 2));
        t.uniforms.add(matrix("u_projTrans"));
        final Sampler s = new Sampler("u_texture");
        s.active = true;
        s.slotOffset = bindless ? 84 : -1;
        t.samplers.add(s);
        t.pushBytes = bindless ? 88 : 64;
        return new Match("sprite_single", t, GdxVkShaders.load("sprite_single.vert", bindless), GdxVkShaders.load("sprite_single.frag", bindless));
    }

    /** shape: the transform at byte 0; u_custom0.x (byte 96) carries the thin-line nudge, which the GL shim sets for lines and points. */
    private static Match shape (boolean bindless) {
        final Translation t = new Translation();
        t.bindless = bindless;
        t.attributes.add(new Attribute("a_position", "vec4", 0));
        t.attributes.add(new Attribute("a_color", "vec4", 1));
        t.uniforms.add(matrix("u_projModelView"));
        t.pushBytes = 112;
        return new Match("shape", t, GdxVkShaders.load("shape.vert", bindless), GdxVkShaders.load("shape.frag", bindless));
    }
}
