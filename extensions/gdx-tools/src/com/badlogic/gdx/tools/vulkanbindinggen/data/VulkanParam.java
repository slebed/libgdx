package com.badlogic.gdx.tools.vulkanbindinggen.data;

// Basic parameter info
public class VulkanParam {
    public String type;
    public String name;
    public String rawType; // Keep the original Vulkan type string

    public VulkanParam(String type, String name, String rawType) {
        this.type = type;
        this.name = name;
        this.rawType = rawType;
    }
    // toString, equals, hashCode...
}
