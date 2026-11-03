package io.github.slebed.gdxvulkan.desktop;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.opentest4j.AssertionFailedError;

/**
 * Compares gdx-vulkan's output with the stock-libGDX-on-OpenGL reference images written by {@link GlReferenceMain} (plan, Core decision 8:
 * "compatibility reference = stock GL"; compared "with a tolerance: per-channel threshold plus a maximum share of differing pixels").
 */
final class OracleImages {
    /** A channel differs when it is off by more than this. Rasterizers and texture units round differently, so exact equality is not expected. */
    static final int CHANNEL_THRESHOLD = 16;
    /** A scene fails when more than this share of its pixels differ. */
    static final double MAX_DIFF_SHARE = 0.005;

    private OracleImages() {}

    static File referenceDir() {
        String d = System.getProperty("gdxvulkan.glReferenceDir");
        return d == null ? null : new File(d);
    }

    /** @return false when there is no reference for the scene (the caller decides whether that is acceptable) */
    static boolean compare(String scene, byte[] rgba, int width, int height) {
        File dir = referenceDir();
        File ref = dir == null ? null : new File(dir, scene + ".png");
        if (ref == null || !ref.isFile()) return false;
        BufferedImage expected;
        try {
            expected = ImageIO.read(ref);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + ref, e);
        }
        if (expected.getWidth() != width || expected.getHeight() != height) {
            throw new AssertionFailedError(String.format("GL reference '%s' is %dx%d, gdx-vulkan rendered %dx%d", scene, expected.getWidth(),
                expected.getHeight(), width, height));
        }
        int differing = 0, maxDelta = 0;
        long sumDelta = 0;
        BufferedImage diff = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                int e = expected.getRGB(x, y);
                int d = Math.max(Math.max(Math.abs((rgba[i] & 0xFF) - ((e >> 16) & 0xFF)), Math.abs((rgba[i + 1] & 0xFF) - ((e >> 8) & 0xFF))),
                    Math.abs((rgba[i + 2] & 0xFF) - (e & 0xFF)));
                maxDelta = Math.max(maxDelta, d);
                sumDelta += d;
                boolean bad = d > CHANNEL_THRESHOLD;
                if (bad) differing++;
                diff.setRGB(x, y, bad ? 0xFFFF00FF : 0x20000000 | (e & 0x00FFFFFF));
            }
        }
        double share = differing / (double)(width * height);
        System.out.printf("[oracle] %-18s differing=%d (%.3f%%) maxDelta=%d meanDelta=%.3f%n", scene, differing, share * 100, maxDelta,
            sumDelta / (double)(width * height));
        if (share > MAX_DIFF_SHARE) {
            String dd = System.getProperty("gdxvulkan.diffDir");
            if (dd != null) {
                try {
                    new File(dd).mkdirs();
                    ImageIO.write(diff, "png", new File(dd, scene + ".gl-diff.png"));
                } catch (IOException ignored) {
                    // the failure message below is what matters
                }
            }
            throw new AssertionFailedError(String.format("%s differs from stock libGDX on OpenGL: %d pixels (%.3f%%) are off by more than %d (max delta %d); "
                + "allowed %.3f%%", scene, differing, share * 100, CHANNEL_THRESHOLD, maxDelta, MAX_DIFF_SHARE * 100));
        }
        return true;
    }
}
