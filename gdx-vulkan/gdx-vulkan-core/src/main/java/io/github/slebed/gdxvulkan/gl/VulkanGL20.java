package io.github.slebed.gdxvulkan.gl;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.IntMap;

import io.github.slebed.gdxvulkan.CommandStream;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkResources;
import io.github.slebed.gdxvulkan.GdxVkSampler;

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        int vkFormat, blockW, blockH, blockBytes; // FORMAT_COMPRESSED only
        int width, height;
        final byte[][] levelData = new byte[MAX_LEVELS][]; // CPU copies until the image exists
        final int[] levelW = new int[MAX_LEVELS], levelH = new int[MAX_LEVELS];
        boolean generateMips;
        boolean renderTarget; // attached to a framebuffer: the native image is also a colour attachment
        // GL defaults
        int minFilter = GL20.GL_NEAREST_MIPMAP_LINEAR, magFilter = GL20.GL_LINEAR, wrapS = GL20.GL_REPEAT, wrapT = GL20.GL_REPEAT;
        float anisotropy = 1f;
        GdxVkSampler appliedSampler; // what the native texture currently uses

        GlTexture(int handle) {
            this.handle = handle;
        }
    }

    /**
     * Told when a texture's native image or sampler changed behind a GL handle (a region update renamed it, mips were rebuilt, a filter
     * changed). A batch that has the texture in flight uses it to start a new slot, so quads after the change see the new contents and
     * quads before it keep the old ones (plan, Core decision 4).
     */
    public interface TextureRebindListener {
        void textureRebound(int glHandle);
    }

    private final GdxVkContext context;
    private final GdxVkResources resources;
    private final List<TextureRebindListener> listeners = new ArrayList<TextureRebindListener>();
    private CommandStream stream; // ordered state goes here, so it interleaves correctly with the batches' draws
    private float clearR, clearG, clearB, clearA;
    private boolean viewportSet, scissorEnabled;
    private final int[] scissor = new int[4];
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
        case GL_FRAMEBUFFER_BINDING_: params.put(p, boundFramebuffer); break; // 0 = the swapchain
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

    /** Where ordered state (clear, viewport, scissor) is written. The frame sets this; without one the state is only tracked. */
    public void setCommandStream(CommandStream stream) {
        this.stream = stream;
    }

    /**
     * GL state persists across frames but a Vulkan frame starts from defaults, so a new frame re-emits whatever the application set.
     * Call after {@code setCommandStream} at the start of every frame.
     */
    public void replayState() {
        if (stream == null) return;
        if (boundFramebuffer != 0) emitTarget(); // a frame starts on the swapchain; GL state says otherwise
        emitDepth();
        emitStencil();
        if (viewportSet) stream.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        if (scissorEnabled) {
            stream.scissor(scissor[0], scissor[1], scissor[2], scissor[3]);
            stream.scissorEnable(true);
        }
    }

    public void addTextureRebindListener (TextureRebindListener l) {
        listeners.add(l);
    }

    public void removeTextureRebindListener (TextureRebindListener l) {
        listeners.remove(l);
    }

    private void rebound (GlTexture t) {
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).textureRebound(t.handle);
    }

    @Override public void glViewport (int x, int y, int width, int height) {
        viewport[0] = x;
        viewport[1] = y;
        viewport[2] = width;
        viewport[3] = height;
        viewportSet = true;
        if (stream != null) stream.viewport(x, y, width, height);
    }

    @Override public void glScissor (int x, int y, int width, int height) {
        scissor[0] = x;
        scissor[1] = y;
        scissor[2] = width;
        scissor[3] = height;
        if (stream != null) stream.scissor(x, y, width, height);
    }

    // Blend state is pipeline state in Vulkan. The batches own theirs; the immediate-mode renderer follows what the application set here.
    private boolean blendEnabled;
    private int blendSrcRgb = GL_ONE, blendDstRgb = GL_ZERO, blendSrcAlpha = GL_ONE, blendDstAlpha = GL_ZERO;

    public boolean isBlendEnabled () {
        return blendEnabled;
    }

    /** {srcRGB, dstRGB, srcAlpha, dstAlpha} as set by glBlendFunc / glBlendFuncSeparate. */
    public int[] blendFunctions () {
        return new int[] {blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha};
    }

    @Override public void glBlendFunc (int sfactor, int dfactor) {
        blendSrcRgb = blendSrcAlpha = sfactor;
        blendDstRgb = blendDstAlpha = dfactor;
    }

    @Override public void glBlendFuncSeparate (int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        blendSrcRgb = srcRGB;
        blendDstRgb = dstRGB;
        blendSrcAlpha = srcAlpha;
        blendDstAlpha = dstAlpha;
    }

    // Depth and stencil state: ordered, like clear and scissor. The native side applies it as dynamic state (Tier A) or picks a pipeline
    // variant (Tier B); it only matters while the current target has a depth attachment (a framebuffer's).
    private static final int GL_DEPTH_TEST_ = 0x0B71, GL_STENCIL_TEST_ = 0x0B90, GL_LESS_ = 0x0201, GL_ALWAYS_ = 0x0207, GL_KEEP_ = 0x1E00;
    private boolean depthTest, depthMask = true, stencilTest;
    private int depthFunc = GL_LESS_, stencilFunc = GL_ALWAYS_, stencilRef, stencilCompareMask = -1, stencilWriteMask = -1;
    private int stencilFail = GL_KEEP_, stencilDepthFail = GL_KEEP_, stencilPass = GL_KEEP_;
    private float clearDepth = 1f;
    private int clearStencil;

    private void emitDepth () {
        if (stream != null) stream.depthState(depthTest, depthMask, depthFunc);
    }

    private void emitStencil () {
        if (stream != null) {
            stream.stencilState(stencilTest, stencilFunc, stencilRef, stencilCompareMask, stencilWriteMask, stencilFail, stencilDepthFail, stencilPass);
        }
    }

    @Override public void glEnable (int cap) {
        if (cap == GL_BLEND) blendEnabled = true;
        if (cap == GL_SCISSOR_TEST) {
            scissorEnabled = true;
            if (stream != null) stream.scissorEnable(true);
        }
        if (cap == GL_DEPTH_TEST_) {
            depthTest = true;
            emitDepth();
        }
        if (cap == GL_STENCIL_TEST_) {
            stencilTest = true;
            emitStencil();
        }
    }

    @Override public void glDisable (int cap) {
        if (cap == GL_BLEND) blendEnabled = false;
        if (cap == GL_SCISSOR_TEST) {
            scissorEnabled = false;
            if (stream != null) stream.scissorEnable(false);
        }
        if (cap == GL_DEPTH_TEST_) {
            depthTest = false;
            emitDepth();
        }
        if (cap == GL_STENCIL_TEST_) {
            stencilTest = false;
            emitStencil();
        }
    }

    @Override public void glDepthFunc (int func) {
        depthFunc = func;
        emitDepth();
    }

    @Override public void glDepthMask (boolean flag) {
        depthMask = flag;
        emitDepth();
    }

    @Override public void glStencilFunc (int func, int ref, int mask) {
        stencilFunc = func;
        stencilRef = ref;
        stencilCompareMask = mask;
        emitStencil();
    }

    @Override public void glStencilMask (int mask) {
        stencilWriteMask = mask;
        emitStencil();
    }

    @Override public void glStencilOp (int fail, int zfail, int zpass) {
        stencilFail = fail;
        stencilDepthFail = zfail;
        stencilPass = zpass;
        emitStencil();
    }

    @Override public void glClearDepthf (float depth) {
        clearDepth = depth;
    }

    @Override public void glClearStencil (int s) {
        clearStencil = s;
    }

    @Override public void glClearColor (float red, float green, float blue, float alpha) {
        clearR = red;
        clearG = green;
        clearB = blue;
        clearA = alpha;
    }

    @Override public void glClear (int mask) {
        if (stream != null) stream.clear(mask, clearR, clearG, clearB, clearA, clearDepth, clearStencil);
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
                rebound(t);
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
            rebound(t);
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
            rebound(t);
            return;
        }
        byte[] dst = t.levelData[level];
        if (dst == null) throw new GdxRuntimeException("gdx-vulkan: glTexSubImage2D into level " + level + " that has no storage");
        int bpp = GdxVkResources.bytesPerTexel(t.format);
        for (int y = 0; y < height; y++) {
            System.arraycopy(c.data, y * width * bpp, dst, ((yoffset + y) * t.levelW[level] + xoffset) * bpp, width * bpp);
        }
    }

    /** GL compressed internal format -> {VkFormat, blockW, blockH, blockBytes}, or null. */
    private static int[] compressedFormat(int glFormat) {
        switch (glFormat) {
        case 0x83F0: case 0x83F1: return new int[] {131, 4, 4, 8}; // DXT1 RGB / RGBA -> BC1_RGBA_UNORM
        case 0x83F2: return new int[] {135, 4, 4, 16}; // DXT3 -> BC2
        case 0x83F3: return new int[] {137, 4, 4, 16}; // DXT5 -> BC3
        case 0x8D64: case 0x9274: return new int[] {147, 4, 4, 8}; // ETC1 / ETC2 RGB8 -> ETC2_R8G8B8
        case 0x9276: return new int[] {149, 4, 4, 8}; // ETC2 RGB8 punch-through alpha
        case 0x9278: return new int[] {151, 4, 4, 16}; // ETC2 RGBA8
        default:
            if (glFormat >= 0x93B0 && glFormat <= 0x93BD) { // ASTC 4x4 ... 12x12
                final int[][] blocks = {{4, 4}, {5, 4}, {5, 5}, {6, 5}, {6, 6}, {8, 5}, {8, 6}, {8, 8}, {10, 5}, {10, 6}, {10, 8}, {10, 10}, {12, 10}, {12, 12}};
                final int i = glFormat - 0x93B0;
                return new int[] {157 + i * 2, blocks[i][0], blocks[i][1], 16}; // ASTC_nxm_UNORM_BLOCK = 157, 159, ...
            }
            return null;
        }
    }

    @Override public void glCompressedTexImage2D (int target, int level, int internalformat, int width, int height, int border, int imageSize, Buffer data) {
        GlTexture t = bound(target);
        if (level < 0 || level >= MAX_LEVELS) throw new GdxRuntimeException("gdx-vulkan: mip level " + level + " out of range");
        int[] f = compressedFormat(internalformat);
        if (f == null) throw unsupported("compressed texture format 0x" + Integer.toHexString(internalformat));
        if (!resources.formatSupported(f[0])) {
            throw new GdxRuntimeException("gdx-vulkan: this GPU cannot sample compressed format 0x" + Integer.toHexString(internalformat)
                + " (VkFormat " + f[0] + "); BC formats are desktop-only, ETC2 and ASTC mobile-only");
        }
        if (data == null) throw unsupported("glCompressedTexImage2D without data");
        byte[] bytes = PixelConversion.bytesOf(data, imageSize);
        int expected = ((width + f[1] - 1) / f[1]) * ((height + f[2] - 1) / f[2]) * f[3];
        if (bytes.length != expected) throw new GdxRuntimeException("gdx-vulkan: compressed level is " + bytes.length + " bytes, expected " + expected);
        if (t.nativeId >= 0) {
            if (level != 0) throw unsupported("glCompressedTexImage2D of level " + level + " after the image was created");
            resources.destroyTexture(t.nativeId);
            t.nativeId = -1;
            t.appliedSampler = null;
            java.util.Arrays.fill(t.levelData, null);
            t.generateMips = false;
            rebound(t);
        }
        t.levelData[level] = bytes;
        t.levelW[level] = width;
        t.levelH[level] = height;
        if (level == 0) {
            t.format = GdxVkResources.FORMAT_COMPRESSED;
            t.vkFormat = f[0];
            t.blockW = f[1];
            t.blockH = f[2];
            t.blockBytes = f[3];
            t.width = width;
            t.height = height;
        }
    }

    @Override public void glCompressedTexSubImage2D (int target, int level, int xoffset, int yoffset, int width, int height, int format, int imageSize, Buffer data) {
        GlTexture t = bound(target);
        if (t.format != GdxVkResources.FORMAT_COMPRESSED) throw new GdxRuntimeException("gdx-vulkan: glCompressedTexSubImage2D into an uncompressed texture");
        if (t.nativeId < 0) throw unsupported("glCompressedTexSubImage2D before the image exists (draw with it once first)");
        resources.updateTexture(t.nativeId, level, xoffset, yoffset, width, height, PixelConversion.bytesOf(data, imageSize));
        rebound(t);
    }

    @Override public void glGenerateMipmap (int target) {
        GlTexture t = bound(target);
        if (t.format == GdxVkResources.FORMAT_COMPRESSED) return; // compressed images cannot be blitted; GL leaves them as uploaded too
        if (t.nativeId >= 0) {
            resources.regenerateMips(t.nativeId);
            rebound(t);
        }
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
        rebound(t);
    }

    // ------------------------------------------------------------------------------------------------------------------ framebuffers

    private static final int GL_FRAMEBUFFER = 0x8D40, GL_RENDERBUFFER = 0x8D41, GL_COLOR_ATTACHMENT0 = 0x8CE0, GL_DEPTH_ATTACHMENT = 0x8D00,
        GL_STENCIL_ATTACHMENT = 0x8D20, GL_DEPTH_STENCIL_ATTACHMENT = 0x821A, GL_FRAMEBUFFER_COMPLETE = 0x8CD5,
        GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT = 0x8CD7, GL_FRAMEBUFFER_UNSUPPORTED = 0x8CDD;

    private static final class GlFramebuffer {
        final int handle;
        int colorTexture; // GL handle of the texture on COLOR_ATTACHMENT0, 0 = none
        int depthRenderbuffer, stencilRenderbuffer, depthStencilRenderbuffer;
        int depthImage = -1; // the native depth(-stencil) image, created at first use and recreated if the attachments change
        long depthSignature;

        GlFramebuffer(int handle) {
            this.handle = handle;
        }
    }

    private static final class GlRenderbuffer {
        final int handle;
        int format, width, height;

        GlRenderbuffer(int handle) {
            this.handle = handle;
        }
    }

    private final IntMap<GlFramebuffer> framebuffers = new IntMap<GlFramebuffer>();
    private final IntMap<GlRenderbuffer> renderbuffers = new IntMap<GlRenderbuffer>();
    private int boundFramebuffer, boundRenderbuffer;

    @Override public int glGenFramebuffer () {
        int h = nextHandle++;
        framebuffers.put(h, new GlFramebuffer(h));
        return h;
    }

    @Override public void glGenFramebuffers (int n, IntBuffer buffers) {
        for (int i = 0; i < n; i++) buffers.put(buffers.position() + i, glGenFramebuffer());
    }

    @Override public void glDeleteFramebuffer (int framebuffer) {
        GlFramebuffer gone = framebuffers.remove(framebuffer);
        if (gone != null && gone.depthImage >= 0) resources.destroyDepthImage(gone.depthImage);
        if (boundFramebuffer == framebuffer) glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    @Override public void glDeleteFramebuffers (int n, IntBuffer buffers) {
        for (int i = 0; i < n; i++) glDeleteFramebuffer(buffers.get(buffers.position() + i));
    }

    @Override public int glGenRenderbuffer () {
        int h = nextHandle++;
        renderbuffers.put(h, new GlRenderbuffer(h));
        return h;
    }

    @Override public void glGenRenderbuffers (int n, IntBuffer buffers) {
        for (int i = 0; i < n; i++) buffers.put(buffers.position() + i, glGenRenderbuffer());
    }

    @Override public void glDeleteRenderbuffer (int renderbuffer) {
        renderbuffers.remove(renderbuffer);
        if (boundRenderbuffer == renderbuffer) boundRenderbuffer = 0;
    }

    @Override public void glDeleteRenderbuffers (int n, IntBuffer buffers) {
        for (int i = 0; i < n; i++) glDeleteRenderbuffer(buffers.get(buffers.position() + i));
    }

    @Override public void glBindRenderbuffer (int target, int renderbuffer) {
        if (target != GL_RENDERBUFFER) throw unsupported("glBindRenderbuffer(target 0x" + Integer.toHexString(target) + ")");
        if (renderbuffer != 0 && renderbuffers.get(renderbuffer) == null) renderbuffers.put(renderbuffer, new GlRenderbuffer(renderbuffer));
        boundRenderbuffer = renderbuffer;
    }

    @Override public void glRenderbufferStorage (int target, int internalformat, int width, int height) {
        GlRenderbuffer rb = renderbuffers.get(boundRenderbuffer);
        if (target != GL_RENDERBUFFER || rb == null) throw new GdxRuntimeException("gdx-vulkan: glRenderbufferStorage with no renderbuffer bound");
        rb.format = internalformat;
        rb.width = width;
        rb.height = height;
    }

    @Override public void glBindFramebuffer (int target, int framebuffer) {
        if (target != GL_FRAMEBUFFER) throw unsupported("glBindFramebuffer(target 0x" + Integer.toHexString(target) + ")");
        if (framebuffer != 0 && framebuffers.get(framebuffer) == null) framebuffers.put(framebuffer, new GlFramebuffer(framebuffer));
        boundFramebuffer = framebuffer;
        emitTarget();
    }

    @Override public void glFramebufferTexture2D (int target, int attachment, int textarget, int texture, int level) {
        GlFramebuffer fb = framebuffers.get(boundFramebuffer);
        if (target != GL_FRAMEBUFFER || fb == null) throw new GdxRuntimeException("gdx-vulkan: glFramebufferTexture2D with no framebuffer bound");
        if (attachment != GL_COLOR_ATTACHMENT0) {
            throw unsupported("glFramebufferTexture2D(attachment 0x" + Integer.toHexString(attachment) + "): one colour attachment is supported; "
                + "use VulkanMultiTargetFrameBuffer for more");
        }
        if (texture != 0 && (textarget != GL_TEXTURE_2D || level != 0)) throw unsupported("glFramebufferTexture2D(textarget/level other than 2D level 0)");
        fb.colorTexture = texture;
        if (texture != 0) makeRenderTarget(textures.get(texture));
        if (boundFramebuffer == fb.handle) emitTarget();
    }

    @Override public void glFramebufferRenderbuffer (int target, int attachment, int renderbuffertarget, int renderbuffer) {
        GlFramebuffer fb = framebuffers.get(boundFramebuffer);
        if (target != GL_FRAMEBUFFER || fb == null) throw new GdxRuntimeException("gdx-vulkan: glFramebufferRenderbuffer with no framebuffer bound");
        switch (attachment) {
        case GL_DEPTH_ATTACHMENT: fb.depthRenderbuffer = renderbuffer; break;
        case GL_STENCIL_ATTACHMENT: fb.stencilRenderbuffer = renderbuffer; break;
        case GL_DEPTH_STENCIL_ATTACHMENT: fb.depthStencilRenderbuffer = renderbuffer; break;
        default: throw unsupported("glFramebufferRenderbuffer(attachment 0x" + Integer.toHexString(attachment) + ")");
        }
        if (boundFramebuffer == fb.handle) emitTarget();
    }

    @Override public int glCheckFramebufferStatus (int target) {
        GlFramebuffer fb = framebuffers.get(boundFramebuffer);
        if (target != GL_FRAMEBUFFER) throw unsupported("glCheckFramebufferStatus(target 0x" + Integer.toHexString(target) + ")");
        if (fb == null) return GL_FRAMEBUFFER_COMPLETE; // the default framebuffer
        if (fb.colorTexture == 0) return GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT;
        GlTexture t = textures.get(fb.colorTexture);
        if (t == null || t.format != GdxVkResources.FORMAT_RGBA8) return GL_FRAMEBUFFER_UNSUPPORTED; // only RGBA8 colour targets
        return GL_FRAMEBUFFER_COMPLETE;
    }

    /** The texture will be rendered into: its native image gets colour-attachment usage and is never renamed. Recreated if it already exists. */
    private void makeRenderTarget (GlTexture t) {
        if (t == null || t.renderTarget) return;
        t.renderTarget = true;
        if (t.nativeId < 0) return;
        // The image exists without attachment usage: replace it (nothing has been rendered into it, so zeroed storage is faithful).
        resources.destroyTexture(t.nativeId);
        t.nativeId = -1;
        t.levelData[0] = new byte[t.width * t.height * 4];
        t.levelW[0] = t.width;
        t.levelH[0] = t.height;
        rebound(t);
    }

    private static final int GL_DEPTH_COMPONENT16_ = 0x81A5, GL_DEPTH_COMPONENT24_ = 0x81A6, GL_DEPTH_COMPONENT32_ = 0x81A7;

    /**
     * The native depth image behind a framebuffer's depth and stencil renderbuffers, created now if it is needed and does not exist. A
     * depth renderbuffer and a stencil renderbuffer on one framebuffer become one combined image, as do a packed one: dynamic rendering
     * takes a single depth-stencil view (plan, Phase 3). -1 if the framebuffer has neither.
     */
    private int depthImageOf (GlFramebuffer fb) {
        GlRenderbuffer depth = renderbuffers.get(fb.depthRenderbuffer), stencil = renderbuffers.get(fb.stencilRenderbuffer),
            packed = renderbuffers.get(fb.depthStencilRenderbuffer);
        if (depth == null && stencil == null && packed == null) {
            if (fb.depthImage >= 0) {
                resources.destroyDepthImage(fb.depthImage);
                fb.depthImage = -1;
                fb.depthSignature = 0;
            }
            return -1;
        }
        GlRenderbuffer sized = packed != null ? packed : depth != null ? depth : stencil;
        boolean wantStencil = stencil != null || packed != null;
        int bits = 16;
        if (depth != null && (depth.format == GL_DEPTH_COMPONENT24_ || depth.format == GL_DEPTH_COMPONENT32_)) bits = 32;
        if (packed != null) bits = 24;
        if (sized.width <= 0 || sized.height <= 0) throw new GdxRuntimeException("gdx-vulkan: a framebuffer's renderbuffer has no storage (glRenderbufferStorage was not called)");
        long signature = ((long)sized.width << 40) | ((long)sized.height << 16) | (wantStencil ? 1L << 8 : 0) | bits;
        if (fb.depthImage < 0 || fb.depthSignature != signature) {
            if (fb.depthImage >= 0) resources.destroyDepthImage(fb.depthImage);
            fb.depthImage = resources.createDepthImage(sized.width, sized.height, wantStencil, bits);
            fb.depthSignature = signature;
        }
        return fb.depthImage;
    }

    /** Puts the bound framebuffer's colour texture (or the swapchain) into the stream as the current target. */
    private void emitTarget () {
        if (stream == null) return;
        if (boundFramebuffer == 0) {
            stream.bindTarget(-1, -1);
            return;
        }
        GlFramebuffer fb = framebuffers.get(boundFramebuffer);
        if (fb == null || fb.colorTexture == 0) return; // still being assembled: the attachment call emits the target
        int id = nativeTexture(fb.colorTexture);
        if (id < 0) throw new GdxRuntimeException("gdx-vulkan: the framebuffer's colour texture has no storage (nothing was uploaded to it)");
        stream.bindTarget(id, depthImageOf(fb));
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
        if (t.renderTarget) {
            if (t.format != GdxVkResources.FORMAT_RGBA8 || levels != 1) {
                throw new GdxRuntimeException("gdx-vulkan: a framebuffer colour texture must be RGBA8 with one level");
            }
            t.nativeId = resources.createTexture(t.width, t.height, GdxVkResources.FORMAT_RENDER_TARGET, 1, false, data, s);
        } else if (t.format == GdxVkResources.FORMAT_COMPRESSED) {
            t.nativeId = resources.createCompressedTexture(t.width, t.height, t.vkFormat, t.blockW, t.blockH, t.blockBytes, levels, data, s);
        } else {
            t.nativeId = resources.createTexture(t.width, t.height, t.format, levels, t.generateMips, data, s);
        }
        t.appliedSampler = s;
        java.util.Arrays.fill(t.levelData, null); // the image owns the pixels now
        return t.nativeId;
    }

    /** The bindless-table slot (Tier A) or 0 (Tier B) of a native texture; it changes whenever the texture is rebound. */
    public int slotOf (int nativeTexture) {
        return resources.slotOf(nativeTexture);
    }

    /** Textures the shim currently knows (GL names, not native images). */
    public int textureCount() {
        return textures.size;
    }
}
