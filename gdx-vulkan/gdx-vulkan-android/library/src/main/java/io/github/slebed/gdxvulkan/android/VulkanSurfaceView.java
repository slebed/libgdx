package io.github.slebed.gdxvulkan.android;

import android.content.Context;
import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.badlogic.gdx.Input.OnscreenKeyboardType;
import com.badlogic.gdx.backends.android.DefaultAndroidInput;

/**
 * The surface the Vulkan application draws into. Apart from being a {@code SurfaceView}, it does what libGDX's own {@code GLSurfaceView20} does for the on-screen
 * keyboard: it offers the keyboard an input connection, so that what is typed arrives as the key events {@code DefaultAndroidInput} turns into libGDX input
 * events. Without it the keyboard has nowhere to type to.
 */
public final class VulkanSurfaceView extends SurfaceView {
    /** The kind of keyboard the next {@code onCreateInputConnection} asks for; set by {@link VulkanAndroidInput}. */
    OnscreenKeyboardType onscreenKeyboardType = OnscreenKeyboardType.Default;

    public VulkanSurfaceView (Context context) {
        super(context);
    }

    @Override public InputConnection onCreateInputConnection (EditorInfo outAttrs) {
        if (outAttrs != null) {
            outAttrs.imeOptions = outAttrs.imeOptions | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            outAttrs.inputType = DefaultAndroidInput.getAndroidInputType(onscreenKeyboardType, true);
        }
        return new BaseInputConnection(this, false) {
            @Override public boolean deleteSurroundingText (int beforeLength, int afterLength) {
                // Since Jelly Bean the keyboard sends this instead of a delete key event: turn it back into one, as stock libGDX does.
                if (beforeLength == 1 && afterLength == 0) {
                    final long eventTime = SystemClock.uptimeMillis();
                    super.sendKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                        KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
                    super.sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                        KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
                    return true;
                }
                return super.deleteSurroundingText(beforeLength, afterLength);
            }
        };
    }
}
