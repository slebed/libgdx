package io.github.slebed.gdxvulkan.demos;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Disposable;

/**
 * The text on top of a demo: what it is about, what to look at, and live numbers. It is plain libGDX (a {@code SpriteBatch}, a {@code ShapeRenderer} and a
 * FreeType font), so it runs unchanged on OpenGL and on gdx-vulkan.
 *
 * <p>The size comes from the window height, never from fixed pixels: the font is generated again when the window changes, so the text is as easy to read on a
 * 4K display as on a laptop.
 */
public final class Hud implements Disposable {
    private static final Color PANEL = new Color(0.02f, 0.03f, 0.06f, 0.82f);
    private static final Color TITLE = new Color(1f, 0.82f, 0.35f, 1f);
    private static final Color BODY = new Color(0.95f, 0.96f, 1f, 1f);
    private static final Color STATS = new Color(0.55f, 0.95f, 0.7f, 1f);

    /** -Ddemo.hud: true (default), false (nothing), or one part for finding out what the text costs: layout, shapes, text. */
    private static final String PART = System.getProperty("demo.hud", "true");
    private static final boolean HIDDEN = PART.equals("false");
    private final Batch batch = Backend.vulkan ? new io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch() : new SpriteBatch(); // the batch of the backend the demo runs on
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final FreeTypeFontGenerator generator = new FreeTypeFontGenerator(Gdx.files.classpath("data/lsans.ttf"));
    private final GlyphLayout layout = new GlyphLayout();
    private final FrameStats frameStats = new FrameStats();
    private BitmapFont bodyFont, titleFont;
    private int fontsForHeight = -1;
    private boolean touchHint = true;
    private float buttonLeft, buttonTop, buttonWidth, buttonHeight, buttonGap;
    private int buttonCount;

    /** The size of the body text for a window of this height: about 1/36 of it, and never below 14 pixels. */
    static int bodySize (int windowHeight) {
        return Math.max(14, Math.round(windowHeight / 36f));
    }

    private void makeFonts (int windowHeight) {
        if (windowHeight == fontsForHeight) return;
        fontsForHeight = windowHeight;
        if (bodyFont != null) bodyFont.dispose();
        if (titleFont != null) titleFont.dispose();
        final FreeTypeFontParameter p = new FreeTypeFontParameter();
        p.size = bodySize(windowHeight);
        p.minFilter = com.badlogic.gdx.graphics.Texture.TextureFilter.Linear;
        p.magFilter = com.badlogic.gdx.graphics.Texture.TextureFilter.Linear;
        bodyFont = generator.generateFont(p);
        p.size = Math.round(p.size * 1.5f);
        titleFont = generator.generateFont(p);
    }

    /**
     * Draws the description panel at the top left and the live numbers at the bottom left. Call it after the scene, with nothing bound, once per frame.
     * The numbers are the same on every backend: what it runs on ({@link Backend#lines}), the frame, CPU and GPU time ({@link FrameStats}), then {@code demoStats}.
     */
    public void draw (String title, String summary, String[] notes, String... demoStats) {
        frameStats.frame();
        if (HIDDEN) return; // -Ddemo.hud=false: the scene alone, to see what the text costs
        if (Controls.touch()) { // no keyboard: the notes about C go, the touch controls come
            final java.util.ArrayList<String> kept = new java.util.ArrayList<String>();
            for (String note : notes) if (!note.startsWith("Press C")) kept.add(note);
            if (touchHint) kept.add("Touch: a tap on the left half of the screen is Space, on the right half L." + (Backend.menu ? " The Back button returns to the menu." : ""));
            notes = kept.toArray(new String[0]);
        }
        final String[] stats = new String[Backend.lines.length + frameStats.lines().length + demoStats.length];
        System.arraycopy(Backend.lines, 0, stats, 0, Backend.lines.length);
        System.arraycopy(frameStats.lines(), 0, stats, Backend.lines.length, frameStats.lines().length);
        System.arraycopy(demoStats, 0, stats, Backend.lines.length + frameStats.lines().length, demoStats.length);
        final int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        makeFonts(h);
        final float margin = bodyFont.getLineHeight() * 0.8f;
        final float textWidth = Math.min(w * 0.5f, bodyFont.getLineHeight() * 34f);

        // Measure the description first, so the panel can be drawn behind it.
        float textHeight = titleFont.getLineHeight() + margin * 0.5f;
        layout.setText(bodyFont, summary, BODY, textWidth, Align.left, true);
        textHeight += layout.height + margin * 0.5f;
        for (String note : notes) {
            layout.setText(bodyFont, note, BODY, textWidth, Align.left, true);
            textHeight += layout.height + margin * 0.6f;
        }
        final float statsHeight = bodyFont.getLineHeight() * stats.length;
        float statsWidth = 0;
        for (String line : stats) {
            layout.setText(bodyFont, line);
            statsWidth = Math.max(statsWidth, layout.width);
        }

        if (PART.equals("layout")) return;
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        if (!PART.equals("text")) {
            shapes.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(PANEL);
            shapes.rect(margin * 0.5f, h - margin * 0.5f - textHeight - margin, textWidth + margin * 2f, textHeight + margin * 1.5f);
            if (stats.length > 0) shapes.rect(margin * 0.5f, margin * 0.5f, statsWidth + margin * 1.5f, statsHeight + margin);
            shapes.end();
        }
        if (PART.equals("shapes")) {
            Gdx.gl.glDisable(GL20.GL_BLEND);
            return;
        }

        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        batch.begin();
        float y = h - margin;
        titleFont.setColor(TITLE);
        titleFont.draw(batch, title, margin, y);
        y -= titleFont.getLineHeight() + margin * 0.5f;
        bodyFont.setColor(BODY);
        layout.setText(bodyFont, summary, BODY, textWidth, Align.left, true);
        bodyFont.draw(batch, layout, margin, y);
        y -= layout.height + margin * 0.5f;
        for (String note : notes) {
            layout.setText(bodyFont, note, BODY, textWidth, Align.left, true);
            bodyFont.draw(batch, layout, margin, y);
            y -= layout.height + margin * 0.6f;
        }
        bodyFont.setColor(STATS);
        float sy = margin + statsHeight - bodyFont.getLineHeight() * 0.3f;
        for (String line : stats) {
            bodyFont.draw(batch, line, margin, sy);
            sy -= bodyFont.getLineHeight();
        }
        batch.end();
        if (DEBUGCALLS && ++dbg % 120 == 0) System.out.println("[hud] flushes " + (batch instanceof SpriteBatch ? ((SpriteBatch)batch).renderCalls : ((io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch)batch).renderCalls));
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }
    private static final boolean DEBUGCALLS = Boolean.getBoolean("demo.hudcalls");
    private int dbg;

    /**
     * Draws a column of large buttons at the right of the screen, for the menu of a phone (call it after {@link #draw}), and remembers where they are for
     * {@link #buttonAt}.
     */
    public void drawButtons (String[] labels) {
        makeFonts(Gdx.graphics.getHeight());
        final int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        buttonCount = labels.length;
        buttonHeight = bodyFont.getLineHeight() * 3f;
        buttonGap = bodyFont.getLineHeight();
        buttonWidth = Math.min(w * 0.45f, bodyFont.getLineHeight() * 18f);
        buttonLeft = w - buttonWidth - bodyFont.getLineHeight();
        buttonTop = h * 0.5f + (buttonHeight * labels.length + buttonGap * (labels.length - 1)) / 2f; // y up, the top of the first button
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.12f, 0.22f, 0.4f, 0.95f);
        for (int i = 0; i < labels.length; i++) shapes.rect(buttonLeft, buttonTop - (i + 1) * buttonHeight - i * buttonGap, buttonWidth, buttonHeight);
        shapes.end();
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        batch.begin();
        bodyFont.setColor(BODY);
        for (int i = 0; i < labels.length; i++) {
            layout.setText(bodyFont, labels[i], BODY, buttonWidth - bodyFont.getLineHeight(), Align.center, true);
            final float top = buttonTop - i * (buttonHeight + buttonGap);
            bodyFont.draw(batch, layout, buttonLeft + bodyFont.getLineHeight() * 0.5f, top - (buttonHeight - layout.height) / 2f);
        }
        batch.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
        touchHint = false; // this is the menu: its text says what a tap does
    }

    /** The index of the button under a touch at these window coordinates (y down), or -1. */
    public int buttonAt (int x, int y) {
        final float up = Gdx.graphics.getHeight() - y;
        for (int i = 0; i < buttonCount; i++) {
            final float top = buttonTop - i * (buttonHeight + buttonGap);
            if (x >= buttonLeft && x <= buttonLeft + buttonWidth && up <= top && up >= top - buttonHeight) return i;
        }
        return -1;
    }

    @Override public void dispose () {
        batch.dispose();
        shapes.dispose();
        generator.dispose();
        if (bodyFont != null) bodyFont.dispose();
        if (titleFont != null) titleFont.dispose();
    }
}
