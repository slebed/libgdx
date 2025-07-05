
package com.badlogic.gdx.backend.vulkan;

/** Interface for Vulkan resources that need preparation work at the beginning of a frame, typically after fence synchronization
 * but before command buffer recording begins. */
public interface VulkanFrameResourcePreparer {
	/** Called once per frame to prepare resources for the given frame index. This is the appropriate time to update descriptor
	 * sets, UBOs for the upcoming frame, etc.
	 *
	 * @param frameIndex The index of the frame about to be rendered (0 to MAX_FRAMES_IN_FLIGHT - 1). */
	void prepareResourcesForFrame (int frameIndex);
}
