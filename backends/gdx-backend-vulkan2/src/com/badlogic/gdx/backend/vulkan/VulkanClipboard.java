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

import org.lwjgl.glfw.GLFW;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Clipboard;

/** Clipboard implementation for desktop that uses the system clipboard via GLFW.
 * @author mzechner */
public class VulkanClipboard implements Clipboard {
	@Override
	public boolean hasContents () {
		String contents = getContents();
		return contents != null && !contents.isEmpty();
	}

	@Override
	public String getContents () {
		long windowHandle = ((VulkanGraphics)Gdx.graphics).getWindowHandle();
		if (windowHandle == 0) return null; // Handle case where handle might be invalid
		return GLFW.glfwGetClipboardString(windowHandle);
	}

	@Override
	public void setContents (String content) {
		long windowHandle = ((VulkanGraphics)Gdx.graphics).getWindowHandle();
		if (windowHandle == 0) return; // Handle case where handle might be invalid
		GLFW.glfwSetClipboardString(windowHandle, content);
	}
}
