package io.github.slebed.gdxvulkan.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.badlogic.gdx.Application.ApplicationType;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.graphics.glutils.MipMapGenerator;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;

import io.github.slebed.gdxvulkan.GdxVkConfig;
import io.github.slebed.gdxvulkan.GdxVkContext;
import io.github.slebed.gdxvulkan.GdxVkPresenter;
import io.github.slebed.gdxvulkan.GdxVkShaders;
import io.github.slebed.gdxvulkan.VulkanCaps;
import io.github.slebed.gdxvulkan.gl.VulkanGL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lwjgl.glfw.GLFW;

/**
 * Phase 2, texture shim: unmodified stock {@code Texture} and {@code Pixmap} on {@link VulkanGL20}. Each test uploads through the stock
 * classes and reads back exactly what the GPU samples (a probe shader writes one {@code textureLod} sample to the screen, nearest
 * filtering where exact values matter).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TextureShimTest {
    private GdxVkContext ctx;
    private VulkanGL20 gl;
    private GdxVkPresenter presenter;
    private long window;
    private int pipeline;
    private boolean tierA;

    @BeforeAll void setUp() {
        assumeTrue(GLFW.glfwInit(), "GLFW could not initialise (no display)");
        ctx = GdxVkContext.create(GdxVkConfig.fromSystemProperties());
        tierA = ctx.caps().tier == VulkanCaps.Tier.A;
        gl = TestGdx.install(ctx);
        GlfwSurfaces.applyNoApiHints();
        window = GLFW.glfwCreateWindow(64, 64, "gdx-vulkan texture shim", 0, 0);
        assumeTrue(window != 0, "Could not create a window");
        presenter = GlfwSurfaces.createPresenter(ctx, window, false);
        pipeline = presenter.createPipeline("texprobe", GdxVkShaders.load("triangle.vert", tierA), GdxVkShaders.load("texprobe.frag", tierA));
    }

    @AfterAll void tearDown() {
        TestGdx.uninstall();
        MipMapGenerator.setUseHardwareMipMap(true);
        if (presenter != null) presenter.close();
        if (ctx != null) {
            assertEquals(0, ctx.validationMessageCount(), "validation layer reported messages");
            ctx.close();
        }
        if (window != 0) GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    /** Draws one sample of the texture at (u, v, lod) over the whole triangle and returns the pixel as {r, g, b, a} 0..255. */
    private int[] sample(Texture t, float u, float v, float lod) {
        int id = gl.nativeTexture(t.getTextureObjectHandle()); // creates the image now if this is its first use
        assertTrue(id >= 0, "texture has no storage");
        GLFW.glfwPollEvents();
        assertTrue(presenter.beginFrame(0f, 0f, 0f, 1f));
        int slot = presenter.bindTexture(id);
        float[] push = new float[32];
        push[0] = push[5] = push[10] = push[15] = 1f;
        push[21] = Float.intBitsToFloat(slot); // u_indices.y
        push[24] = lod;                         // u_custom0.x
        push[28] = u;                           // u_custom1.xy
        push[29] = v;
        presenter.draw(pipeline, 3, push);
        byte[] px = presenter.endFrameAndCapture();
        int w = presenter.width(), h = presenter.height();
        int i = ((int)(h * 0.55) * w + w / 2) * 4;
        return new int[] {px[i] & 0xFF, px[i + 1] & 0xFF, px[i + 2] & 0xFF, px[i + 3] & 0xFF};
    }

    private int[] texel(Texture t, int x, int y, int level) {
        int w = Math.max(1, t.getWidth() >> level), h = Math.max(1, t.getHeight() >> level);
        return sample(t, (x + 0.5f) / w, (y + 0.5f) / h, level);
    }

    private static void assertRgba(int[] actual, int r, int g, int b, int a, int tol, String what) {
        int[] want = {r, g, b, a};
        for (int c = 0; c < 4; c++) {
            assertTrue(Math.abs(actual[c] - want[c]) <= tol, what + ": channel " + c + " was " + actual[c] + ", expected " + want[c] + " (all: "
                + java.util.Arrays.toString(actual) + ")");
        }
    }

    private static Texture nearest(Texture t) {
        t.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        return t;
    }

    // ------------------------------------------------------------------------------------------------------------- formats

    @Test void rgba8888() {
        Pixmap pm = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) pm.drawPixel(x, y, (x * 60 << 24) | (y * 60 << 16) | ((100 + x + y) << 8) | 255);
        Texture t = nearest(new Texture(pm));
        assertRgba(texel(t, 1, 2, 0), 60, 120, 103, 255, 1, "texel (1,2)");
        assertRgba(texel(t, 3, 0, 0), 180, 0, 103, 255, 1, "texel (3,0)");
        t.dispose();
        pm.dispose();
    }

    @Test void rgb888ExpandsToOpaque() {
        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGB888);
        pm.setColor(0.2f, 0.4f, 0.8f, 1f);
        pm.fill();
        Texture t = nearest(new Texture(pm));
        assertRgba(texel(t, 1, 1, 0), 51, 102, 204, 255, 1, "RGB888");
        t.dispose();
        pm.dispose();
    }

    @Test void alphaIsSampledAsZeroZeroZeroA() {
        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.Alpha);
        ByteBuffer b = pm.getPixels();
        for (int i = 0; i < 4; i++) b.put(i, (byte)(40 * (i + 1))); // 40, 80, 120, 160
        Texture t = nearest(new Texture(pm));
        assertRgba(texel(t, 0, 0, 0), 0, 0, 0, 40, 0, "alpha texel 0");
        assertRgba(texel(t, 1, 1, 0), 0, 0, 0, 160, 0, "alpha texel 3");
        t.dispose();
        pm.dispose();
    }

    @Test void luminanceAlphaIsSampledAsLLLA() {
        Pixmap pm = new Pixmap(2, 1, Pixmap.Format.LuminanceAlpha);
        ByteBuffer b = pm.getPixels();
        b.put(0, (byte)200).put(1, (byte)50).put(2, (byte)10).put(3, (byte)90); // (L,A), (L,A)
        Texture t = nearest(new Texture(pm));
        assertRgba(texel(t, 0, 0, 0), 200, 200, 200, 50, 0, "LA texel 0");
        assertRgba(texel(t, 1, 0, 0), 10, 10, 10, 90, 0, "LA texel 1");
        t.dispose();
        pm.dispose();
    }

    @Test void rgb565AndRgba4444AreExpanded() {
        Pixmap p565 = new Pixmap(1, 1, Pixmap.Format.RGB565);
        p565.getPixels().order(ByteOrder.nativeOrder()).putShort(0, (short)((31 << 11) | (32 << 5) | 0)); // r=31 g=32 b=0
        Texture t565 = nearest(new Texture(p565));
        assertRgba(texel(t565, 0, 0, 0), 255, 130, 0, 255, 1, "565"); // 31 -> 255, 32 -> (32<<2)|(32>>4) = 130
        Pixmap p4444 = new Pixmap(1, 1, Pixmap.Format.RGBA4444);
        p4444.getPixels().order(ByteOrder.nativeOrder()).putShort(0, (short)((0xF << 12) | (0x8 << 8) | (0x3 << 4) | 0xA)); // r=F g=8 b=3 a=A
        Texture t4444 = nearest(new Texture(p4444));
        assertRgba(texel(t4444, 0, 0, 0), 255, 136, 51, 170, 0, "4444");
        t565.dispose();
        t4444.dispose();
        p565.dispose();
        p4444.dispose();
    }

    @Test void unpackAlignmentIsHonoured() {
        // 3x2 RGB with GL_UNPACK_ALIGNMENT 4: each row is 9 bytes of pixels + 3 of padding.
        ByteBuffer src = BufferUtils.newByteBuffer(24);
        for (int i = 0; i < 24; i++) src.put(i, (byte)0xEE); // padding is garbage that must not appear
        int[][] rows = {{10, 20, 30, 40, 50, 60, 70, 80, 90}, {11, 21, 31, 41, 51, 61, 71, 81, 91}};
        for (int y = 0; y < 2; y++) for (int i = 0; i < 9; i++) src.put(y * 12 + i, (byte)rows[y][i]);
        int h = Gdx.gl.glGenTexture();
        Gdx.gl.glBindTexture(GL20.GL_TEXTURE_2D, h);
        Gdx.gl.glPixelStorei(0x0CF5, 4);
        Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGB, 3, 2, 0, GL20.GL_RGB, GL20.GL_UNSIGNED_BYTE, src);
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MIN_FILTER, GL20.GL_NEAREST);
        Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_MAG_FILTER, GL20.GL_NEAREST);
        Gdx.gl.glPixelStorei(0x0CF5, 1);
        int nativeId = gl.nativeTexture(h);
        assertTrue(nativeId >= 0);
        GLFW.glfwPollEvents();
        assertTrue(presenter.beginFrame(0, 0, 0, 1));
        float[] push = new float[32];
        push[0] = push[5] = push[10] = push[15] = 1f;
        push[21] = Float.intBitsToFloat(presenter.bindTexture(nativeId));
        push[28] = 2.5f / 3f; // texel (2, 1)
        push[29] = 1.5f / 2f;
        presenter.draw(pipeline, 3, push);
        byte[] px = presenter.endFrameAndCapture();
        int i = ((int)(presenter.height() * 0.55) * presenter.width() + presenter.width() / 2) * 4;
        assertEquals(71, px[i] & 0xFF);
        assertEquals(81, px[i + 1] & 0xFF);
        assertEquals(91, px[i + 2] & 0xFF);
        Gdx.gl.glDeleteTexture(h);
    }

    // --------------------------------------------------------------------------------------------------------------- mipmaps

    private static Pixmap halves(int size) { // left half red, right half blue
        Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) pm.drawPixel(x, y, x < size / 2 ? 0xFF0000FF : 0x0000FFFF);
        return pm;
    }

    @Test void hardwareGeneratedMipmaps() {
        MipMapGenerator.setUseHardwareMipMap(true);
        Pixmap pm = halves(4);
        Texture t = new Texture(pm, true); // glTexImage2D(level 0) + glGenerateMipmap, the desktop path
        t.setFilter(TextureFilter.MipMapNearestNearest, TextureFilter.Nearest);
        assertRgba(texel(t, 0, 0, 0), 255, 0, 0, 255, 1, "level 0 red");
        assertRgba(texel(t, 1, 0, 1), 0, 0, 255, 255, 1, "level 1 right texel is blue");
        assertRgba(texel(t, 0, 0, 2), 128, 0, 128, 255, 2, "level 2 is the average");
        assertEquals(3, ctx.resources().textureInfo(gl.nativeTexture(t.getTextureObjectHandle()))[2], "4x4 has a three level chain");
        t.dispose();
        pm.dispose();
    }

    @Test void cpuGeneratedMipmapsUploadLevelsAboveZero() {
        MipMapGenerator.setUseHardwareMipMap(false); // glTexImage2D once per level
        try {
            Pixmap pm = halves(4);
            Texture t = new Texture(pm, true);
            t.setFilter(TextureFilter.MipMapNearestNearest, TextureFilter.Nearest);
            assertEquals(3, ctx.resources().textureInfo(gl.nativeTexture(t.getTextureObjectHandle()))[2]);
            // The oracle is stock libGDX's own downscale (MipMapGenerator.generateMipMapCPU does exactly this), so the shim must store
            // precisely those bytes: it does no filtering of its own on this path.
            Pixmap l1 = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
            l1.setBlending(Pixmap.Blending.None);
            l1.drawPixmap(pm, 0, 0, 4, 4, 0, 0, 2, 2);
            Pixmap l2 = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            l2.setBlending(Pixmap.Blending.None);
            l2.drawPixmap(l1, 0, 0, 2, 2, 0, 0, 1, 1);
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    int c = l1.getPixel(x, y);
                    assertRgba(texel(t, x, y, 1), c >>> 24, (c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, 0, "level 1 texel " + x + "," + y);
                }
            }
            int c2 = l2.getPixel(0, 0);
            assertRgba(texel(t, 0, 0, 2), c2 >>> 24, (c2 >> 16) & 0xFF, (c2 >> 8) & 0xFF, c2 & 0xFF, 0, "level 2");
            l1.dispose();
            l2.dispose();
            t.dispose();
            pm.dispose();
        } finally {
            MipMapGenerator.setUseHardwareMipMap(true);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- updates

    @Test void texSubImageRenamesAndKeepsTheRest() {
        Pixmap base = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
        base.setColor(1f, 0f, 0f, 1f);
        base.fill();
        Texture t = nearest(new Texture(base));
        assertRgba(texel(t, 1, 1, 0), 255, 0, 0, 255, 0, "before");

        Pixmap patch = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        patch.setColor(0f, 1f, 0f, 1f);
        patch.fill();
        t.draw(patch, 1, 1); // glTexSubImage2D after the image exists: copy-on-write rename
        assertRgba(texel(t, 1, 1, 0), 0, 255, 0, 255, 0, "patched texel");
        assertRgba(texel(t, 2, 2, 0), 0, 255, 0, 255, 0, "patched texel");
        assertRgba(texel(t, 0, 0, 0), 255, 0, 0, 255, 0, "untouched texel survives the rename");
        assertRgba(texel(t, 3, 3, 0), 255, 0, 0, 255, 0, "untouched texel survives the rename");

        // several updates in one frame's worth of work, then a draw: the last one wins
        for (int i = 0; i < 5; i++) {
            patch.setColor(0f, 0f, i / 4f, 1f);
            patch.fill();
            t.draw(patch, 0, 0);
        }
        assertRgba(texel(t, 0, 0, 0), 0, 0, 255, 255, 0, "last update wins");
        t.dispose();
        base.dispose();
        patch.dispose();
    }

    @Test void updatedBeforeFirstUseNeedsNoRename() {
        Pixmap base = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
        base.setColor(1f, 0f, 0f, 1f);
        base.fill();
        Texture t = nearest(new Texture(base)); // no draw yet, so the image does not exist
        Pixmap patch = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        patch.setColor(0f, 1f, 0f, 1f);
        patch.fill();
        t.draw(patch, 2, 3);
        assertRgba(texel(t, 2, 3, 0), 0, 255, 0, 255, 0, "patched before creation");
        assertRgba(texel(t, 2, 2, 0), 255, 0, 0, 255, 0, "neighbour");
        t.dispose();
        base.dispose();
        patch.dispose();
    }

    @Test void reloadingWithAnotherSizeRedefinesTheTexture() {
        Pixmap small = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        small.setColor(1f, 0f, 0f, 1f);
        small.fill();
        Texture t = nearest(new Texture(small));
        assertRgba(texel(t, 0, 0, 0), 255, 0, 0, 255, 0, "2x2");
        Pixmap big = new Pixmap(8, 8, Pixmap.Format.RGBA8888);
        big.setColor(0f, 0f, 1f, 1f);
        big.fill();
        t.load(new com.badlogic.gdx.graphics.glutils.PixmapTextureData(big, null, false, false));
        assertEquals(8, ctx.resources().textureInfo(gl.nativeTexture(t.getTextureObjectHandle()))[0], "storage was redefined");
        assertRgba(texel(t, 7, 7, 0), 0, 0, 255, 255, 0, "8x8");
        t.dispose();
        small.dispose();
        big.dispose();
    }

    // --------------------------------------------------------------------------------------------------------- sampler state

    @Test void filterAndWrapChangesAfterCreation() {
        Pixmap pm = new Pixmap(2, 1, Pixmap.Format.RGBA8888);
        pm.drawPixel(0, 0, 0xFF0000FF); // red | blue
        pm.drawPixel(1, 0, 0x0000FFFF);
        Texture t = new Texture(pm);
        t.setFilter(TextureFilter.Linear, TextureFilter.Linear);
        t.setWrap(TextureWrap.ClampToEdge, TextureWrap.ClampToEdge);
        int[] blend = sample(t, 0.5f, 0.5f, 0f);
        assertRgba(blend, 128, 0, 128, 255, 2, "linear between the two texels");

        long samplersBefore = ctx.resources().stats().samplers;
        t.setFilter(TextureFilter.Nearest, TextureFilter.Nearest); // sampler change after creation: new binding, old retired
        int[] hard = sample(t, 0.3f, 0.5f, 0f);
        assertRgba(hard, 255, 0, 0, 255, 0, "nearest picks the texel");

        t.setWrap(TextureWrap.Repeat, TextureWrap.Repeat);
        assertRgba(sample(t, 1.25f, 0.5f, 0f), 255, 0, 0, 255, 0, "repeat wraps 1.25 back to the left texel");
        t.setWrap(TextureWrap.ClampToEdge, TextureWrap.ClampToEdge);
        assertRgba(sample(t, 1.25f, 0.5f, 0f), 0, 0, 255, 255, 0, "clamp holds the right texel");
        t.setWrap(TextureWrap.MirroredRepeat, TextureWrap.MirroredRepeat);
        assertRgba(sample(t, 1.25f, 0.5f, 0f), 0, 0, 255, 255, 0, "mirror reflects 1.25 to 0.75, the right texel");

        for (int i = 0; i < 50; i++) { // flipping back and forth must not create samplers without bound
            t.setFilter(i % 2 == 0 ? TextureFilter.Linear : TextureFilter.Nearest, TextureFilter.Nearest);
            sample(t, 0.5f, 0.5f, 0f);
        }
        assertTrue(ctx.resources().stats().samplers <= samplersBefore + 6, "sampler count stays bounded: " + ctx.resources().stats().samplers);
        t.dispose();
        pm.dispose();
    }

    // ------------------------------------------------------------------------------------------------------ lifetime & queries

    @Test void disposeReleasesTheNativeTexture() {
        int before = ctx.resources().stats().texturesLive;
        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        Texture t = nearest(new Texture(pm));
        sample(t, 0.25f, 0.25f, 0f);
        assertEquals(before + 1, ctx.resources().stats().texturesLive);
        t.dispose();
        assertEquals(before, ctx.resources().stats().texturesLive);
        pm.dispose();
    }

    // ------------------------------------------------------------------------------------------------------------- compressed

    private static final int GL_DXT1_RGBA = 0x83F1, GL_ETC2_RGB = 0x9274, GL_ASTC_4x4 = 0x93B0;
    private static final int BC1_RGBA_UNORM = 131;

    /** One BC1 block that decodes to a single colour: colour0 = rgb565, colour1 = 0, every index 0. */
    private static void bc1Solid(ByteBuffer out, int rgb565) {
        out.put((byte)rgb565).put((byte)(rgb565 >> 8)).putShort((short)0).putInt(0);
    }

    /** A custom TextureData that uploads the given BC1 levels (8x8 quadrants red, green, blue, white; level 1 is one white block). */
    private static com.badlogic.gdx.graphics.TextureData bc1Data() {
        return new com.badlogic.gdx.graphics.TextureData() {
            public TextureDataType getType() { return TextureDataType.Custom; }
            public boolean isPrepared() { return true; }
            public void prepare() {}
            public Pixmap consumePixmap() { throw new GdxRuntimeException("custom"); }
            public boolean disposePixmap() { return false; }
            public void consumeCustomData(int target) {
                ByteBuffer l0 = BufferUtils.newByteBuffer(32).order(ByteOrder.LITTLE_ENDIAN);
                bc1Solid(l0, 0xF800); // top-left red
                bc1Solid(l0, 0x07E0); // top-right green
                bc1Solid(l0, 0x001F); // bottom-left blue
                bc1Solid(l0, 0xFFFF); // bottom-right white
                l0.flip();
                Gdx.gl.glCompressedTexImage2D(target, 0, GL_DXT1_RGBA, 8, 8, 0, 32, l0);
            }
            public int getWidth() { return 8; }
            public int getHeight() { return 8; }
            public Pixmap.Format getFormat() { return Pixmap.Format.RGBA8888; }
            public boolean useMipMaps() { return false; }
            public boolean isManaged() { return true; }
        };
    }

    @Test void bc1CompressedTexturesAreSampledAndUpdatable() {
        assumeTrue(ctx.resources().formatSupported(BC1_RGBA_UNORM), "device cannot sample BC1");
        Texture t = nearest(new Texture(bc1Data()));
        assertRgba(texel(t, 1, 1, 0), 255, 0, 0, 255, 0, "red block");
        assertRgba(texel(t, 5, 1, 0), 0, 255, 0, 255, 0, "green block");
        assertRgba(texel(t, 1, 5, 0), 0, 0, 255, 255, 0, "blue block");
        assertRgba(texel(t, 6, 6, 0), 255, 255, 255, 255, 0, "white block");

        t.bind();
        ByteBuffer patch = BufferUtils.newByteBuffer(8).order(ByteOrder.LITTLE_ENDIAN);
        bc1Solid(patch, 0xFFE0); // yellow
        patch.flip();
        gl.glCompressedTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 4, 4, 4, 4, GL_DXT1_RGBA, 8, patch); // block-aligned update: renames
        assertRgba(texel(t, 5, 5, 0), 255, 255, 0, 255, 0, "patched block");
        assertRgba(texel(t, 1, 1, 0), 255, 0, 0, 255, 0, "untouched block survives the rename");

        ByteBuffer off = BufferUtils.newByteBuffer(8).order(ByteOrder.LITTLE_ENDIAN);
        bc1Solid(off, 0xFFE0);
        off.flip();
        assertThrows(IllegalArgumentException.class, () -> gl.glCompressedTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 1, 1, 4, 4, GL_DXT1_RGBA, 8, off),
            "updates not aligned to the block grid are refused");
        t.dispose();
    }

    @Test void compressedFormatTheDeviceCannotSampleIsRefusedClearly() {
        // Desktop GPUs have BC but not ETC2/ASTC and mobile GPUs the reverse: find a format this device lacks, if any.
        int[][] candidates = {{GL_ETC2_RGB, 147}, {GL_ASTC_4x4, 157}, {GL_DXT1_RGBA, BC1_RGBA_UNORM}};
        for (int[] c : candidates) {
            if (ctx.resources().formatSupported(c[1])) continue;
            Texture t = new Texture(1, 1, Pixmap.Format.RGBA8888);
            t.bind();
            ByteBuffer data = BufferUtils.newByteBuffer(16);
            GdxRuntimeException e = assertThrows(GdxRuntimeException.class,
                () -> gl.glCompressedTexImage2D(GL20.GL_TEXTURE_2D, 0, c[0], 4, 4, 0, 16, data));
            assertTrue(e.getMessage().contains("cannot sample"), e.getMessage());
            t.dispose();
            return;
        }
        assumeTrue(false, "this device samples BC, ETC2 and ASTC");
    }

    @Test void unknownCompressedFormatIsRefused() {
        Texture t = new Texture(1, 1, Pixmap.Format.RGBA8888);
        t.bind();
        assertThrows(GdxRuntimeException.class, () -> gl.glCompressedTexImage2D(GL20.GL_TEXTURE_2D, 0, 0x1234, 4, 4, 0, 8, BufferUtils.newByteBuffer(8)));
        t.dispose();
    }

    @Test void queryTableAndVersionString() {
        assertTrue(Gdx.graphics.supportsExtension("GL_ARB_framebuffer_object"), "MipMapGenerator needs glGenerateMipmap");
        assertTrue(Gdx.graphics.supportsExtension("GL_OES_framebuffer_object"));
        assertTrue(Gdx.graphics.supportsExtension("GL_OES_packed_depth_stencil"));
        assertTrue(Gdx.graphics.supportsExtension("GL_EXT_packed_depth_stencil"));
        assertEquals(ctx.caps().maxSamplerAnisotropy > 1f, Gdx.graphics.supportsExtension("GL_EXT_texture_filter_anisotropic"));
        assertTrue(!Gdx.graphics.supportsExtension("GL_SOMETHING_ELSE"));

        String version = Gdx.gl.glGetString(GL20.GL_VERSION);
        assertTrue(version.startsWith("OpenGL ES 2.0 gdx-vulkan"), version);
        // GLVersion takes its type from the application type and reads the number from the string, under either type.
        GLVersion asAndroid = new GLVersion(ApplicationType.Android, version, Gdx.gl.glGetString(GL20.GL_VENDOR), Gdx.gl.glGetString(GL20.GL_RENDERER));
        assertEquals(GLVersion.Type.GLES, asAndroid.getType());
        assertEquals(2, asAndroid.getMajorVersion());
        assertEquals(0, asAndroid.getMinorVersion());
        GLVersion asDesktop = new GLVersion(ApplicationType.Desktop, version, Gdx.gl.glGetString(GL20.GL_VENDOR), Gdx.gl.glGetString(GL20.GL_RENDERER));
        assertEquals(2, asDesktop.getMajorVersion());
        assertEquals(0, asDesktop.getMinorVersion());

        java.nio.IntBuffer ib = BufferUtils.newIntBuffer(16);
        Gdx.gl.glGetIntegerv(GL20.GL_MAX_TEXTURE_SIZE, ib);
        assertEquals(ctx.caps().maxImageDimension2D, ib.get(0));
        Gdx.gl.glViewport(3, 4, 50, 60);
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, ib);
        assertEquals(3, ib.get(0));
        assertEquals(60, ib.get(3));
        assertEquals(0, Gdx.gl.glGetError());

        float max = Texture.getMaxAnisotropicFilterLevel();
        assertEquals(Math.max(1f, ctx.caps().maxSamplerAnisotropy), Math.max(1f, max));
        Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        Texture t = nearest(new Texture(pm));
        float applied = t.setAnisotropicFilter(4f);
        assertEquals(Math.min(4f, Math.max(1f, max)), Math.max(1f, applied), 0.01f);
        sample(t, 0.25f, 0.25f, 0f); // creating the image with the anisotropic sampler must not fail validation
        t.dispose();
        pm.dispose();
    }

    @Test void drawingIsRefusedWithAMessageThatNamesTheCall() {
        GdxRuntimeException e = assertThrows(GdxRuntimeException.class, () -> Gdx.gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 3));
        assertTrue(e.getMessage().contains("glDrawArrays") && e.getMessage().contains("Vulkan batches"), e.getMessage());
        assertThrows(GdxRuntimeException.class, () -> Gdx.gl.glCreateProgram());
        // pure state is accepted
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glClearColor(0, 0, 0, 1);
    }
}
