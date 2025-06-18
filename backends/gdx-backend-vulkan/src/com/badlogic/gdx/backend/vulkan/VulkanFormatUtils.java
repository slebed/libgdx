package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.vulkan.VK10; // For VK_ enums

public class VulkanFormatUtils {

    public static int getVkFormat(int glInternalFormat, int glFormat, int glType) {
        // This needs a comprehensive mapping.
        // Example for common BitmapFont default (RGBA8888):
        if (glFormat == GL20.GL_RGBA && glType == GL20.GL_UNSIGNED_BYTE) {
            // For internalformat, GL_RGBA in GL20 often implies 8-bit per channel.
            // SRGB is usually preferred for color textures that are visually authored.
            return org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB;
        }
        if (glFormat == GL20.GL_RGB && glType == GL20.GL_UNSIGNED_BYTE) {
            return org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8_SRGB;
        }
        if (glFormat == GL20.GL_LUMINANCE_ALPHA && glType == GL20.GL_UNSIGNED_BYTE) {
            // Vulkan doesn't have direct LA formats. Often mapped to R8G8_UNORM,
            // with shaders swizzling (L -> R, A -> G). Or use VK_FORMAT_R8_UNORM for L, another for A.
            // For simplicity, if you use RGBA textures for this:
            // return org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_SRGB; (and expand L to RGB, A to A in shader or CPU)
            // Or a more direct mapping to a 2-channel format:
            return org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8_UNORM; // Example: L in R, A in G
        }
        if ((glFormat == GL20.GL_LUMINANCE || glFormat == GL20.GL_ALPHA) && glType == GL20.GL_UNSIGNED_BYTE) {
            return org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM; // Common mapping for single channel
        }

        // Add more mappings based on what LibGDX Pixmap.Format and TextureData can produce
        Gdx.app.error("VulkanFormatUtils", "Unsupported GL combination for VkFormat: internalFmt=" + glInternalFormat + ", fmt=" + glFormatToString(glFormat) + ", type=" + glTypeToString(glType));
        return org.lwjgl.vulkan.VK10.VK_FORMAT_UNDEFINED; // Fallback
    }

    /**
     * Determines the number of bytes per pixel for a given GL format and type combination.
     *
     * @param glFormat The GL format enum (e.g., GL20.GL_RGBA, GL20.GL_RGB).
     * @param glType   The GL type enum (e.g., GL20.GL_UNSIGNED_BYTE, GL20.GL_UNSIGNED_SHORT_5_6_5).
     * @return The number of bytes per pixel, or 0 if the combination is unsupported or unknown.
     */
    public static int getBytesPerPixel(int glFormat, int glType) {
        switch (glFormat) {
            case GL20.GL_RGBA:
                switch (glType) {
                    case GL20.GL_UNSIGNED_BYTE:
                        return 4; // R8G8B8A8
                    case GL20.GL_UNSIGNED_SHORT_4_4_4_4:
                        return 2; // R4G4B4A4
                    case GL20.GL_UNSIGNED_SHORT_5_5_5_1:
                        return 2; // R5G5B5A1
                    // Add other RGBA types if your backend/GL20 definition supports them (e.g., float types for GL30+)
                    default:
                        Gdx.app.error("VulkanFormatUtils", "Unsupported GL_RGBA type: " + glTypeToString(glType));
                        return 0;
                }
            case GL20.GL_RGB:
                switch (glType) {
                    case GL20.GL_UNSIGNED_BYTE:
                        return 3; // R8G8B8
                    case GL20.GL_UNSIGNED_SHORT_5_6_5:
                        return 2; // R5G6B5
                    // Add other RGB types if supported
                    default:
                        Gdx.app.error("VulkanFormatUtils", "Unsupported GL_RGB type: " + glTypeToString(glType));
                        return 0;
                }
            case GL20.GL_LUMINANCE_ALPHA:
                switch (glType) {
                    case GL20.GL_UNSIGNED_BYTE:
                        return 2; // L8A8
                    default:
                        Gdx.app.error("VulkanFormatUtils", "Unsupported GL_LUMINANCE_ALPHA type: " + glTypeToString(glType));
                        return 0;
                }
            case GL20.GL_LUMINANCE: // Often treated as a single red channel in modern APIs for grayscale
            case GL20.GL_ALPHA:     // Single alpha channel
                switch (glType) {
                    case GL20.GL_UNSIGNED_BYTE:
                        return 1; // L8 or A8
                    default:
                        Gdx.app.error("VulkanFormatUtils", "Unsupported GL_LUMINANCE or GL_ALPHA type: " + glTypeToString(glType));
                        return 0;
                }
                // GL20 doesn't explicitly have GL_RED, GL_RG etc. in LibGDX's GL20 interface for format,
                // but internalformat could be different for some extensions or later versions.
                // For strict GL20, Luminance and Alpha cover single/dual channel grayscale/alpha.

                // Depth and Stencil formats (less common for client-side pixel uploads but good to be aware of)
            case GL20.GL_DEPTH_COMPONENT:
                switch (glType) {
                    case GL20.GL_UNSIGNED_SHORT:
                        return 2; // Depth16
                    case GL20.GL_UNSIGNED_INT:
                        return 4; // Depth24 or Depth32 (depending on internal format)
                    // GL_FLOAT for GL30+ Depth32F
                    default:
                        Gdx.app.error("VulkanFormatUtils", "Unsupported GL_DEPTH_COMPONENT type: " + glTypeToString(glType));
                        return 0;
                }
                // GL_DEPTH_STENCIL for combined depth/stencil (GL_UNSIGNED_INT_24_8_EXT etc.) - complex, often not directly uploaded this way.

            default:
                Gdx.app.error("VulkanFormatUtils", "Unsupported GL format: " + glFormatToString(glFormat));
                return 0;
        }
    }

    // Helper methods to convert GL enums to strings for logging (optional but useful)
    public static String glFormatToString(int glFormat) {
        switch (glFormat) {
            case GL20.GL_RGBA:
                return "GL_RGBA";
            case GL20.GL_RGB:
                return "GL_RGB";
            case GL20.GL_LUMINANCE_ALPHA:
                return "GL_LUMINANCE_ALPHA";
            case GL20.GL_LUMINANCE:
                return "GL_LUMINANCE";
            case GL20.GL_ALPHA:
                return "GL_ALPHA";
            case GL20.GL_DEPTH_COMPONENT:
                return "GL_DEPTH_COMPONENT";
            default:
                return "Unknown GLFormat (" + glFormat + ")";
        }
    }

    public static String glTypeToString(int glType) {
        switch (glType) {
            case GL20.GL_UNSIGNED_BYTE:
                return "GL_UNSIGNED_BYTE";
            case GL20.GL_UNSIGNED_SHORT_4_4_4_4:
                return "GL_UNSIGNED_SHORT_4_4_4_4";
            case GL20.GL_UNSIGNED_SHORT_5_5_5_1:
                return "GL_UNSIGNED_SHORT_5_5_5_1";
            case GL20.GL_UNSIGNED_SHORT_5_6_5:
                return "GL_UNSIGNED_SHORT_5_6_5";
            case GL20.GL_UNSIGNED_SHORT:
                return "GL_UNSIGNED_SHORT";
            case GL20.GL_UNSIGNED_INT:
                return "GL_UNSIGNED_INT";
            // Add GL_FLOAT if you ever use it with GL30+ texture types
            default:
                return "Unknown GLType (" + glType + ")";
        }
    }

    public static TextureFilter glToTextureFilter(int glEnum) {
        switch (glEnum) {
            case GL20.GL_NEAREST: return TextureFilter.Nearest;
            case GL20.GL_LINEAR: return TextureFilter.Linear;
            case GL20.GL_NEAREST_MIPMAP_NEAREST: return TextureFilter.MipMapNearestNearest;
            case GL20.GL_LINEAR_MIPMAP_NEAREST: return TextureFilter.MipMapLinearNearest;
            case GL20.GL_NEAREST_MIPMAP_LINEAR: return TextureFilter.MipMapNearestLinear;
            case GL20.GL_LINEAR_MIPMAP_LINEAR: return TextureFilter.MipMapLinearLinear;
            default: throw new GdxRuntimeException("Unknown GL filter " + glEnum);
        }
    }

    public static TextureWrap glToTextureWrap(int glEnum) {
        switch (glEnum) {
            case GL20.GL_CLAMP_TO_EDGE: return TextureWrap.ClampToEdge;
            case GL20.GL_REPEAT: return TextureWrap.Repeat;
            case GL20.GL_MIRRORED_REPEAT: return TextureWrap.MirroredRepeat; // Note: MirroredRepeat is GL_MIRRORED_REPEAT_OES in GLES2
            default: throw new GdxRuntimeException("Unknown GL wrap " + glEnum);
        }
    }

    public static int getVkFilter(TextureFilter filter) {
        switch (filter) {
            case Linear:
            case MipMapLinearLinear:
            case MipMapLinearNearest:
                return org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
            case Nearest:
            case MipMapNearestNearest:
            case MipMapNearestLinear:
            default:
                return org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
        }
    }

    public static int getVkSamplerAddressMode(TextureWrap wrap) {
        switch (wrap) {
            case MirroredRepeat:
                return org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
            case ClampToEdge:
                return org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            case Repeat:
            default:
                return org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT;
        }
    }

    public static int getVkSamplerMipmapMode(TextureFilter filter) {
        if (!filter.isMipMap()) return org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST; // Or Linear if preferred for non-mipmapped
        switch (filter) {
            case MipMapLinearLinear:
            case MipMapLinearNearest:
                return org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR;
            case MipMapNearestNearest:
            case MipMapNearestLinear:
            default:
                return org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
        }
    }

    /**
     * Determines if a VkFormat contains a depth component.
     *
     * @param vkFormat The Vulkan format.
     * @return True if the format has a depth component, false otherwise.
     */
    public static boolean isDepthFormat(int vkFormat) {
        switch (vkFormat) {
            case VK10.VK_FORMAT_D16_UNORM:
            case VK10.VK_FORMAT_X8_D24_UNORM_PACK32: // Depth component is D24
            case VK10.VK_FORMAT_D32_SFLOAT:
            case VK10.VK_FORMAT_D16_UNORM_S8_UINT:   // Has depth
            case VK10.VK_FORMAT_D24_UNORM_S8_UINT:   // Has depth
            case VK10.VK_FORMAT_D32_SFLOAT_S8_UINT:  // Has depth
                return true;
            default:
                return false;
        }
    }

    /**
     * Determines if a VkFormat contains a stencil component.
     *
     * @param vkFormat The Vulkan format.
     * @return True if the format has a stencil component, false otherwise.
     */
    public static boolean isStencilFormat(int vkFormat) {
        switch (vkFormat) {
            case VK10.VK_FORMAT_S8_UINT:
            case VK10.VK_FORMAT_D16_UNORM_S8_UINT:   // Has stencil
            case VK10.VK_FORMAT_D24_UNORM_S8_UINT:   // Has stencil
            case VK10.VK_FORMAT_D32_SFLOAT_S8_UINT:  // Has stencil
                return true;
            default:
                return false;
        }
    }

    /**
     * Gets the appropriate VkImageAspectFlags for a given VkFormat.
     * This is used for image views and image memory barriers.
     *
     * @param vkFormat The Vulkan format (e.g., VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_D32_SFLOAT_S8_UINT).
     * @return The corresponding VkImageAspectFlags.
     */
    public static int getImageAspectMask(int vkFormat) {
        if (isDepthFormat(vkFormat) && isStencilFormat(vkFormat)) {
            // Combined Depth/Stencil format
            return VK10.VK_IMAGE_ASPECT_DEPTH_BIT | VK10.VK_IMAGE_ASPECT_STENCIL_BIT;
        } else if (isDepthFormat(vkFormat)) {
            // Depth-only format
            return VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
        } else if (isStencilFormat(vkFormat)) {
            // Stencil-only format (rare for typical textures, more for attachments)
            return VK10.VK_IMAGE_ASPECT_STENCIL_BIT;
        } else {
            // Assume color format otherwise
            // You might want to add more explicit checks if a format is neither color, depth, nor stencil
            // (e.g. some multi-planar YCbCr formats, though less common for simple texture views)
            return VK10.VK_IMAGE_ASPECT_COLOR_BIT;
        }
    }

}