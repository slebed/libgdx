
package com.badlogic.gdx.backend.vulkan.tbd;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backend.vulkan.VkMemoryUtil;
import com.badlogic.gdx.backend.vulkan.VulkanDevice;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.LongBuffer;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Manages Vulkan synchronization primitives (Semaphores, Fences) required for controlling the frame rendering loop. (Initial
 * version supports 1 frame in flight). */
public class VulkanSyncManager implements Disposable {
	private final String logTag = "VulkanSyncManager";
	private final VulkanDevice device;
	private final VkDevice rawDevice;

	// Handles for 1 frame in flight
	private long imageAvailableSemaphore = VK_NULL_HANDLE;
	private long renderFinishedSemaphore = VK_NULL_HANDLE;
	private long inFlightFence = VK_NULL_HANDLE;

	/** Creates synchronization objects for single frame in flight rendering.
	 * @param device The VulkanDevice wrapper. */
	public VulkanSyncManager (VulkanDevice device) {
		this.device = device;
		this.rawDevice = device.getRawDevice();
		createSyncObjectsInternal();
		Gdx.app.log(logTag, "Initialized (1 frame in flight).");
	}

	/** Internal method to create the synchronization objects. */
	private void createSyncObjectsInternal () {
		Gdx.app.log(logTag, "Creating sync objects...");
		try (MemoryStack stack = stackPush()) {
			VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();

			// Create one fence, initially signaled so the first frame doesn't wait infinitely
			VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);

			LongBuffer pSemaphore = stack.mallocLong(1);
			LongBuffer pFence = stack.mallocLong(1);

			// Image Available Semaphore (Signaled by acquire, Waited by submit)
			vkCheck(vkCreateSemaphore(rawDevice, semaphoreInfo, null, pSemaphore), "Failed to create image available semaphore");
			imageAvailableSemaphore = pSemaphore.get(0);

			// Render Finished Semaphore (Signaled by submit, Waited by present)
			vkCheck(vkCreateSemaphore(rawDevice, semaphoreInfo, null, pSemaphore), "Failed to create render finished semaphore");
			renderFinishedSemaphore = pSemaphore.get(0);

			// In Flight Fence (Signaled by submit, Waited by CPU)
			vkCheck(vkCreateFence(rawDevice, fenceInfo, null, pFence), "Failed to create in-flight fence");
			inFlightFence = pFence.get(0);

			Gdx.app.log(logTag, "Sync objects created: ImgAvail=" + imageAvailableSemaphore + ", RenderFin="
				+ renderFinishedSemaphore + ", Fence=" + inFlightFence);

		} catch (Exception e) {
			// Cleanup if partially created
			dispose();
			throw new GdxRuntimeException("Failed to create synchronization objects", e);
		}
	}

	// --- Getters for the handles ---

	public long getImageAvailableSemaphore () {
		return imageAvailableSemaphore;
	}

	public long getRenderFinishedSemaphore () {
		return renderFinishedSemaphore;
	}

	public long getInFlightFence () {
		return inFlightFence;
	}

	/** Destroys all managed synchronization objects. */
	@Override
	public void dispose () {
		Gdx.app.log(logTag, "Disposing sync objects...");
		// Use safe destroy utils which handle null checks
		VkMemoryUtil.safeDestroySemaphore(imageAvailableSemaphore, rawDevice);
		VkMemoryUtil.safeDestroySemaphore(renderFinishedSemaphore, rawDevice);
		VkMemoryUtil.safeDestroyFence(inFlightFence, rawDevice);

		imageAvailableSemaphore = VK_NULL_HANDLE;
		renderFinishedSemaphore = VK_NULL_HANDLE;
		inFlightFence = VK_NULL_HANDLE;
		Gdx.app.log(logTag, "Sync objects disposed.");
	}
}
