package io.github.slebed.gdxvulkan.demos;

/**
 * The three OpenGL 4.3 / ES 3.1 calls libGDX's interfaces have on desktop only ({@code Gdx.gl31}); the OpenGL boids demo makes everything else through {@code Gdx.gl30}. The
 * desktop main sets {@link #current} from {@code Gdx.gl31}, the Android app from {@code android.opengl.GLES31}, so the same demo class runs on both.
 */
public interface Compute {
    /** The implementation of the running application, null before it sets one. */
    Compute[] CURRENT = new Compute[1];

    void dispatch (int x, int y, int z);

    void barrier (int bits);

    /** {@code glDrawElementsIndirect} with the command at this byte offset of the bound draw-indirect buffer. */
    void drawElementsIndirect (int mode, int type, int offset);

    /**
     * The source of a shader written for desktop OpenGL 4.3, adapted for OpenGL ES 3.1 when the application is an Android one: the version line, default precisions, and no
     * {@code const} on local variables (ES insists on constant initialisers).
     */
    static String source (String desktop) {
        if (!Controls.touch()) return desktop;
        final StringBuilder out = new StringBuilder("#version 310 es\nprecision highp float;\nprecision highp int;\n");
        boolean first = true;
        for (String line : desktop.split("\n", -1)) {
            if (first && line.startsWith("#version")) {
                first = false;
                continue;
            }
            first = false;
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;
            out.append(indent > 0 && line.startsWith("const ", indent) ? line.substring(0, indent) + line.substring(indent + 6) : line).append('\n');
        }
        return out.toString();
    }
}
