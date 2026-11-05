package io.github.slebed.gdxvulkan.demoapp;

/**
 * The GPU time of the commands between {@link #begin} and {@link #end} on OpenGL ES, from GL_EXT_disjoint_timer_query (a few lines of JNI: the Android SDK has no binding
 * for it). The result is read two frames later, so asking for it never makes the CPU wait for the GPU. {@link #nanos} is 0 when the phone has no such extension.
 */
final class GlesTimer {
    private static final int SLOTS = 3;
    private final boolean ok;
    private final boolean[] issued = new boolean[SLOTS];
    private int next;
    private volatile long last;

    static {
        System.loadLibrary("gdxvkdemo");
    }

    /** Call with the GL context current. */
    GlesTimer () {
        boolean made;
        try {
            made = nativeInit(SLOTS) == SLOTS;
        } catch (Throwable e) {
            made = false;
        }
        ok = made;
    }

    void begin () {
        if (ok) nativeBegin(next);
    }

    void end () {
        if (!ok) return;
        nativeEnd();
        issued[next] = true;
        next = (next + 1) % SLOTS;
        if (issued[next] && nativeAvailable(next)) last = nativeResult(next); // the oldest slot: ready by now
    }

    long nanos () {
        return last;
    }

    private static native int nativeInit (int count);

    private static native void nativeBegin (int slot);

    private static native void nativeEnd ();

    private static native boolean nativeAvailable (int slot);

    private static native long nativeResult (int slot);
}
