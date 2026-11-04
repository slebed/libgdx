package io.github.slebed.gdxvulkan.glutils;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.Texture.TextureWrap;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.nio.IntBuffer;

/**
 * A framebuffer with several colour attachments, optionally floating-point (plan, Phase 3): what stock {@code FrameBuffer} cannot be without
 * {@code Gdx.gl30}, which gdx-vulkan keeps null. A fragment shader writes the attachments through outputs at locations 0 to n-1; with the sprite
 * batches that is a {@code VulkanShaderPipelineBundle} with {@code withColorOutputs(n)}.
 *
 * <p>Use it like {@code FrameBuffer}: {@link #begin()}, draw, {@link #end()}, then sample {@link #getColorBufferTexture(int)}. It is built from the
 * same GL calls a stock framebuffer makes (textures from {@code GLOnlyTextureData}, a renderbuffer for depth and stencil, {@code glFramebufferTexture2D}
 * for attachments 0 to n-1), so it works with the GL20 shim and nothing else. A pipeline must write exactly as many outputs as the framebuffer has
 * attachments; a clear clears all of them.
 *
 * <p>Floating-point attachments hold values outside 0..1. {@code RGBA32F} may not be filterable on every GPU: its texture is then sampled with
 * nearest filtering whatever the filter says.
 */
public final class VulkanMultiTargetFrameBuffer implements Disposable {
    private static final int GL_FRAMEBUFFER = 0x8D40, GL_RENDERBUFFER = 0x8D41, GL_COLOR_ATTACHMENT0 = 0x8CE0, GL_DEPTH_ATTACHMENT = 0x8D00,
        GL_STENCIL_ATTACHMENT = 0x8D20, GL_DEPTH_STENCIL_ATTACHMENT = 0x821A, GL_FRAMEBUFFER_COMPLETE = 0x8CD5, GL_FRAMEBUFFER_BINDING = 0x8CA6,
        GL_DEPTH_COMPONENT16 = 0x81A5, GL_STENCIL_INDEX8 = 0x8D48, GL_DEPTH24_STENCIL8_OES = 0x88F0;

    /** What one colour attachment holds. */
    public enum ColorFormat {
        /** 8 bits per channel. */
        RGBA8888(GL20.GL_RGBA, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE),
        /** 16-bit floats per channel (HDR). */
        RGBA16F(0x881A, GL20.GL_RGBA, 0x140B),
        /** 32-bit floats per channel. */
        RGBA32F(0x8814, GL20.GL_RGBA, GL20.GL_FLOAT);

        final int internalFormat, format, type;

        ColorFormat (int internalFormat, int format, int type) {
            this.internalFormat = internalFormat;
            this.format = format;
            this.type = type;
        }
    }

    private final int width, height;
    private final Texture[] colors;
    private final int framebufferHandle;
    private final int renderbufferHandle; // 0 = none

    public VulkanMultiTargetFrameBuffer (int width, int height, boolean hasDepth, boolean hasStencil, ColorFormat... formats) {
        if (formats.length < 1 || formats.length > 8) throw new GdxRuntimeException("a multi-target framebuffer has 1 to 8 colour attachments");
        this.width = width;
        this.height = height;
        final GL20 gl = Gdx.gl20;
        colors = new Texture[formats.length];
        for (int i = 0; i < formats.length; i++) {
            ColorFormat f = formats[i];
            Texture t = new Texture(new GLOnlyTextureData(width, height, 0, f.internalFormat, f.format, f.type));
            TextureFilter filter = f == ColorFormat.RGBA32F ? TextureFilter.Nearest : TextureFilter.Linear;
            t.setFilter(filter, filter);
            t.setWrap(TextureWrap.ClampToEdge, TextureWrap.ClampToEdge);
            colors[i] = t;
        }

        int rb = 0;
        if (hasDepth || hasStencil) {
            rb = gl.glGenRenderbuffer();
            gl.glBindRenderbuffer(GL_RENDERBUFFER, rb);
            gl.glRenderbufferStorage(GL_RENDERBUFFER, hasDepth && hasStencil ? GL_DEPTH24_STENCIL8_OES : hasDepth ? GL_DEPTH_COMPONENT16 : GL_STENCIL_INDEX8,
                width, height);
            gl.glBindRenderbuffer(GL_RENDERBUFFER, 0);
        }
        renderbufferHandle = rb;

        IntBuffer previous = com.badlogic.gdx.utils.BufferUtils.newIntBuffer(1);
        gl.glGetIntegerv(GL_FRAMEBUFFER_BINDING, previous);
        framebufferHandle = gl.glGenFramebuffer();
        gl.glBindFramebuffer(GL_FRAMEBUFFER, framebufferHandle);
        for (int i = 0; i < colors.length; i++) {
            gl.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL20.GL_TEXTURE_2D, colors[i].getTextureObjectHandle(), 0);
        }
        if (rb != 0) {
            gl.glFramebufferRenderbuffer(GL_FRAMEBUFFER, hasDepth && hasStencil ? GL_DEPTH_STENCIL_ATTACHMENT : hasDepth ? GL_DEPTH_ATTACHMENT : GL_STENCIL_ATTACHMENT,
                GL_RENDERBUFFER, rb);
        }
        int status = gl.glCheckFramebufferStatus(GL_FRAMEBUFFER);
        gl.glBindFramebuffer(GL_FRAMEBUFFER, previous.get(0));
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            dispose();
            throw new GdxRuntimeException("multi-target framebuffer is not complete: status 0x" + Integer.toHexString(status));
        }
    }

    /** Binds the framebuffer and sets the viewport to its size. */
    public void begin () {
        Gdx.gl20.glBindFramebuffer(GL_FRAMEBUFFER, framebufferHandle);
        Gdx.gl20.glViewport(0, 0, width, height);
    }

    /** Back to the screen, with the viewport restored to the back buffer. */
    public void end () {
        end(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    public void end (int x, int y, int viewportWidth, int viewportHeight) {
        Gdx.gl20.glBindFramebuffer(GL_FRAMEBUFFER, 0);
        Gdx.gl20.glViewport(x, y, viewportWidth, viewportHeight);
    }

    public int getColorBufferCount () {
        return colors.length;
    }

    /** The texture of one colour attachment. Its contents have GL's row order (row 0 at the bottom): draw it with {@code region.flip(false, true)}. */
    public Texture getColorBufferTexture (int index) {
        return colors[index];
    }

    public int getFramebufferHandle () {
        return framebufferHandle;
    }

    public int getWidth () {
        return width;
    }

    public int getHeight () {
        return height;
    }

    @Override public void dispose () {
        final GL20 gl = Gdx.gl20;
        for (Texture t : colors) if (t != null) t.dispose();
        if (renderbufferHandle != 0) gl.glDeleteRenderbuffer(renderbufferHandle);
        if (framebufferHandle != 0) gl.glDeleteFramebuffer(framebufferHandle);
    }
}
