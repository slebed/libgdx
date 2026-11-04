package io.github.slebed.gdxvulkan.desktop;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Disposable;

/** The calls the application loop makes on the input (stock: Lwjgl3Input). */
public interface DesktopInputPolling extends Input, Disposable {
    void windowHandleChanged (long windowHandle);

    void update ();

    void prepareNext ();

    void resetPollingStates ();
}
