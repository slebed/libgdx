package io.github.slebed.gdxvulkan.testapp;


import io.github.slebed.gdxvulkan.GdxVkPresenter;

/**
 * The desktop TriangleTest, resolution independent: the triangle is upright, not culled, reversed winding is culled.
 * Samples are fractions of the surface size (clip space spans the whole viewport, so they hold at any aspect ratio).
 * Logs one line starting with {@code SELFTEST PASS} or {@code SELFTEST FAIL}.
 */
final class SelfTest {
    private SelfTest() {}

    private static final float[] CLEAR = {0.1f, 0.1f, 0.3f, 1f};
    private static final int[] CLEAR8 = {26, 26, 77, 255};
    private static final int[] TRI8 = {255, 128, 0, 255};

    static void run(GdxVkPresenter p, int pipeline) {
        StringBuilder failures = new StringBuilder();
        int w = p.width(), h = p.height();

        byte[] front = capture(p, pipeline, false);
        expect(failures, front, w, 0.50, 0.45, TRI8, "centre of the triangle");
        expect(failures, front, w, 0.50, 0.70, TRI8, "just above the base");
        expect(failures, front, w, 0.50, 0.10, CLEAR8, "above the apex (triangle flipped?)");
        expect(failures, front, w, 0.50, 0.90, CLEAR8, "below the base (triangle flipped?)");
        expect(failures, front, w, 0.10, 0.50, CLEAR8, "left of the triangle");
        expect(failures, front, w, 0.90, 0.50, CLEAR8, "right of the triangle");

        byte[] culled = capture(p, pipeline, true);
        expect(failures, culled, w, 0.50, 0.45, CLEAR8, "reversed winding must be culled");
        expect(failures, culled, w, 0.50, 0.70, CLEAR8, "reversed winding must be culled");

        if (failures.length() == 0) TestLog.i(MainActivity.TAG, "SELFTEST PASS " + w + "x" + h);
        else TestLog.e(MainActivity.TAG, "SELFTEST FAIL " + w + "x" + h + failures);
    }

    private static byte[] capture(GdxVkPresenter p, int pipeline, boolean reverse) {
        // The first frames after surface creation can be skipped while the swapchain settles.
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!p.beginFrame(CLEAR[0], CLEAR[1], CLEAR[2], CLEAR[3])) continue;
            float[] push = new float[32];
            push[0] = push[5] = push[10] = push[15] = 1f;
            push[16] = 1f; push[17] = 0.5f; push[18] = 0f; push[19] = 1f;
            push[20] = Float.intBitsToFloat(reverse ? 1 : 0);
            p.draw(pipeline, 3, push);
            return p.endFrameAndCapture();
        }
        throw new IllegalStateException("no frame could be started");
    }

    private static void expect(StringBuilder failures, byte[] rgba, int width, double fx, double fy, int[] expected, String what) {
        int height = rgba.length / 4 / width;
        int x = (int)(fx * width), y = (int)(fy * height);
        int i = (y * width + x) * 4;
        for (int c = 0; c < 4; c++) {
            int actual = rgba[i + c] & 0xFF;
            if (Math.abs(actual - expected[c]) > 2) {
                failures.append("\n  ").append(what).append(": pixel (").append(x).append(',').append(y).append(") channel ").append(c)
                    .append(" was ").append(actual).append(", expected ").append(expected[c]);
                return;
            }
        }
    }
}
