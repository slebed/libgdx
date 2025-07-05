
package com.badlogic.gdx.backend.vulkan;

/** Convenience implementation of {@link VulkanWindowListener}. Derive from this class and only overwrite the methods you are
 * interested in.
 *
 * @author badlogic */
public class VulkanWindowAdapter implements VulkanWindowListener {
	@Override
	public void created (VulkanWindow window) {
	}

	@Override
	public void iconified (boolean isIconified) {
	}

	@Override
	public void maximized (boolean isMaximized) {
	}

	@Override
	public void focusLost () {
	}

	@Override
	public void focusGained () {
	}

	@Override
	public boolean closeRequested () {
		return true;
	}

	@Override
	public void filesDropped (String[] files) {
	}

	@Override
	public void refreshRequested () {
	}
}
