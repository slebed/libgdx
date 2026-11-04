package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.badlogic.gdx.Gdx;

import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.g2d.VulkanSpriteBatch;

/** The no-argument batch constructors need a gdx-vulkan application; without one they say what to do instead. (No GPU needed.) */
class FrameCurrentTest {
    @Test void withoutAVulkanApplicationTheErrorSaysWhatToDo () {
        Gdx.graphics = null;
        IllegalStateException e = assertThrows(IllegalStateException.class, GdxVkFrame::current);
        assertTrue(e.getMessage().contains("new VulkanSpriteBatch(frame)"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> new VulkanSpriteBatch());
    }
}
