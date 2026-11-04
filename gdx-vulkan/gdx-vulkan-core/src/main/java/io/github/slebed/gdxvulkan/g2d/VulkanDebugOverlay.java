package io.github.slebed.gdxvulkan.g2d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.utils.Disposable;

import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.GdxVkResources;

/**
 * A text overlay of what the renderer is doing, for development: frame rate, texture renames in the last frame, the deferred-deletion backlog,
 * live textures and buffers, sampler and staging-block counts, and the memory VMA holds. Draw it last in {@code render()}, inside a frame:
 *
 * <pre>
 * overlay.render(); // after the scene, before the frame ends
 * </pre>
 *
 * A steady non-zero "renames" on textures that are not meant to change, or a "retired" figure that keeps climbing, is the thing to look for.
 */
public class VulkanDebugOverlay implements Disposable {
    private final GdxVkFrame frame;
    private final VulkanSpriteBatch batch;
    private final BitmapFont font;
    private final boolean ownsFont;
    private final StringBuilder text = new StringBuilder(256);

    /** Uses the stock default font. */
    public VulkanDebugOverlay (GdxVkFrame frame) {
        this(frame, new BitmapFont(), true);
    }

    public VulkanDebugOverlay (GdxVkFrame frame, BitmapFont font) {
        this(frame, font, false);
    }

    private VulkanDebugOverlay (GdxVkFrame frame, BitmapFont font, boolean ownsFont) {
        this.frame = frame;
        this.font = font;
        this.ownsFont = ownsFont;
        this.batch = new VulkanSpriteBatch(frame);
    }

    /** The overlay's lines, refreshed from the current statistics. Allocation-free after the first call apart from the stats snapshot. */
    public CharSequence text () {
        final GdxVkResources.Stats s = frame.context().resources().stats();
        text.setLength(0);
        text.append("fps ").append(Gdx.graphics != null ? Gdx.graphics.getFramesPerSecond() : 0).append("  tier ")
            .append(frame.isTierA() ? 'A' : 'B').append('\n');
        text.append("renames/frame ").append(frame.getRenamesLastFrame()).append('\n');
        text.append("textures ").append(s.texturesLive).append("  buffers ").append(s.buffersLive).append("  slots ").append(s.slotsInUse).append('\n');
        text.append("retired pending ").append(s.retiredPending).append("  total ").append(s.totalRetired).append('\n');
        text.append("samplers ").append(s.samplers).append("  staging blocks ").append(s.stagingBlocks).append('\n');
        text.append("vma ").append(s.vmaAllocationBytes >> 10).append(" KB in ").append(s.vmaBlockBytes >> 10).append(" KB blocks");
        return text;
    }

    /** Draws the overlay in the top-left corner. Leaves the batch state you had (it uses its own batch). */
    public void render () {
        batch.getProjectionMatrix().setToOrtho2D(0, 0, frame.width(), frame.height());
        batch.begin();
        final float margin = 8 * (Gdx.graphics != null ? Math.max(1f, Gdx.graphics.getDensity()) : 1f);
        font.setColor(Color.WHITE);
        font.draw(batch, text(), margin, frame.height() - margin);
        batch.end();
    }

    @Override public void dispose () {
        batch.dispose();
        if (ownsFont) font.dispose();
    }
}
