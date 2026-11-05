package io.github.slebed.gdxvulkan.gl;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.GdxRuntimeException;

import io.github.slebed.gdxvulkan.GdxVkResources;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Turns the (format, type, pixels) of a glTexImage2D / glTexSubImage2D into one of the three native texture formats (plan, Phase 2):
 * <pre>
 *   GL_ALPHA / UNSIGNED_BYTE            -> R8         (sampled as 0,0,0,r)
 *   GL_LUMINANCE_ALPHA / UNSIGNED_BYTE  -> R8G8       (sampled as r,r,r,g)
 *   GL_RGBA / UNSIGNED_BYTE             -> R8G8B8A8
 *   GL_RGB / UNSIGNED_BYTE              -> R8G8B8A8, alpha 255
 *   GL_LUMINANCE / UNSIGNED_BYTE        -> R8G8B8A8 (l,l,l,255)
 *   GL_RGB / UNSIGNED_SHORT_5_6_5       -> R8G8B8A8 (expanded; a packed Vulkan format is a later optimisation)
 *   GL_RGBA / UNSIGNED_SHORT_4_4_4_4    -> R8G8B8A8 (expanded)
 * </pre>
 * Rows are read honouring GL_UNPACK_ALIGNMENT; the result is tightly packed.
 */
final class PixelConversion {
    private PixelConversion() {}

    static final class Converted {
        final int format; // GdxVkResources.FORMAT_*
        final byte[] data;

        Converted(int format, byte[] data) {
            this.format = format;
            this.data = data;
        }
    }

    static final int GL_HALF_FLOAT = 0x140B, GL_HALF_FLOAT_OES = 0x8D61;

    /**
     * What {@code convert} returns as the data of a texture defined with no pixels (glTexImage2D with null): the contents are undefined until
     * something draws into the texture or uploads to it, so nothing is allocated. Compare by identity.
     */
    static final byte[] NO_CONTENTS = new byte[0];

    private static final int GL_RED = 0x1903;

    /** The native format a GL (format, type) pair is stored as. */
    static int nativeFormat(int glFormat, int glType) {
        if (glType == GL20.GL_UNSIGNED_BYTE) {
            switch (glFormat) {
            case GL20.GL_ALPHA: return GdxVkResources.FORMAT_ALPHA;
            case GL20.GL_LUMINANCE_ALPHA: return GdxVkResources.FORMAT_LUMINANCE_ALPHA;
            case GL20.GL_RGBA:
            case GL20.GL_RGB:
            case GL20.GL_LUMINANCE: return GdxVkResources.FORMAT_RGBA8;
            default: break;
            }
        } else if (glFormat == GL_RED && glType == GL20.GL_FLOAT) {
            return GdxVkResources.FORMAT_R32F;
        } else if (glFormat == GL20.GL_RGBA && (glType == GL_HALF_FLOAT || glType == GL_HALF_FLOAT_OES)) {
            return GdxVkResources.FORMAT_RGBA16F; // a floating-point framebuffer attachment: no pixel data can be given for it
        } else if (glFormat == GL20.GL_RGBA && glType == GL20.GL_FLOAT) {
            return GdxVkResources.FORMAT_RGBA32F;
        } else if (glType == GL20.GL_UNSIGNED_SHORT_5_6_5 && glFormat == GL20.GL_RGB) {
            return GdxVkResources.FORMAT_RGBA8;
        } else if (glType == GL20.GL_UNSIGNED_SHORT_4_4_4_4 && glFormat == GL20.GL_RGBA) {
            return GdxVkResources.FORMAT_RGBA8;
        }
        throw new GdxRuntimeException("gdx-vulkan: unsupported pixel format 0x" + Integer.toHexString(glFormat) + " / type 0x"
            + Integer.toHexString(glType));
    }

    /** The {@code size} bytes at a ByteBuffer's position, copied out (compressed texture data is passed through untouched). */
    static byte[] bytesOf(Buffer data, int size) {
        if (!(data instanceof ByteBuffer)) {
            throw new GdxRuntimeException("gdx-vulkan: compressed texture data must be a ByteBuffer, was " + (data == null ? "null" : data.getClass().getSimpleName()));
        }
        ByteBuffer src = ((ByteBuffer)data).duplicate();
        if (src.remaining() < size) throw new GdxRuntimeException("gdx-vulkan: compressed data has " + src.remaining() + " bytes, imageSize says " + size);
        byte[] out = new byte[size];
        src.get(out);
        return out;
    }

    /** @param pixels the source (position to limit); null yields zeroed storage, as glTexImage2D with no data does */
    static Converted convert(int glFormat, int glType, int width, int height, int alignment, Buffer pixels) {
        int format = nativeFormat(glFormat, glType);
        int outBpp = GdxVkResources.bytesPerTexel(format);
        if (pixels == null) return new Converted(format, NO_CONTENTS);
        if (format == GdxVkResources.FORMAT_RGBA16F || format == GdxVkResources.FORMAT_RGBA32F) {
            throw new GdxRuntimeException("gdx-vulkan: floating-point textures can only be framebuffer attachments: pixel data cannot be uploaded to one");
        }
        if (format == GdxVkResources.FORMAT_R32F) { // float rows copied as they are
            if (!(pixels instanceof ByteBuffer) && !(pixels instanceof java.nio.FloatBuffer)) throw new GdxRuntimeException("gdx-vulkan: R32F texture data must be a ByteBuffer or a FloatBuffer");
            final ByteBuffer raw;
            if (pixels instanceof java.nio.FloatBuffer) {
                final java.nio.FloatBuffer fb = ((java.nio.FloatBuffer)pixels).duplicate();
                raw = ByteBuffer.allocate(fb.remaining() * 4).order(ByteOrder.nativeOrder());
                raw.asFloatBuffer().put(fb);
            } else {
                raw = ((ByteBuffer)pixels).duplicate().order(ByteOrder.nativeOrder());
            }
            final int rowBytes = width * 4, rowStride = (rowBytes + alignment - 1) / alignment * alignment;
            final byte[] floats = new byte[width * height * 4];
            final int start = raw.position();
            if (raw.remaining() < rowStride * (height - 1) + rowBytes) throw new GdxRuntimeException("gdx-vulkan: the pixel buffer is too small for " + width + "x" + height + " floats");
            for (int y = 0; y < height; y++) {
                raw.position(start + y * rowStride);
                raw.get(floats, y * rowBytes, rowBytes);
            }
            return new Converted(format, floats);
        }
        byte[] out = new byte[width * height * outBpp];
        if (!(pixels instanceof ByteBuffer)) {
            throw new GdxRuntimeException("gdx-vulkan: texture data must be a ByteBuffer, was " + pixels.getClass().getSimpleName());
        }
        ByteBuffer src = ((ByteBuffer)pixels).duplicate().order(ByteOrder.nativeOrder());
        int srcBpp = glType == GL20.GL_UNSIGNED_BYTE ? (glFormat == GL20.GL_RGBA ? 4 : glFormat == GL20.GL_RGB ? 3
            : glFormat == GL20.GL_LUMINANCE_ALPHA ? 2 : 1) : 2;
        int rowBytes = width * srcBpp;
        int stride = (rowBytes + alignment - 1) / alignment * alignment;
        int need = stride * (height - 1) + rowBytes;
        if (src.remaining() < need) {
            throw new GdxRuntimeException("gdx-vulkan: pixel buffer has " + src.remaining() + " bytes, " + need + " needed for " + width + "x" + height);
        }
        int base = src.position();
        int o = 0;
        for (int y = 0; y < height; y++) {
            int p = base + y * stride;
            for (int x = 0; x < width; x++, p += srcBpp) {
                if (glType == GL20.GL_UNSIGNED_BYTE) {
                    switch (glFormat) {
                    case GL20.GL_RGBA:
                        out[o++] = src.get(p);
                        out[o++] = src.get(p + 1);
                        out[o++] = src.get(p + 2);
                        out[o++] = src.get(p + 3);
                        break;
                    case GL20.GL_RGB:
                        out[o++] = src.get(p);
                        out[o++] = src.get(p + 1);
                        out[o++] = src.get(p + 2);
                        out[o++] = (byte)255;
                        break;
                    case GL20.GL_LUMINANCE: {
                        byte l = src.get(p);
                        out[o++] = l;
                        out[o++] = l;
                        out[o++] = l;
                        out[o++] = (byte)255;
                        break;
                    }
                    case GL20.GL_LUMINANCE_ALPHA:
                        out[o++] = src.get(p);
                        out[o++] = src.get(p + 1);
                        break;
                    default: // GL_ALPHA
                        out[o++] = src.get(p);
                        break;
                    }
                } else if (glType == GL20.GL_UNSIGNED_SHORT_5_6_5) {
                    int v = src.getShort(p) & 0xFFFF;
                    int r = (v >> 11) & 31, g = (v >> 5) & 63, b = v & 31;
                    out[o++] = (byte)((r << 3) | (r >> 2));
                    out[o++] = (byte)((g << 2) | (g >> 4));
                    out[o++] = (byte)((b << 3) | (b >> 2));
                    out[o++] = (byte)255;
                } else { // 4_4_4_4
                    int v = src.getShort(p) & 0xFFFF;
                    out[o++] = (byte)(((v >> 12) & 15) * 17);
                    out[o++] = (byte)(((v >> 8) & 15) * 17);
                    out[o++] = (byte)(((v >> 4) & 15) * 17);
                    out[o++] = (byte)((v & 15) * 17);
                }
            }
        }
        return new Converted(format, out);
    }
}
