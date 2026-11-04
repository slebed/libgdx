package io.github.slebed.gdxvulkan.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.InputMethodManager;

import com.badlogic.gdx.backends.android.AndroidApplicationBase;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.DefaultAndroidInput;

/**
 * libGDX's Android input with the on-screen keyboard working for a {@link VulkanSurfaceView}. The stock {@code setOnscreenKeyboardVisible} reaches its view by
 * casting the graphics to {@code AndroidGraphics} and the view to {@code GLSurfaceView20}, which an application that does not draw with OpenGL does not have; this
 * class shows and hides the keyboard for its own view instead. Touch, keys, sensors and the rest are the stock code.
 *
 * <p>{@code setOnscreenKeyboardVisible} is what Scene2D's {@code TextField} uses; {@code getTextInput} (the dialog) is the stock code. A newer libGDX's
 * {@code openTextInputField} (a text field overlay) needs the stock {@code AndroidApplication} and is not covered.
 */
public final class VulkanAndroidInput extends DefaultAndroidInput {
    private final Context context;
    private final VulkanSurfaceView view;
    private final Handler handler = new Handler(Looper.getMainLooper());

    VulkanAndroidInput (AndroidApplicationBase activity, Context context, VulkanSurfaceView view, AndroidApplicationConfiguration config) {
        super(activity, context, view, config);
        this.context = context;
        this.view = view;
    }

    @Override public void setOnscreenKeyboardVisible (final boolean visible, final OnscreenKeyboardType type) {
        handler.post(new Runnable() {
            @Override public void run () {
                final InputMethodManager manager = (InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (visible) {
                    final OnscreenKeyboardType wanted = type == null ? OnscreenKeyboardType.Default : type;
                    if (view.onscreenKeyboardType != wanted) {
                        view.onscreenKeyboardType = wanted;
                        manager.restartInput(view);
                    }
                    view.setFocusable(true);
                    view.setFocusableInTouchMode(true);
                    view.requestFocus();
                    manager.showSoftInput(view, 0);
                } else {
                    manager.hideSoftInputFromWindow(view.getWindowToken(), 0);
                }
            }
        });
    }
}
