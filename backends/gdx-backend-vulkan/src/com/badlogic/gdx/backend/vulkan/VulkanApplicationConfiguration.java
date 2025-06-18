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

import java.io.PrintStream;
import java.nio.IntBuffer;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.LifecycleListener;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWVidMode.Buffer;

import com.badlogic.gdx.Audio;
import com.badlogic.gdx.Files;
import com.badlogic.gdx.Files.FileType;
import com.badlogic.gdx.Graphics.DisplayMode;
import com.badlogic.gdx.Graphics.Monitor;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.audio.Music;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.math.GridPoint2;

public class VulkanApplicationConfiguration extends VulkanWindowConfiguration {
    public static PrintStream errorStream = System.err;

    SwapchainPresentMode presentMode = null;//SwapchainPresentMode.IMMEDIATE;//MAILBOX;//.FIFO; // VSync/Buffering strategy
    GpuPreference gpuPreference = GpuPreference.ANY; // GPU selection hint
    String preferencesDirectory = ".prefs/";
    Files.FileType preferencesFileType = FileType.External;
    HdpiMode hdpiMode = HdpiMode.Logical;

    /**
     * The maximum number of threads to use for network requests. Default is {@link Integer#MAX_VALUE}.
     */
    int maxNetThreads = Integer.MAX_VALUE;

    int audioDeviceSimultaneousSources = 16;
    int audioDeviceBufferSize = 512;
    int audioDeviceBufferCount = 9;

    int MAX_FRAMES_IN_FLIGHT = 2;

    int r = 8, g = 8, b = 8, a = 8;
    int depth = 16, stencil = 0;
    int samples = 0;
    int idleFPS = 60;
    int foregroundFPS = 0;

    boolean disableAudio = false;
    boolean pauseWhenMinimized = true;
    boolean pauseWhenLostFocus = false;
    boolean transparentFramebuffer;
    boolean preferSrgbFramebuffer = true; // Request sRGB swapchain format if available
    boolean debugLog = false;

    public enum SwapchainPresentMode {
        /**
         * Standard VSync, waits for vertical blank. Guaranteed to be available. Maps to VK_PRESENT_MODE_FIFO_KHR.
         */
        FIFO,
        /**
         * VSync, but allows tearing if the application is late. Maps to VK_PRESENT_MODE_FIFO_RELAXED_KHR.
         */
        FIFO_RELAXED,
        /**
         * No VSync, renders immediately, may cause tearing. Maps to VK_PRESENT_MODE_IMMEDIATE_KHR.
         */
        IMMEDIATE,
        /**
         * A low-latency VSync mode (often triple-buffered), preferred for performance if available. Maps to VK_PRESENT_MODE_MAILBOX_KHR.
         */
        MAILBOX
    }

    public enum GpuPreference {
        /**
         * No preference, backend chooses default.
         */
        ANY,
        /**
         * Prefer a dedicated/discrete GPU for higher performance.
         */
        DISCRETE,
        /**
         * Prefer an integrated GPU, potentially for power saving.
         */
        INTEGRATED
    }

    boolean enableValidationLayers = false; // Enable Vulkan validation layers
    PrintStream validationLayerMessageStream = System.err; // Output for validation layers

    static VulkanApplicationConfiguration copy(VulkanApplicationConfiguration config) {
        VulkanApplicationConfiguration copy = new VulkanApplicationConfiguration();
        copy.set(config);
        return copy;
    }

    void set(VulkanApplicationConfiguration config) {
        super.setWindowConfiguration(config);
        disableAudio = config.disableAudio;
        audioDeviceSimultaneousSources = config.audioDeviceSimultaneousSources;
        audioDeviceBufferSize = config.audioDeviceBufferSize;
        audioDeviceBufferCount = config.audioDeviceBufferCount;
        r = config.r;
        g = config.g;
        b = config.b;
        a = config.a;
        depth = config.depth;
        stencil = config.stencil;
        samples = config.samples;
        transparentFramebuffer = config.transparentFramebuffer;
        idleFPS = config.idleFPS;
        foregroundFPS = config.foregroundFPS;
        pauseWhenMinimized = config.pauseWhenMinimized;
        pauseWhenLostFocus = config.pauseWhenLostFocus;
        preferencesDirectory = config.preferencesDirectory;
        preferencesFileType = config.preferencesFileType;
        hdpiMode = config.hdpiMode;
        preferSrgbFramebuffer = config.preferSrgbFramebuffer;
        presentMode = config.presentMode;
        gpuPreference = config.gpuPreference;
        enableValidationLayers = config.enableValidationLayers;
        validationLayerMessageStream = config.validationLayerMessageStream;
    }

    /**
     * @param visibility whether the window will be visible on creation. (default true)
     */
    public void setInitialVisible(boolean visibility) {
        this.initialVisible = visibility;
    }

    /**
     * Whether to disable audio or not. If set to true, the returned audio class instances like {@link Audio} or {@link Music}
     * will be mock implementations.
     */
    public void disableAudio(boolean disableAudio) {
        this.disableAudio = disableAudio;
    }

    /**
     * Sets the maximum number of threads to use for network requests.
     */
    public void setMaxNetThreads(int maxNetThreads) {
        this.maxNetThreads = maxNetThreads;
    }

    /**
     * Sets the audio device configuration.
     *
     * @param simultaneousSources the maximum number of sources that can be played simultaniously (default 16)
     * @param bufferSize          the audio device buffer size in samples (default 512)
     * @param bufferCount         the audio device buffer count (default 9)
     */
    public void setAudioConfig(int simultaneousSources, int bufferSize, int bufferCount) {
        this.audioDeviceSimultaneousSources = simultaneousSources;
        this.audioDeviceBufferSize = bufferSize;
        this.audioDeviceBufferCount = bufferCount;
    }

    /**
     * Sets the bit depth of the color, depth and stencil buffer as well as multi-sampling.
     *
     * @param r       red bits (default 8)
     * @param g       green bits (default 8)
     * @param b       blue bits (default 8)
     * @param a       alpha bits (default 8)
     * @param depth   depth bits (default 16)
     * @param stencil stencil bits (default 0)
     * @param samples MSAA samples (default 0)
     */
    public void setBackBufferConfig(int r, int g, int b, int a, int depth, int stencil, int samples) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
        this.depth = depth;
        this.stencil = stencil;
        this.samples = samples;
    }

    /**
     * Set transparent window hint. Results may vary on different OS and GPUs. Usage with the ANGLE backend is less consistent.
     *
     * @param transparentFramebuffer
     */
    public void setTransparentFramebuffer(boolean transparentFramebuffer) {
        this.transparentFramebuffer = transparentFramebuffer;
    }

    /**
     * Sets the polling rate during idle time in non-continuous rendering mode. Must be positive. Default is 60.
     */
    public void setIdleFPS(int fps) {
        this.idleFPS = fps;
    }

    /**
     * Sets the target framerate for the application. The CPU sleeps as needed. Must be positive. Use 0 to never sleep. Default is
     * 0.
     */
    public void setForegroundFPS(int fps) {
        this.foregroundFPS = fps;
    }

    /**
     * Sets whether to pause the application {@link ApplicationListener#pause()} and fire
     * {@link LifecycleListener#pause()}/{@link LifecycleListener#resume()} events on when window is minimized/restored.
     **/
    public void setPauseWhenMinimized(boolean pauseWhenMinimized) {
        this.pauseWhenMinimized = pauseWhenMinimized;
    }

    /**
     * Sets whether to pause the application {@link ApplicationListener#pause()} and fire
     * {@link LifecycleListener#pause()}/{@link LifecycleListener#resume()} events on when window loses/gains focus.
     **/
    public void setPauseWhenLostFocus(boolean pauseWhenLostFocus) {
        this.pauseWhenLostFocus = pauseWhenLostFocus;
    }

    /**
     * Sets the directory where {@link Preferences} will be stored, as well as the file type to be used to store them. Defaults to
     * "$USER_HOME/.prefs/" and {@link FileType#External}.
     */
    public void setPreferencesConfig(String preferencesDirectory, Files.FileType preferencesFileType) {
        this.preferencesDirectory = preferencesDirectory;
        this.preferencesFileType = preferencesFileType;
    }

    /**
     * Defines how HDPI monitors are handled. Operating systems may have a per-monitor HDPI scale setting. The operating system
     * may report window width/height and mouse coordinates in a logical coordinate system at a lower resolution than the actual
     * physical resolution. This setting allows you to specify whether you want to work in logical or raw pixel units. See
     * {@link HdpiMode} for more information. Note that some OpenGL functions like {@link GL20#glViewport(int, int, int, int)} and
     * {@link GL20#glScissor(int, int, int, int)} require raw pixel units. Use {@link HdpiUtils} to help with the conversion if
     * HdpiMode is set to {@link HdpiMode#Logical}. Defaults to {@link HdpiMode#Logical}.
     */
    public void setHdpiMode(HdpiMode mode) {
        this.hdpiMode = mode;
    }

    public void setPreferSrgbFramebuffer(boolean preferSrgb) {
        this.preferSrgbFramebuffer = preferSrgb;
    }

    public void setPresentationMode(SwapchainPresentMode mode) {
        this.presentMode = mode;
    }

    public void setGpuPreference(GpuPreference preference) {
        this.gpuPreference = preference;
    }

    public void enableValidationLayers(boolean enable, PrintStream stream) {
        this.enableValidationLayers = enable;
        this.validationLayerMessageStream = (stream != null) ? stream : System.err;
    }

    /**
     * @return the currently active {@link DisplayMode} of the primary monitor
     */
    public static DisplayMode getDisplayMode() {
        VulkanApplication.initializeGlfw();
        GLFWVidMode videoMode = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
        return new VulkanGraphics.VulkanDisplayMode(GLFW.glfwGetPrimaryMonitor(), videoMode.width(), videoMode.height(),
                videoMode.refreshRate(), videoMode.redBits() + videoMode.greenBits() + videoMode.blueBits());
    }

    /**
     * @return the currently active {@link DisplayMode} of the given monitor
     */
    public static DisplayMode getDisplayMode(Monitor monitor) {
        VulkanApplication.initializeGlfw();
        GLFWVidMode videoMode = GLFW.glfwGetVideoMode(((VulkanGraphics.VulkanMonitor) monitor).monitorHandle);
        return new VulkanGraphics.VulkanDisplayMode(((VulkanGraphics.VulkanMonitor) monitor).monitorHandle, videoMode.width(),
                videoMode.height(), videoMode.refreshRate(), videoMode.redBits() + videoMode.greenBits() + videoMode.blueBits());
    }

    /**
     * @return the available {@link DisplayMode}s of the primary monitor
     */
    public static DisplayMode[] getDisplayModes() {
        VulkanApplication.initializeGlfw();
        Buffer videoModes = GLFW.glfwGetVideoModes(GLFW.glfwGetPrimaryMonitor());
        DisplayMode[] result = new DisplayMode[videoModes.limit()];
        for (int i = 0; i < result.length; i++) {
            GLFWVidMode videoMode = videoModes.get(i);
            result[i] = new VulkanGraphics.VulkanDisplayMode(GLFW.glfwGetPrimaryMonitor(), videoMode.width(), videoMode.height(),
                    videoMode.refreshRate(), videoMode.redBits() + videoMode.greenBits() + videoMode.blueBits());
        }
        return result;
    }

    /**
     * @return the available {@link DisplayMode}s of the given {@link Monitor}
     */
    public static DisplayMode[] getDisplayModes(Monitor monitor) {
        VulkanApplication.initializeGlfw();
        Buffer videoModes = GLFW.glfwGetVideoModes(((VulkanGraphics.VulkanMonitor) monitor).monitorHandle);
        DisplayMode[] result = new DisplayMode[videoModes.limit()];
        for (int i = 0; i < result.length; i++) {
            GLFWVidMode videoMode = videoModes.get(i);
            result[i] = new VulkanGraphics.VulkanDisplayMode(((VulkanGraphics.VulkanMonitor) monitor).monitorHandle,
                    videoMode.width(), videoMode.height(), videoMode.refreshRate(),
                    videoMode.redBits() + videoMode.greenBits() + videoMode.blueBits());
        }
        return result;
    }

    /**
     * @return the primary {@link Monitor}
     */
    public static Monitor getPrimaryMonitor() {
        VulkanApplication.initializeGlfw();
        return toVulkanMonitor(GLFW.glfwGetPrimaryMonitor());
    }

    /**
     * @return the connected {@link Monitor}s
     */
    public static Monitor[] getMonitors() {
        VulkanApplication.initializeGlfw();
        PointerBuffer glfwMonitors = GLFW.glfwGetMonitors();
        if (glfwMonitors == null) return new Monitor[0];
        Monitor[] monitors = new Monitor[glfwMonitors.limit()];
        for (int i = 0; i < glfwMonitors.limit(); i++) {
            monitors[i] = toVulkanMonitor(glfwMonitors.get(i));
        }
        return monitors;
    }

    static VulkanGraphics.VulkanMonitor toVulkanMonitor(long glfwMonitor) {
        IntBuffer tmp = BufferUtils.createIntBuffer(1);
        IntBuffer tmp2 = BufferUtils.createIntBuffer(1);
        GLFW.glfwGetMonitorPos(glfwMonitor, tmp, tmp2);
        int virtualX = tmp.get(0);
        int virtualY = tmp2.get(0);
        String name = GLFW.glfwGetMonitorName(glfwMonitor);
        return new VulkanGraphics.VulkanMonitor(glfwMonitor, virtualX, virtualY, name);
    }

    static GridPoint2 calculateCenteredWindowPosition(VulkanGraphics.VulkanMonitor monitor, int newWidth, int newHeight) {
        IntBuffer tmp = BufferUtils.createIntBuffer(1);
        IntBuffer tmp2 = BufferUtils.createIntBuffer(1);
        IntBuffer tmp3 = BufferUtils.createIntBuffer(1);
        IntBuffer tmp4 = BufferUtils.createIntBuffer(1);

        DisplayMode displayMode = getDisplayMode(monitor);

        GLFW.glfwGetMonitorWorkarea(monitor.monitorHandle, tmp, tmp2, tmp3, tmp4);
        int workareaWidth = tmp3.get(0);
        int workareaHeight = tmp4.get(0);

        int minX, minY, maxX, maxY;

        // If the new width is greater than the working area, we have to ignore stuff like the taskbar for centering and use the
        // whole monitor's size
        if (newWidth > workareaWidth) {
            minX = monitor.virtualX;
            maxX = displayMode.width;
        } else {
            minX = tmp.get(0);
            maxX = workareaWidth;
        }
        // The same is true for height
        if (newHeight > workareaHeight) {
            minY = monitor.virtualY;
            maxY = displayMode.height;
        } else {
            minY = tmp2.get(0);
            maxY = workareaHeight;
        }

        return new GridPoint2(Math.max(minX, minX + (maxX - newWidth) / 2), Math.max(minY, minY + (maxY - newHeight) / 2));
    }

    public int getMaxFramesInFlight() {
        return MAX_FRAMES_IN_FLIGHT;
    }

    public void setMaxFramesInFlight(int maxFramesInFlight) {
        MAX_FRAMES_IN_FLIGHT = maxFramesInFlight;
    }

}
