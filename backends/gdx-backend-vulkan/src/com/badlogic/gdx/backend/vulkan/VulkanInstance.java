
package com.badlogic.gdx.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public class VulkanInstance implements VkResource {

	private final VkInstanceHandle instanceHandle;

	VulkanInstance (VkInstanceHandle instanceHandle) {
		this.instanceHandle = instanceHandle;
	}

	public long getHandle () {
		return instanceHandle.address();
	}

	public org.lwjgl.vulkan.VkInstance getRawInstance () {
		return instanceHandle;
	}

	@Override
	public void cleanup () {
		VkMemoryUtil.safeDestroyInstance(instanceHandle);
	}

	public static class Builder {
		private String applicationName;
		private List<String> requiredExtensions = new ArrayList<>();
		private List<String> validationLayers = new ArrayList<>();
		private int apiVersion = VK_API_VERSION_1_2;;

		public Builder setApplicationName (String applicationName) {
			this.applicationName = applicationName;
			return this;
		}

		public Builder setRequiredExtensions (List<String> extensions) {
			this.requiredExtensions = extensions;
			return this;
		}

		public Builder setValidationLayers (List<String> layers) { // New method
			this.validationLayers = layers;
			return this;
		}

		public Builder setApiVersion (int applicationVersion) {
			this.apiVersion = applicationVersion;
			return this;
		}

		public VulkanInstance build () {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
					.pApplicationName(stack.UTF8(applicationName)).pEngineName(stack.UTF8("libGDX Vulkan Backend"))
					.apiVersion(apiVersion) // .apiVersion(VK_API_VERSION_1_0).engineVersion(VK_MAKE_VERSION(1, 0, 0))
					.applicationVersion(VK_MAKE_VERSION(1, 0, 0));

				PointerBuffer ppExtensions = stack.mallocPointer(requiredExtensions.size());
				for (String ext : requiredExtensions) {
					ppExtensions.put(stack.UTF8(ext));
				}
				ppExtensions.flip();

				VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
					.pApplicationInfo(appInfo).ppEnabledExtensionNames(ppExtensions);

				if (validationLayers != null && !validationLayers.isEmpty()) {
					PointerBuffer ppValidationLayers = stack.mallocPointer(validationLayers.size());
					for (String layer : validationLayers) {
						ppValidationLayers.put(stack.UTF8(layer));
					}
					ppValidationLayers.flip();
					ci.ppEnabledLayerNames(ppValidationLayers);
				}

				PointerBuffer pInstance = stack.mallocPointer(1);
				VkMemoryUtil.vkCheck(vkCreateInstance(ci, null, pInstance), "Failed to create Vulkan instance");

				return new VulkanInstance(new VkInstanceHandle(pInstance.get(0), ci));
			}
		}

	}

	private static class VkInstanceHandle extends org.lwjgl.vulkan.VkInstance {

		private VkInstanceHandle (long handle, VkInstanceCreateInfo ci) { // This matches the constructor requirement
			super(handle, ci); // Call the parent constructor with both handle and ci
		}
	}
}
