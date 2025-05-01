package com.badlogic.gdx.tools.vulkanbindinggen.data;

import java.util.ArrayList;
import java.util.List;

public class VulkanFeature {
    public String name; // e.g., "VK_VERSION_1_1"
    public String api; // e.g., "vulkan"
    public String number; // e.g., "1.1"
    public List<String> requiredCommandNames = new ArrayList<>();
    // Add required types, enums if needed for more complex generation

    public VulkanFeature(String name, String api, String number) {
        this.name = name;
        this.api = api;
        this.number = number;
    }
}