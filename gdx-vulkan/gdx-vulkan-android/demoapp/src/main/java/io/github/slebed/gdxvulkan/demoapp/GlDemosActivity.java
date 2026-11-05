package io.github.slebed.gdxvulkan.demoapp;

import android.opengl.GLES31;
import android.os.Bundle;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;

import io.github.slebed.gdxvulkan.demos.Backend;
import io.github.slebed.gdxvulkan.demos.Compute;
import io.github.slebed.gdxvulkan.demos.Demo;
import io.github.slebed.gdxvulkan.demos.GpuBoidsOpenGlDemo;
import io.github.slebed.gdxvulkan.demos.SpritesDemo;
import io.github.slebed.gdxvulkan.demos.StockShadowsDemo;

/**
 * One demo on <b>stock libGDX and OpenGL ES</b>, the counterpart of {@link DemosActivity} on a phone: the same Demo classes, started through libGDX's own
 * {@code AndroidApplication}. For measuring against gdx-vulkan on the same phone.
 *
 * <pre>
 * adb shell am start -n io.github.slebed.gdxvulkan.demos/io.github.slebed.gdxvulkan.demoapp.GlDemosActivity --es demo sprites|boids|shadows-stock [--ei boids N] [--ez vsync true] [--ei logSeconds S]
 * </pre>
 */
public class GlDemosActivity extends AndroidApplication {
    @Override protected void onCreate (Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String id = getIntent().hasExtra("demo") ? getIntent().getStringExtra("demo") : "boids";
        if (getIntent().hasExtra("boids")) System.setProperty("demo.boids", String.valueOf(getIntent().getIntExtra("boids", 0)));
        if (getIntent().hasExtra("sprites")) System.setProperty("demo.sprites", String.valueOf(getIntent().getIntExtra("sprites", 0)));
        if (getIntent().hasExtra("hud")) System.setProperty("demo.hud", String.valueOf(getIntent().getBooleanExtra("hud", true)));
        if (getIntent().hasExtra("mode")) System.setProperty("demo.mode", String.valueOf(getIntent().getIntExtra("mode", 0)));
        final PhoneStats stats = new PhoneStats(getFilesDir(), getIntent().getIntExtra("logSeconds", 0));
        final AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useGL30 = true; // ES 3.x: compute shaders need 3.1, which the context gives where the phone has it
        config.useAccelerometer = false;
        config.useCompass = false;
        config.disableAudio = true;
        config.useImmersiveMode = true;
        config.r = config.g = config.b = config.a = 8;
        config.depth = 24;
        final Demo demo = id.equals("sprites") ? new SpritesDemo() : id.equals("shadows-stock") ? new StockShadowsDemo() : new GpuBoidsOpenGlDemo();
        final boolean vsync = getIntent().getBooleanExtra("vsync", true);
        initialize(new ApplicationAdapter() {
            @Override public void create () {
                if (!vsync) android.opengl.EGL14.eglSwapInterval(android.opengl.EGL14.eglGetCurrentDisplay(), 0); // libGDX's Android setVSync does nothing
                Backend.menu = false;
                Backend.lines = new String[] {Gdx.gl.glGetString(GL20.GL_VERSION).replaceAll(" V@.*", "") + ", stock libGDX", Gdx.gl.glGetString(GL20.GL_RENDERER),
                    "driver " + Gdx.gl.glGetString(GL20.GL_VENDOR), "AndroidApplication, " + (Gdx.gl30 != null ? "GL30 interface" : "GL20 interface")};
                Compute.CURRENT[0] = new Compute() {
                    @Override public void dispatch (int x, int y, int z) {
                        GLES31.glDispatchCompute(x, y, z);
                    }

                    @Override public void barrier (int bits) {
                        GLES31.glMemoryBarrier(bits);
                    }

                    @Override public void drawElementsIndirect (int mode, int type, int offset) {
                        GLES31.glDrawElementsIndirect(mode, type, offset);
                    }
                };
                // Android's SDK has no binding for GL_EXT_disjoint_timer_query, so the GPU time of stock OpenGL ES is not measured here.
                demo.create();
            }

            @Override public void resize (int width, int height) {
                demo.resize(width, height);
            }

            @Override public void render () {
                demo.render();
                stats.frame();
            }

            @Override public void dispose () {
                demo.dispose();
            }
        }, config);
    }
}
