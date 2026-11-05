package io.github.slebed.gdxvulkan.demoapp;

import android.os.Bundle;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkFrame;
import io.github.slebed.gdxvulkan.VulkanCaps;
import io.github.slebed.gdxvulkan.android.VulkanAndroidApplication;
import io.github.slebed.gdxvulkan.demos.Backend;
import io.github.slebed.gdxvulkan.demos.DemoMenu;

/**
 * The demo app on a phone: a menu of the demos (a tap starts one, the Back button returns), the same classes the desktop app runs. The OpenGL counterparts are
 * desktop only, so there is no C key.
 *
 * <pre>
 * adb shell am start -n io.github.slebed.gdxvulkan.demos/io.github.slebed.gdxvulkan.demoapp.DemosActivity [--ei start 1..3] [--ez forceTierB true] [--ez vsync true] [--ei logSeconds N]
 * </pre>
 * {@code start} opens a demo at once; {@code logSeconds} writes the numbers shown on screen to {@code files/gdxvk-demos.log} every N seconds (for the test script,
 * since logcat is restricted on some phones).
 */
public class DemosActivity extends VulkanAndroidApplication {
    @Override public void onBackPressed () { // before Android 13; the demos read the flag as Esc: back to the menu, and from the menu out of the app
        Backend.back = true;
    }

    @Override protected void onCreate (Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Android 13 and later with a target of 36 no longer call onBackPressed: the app registers for the back gesture and button instead.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> Backend.back = true);
        }
        final int start = getIntent().getIntExtra("start", 0);
        final int logSeconds = getIntent().getIntExtra("logSeconds", 0);
        if (getIntent().hasExtra("boids")) System.setProperty("demo.boids", String.valueOf(getIntent().getIntExtra("boids", 0)));
        if (getIntent().hasExtra("sprites")) System.setProperty("demo.sprites", String.valueOf(getIntent().getIntExtra("sprites", 0)));
        if (getIntent().hasExtra("hud")) System.setProperty("demo.hud", getIntent().getStringExtra("hud"));
        if (getIntent().hasExtra("mode")) System.setProperty("demo.mode", String.valueOf(getIntent().getIntExtra("mode", 0)));
        final PhoneStats stats = new PhoneStats(getFilesDir(), logSeconds);
        final GdxVkConfig vkConfig = new GdxVkConfig();
        vkConfig.forceTierB = getIntent().getBooleanExtra("forceTierB", false);
        final AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useAccelerometer = false;
        config.useCompass = false;
        config.disableAudio = true;
        config.useImmersiveMode = true;
        final DemoMenu menu = new DemoMenu(null);
        initialize(new ApplicationAdapter() {
            @Override public void create () {
                final VulkanCaps caps = GdxVkFrame.current().context().caps();
                Backend.lines = new String[] {"Vulkan " + Backend.vulkanVersion(caps.deviceApiVersion) + ", gdx-vulkan", caps.deviceName,
                    "driver " + (caps.driverInfo == null || caps.driverInfo.isEmpty() ? "(not reported)" : caps.driverInfo),
                    caps.tier == VulkanCaps.Tier.A ? "modern code path: bindless textures, dynamic rendering" : "compatibility code path (Tier B)"};
                Backend.vulkan = true;
                Backend.gpuNanos = () -> GdxVkFrame.current().presenter().gpuFrameNanos();
                Gdx.input.setCatchKey(Input.Keys.BACK, true);
                Gdx.input.setInputProcessor(new com.badlogic.gdx.InputAdapter() {
                    @Override public boolean keyDown (int keycode) {
                        if (keycode != Input.Keys.BACK) return false;
                        Backend.back = true;
                        return true;
                    }
                });
                menu.create();
                if (start > 0) menu.start(start - 1);
            }

            @Override public void resize (int width, int height) {
                menu.resize(width, height);
            }

            @Override public void render () {
                menu.render();
                stats.frame();
            }

            @Override public void pause () {
                menu.pause();
            }

            @Override public void resume () {
                menu.resume();
            }

            @Override public void dispose () {
                menu.dispose();
            }
        }, config, vkConfig, getIntent().getBooleanExtra("vsync", true));
    }
}
