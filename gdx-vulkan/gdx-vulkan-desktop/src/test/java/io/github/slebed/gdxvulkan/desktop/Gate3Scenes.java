package io.github.slebed.gdxvulkan.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.List;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import io.github.slebed.gdxvulkan.VulkanShaderPipelineBundle;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;

/**
 * The Gate 3 scenes (plan, Phase 3), written once against stock {@link Batch}, {@code ShapeRenderer} and {@link FrameBuffer}, so the same code
 * drives stock libGDX on OpenGL (the reference) and gdx-vulkan. The only backend-specific piece is {@link Fx}: the post-processing shaders
 * are GLSL programs on one side and precompiled SPIR-V bundles on the other, written to compute the same thing.
 *
 * <ul>
 * <li>{@code gate3_2d3d}: a 2D scene and a 3D scene (filled boxes, perspective, depth test) each rendered into a stock {@code FrameBuffer},
 * then drawn side by side with {@code region.flip(false, true)}.</li>
 * <li>{@code gate3_postfx}: a clipped Scene2D UI rendered into a {@code FrameBuffer}, then four passes through ping-pong
 * {@code FrameBuffer}s (bright pass, horizontal blur, vertical blur, CRT composite with additive bloom).</li>
 * </ul>
 */
final class Gate3Scenes {
    static final int SIZE = 256;
    static final float[] BACKGROUND = {0.04f, 0.04f, 0.07f, 1f};

    enum Effect { BRIGHT, BLUR, CRT }

    /** Post-processing shaders on the current backend. Used between {@code batch.begin()} and {@code end()}; {@link #none} restores the default. */
    interface Fx {
        void use(Batch batch, Effect effect, float p0, float p1, float p2, float p3);

        void none(Batch batch);

        void dispose();
    }

    interface Env {
        Batch batch();

        Fx fx();

        /** A fresh shape renderer; the caller disposes it. */
        ShapeApi shapes();
    }

    interface Scene {
        String name();

        void prepare(FileHandle data, int width, int height) throws Exception;

        void draw(Env env, int width, int height) throws Exception;

        void dispose();
    }

    private Gate3Scenes() {}

    static Scene[] all() {
        return new Scene[] {new TwoDThreeD(), new PostFx()};
    }

    private static TextureRegion flipped(FrameBuffer fb) {
        TextureRegion r = new TextureRegion(fb.getColorBufferTexture());
        r.flip(false, true);
        return r;
    }

    private static void clear(float r, float g, float b, int mask) {
        Gdx.gl.glClearColor(r, g, b, 1f);
        Gdx.gl.glClearDepthf(1f);
        Gdx.gl.glClear(mask);
    }

    // ---------------------------------------------------------------------------------------------------------------- 2D + 3D

    private static final class TwoDThreeD implements Scene {
        Texture checker;
        BitmapFont font;
        FrameBuffer fb2d, fb3d;

        public String name() { return "gate3_2d3d"; }

        public void prepare(FileHandle data, int w, int h) {
            Pixmap pm = new Pixmap(32, 32, Pixmap.Format.RGBA8888);
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) pm.drawPixel(x, y, (((x / 8) + (y / 8)) & 1) == 0 ? 0xE8E8F0FF : 0x3050A0FF);
            }
            checker = new Texture(pm);
            checker.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
            pm.dispose();
            font = new BitmapFont();
            fb2d = new FrameBuffer(Pixmap.Format.RGBA8888, w / 2, h, false);
            fb3d = new FrameBuffer(Pixmap.Format.RGBA8888, w / 2, h, true);
        }

        public void draw(Env env, int w, int h) {
            Batch b = env.batch();
            final int fw = w / 2;

            // the 2D half: opaque, translucent and rotated sprites, and text
            fb2d.begin();
            clear(0.1f, 0.12f, 0.3f, GL20.GL_COLOR_BUFFER_BIT);
            b.getProjectionMatrix().setToOrtho2D(0, 0, fw, h);
            b.begin();
            b.draw(checker, 8, 8, 112, 112);
            b.setColor(1f, 0.4f, 0.2f, 0.7f);
            b.draw(checker, 40, 60, 80, 80);
            b.setColor(1f, 1f, 1f, 1f);
            b.draw(new TextureRegion(checker), 64, 190, 20, 20, 40, 40, 1.2f, 1.2f, 30f);
            font.draw(b, "FBO 2D", 12, h - 10);
            b.end();
            fb2d.end();

            // the 3D half: filled boxes under a perspective camera with the depth test on, two of them interpenetrating
            fb3d.begin();
            clear(0.12f, 0.1f, 0.14f, GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            PerspectiveCamera cam = new PerspectiveCamera(50, fw, h);
            cam.position.set(0f, 2.5f, 7f);
            cam.lookAt(0f, 0f, 0f);
            cam.near = 1f;
            cam.far = 20f;
            cam.update();
            ShapeApi s = env.shapes();
            try {
                s.setProjectionMatrix(cam.combined);
                s.begin(ShapeApi.Kind.Filled);
                s.setColor(0.25f, 0.5f, 0.3f, 1f);
                s.box(-3f, -1.3f, 3f, 6f, 0.2f, 6f); // floor slab
                s.setColor(0.9f, 0.25f, 0.2f, 1f);
                s.identity();
                s.rotate(0, 1, 0, 35f);
                s.box(-1f, -1f, 1f, 2f, 2f, 2f);
                s.setColor(0.2f, 0.45f, 0.95f, 1f);
                s.identity();
                s.translate(0.7f, 0.4f, 0.3f);
                s.rotate(0, 1, 0, -20f);
                s.rotate(1, 0, 0, 25f);
                s.box(-1f, -1f, 1f, 2f, 2f, 2f);
                s.end();
            } finally {
                s.dispose();
            }
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            fb3d.end();

            // both halves onto the screen, upright
            b.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
            b.begin();
            b.setColor(1f, 1f, 1f, 1f);
            b.draw(flipped(fb2d), 0, 0, fw, h);
            b.draw(flipped(fb3d), fw, 0, fw, h);
            b.end();
        }

        public void dispose() {
            if (checker != null) checker.dispose();
            if (font != null) font.dispose();
            if (fb2d != null) fb2d.dispose();
            if (fb3d != null) fb3d.dispose();
        }
    }

    // ------------------------------------------------------------------------------------------------------------ UI + post-processing

    private static final class PostFx implements Scene {
        Skin skin;
        FrameBuffer scene, ping, pong;

        public String name() { return "gate3_postfx"; }

        public void prepare(FileHandle data, int w, int h) {
            skin = new Skin(data.child("uiskin.json"));
            scene = new FrameBuffer(Pixmap.Format.RGBA8888, w, h, false);
            ping = new FrameBuffer(Pixmap.Format.RGBA8888, w, h, false);
            pong = new FrameBuffer(Pixmap.Format.RGBA8888, w, h, false);
        }

        /** Draws a framebuffer's texture over the whole current target, keeping GL's row order (flipped once in, flipped once back). */
        private static void blit(Batch b, FrameBuffer from, int w, int h) {
            b.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
            b.begin();
            b.setColor(1f, 1f, 1f, 1f);
            b.draw(flipped(from), 0, 0, w, h);
            b.end();
        }

        public void draw(Env env, int w, int h) {
            Batch b = env.batch();
            Fx fx = env.fx();

            // pass 0: the UI, with its clipped scroll pane, into a framebuffer
            Stage stage = new Stage(new ScreenViewport(), b);
            stage.getViewport().update(w, h, true);
            Table root = new Table(skin);
            root.setFillParent(true);
            root.pad(8);
            root.add(new Label("Post FX", skin)).colspan(2).padBottom(6).row();
            root.add(new TextButton("Button", skin)).pad(2);
            root.add(new CheckBox(" check", skin)).pad(2).row();
            Slider slider = new Slider(0, 10, 1, false, skin);
            slider.setValue(7);
            root.add(slider).colspan(2).fillX().pad(2).row();
            List<String> list = new List<String>(skin);
            Array<String> items = new Array<String>();
            for (int i = 1; i <= 30; i++) items.add("Item number " + i); // taller than the pane: it clips
            list.setItems(items);
            ScrollPane pane = new ScrollPane(list, skin);
            pane.setFadeScrollBars(false);
            root.add(pane).colspan(2).size(180, 110).pad(4).row();
            stage.addActor(root);
            stage.act(0);
            scene.begin();
            clear(0.05f, 0.06f, 0.14f, GL20.GL_COLOR_BUFFER_BIT);
            stage.draw();
            scene.end();
            stage.dispose();

            // pass 1: bright pass scene -> ping
            ping.begin();
            clear(0f, 0f, 0f, GL20.GL_COLOR_BUFFER_BIT);
            b.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
            b.begin();
            fx.use(b, Effect.BRIGHT, 0.35f, 2.2f, 0f, 0f);
            b.setColor(1f, 1f, 1f, 1f);
            b.draw(flipped(scene), 0, 0, w, h);
            fx.none(b);
            b.end();
            ping.end();

            // pass 2: horizontal blur ping -> pong; pass 3: vertical blur pong -> ping
            pong.begin();
            clear(0f, 0f, 0f, GL20.GL_COLOR_BUFFER_BIT);
            b.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
            b.begin();
            fx.use(b, Effect.BLUR, 1f / w, 0f, 0f, 0f);
            b.setColor(1f, 1f, 1f, 1f);
            b.draw(flipped(ping), 0, 0, w, h);
            fx.none(b);
            b.end();
            pong.end();

            ping.begin();
            clear(0f, 0f, 0f, GL20.GL_COLOR_BUFFER_BIT);
            b.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
            b.begin();
            fx.use(b, Effect.BLUR, 0f, 1f / h, 0f, 0f);
            b.setColor(1f, 1f, 1f, 1f);
            b.draw(flipped(pong), 0, 0, w, h);
            fx.none(b);
            b.end();
            ping.end();

            // pass 4: CRT look on the scene, bloom (the blurred bright parts) added on top, onto the screen
            b.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
            b.begin();
            fx.use(b, Effect.CRT, h / 2f, 1.1f, 0f, 0f);
            b.setColor(1f, 1f, 1f, 1f);
            b.draw(flipped(scene), 0, 0, w, h);
            fx.none(b);
            b.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE);
            b.setColor(0.9f, 0.9f, 0.9f, 1f);
            b.draw(flipped(ping), 0, 0, w, h);
            b.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            b.end();
        }

        public void dispose() {
            if (skin != null) skin.dispose();
            if (scene != null) scene.dispose();
            if (ping != null) ping.dispose();
            if (pong != null) pong.dispose();
        }
    }

    // -------------------------------------------------------------------------------------------------------------------------- Fx

    /** The post-processing shaders as GLSL programs on stock libGDX: the reference. */
    static Fx glFx() {
        final String vert = "attribute vec4 a_position;\nattribute vec4 a_color;\nattribute vec2 a_texCoord0;\nuniform mat4 u_projTrans;\n"
            + "varying vec4 v_color;\nvarying vec2 v_texCoords;\n"
            + "void main() {\n  v_color = a_color;\n  v_color.a = v_color.a * (255.0 / 254.0);\n  v_texCoords = a_texCoord0;\n"
            + "  gl_Position = u_projTrans * a_position;\n}\n";
        final String head = "#ifdef GL_ES\nprecision highp float;\n#endif\nvarying vec4 v_color;\nvarying vec2 v_texCoords;\nuniform sampler2D u_texture;\n"
            + "uniform vec4 u_p;\n";
        final String[] frags = {
            // BRIGHT
            head + "void main() {\n  vec4 c = v_color * texture2D(u_texture, v_texCoords);\n"
                + "  gl_FragColor = vec4(max(c.rgb - vec3(u_p.x), vec3(0.0)) * u_p.y, 1.0);\n}\n",
            // BLUR
            head + "void main() {\n  float w0 = 0.227027, w1 = 0.1945946, w2 = 0.1216216, w3 = 0.054054, w4 = 0.016216;\n  vec2 s = u_p.xy;\n"
                + "  vec4 sum = texture2D(u_texture, v_texCoords) * w0;\n"
                + "  sum += (texture2D(u_texture, v_texCoords + s) + texture2D(u_texture, v_texCoords - s)) * w1;\n"
                + "  sum += (texture2D(u_texture, v_texCoords + 2.0 * s) + texture2D(u_texture, v_texCoords - 2.0 * s)) * w2;\n"
                + "  sum += (texture2D(u_texture, v_texCoords + 3.0 * s) + texture2D(u_texture, v_texCoords - 3.0 * s)) * w3;\n"
                + "  sum += (texture2D(u_texture, v_texCoords + 4.0 * s) + texture2D(u_texture, v_texCoords - 4.0 * s)) * w4;\n"
                + "  gl_FragColor = vec4(sum.rgb * v_color.rgb, 1.0);\n}\n",
            // CRT
            head + "void main() {\n  vec4 c = v_color * texture2D(u_texture, v_texCoords);\n"
                + "  float scan = 0.85 + 0.15 * sin(v_texCoords.y * u_p.x * 6.2831853);\n  vec2 d = v_texCoords - vec2(0.5);\n"
                + "  float vignette = 1.0 - dot(d, d) * u_p.y;\n  gl_FragColor = vec4(c.rgb * scan * vignette, c.a);\n}\n"};
        ShaderProgram.pedantic = false;
        final ShaderProgram[] programs = new ShaderProgram[frags.length];
        for (int i = 0; i < frags.length; i++) {
            programs[i] = new ShaderProgram(vert, frags[i]);
            if (!programs[i].isCompiled()) throw new IllegalStateException("GL effect " + i + " did not compile: " + programs[i].getLog());
        }
        return new Fx() {
            public void use(Batch b, Effect e, float p0, float p1, float p2, float p3) {
                ShaderProgram p = programs[e.ordinal()];
                p.bind();
                p.setUniformf("u_p", p0, p1, p2, p3);
                b.setShader(p);
            }

            public void none(Batch b) {
                b.setShader(null);
            }

            public void dispose() {
                for (ShaderProgram p : programs) p.dispose();
            }
        };
    }

    /** The same shaders as SPIR-V bundles on gdx-vulkan. */
    static Fx vulkanFx() {
        final VulkanShaderPipelineBundle[] bundles = {VulkanShaderPipelineBundle.fromResources("fx_bright", "sprite.vert", "fx_bright.frag"),
            VulkanShaderPipelineBundle.fromResources("fx_blur", "sprite.vert", "fx_blur.frag"),
            VulkanShaderPipelineBundle.fromResources("fx_crt", "sprite.vert", "fx_crt.frag")};
        return new Fx() {
            public void use(Batch b, Effect e, float p0, float p1, float p2, float p3) {
                VulkanSpriteBatch vb = (VulkanSpriteBatch)b;
                vb.flush(); // the custom values are read when the batch flushes: what is queued keeps the old ones
                VulkanShaderPipelineBundle bundle = bundles[e.ordinal()];
                bundle.custom0[0] = p0;
                bundle.custom0[1] = p1;
                bundle.custom0[2] = p2;
                bundle.custom0[3] = p3;
                vb.setShaderBundle(bundle);
            }

            public void none(Batch b) {
                ((VulkanSpriteBatch)b).setShaderBundle(null);
            }

            public void dispose() {}
        };
    }
}
