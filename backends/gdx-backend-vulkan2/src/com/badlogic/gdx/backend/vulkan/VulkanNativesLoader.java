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

import com.badlogic.gdx.utils.GdxNativesLoader;

public final class VulkanNativesLoader {
	static public boolean loadCalled = false;

	static public synchronized void load () {
		if (loadCalled) {
			System.out.println("[VulkanNativesLoader] load() already called."); // Log if called again
			return;
		}
		System.out.println("[VulkanNativesLoader] load() called. Attempting GdxNativesLoader.load()..."); // Log entry
		loadCalled = true;
		try {
			GdxNativesLoader.load();
			System.out.println("[VulkanNativesLoader] GdxNativesLoader.load() completed WITHOUT throwing."); // Log success
		} catch (Throwable t) {
			System.err.println("[VulkanNativesLoader] GdxNativesLoader.load() THREW an exception!"); // Log failure
			throw t; // Re-throw original error
		}
	}
}
