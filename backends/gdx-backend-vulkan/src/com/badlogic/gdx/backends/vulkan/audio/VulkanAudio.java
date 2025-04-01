
package com.badlogic.gdx.backends.vulkan.audio;

import com.badlogic.gdx.Audio;
import com.badlogic.gdx.utils.Disposable;

public interface VulkanAudio extends Audio, Disposable {

	void update ();
}
