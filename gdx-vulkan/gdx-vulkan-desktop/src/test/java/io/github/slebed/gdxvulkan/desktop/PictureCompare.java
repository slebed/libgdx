package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Two captured pictures (RGBA bytes): how many pixels differ by more than a tolerance in any colour channel. */
final class PictureCompare {
    private PictureCompare () {}

    /**
     * At most {@code fraction} of the pixels may differ by more than {@code tolerance} (0 to 255). The pictures are written to {@code build/<label>-*.png}
     * when they do not agree, to look at.
     */
    static void assertSimilar (String label, byte[] a, byte[] b, int width, int height, int tolerance, double fraction) {
        assertEquals(a.length, b.length, label + ": picture sizes");
        int off = 0, worst = 0;
        for (int i = 0; i < a.length; i += 4) {
            int d = 0;
            for (int c = 0; c < 3; c++) d = Math.max(d, Math.abs((a[i + c] & 255) - (b[i + c] & 255)));
            worst = Math.max(worst, d);
            if (d > tolerance) off++;
        }
        final double share = off / (double)(a.length / 4);
        if (share > fraction) {
            write(label + "-expected", a, width, height);
            write(label + "-stock", b, width, height);
        }
        assertTrue(share <= fraction, label + ": " + off + " pixels (" + share + ") differ by more than " + tolerance + ", worst " + worst + " (build/" + label + "-*.png)");
    }

    private static void write (String name, byte[] rgba, int w, int h) {
        try {
            final java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    final int o = (y * w + x) * 4;
                    img.setRGB(x, y, ((rgba[o] & 255) << 16) | ((rgba[o + 1] & 255) << 8) | (rgba[o + 2] & 255));
                }
            javax.imageio.ImageIO.write(img, "png", new java.io.File("build/" + name.replaceAll("[^A-Za-z0-9_-]", "_") + ".png"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
