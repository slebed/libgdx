
package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.backend.vulkan.audio.VulkanAudio;

public interface VulkanApplicationBase extends Application {

	VulkanAudio createAudio (VulkanApplicationConfiguration config);

	VulkanInput createInput (VulkanWindow window);
}
