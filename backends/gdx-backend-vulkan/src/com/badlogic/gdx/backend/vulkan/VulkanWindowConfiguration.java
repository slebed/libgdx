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

import java.util.Arrays;

import com.badlogic.gdx.Files.FileType;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.Graphics.DisplayMode;
import com.badlogic.gdx.graphics.Color;

public class VulkanWindowConfiguration {

	VulkanGraphics.VulkanMonitor maximizedMonitor;
	VulkanApplicationConfiguration.SwapchainPresentMode presentMode = VulkanApplicationConfiguration.SwapchainPresentMode.FIFO;
	VulkanWindowListener windowListener;
	VulkanGraphics.VulkanDisplayMode fullscreenMode;
	FileType windowIconFileType;
	String[] windowIconPaths;
	String title;
	Color initialBackgroundColor = Color.BLACK;
	int windowX = -1;
	int windowY = -1;
	int windowWidth = 640;
	int windowHeight = 480;
	int windowMinWidth = -1, windowMinHeight = -1, windowMaxWidth = -1, windowMaxHeight = -1;
	boolean windowResizable = true;
	boolean windowDecorated = true;
	boolean windowMaximized = false;
	boolean autoIconify = true;
	boolean initialVisible = true;

	void setWindowConfiguration (VulkanWindowConfiguration config) {
		windowX = config.windowX;
		windowY = config.windowY;
		windowWidth = config.windowWidth;
		windowHeight = config.windowHeight;
		windowMinWidth = config.windowMinWidth;
		windowMinHeight = config.windowMinHeight;
		windowMaxWidth = config.windowMaxWidth;
		windowMaxHeight = config.windowMaxHeight;
		windowResizable = config.windowResizable;
		windowDecorated = config.windowDecorated;
		windowMaximized = config.windowMaximized;
		maximizedMonitor = config.maximizedMonitor;
		autoIconify = config.autoIconify;
		windowIconFileType = config.windowIconFileType;
		if (config.windowIconPaths != null) windowIconPaths = Arrays.copyOf(config.windowIconPaths, config.windowIconPaths.length);
		windowListener = config.windowListener;
		fullscreenMode = config.fullscreenMode;
		title = config.title;
		initialBackgroundColor = config.initialBackgroundColor;
		initialVisible = config.initialVisible;
		this.presentMode = config.presentMode;
	}

	/** @param visibility whether the window will be visible on creation. (default true) */
	public void setInitialVisible (boolean visibility) {
		this.initialVisible = visibility;
	}

	/** Sets the app to use windowed mode.
	 * 
	 * @param width the width of the window (default 640)
	 * @param height the height of the window (default 480) */
	public void setWindowedMode (int width, int height) {
		this.windowWidth = width;
		this.windowHeight = height;
	}

	/** @param resizable whether the windowed mode window is resizable (default true) */
	public void setResizable (boolean resizable) {
		this.windowResizable = resizable;
	}

	/** @param decorated whether the windowed mode window is decorated, i.e. displaying the title bars (default true) */
	public void setDecorated (boolean decorated) {
		this.windowDecorated = decorated;
	}

	/** @param maximized whether the window starts maximized. Ignored if the window is full screen. (default false) */
	public void setMaximized (boolean maximized) {
		this.windowMaximized = maximized;
	}

	/** @param monitor what monitor the window should maximize to */
	public void setMaximizedMonitor (Graphics.Monitor monitor) {
		this.maximizedMonitor = (VulkanGraphics.VulkanMonitor)monitor;
	}

	/** @param autoIconify whether the window should automatically iconify and restore previous video mode on input focus loss.
	 *           (default true) Does nothing in windowed mode. */
	public void setAutoIconify (boolean autoIconify) {
		this.autoIconify = autoIconify;
	}

	/** Sets the position of the window in windowed mode. Default -1 for both coordinates for centered on primary monitor. */
	public void setWindowPosition (int x, int y) {
		windowX = x;
		windowY = y;
	}

	/** Sets minimum and maximum size limits for the window. If the window is full screen or not resizable, these limits are
	 * ignored. The default for all four parameters is -1, which means unrestricted. */
	public void setWindowSizeLimits (int minWidth, int minHeight, int maxWidth, int maxHeight) {
		windowMinWidth = minWidth;
		windowMinHeight = minHeight;
		windowMaxWidth = maxWidth;
		windowMaxHeight = maxHeight;
	}

	/** Sets the icon that will be used in the window's title bar. Has no effect in macOS, which doesn't use window icons.
	 * @param filePaths One or more {@linkplain FileType#Internal internal} image paths. Must be JPEG, PNG, or BMP format. The one
	 *           closest to the system's desired size will be scaled. Good sizes include 16x16, 32x32 and 48x48. */
	public void setWindowIcon (String... filePaths) {
		setWindowIcon(FileType.Internal, filePaths);
	}

	/** Sets the icon that will be used in the window's title bar. Has no effect in macOS, which doesn't use window icons.
	 * @param fileType The type of file handle the paths are relative to.
	 * @param filePaths One or more image paths, relative to the given {@linkplain FileType}. Must be JPEG, PNG, or BMP format. The
	 *           one closest to the system's desired size will be scaled. Good sizes include 16x16, 32x32 and 48x48. */
	public void setWindowIcon (FileType fileType, String... filePaths) {
		windowIconFileType = fileType;
		windowIconPaths = filePaths;
	}

	/** Sets the {@link VulkanWindowListener} which will be informed about iconficiation, focus loss and window close events. */
	public void setWindowListener (VulkanWindowListener windowListener) {
		this.windowListener = windowListener;
	}

	/** Sets the app to use fullscreen mode. Use the static methods like {@link VulkanApplicationConfiguration#getDisplayMode()} on
	 * this class to enumerate connected monitors and their fullscreen display modes. */
	public void setFullscreenMode (DisplayMode mode) {
		this.fullscreenMode = (VulkanGraphics.VulkanDisplayMode)mode;
	}

	/** Sets the window title. If null, the application listener's class name is used. */
	public void setTitle (String title) {
		this.title = title;
	}

	/** Sets the initial background color. Defaults to black. */
	public void setInitialBackgroundColor (Color color) {
		initialBackgroundColor = color;
	}

	public VulkanApplicationConfiguration.SwapchainPresentMode getPresentMode(){
		return presentMode;
	}

	public void setPresentMode(VulkanApplicationConfiguration.SwapchainPresentMode swapchainPresentMode) {
		this.presentMode = swapchainPresentMode;
	}
}
