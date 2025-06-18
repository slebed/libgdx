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
/*
 * Copyright 2010 Mario Zechner (contact@badlogicgames.com), Nathan Sweet (admin@esotericsoftware.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.badlogic.gdx.tests.vulkan;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.tests.utils.GdxTest;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.StreamUtils;

/**
 * List of GdxTest classes. To be used by the test launchers. If you write your own test, add it in here!
 *
 * @author badlogicgames@gmail.com
 */
public class GdxVulkanTests {
    private static final String TAG = "GdxVulkanTests";
    public static List<Class<? extends GdxTest>> tests; // Declare final list
    public static List<Class<?>> techdemos;

    // Use a static initializer block to create and populate the list
    static {
        // Create a temporary list using Arrays.asList (this list might be fixed-size)
        List<Class<? extends GdxTest>> initialTests = Arrays.asList(
                // @off
                //VulkanClearScreenTest.class,
                VulkanScene2dTest.class,
                VulkanSpriteBatchPerformanceTest.class,
                VulkanSpriteBatchPerformanceTest2.class,
                VulkanSpriteBatchTest.class,
                VulkanSpriteBatchStressTest.class,
                VulkanSpriteBatchTextureSwitchTest.class,
                Vulkan3DCubeTest.class,
                Vulkan3DTexturedCubeTest.class,
                VulkanFreeTypeFontTest.class,
                Vulkan3DLitTexturedCubeTest.class
                // @on
        );

        // Create the final ArrayList using the constructor that accepts a Collection
        tests = new ArrayList<>(initialTests);
    }

    static {
        List<Class<? >>initialTechDemos = Arrays.asList(
                TechDemo_BulletHell.class
        );

        techdemos = new ArrayList<>(initialTechDemos);
    }

    static final ObjectMap<String, String> obfuscatedToOriginal = new ObjectMap<>();
    static final ObjectMap<String, String> originalToObfuscated = new ObjectMap<>();

    static {
        InputStream mappingInput = GdxVulkanTests.class.getResourceAsStream("/mapping.txt");
        if (mappingInput != null) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(mappingInput), 512);
                while (true) {
                    String line = reader.readLine();
                    if (line == null) break;
                    if (line.startsWith("    ")) continue;
                    String[] split = line.replace(":", "").split(" -> ");
                    String original = split[0];
                    if (original.indexOf('.') != -1) original = original.substring(original.lastIndexOf('.') + 1);
                    originalToObfuscated.put(original, split[1]);
                    obfuscatedToOriginal.put(split[1], original);
                }
                reader.close();
            } catch (Exception ex) {
                System.out.println("GdxTests: Error reading mapping file: mapping.txt");
                ex.printStackTrace();
            } finally {
                StreamUtils.closeQuietly(reader);
            }
        }
    }

    public static List<String> getTestNames() {
        List<String> names = new ArrayList<>(tests.size());
        for (Class clazz : tests) {
            names.add(obfuscatedToOriginal.get(clazz.getSimpleName(), clazz.getSimpleName()));
        }
        Collections.sort(names);
        return names;
    }

    public static List<String> getTechDemoNames() {
        List<String> names = new ArrayList<>(techdemos.size());
        for (Class clazz : techdemos) {
            names.add(obfuscatedToOriginal.get(clazz.getSimpleName(), clazz.getSimpleName()));
        }
        Collections.sort(names);
        return names;
    }

    public static Class<? extends GdxTest> forName(String name) {
        name = originalToObfuscated.get(name, name);
        for (Class clazz : tests)
            if (clazz.getSimpleName().equals(name)) return clazz;
        return null;
    }

    public static GdxTest newTest(String testName) {
        testName = originalToObfuscated.get(testName, testName);
        try {
            return Objects.requireNonNull(forName(testName)).newInstance();
        } catch (InstantiationException e) {
            Gdx.app.error(TAG, "Failed to instantiate test: " + testName);
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            Gdx.app.error(TAG, "Failed with illegal access to test: " + testName);
            e.printStackTrace();
        }
        return null;
    }

    public static Class<?> forTechDemoName(String name) { // Renamed for clarity
        name = originalToObfuscated.get(name, name);
        for (Class<?> clazz : techdemos) { // Iterate through techdemos list
            if (clazz.getSimpleName().equals(name)) return clazz;
        }
        return null;
    }

    public static ApplicationListener newTechDemo(String techDemoName) {
        techDemoName = originalToObfuscated.get(techDemoName, techDemoName);
        try {
            Class<?> techDemoClass = forTechDemoName(techDemoName);
            if (techDemoClass != null && ApplicationListener.class.isAssignableFrom(techDemoClass)) {
                return (ApplicationListener) techDemoClass.getDeclaredConstructor().newInstance();
            } else if (techDemoClass == null) {
                Gdx.app.error(TAG, "Tech demo class not found: " + techDemoName);
            } else {
                Gdx.app.error(TAG, "Tech demo class " + techDemoName + " does not implement ApplicationListener.");
            }
        } catch (ReflectiveOperationException e) { // Catch broader reflective exceptions
            Gdx.app.error(TAG, "Failed to instantiate tech demo: " + techDemoName, e);
        }
        return null;
    }
}
