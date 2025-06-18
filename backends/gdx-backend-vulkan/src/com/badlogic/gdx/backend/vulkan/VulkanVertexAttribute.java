package com.badlogic.gdx.backend.vulkan; // Your package

import com.badlogic.gdx.graphics.VertexAttributes.Usage; // Keep this import
import com.badlogic.gdx.graphics.glutils.ShaderProgram; // Used for default aliases

/**
 * A Vulkan-specific vertex attribute defined by its {@link Usage}, its number of components,
 * its shader alias, and its explicit shader **location**.
 * The {@link #type} field uses GL constants as per LibGDX convention.
 *
 * @author mzechner (original), with Vulkan adaptations
 */
public final class VulkanVertexAttribute {
    /** The attribute {@link Usage}, used for identification. **/
    public final int usage;
    /** the number of components this attribute has **/
    public final int numComponents;
    /** For fixed types, whether the values are normalized to either -1f and +1f (signed) or 0f and +1f (unsigned) */
    public final boolean normalized;
    /** The GL-style type of each component, e.g. {@link #GL_FLOAT} or {@link #GL_UNSIGNED_BYTE} */
    public final int type;
    /** The offset of this attribute in bytes, calculated by VulkanVertexAttributes! **/
    public int offset;
    /** The alias for the attribute. **/
    public String alias;
    /** **The explicit shader location for this attribute.** This is crucial for Vulkan. **/
    public final int location; // Correctly added!
    /** Optional unit/index specifier, used for texture coordinates and bone weights. **/
    public int unit;
    private final int usageIndex;

    // GL constants defined locally
    public static final int GL_BYTE = 0x1400;
    public static final int GL_UNSIGNED_BYTE = 0x1401;
    public static final int GL_SHORT = 0x1402;
    public static final int GL_UNSIGNED_SHORT = 0x1403;
    public static final int GL_FIXED = 0x140C;
    public static final int GL_FLOAT = 0x1406;

    /**
     * Constructs a new VulkanVertexAttribute with an explicit shader location.
     * This is the primary constructor.
     */
    public VulkanVertexAttribute (int usage, int numComponents, int type, boolean normalized, String alias, int unit, int location) {
        this.usage = usage;
        this.numComponents = numComponents;
        this.type = type;
        this.normalized = normalized;
        this.alias = alias;
        this.unit = unit;
        this.location = location; // Location is assigned
        this.usageIndex = Integer.numberOfTrailingZeros(usage);
    }

    /**
     * Constructs a new VulkanVertexAttribute, inferring type and normalization for common usages,
     * and requires an explicit shader location.
     */
    public VulkanVertexAttribute (int usage, int numComponents, String alias, int unit, int location) {
        this(usage, numComponents,
                usage == Usage.ColorPacked ? GL_UNSIGNED_BYTE : GL_FLOAT,
                usage == Usage.ColorPacked,
                alias, unit, location);
    }

    /**
     * Simplified constructor requiring explicit location.
     */
    public VulkanVertexAttribute (int usage, int numComponents, String alias, int location) {
        this(usage, numComponents, alias, 0, location);
    }

    /**
     * @return A copy of this VulkanVertexAttribute with the same parameters, including location.
     * The {@link #offset} is not copied.
     */
    public VulkanVertexAttribute copy () {
        // *** CORRECTED: Pass location to the constructor ***
        return new VulkanVertexAttribute(usage, numComponents, type, normalized, alias, unit, location);
    }

    // --- Static factory methods updated to include and use location ---

    public static VulkanVertexAttribute Position (int location) {
        // *** CORRECTED: Call constructor that accepts location ***
        return new VulkanVertexAttribute(Usage.Position, 3, GL_FLOAT, false, ShaderProgram.POSITION_ATTRIBUTE, 0, location);
    }

    public static VulkanVertexAttribute TexCoords (int unit, int location) {
        // *** CORRECTED: Call constructor that accepts location ***
        return new VulkanVertexAttribute(Usage.TextureCoordinates, 2, GL_FLOAT, false, ShaderProgram.TEXCOORD_ATTRIBUTE + unit, unit, location);
    }

    public static VulkanVertexAttribute Normal (int location) {
        // *** CORRECTED: Call constructor that accepts location ***
        return new VulkanVertexAttribute(Usage.Normal, 3, GL_FLOAT, false, ShaderProgram.NORMAL_ATTRIBUTE, 0, location);
    }

    public static VulkanVertexAttribute ColorPacked (int location) {
        // *** CORRECTED: Call constructor that accepts location ***
        return new VulkanVertexAttribute(Usage.ColorPacked, 4, GL_UNSIGNED_BYTE, true, ShaderProgram.COLOR_ATTRIBUTE, 0, location);
    }

    public static VulkanVertexAttribute ColorUnpacked (int location) {
        // *** CORRECTED: Call constructor that accepts location ***
        return new VulkanVertexAttribute(Usage.ColorUnpacked, 4, GL_FLOAT, false, ShaderProgram.COLOR_ATTRIBUTE, 0, location);
    }

    public static VulkanVertexAttribute Tangent (int location) {
        // *** CORRECTED: Call constructor that accepts location ***
        return new VulkanVertexAttribute(Usage.Tangent, 3, GL_FLOAT, false, ShaderProgram.TANGENT_ATTRIBUTE, 0, location);
    }

    public static VulkanVertexAttribute Binormal (int location) {
        // *** CORRECTED: Call constructor that accepts location ***
        return new VulkanVertexAttribute(Usage.BiNormal, 3, GL_FLOAT, false, ShaderProgram.BINORMAL_ATTRIBUTE, 0, location);
    }

    public static VulkanVertexAttribute BoneWeight (int unit, int location) {
        // *** CORRECTED: Call constructor that accepts location ***
        return new VulkanVertexAttribute(Usage.BoneWeight, 2, GL_FLOAT, false, ShaderProgram.BONEWEIGHT_ATTRIBUTE + unit, unit, location);
    }

    /** Tests to determine if the passed object was created with the same parameters. */
    @Override
    public boolean equals (final Object obj) {
        if (this == obj) return true; // Optimization
        if (!(obj instanceof VulkanVertexAttribute)) {
            return false;
        }
        return equals((VulkanVertexAttribute)obj);
    }

    public boolean equals (final VulkanVertexAttribute other) {
        if (other == null) return false;
        // *** CORRECTED: Include location in comparison ***
        return usage == other.usage &&
                numComponents == other.numComponents &&
                type == other.type &&
                normalized == other.normalized &&
                alias.equals(other.alias) && // Add null check for alias if it can be null
                unit == other.unit &&
                location == other.location;
    }

    /** @return A unique number specifying the usage index (3 MSB) and unit (1 LSB). */
    public int getKey () {
        return (usageIndex << 8) + (unit & 0xFF);
    }

    /** @return How many bytes this attribute uses. */
    public int getSizeInBytes () {
        switch (type) {
            case GL_FLOAT:
            case GL_FIXED:
                return 4 * numComponents;
            case GL_UNSIGNED_BYTE:
            case GL_BYTE:
                return numComponents;
            case GL_UNSIGNED_SHORT:
            case GL_SHORT:
                return 2 * numComponents;
        }
        return 0;
    }

    @Override
    public int hashCode () {
        int result = getKey(); // Or start with a prime
        result = 31 * result + numComponents;
        result = 31 * result + (alias != null ? alias.hashCode() : 0); // Null safe
        // *** CORRECTED: Include location in hash code ***
        result = 31 * result + location;
        // You might want to include other fields like type, normalized, usage for a more robust hash
        result = 31 * result + usage;
        result = 31 * result + type;
        result = 31 * result + (normalized ? 1 : 0);
        result = 31 * result + unit;
        return result;
    }

    @Override
    public String toString() {
        return alias + " (loc: " + location + ", usage: " + usage + ", comps: " + numComponents +
                ", type: 0x" + Integer.toHexString(type) + ", norm: " + normalized +
                ", unit: " + unit + ", offset: " + offset + ")";
    }
}