package io.github.slebed.gdxvulkan.demos;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

/**
 * Every demo of the demo app starts, draws for twenty frames and leaves a picture that is not blank and has the on-screen text in it. They run through
 * {@link VulkanDemos#main} with the same switches a person would use ({@code -Ddemo.start}, {@code -Ddemo.screenshot}).
 */
class DemosSmokeTest {
    @TempDir File dir;

    /** Runs demo number {@code number} and returns the picture; the main method returns when the window closes. */
    private File run (int number) {
        final File picture = new File(dir, "demo" + number + ".png");
        System.setProperty("demo.start", String.valueOf(number));
        System.setProperty("demo.screenshot", picture.getAbsolutePath());
        System.setProperty("demo.frames", "20");
        System.setProperty("demo.width", "640");
        System.setProperty("demo.height", "400");
        System.setProperty("demo.boids", "4000");
        try {
            VulkanDemos.main(new String[0]);
        } finally {
            for (String key : new String[] {"demo.start", "demo.screenshot", "demo.frames", "demo.width", "demo.height", "demo.boids"}) System.clearProperty(key);
        }
        return picture;
    }

    /** More than 1% of the pixels are not the most common colour, and the text panel's corner is darker than the sky or the ground behind it would be alone. */
    private static void assertDrawn (File picture) throws IOException {
        assertTrue(picture.isFile(), "the demo wrote its picture");
        final java.awt.image.BufferedImage image = ImageIO.read(picture);
        final java.util.Map<Integer, Integer> counts = new java.util.HashMap<Integer, Integer>();
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) counts.merge(image.getRGB(x, y), 1, Integer::sum);
        }
        int total = 0, top = 0;
        for (int c : counts.values()) {
            total += c;
            top = Math.max(top, c);
        }
        assertTrue(top < total * 0.99, "the picture is not one flat colour: " + counts.size() + " colours, the commonest " + top + " of " + total);
        assertTrue(counts.size() > 20, "the picture has detail: " + counts.size() + " colours");
    }

    private static void needDisplay () {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        GLFW.glfwTerminate();
    }

    private static boolean tierB () {
        return Boolean.getBoolean("gdxvulkan.forceTierB");
    }

    @Test void theSpritesDemoDraws () throws IOException {
        needDisplay();
        assertDrawn(run(1));
    }

    @Test void theStockShadowsDemoDraws () throws IOException {
        needDisplay();
        assertDrawn(run(3));
    }

    @Test void theCascadedShadowsDemoDraws () throws IOException {
        needDisplay();
        assumeFalse(tierB(), "cascaded shadow maps need the modern code path (Vulkan 1.3)");
        assertDrawn(run(4));
    }

    @Test void theGpuBoidsDemoDraws () throws IOException {
        needDisplay();
        assumeFalse(tierB(), "GPU-driven instancing needs the modern code path (Vulkan 1.3)");
        assertDrawn(run(2));
    }
}
