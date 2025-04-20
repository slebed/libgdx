
package com.badlogic.gdx.backend.vulkan;

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

import static com.badlogic.gdx.backend.vulkan.VkMemoryUtil.vkCheck;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.vmaCreateAllocator;
import static org.lwjgl.util.vma.Vma.vmaDestroyAllocator;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_1_0;
import static org.lwjgl.vulkan.VK10.VK_FALSE;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_TRUE;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceLayerProperties;

import java.io.File;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.ApplicationLogger;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backend.vulkan.audio.OpenALLwjgl3Audio;
import com.badlogic.gdx.backend.vulkan.audio.VulkanAudio;

import com.badlogic.gdx.utils.*;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.EXTDebugUtils;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDevice;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.LifecycleListener;
import com.badlogic.gdx.Net;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.backend.vulkan.audio.mock.MockAudio;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Clipboard;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.SharedLibraryLoader;

import org.lwjgl.vulkan.VkLayerProperties;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceProperties;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceQueueFamilyProperties;
import static org.lwjgl.vulkan.KHRSwapchain.*;

import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkExtensionProperties;

import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

public class VulkanApplication implements VulkanApplicationBase {
	final String TAG = "    VulkanApplication";
	final Array<VulkanWindow> windows = new Array<>();
	private final long primaryWindowHandle;
	private VulkanInstance vulkanInstance;
	private volatile VulkanWindow currentWindow;
	private VulkanAudio audio;
	private final Files files;
	private final Net net;
	private final ObjectMap<String, Preferences> preferences = new ObjectMap<String, Preferences>();
	private int logLevel = LOG_INFO;
	private ApplicationLogger applicationLogger;
	private volatile boolean running = true;
	private final Array<Runnable> runnables = new Array<Runnable>();
	private final Array<Runnable> executedRunnables = new Array<Runnable>();
	private final Array<LifecycleListener> lifecycleListeners = new Array<>();
	private static GLFWErrorCallback errorCallback;

	private VkPhysicalDevice physicalDevice; // You'll need logic to select this
	private VulkanDevice vulkanDevice;

	private long primarySurface = VK_NULL_HANDLE; // For the first window
	private final VulkanClipboard clipboard;
	private final VulkanApplicationConfiguration appConfig;
	private long debugMessenger;
	private boolean enableValidationLayers = true;

	private static final Set<String> DEVICE_EXTENSIONS = Collections.singleton(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
	private static final List<String> DESIRED_VALIDATION_LAYERS = Collections.singletonList("VK_LAYER_KHRONOS_validation");
	// private static final List<String> DESIRED_VALIDATION_LAYERS = Collections.singletonList(null);

	private long vmaAllocator = VK_NULL_HANDLE;

	private VkDebugUtilsMessengerCallbackEXT debugCallbackInstance = null;

	private Integer graphicsQueueFamily;
	private Integer presentQueueFamily;

	private final Map<Long, Long> windowSurfaces = new ConcurrentHashMap<>();

	static void initializeGlfw () {
		if (errorCallback == null) {
			VulkanNativesLoader.load();
			errorCallback = GLFWErrorCallback.createPrint(VulkanApplicationConfiguration.errorStream);
			GLFW.glfwSetErrorCallback(errorCallback);
			if (SharedLibraryLoader.os == Os.MacOsX)
				GLFW.glfwInitHint(GLFW.GLFW_ANGLE_PLATFORM_TYPE, GLFW.GLFW_ANGLE_PLATFORM_TYPE_METAL);
			GLFW.glfwInitHint(GLFW.GLFW_JOYSTICK_HAT_BUTTONS, GLFW.GLFW_FALSE);
			if (!GLFW.glfwInit()) {
				throw new GdxRuntimeException("Unable to initialize GLFW");
			}
		}
	}

	private void initializeVulkanCore (long windowHandle) {
		try (MemoryStack stack = MemoryStack.stackPush()) {

			List<String> requiredExtensions = getRequiredInstanceExtensions(stack);
			List<String> validationLayers = getValidationLayers(); // Get based on config

			this.vulkanInstance = new VulkanInstance.Builder().setApplicationName(appConfig.title)
				.setRequiredExtensions(requiredExtensions).setValidationLayers(validationLayers).build();
			System.out.println("Vulkan Instance created.");

			if (enableValidationLayers) { // Assuming this field exists
				setupDebugMessenger(stack);
				System.out.println("Debug Messenger created.");
			}

			LongBuffer pSurface = stack.mallocLong(1);
			int err = GLFWVulkan.glfwCreateWindowSurface(vulkanInstance.getRawInstance(), windowHandle, null, pSurface);
			vkCheck(err, "Failed to create window surface");
			this.primarySurface = pSurface.get(0);
			System.out.println("Window Surface created.");

			this.physicalDevice = selectPhysicalDevice(stack);
			System.out.println("Physical Device selected.");

			QueueFamilyIndices indices = findQueueFamilies(physicalDevice, stack);
			if (!indices.isComplete()) {
				// This should ideally be checked inside findQueueFamilies and throw
				throw new GdxRuntimeException("Failed to find required queue families.");
			}
			this.graphicsQueueFamily = indices.graphicsFamily; // Store the index
			this.presentQueueFamily = indices.presentFamily; // Store the index
			this.vulkanDevice = new VulkanDevice.Builder().setPhysicalDevice(physicalDevice)
				.setQueueFamilyIndex(indices.graphicsFamily).build();
			System.out.println("Logical Device created.");

			if (this.vulkanInstance != null && this.vulkanDevice != null) {
				createVmaAllocator(this.vulkanInstance, this.vulkanDevice);
			} else {
				throw new GdxRuntimeException("Cannot create VMA Allocator: Instance or Device is null!");
			}

		} catch (Exception e) {
			throw new GdxRuntimeException("Failed core Vulkan initialization", e);
		}
	}

	private List<String> convertPointerBufferToStringList (PointerBuffer buffer) {
		if (buffer == null) return null;
		List<String> list = new ArrayList<>(buffer.remaining());
		for (int i = buffer.position(); i < buffer.limit(); i++) {
			list.add(MemoryUtil.memUTF8(buffer.get(i)));
		}
		return list;
	}

	public VulkanApplication (ApplicationListener listener) {
		this(listener, new VulkanApplicationConfiguration());
	}

	public VulkanApplication (ApplicationListener listener, VulkanApplicationConfiguration appConfig) {
		initializeGlfw(); // Assuming this initializes GLFW
		setApplicationLogger(new VulkanApplicationLogger()); // Assuming this exists
		this.appConfig = VulkanApplicationConfiguration.copy(appConfig); // Store a copy

		if (this.appConfig.title == null) this.appConfig.title = listener.getClass().getSimpleName();
		Gdx.app = this; // Set static Gdx.app reference

		long windowHandle = createGlfwWindow(this.appConfig, 0);
		this.primaryWindowHandle = windowHandle; // Store primary handle

		initializeVulkanCore(windowHandle);

		if (this.vulkanDevice == null || this.vulkanDevice.getRawDevice() == null
			|| this.vulkanDevice.getRawDevice().address() == VK_NULL_HANDLE) {
			throw new GdxRuntimeException("VulkanDevice was not initialized by initializeVulkanCore!");
		}
		if (this.vmaAllocator == VK_NULL_HANDLE) {
			throw new GdxRuntimeException("VMA Allocator was not initialized by initializeVulkanCore!");
		}

		if (!this.appConfig.disableAudio) {
			this.audio = createAudio(this.appConfig); // Assuming this exists
		} else {
			this.audio = new MockAudio(); // Assuming MockAudio exists
		}
		Gdx.audio = (Audio)this.audio; // Cast needed? Assign Gdx.audio
		this.files = Gdx.files = createFiles(); // Assuming this exists
		this.net = Gdx.net = new VulkanNet(this.appConfig); // Assuming VulkanNet exists
		this.clipboard = new VulkanClipboard(); // Assuming VulkanClipboard exists

		VulkanGraphics graphics = new VulkanGraphics(windowHandle, this.appConfig, this.vulkanDevice, this.vmaAllocator);
		Gdx.graphics = graphics;

		VulkanWindow window = new VulkanWindow(listener, lifecycleListeners, this.appConfig, this);
		Gdx.app.log("VulkanAppInit", "VulkanWindow object created. Hash: " + window.hashCode());

		Gdx.app.log("VulkanAppInit", "Calling createInput...");

		VulkanInput createdInput = createInput(window);
		Gdx.app.log("VulkanAppInit",
			"createInput returned: " + (createdInput == null ? "NULL" : createdInput.getClass().getName()));

		Gdx.input = createdInput;
		if (Gdx.input != null) {
			Gdx.app.log("VulkanAppInit", "Gdx.input assigned. Instance Hash: " + Gdx.input.hashCode());
		} else {
			Gdx.app.log("VulkanAppInit", "Gdx.input assignment resulted in NULL!");
			throw new GdxRuntimeException("Failed to create VulkanInput handler"); // Fail fast
		}

		window.setInputHandler(createdInput); // Call the setter method

		Gdx.app.log("VulkanAppInit", "Calling window.create() for handle: " + windowHandle);
		window.create(windowHandle);
		Gdx.app.log("VulkanAppInit", "window.create() finished.");

		window.setVisible(this.appConfig.initialVisible);
		windows.add(window);
		this.currentWindow = window; // Set initial current window

		try {
			Gdx.app.log(TAG, "Calling listener.create()...");
			listener.create(); // Calls VulkanTestChooser.create() -> setInputProcessor()
			Gdx.app.log(TAG, "listener.create() completed.");
		} catch (Throwable e) {
			cleanup();
			throw new GdxRuntimeException("Exception occurred in ApplicationListener.create()", e);
		}

		try {
			final int initialWidth = Gdx.graphics.getWidth();
			final int initialHeight = Gdx.graphics.getHeight();
			if (initialWidth > 0 && initialHeight > 0) {
				Gdx.app.log(TAG, "Calling listener.resize() with initial dimensions: " + initialWidth + "x" + initialHeight);
				listener.resize(initialWidth, initialHeight);
				Gdx.app.log(TAG, "Initial listener.resize() completed.");
			} else {
				Gdx.app.error(TAG, "Initial dimensions from Gdx.graphics are invalid (" + initialWidth + "x" + initialHeight
					+ "), skipping initial resize call!");
			}
		} catch (Throwable e) {
			Gdx.app.error(TAG, "Exception occurred during initial ApplicationListener.resize()", e);
			cleanup();
			throw new GdxRuntimeException("Exception occurred during initial ApplicationListener.resize()", e);
		}

		Gdx.app.log(TAG, "Starting main loop...");
		runMainLoop();
	}

	public void runMainLoop () {
		try {
			loop();
			cleanupWindows();
		} catch (Throwable t) {
			if (t instanceof RuntimeException)
				throw (RuntimeException)t;
			else
				throw new GdxRuntimeException(t);
		} finally {
			cleanup();
		}
	}

	public void loop () {
		Array<VulkanWindow> closedWindows = new Array<>();
		long frameCount = 0;

		if (windows.size > 0) {
			currentWindow = windows.first();
			if (currentWindow != null) {
				// Ensure context is active for initial operations if needed
				currentWindow.makeCurrent();
			} else {
				Gdx.app.error(TAG, "Window array has size > 0 but first element is null!");
				// Handle this error state appropriately
			}
		}

		// Main loop continues as long as the app is running and has windows
		while (running && windows.size > 0) {
			if (audio != null /* && audio instanceof ActualAudioType */) {
				// ((ActualAudioType)audio).update(); // Call appropriate update method
			}

			if (++frameCount % 120 == 0) {
				Gdx.app.log(TAG, "loop() iteration: " + frameCount);
			}

			GLFW.glfwPollEvents();

			VulkanInput currentInput = null;
			if (currentWindow != null) {
				currentInput = currentWindow.getInput();
			}

			if (currentInput != null) {
				InputProcessor loopProc = currentInput.getInputProcessor();
				currentInput.update();
				currentInput.prepareNext();
			} else {
				// Log if input couldn't be processed (e.g., no current window)
				// Gdx.app.log("VulkanAppLoop", "currentInput is NULL (currentWindow=" + currentWindow + "), skipping input
				// processing.");
			}

			boolean shouldRequestRendering;
			synchronized (runnables) {
				shouldRequestRendering = runnables.size > 0;
				if (shouldRequestRendering) {
					executedRunnables.clear();
					executedRunnables.addAll(runnables);
					runnables.clear();
				}
			}
			if (shouldRequestRendering) {
				for (Runnable runnable : executedRunnables) {
					try {
						runnable.run();
					} catch (Throwable t) {
						Gdx.app.error(TAG, "Exception occurred in runnable execution", t);
					}
				}

				for (VulkanWindow window : windows) {
					if (!window.getGraphics().isContinuousRendering()) window.requestRendering();
				}
			}

			boolean haveWindowsRendered = false;
			closedWindows.clear();
			int targetFramerate = -2; // Use -2 to indicate not yet set this frame

			for (VulkanWindow window : windows) {
				if (currentWindow != window) {
					window.makeCurrent();
					currentWindow = window;
				}

				if (targetFramerate == -2) targetFramerate = appConfig.foregroundFPS;// window.getConfig().foregroundFPS;

				synchronized (lifecycleListeners) {
					try {
						haveWindowsRendered |= window.update();
					} catch (Throwable t) {
						Gdx.app.error(TAG, "Exception occurred during window update/render", t);
					}
				}

				if (window.shouldClose()) {
					closedWindows.add(window);
				}
			}

			if (closedWindows.size > 0) {
				for (VulkanWindow closedWindow : closedWindows) {
					if (windows.size == 1) {
						for (int i = lifecycleListeners.size - 1; i >= 0; i--) {
							LifecycleListener l = lifecycleListeners.get(i);
							try {
								l.pause();
								l.dispose();
							} catch (Throwable t) {
								Gdx.app.error(TAG, "Exception occurred during lifecycle listener pause/dispose", t);
							}
						}
						lifecycleListeners.clear();
					}

					try {
						closedWindow.dispose();
					} catch (Throwable t) {
						Gdx.app.error(TAG, "Exception occurred during window dispose", t);
					}

					windows.removeValue(closedWindow, true); // Use identity comparison

					if (currentWindow == closedWindow) {
						currentWindow = null;
					}
				}

				if (currentWindow == null && windows.size > 0) {
					currentWindow = windows.first();
					if (currentWindow != null) {
						currentWindow.makeCurrent(); // Activate its context
					} else {
						Gdx.app.error(TAG, "Window array has size > 0 but first element is null after closing window!");
						// This indicates a potential issue with window removal or list management
					}
				}
			}

			if (!haveWindowsRendered) {
				try {
					Thread.sleep(1000 / appConfig.idleFPS);
				} catch (InterruptedException e) {
					// ignore
				}
			} else if (targetFramerate > 0) {
				// Optional: Implement frame limiting if needed
				// sync.sync(targetFramerate); // Example using hypothetical Sync class
			}
		} // End while loop

		Gdx.app.log(TAG, "loop() finished after " + frameCount + " iterations.");
	}

	protected void cleanupWindows () {
		synchronized (lifecycleListeners) {
			for (LifecycleListener lifecycleListener : lifecycleListeners) {
				lifecycleListener.pause();
				lifecycleListener.dispose();
			}
		}
		for (VulkanWindow window : windows) {
			window.dispose();
		}
		windows.clear();
	}

	protected void cleanup () {

		// Use System.out/err during cleanup as Gdx.app might become null unexpectedly
		Gdx.app.log(TAG, " Starting cleanup...");

		// 1. Dispose Listener FIRST (Prevents listener code running during shutdown)
		if (currentWindow != null && currentWindow.getListener() != null) {
			try {
				currentWindow.getListener().pause();
				currentWindow.getListener().dispose();
				Gdx.app.log(TAG, "ApplicationListener disposed.");
			} catch (Throwable t) {
				System.err.println("[" + TAG + "] ERROR: Exception during listener dispose.");
				t.printStackTrace();
			}
		}

		cleanupWindows(); // Calls dispose() on each VulkanWindow
		Gdx.app.log(TAG, "Window cleanup finished.");

		if (Gdx.graphics instanceof VulkanGraphics) {
			System.out.println("[" + TAG + "] Disposing VulkanGraphics...");
			try {
				((VulkanGraphics)Gdx.graphics).dispose();
				Gdx.app.log(TAG, "VulkanGraphics disposed.");
			} catch (Throwable t) {
				Gdx.app.log(TAG, "ERROR: Exception during graphics dispose.");
				t.printStackTrace();
			}
		} else if (Gdx.graphics != null) {
			Gdx.app.error(TAG, "WARNING: Gdx.graphics was not VulkanGraphics?");
		}

		// 4. Dispose Audio (Can happen after graphics)
		if (audio != null) {
			audio.dispose();
			Gdx.app.error(TAG, "Audio disposed.");
			audio = null;
		}

		destroyVmaAllocator();

		if (vulkanDevice != null) {
			System.out.println("[" + TAG + "] Cleaning up VulkanDevice...");
			vulkanDevice.cleanup();
			System.out.println("[" + TAG + "] VulkanDevice cleanup finished.");
			vulkanDevice = null;
		}

		if (debugMessenger != VK_NULL_HANDLE && vulkanInstance != null) {
			EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(vulkanInstance.getRawInstance(), debugMessenger, null);
			System.out.println("[" + TAG + "] Debug messenger destroyed.");
			debugMessenger = VK_NULL_HANDLE;
		} else if (debugMessenger != VK_NULL_HANDLE) {
			System.err.println("[" + TAG + "] ERROR: Cannot destroy debug messenger, Vulkan instance is null!");
		}

		if (debugCallbackInstance != null) {
			debugCallbackInstance.free();
			System.out.println("[" + TAG + "] Debug callback freed.");
			debugCallbackInstance = null;
		}

		if (primarySurface != VK_NULL_HANDLE && vulkanInstance != null) {
			KHRSurface.vkDestroySurfaceKHR(vulkanInstance.getRawInstance(), primarySurface, null);
			System.out.println("[" + TAG + "] Primary surface destroyed.");
			primarySurface = VK_NULL_HANDLE;
		} else if (primarySurface != VK_NULL_HANDLE) {
			System.err.println("[" + TAG + "] ERROR: Cannot destroy surface - Vulkan instance already null!");
		}

		if (vulkanInstance != null) {
			vulkanInstance.cleanup(); // Calls vkDestroyInstance
			System.out.println("[" + TAG + "] Vulkan instance cleaned up.");
			vulkanInstance = null;
		}

		if (errorCallback != null) {
			errorCallback.free();
			errorCallback = null; // Set static field to null
			System.out.println("[" + TAG + "] GLFW error callback freed.");
		}

		GLFW.glfwTerminate();
		System.out.println("[" + TAG + "] GLFW terminated.");

		VulkanCursor.disposeSystemCursors();

		System.out.println("[" + TAG + "] Cleanup finished.");

		Gdx.app = null;
		Gdx.graphics = null;
		Gdx.input = null;
		Gdx.audio = null;
		Gdx.files = null;
		Gdx.net = null;
	}

	public VulkanInstance getVulkanInstance () {
		return vulkanInstance;
	}

	public VulkanDevice getVkDevice () {
		return vulkanDevice;
	}

	public long getSurface (long windowHandle) {
		return windowSurfaces.getOrDefault(windowHandle, VK10.VK_NULL_HANDLE);
	}

	@Override
	public ApplicationListener getApplicationListener () {
		return currentWindow.getListener();
	}

	@Override
	public Graphics getGraphics () {
		return currentWindow.getGraphics();
	}

	@Override
	public Audio getAudio () {
		return audio;
	}

	@Override
	public Input getInput () {
		return currentWindow.getInput();
	}

	@Override
	public Files getFiles () {
		return files;
	}

	@Override
	public Net getNet () {
		return net;
	}

	@Override
	public void debug (String tag, String message) {
		if (logLevel >= LOG_DEBUG) getApplicationLogger().debug(tag, message);
	}

	@Override
	public void debug (String tag, String message, Throwable exception) {
		if (logLevel >= LOG_DEBUG) getApplicationLogger().debug(tag, message, exception);
	}

	@Override
	public void log (String tag, String message) {
		if (logLevel >= LOG_INFO) getApplicationLogger().log(tag, message);
	}

	@Override
	public void log (String tag, String message, Throwable exception) {
		if (logLevel >= LOG_INFO) getApplicationLogger().log(tag, message, exception);
	}

	@Override
	public void error (String tag, String message) {
		if (logLevel >= LOG_ERROR) getApplicationLogger().error(tag, message);
	}

	@Override
	public void error (String tag, String message, Throwable exception) {
		if (logLevel >= LOG_ERROR) getApplicationLogger().error(tag, message, exception);
	}

	@Override
	public void setLogLevel (int logLevel) {
		this.logLevel = logLevel;
	}

	@Override
	public int getLogLevel () {
		return logLevel;
	}

	@Override
	public void setApplicationLogger (ApplicationLogger applicationLogger) {
		this.applicationLogger = applicationLogger;
	}

	@Override
	public ApplicationLogger getApplicationLogger () {
		return applicationLogger;
	}

	@Override
	public ApplicationType getType () {
		return ApplicationType.HeadlessDesktop;
	}

	@Override
	public int getVersion () {
		return 0;
	}

	@Override
	public long getJavaHeap () {
		return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
	}

	@Override
	public long getNativeHeap () {
		return getJavaHeap();
	}

	@Override
	public Preferences getPreferences (String name) {
		if (preferences.containsKey(name)) {
			return preferences.get(name);
		} else {
			Preferences prefs = new VulkanPreferences(
				new VulkanFileHandle(new File(appConfig.preferencesDirectory, name), appConfig.preferencesFileType));
			preferences.put(name, prefs);
			return prefs;
		}
	}

	@Override
	public Clipboard getClipboard () {
		return clipboard;
	}

	@Override
	public void postRunnable (Runnable runnable) {
		synchronized (runnables) {
			runnables.add(runnable);
		}
	}

	@Override
	public void exit () {
		running = false;
	}

	@Override
	public void addLifecycleListener (LifecycleListener listener) {
		synchronized (lifecycleListeners) {
			lifecycleListeners.add(listener);
		}
	}

	@Override
	public void removeLifecycleListener (LifecycleListener listener) {
		synchronized (lifecycleListeners) {
			lifecycleListeners.removeValue(listener, true);
		}
	}

	@Override
	public VulkanAudio createAudio (VulkanApplicationConfiguration config) {
		return new OpenALLwjgl3Audio(config.audioDeviceSimultaneousSources, config.audioDeviceBufferCount,
			config.audioDeviceBufferSize);
	}

	@Override
	public VulkanInput createInput (VulkanWindow window) {
		return new DefaultVulkanInput(window);
	}

	/** @return The selected physical device (VkPhysicalDevice). */
	public VkPhysicalDevice getPhysicalDevice () {
		if (physicalDevice == null) {
			throw new IllegalStateException("Physical Device not selected or initialized.");
		}
		return physicalDevice;
	}

	/** @return The VulkanDevice wrapper (contains logical device, queues, pool). */
	public VulkanDevice getVulkanDevice () { // Renamed from getVkDevice for clarity
		if (vulkanDevice == null) {
			throw new IllegalStateException("Vulkan Logical Device not created.");
		}
		return vulkanDevice;
	}

	/** @return The VMA Allocator handle. */
	public long getVmaAllocator () {
		if (vmaAllocator == VK_NULL_HANDLE) {
			throw new IllegalStateException("VMA Allocator not created or already destroyed.");
		}
		return vmaAllocator;
	}

	public void registerSurface (long windowHandle, long surfaceHandle) {
		if (surfaceHandle != VK10.VK_NULL_HANDLE) {
			windowSurfaces.put(windowHandle, surfaceHandle);
			Gdx.app.log("VulkanApplication", "Registered surface " + surfaceHandle + " for window " + windowHandle);
		} else {
			Gdx.app.error("VulkanApplication", "Attempted to register VK_NULL_HANDLE surface for window " + windowHandle);
		}
	}

	/** @return The index of the graphics queue family. */
	public int getGraphicsQueueFamily () {
		if (graphicsQueueFamily == null) {
			throw new IllegalStateException("Graphics Queue Family index not found or initialized.");
		}
		return graphicsQueueFamily;
	}

	/** @return The index of the present queue family. */
	public int getPresentQueueFamily () {
		if (presentQueueFamily == null) {
			throw new IllegalStateException("Present Queue Family index not found or initialized.");
		}
		return presentQueueFamily;
	}

	/** @return The primary window (first created). */
	public VulkanWindow getPrimaryWindow () {
		return windows.size > 0 ? windows.first() : null;
	}

	/** @return The window object for a given handle, or null if not found. */
	public VulkanWindow getWindow (long handle) {
		for (VulkanWindow window : windows) {
			if (window.getWindowHandle() == handle) {
				return window;
			}
		}
		return null;
	}

	protected Files createFiles () {
		return new VulkanFiles();
	}

	public VulkanApplicationConfiguration getAppConfig () {
		return appConfig;
	}

	/** Creates and registers a new VulkanWindow.
	 *
	 * @param listener The ApplicationListener for the new window.
	 * @param config The configuration for the new window.
	 * @return The newly created VulkanWindow. */
	public VulkanWindow newWindow (ApplicationListener listener, VulkanWindowConfiguration config) {
		Gdx.app.log(TAG, "Creating new window for listener: " + listener.getClass().getSimpleName());

		long newWindowHandle = createGlfwWindow(config, this.primaryWindowHandle);
		if (newWindowHandle == 0) {
			throw new GdxRuntimeException("Failed to create GLFW window for newWindow");
		}

		VulkanWindow newWindow = new VulkanWindow(listener, lifecycleListeners, config, this);

		VulkanInput newInput = new DefaultVulkanInput(newWindow); // Create input FOR THIS NEW WINDOW
		newWindow.setInputHandler(newInput); // Associate it using the setter method
		Gdx.app.log(TAG, "Created and set Input handler for new window. Hash: " + newInput.hashCode());

		Gdx.app.log(TAG, ">>> PAUSING before creating new window resources (Testing Timing)...");
		try {
			// Pause for 2 seconds (2000 milliseconds)
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			Gdx.app.error(TAG, "Thread sleep interrupted", e);
			Thread.currentThread().interrupt(); // Restore interrupt status
		}
		Gdx.app.log(TAG, "<<< RESUMING after pause.");

		try {
			newWindow.create(newWindowHandle);
			Gdx.app.log(TAG, "newWindow.create() finished for handle: " + newWindowHandle);
		} catch (Throwable t) {
			Gdx.app.error(TAG, "Failed to create new window's resources", t);
			// Clean up the GLFW window handle if creation failed mid-way
			GLFW.glfwDestroyWindow(newWindowHandle);
			// Rethrow or handle appropriately
			throw new GdxRuntimeException("Failed to create new window's resources", t);
		}

		newWindow.setVisible(config.initialVisible);

		synchronized (windows) { // Or use a concurrent collection
			windows.add(newWindow);
		}

		Gdx.app.log(TAG, "newWindow finished successfully.");
		return newWindow;
	}

	private long createGlfwWindow (VulkanWindowConfiguration config, long sharedContextWindow) {
		GLFW.glfwDefaultWindowHints();
		GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, config.windowResizable ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW.GLFW_MAXIMIZED, config.windowMaximized ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
		GLFW.glfwWindowHint(GLFW.GLFW_AUTO_ICONIFY, config.autoIconify ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
		if (!GLFW.glfwInit()) {
			throw new RuntimeException("Failed to initialize GLFW");
		}

		System.out.println("Creating Vulkan-compatible GLFW window...");
		long window = GLFW.glfwCreateWindow(config.windowWidth, config.windowHeight, "GdxVk", 0, 0);

		if (window == 0) {
			throw new RuntimeException("Failed to create GLFW window");
		}

		System.out.println("Vulkan-compatible window created. Window handle: " + window);

		GLFW.glfwSetWindowPos(window, 100, 100); // Set position explicitly
		System.out.println("Window position explicitly set to 100, 100.");

		return window;
	}

	private List<String> getRequiredInstanceExtensions (MemoryStack stack) {
		PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
		if (glfwExtensions == null) {
			throw new GdxRuntimeException("Failed to find required GLFW extensions for Vulkan");
		}

		// Use the existing helper to convert PointerBuffer to List<String>
		List<String> extensions = convertPointerBufferToStringList(glfwExtensions);

		// Add debug utils extension if validation layers are enabled
		if (enableValidationLayers) {
			// Make sure the list is modifiable if needed
			if (extensions == Collections.EMPTY_LIST) { // Check if the list from helper could be immutable empty
				extensions = new ArrayList<>();
			} else if (extensions.getClass() != ArrayList.class) { // Ensure it's a modifiable list type
				extensions = new ArrayList<>(extensions);
			}
			extensions.add(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
			System.out.println("Debug Utils extension enabled.");
		}

		System.out.println("Required instance extensions: " + extensions);
		if (enableValidationLayers && extensions.contains(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)) {
			System.out.println("[Validation Check] Added VK_EXT_DEBUG_UTILS_EXTENSION_NAME.");
		} else if (enableValidationLayers) {
			System.out.println("[Validation Check] Validation enabled, but Debug Utils extension missing?");
		}
		return extensions;
	}

	private List<String> getValidationLayers () {
		if (!enableValidationLayers) {
			System.out.println("Validation layers disabled.");
			return Collections.emptyList(); // Return empty list, not null
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer layerCount = stack.mallocInt(1);
			vkEnumerateInstanceLayerProperties(layerCount, null);

			if (layerCount.get(0) == 0) {
				System.err.println("WARNING: Validation layers requested, but no layers found!");
				return Collections.emptyList();
			}

			VkLayerProperties.Buffer availableLayers = VkLayerProperties.calloc(layerCount.get(0), stack);
			vkEnumerateInstanceLayerProperties(layerCount, availableLayers);

			// Check if all desired layers are available
			Set<String> availableLayerNames = new HashSet<>();
			for (int i = 0; i < availableLayers.limit(); i++) {
				availableLayerNames.add(availableLayers.get(i).layerNameString());
			}

			for (String layerName : DESIRED_VALIDATION_LAYERS) {
				if (!availableLayerNames.contains(layerName)) {
					System.err.println("WARNING: Validation layer '" + layerName + "' requested, but not available!");
					// You could choose to throw an error or proceed without it
					// For now, we'll proceed without validation if the standard one isn't found
					return Collections.emptyList();
				}
			}

			System.out.println("Validation layers enabled: " + DESIRED_VALIDATION_LAYERS);
			if (enableValidationLayers && !DESIRED_VALIDATION_LAYERS.isEmpty()
				&& availableLayerNames.containsAll(DESIRED_VALIDATION_LAYERS)) {
				System.out.println("[Validation Check] Found and returning requested layers: " + DESIRED_VALIDATION_LAYERS);
			} else if (enableValidationLayers) {
				System.out.println(
					"[Validation Check] Validation enabled, but requested layers NOT found or not available. Returning empty list.");
			} else {
				System.out.println("[Validation Check] Validation disabled. Returning empty list.");
			}
			return DESIRED_VALIDATION_LAYERS; // Return the constant list

		} catch (Exception e) {
			System.err.println("WARNING: Failed to check for validation layers. Disabling. Error: " + e.getMessage());
			return Collections.emptyList();
		}
	}

	private void setupDebugMessenger (MemoryStack stack) {
		if (!enableValidationLayers) return;

		// Create the callback instance lazily
		if (this.debugCallbackInstance == null) {
			this.debugCallbackInstance = new VkDebugUtilsMessengerCallbackEXT() {
				@Override
				public int invoke (int messageSeverity, int messageTypes, long pCallbackData, long pUserData) {
					VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
					// ... your logging logic ...
					String message = "VULKAN DEBUG: " + callbackData.pMessageString();
					if (Gdx.app != null)
						Gdx.app.error("VulkanDebug", message);
					else
						System.err.println(message);
					return VK_FALSE;
				}
			};
			if (Gdx.app != null)
				Gdx.app.log(TAG, "Debug callback instance created.");
			else
				System.out.println("VulkanApplication: Debug callback instance created.");
		}

		VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
			// ---> ADD or CORRECT these lines <---
			.messageSeverity( // Specify which message severities to receive
				// VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT | // Optional: very detailed output
				VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) // Must be non-zero!
			.messageType( // Specify which message types to receive
				VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
					| VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT) // Must be non-zero!
			// ---------------------------------
			.pfnUserCallback(this.debugCallbackInstance).pUserData(0);

		// ... rest of the method ...
		LongBuffer pDebugMessenger = stack.mallocLong(1);
		int err = EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(vulkanInstance.getRawInstance(), createInfo, null, pDebugMessenger);
		// ... handle error or success ...
		if (err == VK_SUCCESS) {
			this.debugMessenger = pDebugMessenger.get(0);
			if (Gdx.app != null)
				Gdx.app.log(TAG, "Vulkan Debug Messenger setup complete.");
			else
				System.out.println("VulkanApplication: Vulkan Debug Messenger setup complete.");
		} else {
			if (Gdx.app != null)
				Gdx.app.error(TAG, "Failed to set up Vulkan debug messenger: error code " + err);
			else
				System.err.println("WARNING: Failed to set up Vulkan debug messenger: error code " + err);
			this.debugMessenger = VK_NULL_HANDLE;
		}

	}

	private VkPhysicalDevice selectPhysicalDevice (MemoryStack stack) {
		org.lwjgl.vulkan.VkInstance rawInstance = vulkanInstance.getRawInstance();

		IntBuffer deviceCount = stack.mallocInt(1);
		vkEnumeratePhysicalDevices(rawInstance, deviceCount, null);

		if (deviceCount.get(0) == 0) {
			throw new GdxRuntimeException("Failed to find GPUs with Vulkan support!");
		}

		PointerBuffer ppPhysicalDevices = stack.mallocPointer(deviceCount.get(0));
		vkEnumeratePhysicalDevices(rawInstance, deviceCount, ppPhysicalDevices);

		VkPhysicalDevice selectedDevice = null;
		for (int i = 0; i < ppPhysicalDevices.limit(); i++) {
			VkPhysicalDevice device = new VkPhysicalDevice(ppPhysicalDevices.get(i), rawInstance);
			if (isDeviceSuitable(device, stack)) {
				selectedDevice = device;
				// Prefer discrete GPU if found, otherwise take the first suitable
				VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
				vkGetPhysicalDeviceProperties(device, properties);
				System.out.println("Selected Physical Device: " + properties.deviceNameString());
				// properties.free(); // Free the allocated properties struct
				if (properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
					break; // Found a discrete GPU, stop searching
				}
				// Keep searching in case a discrete GPU comes later
			}
		}

		if (selectedDevice == null) {
			throw new GdxRuntimeException("Failed to find a suitable GPU!");
		}

		return selectedDevice;
	}

	// Helper function to check device suitability
	private boolean isDeviceSuitable (VkPhysicalDevice device, MemoryStack stack) {
		// 1. Check Queue Families
		QueueFamilyIndices indices = findQueueFamilies(device, stack); // Reuse the find method

		// 2. Check Required Device Extensions
		boolean extensionsSupported = checkDeviceExtensionSupport(device, stack);

		// 3. Check Swapchain Support (requires extensions to be supported first)
		boolean swapChainAdequate = false;
		if (extensionsSupported) {
			// querySwapChainSupport allocates details on the current stack frame
			SwapChainSupportDetails swapChainSupport = querySwapChainSupport(device, stack);
			// Check if swapchain support is minimally adequate
			swapChainAdequate = swapChainSupport.formats.limit() > 0 && swapChainSupport.presentModes.limit() > 0;
			// *** No need to free swapChainSupport members allocated on the stack ***
		}

		return indices.isComplete() && extensionsSupported && swapChainAdequate; // && featuresSupported;
	}

	// Helper to check device extension support
	private boolean checkDeviceExtensionSupport (VkPhysicalDevice device, MemoryStack stack) {
		IntBuffer extensionCount = stack.mallocInt(1);
		vkEnumerateDeviceExtensionProperties(device, (String)null, extensionCount, null);

		if (extensionCount.get(0) == 0 && !DEVICE_EXTENSIONS.isEmpty()) {
			return false; // No extensions available, but we need some
		}

		VkExtensionProperties.Buffer availableExtensions = VkExtensionProperties.calloc(extensionCount.get(0), stack);
		vkEnumerateDeviceExtensionProperties(device, (String)null, extensionCount, availableExtensions);

		Set<String> availableExtensionNames = new HashSet<>();
		for (int i = 0; i < availableExtensions.limit(); i++) {
			availableExtensionNames.add(availableExtensions.get(i).extensionNameString());
		}

		// Check if all required extensions are present
		return availableExtensionNames.containsAll(DEVICE_EXTENSIONS);
	}

	// Helper to query swap chain support details
	private SwapChainSupportDetails querySwapChainSupport (VkPhysicalDevice device, MemoryStack stack) {
		SwapChainSupportDetails details = new SwapChainSupportDetails();

		// Allocate these on the stack passed in, they will be freed by the caller (isDeviceSuitable)
		details.capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
		vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, this.primarySurface, details.capabilities);

		IntBuffer formatCount = stack.mallocInt(1);
		vkGetPhysicalDeviceSurfaceFormatsKHR(device, this.primarySurface, formatCount, null);
		if (formatCount.get(0) != 0) {
			details.formats = VkSurfaceFormatKHR.malloc(formatCount.get(0), stack);
			vkGetPhysicalDeviceSurfaceFormatsKHR(device, this.primarySurface, formatCount, details.formats);
		} else {
			details.formats = VkSurfaceFormatKHR.malloc(0, stack); // Allocate empty buffer if none found
		}

		IntBuffer presentModeCount = stack.mallocInt(1);
		vkGetPhysicalDeviceSurfacePresentModesKHR(device, this.primarySurface, presentModeCount, null);
		if (presentModeCount.get(0) != 0) {
			details.presentModes = stack.mallocInt(presentModeCount.get(0));
			vkGetPhysicalDeviceSurfacePresentModesKHR(device, this.primarySurface, presentModeCount, details.presentModes);
		} else {
			details.presentModes = stack.mallocInt(0); // Allocate empty buffer if none found
		}

		return details;
	}

	public void createVmaAllocator (VulkanInstance vkInstanceWrapper, VulkanDevice vulkanDeviceWrapper) {
		Gdx.app.log(TAG, "Creating VMA Allocator...");
		try (MemoryStack stack = stackPush()) {
			// 1. Set up Vulkan functions for VMA (LWJGL helper)
			VmaVulkanFunctions vulkanFunctions = VmaVulkanFunctions.calloc(stack).set(vkInstanceWrapper.getRawInstance(),
				vulkanDeviceWrapper.getRawDevice()); // Pass VkInstance, VkDevice

			// 2. Set up Allocator Create Info
			VmaAllocatorCreateInfo allocatorInfo = VmaAllocatorCreateInfo.calloc(stack).flags(0) // Optional flags (e.g.,
																																// VMA_ALLOCATOR_CREATE_EXT_MEMORY_BUDGET_BIT)
				.physicalDevice(vulkanDeviceWrapper.getPhysicalDevice()) // Pass VkPhysicalDevice
				.device(vulkanDeviceWrapper.getRawDevice()) // Pass VkDevice
				.pVulkanFunctions(vulkanFunctions).instance(vkInstanceWrapper.getRawInstance()) // Pass VkInstance
				.vulkanApiVersion(VK_API_VERSION_1_0); // Or your target version (VK_API_VERSION_1_1 etc.)
			// Optional: .pHeapSizeLimit(), .pTypeExternalMemoryHandleTypes()

			// 3. Create the Allocator
			PointerBuffer pAllocator = stack.mallocPointer(1); // VmaAllocator is pointer type
			vkCheck(vmaCreateAllocator(allocatorInfo, pAllocator), "Failed to create VMA allocator"); // Use vkCheck or check result
			this.vmaAllocator = pAllocator.get(0); // Store the handle

			if (this.vmaAllocator == VK_NULL_HANDLE) {
				throw new GdxRuntimeException("VMA Allocator creation succeeded according to result code, but handle is NULL!");
			}
			Gdx.app.log(TAG, "VMA Allocator created successfully. Handle: " + this.vmaAllocator);
		}
	}

	// Ensure you have a corresponding destroy method called before device/instance destruction
	public void destroyVmaAllocator () {
		if (vmaAllocator != VK_NULL_HANDLE) {
			Gdx.app.log(TAG, "Destroying VMA Allocator...");
			vmaDestroyAllocator(vmaAllocator);
			vmaAllocator = VK_NULL_HANDLE;
			Gdx.app.log(TAG, "VMA Allocator destroyed.");
		}
	}

	private QueueFamilyIndices findQueueFamilies (VkPhysicalDevice device, MemoryStack stack) {
		QueueFamilyIndices indices = new QueueFamilyIndices();

		IntBuffer queueFamilyCount = stack.mallocInt(1);
		vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null);

		if (queueFamilyCount.get(0) == 0) {
			throw new GdxRuntimeException("Physical device has no queue families!");
		}

		VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.calloc(queueFamilyCount.get(0), stack);
		vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies);

		IntBuffer pPresentSupport = stack.mallocInt(1); // Buffer for surface support query result

		for (int i = 0; i < queueFamilies.limit(); i++) {
			// Check for Graphics support
			if ((queueFamilies.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
				indices.graphicsFamily = i;
			}

			// Check for Presentation support (needs the surface)
			vkGetPhysicalDeviceSurfaceSupportKHR(device, i, this.primarySurface, pPresentSupport);
			if (pPresentSupport.get(0) == VK_TRUE) {
				indices.presentFamily = i;
			}

			// If we found both, we can stop early (though separate queues can be better)
			if (indices.isComplete()) {
				break;
			}
		}

		if (!indices.isComplete()) {
			throw new GdxRuntimeException("Could not find suitable queue families on the physical device!");
		}

		System.out.println("Found Graphics Queue Family: " + indices.graphicsFamily);
		System.out.println("Found Present Queue Family: " + indices.presentFamily);
		return indices;
	}

	public VulkanWindow getCurrentWindow () {
		return this.currentWindow;
	}

	public static class QueueFamilyIndices {
		public Integer graphicsFamily;
		public Integer presentFamily; // Queue family for presenting to the surface

		public boolean isComplete () {
			return graphicsFamily != null && presentFamily != null;
		}
	}

	private static class SwapChainSupportDetails {
		VkSurfaceCapabilitiesKHR capabilities;
		VkSurfaceFormatKHR.Buffer formats;
		IntBuffer presentModes;
	}
}
