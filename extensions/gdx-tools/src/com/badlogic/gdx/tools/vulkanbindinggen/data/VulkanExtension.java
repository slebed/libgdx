package com.badlogic.gdx.tools.vulkanbindinggen.data;

import java.util.ArrayList;
import java.util.List;

// Represents an extension
public class VulkanExtension {
    public String name;
    public String number;
    public String type; // "instance" or "device"
    public String supported; // e.g., "vulkan"
    public String requiresCore; // e.g., "1.1"
    public List<String> requiredCommandNames = new ArrayList<>();
    // Add dependencies on other extensions if implementing validation

    public VulkanExtension(String name) {
        this.name = name;
    }
}