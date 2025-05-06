package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

public final class VulkanDebugLogger { // final and private constructor for utility class pattern

    private static final String TAG_PREFIX = "VulkanDebug";
    // Use EnumSet for efficient storage and lookup of enum constants
    private static final Set<VulkanLogCategory> enabledCategories = EnumSet.noneOf(VulkanLogCategory.class);

    private VulkanDebugLogger() {} // Prevent instantiation

    // --- Configuration Methods ---

    public static void enableCategory(VulkanLogCategory category) {
        if (enabledCategories.add(category)) { // Add returns true if the set was changed
            if (Gdx.app != null) { // Check Gdx.app in case called very early or late
                Gdx.app.log(TAG_PREFIX, "Enabled logging for category: " + category);
            }
        }
    }

    public static void disableCategory(VulkanLogCategory category) {
        if (enabledCategories.remove(category)) { // Remove returns true if the element was present
            if (Gdx.app != null) {
                Gdx.app.log(TAG_PREFIX, "Disabled logging for category: " + category);
            }
        }
    }

    public static void setEnabledCategories(Set<VulkanLogCategory> categories) {
        enabledCategories.clear();
        if (categories != null) {
            enabledCategories.addAll(categories);
        }
        if (Gdx.app != null) {
            Gdx.app.log(TAG_PREFIX, "Set enabled categories to: " + enabledCategories);
        }
    }

    public static void enableAll() {
        setEnabledCategories(EnumSet.allOf(VulkanLogCategory.class));
        if (Gdx.app != null) {
            Gdx.app.log(TAG_PREFIX, "Enabled all logging categories.");
        }
    }

    public static void disableAll() {
        setEnabledCategories(EnumSet.noneOf(VulkanLogCategory.class));
        if (Gdx.app != null) {
            Gdx.app.log(TAG_PREFIX, "Disabled all logging categories.");
        }
    }

    public static boolean isCategoryEnabled(VulkanLogCategory category) {
        return enabledCategories.contains(category);
    }

    // --- Logging Methods ---

    // Debug level logging - most common for verbose info
    public static void debug(VulkanLogCategory category, String message) {
        // Check level first for LibGDX optimization
        if (Gdx.app != null && Gdx.app.getLogLevel() >= Application.LOG_DEBUG && enabledCategories.contains(category)) {
            Gdx.app.debug(TAG_PREFIX + "." + category.name(), message);
        }
    }

    public static void debug(VulkanLogCategory category, Supplier<String> messageSupplier) {
        // Check level and category *before* evaluating the supplier
        if (Gdx.app != null && Gdx.app.getLogLevel() >= Application.LOG_DEBUG && enabledCategories.contains(category)) {
            Gdx.app.debug(TAG_PREFIX + "." + category.name(), messageSupplier.get());
        }
    }

    // Info level logging - for less verbose, important events
    public static void info(VulkanLogCategory category, String message) {
        // You might choose to ignore category filters for INFO, or keep them
        // This example keeps the filter:
        if (Gdx.app != null && Gdx.app.getLogLevel() >= Application.LOG_INFO && enabledCategories.contains(category)) {
            Gdx.app.log(TAG_PREFIX + "." + category.name(), message);
        }
        // Example ignoring filter for INFO:
        // if (Gdx.app != null && Gdx.app.getLogLevel() >= Application.LOG_INFO) {
        //    Gdx.app.log(TAG_PREFIX + "." + category.name(), message);
        // }
    }

    public static void info(VulkanLogCategory category, Supplier<String> messageSupplier) {
        if (Gdx.app != null && Gdx.app.getLogLevel() >= Application.LOG_INFO && enabledCategories.contains(category)) {
            Gdx.app.log(TAG_PREFIX + "." + category.name(), messageSupplier.get());
        }
    }


    // Error level logging - should likely always be shown
    public static void error(VulkanLogCategory category, String message, Throwable throwable) {
        if (Gdx.app != null) { // Errors usually ignore log level and category filters
            Gdx.app.error(TAG_PREFIX + "." + category.name(), message, throwable);
        }
    }

    public static void error(VulkanLogCategory category, String message) {
        if (Gdx.app != null) {
            Gdx.app.error(TAG_PREFIX + "." + category.name(), message);
        }
    }
}