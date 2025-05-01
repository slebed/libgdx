/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.backend.vulkan;

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkQueuePresentKHR;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_FENCE_CREATE_SIGNALED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUBPASS_CONTENTS_INLINE;
import static org.lwjgl.vulkan.VK10.VK_SUBPASS_EXTERNAL;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkAllocateCommandBuffers;
import static org.lwjgl.vulkan.VK10.vkBeginCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdBeginRenderPass;
import static org.lwjgl.vulkan.VK10.vkCmdEndRenderPass;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;
import static org.lwjgl.vulkan.VK10.vkCmdSetViewport;
import static org.lwjgl.vulkan.VK10.vkCreateCommandPool;
import static org.lwjgl.vulkan.VK10.vkCreateFence;
import static org.lwjgl.vulkan.VK10.vkCreateFramebuffer;
import static org.lwjgl.vulkan.VK10.vkCreateRenderPass;
import static org.lwjgl.vulkan.VK10.vkCreateSemaphore;
import static org.lwjgl.vulkan.VK10.vkDestroyCommandPool;
import static org.lwjgl.vulkan.VK10.vkDestroyFence;
import static org.lwjgl.vulkan.VK10.vkDestroyFramebuffer;
import static org.lwjgl.vulkan.VK10.vkDestroyRenderPass;
import static org.lwjgl.vulkan.VK10.vkDestroySemaphore;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkQueueSubmit;
import static org.lwjgl.vulkan.VK10.vkResetCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkResetFences;
import static org.lwjgl.vulkan.VK10.vkWaitForFences;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.*;

import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.Os;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWWindowCloseCallback;
import org.lwjgl.glfw.GLFWWindowFocusCallback;
import org.lwjgl.glfw.GLFWWindowIconifyCallback;
import org.lwjgl.glfw.GLFWWindowMaximizeCallback;
import org.lwjgl.glfw.GLFWWindowRefreshCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkViewport;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.SharedLibraryLoader;

public class VulkanWindow implements Disposable {
    final private String TAG = "VulkanWindow";
    private final VulkanGraphics vulkanGraphics;
    private long windowHandle;
    final ApplicationListener listener;
    private final Array<LifecycleListener> lifecycleListeners;
    final VulkanApplication application;
    private boolean listenerInitialized = false;
    VulkanWindowListener windowListener;
    private VulkanInput input;
    private final VulkanWindowConfiguration config; // This is the WINDOW config
    private final Array<Runnable> runnables = new Array<>();
    private final Array<Runnable> executedRunnables = new Array<>();
    private final IntBuffer tmpBuffer;
    private final IntBuffer tmpBuffer2;
    private long surface;
    boolean iconified = false;
    boolean focused = false;
    private boolean requestRendering = false;
    private boolean framebufferResized = false;

    private VulkanSwapchain swapchain = null;
    private long renderPass = VK_NULL_HANDLE; // VkRenderPass handle
    private List<Long> framebuffers = new ArrayList<>(); // List of VkFramebuffer handles
    private long commandPool = VK_NULL_HANDLE; // VkCommandPool handle
    private List<VkCommandBuffer> commandBuffers = new ArrayList<>(); // List of VkCommandBuffer objects/handles
    private List<Long> imageAvailableSemaphores = new ArrayList<>(); // List of VkSemaphore handles
    private List<Long> renderFinishedSemaphores = new ArrayList<>(); // List of VkSemaphore handles
    private List<Long> inFlightFences = new ArrayList<>(); // List of VkFence handles
    private int maxFramesInFlight = 1; // Example, configure as needed
    private int currentFrame = 0; // For sync object cycling


    private final GLFWWindowFocusCallback focusCallback = new GLFWWindowFocusCallback() {
        @Override
        public void invoke(long windowHandle, final boolean focused) {
            //Gdx.app.log(TAG, "Window " + this.hashCode() + " Focus changed");
            postRunnable(new Runnable() {
                @Override
                public void run() {
                    if (windowListener != null) {
                        if (focused) {
                            if (application.getAppConfig().pauseWhenLostFocus) {
                                synchronized (lifecycleListeners) {
                                    for (LifecycleListener lifecycleListener : lifecycleListeners) {
                                        lifecycleListener.resume();
                                    }
                                }
                            }
                            windowListener.focusGained();
                        } else {
                            windowListener.focusLost();
                            if (application.getAppConfig().pauseWhenLostFocus) {
                                synchronized (lifecycleListeners) {
                                    for (LifecycleListener lifecycleListener : lifecycleListeners) {
                                        lifecycleListener.pause();
                                    }
                                }
                                listener.pause();
                            }
                        }
                        VulkanWindow.this.focused = focused;
                    }
                }
            });
        }
    };

    private final GLFWWindowIconifyCallback iconifyCallback = new GLFWWindowIconifyCallback() {
        @Override
        public void invoke(long windowHandle, final boolean iconified) {
            postRunnable(new Runnable() {
                @Override
                public void run() {
                    if (windowListener != null) {
                        windowListener.iconified(iconified);
                    }
                    VulkanWindow.this.iconified = iconified;
                    if (iconified) {
                        if (application.getAppConfig().pauseWhenMinimized) {
                            synchronized (lifecycleListeners) {
                                for (LifecycleListener lifecycleListener : lifecycleListeners) {
                                    lifecycleListener.pause();
                                }
                            }
                            listener.pause();
                        }
                    } else {
                        if (application.getAppConfig().pauseWhenMinimized) {
                            synchronized (lifecycleListeners) {
                                for (LifecycleListener lifecycleListener : lifecycleListeners) {
                                    lifecycleListener.resume();
                                }
                            }
                            listener.resume();
                        }
                    }
                }
            });
        }
    };

    private final GLFWWindowMaximizeCallback maximizeCallback = new GLFWWindowMaximizeCallback() {
        @Override
        public void invoke(long windowHandle, final boolean maximized) {
            postRunnable(new Runnable() {
                @Override
                public void run() {
                    if (windowListener != null) {
                        windowListener.maximized(maximized);
                    }
                }
            });
        }

    };

    private final GLFWWindowCloseCallback closeCallback = new GLFWWindowCloseCallback() {
        @Override
        public void invoke(final long windowHandle) {
            postRunnable(new Runnable() {
                @Override
                public void run() {
                    if (windowListener != null) {
                        if (!windowListener.closeRequested()) {
                            GLFW.glfwSetWindowShouldClose(windowHandle, false);
                        }
                    }
                }
            });
        }
    };

    private final GLFWDropCallback dropCallback = new GLFWDropCallback() {
        @Override
        public void invoke(final long windowHandle, final int count, final long names) {
            final String[] files = new String[count];
            for (int i = 0; i < count; i++) {
                files[i] = getName(names, i);
            }
            postRunnable(new Runnable() {
                @Override
                public void run() {
                    if (windowListener != null) {
                        windowListener.filesDropped(files);
                    }
                }
            });
        }
    };

    private final GLFWWindowRefreshCallback refreshCallback = new GLFWWindowRefreshCallback() {
        @Override
        public void invoke(long windowHandle) {
            postRunnable(new Runnable() {
                @Override
                public void run() {
                    if (windowListener != null) {
                        windowListener.refreshRequested();
                    }
                }
            });
        }
    };

    private final GLFWFramebufferSizeCallback resizeCallback = new GLFWFramebufferSizeCallback() {
        @Override
        public void invoke(long windowHandle, int width, int height) {
            if (windowHandle == VulkanWindow.this.windowHandle) {
                //System.out.println("_____________________");
                //Gdx.app.log(TAG, "[" + VulkanWindow.this.hashCode() + "] Framebuffer resize callback invoked: " + width + "x" + height);
                VulkanWindow.this.framebufferResized = true;
            }
        }
    };

    VulkanWindow(ApplicationListener listener, Array<LifecycleListener> lifecycleListeners, VulkanWindowConfiguration config, VulkanApplication application, long surfaceHandle, VulkanGraphics primaryGraphics) {
        this.listener = listener;
        this.lifecycleListeners = lifecycleListeners;
        this.windowListener = config.windowListener;
        this.config = config;
        this.application = application;
        this.surface = surfaceHandle;
        this.tmpBuffer = BufferUtils.createIntBuffer(1);
        this.tmpBuffer2 = BufferUtils.createIntBuffer(1);
        this.vulkanGraphics = primaryGraphics;

        if (this.surface == VK_NULL_HANDLE) {
            throw new GdxRuntimeException("VulkanWindow created with VK_NULL_HANDLE surface!");
        }
        //Gdx.app.log(TAG, "[" + this.hashCode() + "] Constructor finished. Initial listenerInitialized=" + this.listenerInitialized + ", listener=" + this.listener);
    }

    void create(long windowHandle) {
        //Gdx.app.log(TAG, "[" + this.hashCode() + "] create() called for handle: " + windowHandle);
        if (windowHandle == 0) {
            Gdx.app.error(TAG, "create() called with invalid window handle!");
            throw new GdxRuntimeException("Cannot create VulkanWindow with invalid handle.");
        }
        this.windowHandle = windowHandle; // Store handle

        // Register non-input GLFW callbacks (including resize)
        GLFW.glfwSetWindowFocusCallback(windowHandle, focusCallback);
        GLFW.glfwSetWindowIconifyCallback(windowHandle, iconifyCallback);
        GLFW.glfwSetWindowMaximizeCallback(windowHandle, maximizeCallback);
        GLFW.glfwSetWindowCloseCallback(windowHandle, closeCallback);
        GLFW.glfwSetDropCallback(windowHandle, dropCallback);
        GLFW.glfwSetWindowRefreshCallback(windowHandle, refreshCallback);
        GLFW.glfwSetFramebufferSizeCallback(windowHandle, resizeCallback); // Register resize callback

        // Register input callbacks via the input handler (must be set BEFORE create is called)
        if (this.input != null) {
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Calling windowHandleChanged on input handler hash: " + this.input.hashCode());
            try {
                this.input.windowHandleChanged(this.windowHandle);
                //Gdx.app.log(TAG, "[" + this.hashCode() + "] Finished calling windowHandleChanged for handle " + this.windowHandle);
            } catch (Throwable t) {
                Gdx.app.error(TAG, "[" + this.hashCode() + "] Error calling windowHandleChanged!", t);
                // Decide if this is fatal
            }
        } else {
            // This should NOT happen if VulkanApplication order is correct
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Cannot set input callbacks during create(), input handler is null!");
        }

        //Gdx.app.log(TAG, "[" + this.hashCode() + "] Creating Vulkan resources...");

        try {
            VulkanDevice vulkanDevice = application.getVulkanDevice();
            // Ensure VulkanSwapchain.Builder accepts VulkanWindowConfiguration
            this.swapchain = new VulkanSwapchain.Builder()
                    .device(vulkanDevice)
                    .surface(this.surface)
                    .windowHandle(this.windowHandle)
                    .configuration(this.config) // Pass VulkanWindowConfiguration
                    .build();
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Swapchain created.");
        } catch (Exception e) {
            // Clean up surface if swapchain creation fails
            if (this.surface != VK_NULL_HANDLE && application.getVulkanInstance() != null) {
                try {
                    vkDestroySurfaceKHR(application.getVulkanInstance().getRawInstance(), this.surface, null);
                } catch (Exception cleanupEx) {
                    Gdx.app.error(TAG, "Error cleaning up surface after swapchain failure", cleanupEx);
                } finally {
                    this.surface = VK_NULL_HANDLE;
                }
            }
            throw new GdxRuntimeException("Swapchain creation failed", e);
        }

        this.maxFramesInFlight = application.getAppConfig().getMaxFramesInFlight();
        if (this.maxFramesInFlight < 1) { // Add validation just in case config had an invalid value
            Gdx.app.error(TAG, "Configuration maxFramesInFlight is invalid (" + this.maxFramesInFlight + "), defaulting to 1.");
            this.maxFramesInFlight = 1;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDevice device = application.getVulkanDevice().getRawDevice(); // Get raw device

            // --- Create Render Pass ---
            VkAttachmentDescription.Buffer colorAttachment = VkAttachmentDescription.calloc(1, stack).format(this.swapchain.getImageFormat()).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE).stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            VkAttachmentReference.Buffer colorAttachmentRef = VkAttachmentReference.calloc(1, stack).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(colorAttachmentRef.remaining())
                    .pColorAttachments(colorAttachmentRef);

            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0).srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).srcAccessMask(0).dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT).dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack).sType$Default().pAttachments(colorAttachment).pSubpasses(subpass).pDependencies(dependency);
            LongBuffer pRenderPass = stack.mallocLong(1);
            int rpErrCode = vkCreateRenderPass(device, renderPassInfo, null, pRenderPass);
            VkResult rpResult = VkResult.translate(rpErrCode);
            if (!rpResult.isSuccess()) { // Use helper method
                throw new GdxRuntimeException("Failed to create render pass: " + rpResult);
            }
            this.renderPass = pRenderPass.get(0);
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] RenderPass created: " + this.renderPass);


            // --- Create Framebuffers ---
            List<Long> swapChainImageViews = this.swapchain.getImageViews();
            VkExtent2D swapChainExtent = this.swapchain.getExtent();
            this.framebuffers = new ArrayList<>(swapChainImageViews.size());
            LongBuffer attachments = stack.mallocLong(1);
            LongBuffer pFramebuffer = stack.mallocLong(1);
            for (long imageView : swapChainImageViews) {
                attachments.put(0, imageView);
                VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack).sType$Default().renderPass(this.renderPass).pAttachments(attachments).width(swapChainExtent.width()).height(swapChainExtent.height()).layers(1);
                int fbErrCode = vkCreateFramebuffer(device, framebufferInfo, null, pFramebuffer);
                VkResult fbResult = VkResult.translate(fbErrCode);
                if (!fbResult.isSuccess()) {
                    throw new GdxRuntimeException("Failed to create framebuffer: " + fbResult);
                }
                this.framebuffers.add(pFramebuffer.get(0));
            }
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Framebuffers created (" + this.framebuffers.size() + ")");


            // --- Create Command Pool ---
            int graphicsQueueFamily = application.getGraphicsQueueFamily();
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default().flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(graphicsQueueFamily);
            LongBuffer pCommandPool = stack.mallocLong(1);
            int cpErrCode = vkCreateCommandPool(device, poolInfo, null, pCommandPool);
            VkResult cpResult = VkResult.translate(cpErrCode);
            if (!cpResult.isSuccess()) {
                throw new GdxRuntimeException("Failed to create command pool: " + cpResult);
            }
            this.commandPool = pCommandPool.get(0);
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Command Pool created: " + this.commandPool);

            //this.maxFramesInFlight = Math.min(this.swapchain.getImageCount(), 2); // Use swapchain count or fixed
            this.commandBuffers = new ArrayList<>(maxFramesInFlight);
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(this.commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(maxFramesInFlight);
            PointerBuffer pCommandBuffers = stack.mallocPointer(maxFramesInFlight);
            int cbErrCode = vkAllocateCommandBuffers(device, allocInfo, pCommandBuffers);
            VkResult cbResult = VkResult.translate(cbErrCode);
            if (!cbResult.isSuccess()) {
                throw new GdxRuntimeException("Failed to allocate command buffers: " + cbResult);
            }
            for (int i = 0; i < maxFramesInFlight; i++) {
                this.commandBuffers.add(new VkCommandBuffer(pCommandBuffers.get(i), device));
            }
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Command Buffers allocated (" + this.commandBuffers.size() + ")");

            // --- Create Synchronization Objects ---
            this.imageAvailableSemaphores = new ArrayList<>(maxFramesInFlight);
            this.renderFinishedSemaphores = new ArrayList<>(maxFramesInFlight);
            this.inFlightFences = new ArrayList<>(maxFramesInFlight);
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer pSemaphore = stack.mallocLong(1);
            LongBuffer pFence = stack.mallocLong(1);

            vkCheck(vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore), "Failed to create imageAvailable semaphore");
            long imgAvailSemHandle = pSemaphore.get(0); // Get handle
            imageAvailableSemaphores.add(imgAvailSemHandle);
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Created imageAvailableSemaphore[0]: " + imgAvailSemHandle + " (Hex: " + Long.toHexString(imgAvailSemHandle) + ")"); // <<< LOG HEX TOO

            vkCheck(vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore), "Failed to create renderFinished semaphore");
            long rndFinSemHandle = pSemaphore.get(0); // Get handle
            renderFinishedSemaphores.add(rndFinSemHandle);
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Created renderFinishedSemaphore[0]: " + rndFinSemHandle + " (Hex: " + Long.toHexString(rndFinSemHandle) + ")"); // <<< LOG HEX TOO

            vkCheck(vkCreateFence(device, fenceInfo, null, pFence), "Failed to create inFlight fence");
            long fenceHandle = pFence.get(0); // Get handle
            inFlightFences.add(fenceHandle);
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Created inFlightFence[0]: " + fenceHandle + " (Hex: " + Long.toHexString(fenceHandle) + ")"); // <<< LOG HEX TOO

            for (int i = 0; i < maxFramesInFlight; i++) {
                int semErrCode1 = vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore);
                VkResult semResult1 = VkResult.translate(semErrCode1);
                if (!semResult1.isSuccess()) throw new GdxRuntimeException("Failed to create imageAvailable semaphore: " + semResult1);
                imageAvailableSemaphores.add(pSemaphore.get(0));

                int semErrCode2 = vkCreateSemaphore(device, semaphoreInfo, null, pSemaphore);
                VkResult semResult2 = VkResult.translate(semErrCode2);
                if (!semResult2.isSuccess()) throw new GdxRuntimeException("Failed to create renderFinished semaphore: " + semResult2);
                renderFinishedSemaphores.add(pSemaphore.get(0));

                int fenceErrCode = vkCreateFence(device, fenceInfo, null, pFence);
                VkResult fenceResult = VkResult.translate(fenceErrCode);
                if (!fenceResult.isSuccess()) throw new GdxRuntimeException("Failed to create inFlight fence: " + fenceResult);
                inFlightFences.add(pFence.get(0));
            }
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Sync objects created (" + maxFramesInFlight + " frames in flight)");

        } catch (Exception e) {
            disposeVulkanResources(); // Clean up potentially created resources
            throw new GdxRuntimeException("Failed during Vulkan resource creation (Step 3) for window " + windowHandle, e);
        }

        if (windowListener != null) {
            windowListener.created(this);
        }
        //Gdx.app.log(TAG, "[" + this.hashCode() + "] create() finished for handle: " + windowHandle);
    }

    /**
     * @return the {@link ApplicationListener} associated with this window
     **/
    public ApplicationListener getListener() {
        return listener;
    }

    /**
     * @return the {@link VulkanWindowListener} set on this window
     **/
    public VulkanWindowListener getWindowListener() {
        return windowListener;
    }

    public void setWindowListener(VulkanWindowListener listener) {
        this.windowListener = listener;
    }

    /**
     * Post a {@link Runnable} to this window's event queue. Use this if you access statics like {@link Gdx#graphics} in your
     * runnable instead of {@link Application#postRunnable(Runnable)}.
     */
    public void postRunnable(Runnable runnable) {
        synchronized (runnables) {
            runnables.add(runnable);
        }
    }

    /**
     * Sets the position of the window in logical coordinates. All monitors span a virtual surface together. The coordinates are
     * relative to the first monitor in the virtual surface.
     **/
    public void setPosition(int x, int y) {
        if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) return;
        GLFW.glfwSetWindowPos(windowHandle, x, y);
    }

    /**
     * @return the window position in logical coordinates. All monitors span a virtual surface together. The coordinates are
     * relative to the first monitor in the virtual surface.
     **/
    public int getPositionX() {
        GLFW.glfwGetWindowPos(windowHandle, tmpBuffer, tmpBuffer2);
        return tmpBuffer.get(0);
    }

    /**
     * @return the window position in logical coordinates. All monitors span a virtual surface together. The coordinates are
     * relative to the first monitor in the virtual surface.
     **/
    public int getPositionY() {
        GLFW.glfwGetWindowPos(windowHandle, tmpBuffer, tmpBuffer2);
        return tmpBuffer2.get(0);
    }

    /**
     * Sets the visibility of the window. Invisible windows will still call their {@link ApplicationListener}
     */
    public void setVisible(boolean visible) {
        System.out.println("[VulkanWindow] setVisible called with: " + visible);
        if (visible) {
            System.out.println("[VulkanWindow] Attempting glfwShowWindow...");
            GLFW.glfwShowWindow(windowHandle);
            System.out.println("[VulkanWindow] glfwShowWindow call finished.");
        } else {
            GLFW.glfwHideWindow(windowHandle);
            System.out.println("[VulkanWindow] glfwHideWindow call finished.");
        }
    }

    /**
     * Closes this window and pauses and disposes the associated {@link ApplicationListener}.
     */
    public void closeWindow() {
        GLFW.glfwSetWindowShouldClose(windowHandle, true);
    }

    /**
     * Minimizes (iconifies) the window. Iconified windows do not call their {@link ApplicationListener} until the window is
     * restored.
     */
    public void iconifyWindow() {
        GLFW.glfwIconifyWindow(windowHandle);
    }

    /**
     * Whether the window is iconfieid
     */
    public boolean isIconified() {
        return iconified;
    }

    /**
     * De-minimizes (de-iconifies) and de-maximizes the window.
     */
    public void restoreWindow() {
        GLFW.glfwRestoreWindow(windowHandle);
    }

    /**
     * Maximizes the window.
     */
    public void maximizeWindow() {
        GLFW.glfwMaximizeWindow(windowHandle);
    }

    /**
     * Brings the window to front and sets input focus. The window should already be visible and not iconified.
     */
    public void focusWindow() {
        GLFW.glfwFocusWindow(windowHandle);
    }

    public boolean isFocused() {
        return focused;
    }

    /**
     * Sets the icon that will be used in the window's title bar. Has no effect in macOS, which doesn't use window icons.
     *
     * @param image One or more images. The one closest to the system's desired size will be scaled. Good sizes include 16x16,
     *              32x32 and 48x48. Pixmap format {@link Pixmap.Format#RGBA8888 RGBA8888} is preferred so
     *              the images will not have to be copied and converted. The chosen image is copied, and the provided Pixmaps are not
     *              disposed.
     */
    public void setIcon(Pixmap... image) {
        setIcon(windowHandle, image);
    }

    static void setIcon(long windowHandle, String[] imagePaths, Files.FileType imageFileType) {
        if (SharedLibraryLoader.os == Os.MacOsX) return;

        Pixmap[] pixmaps = new Pixmap[imagePaths.length];
        for (int i = 0; i < imagePaths.length; i++) {
            pixmaps[i] = new Pixmap(Gdx.files.getFileHandle(imagePaths[i], imageFileType));
        }

        setIcon(windowHandle, pixmaps);

        for (Pixmap pixmap : pixmaps) {
            pixmap.dispose();
        }
    }

    static void setIcon(long windowHandle, Pixmap[] images) {
        if (SharedLibraryLoader.os == Os.MacOsX) return;
        if (GLFW.glfwGetPlatform() == GLFW.GLFW_PLATFORM_WAYLAND) return;

        GLFWImage.Buffer buffer = GLFWImage.malloc(images.length);
        Pixmap[] tmpPixmaps = new Pixmap[images.length];

        for (int i = 0; i < images.length; i++) {
            Pixmap pixmap = images[i];

            if (pixmap.getFormat() != Pixmap.Format.RGBA8888) {
                Pixmap rgba = new Pixmap(pixmap.getWidth(), pixmap.getHeight(), Pixmap.Format.RGBA8888);
                rgba.setBlending(Pixmap.Blending.None);
                rgba.drawPixmap(pixmap, 0, 0);
                tmpPixmaps[i] = rgba;
                pixmap = rgba;
            }

            GLFWImage icon = GLFWImage.malloc();
            icon.set(pixmap.getWidth(), pixmap.getHeight(), pixmap.getPixels());
            buffer.put(icon);

            icon.free();
        }

        buffer.position(0);
        GLFW.glfwSetWindowIcon(windowHandle, buffer);

        buffer.free();
        for (Pixmap pixmap : tmpPixmaps) {
            if (pixmap != null) {
                pixmap.dispose();
            }
        }
    }

    public void resizeFrameBuffer(boolean b) {
        framebufferResized = b;
    }

    public void setTitle(CharSequence title) {
        GLFW.glfwSetWindowTitle(windowHandle, title);
    }

    /**
     * Sets minimum and maximum size limits for the window. If the window is full screen or not resizable, these limits are
     * ignored. Use -1 to indicate an unrestricted dimension.
     */
    public void setSizeLimits(int minWidth, int minHeight, int maxWidth, int maxHeight) {
        setSizeLimits(windowHandle, minWidth, minHeight, maxWidth, maxHeight);
    }

    static void setSizeLimits(long windowHandle, int minWidth, int minHeight, int maxWidth, int maxHeight) {
        GLFW.glfwSetWindowSizeLimits(windowHandle, minWidth > -1 ? minWidth : GLFW.GLFW_DONT_CARE,
                minHeight > -1 ? minHeight : GLFW.GLFW_DONT_CARE, maxWidth > -1 ? maxWidth : GLFW.GLFW_DONT_CARE,
                maxHeight > -1 ? maxHeight : GLFW.GLFW_DONT_CARE);
    }

    VulkanGraphics getGraphics() {
        return (VulkanGraphics) Gdx.graphics;
    }

    VulkanInput getInput() {
        return input;
    }

    public long getWindowHandle() {
        return windowHandle;
    }

    /**
     * @return The width of the window's client area in logical coordinates.
     */
    public int getLogicalWidth() {
        GLFW.glfwGetWindowSize(windowHandle, tmpBuffer, tmpBuffer2);
        return tmpBuffer.get(0);
    }

    /**
     * @return The height of the window's client area in logical coordinates.
     */
    public int getLogicalHeight() {
        GLFW.glfwGetWindowSize(windowHandle, tmpBuffer, tmpBuffer2);
        return tmpBuffer2.get(0);
    }

    /**
     * @return The width of the window's framebuffer in physical pixels.
     */
    public int getBackBufferWidth() {
        GLFW.glfwGetFramebufferSize(windowHandle, tmpBuffer, tmpBuffer2);
        return tmpBuffer.get(0);
    }

    /**
     * @return The height of the window's framebuffer in physical pixels.
     */
    public int getBackBufferHeight() {
        GLFW.glfwGetFramebufferSize(windowHandle, tmpBuffer, tmpBuffer2);
        return tmpBuffer2.get(0);
    }

    /*void windowHandleChanged(long windowHandle) {
        this.windowHandle = windowHandle;
        input.windowHandleChanged(windowHandle);
    }
*/

    /**
     * Performs a single update step, processing input, runnables, and rendering a frame if needed.
     * This version delegates core Vulkan frame lifecycle management to VulkanGraphics.
     *
     * @return True if rendering occurred, false otherwise.
     */
    /**
     * Updates the window's state, processes input and runnables,
     * and performs the Vulkan rendering sequence for this window's frame.
     *
     * @return true if rendering occurred, false otherwise.
     */
    boolean update() {
        //Gdx.app.log(TAG, "[" + this.hashCode() + "] update() called. listenerInitialized=" + this.listenerInitialized + ", listener=" + this.listener); // <-- ADD THIS

        if (!listenerInitialized && listener != null) {
            ensureListenerCreatedAndResized(); // Call helper method
        }
        //Gdx.app.log(TAG, "VulkanWindow update() called.");
        // 1. Initialize Listener if not already done
        // Ensures listener.create() and initial listener.resize() are called once.
//        if (!listenerInitialized) {
//            initializeListener();
//        }

        // 2. Process Input for this window
        // Drains the GLFW event queue via VulkanApplication.loop -> glfwPollEvents()
        // This update call drains the internal InputEventQueue to the listener.
        if (!iconified && this.input != null) {
            this.input.update();      // Process events (touchDown, keyTyped etc.)
            this.input.prepareNext(); // Reset polling states (isKeyPressed etc.)
        } else if (!iconified) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Cannot update input, this.input is null!");
        }

        // 3. Process Runnables posted via Gdx.app.postRunnable()
        synchronized (runnables) {
            if (runnables.size > 0) {
                executedRunnables.clear();
                executedRunnables.addAll(runnables);
                runnables.clear();
            }
        }
        for (Runnable runnable : executedRunnables) {
            try {
                runnable.run();
            } catch (Throwable t) {
                Gdx.app.error(TAG, "Exception in runnable", t);
            }
        }
        // Determine if rendering is needed based on runnables or continuous rendering setting
        VulkanGraphics gfx = getGraphics(); // Get graphics instance early
        boolean shouldRender = executedRunnables.size > 0 || (gfx != null && gfx.isContinuousRendering());
        boolean continuous = (gfx != null && gfx.isContinuousRendering());
        executedRunnables.clear();
        //Gdx.app.log(TAG, "update() called. shouldRender=" + shouldRender + ", continuous=" + continuous + ", requestRendering=" + requestRendering + ", iconified=" + iconified);
        boolean renderingRequested = false;
        synchronized (this) {
            renderingRequested = requestRendering;
            shouldRender |= renderingRequested && !iconified;
            requestRendering = false; // Consume the request
        }

        // Check if the framebuffer was resized (flag set by GLFW callback)
        if (framebufferResized) {
            shouldRender = true; // Force render if resized to handle recreation
        }

        // --- Early Exit Checks ---
        if (!shouldRender || iconified || swapchain == null || gfx == null) {
            return false; // No rendering needed or possible this iteration
        }

        try {
            // ADDED: Log entry into pre-render checks
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Pre-Render Checks Starting for frame " + currentFrame + "...");

            // Existing resource check
            if (commandBuffers == null || commandBuffers.isEmpty() || // Added null check
                    inFlightFences == null || inFlightFences.isEmpty() || // Added null check
                    imageAvailableSemaphores == null || imageAvailableSemaphores.isEmpty() || // Added null check
                    renderFinishedSemaphores == null || renderFinishedSemaphores.isEmpty() || // Added null check
                    renderPass == VK_NULL_HANDLE ||
                    application.getVulkanDevice().getPresentQueue() == null || // Check via getter
                    application.getVulkanDevice().getGraphicsQueue() == null) { // Check via getter
                Gdx.app.error(TAG, "[" + this.hashCode() + "] Skipping render: Essential Vulkan resources/queues missing or empty!");
                // Log details
                Gdx.app.error(TAG, " ---> CBs: " + (commandBuffers == null ? "NULL" : commandBuffers.size())
                        + ", Fences: " + (inFlightFences == null ? "NULL" : inFlightFences.size())
                        + ", ImgAvailSems: " + (imageAvailableSemaphores == null ? "NULL" : imageAvailableSemaphores.size())
                        + ", RendFinSems: " + (renderFinishedSemaphores == null ? "NULL" : renderFinishedSemaphores.size())
                        + ", RenderPass: " + renderPass
                        + ", PresQ: " + application.getVulkanDevice().getPresentQueue()
                        + ", GfxQ: " + application.getVulkanDevice().getGraphicsQueue());
                return false;
            }
            // ADDED: Log after resource check passed
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Resource check passed.");

            // ADDED: Log before accessing sync objects
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Accessing sync objects: currentFrame=" + currentFrame            +", maxFramesInFlight=" + maxFramesInFlight                    + ", fenceListSize=" + (inFlightFences != null ? inFlightFences.size() : "NULL") // Size check);

            // Check index validity BEFORE accessing
            if (currentFrame < 0 || currentFrame >= maxFramesInFlight || currentFrame >= inFlightFences.size()) {
                Gdx.app.error(TAG, "[" + this.hashCode() + "] Invalid currentFrame index before accessing fence! currentFrame=" + currentFrame + ", maxFrames=" + maxFramesInFlight + ", listSize=" + inFlightFences.size());
                // You might want to handle this error more gracefully, maybe reset currentFrame or throw
                return false; // Prevent crash
            }

            // Existing access (now we know index is likely valid if we get here)
            long fence = inFlightFences.get(currentFrame);

            // ADDED: Log after successfully getting fence handle reference (doesn't mean it's waited on yet)
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Got fence handle reference for frame " + currentFrame + ": " + fence);

        } catch (Throwable t) {
            // ADDED: Catch and log any hidden Java exception here
            Gdx.app.error(TAG, "[" + this.hashCode() + "] !!! EXCEPTION during pre-render checks !!!", t);
            // Consider if you should return false or re-throw depending on desired behavior
            return false; // Exit update loop gracefully if checks fail
            // throw t; // Or re-throw to make the failure obvious
        }

        // --- Main Rendering Sequence ---
        try (MemoryStack stack = MemoryStack.stackPush()) {
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Starting main Vulkan sequence with MemoryStack for frame " + currentFrame);

            VulkanDevice vulkanDevice = application.getVulkanDevice();
            VkDevice device = vulkanDevice.getRawDevice();
            VkQueue graphicsQueue = vulkanDevice.getGraphicsQueue();
            VkQueue presentQueue = vulkanDevice.getPresentQueue();

            //Gdx.app.log(TAG, "maxFramesInFlight: " + maxFramesInFlight + commandBuffers.size());
            // Check essential resources
            if (commandBuffers.isEmpty() || inFlightFences.isEmpty() ||
                    imageAvailableSemaphores.isEmpty() || renderFinishedSemaphores.isEmpty() ||
                    renderPass == VK_NULL_HANDLE || presentQueue == null || graphicsQueue == null) {
                Gdx.app.error(TAG, "[" + this.hashCode() + "] Skipping render: Essential Vulkan resources/queues missing!");
                return false;
            }

            long fence = inFlightFences.get(currentFrame);
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Waiting for fence: " + fence + " (Frame " + currentFrame + ")");
            long waitStart = System.nanoTime(); // Optional: time the wait
            vkCheck(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "vkWaitForFences failed");
            long waitEnd = System.nanoTime();
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Fence signaled. Wait time: " + ((waitEnd - waitStart) / 1000000.0) + " ms");


            // ---> Step 1.5: <<< CLEANUP COMPLETED FRAME DESCRIPTOR SETS >>> <--- ADDED HERE
            VulkanDescriptorManager descriptorManager = this.vulkanGraphics.getDescriptorManager();
            if (descriptorManager != null) {
                // Clean up descriptor sets that were queued for freeing during the frame
                // index whose fence we just waited on.
                descriptorManager.cleanupCompletedFrameSets(currentFrame);
            } else {
                // Log an error if the manager is unexpectedly null
                Gdx.app.error(TAG, "[" + this.hashCode() + "] Descriptor Manager is null, cannot clean up sets!");
            }

            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Resetting fence: " + fence);
            vkCheck(vkResetFences(device, fence), "Failed to reset fence after wait");
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Fence reset.");


            // ---> Step 3: Acquire the next image <---
            long imageAvailableSemaphore = imageAvailableSemaphores.get(currentFrame);
            IntBuffer pImageIndex = stack.mallocInt(1);
            int acquireResultCode;
            try {
                acquireResultCode = swapchain.acquireNextImage(
                        imageAvailableSemaphore,
                        VK_NULL_HANDLE, // Use semaphore, not fence
                        pImageIndex
                );
            } catch (Exception e) {
                Gdx.app.error(TAG, "Exception during swapchain.acquireNextImage", e);
                framebufferResized = true; // Flag for recreation
                acquireResultCode = VK_ERROR_OUT_OF_DATE_KHR; // Simulate error
            }

            // ---> Step 4: Handle potential swapchain invalidation after acquiring <---
            boolean resizeNeeded = framebufferResized; // Capture flag state before resetting
            boolean justRecreated = false; // Flag to know if we recreated in this iteration

            if (acquireResultCode == VK_ERROR_OUT_OF_DATE_KHR || acquireResultCode == VK_SUBOPTIMAL_KHR || resizeNeeded) {
                framebufferResized = false; // Reset flag
                //Gdx.app.log(TAG, "[" + this.hashCode() + "] Swapchain needs recreation (Acquire Result: " + VkResult.translate(acquireResultCode) + " / Resized Flag: " + resizeNeeded + ")");

                // Fence was already reset in Step 2.
                try {
                    //Gdx.app.log(TAG, "[" + this.hashCode() + "] Calling recreateSwapchainAndDependents()...");
                    recreateSwapchainAndDependents(); // Waits for idle internally
                    // Log after successful recreation
                    //Gdx.app.log(TAG, "[" + this.hashCode() + "] recreateSwapchainAndDependents() finished successfully.");
                    justRecreated = true;

                    // Update cache & notify listener
                    int currentBBW = this.getBackBufferWidth();
                    int currentBBH = this.getBackBufferHeight();
                    int currentLW = this.getLogicalWidth();
                    int currentLH = this.getLogicalHeight();
                    gfx.updateFramebufferInfo(currentBBW, currentBBH, currentLW, currentLH);
                    if (listener != null) {
                        int lw = (application.getAppConfig().hdpiMode == HdpiMode.Pixels) ? currentBBW : currentLW;
                        int lh = (application.getAppConfig().hdpiMode == HdpiMode.Pixels) ? currentBBH : currentLH;
                        if (lw > 0 && lh > 0) listener.resize(lw, lh);
                    }
                    //return true; // Skip the rest of this frame's rendering
                } catch (Exception e) {
                    Gdx.app.error(TAG, "[" + this.hashCode() + "] Exception during swapchain recreation!", e);
                    return false;
                }
            } else if (acquireResultCode != VK_SUCCESS) {
                // Handle other acquire errors
                throw new GdxRuntimeException("Failed to acquire swapchain image. Result code: " + acquireResultCode);
            }

            if (justRecreated) {
                //Gdx.app.log(TAG, "[" + this.hashCode() + "] Re-acquiring image after recreation (Timeout: Inf, Sem: " + imageAvailableSemaphore + ")");
                // Make sure imageAvailableSemaphore handle is still valid (it wasn't recreated)
                acquireResultCode = swapchain.acquireNextImage(imageAvailableSemaphore, VK_NULL_HANDLE, pImageIndex);
                //Gdx.app.log(TAG, "[" + this.hashCode() + "] Post-recreation acquireNextImage result: " + VkResult.translate(acquireResultCode));

                // Handle potential immediate failure even after recreation
                if (acquireResultCode != VK_SUCCESS && acquireResultCode != VK_SUBOPTIMAL_KHR) { // Suboptimal is okay here
                    Gdx.app.error(TAG, "[" + this.hashCode() + "] Failed to acquire image immediately after recreation!");
                    // Maybe throw, maybe try again next frame?
                    return false;
                }
                // If suboptimal, we might want to flag resize for next frame, but proceed for now
            }


            // If acquire was successful and no resize needed, we have a valid image index
            int imageIndex = pImageIndex.get(0);
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Acquired image index: " + imageIndex);

            // Fence was already reset in Step 2.

            // ---> Step 5: Record the command buffer <---
            VkCommandBuffer commandBuffer = commandBuffers.get(currentFrame);
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Resetting command buffer for frame " + currentFrame);
            vkCheck(vkResetCommandBuffer(commandBuffer, 0), "Failed to reset command buffer"); // Safe, fence protects

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Beginning command buffer recording...");
            vkCheck(vkBeginCommandBuffer(commandBuffer, beginInfo), "Failed to begin recording command buffer");
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Command buffer recording started.");


            // Set global context
            gfx.setCurrentCommandBuffer(commandBuffer);
            gfx.setCurrentRenderPassHandle(this.renderPass);
            gfx.setCurrentFrameIndex(this.currentFrame);

            // Begin Render Pass
            VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack).sType$Default();
            renderPassInfo.renderPass(this.renderPass);
            long currentFramebufferHandle = VK_NULL_HANDLE;
            if (this.framebuffers != null && imageIndex < this.framebuffers.size()) {
                currentFramebufferHandle = this.framebuffers.get(imageIndex);
            }
            if (currentFramebufferHandle == VK_NULL_HANDLE) {
                throw new GdxRuntimeException("Could not get valid Framebuffer handle for imageIndex: " + imageIndex);
            }
            renderPassInfo.framebuffer(currentFramebufferHandle);
            renderPassInfo.renderArea().offset().set(0, 0);
            VkExtent2D extent = this.swapchain.getExtent();
            renderPassInfo.renderArea().extent().set(extent);
            VkClearValue.Buffer clearValues = VkClearValue.calloc(1, stack);
            clearValues.get(0).color().float32(stack.floats(config.initialBackgroundColor.r, config.initialBackgroundColor.g, config.initialBackgroundColor.b, config.initialBackgroundColor.a));
            renderPassInfo.pClearValues(clearValues);
            vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

            // Set dynamic states
            VkViewport.Buffer vkViewport = VkViewport.calloc(1, stack)
                    .x(0.0f).y((float) extent.height()).width((float) extent.width())
                    .height(-(float) extent.height()).minDepth(0.0f).maxDepth(1.0f);
            vkCmdSetViewport(commandBuffer, 0, vkViewport);
            VkRect2D.Buffer vkScissor = VkRect2D.calloc(1, stack)
                    .offset(VkOffset2D.calloc(stack).set(0, 0)).extent(extent);
            vkCmdSetScissor(commandBuffer, 0, vkScissor);

            // Execute Application Rendering
            try {
                if (listener != null) listener.render();
            } catch (Throwable t) { /* ... error handling ... */
                throw t;
            }

            // End Render Pass & Command Buffer
            vkCmdEndRenderPass(commandBuffer);
            vkCheck(vkEndCommandBuffer(commandBuffer), "Failed to record command buffer");

            // Clear global context
            gfx.setCurrentCommandBuffer(null);
            gfx.setCurrentRenderPassHandle(VK_NULL_HANDLE);

            // ---> Step 6: Submit the command buffer <---
// ---> Step 6: Submit the command buffer <---
            imageAvailableSemaphore = imageAvailableSemaphores.get(currentFrame);
            long renderFinishedSemaphore = renderFinishedSemaphores.get(currentFrame);
            fence = inFlightFences.get(currentFrame);
            commandBuffer = commandBuffers.get(currentFrame);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack).sType$Default();

            // --- Explicitly create and populate wait buffers ---
            LongBuffer waitSemaphores = stack.mallocLong(1); // Allocate size 1
            waitSemaphores.put(0, imageAvailableSemaphore);   // Put handle at index 0
            // waitSemaphores.flip(); // Not needed if using indexed put and limit() below

            IntBuffer waitStages = stack.mallocInt(1);       // Allocate size 1
            waitStages.put(0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT); // Put stage at index 0
            // waitStages.flip(); // Not needed

            // --- Explicitly create and populate other buffers ---
            PointerBuffer pCommandBuffers = stack.mallocPointer(1); // Allocate size 1
            pCommandBuffers.put(0, commandBuffer);                 // Put CB handle
            // pCommandBuffers.flip(); // Not needed

            LongBuffer signalSemaphores = stack.mallocLong(1); // Allocate size 1
            signalSemaphores.put(0, renderFinishedSemaphore);  // Put handle at index 0
            // signalSemaphores.flip(); // Not needed

            // --- Set struct members using the explicitly created buffers ---
            submitInfo.waitSemaphoreCount(waitSemaphores.limit()); // Set count explicitly (should be 1)
            submitInfo.pWaitSemaphores(waitSemaphores);           // Set pointer
            submitInfo.pWaitDstStageMask(waitStages);             // Set pointer (count implicitly linked to waitSemaphoreCount)
            submitInfo.pCommandBuffers(pCommandBuffers);          // Set pointer
            submitInfo.pSignalSemaphores(signalSemaphores);         // Set pointer

            //Gdx.app.log(TAG, "Frame " + currentFrame + ": Submitting CB. WaitSem: " + imageAvailableSemaphore + ", SignalSem: " + renderFinishedSemaphore + ", SignalFence: " + fence);
            vkCheck(vkQueueSubmit(graphicsQueue, submitInfo, fence), "Failed to submit draw command buffer");

            // ---> Step 7: Present the image <---
            LongBuffer pSwapchains = stack.longs(this.swapchain.getHandle());
            IntBuffer pImageIndices = stack.ints(imageIndex);
            LongBuffer pWaitSemaphoresPresent = stack.longs(renderFinishedSemaphore); // Wait for render finished

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack).sType$Default()
                    //.waitSemaphoreCount(pWaitSemaphoresPresent.limit())
                    .pWaitSemaphores(pWaitSemaphoresPresent)
                    .swapchainCount(1)
                    .pSwapchains(pSwapchains)
                    .pImageIndices(pImageIndices);

            //Gdx.app.log(TAG, "Frame " + currentFrame + ": Presenting Image " + imageIndex + ". WaitSem: " + renderFinishedSemaphore);
            int presentResultCode = vkQueuePresentKHR(presentQueue, presentInfo);

            // Handle present results
            if (presentResultCode == VK_ERROR_OUT_OF_DATE_KHR || presentResultCode == VK_SUBOPTIMAL_KHR) {
                // Flag for recreate on next frame
                framebufferResized = true;
            } else if (presentResultCode != VK_SUCCESS) {
                throw new GdxRuntimeException("Failed to present swap chain image: " + VkResult.translate(presentResultCode));
            }

            // ---> Step 8: Advance Frame Index <---
            currentFrame = (currentFrame + 1) % maxFramesInFlight;
            return true; // Success

        } catch (Exception e) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] Exception during Vulkan frame rendering", e);
            if (gfx != null) {
                gfx.setCurrentCommandBuffer(null);
                gfx.setCurrentRenderPassHandle(VK_NULL_HANDLE);
            }
            if (e instanceof GdxRuntimeException) throw (GdxRuntimeException) e;
            throw new GdxRuntimeException("Vulkan frame rendering failed", e);
        }

        // Note: Finally block removed as context clearing is handled in catch or after successful execution
    }

    private void ensureListenerCreatedAndResized() {
        // Log entry point and current state values
        //Gdx.app.log(TAG, "[" + this.hashCode() + "] ensureListenerCreatedAndResized() ENTERED. listenerInitialized=" + this.listenerInitialized + ", listenerIsNull=" + (this.listener == null));

        // Check if initialization is needed and possible
        if (!listenerInitialized && listener != null) {
            // Condition passed, log that we are proceeding
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Condition PASSED (!listenerInitialized && listener != null), proceeding with create/resize...");

            try {
                // --- Set Context BEFORE calling listener ---
                // Ensures Gdx.graphics/input point to this window's instances
                // during listener create()/resize().
                //Gdx.app.log(TAG, "[" + this.hashCode() + "] Setting Gdx.graphics/input context before listener calls...");
                //Gdx.graphics = this.vulkanGraphics; // Use the instance field for graphics
                //Gdx.input = this.input;             // Use the instance field for input
                application.setCurrentWindow(this); // Inform application of the current window context
                // --- End Set Context ---

                listener.create();
                //Gdx.app.log(TAG, "[" + this.hashCode() + "] listener.create() completed.");

                // Initial resize call
                // Get dimensions from the now correctly set Gdx.graphics
                int width = Gdx.graphics.getWidth();
                int height = Gdx.graphics.getHeight();

                if (width > 0 && height > 0) {
                    //Gdx.app.log(TAG, "[" + this.hashCode() + "] Calling initial listener.resize(" + width + ", " + height + ")");
                    listener.resize(width, height);
                    //Gdx.app.log(TAG, "[" + this.hashCode() + "] Initial listener.resize() completed.");
                } else {
                    // Log error if dimensions aren't valid yet, might happen if called too early?
                    Gdx.app.error(TAG, "[" + this.hashCode() + "] Invalid dimensions (" + width + "x" + height + ") obtained for initial listener resize.");
                    // Proceed to mark as initialized anyway to avoid repeated create() calls.
                }

            } catch (Throwable t) {
                Gdx.app.error(TAG, "[" + this.hashCode() + "] !!! EXCEPTION during listener.create() or initial listener.resize() !!!", t);
                // If create/resize fails critically, rethrowing is often best
                // as the application state might be inconsistent. Avoid setting
                // listenerInitialized = true in this case if you want it to potentially retry.
                // For now, we will proceed to the finally block which sets it true.
                // Consider adding specific error handling if needed.
                throw new GdxRuntimeException("Listener create/resize failed for window " + windowHandle, t); // Rethrow
            } finally {
                // Crucially, mark the listener as initialized AFTER the try block attempts create/resize,
                // preventing this block from running again for this window instance.
                listenerInitialized = true;
                //Gdx.app.log(TAG, "[" + this.hashCode() + "] Listener marked as initialized (listenerInitialized = true).");
            }
        } else {
            // Condition failed, log the reason and skip initialization steps
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Condition FAILED (listenerInitialized=" + listenerInitialized + ", listenerIsNull=" + (listener == null) + "), skipping create/resize.");
        }
    }

    /**
     * Handles swapchain recreation and dependent resources like framebuffers.
     */
    private void recreateSwapchainAndDependents() {
        //Gdx.app.log(TAG, "[" + this.hashCode() + "] --- ENTERING recreateSwapchainAndDependents ---");
        try {
            VulkanApplication application = (VulkanApplication) Gdx.app;
            VkDevice device = application.getVulkanDevice().getRawDevice();

            // 1. Wait for device to be idle - ESSENTIAL before destroying resources
            vkDeviceWaitIdle(device);

            // 2. Clean up OLD framebuffers (depend on old image views)
            for (long framebuffer : framebuffers) {
                if (framebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(device, framebuffer, null);
            }
            framebuffers.clear();
            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Old framebuffers destroyed.");

            // 3. Recreate the swapchain (this cleans up old swapchain, image views internally)
            if (swapchain != null) {
                swapchain.recreate(); // Assumes this method exists and handles internal cleanup/recreation
                //Gdx.app.log(TAG, "[" + this.hashCode() + "] Swapchain recreate() called.");
            } else {
                throw new GdxRuntimeException("Cannot recreate null swapchain");
            }

            // 4. Recreate framebuffers using NEW swapchain image views and extent
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // RenderPass usually doesn't need recreation unless format changes.
                // If it might, add logic here to destroy/recreate this.renderPass.
                if (this.renderPass == VK_NULL_HANDLE) {
                    Gdx.app.error(TAG, "RenderPass was null during swapchain recreation!");
                    // TODO: Add render pass recreation logic if necessary
                    throw new GdxRuntimeException("RenderPass missing during swapchain recreation");
                }

                List<Long> newSwapChainImageViews = this.swapchain.getImageViews();
                this.framebuffers = new ArrayList<>(newSwapChainImageViews.size());
                VkExtent2D newSwapChainExtent = this.swapchain.getExtent();
                //Gdx.app.log(TAG, "[" + this.hashCode() + "] New swapchain extent: " + newSwapChainExtent);

                LongBuffer attachments = stack.mallocLong(1);
                LongBuffer pFramebuffer = stack.mallocLong(1);

                for (long imageView : newSwapChainImageViews) {
                    attachments.put(0, imageView);
                    VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                            .sType$Default()
                            .renderPass(this.renderPass) // Use existing render pass
                            .pAttachments(attachments)
                            .width(newSwapChainExtent.width())
                            .height(newSwapChainExtent.height())
                            .layers(1);

                    VkResult fbResult = VkResult.translate(vkCreateFramebuffer(device, framebufferInfo, null, pFramebuffer));
                    if (!fbResult.isSuccess()) {
                        throw new GdxRuntimeException("Failed to recreate framebuffer: " + fbResult);
                    }
                    this.framebuffers.add(pFramebuffer.get(0));
                }
                //Gdx.app.log(TAG, "[" + this.hashCode() + "] Framebuffers recreated (" + this.framebuffers.size() + ")");
            }

            //Gdx.app.log(TAG, "[" + this.hashCode() + "] Swapchain recreation process finished.");

        } catch (Exception e) {
            Gdx.app.error(TAG, "[" + this.hashCode() + "] CRITICAL: Failed to recreate swapchain!", e);
            throw new GdxRuntimeException("Swapchain recreation failed", e);
        }
    }

    void requestRendering() {
        synchronized (this) {
            this.requestRendering = true;
        }
    }

    boolean shouldClose() {
        return GLFW.glfwWindowShouldClose(windowHandle);
    }

    VulkanApplicationConfiguration getApplicationConfig() {
        return application.getAppConfig();
    }

    VulkanWindowConfiguration getConfig() {
        return config;
    }

    boolean isListenerInitialized() {
        return listenerInitialized;
    }

    /*void initializeListener() { // Package-private or private
        if (!listenerInitialized) {
           //Gdx.app.log(TAG, "[" + this.hashCode() + "] Initializing listener... " + listener.getClass().getName());
            try {

                listenerInitialized = true; // Mark as initialized
               //Gdx.app.log(TAG, "[" + this.hashCode() + "] Listener initialized.");
            } catch (Throwable t) {
                Gdx.app.error(TAG, "[" + this.hashCode() + "] Exception during listener initialization!", t);
                // Optionally close window or rethrow
                throw new GdxRuntimeException("Listener initialization failed for window " + windowHandle, t);
            }
        }
    }*/

    void makeCurrent() {
        if (this.input != null) {
            Gdx.input = this.input;
        } else {
            // This case should ideally not happen if input is created properly
            Gdx.app.error(TAG, "makeCurrent() called on window " + windowHandle + " but its input handler is null!");
            Gdx.input = null; // Explicitly null out global static
        }
    }

    @Override
    public void dispose() {
        final boolean useGdxLog = (Gdx.app != null && Gdx.app.getApplicationLogger() != null);
        logInfo(TAG, "[" + this.hashCode() + "] Disposing window " + windowHandle, useGdxLog);

        VkDevice device = null;
        VkInstance instance = null;
        if (application != null && application.getVulkanDevice() != null) {
            device = application.getVulkanDevice().getRawDevice();
            instance = application.getVulkanInstance().getRawInstance();
        } else {
            logInfo(TAG, "[" + this.hashCode() + "] Warning: Cannot get Vulkan device/instance for proper cleanup.", useGdxLog);
        }

        // Wait for idle before destroying window-specific resources
        if (device != null) {
            logInfo(TAG, "[" + this.hashCode() + "] Waiting for device idle before window resource cleanup...", useGdxLog);
            vkDeviceWaitIdle(device);
            logInfo(TAG, "[" + this.hashCode() + "] Device idle.", useGdxLog);
        }

        // --- Nullify ALL GLFW Callbacks FIRST ---
        // This prevents issues if callback objects are freed before GLFW stops using them.
        if (windowHandle != NULL) {
            logInfo(TAG, "[" + this.hashCode() + "] Nullifying GLFW callbacks.", useGdxLog);
            GLFW.glfwSetWindowFocusCallback(windowHandle, null);
            GLFW.glfwSetWindowIconifyCallback(windowHandle, null);
            GLFW.glfwSetWindowMaximizeCallback(windowHandle, null);
            GLFW.glfwSetWindowCloseCallback(windowHandle, null);
            GLFW.glfwSetDropCallback(windowHandle, null);
            GLFW.glfwSetWindowRefreshCallback(windowHandle, null);
            // Nullify input callbacks too
            GLFW.glfwSetKeyCallback(windowHandle, null);
            GLFW.glfwSetCharCallback(windowHandle, null);
            GLFW.glfwSetScrollCallback(windowHandle, null);
            GLFW.glfwSetCursorPosCallback(windowHandle, null);
            GLFW.glfwSetMouseButtonCallback(windowHandle, null);
        }
        // --- End Nullify Callbacks ---

        // Dispose Vulkan Resources (Helper Method)
        disposeVulkanResources(); // Disposes swapchain, surface, pool, sync etc.

        if (vulkanGraphics != null) {
            vulkanGraphics.dispose(); // Dispose this window's graphics instance
            logInfo(TAG, "[" + this.hashCode() + "] VulkanGraphics instance disposed.", useGdxLog);
            // this.vulkanGraphics = null; // Not needed if field is final
        }

        // Dispose Input Handler (which frees its callback objects)
        if (input != null) {
            input.dispose();
            logInfo(TAG, "[" + this.hashCode() + "] Input disposed.", useGdxLog);
            input = null;
        }

        // Dispose Cursors
        VulkanCursor.dispose(this);
        logInfo(TAG, "[" + this.hashCode() + "] Cursors disposed.", useGdxLog);

        // Destroy GLFW Window Handle
        if (windowHandle != NULL) {
            GLFW.glfwDestroyWindow(windowHandle);
            logInfo(TAG, "[" + this.hashCode() + "] GLFW window handle " + windowHandle + " destroyed.", useGdxLog);
            windowHandle = NULL;
        }

        // Free Window Callback Instances (Input ones freed by input.dispose())
        if (focusCallback != null) focusCallback.free();
        if (iconifyCallback != null) iconifyCallback.free();
        if (maximizeCallback != null) maximizeCallback.free();
        if (closeCallback != null) closeCallback.free();
        if (dropCallback != null) dropCallback.free();
        if (refreshCallback != null) refreshCallback.free();
        if (resizeCallback != null) resizeCallback.free();
        logInfo(TAG, "[" + this.hashCode() + "] Window callback instances freed.", useGdxLog);

        logInfo(TAG, "[" + this.hashCode() + "] dispose() finished.", useGdxLog);
    }

    private void disposeVulkanResources() {
        final boolean useGdxLog = (Gdx.app != null && Gdx.app.getApplicationLogger() != null);
        VkDevice device = null;
        VkInstance instance = null;
        if (application != null && application.getVulkanDevice() != null) {
            device = application.getVulkanDevice().getRawDevice();
            instance = application.getVulkanInstance().getRawInstance();
        }

        // Dispose Step 3 Resources (Order: Sync -> Pool -> Framebuffers -> RenderPass)
        if (device != null) {
            for (long semaphore : imageAvailableSemaphores) if (semaphore != VK_NULL_HANDLE) vkDestroySemaphore(device, semaphore, null);
            for (long semaphore : renderFinishedSemaphores) if (semaphore != VK_NULL_HANDLE) vkDestroySemaphore(device, semaphore, null);
            for (long fence : inFlightFences) if (fence != VK_NULL_HANDLE) vkDestroyFence(device, fence, null);
            if (!imageAvailableSemaphores.isEmpty() || !renderFinishedSemaphores.isEmpty() || !inFlightFences.isEmpty())
                logInfo(TAG, "[" + this.hashCode() + "] Sync objects disposed.", useGdxLog);
        }
        imageAvailableSemaphores.clear();
        renderFinishedSemaphores.clear();
        inFlightFences.clear();

        // Command buffers are freed when pool is destroyed, just clear list
        commandBuffers.clear();
        if (device != null && commandPool != VK_NULL_HANDLE) {
            vkDestroyCommandPool(device, commandPool, null);
            logInfo(TAG, "[" + this.hashCode() + "] Command pool disposed.", useGdxLog);
            commandPool = VK_NULL_HANDLE;
        }


        if (device != null) {
            for (long framebuffer : framebuffers) if (framebuffer != VK_NULL_HANDLE) vkDestroyFramebuffer(device, framebuffer, null);
            if (!framebuffers.isEmpty()) logInfo(TAG, "[" + this.hashCode() + "] Framebuffers disposed.", useGdxLog);
        }
        framebuffers.clear();

        if (device != null && renderPass != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device, renderPass, null);
            logInfo(TAG, "[" + this.hashCode() + "] Render pass disposed.", useGdxLog);
            renderPass = VK_NULL_HANDLE;
        }
        // --- End Step 3 Resource Cleanup ---


        // --- Dispose Step 2 Resources ---
        if (swapchain != null) {
            swapchain.dispose();
            logInfo(TAG, "[" + this.hashCode() + "] Swapchain disposed.", useGdxLog);
            swapchain = null;
        }
        if (instance != null && surface != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(instance, surface, null);
            logInfo(TAG, "[" + this.hashCode() + "] Surface disposed.", useGdxLog);
            surface = VK_NULL_HANDLE;
        }
        // --- End Step 2 Resources ---
    }

    // Helper method for consistent logging during cleanup
    private void logInfo(String tag, String message, boolean useGdx) {
        if (useGdx) {
            //Gdx.app.log(tag, message);
        } else {
            System.out.println("[" + tag + "] " + message);
        }
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + Long.hashCode(windowHandle);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        VulkanWindow other = (VulkanWindow) obj;
        if (windowHandle != other.windowHandle) return false;
        return true;
    }

    public void flash() {
        GLFW.glfwRequestWindowAttention(windowHandle);
    }

    public void setInputHandler(VulkanInput inputInstance) {
        //Gdx.app.log(TAG, "setInputHandler called with instance hash: " + (inputInstance == null ? "NULL" : inputInstance.hashCode()));
        this.input = inputInstance; // Store the instance
        //Gdx.app.log(TAG, "Input handler set to: " + (input == null ? "NULL" : input.hashCode()));
    }

    /** Called by VulkanApplication.isFullscreen() */
    boolean isFullscreenInternal() {
        return GLFW.glfwGetWindowMonitor(this.windowHandle) != NULL;
    }

    /** Called by VulkanApplication.setFullscreenMode() */
    boolean setFullscreenModeInternal(Graphics.DisplayMode displayMode) {
        if (input != null) input.resetPollingStates();

        if (!(displayMode instanceof VulkanGraphics.VulkanDisplayMode)) {
            Gdx.app.error(TAG, "[" + windowHandle + "] Invalid DisplayMode type provided to setFullscreenModeInternal.");
            return false;
        }
        VulkanGraphics.VulkanDisplayMode newMode = (VulkanGraphics.VulkanDisplayMode) displayMode;

        if (isFullscreenInternal()) {
            // Already fullscreen, potentially change mode or monitor
            VulkanGraphics.VulkanDisplayMode currentMode = (VulkanGraphics.VulkanDisplayMode) vulkanGraphics.getDisplayMode(vulkanGraphics.getMonitor());
            if (currentMode.monitorHandle == newMode.monitorHandle && currentMode.refreshRate == newMode.refreshRate) {
                GLFW.glfwSetWindowSize(this.windowHandle, newMode.width, newMode.height);
            } else {
                GLFW.glfwSetWindowMonitor(this.windowHandle, newMode.monitorHandle, 0, 0, newMode.width, newMode.height, newMode.refreshRate);
            }
        } else {
            // Switching from windowed to fullscreen
            // TODO: Need to properly implement storing/restoring previous window state
            // storeCurrentWindowPositionAndDisplayMode(); // Logic needs to exist here or be passed
            //Gdx.app.log(TAG, "[" + windowHandle + "] Storing window state before fullscreen (TODO: Implement!)");
            GLFW.glfwSetWindowMonitor(this.windowHandle, newMode.monitorHandle, 0, 0, newMode.width, newMode.height, newMode.refreshRate);
        }
        // Swapchain recreation will be triggered by resize event / framebufferResized flag
        return true;
    }

    /** Called by VulkanApplication.setWindowedMode() */
    boolean setWindowedModeInternal(int width, int height) {
        if (input != null) input.resetPollingStates();

        if (!isFullscreenInternal()) {
            // Already windowed, just resize
            // TODO: Implement centering logic if desired, potentially using stored previous position?
            //Gdx.app.log(TAG, "[" + windowHandle + "] Setting window size to " + width + "x" + height + " (TODO: Centering?)");
            GLFW.glfwSetWindowSize(this.windowHandle, width, height);
        } else {
            // Switching from fullscreen to windowed
            // TODO: Restore previous window position/size/mode properly
            //Gdx.app.log(TAG, "[" + windowHandle + "] Restoring windowed mode at " + width + "x" + height + " (TODO: Use stored state!)");
            int posX = 100; // Placeholder - use stored previous X
            int posY = 100; // Placeholder - use stored previous Y
            int refreshRate = GLFW.GLFW_DONT_CARE; // Placeholder

            GLFW.glfwSetWindowMonitor(this.windowHandle, NULL, posX, posY, width, height, refreshRate);
        }
        // Swapchain recreation will be triggered by resize event / framebufferResized flag
        return true;
    }

    /** Called by VulkanApplication.setTitle() */
    void setTitleInternal(CharSequence title) {
        GLFW.glfwSetWindowTitle(this.windowHandle, title);
    }

    /** Called by VulkanApplication.setUndecorated() */
    void setUndecoratedInternal(boolean undecorated) {
        GLFW.glfwSetWindowAttrib(this.windowHandle, GLFW.GLFW_DECORATED, undecorated ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE);
    }

    /** Called by VulkanApplication.setResizable() */
    void setResizableInternal(boolean resizable) {
        GLFW.glfwSetWindowAttrib(this.windowHandle, GLFW.GLFW_RESIZABLE, resizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
    }

    /** Called by VulkanApplication.setVSync() */
    void setVSyncInternal(boolean vsync) {
        // VSync is controlled by the swapchain present mode.
        // We need to change the desired mode in the window config and trigger a swapchain recreate.
        VulkanApplicationConfiguration.SwapchainPresentMode targetMode =
                vsync ? VulkanApplicationConfiguration.SwapchainPresentMode.FIFO
                        : VulkanApplicationConfiguration.SwapchainPresentMode.MAILBOX; // Or IMMEDIATE

        if (config.presentMode != targetMode) {
            //Gdx.app.log(TAG, "[" + windowHandle + "] setVSyncInternal(" + vsync + ") changing presentMode to: " + targetMode);
            config.presentMode = targetMode; // Update this window's config
            this.framebufferResized = true; // Flag for swapchain recreation
            this.requestRendering();         // Ensure render loop checks the flag
        }
    }

    /** Called by VulkanApplication.setCursor() */
    void setCursorInternal(Cursor cursor) {
        // Assumes VulkanCursor holds the glfwCursor handle
        if (cursor instanceof VulkanCursor) {
            GLFW.glfwSetCursor(this.windowHandle, ((VulkanCursor) cursor).glfwCursor);
        } else if (cursor == null) {
            GLFW.glfwSetCursor(this.windowHandle, NULL);
        } else {
            Gdx.app.error(TAG, "Cannot set cursor: Invalid cursor type provided.");
        }
    }

    /** Called by VulkanApplication.setSystemCursor() */
    void setSystemCursorInternal(Cursor.SystemCursor systemCursor) {
        VulkanCursor.setSystemCursor(this.windowHandle, systemCursor);
    }

    // --- Optional Helpers for requestRendering flag ---
    boolean needsRendering() {
        return requestRendering;
    }

    void clearNeedsRendering() {
        requestRendering = false;
    }

}
