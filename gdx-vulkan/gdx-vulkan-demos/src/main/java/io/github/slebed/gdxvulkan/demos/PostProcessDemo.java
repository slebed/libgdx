package io.github.slebed.gdxvulkan.demos;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ProgressBar;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

/**
 * Post-processing over a Scene2D UI, with stock libGDX classes only ({@code Stage}, {@code Skin}, {@code FrameBuffer}, {@code ShaderProgram}, {@code SpriteBatch}): the stage
 * is drawn into a framebuffer, a bright pass and two blur passes run over it, and the sum goes to the screen. On gdx-vulkan the shaders are the stock GLSL below, translated at
 * run time; nothing in this class names the backend.
 */
public final class PostProcessDemo implements Demo {
    private static final String VERTEX = "attribute vec4 a_position;\nattribute vec4 a_color;\nattribute vec2 a_texCoord0;\nuniform mat4 u_projTrans;\n"
        + "varying vec4 v_color;\nvarying vec2 v_texCoords;\n"
        + "void main() {\n    v_color = a_color;\n    v_color.a = v_color.a * (255.0 / 254.0);\n    v_texCoords = a_texCoord0;\n    gl_Position = u_projTrans * a_position;\n}\n";
    private static final String BRIGHT = "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_color;\nvarying vec2 v_texCoords;\nuniform sampler2D u_texture;\n"
        + "void main() {\n    vec4 c = texture2D(u_texture, v_texCoords);\n    float l = max(c.r, max(c.g, c.b));\n    gl_FragColor = c * smoothstep(0.55, 0.9, l);\n}\n";
    private static final String BLUR = "#ifdef GL_ES\nprecision mediump float;\n#endif\nvarying vec4 v_color;\nvarying vec2 v_texCoords;\nuniform sampler2D u_texture;\n"
        + "uniform vec2 u_dir;\n"
        + "void main() {\n    vec4 sum = texture2D(u_texture, v_texCoords) * 0.2270270270;\n"
        + "    sum += (texture2D(u_texture, v_texCoords + u_dir * 1.3846153846) + texture2D(u_texture, v_texCoords - u_dir * 1.3846153846)) * 0.3162162162;\n"
        + "    sum += (texture2D(u_texture, v_texCoords + u_dir * 3.2307692308) + texture2D(u_texture, v_texCoords - u_dir * 3.2307692308)) * 0.0702702703;\n"
        + "    gl_FragColor = sum;\n}\n";

    private SpriteBatch batch;
    private Stage stage;
    private Skin skin;
    private BitmapFont font;
    private Texture white;
    private FrameBuffer scene, ping, pong;
    private ShaderProgram bright, blur;
    private ProgressBar bar;
    private Label clock;
    private float time;
    private Hud hud;
    private int width, height;

    @Override public String title () {
        return "Post-processing over a Scene2D UI";
    }

    @Override public String summary () {
        return "A Scene2D stage drawn into a framebuffer, then a bright pass and two blur passes (stock GLSL) and the sum on the screen: the glow follows the animated UI.";
    }

    @Override public String[] notes () {
        return new String[] {"Stock classes only, the same code on both backends: on gdx-vulkan the stock SpriteBatch, Stage, FrameBuffer and the GLSL below run through the GL shim.",
            Controls.touch() ? "" : "C starts the stock OpenGL version beside this window."};
    }

    @Override public String openGlCounterpart () {
        return "postfx";
    }

    @Override public void create () {
        // The post passes use the stock SpriteBatch on both backends (through the shim on gdx-vulkan: the translated GLSL below needs a stock ShaderProgram); the Stage draws with it too.
        batch = new SpriteBatch();
        font = new BitmapFont();
        final Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pm.setColor(Color.WHITE);
        pm.fill();
        white = new Texture(pm);
        pm.dispose();
        skin = new Skin();
        skin.add("white", white);
        skin.add("default", font);
        skin.add("default", new Label.LabelStyle(font, Color.WHITE));
        final TextButton.TextButtonStyle button = new TextButton.TextButtonStyle();
        button.font = font;
        button.up = skin.newDrawable("white", 0.15f, 0.35f, 0.9f, 1f);
        button.down = skin.newDrawable("white", 0.9f, 0.5f, 0.1f, 1f);
        skin.add("default", button);
        final ProgressBar.ProgressBarStyle progress = new ProgressBar.ProgressBarStyle();
        progress.background = skin.newDrawable("white", 0.1f, 0.1f, 0.14f, 1f);
        progress.background.setMinHeight(18);
        progress.knobBefore = skin.newDrawable("white", 1f, 0.75f, 0.1f, 1f);
        progress.knobBefore.setMinHeight(18);
        skin.add("default-horizontal", progress);

        bright = new ShaderProgram(VERTEX, BRIGHT);
        blur = new ShaderProgram(VERTEX, BLUR);
        if (!bright.isCompiled() || !blur.isCompiled()) throw new IllegalStateException(bright.getLog() + blur.getLog());

        stage = new Stage(new ScreenViewport(), batch);
        final Table table = new Table();
        table.setFillParent(true);
        clock = new Label("", skin);
        bar = new ProgressBar(0, 1, 0.001f, false, skin);
        bar.setValue(0f);
        table.add(clock).pad(10).row();
        table.add(bar).width(360).pad(10).row();
        for (int i = 0; i < 4; i++) table.add(new TextButton("Button " + (i + 1), skin)).width(200).height(44).pad(6).row();
        stage.addActor(table);
        hud = new Hud();
    }

    @Override public void resize (int w, int h) {
        width = Math.max(w, 1);
        height = Math.max(h, 1);
        if (stage != null) stage.getViewport().update(width, height, true);
        dispose(scene, ping, pong);
        scene = new FrameBuffer(Pixmap.Format.RGBA8888, width, height, false);
        ping = new FrameBuffer(Pixmap.Format.RGBA8888, Math.max(width / 2, 1), Math.max(height / 2, 1), false);
        pong = new FrameBuffer(Pixmap.Format.RGBA8888, Math.max(width / 2, 1), Math.max(height / 2, 1), false);
        for (FrameBuffer f : new FrameBuffer[] {scene, ping, pong}) f.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
    }

    private static void dispose (FrameBuffer... buffers) {
        for (FrameBuffer f : buffers) if (f != null) f.dispose();
    }

    /** Draws {@code texture} over a w x h target with {@code shader} (null: the batch's own); a blur reads its direction in texels from {@code dx}, {@code dy}. */
    private void pass (Texture texture, int w, int h, ShaderProgram shader, float dx, float dy) {
        batch.setShader(shader);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        batch.begin();
        if (shader == blur) shader.setUniformf("u_dir", dx / w, dy / h);
        batch.draw(texture, 0, 0, w, h, 0, 0, 1, 1); // the texture of a framebuffer: the screen-size quad with the unflipped region keeps the picture upright through every pass
        batch.end();
    }

    @Override public void render () {
        if (scene == null) return;
        time += Gdx.graphics.getDeltaTime();
        bar.setValue((time * 0.25f) % 1f);
        clock.setText(String.format("t = %.1f s", time));
        stage.act(Gdx.graphics.getDeltaTime());

        scene.begin();
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.1f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        stage.draw();
        scene.end();

        batch.disableBlending();
        ping.begin();
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        pass(scene.getColorBufferTexture(), ping.getWidth(), ping.getHeight(), bright, 0, 0);
        ping.end();
        pong.begin();
        pass(ping.getColorBufferTexture(), pong.getWidth(), pong.getHeight(), blur, 1, 0);
        pong.end();
        ping.begin();
        pass(pong.getColorBufferTexture(), ping.getWidth(), ping.getHeight(), blur, 0, 1);
        ping.end();

        Gdx.gl.glViewport(0, 0, width, height);
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        pass(scene.getColorBufferTexture(), width, height, null, 0, 0);
        batch.enableBlending();
        batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE); // add the glow
        pass(ping.getColorBufferTexture(), width, height, null, 0, 0);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        hud.draw(title(), summary(), notes(), "frame buffers: " + width + " x " + height + ", glow at half size", "");
    }

    @Override public void pause () {}

    @Override public void resume () {}

    @Override public void dispose () {
        hud.dispose();
        stage.dispose();
        skin.dispose();
        dispose(scene, ping, pong);
        bright.dispose();
        blur.dispose();
        white.dispose();
        batch.dispose();
    }
}
