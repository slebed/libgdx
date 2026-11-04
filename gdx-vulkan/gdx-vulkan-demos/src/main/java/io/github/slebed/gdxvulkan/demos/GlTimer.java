package io.github.slebed.gdxvulkan.demos;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * Measures how long the GPU takes for the commands between {@link #begin} and {@link #end}, with OpenGL timer queries. libGDX's GL interfaces cannot read a query's
 * result, so this is the one place the demos call LWJGL directly; it only measures and has no part in what a demo draws. The result is read two frames later, so
 * asking for it never makes the CPU wait for the GPU.
 */
final class GlTimer {
    private static final int TIME_ELAPSED = 0x88BF;
    private static final int SLOTS = 3;

    private final int[] queries = new int[SLOTS];
    private final boolean[] issued = new boolean[SLOTS];
    private int next;
    private volatile long lastNanos;

    GlTimer () {
        for (int i = 0; i < SLOTS; i++) queries[i] = GL15.glGenQueries();
    }

    void begin () {
        GL15.glBeginQuery(TIME_ELAPSED, queries[next]);
    }

    void end () {
        GL15.glEndQuery(TIME_ELAPSED);
        issued[next] = true;
        next = (next + 1) % SLOTS;
        // The slot that is next to be used is the oldest: its result is ready by now.
        if (issued[next] && GL15.glGetQueryObjecti(queries[next], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) lastNanos = GL33.glGetQueryObjecti64(queries[next], GL15.GL_QUERY_RESULT);
    }

    /** The GPU time of a recent frame in nanoseconds, 0 before the first one is known. */
    long nanos () {
        return lastNanos;
    }

    void dispose () {
        for (int q : queries) GL15.glDeleteQueries(q);
    }
}
