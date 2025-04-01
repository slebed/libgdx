
package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Disposable;

public interface VulkanInput extends Input, Disposable {

	void windowHandleChanged (long windowHandle);

	void update ();

	void prepareNext ();

	void resetPollingStates ();
}
