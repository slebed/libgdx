
package com.badlogic.gdx.backends.vulkan;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.backends.vulkan.audio.VulkanAudio;

public interface VulkanApplicationBase extends Application {

	VulkanAudio createAudio (VulkanApplicationConfiguration config);

	VulkanInput createInput (VulkanWindow window);
}
