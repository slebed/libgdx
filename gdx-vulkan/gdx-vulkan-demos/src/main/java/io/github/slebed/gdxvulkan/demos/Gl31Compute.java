package io.github.slebed.gdxvulkan.demos;

import com.badlogic.gdx.Gdx;

/** {@link Compute} over libGDX's {@code Gdx.gl31} (desktop OpenGL 4.3). */
final class Gl31Compute implements Compute {
    @Override public void dispatch (int x, int y, int z) {
        Gdx.gl31.glDispatchCompute(x, y, z);
    }

    @Override public void barrier (int bits) {
        Gdx.gl31.glMemoryBarrier(bits);
    }

    @Override public void drawElementsIndirect (int mode, int type, int offset) {
        Gdx.gl31.glDrawElementsIndirect(mode, type, offset);
    }
}
