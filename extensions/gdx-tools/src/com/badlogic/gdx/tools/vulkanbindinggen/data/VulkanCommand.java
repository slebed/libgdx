package com.badlogic.gdx.tools.vulkanbindinggen.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects; // For equals/hashCode

/**
 * Represents a Vulkan command (function) parsed from vk.xml.
 */
public class VulkanCommand {

    /**
     * Categorizes the scope/level at which a Vulkan command operates.
     */
    public enum Level {
        /** Function does not require an instance or device (e.g., vkGetInstanceProcAddr). */
        GLOBAL,
        /** Function operates on a VkInstance or VkPhysicalDevice, or requires one to load its pointer (e.g., vkCreateDevice, vkEnumeratePhysicalDevices, vkGetDeviceProcAddr). */
        INSTANCE,
        /** Function operates on a VkDevice or its child objects (VkQueue, VkCommandBuffer, etc.). This is the default for most commands. */
        DEVICE
    }

    public final String name; // Make final if set only in constructor
    public String returnType;
    public final List<VulkanParam> params = new ArrayList<>(); // Make final, modify contents
    public Level level = Level.DEVICE; // Default to DEVICE, most functions are device-level

    public VulkanCommand(String name) {
        this.name = Objects.requireNonNull(name, "Command name cannot be null");
    }

    /**
     * Determines the command level based on its name and parameters.
     * This classification helps the C generator decide how to load the function pointer.
     */
    public void determineLevel() {
        // 1. Check for specific GLOBAL commands that need no handles.
        // vkGetInstanceProcAddr is the primary one, maybe vkEnumerateInstanceVersion too? (Check spec if vkGetInstanceProcAddr(NULL, "vkEnumerateInstanceVersion") is valid)
        // Let's assume vkEnumerateInstanceVersion might need vkGetInstanceProcAddr(NULL,...) -> INSTANCE level for loading.
        if (name.equals("vkGetInstanceProcAddr")) {
            this.level = Level.GLOBAL;
            // System.out.println("Info: Command '" + name + "' classified as GLOBAL."); // Optional debug
            return;
        }

        // 2. Check for specific INSTANCE commands (that don't necessarily take VkInstance as the *first* param).
        if (name.equals("vkCreateInstance") ||                     // Creates the instance
                name.equals("vkEnumerateInstanceVersion") ||           // Info available before instance creation
                name.equals("vkEnumerateInstanceLayerProperties") ||   // Info available before instance creation
                name.equals("vkEnumerateInstanceExtensionProperties") || // Info available before instance creation
                name.equals("vkGetDeviceProcAddr")) {                  // Requires VkInstance to get device function pointers
            this.level = Level.INSTANCE;
            // System.out.println("Info: Command '" + name + "' classified as INSTANCE by name."); // Optional debug
            return;
        }

        // 3. Check the first parameter type for common INSTANCE or DEVICE handle types.
        if (!params.isEmpty()) {
            String firstParamType = params.get(0).type;
            if ("VkInstance".equals(firstParamType) || "VkPhysicalDevice".equals(firstParamType)) {
                // Functions taking VkInstance or VkPhysicalDevice operate at the instance level.
                this.level = Level.INSTANCE;
                // System.out.println("Info: Command '" + name + "' classified as INSTANCE by first param ("+firstParamType+")."); // Optional debug
                return;
            } else if ("VkDevice".equals(firstParamType) ||
                    "VkQueue".equals(firstParamType) ||
                    "VkCommandBuffer".equals(firstParamType)) {
                // Functions taking VkDevice, VkQueue, or VkCommandBuffer operate at the device level.
                this.level = Level.DEVICE;
                // System.out.println("Info: Command '" + name + "' classified as DEVICE by first param ("+firstParamType+")."); // Optional debug
                return;
                // Note: Many other device-level commands might take other handles first (VkFence, VkSemaphore, etc.)
                // They will correctly fall through to the default DEVICE level.
            }
        }

        // 4. If not identified as GLOBAL or INSTANCE, keep the default Level.DEVICE.
        // This covers the majority of Vulkan commands that operate on a created VkDevice or its children.
        // System.out.println("Info: Command '" + name + "' classified as " + this.level + " (default or based on first param)."); // Optional debug
    }

    @Override
    public String toString() {
        return "VulkanCommand{" +
                "name='" + name + '\'' +
                ", returnType='" + returnType + '\'' +
                ", level=" + level +
                ", params=" + params.size() +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VulkanCommand command = (VulkanCommand) o;
        // Equality based on name should be sufficient as command names are unique
        return name.equals(command.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}