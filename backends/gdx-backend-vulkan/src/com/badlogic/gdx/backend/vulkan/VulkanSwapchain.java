package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

// Disposable might be better than VkResource if it's a libGDX convention
public class VulkanSwapchain implements Disposable {
    private final String TAG = "VulkanSwapchain";
    private static final boolean debug = false;

    private final VulkanDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice rawDevice;
    private final long surface;
    private final long windowHandle;

    // --- Managed Vulkan Resources ---
    private long swapchain = VK_NULL_HANDLE;
    List<Long> swapchainFramebuffers; // VkFramebuffer handles (Owned)
    private List<Long> swapchainImages; // VkImage handles (Not owned, just references)
    private List<Long> swapchainImageViews; // VkImageView handles (Owned)
    private long renderPass = VK_NULL_HANDLE; // RenderPass compatible with swapchain format (Owned)

    // --- State ---
    private int imageFormat; // VkFormat enum value used
    private VkExtent2D extent; // VkExtent2D used for creation

    boolean needsRecreation = false; // Internal flag

    VulkanSwapchain(Builder builder, long swapchainHandle, List<Long> images, List<Long> imageViews, long renderPassHandle, List<Long> framebuffers, VkExtent2D chosenExtent, int chosenFormat) {

        this.device = Objects.requireNonNull(builder.device, "Device cannot be null");
        this.rawDevice = device.getRawDevice();
        this.physicalDevice = device.getPhysicalDevice(); // Get from device wrapper
        this.surface = builder.surface;
        this.windowHandle = builder.windowHandle;

        this.swapchain = swapchainHandle;
        this.swapchainImages = images; // Assign the list of image handles
        this.swapchainImageViews = imageViews;
        this.renderPass = renderPassHandle;
        this.swapchainFramebuffers = framebuffers;

        // Store a copy of the extent object
        this.extent = VkExtent2D.create().set(chosenExtent);
        this.imageFormat = chosenFormat;
    }

    public long getHandle() {
        return swapchain;
    }

    public VkExtent2D getExtent() {
        return extent;
    }

    public int getImageFormat() {
        return imageFormat;
    }

    public long getRenderPass() {
        return renderPass;
    }

    public int getImageCount() {
        return swapchainImages != null ? swapchainImages.size() : 0;
    }

    /**
     * Gets the list of VkImageView handles for the swapchain images.
     * Returns an unmodifiable view of the list to prevent external modification.
     *
     * @return An unmodifiable view of the list of image view handles,
     * or an immutable empty list if image views haven't been created yet.
     */
    public List<Long> getImageViews() {
        if (this.swapchainImageViews == null) {
            // This might happen if called before swapchain resources are fully created
            Gdx.app.error(TAG, "getImageViews() called but internal list is null!");
            return Collections.emptyList(); // Return immutable empty list
        }
        // Return an unmodifiable view to prevent accidental external modification
        return Collections.unmodifiableList(this.swapchainImageViews);
    }

    public long getFramebuffer(int index) {
        if (swapchainFramebuffers == null || index < 0 || index >= swapchainFramebuffers.size()) {
            // Log error or throw? Throwing is generally safer.
            throw new IndexOutOfBoundsException("Invalid index for swapchain framebuffer: " + index + ", Size=" + (swapchainFramebuffers != null ? swapchainFramebuffers.size() : "null"));
        }
        return swapchainFramebuffers.get(index);
    }

    public boolean needsRecreation() {
        return needsRecreation;
    }

    public void clearRecreationFlag() {
        this.needsRecreation = false;
    }

    /**
     * Acquires the next available image from the swapchain.
     *
     * @param signalSemaphore Semaphore to signal when the image is ready for rendering.
     * @param fence           Fence to signal (optional, typically VK_NULL_HANDLE when using semaphore).
     * @param pImageIndex     Output buffer (capacity 1) to store the acquired image index.
     * @return VkResult indicating success, suboptimal, out-of-date, etc.
     */
    public int acquireNextImage(long signalSemaphore, long fence, IntBuffer pImageIndex) {
        final long handleToCheck = this.swapchain; // Read into local var
        // if (debug) Gdx.app.log( TAG, "[Acquire] Checking handle: " + handleToCheck); // Log before check
        if (handleToCheck == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "[Acquire] PRE-CHECK FAILED: swapchain handle is VK_NULL_HANDLE. Flagging for recreation.");
            needsRecreation = true;
            return VK_ERROR_INITIALIZATION_FAILED;
        }
        // if (debug) Gdx.app.log( TAG, "[Acquire] PRE-NATIVE CALL: About to call vkAcquireNextImageKHR with handle: " +
        // handleToCheck); // Log right before native call

        if (swapchain == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Attempted to acquire image from null swapchain! Flagging for recreation.");
            needsRecreation = true;
            return VK_ERROR_INITIALIZATION_FAILED; // Or appropriate error
        }
        long timeout = 0xFFFFFFFFFFFFFFFFL; // UINT64_MAX

        int acquireResult = vkAcquireNextImageKHR(rawDevice, handleToCheck, timeout, signalSemaphore, fence, pImageIndex);
        // int acquireResult = vkAcquireNextImageKHR(rawDevice, swapchain, timeout, signalSemaphore, fence, pImageIndex);

        if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR || acquireResult == VK_SUBOPTIMAL_KHR) {
            needsRecreation = true; // Mark for recreation on next frame
            //if (debug) Gdx.app.log(TAG, "acquireNextImage result: " + VkResultDecoder.decode(acquireResult) + ". Flagged for recreation.");
        } else if (acquireResult != VK_SUCCESS) {
            Gdx.app.error(TAG, "Failed to acquire swap chain image! Result: " + VkResultDecoder.decode(acquireResult));
            // Consider throwing specific exceptions for critical errors like VK_ERROR_DEVICE_LOST
        }
        return acquireResult;
    }

    /**
     * Presents an image to the surface.
     *
     * @param presentQueue  The queue to use for presentation.
     * @param imageIndex    The index of the swapchain image to present.
     * @param waitSemaphore Semaphore to wait on before presentation can begin (signals render finished).
     * @return VkResult indicating success, suboptimal, out-of-date, etc.
     */
    public int present(VkQueue presentQueue, int imageIndex, long waitSemaphore) {
        if (swapchain == VK_NULL_HANDLE) {
            Gdx.app.error(TAG, "Attempted to present with null swapchain! Flagging for recreation.");
            needsRecreation = true;
            return VK_ERROR_INITIALIZATION_FAILED;
        }
        try (MemoryStack stack = stackPush()) {
            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(waitSemaphore))
                    .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(stack.ints(imageIndex)).pResults(null);

            int presentResult = vkQueuePresentKHR(presentQueue, presentInfo);

            if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR) {
                needsRecreation = true; // Mark for recreation on next frame
                if (debug) Gdx.app.log(TAG,                        "vkQueuePresentKHR result: " + VkResultDecoder.decode(presentResult) + ". Flagged for recreation.");
            } else if (presentResult != VK_SUCCESS) {
                Gdx.app.error(TAG, "Failed to present swap chain image! Result: " + VkResultDecoder.decode(presentResult));
                // Consider throwing specific exceptions
            }
            return presentResult;
        }
    }

    /**
     * Recreates the swapchain and dependent resources. IMPORTANT: Assumes vkDeviceWaitIdle() has been called externally before
     * calling this method.
     */
    public void recreate() {
        //if (debug) Gdx.app.log(TAG, "Recreating swapchain (VSync=" + this.vsyncEnabled + ")...");

        // 1. Cleanup existing resources (without waiting for idle again)
        cleanupInternal(false);

        // 2. Re-create everything using current state
        try {
            Builder.recreateInternal(this); // Pass 'this' to static recreate method
            needsRecreation = false; // Reset flag after successful recreation
            //if (debug) Gdx.app.log(TAG, "Swapchain recreation complete.");
        } catch (Exception e) {
            Gdx.app.error(TAG, "Failed during swapchain recreation", e);
            // Attempt cleanup again if recreation fails
            cleanupInternal(false);
            // Propagate the exception so the application knows recreation failed
            throw new GdxRuntimeException("Failed to recreate swapchain", e);
        }
    }

    @Override
    public void dispose() {
        if (debug) Gdx.app.log(TAG, "Disposing swapchain resources...");
        // Ensure device is idle before final cleanup
        cleanupInternal(true);
        if (debug) Gdx.app.log(TAG, "Swapchain resources disposed.");
    }

    /**
     * Cleans up all Vulkan resources owned by this instance.
     *
     * @param waitIdle If true, waits for device idle (use for final dispose).
     */
    private void cleanupInternal(boolean waitIdle) {
        if (waitIdle && rawDevice != null && swapchain != VK_NULL_HANDLE) {
            // Only wait if we actually have something potentially in flight
            //if (debug) Gdx.app.log(TAG, "Waiting for device idle before cleanup...");
            vkDeviceWaitIdle(rawDevice);
            //if (debug) Gdx.app.log(TAG, "Device idle.");
        }

        // Framebuffers
        if (swapchainFramebuffers != null) {
            for (long framebuffer : swapchainFramebuffers) {
                VkMemoryUtil.safeDestroyFramebuffer(framebuffer, rawDevice);
            }
            swapchainFramebuffers.clear();
            swapchainFramebuffers = null; // Help GC
        }

        // Render Pass
        VkMemoryUtil.safeDestroyRenderPass(renderPass, rawDevice);
        renderPass = VK_NULL_HANDLE;

        // Image Views
        if (swapchainImageViews != null) {
            for (long imageView : swapchainImageViews) {
                VkMemoryUtil.safeDestroyImageView(imageView, rawDevice);
            }
            swapchainImageViews.clear();
            swapchainImageViews = null; // Help GC
        }

        // Swapchain itself
        VkMemoryUtil.safeDestroySwapchain(swapchain, rawDevice);
        swapchain = VK_NULL_HANDLE;

        // Swapchain Images list (handles retrieved, not owned, just clear list)
        if (swapchainImages != null) {
            swapchainImages.clear();
            swapchainImages = null;
        }

        // Reset other state fields
        extent = null; // Allow GC
        imageFormat = VK_FORMAT_UNDEFINED;

        // Don't reset needsRecreation or vsyncEnabled here, they persist
    }

    // --- Builder Class ---
    public static class Builder {
        private VulkanDevice device;
        private long surface = VK_NULL_HANDLE;
        private long windowHandle = 0;
        private VulkanApplicationConfiguration config; // To get initial vsync preference
        private VulkanWindowConfiguration windowConfig;

        // Chainable setters
        public Builder device(VulkanDevice device) {
            this.device = device;
            return this;
        }

        public Builder surface(long surface) {
            this.surface = surface;
            return this;
        }

        public Builder windowHandle(long windowHandle) {
            this.windowHandle = windowHandle;
            return this;
        }

        public Builder configuration(VulkanApplicationConfiguration config) {
            this.config = config;
            return this;
        }

        public Builder configuration(VulkanWindowConfiguration config) { // Accept VulkanWindowConfiguration
            this.windowConfig = config;
            return this;
        }

        /**
         * Builds the initial VulkanSwapchain instance.
         */
        public VulkanSwapchain build() {
            validate();
            try {
                return createSwapchainResources(this, this.windowConfig, VK_NULL_HANDLE);
            } catch (Exception e) {
                Gdx.app.error("VulkanSwapchain.Builder", "Failed to build initial swapchain", e);
                // Don't leave partially created resources around if build fails
                throw new GdxRuntimeException("Failed to build initial swapchain", e);
            }
        }

        private void validate() {
            Objects.requireNonNull(device, "VulkanDevice must be set.");
            if (surface == VK_NULL_HANDLE) throw new IllegalStateException("Surface must be set.");
            if (windowHandle == 0) throw new IllegalStateException("Window handle must be set.");
            //Objects.requireNonNull(config, "VulkanApplicationConfiguration must be set.");
            Objects.requireNonNull(windowConfig, "VulkanWindowConfiguration must be set.");
            Objects.requireNonNull(device.getPhysicalDevice(), "PhysicalDevice missing from VulkanDevice."); // Check internal
            // consistency
        }

        /**
         * Internal static method called by recreate(). This performs the core resource creation logic using the state from the
         * existing swapchain instance.
         */
        private static void recreateInternal(VulkanSwapchain instance) {
            VulkanWindow window = ((VulkanApplication) Gdx.app).getWindow(instance.windowHandle);
            if (window == null) {
                throw new GdxRuntimeException("Cannot find VulkanWindow instance during swapchain recreation to get config.");
            }
            VulkanWindowConfiguration windowConf = window.getConfig();
            if (windowConf == null) {
                throw new GdxRuntimeException("VulkanWindowConfiguration is missing during swapchain recreation.");
            }
            VulkanSwapchain recreated = createSwapchainResources(instance, windowConf, VK_NULL_HANDLE);

            // Transfer newly created resources back to the original instance
            instance.swapchain = recreated.swapchain;
            instance.swapchainImages = recreated.swapchainImages;
            instance.swapchainImageViews = recreated.swapchainImageViews;
            instance.renderPass = recreated.renderPass;
            instance.swapchainFramebuffers = recreated.swapchainFramebuffers;
            instance.extent = recreated.extent;
            instance.imageFormat = recreated.imageFormat;
            // Keep instance.vsyncEnabled as it was
            instance.needsRecreation = false; // Mark as successfully recreated
        }

        /**
         * Core logic to query support, choose formats/modes, and create all Vulkan resources. Can be called from build() or
         * recreateInternal().
         *
         * @param source             Provides dependencies (device, surface, windowHandle). Can be Builder or VulkanSwapchain instance.
         * @param oldSwapchainHandle Handle to the old swapchain (VK_NULL_HANDLE if initial creation or not reusing).
         * @return A new VulkanSwapchain instance containing all created resources.
         */
        private static VulkanSwapchain createSwapchainResources(Object source, VulkanWindowConfiguration windowConf, long oldSwapchainHandle) {

            // Extract dependencies from source object
            VulkanDevice dev;
            long surf;
            long windowHnd;
            VkPhysicalDevice physDev;
            //VulkanWindowConfiguration windowConf = null;

            if (source instanceof Builder) {
                Builder builder = (Builder) source;
                dev = builder.device;
                surf = builder.surface;
                windowHnd = builder.windowHandle;
                physDev = dev.getPhysicalDevice();
                windowConf = builder.windowConfig;
            } else if (source instanceof VulkanSwapchain) {
                VulkanSwapchain instance = (VulkanSwapchain) source;
                dev = instance.device;
                surf = instance.surface;
                windowHnd = instance.windowHandle;
                physDev = instance.physicalDevice;
                VulkanWindow window = ((VulkanApplication) Gdx.app).getWindow(windowHnd); // Get window
                if (window != null) {
                    windowConf = window.getConfig(); // Get config from window
                } else {
                    throw new GdxRuntimeException("Cannot find VulkanWindow instance during swapchain recreation to get config.");
                }
            } else {
                throw new IllegalArgumentException("Source must be Builder or VulkanSwapchain");
            }

            if (windowConf == null) {
                throw new GdxRuntimeException("VulkanWindowConfiguration is missing for swapchain creation/recreation.");
            }

            VkDevice rawDev = dev.getRawDevice();

            // --- Resource Creation Logic (using try-with-resources for stack) ---
            try (MemoryStack stack = stackPush()) {
                IntBuffer pWidthCheck = stack.mallocInt(1);
                IntBuffer pHeightCheck = stack.mallocInt(1);
                GLFW.glfwGetFramebufferSize(windowHnd, pWidthCheck, pHeightCheck);
                if (pWidthCheck.get(0) == 0 || pHeightCheck.get(0) == 0) {
                    //if (debug) Gdx.app.log("VulkanSwapchain.Builder", "Skipping resource creation for zero size window.");
                    throw new GdxRuntimeException("Cannot create swapchain for zero-sized window.");
                }

                // --- Query Surface Capabilities ---
                VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
                vkCheck(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physDev, surf, caps), "Failed to get surface capabilities");

                // --- Query Supported Formats ---
                IntBuffer formatCount = stack.mallocInt(1);
                vkCheck(vkGetPhysicalDeviceSurfaceFormatsKHR(physDev, surf, formatCount, null), "Failed query formats count");
                if (formatCount.get(0) == 0) throw new GdxRuntimeException("No surface formats supported!");
                VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(formatCount.get(0), stack);
                vkCheck(vkGetPhysicalDeviceSurfaceFormatsKHR(physDev, surf, formatCount, formats), "Failed query formats");

                // --- Query Supported Present Modes ---
                IntBuffer presentModeCount = stack.mallocInt(1); // Buffer for count output

                // ---> Call 1: Get the count ONLY <---
                vkCheck(vkGetPhysicalDeviceSurfacePresentModesKHR(physDev, surf, presentModeCount, null), "Failed query present modes count");

                int count = presentModeCount.get(0); // Read the count *after* the Vulkan call
                //if (debug) Gdx.app.log("VulkanSwapchain.Builder", "Reported present mode count: " + count); // Log the *actual* count

                // ---> Validation <---
                if (count <= 0) {
                    // If count is 0 or negative, something is wrong.
                    throw new GdxRuntimeException("No present modes supported or invalid count received! Count: " + count);
                }
                // Optional: Add an upper sanity limit if desired, e.g., if (count > 20) { ... }

                // ---> Allocate ONE buffer for the modes using the validated count <---
                IntBuffer availablePresentModes = stack.mallocInt(count); // Allocate on stack

                // ---> Call 2: Fill the allocated buffer <---
                // Pass presentModeCount again; Vulkan ignores its input value here and uses it for output confirmation if needed.
                // The primary purpose now is to provide the 'availablePresentModes' buffer to be filled.
                vkCheck(vkGetPhysicalDeviceSurfacePresentModesKHR(physDev, surf, presentModeCount, availablePresentModes), "Failed query present modes (filling buffer)");
                // availablePresentModes now contains 'count' valid VkPresentModeKHR values

                // --- Choose Swapchain Settings ---
                VkSurfaceFormatKHR chosenFormat = chooseSwapSurfaceFormatInternal(formats);

                // ---> Use the correctly allocated and filled 'availablePresentModes' buffer <---
                int chosenPresentMode = chooseSwapPresentMode(availablePresentModes, windowConf.presentMode);


                VkExtent2D chosenExtent = chooseSwapExtentInternal(caps, windowHnd); // Pass handle for query

                int imageCount = caps.minImageCount() + 1; // Request one more than minimum for buffering
                if (caps.maxImageCount() > 0 && imageCount > caps.maxImageCount()) {
                    imageCount = caps.maxImageCount(); // Clamp to max if defined
                }
                if (debug) Gdx.app.log("VulkanSwapchain.Builder", "Requesting " + imageCount + " swapchain images.");

                // --- Create Swapchain ---
                VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                        .sType$Default()
                        .surface(surf)
                        .minImageCount(imageCount).imageFormat(chosenFormat.format()).imageColorSpace(chosenFormat.colorSpace())
                        .imageExtent(chosenExtent)
                        .imageArrayLayers(1).imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                        .preTransform(caps.currentTransform()).compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                        .presentMode(chosenPresentMode)
                        .clipped(true)
                        .oldSwapchain(oldSwapchainHandle);

                // Handle queue family indices (simplified)
                // TODO: Add proper concurrent sharing mode if graphics/present queues differ
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);

                LongBuffer pSwapchain = stack.mallocLong(1);
                vkCheck(vkCreateSwapchainKHR(rawDev, createInfo, null, pSwapchain), "Failed to create swap chain");
                long swapchainHandle = pSwapchain.get(0);

                if (swapchainHandle == VK_NULL_HANDLE) {
                    String errorMsg = "FATAL: vkCreateSwapchainKHR returned VK_NULL_HANDLE! (vkCheck might have passed)";
                    Gdx.app.error("VulkanSwapchain.Builder", errorMsg);
                    // Clean up anything created *before* the swapchain if necessary
                    throw new GdxRuntimeException(errorMsg);
                }

                //if (debug) Gdx.app.log("VulkanSwapchain.Builder", "[CreateResources] vkCreateSwapchainKHR returned valid handle: " + swapchainHandle);

                // --- Get Swapchain Images ---
                IntBuffer actualImageCountBuf = stack.mallocInt(1);
                vkCheck(vkGetSwapchainImagesKHR(rawDev, swapchainHandle, actualImageCountBuf, null), "Failed get swapchain image count");
                int actualImageCount = actualImageCountBuf.get(0);
                LongBuffer pImages = stack.mallocLong(actualImageCount);
                vkCheck(vkGetSwapchainImagesKHR(rawDev, swapchainHandle, actualImageCountBuf, pImages), "Failed get swapchain images");

                List<Long> images = new ArrayList<>(actualImageCount);
                for (int i = 0; i < actualImageCount; i++) {
                    images.add(pImages.get(i));
                }

                // --- Create Image Views ---
                List<Long> imageViews = createImageViewsInternal(rawDev, images, chosenFormat.format());

                // --- Create Render Pass ---
                long renderPassHandle = createRenderPassInternal(rawDev, chosenFormat.format());

                // --- Create Framebuffers ---
                List<Long> framebuffers = createFramebuffersInternal(rawDev, imageViews, renderPassHandle, chosenExtent);

                if (debug)
                    Gdx.app.log("VulkanSwapchain.Builder", "Created swapchain resources. PresentMode: " + VkResultDecoder.decodePresentMode(chosenPresentMode) + ", Format: " + chosenFormat.format() + ", Extent: " + chosenExtent.width() + "x" + chosenExtent.height()); // Updated log
                // --- Construct VulkanSwapchain Instance ---
                // Use a dummy builder just to pass dependencies if called from recreate
                Builder dummyBuilder = (source instanceof Builder) ? (Builder) source
                        : new Builder().device(dev).surface(surf).windowHandle(windowHnd)
                        .configuration(new VulkanApplicationConfiguration()); // Need config? Pass null?

                return new VulkanSwapchain(dummyBuilder, // Pass original builder or dummy
                        swapchainHandle, images, imageViews, renderPassHandle, framebuffers, chosenExtent, chosenFormat.format());
            }
        }

        private static int chooseSwapPresentMode(IntBuffer availablePresentModes, VulkanApplicationConfiguration.SwapchainPresentMode desiredMode) {
            //if (debug) Gdx.app.log("VulkanSwapchain", "Choosing present mode. Desired: " + desiredMode);

            int desiredVkMode;
            switch (desiredMode) {
                case IMMEDIATE:
                    desiredVkMode = VK_PRESENT_MODE_IMMEDIATE_KHR;
                    break;
                case MAILBOX:
                    desiredVkMode = VK_PRESENT_MODE_MAILBOX_KHR;
                    break;
                case FIFO_RELAXED:
                    desiredVkMode = VK_PRESENT_MODE_FIFO_RELAXED_KHR;
                    break;
                case FIFO:
                default: // Fallback to FIFO
                    desiredVkMode = VK_PRESENT_MODE_FIFO_KHR;
                    break;
            }

            if (debug) { // Assuming 'debug' is a static boolean you can set
                StringBuilder modes = new StringBuilder("Available Present Modes: ");
                for (int i = 0; i < availablePresentModes.limit(); i++) {
                    modes.append(VkResultDecoder.decodePresentMode(availablePresentModes.get(i))).append(" (").append(availablePresentModes.get(i)).append(") ");
                }
                Gdx.app.log("VulkanSwapchain", modes.toString());
                Gdx.app.log("VulkanSwapchain", "Desired LibGDX Mode: " + desiredMode + ", maps to VkMode: " + desiredVkMode);
            }


            // Check if the desired mode is available
            boolean desiredAvailable = false;
            for (int i = 0; i < availablePresentModes.limit(); i++) {
                if (availablePresentModes.get(i) == desiredVkMode) {
                    desiredAvailable = true;
                    break;
                }
            }

            if (desiredAvailable) {
                //if (debug) Gdx.app.log("VulkanSwapchain", "Desired present mode available: " + desiredVkMode);
                return desiredVkMode;
            }

            // If desired isn't available, try preferred alternatives (e.g., Mailbox)
            // This part depends on your preference if the exact desired isn't found.
            // Example: Prefer Mailbox if available, otherwise fallback to FIFO.
            boolean mailboxAvailable = false;
            for (int i = 0; i < availablePresentModes.limit(); i++) {
                if (availablePresentModes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) {
                    mailboxAvailable = true;
                    break;
                }
            }
            if (mailboxAvailable && desiredMode != VulkanApplicationConfiguration.SwapchainPresentMode.MAILBOX) { // Only log if it wasn't the desired
                //if (debug) Gdx.app.log("VulkanSwapchain", "Desired mode (" + desiredMode + ") not available, falling back to available MAILBOX.");
                return VK_PRESENT_MODE_MAILBOX_KHR;
            }


            // Default guaranteed fallback: FIFO (VSync)
            //if (debug) Gdx.app.log("VulkanSwapchain", "Desired mode (" + desiredMode + ") not available, falling back to guaranteed FIFO.");
            return VK_PRESENT_MODE_FIFO_KHR;
        }

        private static VkSurfaceFormatKHR chooseSwapSurfaceFormatInternal(VkSurfaceFormatKHR.Buffer availableFormats) {
            for (VkSurfaceFormatKHR format : availableFormats) {
                if (format.format() == VK_FORMAT_B8G8R8A8_SRGB && format.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    //if (debug) Gdx.app.log("VulkanSwapchain.Builder", "Chosen format: B8G8R8A8_SRGB");
                    return format;
                }
            }
            VkSurfaceFormatKHR fallback = availableFormats.get(0);
            //if (debug) Gdx.app.log("VulkanSwapchain.Builder", "Chosen format: Fallback " + fallback.format());
            return fallback;
        }

        private static VkExtent2D chooseSwapExtentInternal(VkSurfaceCapabilitiesKHR capabilities, long windowHandle) {
            try (MemoryStack stack = stackPush()) {
                if (capabilities.currentExtent().width() != 0xFFFFFFFF) {
                    // Window manager dictates size, use it directly
                    // Return a copy to avoid issues with stack allocation of capabilities
                    return VkExtent2D.create().set(capabilities.currentExtent());
                } else {
                    // We can choose size within bounds, query current window framebuffer size
                    IntBuffer pWidth = stack.mallocInt(1);
                    IntBuffer pHeight = stack.mallocInt(1);
                    GLFW.glfwGetFramebufferSize(windowHandle, pWidth, pHeight);

                    // Create the extent object on the heap to return it safely
                    VkExtent2D actualExtent = VkExtent2D.create();
                    actualExtent
                            .width(clamp(pWidth.get(0), capabilities.minImageExtent().width(), capabilities.maxImageExtent().width()));
                    actualExtent
                            .height(clamp(pHeight.get(0), capabilities.minImageExtent().height(), capabilities.maxImageExtent().height()));
                    //if (debug) Gdx.app.log("VulkanSwapchain.Builder", "Chosen Extent (Clamped): " + actualExtent.width() + "x" + actualExtent.height());
                    return actualExtent;
                }
            }
        }

        // Simple clamp helper
        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static List<Long> createImageViewsInternal(VkDevice rawDevice, List<Long> images, int format) {
            List<Long> imageViews = new ArrayList<>(images.size());
            try (MemoryStack stack = stackPush()) {
                LongBuffer pImageView = stack.mallocLong(1);
                for (long image : images) {
                    VkImageViewCreateInfo createInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                            .viewType(VK_IMAGE_VIEW_TYPE_2D).format(format)
                            .components(c -> c.r(VK_COMPONENT_SWIZZLE_IDENTITY).g(VK_COMPONENT_SWIZZLE_IDENTITY)
                                    .b(VK_COMPONENT_SWIZZLE_IDENTITY).a(VK_COMPONENT_SWIZZLE_IDENTITY))
                            .subresourceRange(
                                    r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1));

                    vkCheck(vkCreateImageView(rawDevice, createInfo, null, pImageView), "Failed to create swapchain image view");
                    imageViews.add(pImageView.get(0));
                }
            }
            return imageViews;
        }

        private static long createRenderPassInternal(VkDevice rawDevice, int format) {
            try (MemoryStack stack = stackPush()) {
                VkAttachmentDescription.Buffer colorAttachment = VkAttachmentDescription.calloc(1, stack).format(format)
                        .samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

                VkAttachmentReference.Buffer colorAttachmentRef = VkAttachmentReference.calloc(1, stack).attachment(0)
                        .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

                VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                        .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(colorAttachmentRef);

                VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack).srcSubpass(VK_SUBPASS_EXTERNAL)
                        .dstSubpass(0).srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).srcAccessMask(0)
                        .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

                VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack).sType$Default()
                        .pAttachments(colorAttachment).pSubpasses(subpass).pDependencies(dependency);

                LongBuffer pRenderPass = stack.mallocLong(1);
                vkCheck(vkCreateRenderPass(rawDevice, renderPassInfo, null, pRenderPass), "Failed to create render pass");
                return pRenderPass.get(0);
            }
        }

        private static List<Long> createFramebuffersInternal(VkDevice rawDevice, List<Long> imageViews, long renderPass,
                                                             VkExtent2D extent) {
            List<Long> framebuffers = new ArrayList<>(imageViews.size());
            try (MemoryStack stack = stackPush()) {
                LongBuffer attachments = stack.mallocLong(1);
                LongBuffer pFramebuffer = stack.mallocLong(1);

                for (long imageView : imageViews) {
                    attachments.put(0, imageView);
                    VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack).sType$Default()
                            .renderPass(renderPass).pAttachments(attachments).width(extent.width()).height(extent.height()).layers(1);

                    vkCheck(vkCreateFramebuffer(rawDevice, framebufferInfo, null, pFramebuffer), "Failed to create framebuffer");
                    framebuffers.add(pFramebuffer.get(0));
                }
            }
            return framebuffers;
        }

    } // End Builder class
} // End VulkanSwapchain class
