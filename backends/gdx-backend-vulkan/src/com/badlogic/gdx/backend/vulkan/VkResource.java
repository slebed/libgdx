
package com.badlogic.gdx.backend.vulkan;

/** A simple interface to enforce consistent cleanup of Vulkan resources. All classes that manage Vulkan resources should
 * implement this interface. */
public interface VkResource {

	/** Cleans up all Vulkan resources associated with this object. Should be called when the resource is no longer needed. */
	void cleanup ();
}
