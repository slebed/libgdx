package com.badlogic.gdx.backend.vulkan; // Your package

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.Pool.Poolable; // Optional: if you want to pool materials

import java.nio.ByteBuffer;
import java.util.Objects;

// Assuming VulkanTexture is in the same package or imported
// import com.badlogic.gdx.backend.vulkan.VulkanTexture;

public class VulkanMaterial implements Poolable { // Implementing Poolable is optional

    public String id;

    // --- Diffuse Properties ---
    public final Color diffuseColor = new Color(Color.WHITE); // Base color if no texture, or tint
    public VulkanTexture diffuseTexture = null;

    // --- Specular Properties (Blinn-Phong style) ---
    public final Color specularColor = new Color(Color.WHITE); // Color of highlights
    public VulkanTexture specularTexture = null; // Modulates specular intensity/color
    public float shininess = 32f; // Specular exponent

    // --- Emissive Properties ---
    public final Color emissiveColor = new Color(Color.BLACK); // Color of emitted light
    public VulkanTexture emissiveTexture = null;

    // --- Other Common Maps ---
    public VulkanTexture normalTexture = null;
    public VulkanTexture ambientOcclusionTexture = null; // AO map

    // --- Transparency ---
    public float opacity = 1.0f;
    // Blending could be a separate state, or implied by opacity < 1.0
    // For now, opacity is just a value; blending state is handled by pipeline.

    // --- PBR (Physically Based Rendering) Style Properties (for future/advanced shaders) ---
    // If using a PBR workflow, diffuseColor/Texture might be interpreted as albedoColor/Texture.
    public float metallic = 0.0f;  // 0.0 for dielectric, 1.0 for metallic
    public float roughness = 0.5f; // Surface roughness (0.0 smooth, 1.0 rough)
    public VulkanTexture metallicRoughnessTexture = null; // Often R for metallic, G for roughness

    public VulkanShaderPipelineBundle pipelineBundle; // The bundle this material should be rendered with

    /**
     * Flags indicating which textures are set. Useful for shaders and UBOs.
     * Bit flags could also be used, but separate booleans/floats are simpler for UBOs initially.
     */
    public static class TextureFlags {
        public static final int DIFFUSE = 1;
        public static final int SPECULAR = 1 << 1;
        public static final int NORMAL = 1 << 2;
        public static final int EMISSIVE = 1 << 3;
        public static final int OCCLUSION = 1 << 4;
        public static final int METALLIC_ROUGHNESS = 1 << 5;
    }
    // public int activeTextureFlags = 0; // Calculated based on set textures

    /**
     * Generic attribute storage for custom shader properties.
     * Key = uniform name, Value = data (Float, Color, Vector2, Vector3 etc.)
     */
    public final ObjectMap<String, Object> customAttributes;

    public VulkanMaterial(String id) {
        this.id = id;
        this.customAttributes = new ObjectMap<>();
        reset(); // Set to default values
    }

    /**
     * Resets the material to its default state. Useful for pooling.
     */
    @Override
    public void reset() {
        id = null; // Or a default ID
        diffuseColor.set(Color.WHITE);
        diffuseTexture = null;
        specularColor.set(Color.WHITE);
        specularTexture = null;
        shininess = 32f;
        emissiveColor.set(Color.BLACK);
        emissiveTexture = null;
        normalTexture = null;
        ambientOcclusionTexture = null;
        opacity = 1.0f;
        metallic = 0.0f;
        roughness = 0.5f;
        metallicRoughnessTexture = null;
        customAttributes.clear();
        pipelineBundle = null;
        // activeTextureFlags = 0;
    }

    public Color getDiffuseColor() {
        return diffuseColor;
    }

    public VulkanTexture getDiffuseTexture() {
        return diffuseTexture;
    }

    public float getOpacity() {
        return opacity;
    }

    public float getShininess() {
        return shininess;
    }

    public Color getSpecularColor() {
        return specularColor;
    }

    // --- Fluent Setters ---
    public VulkanMaterial setId(String id) {
        this.id = id;
        return this;
    }

    public VulkanMaterial setDiffuseColor(float r, float g, float b, float a) {
        this.diffuseColor.set(r, g, b, a);
        return this;
    }

    public VulkanMaterial setDiffuseColor(Color color) {
        this.diffuseColor.set(color);
        return this;
    }

    public VulkanMaterial setDiffuseTexture(VulkanTexture texture) {
        this.diffuseTexture = texture;
        return this;
    }

    public VulkanMaterial setSpecularColor(float r, float g, float b, float a) {
        this.specularColor.set(r, g, b, a);
        return this;
    }

    public VulkanMaterial setSpecularColor(Color color) {
        this.specularColor.set(color);
        return this;
    }

    public VulkanMaterial setSpecularTexture(VulkanTexture texture) {
        this.specularTexture = texture;
        return this;
    }

    public VulkanMaterial setShininess(float shininess) {
        this.shininess = shininess;
        return this;
    }

    public VulkanMaterial setEmissiveColor(float r, float g, float b, float a) {
        this.emissiveColor.set(r, g, b, a);
        return this;
    }

    public VulkanMaterial setEmissiveColor(Color color) {
        this.emissiveColor.set(color);
        return this;
    }

    public VulkanMaterial setEmissiveTexture(VulkanTexture texture) {
        this.emissiveTexture = texture;
        return this;
    }

    public VulkanMaterial setNormalTexture(VulkanTexture texture) {
        this.normalTexture = texture;
        return this;
    }

    public VulkanMaterial setAmbientOcclusionTexture(VulkanTexture texture) {
        this.ambientOcclusionTexture = texture;
        return this;
    }

    public VulkanMaterial setOpacity(float opacity) {
        this.opacity = opacity;
        return this;
    }

    public VulkanMaterial setMetallic(float metallic) {
        this.metallic = metallic;
        return this;
    }

    public VulkanMaterial setRoughness(float roughness) {
        this.roughness = roughness;
        return this;
    }

    public VulkanMaterial setMetallicRoughnessTexture(VulkanTexture texture) {
        this.metallicRoughnessTexture = texture;
        return this;
    }

    public VulkanMaterial setCustomAttribute(String alias, Object value) {
        this.customAttributes.put(alias, value);
        return this;
    }

    public VulkanMaterial setPipelineBundle(VulkanShaderPipelineBundle bundle) { // Setter for the bundle
        this.pipelineBundle = bundle;
        return this;
    }

    /**
     * Populates a ByteBuffer (presumably mapped from a UBO) with this material's
     * scalar and color properties. The order and size must match the shader's UBO definition.
     *
     * Shader UBO Example (std140 layout assumed for GLSL):
     * <pre>
     * layout(std140, set = 1, binding = 0) uniform MaterialUBO { // Example set/binding
     * vec4 diffuseColor;    // offset 0
     * vec4 specularColor;   // offset 16
     * vec4 emissiveColor;   // offset 32
     * float shininess;      // offset 48
     * float opacity;        // offset 52
     * // --- PBR (total 16 bytes for these 4 floats to align next vec4) ---
     * float metallic;       // offset 56
     * float roughness;      // offset 60
     * // --- Texture Flags (using floats 0.0 or 1.0) ---
     * float hasDiffuseTexture; // offset 64
     * float hasNormalTexture;  // offset 68
     * float hasSpecularTexture; // offset 72
     * float hasEmissiveTexture; // offset 76
     * float hasOcclusionTexture; // offset 80
     * float hasMetallicRoughnessTexture; // offset 84
     * // Add padding or more members as needed to match shader UBO size and alignment
     * // float padding1; // offset 88
     * // float padding2; // offset 92 (Total 96 bytes, multiple of 16)
     * };
     * </pre>
     * @param buffer The ByteBuffer to populate. It should be ready for writing (e.g., after buffer.clear() or buffer.position(offset)).
     * @param baseOffset The starting offset in the ByteBuffer to write to.
     * @return The number of bytes written.
     */
    public int writeToUbo(ByteBuffer buffer, int baseOffset) {
        int currentOffset = baseOffset;
        buffer.position(currentOffset);

        // Diffuse Color (vec4)
        buffer.putFloat(diffuseColor.r);
        buffer.putFloat(diffuseColor.g);
        buffer.putFloat(diffuseColor.b);
        buffer.putFloat(diffuseColor.a);
        currentOffset += 4 * Float.BYTES;

        // Specular Color (vec4)
        buffer.position(currentOffset);
        buffer.putFloat(specularColor.r);
        buffer.putFloat(specularColor.g);
        buffer.putFloat(specularColor.b);
        buffer.putFloat(specularColor.a);
        currentOffset += 4 * Float.BYTES;

        // Emissive Color (vec4)
        buffer.position(currentOffset);
        buffer.putFloat(emissiveColor.r);
        buffer.putFloat(emissiveColor.g);
        buffer.putFloat(emissiveColor.b);
        buffer.putFloat(emissiveColor.a);
        currentOffset += 4 * Float.BYTES;

        // Shininess (float)
        buffer.position(currentOffset);
        buffer.putFloat(shininess);
        currentOffset += Float.BYTES;
        // Opacity (float)
        buffer.position(currentOffset);
        buffer.putFloat(opacity);
        currentOffset += Float.BYTES;
        // Metallic (float)
        buffer.position(currentOffset);
        buffer.putFloat(metallic);
        currentOffset += Float.BYTES;
        // Roughness (float)
        buffer.position(currentOffset);
        buffer.putFloat(roughness);
        currentOffset += Float.BYTES;
        // At this point, currentOffset = baseOffset + 16 (diff) + 16 (spec) + 16 (emis) + 4*4 (scalars) = baseOffset + 48 + 16 = baseOffset + 64

        // Texture presence flags (as floats 0.0 or 1.0)
        buffer.position(currentOffset);
        buffer.putFloat(diffuseTexture != null ? 1.0f : 0.0f);
        currentOffset += Float.BYTES;
        buffer.position(currentOffset);
        buffer.putFloat(normalTexture != null ? 1.0f : 0.0f);
        currentOffset += Float.BYTES;
        buffer.position(currentOffset);
        buffer.putFloat(specularTexture != null ? 1.0f : 0.0f);
        currentOffset += Float.BYTES;
        buffer.position(currentOffset);
        buffer.putFloat(emissiveTexture != null ? 1.0f : 0.0f);
        currentOffset += Float.BYTES;
        // currentOffset = baseOffset + 64 + 16 = baseOffset + 80

        buffer.position(currentOffset);
        buffer.putFloat(ambientOcclusionTexture != null ? 1.0f : 0.0f);
        currentOffset += Float.BYTES;
        buffer.position(currentOffset);
        buffer.putFloat(metallicRoughnessTexture != null ? 1.0f : 0.0f);
        currentOffset += Float.BYTES;
        // currentOffset = baseOffset + 80 + 8 = baseOffset + 88

        // Add padding to ensure UBO size is a multiple of 16 (vec4 alignment for std140 blocks)
        // Current size is 88 bytes. Next multiple of 16 is 96. Need 8 bytes of padding (2 floats).
        buffer.position(currentOffset);
        buffer.putFloat(0.0f);
        currentOffset += Float.BYTES; // Padding
        buffer.position(currentOffset);
        buffer.putFloat(0.0f);
        currentOffset += Float.BYTES; // Padding
        // Total bytes written = currentOffset - baseOffset (should be 96)

        return currentOffset - baseOffset;
    }

    /**
     * Checks if this material has a specific texture type set.
     * @param textureFlag e.g., TextureFlags.DIFFUSE, TextureFlags.NORMAL
     * @return true if the corresponding texture is not null.
     */
    public boolean hasTexture(int textureFlag) {
        switch (textureFlag) {
            case TextureFlags.DIFFUSE:
                return diffuseTexture != null;
            case TextureFlags.SPECULAR:
                return specularTexture != null;
            case TextureFlags.NORMAL:
                return normalTexture != null;
            case TextureFlags.EMISSIVE:
                return emissiveTexture != null;
            case TextureFlags.OCCLUSION:
                return ambientOcclusionTexture != null;
            case TextureFlags.METALLIC_ROUGHNESS:
                return metallicRoughnessTexture != null;
            default:
                return false;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VulkanMaterial material = (VulkanMaterial) o;
        return Float.compare(material.shininess, shininess) == 0 &&
                Float.compare(material.opacity, opacity) == 0 &&
                Float.compare(material.metallic, metallic) == 0 &&
                Float.compare(material.roughness, roughness) == 0 &&
                Objects.equals(id, material.id) &&
                diffuseColor.equals(material.diffuseColor) &&
                Objects.equals(diffuseTexture, material.diffuseTexture) && // Relies on VulkanTexture.equals()
                specularColor.equals(material.specularColor) &&
                Objects.equals(specularTexture, material.specularTexture) &&
                emissiveColor.equals(material.emissiveColor) &&
                Objects.equals(emissiveTexture, material.emissiveTexture) &&
                Objects.equals(normalTexture, material.normalTexture) &&
                Objects.equals(ambientOcclusionTexture, material.ambientOcclusionTexture) &&
                Objects.equals(metallicRoughnessTexture, material.metallicRoughnessTexture) &&
                customAttributes.equals(material.customAttributes);
    }

    @Override
    public int hashCode() {
        // A robust hashCode should include all fields that define the material's state.
        // For textures, using their own hashCode (if well-defined) is good.
        return Objects.hash(id, diffuseColor, diffuseTexture, specularColor, specularTexture, shininess,
                emissiveColor, emissiveTexture, normalTexture, ambientOcclusionTexture, opacity,
                metallic, roughness, metallicRoughnessTexture, customAttributes);
    }
}