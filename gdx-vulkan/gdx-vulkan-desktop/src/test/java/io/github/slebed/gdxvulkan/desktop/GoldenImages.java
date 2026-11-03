package io.github.slebed.gdxvulkan.desktop;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.opentest4j.AssertionFailedError;

/**
 * Golden-image comparison (plan, Core decision 8). The goldens are lavapipe renders, so:
 * <ul>
 * <li>On lavapipe (the pinned Mesa on CI) the image must match exactly.</li>
 * <li>On a real GPU the rasterizer differs from lavapipe at triangle edges, so the same golden is compared with a tolerance
 * (per-channel threshold plus a maximum share of differing pixels).</li>
 * </ul>
 * Goldens are written only by a lavapipe run with {@code -Dgdxvulkan.updateGoldens=true}, and only when the Mesa pin changes.
 * On a mismatch the actual image and a diff image go to {@code gdxvulkan.diffDir} for CI to upload.
 */
final class GoldenImages {
    /** Real-GPU tolerance: a channel differs when it is off by more than this ... */
    static final int GPU_CHANNEL_THRESHOLD = 12;
    /** ... and the image fails when more than this share of pixels differ. */
    static final double GPU_MAX_DIFF_SHARE = 0.01;

    private GoldenImages() {}

    static boolean isLavapipe(String deviceName) {
        String n = deviceName.toLowerCase();
        return n.contains("llvmpipe") || n.contains("lavapipe");
    }

    /** @param rgba RGBA8, row 0 at the top, as returned by {@code GdxVkPresenter.endFrameAndCapture()} */
    static void assertMatches(String scene, byte[] rgba, int width, int height, String deviceName) {
        boolean lavapipe = isLavapipe(deviceName);
        File golden = new File(requireProp("gdxvulkan.goldenDir"), scene + ".png");
        BufferedImage actual = toImage(rgba, width, height);

        if (Boolean.getBoolean("gdxvulkan.updateGoldens")) {
            if (!lavapipe) {
                throw new IllegalStateException("Goldens are lavapipe renders; refusing to write one from '" + deviceName
                    + "'. Run with a lavapipe device (see the CI workflow) to update goldens.");
            }
            write(actual, golden);
            System.out.println("[golden] wrote " + golden);
            return;
        }
        if (!golden.isFile()) {
            writeDiffArtifacts(scene, actual, null);
            throw new AssertionFailedError("No golden for '" + scene + "' at " + golden
                + ". Generate it on lavapipe with -Dgdxvulkan.updateGoldens=true; the actual image is in the diff dir.");
        }

        BufferedImage expected = read(golden);
        if (expected.getWidth() != width || expected.getHeight() != height) {
            writeDiffArtifacts(scene, actual, null);
            throw new AssertionFailedError(String.format("Golden '%s' is %dx%d but the capture is %dx%d", scene, expected.getWidth(),
                expected.getHeight(), width, height));
        }

        int threshold = lavapipe ? 0 : GPU_CHANNEL_THRESHOLD;
        double allowedShare = lavapipe ? 0.0 : GPU_MAX_DIFF_SHARE;
        int differing = 0, maxDelta = 0;
        BufferedImage diff = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = actual.getRGB(x, y), e = expected.getRGB(x, y);
                int delta = 0;
                for (int shift = 0; shift < 32; shift += 8) delta = Math.max(delta, Math.abs(((a >> shift) & 0xFF) - ((e >> shift) & 0xFF)));
                maxDelta = Math.max(maxDelta, delta);
                boolean bad = delta > threshold;
                if (bad) differing++;
                diff.setRGB(x, y, bad ? 0xFFFF00FF : (e & 0x00FFFFFF) | 0x40000000);
            }
        }
        double share = differing / (double)(width * height);
        if (share > allowedShare) {
            writeDiffArtifacts(scene, actual, diff);
            throw new AssertionFailedError(String.format(
                "Golden mismatch for '%s' on %s (%s): %d pixels (%.3f%%) differ by more than %d, max channel delta %d; allowed %.3f%%. "
                    + "Images are in %s",
                scene, deviceName, lavapipe ? "exact" : "tolerance", differing, share * 100, threshold, maxDelta, allowedShare * 100,
                System.getProperty("gdxvulkan.diffDir", "(gdxvulkan.diffDir unset)")));
        }
    }

    private static String requireProp(String key) {
        String v = System.getProperty(key);
        if (v == null) throw new IllegalStateException("System property " + key + " is not set (the Gradle test task sets it)");
        return v;
    }

    private static BufferedImage toImage(byte[] rgba, int width, int height) {
        if (rgba.length != width * height * 4) throw new IllegalArgumentException("capture is " + rgba.length + " bytes, expected "
            + width * height * 4);
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0, p = 0; p < width * height; p++, i += 4) {
            int argb = ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16) | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF);
            img.setRGB(p % width, p / width, argb);
        }
        return img;
    }

    private static BufferedImage read(File f) {
        try {
            BufferedImage in = ImageIO.read(f);
            // Copy the pixels exactly. Painting the image into an ARGB one (drawImage) would premultiply and un-premultiply, which moves
            // every partially transparent pixel (blended text edges, for one) by one unit and made an exact compare impossible.
            BufferedImage out = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < in.getHeight(); y++) {
                for (int x = 0; x < in.getWidth(); x++) out.setRGB(x, y, in.getRGB(x, y));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read golden " + f, e);
        }
    }

    private static void write(BufferedImage img, File f) {
        try {
            f.getParentFile().mkdirs();
            ImageIO.write(img, "png", f);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write " + f, e);
        }
    }

    private static void writeDiffArtifacts(String scene, BufferedImage actual, BufferedImage diff) {
        String dir = System.getProperty("gdxvulkan.diffDir");
        if (dir == null) return;
        write(actual, new File(dir, scene + ".actual.png"));
        if (diff != null) write(diff, new File(dir, scene + ".diff.png"));
    }
}
