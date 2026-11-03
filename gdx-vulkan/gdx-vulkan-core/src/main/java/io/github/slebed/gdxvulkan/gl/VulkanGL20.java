package io.github.slebed.gdxvulkan.gl;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.IntMap;

import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkResources;
import io.github.slebed.gdxvulkan.GdxVkSampler;

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * The GL20 shim (plan, Core decision 3): enough of GL for stock libGDX classes ({@code Texture}, {@code Pixmap} uploads, ...) to work
 * unmodified. It implements the resource-management and query parts of GL; it does not emulate drawing or GLSL programs, which
 * throw. Like GL itself it belongs to the render thread.
 *
 * <p>Texture calls are recorded as GL state and the native image is created lazily, at the first {@link #nativeTexture(int)} (the
 * first draw that uses the texture) once the mip chain is known. After that, pixel changes rename the image on the native side.
 */
public class VulkanGL20 extends VulkanGL20Base {
    public static final String VERSION = "0.1.0";

    // Not in the GL20 interface (extensions).
    private static final int GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE;
    private static final int GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF;
    private static final int GL_UNPACK_ALIGNMENT_ = 0x0CF5;
    private static final int GL_FRAMEBUFFER_BINDING_ = 0x8CA6;
    private static final int MAX_LEVELS = 16;

    private static final class GlTexture {
        final int handle;
        int nativeId = -1;
        int format = -1; // GdxVkResources.FORMAT_*, fixed by the first level-0 upload
        int width, height;
        final byte[][] levelData = new byte[MAX_LEVELS][]; // CPU copies until the image exists
        final int[] levelW = new int[MAX_LEVELS], levelH = new int[MAX_LEVELS];
        boolean generateMips;
        // GL defaults
        int minFilter = GL20.GL_NEAREST_MIPMAP_LINEAR, magFilter = GL20.GL_LINEAR, wrapS = GL20.GL_REPEAT, wrapT = GL20.GL_REPEAT;
        float anisotropy = 1f;
        GdxVkSampler appliedSampler; // what the native texture currently uses

        GlTexture(int handle) {
            this.handle = handle;
        }
    }

    private final GdxVkContext context;
    private final GdxVkResources resources;
    private final IntMap<GlTexture> textures = new IntMap<GlTexture>();
    private final int[] boundTexture = new int[32];
    private int nextHandle = 1;
    private int activeUnit;
    private int unpackAlignment = 4;
    private final int[] viewport = new int[4];
    private final Set<Integer> reportedUnknown = new HashSet<Integer>();

    public VulkanGL20(GdxVkContext context) {
        this.context = context;
        this.resources = context.resources();
    }

    // ------------------------------------------------------------------------------------------------------------------ queries

    /** What {@code Graphics.supportsExtension} answers (plan, Core decision 3, query table). */
    public boolean supportsExtension(String extension) {
        if ("GL_ARB_framebuffer_object".equals(extension) || "GL_OES_framebuffer_object".equals(extension)) return true; // MipMapGenerator
        if ("GL_OES_packed_depth_stencil".equals(extension) || "GL_EXT_packed_depth_stencil".equals(extension)) return true; // FrameBuffer
        if ("GL_EXT_texture_filter_anisotropic".equals(extension)) return context.caps().maxSamplerAnisotropy > 1f;
        return false;
    }

    @Override public String glGetString (int name) {
        switch (name) {
        case GL_VERSION: return "OpenGL ES 2.0 gdx-vulkan " + VERSION; // so GLVersion parses as GLES 2.0
        case GL_VENDOR: return "gdx-vulkan";
        case GL_RENDERER: return context.caps().deviceName;
        case GL_SHADING_LANGUAGE_VERSION: return "OpenGL ES GLSL ES 1.00";
        case GL_EXTENSIONS: return "GL_OES_framebuffer_object GL_OES_packed_depth_stencil"
            + (context.caps().maxSamplerAnisotropy > 1f ? " GL_EXT_texture_filter_anisotropic" : "");
        default: return reportUnknown(name) ? null : null;
        }
    }

    @Override public void glGetIntegerv (int pname, IntBuffer params) {
        int p = params.position();
        switch (pname) {
        case GL_MAX_TEXTURE_SIZE: params.put(p, context.caps().maxImageDimension2D); break;
        case GL_VIEWPORT:
            for (int i = 0; i < 4; i++) params.put(p + i, viewport[i]);
            break;
        case GL_FRAMEBUFFER_BINDING_: params.put(p, 0); break; // the swapchain
        case GL_NUM_COMPRESSED_TEXTURE_FORMATS: params.put(p, 0); break;
        case GL_MAX_TEXTURE_IMAGE_UNITS: params.put(p, 16); break;
        case GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS: params.put(p, 32); break;
        case GL_MAX_VERTEX_ATTRIBS: params.put(p, 16); break;
        default:
            reportUnknown(pname);
            params.put(p, 0);
        }
    }

    @Override public void glGetFloatv (int pname, FloatBuffer params) {
        int p = params.position();
        if (pname == GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT) {
            params.put(p, Math.max(1f, context.caps().maxSamplerAnisotropy));
        } else {
            reportUnknown(pname);
            params.put(p, 0f);
        }
    }

    /** Unknown enums log once and answer 0 (plan). @return always true, so callers can use it in an expression */
    private boolean reportUnknown(int pname) {
        if (reportedUnknown.add(pname)) {
            com.badlogic.gdx.Gdx.app.log("gdx-vulkan", "GL query 0x" + Integer.toHexString(pname) + " is not answered by the shim; returning 0");
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------------- ordered state (tracked)

    @Override public void glViewport (int x, int y, int width, int height) {
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = width;
        viewport[3] = height;
    }

    // ----------------------------------------------------------------------------------------------------------------- textures

    @Override public int glGenTexture () {
        int h = nextHandle++;
        textures.put(h, new GlTexture(h));
        return h;
    }

    @Override public void glGenTextures (int n, IntBuffer textures) {
        for (int i = 0; i < n; i++) textures.put(textures.position() + i, glGenTexture());
    }

    @Override public void glDeleteTexture (int texture) {
        GlTexture t = textures.remove(texture);
        if (t == null) return;
        if (t.nativeId >= 0) resources.destroyTexture(t.nativeId);
        for (int i = 0; i < boundTexture.length; i++) if (boundTexture[i] == texture) boundTexture[i] = 0;
    }

    @Override public void glDeleteTextures (int n, IntBuffer textures) {
        for (int i = 0; i < n; i++) glDeleteTexture(textures.get(textures.position() + i));
    }

    @Override public void glActiveTexture (int texture) {
        activeUnit = texture - GL_TEXTURE0;
        if (activeUnit < 0 || activeUnit >= boundTexture.length) throw new GdxRuntimeException("gdx-vulkan: texture unit out of range: " + activeUnit);
    }

    @Override public void glBindTexture (int target, int texture) {
        if (target != GL_TEXTURE_2D) throw unsupported("glBindTexture(target 0x" + Integer.toHexString(target) + ")");
        if (texture != 0 && textures.get(texture) == null) textures.put(texture, new GlTexture(texture)); // a name from elsewhere
        boundTexture[activeUnit] = texture;
    }

    @Override public void glPixelStorei (int pname, int param) {
        if (pname == GL_UNPACK_ALIGNMENT_) unpackAlignment = param;
    }

    private GlTexture bound(int target) {
        if (target != GL_TEXTURE_2D) throw unsupported("texture target 0x" + Integer.toHexString(target));
        GlTexture t = textures.get(boundTexture[activeUnit]);
        if (t == null) throw new GdxRuntimeException("gdx-vulkan: no texture is bound to unit " + activeUnit);
        return t;
    }

    private static int levelExtent(int base, int level) {
        return Math.max(1, base >> level);
    }

    @Override public void glTexImage2D (int target, int level, int internalformat, int width, int height, int border, int format, int type, Buffer pixels) {
        GlTexture t = bound(target);
        if (level < 0 || level >= MAX_LEVELS) throw new GdxRuntimeException("gdx-vulkan: mip level " + level + " out of range");
        PixelConversion.Converted c = PixelConversion.convert(format, type, width, height, unpackAlignment, pixels);

        if (t.nativeId >= 0) {
            int[] info = resources.textureInfo(t.nativeId);
            boolean sameShape = c.format == t.format && level < info[2] && width == levelExtent(info[0], level) && height == levelExtent(info[1], level);
            if (sameShape) { // a reload of the same storage: rewrite the level (the native side renames the image)
                resources.updateTexture(t.nativeId, level, 0, 0, width, height, c.data);
                return;
            }
            if (level != 0) {
                throw unsupported("glTexImage2D of level " + level + " (" + width + "x" + height + ") after the image was created: its mip chain is fixed");
            }
            // Level 0 redefined with another size or format: start over, as GL does.
            resources.destroyTexture(t.nativeId);
            t.nativeId = -1;
            t.appliedSampler = null;
            java.util.Arrays.fill(t.levelData, null);
            t.generateMips = false;
        }
        t.levelData[level] = c.data;
        t.levelW[level] = width;
        t.levelH[level] = height;
        if (level == 0) {
            t.format = c.format;
            t.width = width;
            t.height = height;
        }
    }

    @Override public void glTexSubImage2D (int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, Buffer pixels) {
        GlTexture t = bound(target);
        PixelConversion.Converted c = PixelConversion.convert(format, type, width, height, unpackAlignment, pixels);
        if (c.format != t.format) throw new GdxRuntimeException("gdx-vulkan: glTexSubImage2D format differs from the texture's");
        if (t.nativeId >= 0) {
            resources.updateTexture(t.nativeId, level, xoffset, yoffset, width, height, c.data);
            return;
        }
        byte[] dst = t.levelData[level];
        if (dst == null) throw new GdxRuntimeException("gdx-vulkan: glTexSubImage2D into level " + level + " that has no storage");
        int bpp = GdxVkResources.bytesPerTexel(t.format);
        for (int y = 0; y < height; y++) {
            System.arraycopy(c.data, y * width * bpp, dst, ((yoffset + y) * t.levelW[level] + xoffset) * bpp, width * bpp);
        }
    }

    @Override public void glGenerateMipmap (int target) {
        GlTexture t = bound(target);
        if (t.nativeId >= 0) resources.regenerateMips(t.nativeId);
        else if (t.levelData[0] != null) t.generateMips = true;
    }

    @Override public void glTexParameteri (int target, int pname, int param) {
        GlTexture t = bound(target);
        switch (pname) {
        case GL_TEXTURE_MIN_FILTER: t.minFilter = param; break;
        case GL_TEXTURE_MAG_FILTER: t.magFilter = param; break;
        case GL_TEXTURE_WRAP_S: t.wrapS = param; break;
        case GL_TEXTURE_WRAP_T: t.wrapT = param; break;
        default: reportUnknown(pname); return;
        }
        applySampler(t);
    }

    @Override public void glTexParameterf (int target, int pname, float param) {
        if (pname == GL_TEXTURE_MAX_ANISOTROPY_EXT) {
            GlTexture t = bound(target);
            t.anisotropy = Math.max(1f, param);
            applySampler(t);
        } else {
            glTexParameteri(target, pname, (int)param);
        }
    }

    private static GdxVkSampler samplerOf(GlTexture t) {
        GdxVkSampler s = new GdxVkSampler();
        switch (t.minFilter) {
        case GL_NEAREST:
            s.minFilter = GdxVkSampler.NEAREST;
            s.mipFilter = GdxVkSampler.MIP_NONE;
            break;
        case GL_LINEAR:
            s.minFilter = GdxVkSampler.LINEAR;
            s.mipFilter = GdxVkSampler.MIP_NONE;
            break;
        case GL_NEAREST_MIPMAP_NEAREST:
            s.minFilter = GdxVkSampler.NEAREST;
            s.mipFilter = GdxVkSampler.NEAREST;
            break;
        case GL_LINEAR_MIPMAP_NEAREST:
            s.minFilter = GdxVkSampler.LINEAR;
            s.mipFilter = GdxVkSampler.NEAREST;
            break;
        case GL_NEAREST_MIPMAP_LINEAR:
            s.minFilter = GdxVkSampler.NEAREST;
            s.mipFilter = GdxVkSampler.LINEAR;
            break;
        default: // GL_LINEAR_MIPMAP_LINEAR
            s.minFilter = GdxVkSampler.LINEAR;
            s.mipFilter = GdxVkSampler.LINEAR;
        }
        s.magFilter = t.magFilter == GL_NEAREST ? GdxVkSampler.NEAREST : GdxVkSampler.LINEAR;
        s.wrapU = wrap(t.wrapS);
        s.wrapV = wrap(t.wrapT);
        s.anisotropy = (int)t.anisotropy;
        return s;
    }

    private static int wrap(int glWrap) {
        return glWrap == GL_REPEAT ? GdxVkSampler.REPEAT : glWrap == GL_MIRRORED_REPEAT ? GdxVkSampler.MIRROR : GdxVkSampler.CLAMP;
    }

    private static boolean same(GdxVkSampler a, GdxVkSampler b) {
        return a != null && b != null && a.minFilter == b.minFilter && a.magFilter == b.magFilter && a.mipFilter == b.mipFilter
            && a.wrapU == b.wrapU && a.wrapV == b.wrapV && a.anisotropy == b.anisotropy;
    }

    /** Sampler changes after creation allocate a new binding and retire the old one, so only do it when something changed. */
    private void applySampler(GlTexture t) {
        if (t.nativeId < 0) return;
        GdxVkSampler s = samplerOf(t);
        if (same(s, t.appliedSampler)) return;
        resources.setSampler(t.nativeId, s);
        t.appliedSampler = s;
    }

    /**
     * The native texture id behind a GL texture handle, creating the image now if this is its first use. The batches call this when
     * a draw needs the texture. @return -1 if the handle is unknown or has no level 0 yet
     */
    public int nativeTexture(int glHandle) {
        GlTexture t = textures.get(glHandle);
        if (t == null) return -1;
        if (t.nativeId >= 0) return t.nativeId;
        if (t.levelData[0] == null) return -1;

        int levels = 1;
        if (!t.generateMips) {
            while (levels < MAX_LEVELS && t.levelData[levels] != null) levels++;
        }
        int bpp = GdxVkResources.bytesPerTexel(t.format);
        int total = 0;
        for (int l = 0; l < levels; l++) {
            if (t.levelW[l] != levelExtent(t.width, l) || t.levelH[l] != levelExtent(t.height, l)) {
                throw new GdxRuntimeException("gdx-vulkan: mip level " + l + " is " + t.levelW[l] + "x" + t.levelH[l] + ", expected "
                    + levelExtent(t.width, l) + "x" + levelExtent(t.height, l));
            }
            total += t.levelData[l].length;
        }
        byte[] data = new byte[total];
        int at = 0;
        for (int l = 0; l < levels; l++) {
            System.arraycopy(t.levelData[l], 0, data, at, t.levelData[l].length);
            at += t.levelData[l].length;
        }
        GdxVkSampler s = samplerOf(t);
        t.nativeId = resources.createTexture(t.width, t.height, t.format, levels, t.generateMips, data, s);
        t.appliedSampler = s;
        java.util.Arrays.fill(t.levelData, null); // the image owns the pixels now
        return t.nativeId;
    }

    /** Textures the shim currently knows (GL names, not native images). */
    public int textureCount() {
        return textures.size;
    }
}
