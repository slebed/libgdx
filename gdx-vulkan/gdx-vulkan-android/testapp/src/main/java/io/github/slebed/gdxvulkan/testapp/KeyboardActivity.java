package io.github.slebed.gdxvulkan.testapp;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.android.VulkanAndroidApplication;

/**
 * The on-screen keyboard of a {@link VulkanAndroidApplication}: {@code Gdx.input.setOnscreenKeyboardVisible(true)} shows the system keyboard, the surface offers it an
 * input connection, a key event sent to the surface arrives as libGDX input, and {@code setOnscreenKeyboardVisible(false)} hides it again.
 *
 * <pre>
 * adb shell am start -n io.github.slebed.gdxvulkan.testapp/.KeyboardActivity
 * adb shell run-as io.github.slebed.gdxvulkan.testapp cat files/gdxvk-test.log      # look for KEYBOARD PASS
 * </pre>
 */
public class KeyboardActivity extends VulkanAndroidApplication {
    static final String TAG = "GdxVkKeyboard";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean created;
    private volatile char typed;
    private volatile int keyDowns;

    @Override protected void onCreate (Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TestLog.init(new java.io.File(getFilesDir(), "gdxvk-test.log"));
        final AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
        config.useAccelerometer = false;
        config.useCompass = false;
        config.disableAudio = true;
        initialize(new ApplicationAdapter() {
            private int frames;

            @Override public void create () {
                Gdx.input.setInputProcessor(new InputAdapter() {
                    @Override public boolean keyDown (int keycode) {
                        keyDowns++;
                        return false;
                    }

                    @Override public boolean keyTyped (char character) {
                        typed = character;
                        return false;
                    }
                });
                created = true;
            }

            @Override public void render () {
                Gdx.gl.glClearColor(0.1f, 0.2f, 0.3f, 1f);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                if (++frames == 20) ui.post(new Runnable() {
                    @Override public void run () {
                        runCheck();
                    }
                });
            }
        }, config, new GdxVkConfig(), true);
    }

    private boolean imeVisible () {
        final View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            final WindowInsets insets = decor.getRootWindowInsets();
            return insets != null && insets.isVisible(WindowInsets.Type.ime());
        }
        final InputMethodManager manager = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        return manager.isAcceptingText();
    }

    private void runCheck () {
        TestLog.i(TAG, "showing the keyboard");
        Gdx.input.setOnscreenKeyboardVisible(true);
        ui.postDelayed(new Runnable() {
            @Override public void run () {
                final boolean shown = imeVisible();
                final View view = getVulkanGraphics().getView();
                final boolean connection = view.onCreateInputConnection(new EditorInfo()) != null;
                // A key event sent to the surface, as the keyboard's input connection does, must come out as libGDX input.
                view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
                view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A));
                TestLog.i(TAG, "keyboard shown: " + shown + ", input connection: " + connection);
                Gdx.input.setOnscreenKeyboardVisible(false);
                ui.postDelayed(new Runnable() {
                    @Override public void run () {
                        final boolean hidden = !imeVisible();
                        final boolean gotKey = keyDowns > 0 && typed == 'a';
                        TestLog.i(TAG, "keyboard hidden: " + hidden + ", key reached libGDX: " + gotKey + " (keyDown " + keyDowns + ", keyTyped '" + typed + "')");
                        TestLog.i(TAG, (shown && connection && hidden && gotKey) ? "KEYBOARD PASS" : "KEYBOARD FAIL");
                        finish();
                    }
                }, 1500);
            }
        }, 2000);
    }
}
