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

import static com.badlogic.gdx.backend.vulkan.VulkanMesh.getVkFormat;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_SNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16_SINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16_SNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_SINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_SNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_SINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_SNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_VERTEX;

import java.util.Iterator;
import java.util.NoSuchElementException;

import com.badlogic.gdx.utils.Collections;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

/** Instances of this class specify the vertex attributes of a mesh. VertexAttributes are used by {@link VulkanMesh} instances to define
 * its vertex structure. Vertex attributes have an order. The order is specified by the order they are added to this class.
 *
 * @author mzechner, Xoppa */
public final class VulkanVertexAttributes implements Iterable<VulkanVertexAttribute>, Comparable<VulkanVertexAttributes> {
    /** The usage of a vertex attribute.
     *
     * @author mzechner */
    public static final class Usage {
        public static final int Position = 1;
        public static final int ColorUnpacked = 2;
        public static final int ColorPacked = 4;
        public static final int Normal = 8;
        public static final int TextureCoordinates = 16;
        public static final int Generic = 32;
        public static final int BoneWeight = 64;
        public static final int Tangent = 128;
        public static final int BiNormal = 256;
    }

    /** the attributes in the order they were specified **/
    private final VulkanVertexAttribute[] attributes;

    /** the size of a single vertex in bytes **/
    public final int vertexSize;

    /** cache of the value calculated by {@link #getMask()} **/
    private long mask = -1;

    /** cache for bone weight units. */
    private int boneWeightUnits = -1;

    /** cache for texture coordinate units. */
    private int textureCoordinates = -1;

    private ReadonlyIterable<VulkanVertexAttribute> iterable;

    private VkVertexInputBindingDescription.Buffer bindingDescription;
    private VkVertexInputAttributeDescription.Buffer attributeDescriptions;

    /** Constructor, sets the vertex attributes in a specific order */
    public VulkanVertexAttributes (VulkanVertexAttribute... attributes) {
        if (attributes.length == 0) throw new IllegalArgumentException("attributes must be >= 1");

        VulkanVertexAttribute[] list = new VulkanVertexAttribute[attributes.length];
        for (int i = 0; i < attributes.length; i++)
            list[i] = attributes[i];

        this.attributes = list;
        vertexSize = calculateOffsets();
        generateVertexInputDescriptions();
    }

    /** Returns the offset for the first VertexAttribute with the specified usage.
     * @param usage The usage of the VertexAttribute. */
    public int getOffset (int usage, int defaultIfNotFound) {
        VulkanVertexAttribute vertexAttribute = findByUsage(usage);
        if (vertexAttribute == null) return defaultIfNotFound;
        return vertexAttribute.offset / 4;
    }

    /** Returns the offset for the first VertexAttribute with the specified usage.
     * @param usage The usage of the VertexAttribute. */
    public int getOffset (int usage) {
        return getOffset(usage, 0);
    }

    /**
     * Helper method to convert a {@link VulkanVertexAttribute} (which uses GL-style types)
     * to a Vulkan {@code VkFormat}.
     * @param attribute The VulkanVertexAttribute to convert.
     * @return The corresponding VkFormat.
     */
    public static int getVkFormat(VulkanVertexAttribute attribute) {
        int numComponents = attribute.numComponents;
        boolean normalized = attribute.normalized;
        int type = attribute.type; // Uses constants like VulkanVertexAttribute.GL_FLOAT

        // Map GL types to VkFormat based on numComponents and normalization
        if (type == VulkanVertexAttribute.GL_FLOAT) {
            switch (numComponents) {
                case 1: return VK_FORMAT_R32_SFLOAT;
                case 2: return VK_FORMAT_R32G32_SFLOAT;
                case 3: return VK_FORMAT_R32G32B32_SFLOAT;
                case 4: return VK_FORMAT_R32G32B32A32_SFLOAT;
            }
        } else if (type == VulkanVertexAttribute.GL_UNSIGNED_BYTE) {
            // For Usage.ColorPacked, numComponents is 4, type is GL_UNSIGNED_BYTE, normalized is true.
            // This maps to VK_FORMAT_R8G8B8A8_UNORM.
            if (attribute.usage == Usage.ColorPacked && numComponents == 4 && normalized) {
                return VK_FORMAT_R8G8B8A8_UNORM;
            }
            // General cases for unsigned byte
            if (numComponents == 4) return normalized ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_R8G8B8A8_UINT;
            if (numComponents == 3) return normalized ? VK_FORMAT_R8G8B8_UNORM : VK_FORMAT_R8G8B8_UINT; // Check device support for R8G8B8
            if (numComponents == 2) return normalized ? VK_FORMAT_R8G8_UNORM : VK_FORMAT_R8G8_UINT;
            if (numComponents == 1) return normalized ? VK_FORMAT_R8_UNORM : VK_FORMAT_R8_UINT;
        } else if (type == VulkanVertexAttribute.GL_BYTE) {
            if (numComponents == 4) return normalized ? VK_FORMAT_R8G8B8A8_SNORM : VK_FORMAT_R8G8B8A8_SINT;
            if (numComponents == 2) return normalized ? VK_FORMAT_R8G8_SNORM : VK_FORMAT_R8G8_SINT;
            if (numComponents == 1) return normalized ? VK_FORMAT_R8_SNORM : VK_FORMAT_R8_SINT;
        } else if (type == VulkanVertexAttribute.GL_UNSIGNED_SHORT) {
            if (numComponents == 4) return normalized ? VK_FORMAT_R16G16B16A16_UNORM : VK_FORMAT_R16G16B16A16_UINT;
            if (numComponents == 2) return normalized ? VK_FORMAT_R16G16_UNORM : VK_FORMAT_R16G16_UINT;
            if (numComponents == 1) return normalized ? VK_FORMAT_R16_UNORM : VK_FORMAT_R16_UINT;
        } else if (type == VulkanVertexAttribute.GL_SHORT) {
            if (numComponents == 4) return normalized ? VK_FORMAT_R16G16B16A16_SNORM : VK_FORMAT_R16G16B16A16_SINT;
            if (numComponents == 2) return normalized ? VK_FORMAT_R16G16_SNORM : VK_FORMAT_R16G16_SINT;
            if (numComponents == 1) return normalized ? VK_FORMAT_R16_SNORM : VK_FORMAT_R16_SINT;
        } else if (type == VulkanVertexAttribute.GL_FIXED) {
            // GL_FIXED is 16.16 fixed point. Vulkan doesn't have direct 16.16 formats.
            // Often, this data is pre-converted to float on CPU, or handled as SINT if shaders can interpret it.
            // If treated as 32-bit data that shaders unpack or if pre-converted to float:
            if (numComponents == 1) return VK_FORMAT_R32_SFLOAT; // Or VK_FORMAT_R32_SINT if shader handles fixed point
            if (numComponents == 2) return VK_FORMAT_R32G32_SFLOAT;
            if (numComponents == 3) return VK_FORMAT_R32G32B32_SFLOAT;
            if (numComponents == 4) return VK_FORMAT_R32G32B32A32_SFLOAT;
        }

        throw new GdxRuntimeException("Unsupported VulkanVertexAttribute for VkFormat: alias=" + attribute.alias +
                ", numComponents=" + numComponents + ", type=0x" + Integer.toHexString(type) + ", normalized=" + normalized);
    }

    /** Returns the first VertexAttribute for the given usage.
     * @param usage The usage of the VertexAttribute to find. */
    public VulkanVertexAttribute findByUsage (int usage) {
        int len = size();
        for (int i = 0; i < len; i++)
            if (get(i).usage == usage) return get(i);
        return null;
    }

    private int calculateOffsets () {
        int count = 0;
        for (int i = 0; i < attributes.length; i++) {
            VulkanVertexAttribute attribute = attributes[i];
            attribute.offset = count;
            count += attribute.getSizeInBytes();
        }

        return count;
    }

    /** @return the number of attributes */
    public int size () {
        return attributes.length;
    }

    /** @param index the index
     * @return the VertexAttribute at the given index */
    public VulkanVertexAttribute get (int index) {
        return attributes[index];
    }

    public String toString () {
        StringBuilder builder = new StringBuilder();
        builder.append("[");
        for (int i = 0; i < attributes.length; i++) {
            builder.append("(");
            builder.append(attributes[i].alias);
            builder.append(", ");
            builder.append(attributes[i].usage);
            builder.append(", ");
            builder.append(attributes[i].numComponents);
            builder.append(", ");
            builder.append(attributes[i].offset);
            builder.append(")");
            builder.append("\n");
        }
        builder.append("]");
        return builder.toString();
    }

    @Override
    public boolean equals (final Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof VulkanVertexAttributes)) return false;
        VulkanVertexAttributes other = (VulkanVertexAttributes)obj;
        if (this.attributes.length != other.attributes.length) return false;
        for (int i = 0; i < attributes.length; i++) {
            if (!attributes[i].equals(other.attributes[i])) return false;
        }
        return true;
    }

    @Override
    public int hashCode () {
        long result = 61L * attributes.length;
        for (int i = 0; i < attributes.length; i++)
            result = result * 61 + attributes[i].hashCode();
        return (int)(result ^ (result >> 32));
    }

    /** Calculates a mask based on the contained {@link VulkanVertexAttribute} instances. The mask is a bit-wise or of each attributes
     * {@link VulkanVertexAttribute#usage}.
     * @return the mask */
    public long getMask () {
        if (mask == -1) {
            long result = 0;
            for (int i = 0; i < attributes.length; i++) {
                result |= attributes[i].usage;
            }
            mask = result;
        }
        return mask;
    }

    /**
     * Generates the Vulkan vertex input binding and attribute descriptions.
     * This is called by the constructor after offsets are calculated.
     */
    private void generateVertexInputDescriptions() {
        // Free previous descriptions if they exist (e.g., if this method could be called multiple times)
        if (this.bindingDescription != null) this.bindingDescription.free();
        if (this.attributeDescriptions != null) this.attributeDescriptions.free();

        // Create Binding Description (assuming interleaved vertex data in a single buffer binding 0)
        // These need to be heap allocated as they are returned by getters.
        this.bindingDescription = VkVertexInputBindingDescription.calloc(1);
        this.bindingDescription.get(0)
                .binding(0) // Binding index for the vertex buffer
                .stride(this.vertexSize) // Stride: size of one complete vertex in bytes
                .inputRate(VK_VERTEX_INPUT_RATE_VERTEX); // Per-vertex data (not per-instance)

        // Create Attribute Descriptions
        this.attributeDescriptions = VkVertexInputAttributeDescription.calloc(this.attributes.length);
        for (int i = 0; i < this.attributes.length; i++) {
            VulkanVertexAttribute attribute = this.attributes[i];
            if (attribute.location < 0) { // Ensure location was set
                throw new GdxRuntimeException("VulkanVertexAttribute '" + attribute.alias +
                        "' has an invalid shader location (" + attribute.location +
                        "). Explicit location is required for Vulkan.");
            }
            this.attributeDescriptions.get(i)
                    .binding(0) // Corresponding to the binding index above
                    .location(attribute.location) // Shader input location
                    .format(getVkFormat(attribute)) // Convert to VkFormat
                    .offset(attribute.offset); // Offset within the vertex structure
        }
    }

    /**
     * @return The {@link VkVertexInputBindingDescription.Buffer} for this set of attributes.
     * The caller should NOT free this buffer; it's managed by this class.
     */
    public VkVertexInputBindingDescription.Buffer getBindingDescription() {
        return this.bindingDescription;
    }

    /**
     * @return The {@link VkVertexInputAttributeDescription.Buffer} for this set of attributes.
     * The caller should NOT free this buffer; it's managed by this class.
     */
    public VkVertexInputAttributeDescription.Buffer getAttributeDescriptions() {
        return this.attributeDescriptions;
    }

    /** Calculates the mask based on {@link VulkanVertexAttributes#getMask()} and packs the attributes count into the last 32 bits.
     * @return the mask with attributes count packed into the last 32 bits. */
    public long getMaskWithSizePacked () {
        return getMask() | ((long)attributes.length << 32);
    }

    /** @return Number of bone weights based on {@link VulkanVertexAttribute#unit} */
    public int getBoneWeights () {
        if (boneWeightUnits < 0) {
            boneWeightUnits = 0;
            for (int i = 0; i < attributes.length; i++) {
                VulkanVertexAttribute a = attributes[i];
                if (a.usage == Usage.BoneWeight) {
                    boneWeightUnits = Math.max(boneWeightUnits, a.unit + 1);
                }
            }
        }
        return boneWeightUnits;
    }

    /** @return Number of texture coordinates based on {@link VulkanVertexAttribute#unit} */
    public int getTextureCoordinates () {
        if (textureCoordinates < 0) {
            textureCoordinates = 0;
            for (int i = 0; i < attributes.length; i++) {
                VulkanVertexAttribute a = attributes[i];
                if (a.usage == Usage.TextureCoordinates) {
                    textureCoordinates = Math.max(textureCoordinates, a.unit + 1);
                }
            }
        }
        return textureCoordinates;
    }

    @Override
    public int compareTo (VulkanVertexAttributes o) {
        if (attributes.length != o.attributes.length) return attributes.length - o.attributes.length;
        final long m1 = getMask();
        final long m2 = o.getMask();
        if (m1 != m2) return m1 < m2 ? -1 : 1;
        for (int i = attributes.length - 1; i >= 0; --i) {
            final VulkanVertexAttribute va0 = attributes[i];
            final VulkanVertexAttribute va1 = o.attributes[i];
            if (va0.usage != va1.usage) return va0.usage - va1.usage;
            if (va0.unit != va1.unit) return va0.unit - va1.unit;
            if (va0.numComponents != va1.numComponents) return va0.numComponents - va1.numComponents;
            if (va0.normalized != va1.normalized) return va0.normalized ? 1 : -1;
            if (va0.type != va1.type) return va0.type - va1.type;
        }
        return 0;
    }

    /** @see Collections#allocateIterators */
    @Override
    public Iterator<VulkanVertexAttribute> iterator () {
        if (iterable == null) iterable = new ReadonlyIterable<VulkanVertexAttribute>(attributes);
        return iterable.iterator();
    }

    static private class ReadonlyIterator<T> implements Iterator<T>, Iterable<T> {
        private final T[] array;
        int index;
        boolean valid = true;

        public ReadonlyIterator (T[] array) {
            this.array = array;
        }

        @Override
        public boolean hasNext () {
            if (!valid) throw new GdxRuntimeException("#iterator() cannot be used nested.");
            return index < array.length;
        }

        @Override
        public T next () {
            if (index >= array.length) throw new NoSuchElementException(String.valueOf(index));
            if (!valid) throw new GdxRuntimeException("#iterator() cannot be used nested.");
            return array[index++];
        }

        @Override
        public void remove () {
            throw new GdxRuntimeException("Remove not allowed.");
        }

        public void reset () {
            index = 0;
        }

        @Override
        public Iterator<T> iterator () {
            return this;
        }
    }

    static private class ReadonlyIterable<T> implements Iterable<T> {
        private final T[] array;
        private ReadonlyIterator iterator1, iterator2;

        public ReadonlyIterable (T[] array) {
            this.array = array;
        }

        @Override
        public Iterator<T> iterator () {
            if (Collections.allocateIterators) return new ReadonlyIterator<>(array);
            if (iterator1 == null) {
                iterator1 = new ReadonlyIterator(array);
                iterator2 = new ReadonlyIterator(array);
            }
            if (!iterator1.valid) {
                iterator1.index = 0;
                iterator1.valid = true;
                iterator2.valid = false;
                return iterator1;
            }
            iterator2.index = 0;
            iterator2.valid = true;
            iterator1.valid = false;
            return iterator2;
        }
    }
}
