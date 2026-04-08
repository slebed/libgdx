package com.badlogic.gdx.backend.vulkan; // Or your chosen package

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.GdxRuntimeException;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;

public class VulkanGL20Impl implements GL20 {

    private final boolean debug = false;
    private final VulkanGraphics graphics; // To access VulkanGraphics' state/helpers
    private int activeTextureUnit = 0;
    private final int[] boundTextureHandles = new int[32];

    public VulkanGL20Impl(VulkanGraphics graphics) {
        if (graphics == null) {
            // Use Gdx.app.error if Gdx.app is already initialized, otherwise System.err
            String errorMsg = "VulkanGraphics argument cannot be null for VulkanGL20Impl constructor.";
            if (Gdx.app != null && debug) Gdx.app.error("VulkanGL20Impl", errorMsg);
            else System.err.println("VulkanGL20Impl: " + errorMsg);
            throw new IllegalArgumentException(errorMsg);
        }
        this.graphics = graphics;
        if (Gdx.app != null && debug) Gdx.app.log("VulkanGL20Impl", "Instance created, linked with VulkanGraphics: " + graphics.hashCode());
        else System.out.println("VulkanGL20Impl: Instance created, linked with VulkanGraphics: " + graphics.hashCode());
    }

    @Override
    public void glScissor(int gdxScissorX, int gdxScissorY, int gdxScissorWidth, int gdxScissorHeight) {
        // Always log, even if Gdx.app isn't fully up, for early debugging.
        //System.out.println("VulkanGL20Impl: glScissor GdxCoords(x=" + gdxScissorX + ", y=" + gdxScissorY + ", w=" + gdxScissorWidth + ", h=" + gdxScissorHeight + ")");

        VkCommandBuffer currentCB = graphics.getCurrentVkCommandBuffer(); // Method to add in VulkanGraphics
        if (currentCB == null) {
            String errorMsg = "Current command buffer is NULL! Cannot set scissor.";
            if (Gdx.app != null && debug) Gdx.app.error("VulkanGL20Impl.glScissor", errorMsg);
            else System.err.println("VulkanGL20Impl.glScissor: " + errorMsg);
            return;
        }

        int framebufferHeight = graphics.getBackBufferHeight(); // From VulkanGraphics
        int framebufferWidth = graphics.getBackBufferWidth();   // From VulkanGraphics

        // --- Convert LibGDX (bottom-left origin) to Vulkan (top-left origin) scissor ---
        int vkScissorX = gdxScissorX;
        int vkScissorY = framebufferHeight - (gdxScissorY + gdxScissorHeight); // Y-flip
        int vkScissorWidth = gdxScissorWidth;
        int vkScissorHeight = gdxScissorHeight;

        // --- Clamp values to be safe for Vulkan ---
        vkScissorX = Math.max(0, vkScissorX);
        vkScissorY = Math.max(0, vkScissorY);

        if (vkScissorX >= framebufferWidth || vkScissorY >= framebufferHeight || vkScissorWidth <= 0 || vkScissorHeight <= 0) {
            // Scissor is fully outside or has no area after initial check
            vkScissorWidth = 0;
            vkScissorHeight = 0;
        } else {
            // Clamp width: make sure x + width doesn't exceed framebufferWidth
            vkScissorWidth = Math.min(vkScissorWidth, framebufferWidth - vkScissorX);
            // Clamp height: make sure y + height doesn't exceed framebufferHeight
            vkScissorHeight = Math.min(vkScissorHeight, framebufferHeight - vkScissorY);
        }
        // Ensure non-negative after all clamping
        vkScissorWidth = Math.max(0, vkScissorWidth);
        vkScissorHeight = Math.max(0, vkScissorHeight);


        //String logMsg = "Applying VkCmdSetScissor with VulkanCoords(x=" + vkScissorX + ", y=" + vkScissorY + ", w=" + vkScissorWidth + ", h=" + vkScissorHeight + ")";
        //if (Gdx.app != null && debug) Gdx.app.log("VulkanGL20Impl.glScissor", logMsg);
        //else System.out.println("VulkanGL20Impl.glScissor: " + logMsg);


        if (vkScissorWidth <= 0 || vkScissorHeight <= 0) {
            //String warnMsg = "Calculated Vulkan scissor has zero or negative extent. Content will be clipped.";
            //if (Gdx.app != null) Gdx.app.warn("VulkanGL20Impl.glScissor", warnMsg);
            //else System.err.println("VulkanGL20Impl.glScissor: WARNING - " + warnMsg);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            //VkRect2D vkScissorRect = VkRect2D.calloc(stack);
            int finalVkScissorX = vkScissorX;
            int finalVkScissorY = vkScissorY;
            //vkScissorRect.offset(it -> it.x(finalVkScissorX).y(finalVkScissorY));
            int finalVkScissorWidth = vkScissorWidth;
            int finalVkScissorHeight = vkScissorHeight;
            //vkScissorRect.extent(it -> it.width(finalVkScissorWidth).height(finalVkScissorHeight));
            VkRect2D.Buffer vkScissorRectBuffer = VkRect2D.calloc(1, stack);

            // Get the single VkRect2D struct within the buffer and populate it
            vkScissorRectBuffer.get(0)
                    .offset(it -> it.x(finalVkScissorX).y(finalVkScissorY))
                    .extent(it -> it.width(finalVkScissorWidth).height(finalVkScissorHeight));
            VK10.vkCmdSetScissor(currentCB, 0, vkScissorRectBuffer);
        }
    }

    @Override
    public void glStencilFunc(int func, int ref, int mask) {
        warnNotImplemented("glStencilFunc");
    }

    @Override
    public void glStencilMask(int mask) {
        warnNotImplemented("glStencilMask");
    }

    @Override
    public void glStencilOp(int fail, int zfail, int zpass) {
        warnNotImplemented("glStencilOp");
    }

    @Override
    public void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, Buffer pixels) {
        warnNotImplemented("glTexImage2D");
    }

    @Override
    public void glTexParameterf(int target, int pname, float param) {
        // Texture parameters are handled by VulkanTexture.setFilter/setWrap directly.
    }

    @Override
    public void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, Buffer pixels) {
        warnNotImplemented("glTexSubImage2D");
    }

    @Override
    public void glViewport(int x, int y, int width, int height) {
        String msg = "glViewport called: x=" + x + ", y=" + y + ", w=" + width + ", h=" + height;
        //if (Gdx.app != null && debug) Gdx.app.log("VulkanGL20Impl", msg);
        //else System.out.println("VulkanGL20Impl: " + msg);
        // TODO: Implement vkCmdSetViewport if this method should control viewport for FBOs etc.
        // Be careful not to conflict with VulkanWindow.updateDynamicStates() for the main swapchain viewport.
    }

    @Override
    public void glAttachShader(int program, int shader) {
        warnNotImplemented("glAttachShader");
    }

    @Override
    public void glBindAttribLocation(int program, int index, String name) {
        warnNotImplemented("glBindAttribLocation");
    }

    @Override
    public void glBindBuffer(int target, int buffer) {
        warnNotImplemented("glBindBuffer");
    }

    @Override
    public void glBindFramebuffer(int target, int framebuffer) {
        warnNotImplemented("glBindFramebuffer");
    }

    @Override
    public void glBindRenderbuffer(int target, int renderbuffer) {
        warnNotImplemented("glBindRenderbuffer");
    }

    @Override
    public void glBlendColor(float red, float green, float blue, float alpha) {
        warnNotImplemented("glBlendColor");
    }

    @Override
    public void glBlendEquation(int mode) {
        warnNotImplemented("glBlendEquation");
    }

    @Override
    public void glBlendEquationSeparate(int modeRGB, int modeAlpha) {
        warnNotImplemented("glBlendEquationSeparate");
    }

    @Override
    public void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        // Blend state is baked into Vulkan pipelines. VulkanSpriteBatch handles blend
        // function parameters directly when selecting/creating pipeline variants.
    }

    @Override
    public void glBufferData(int target, int size, Buffer data, int usage) {
        warnNotImplemented("glBufferData");
    }

    @Override
    public void glBufferSubData(int target, int offset, int size, Buffer data) {
        warnNotImplemented("glBufferSubData");
    }

    @Override
    public int glCheckFramebufferStatus(int target) {
        warnNotImplemented("glCheckFramebufferStatus");
        return 0;
    }

    @Override
    public void glCompileShader(int shader) {
        warnNotImplemented("glCompileShader");
    }

    @Override
    public int glCreateProgram() {
        warnNotImplemented("glCreateProgram");
        return 0;
    }

    @Override
    public int glCreateShader(int type) {
        warnNotImplemented("glCreateShader");
        return 0;
    }

    @Override
    public void glDeleteBuffer(int buffer) {
        warnNotImplemented("glDeleteBuffer");
    }

    @Override
    public void glDeleteBuffers(int n, IntBuffer buffers) {
        warnNotImplemented("glDeleteBuffers");
    }

    @Override
    public void glDeleteFramebuffer(int framebuffer) {
        warnNotImplemented("glDeleteFramebuffer");
    }

    @Override
    public void glDeleteFramebuffers(int n, IntBuffer framebuffers) {
        warnNotImplemented("glDeleteFramebuffers");
    }

    @Override
    public void glDeleteProgram(int program) {
        warnNotImplemented("glDeleteProgram");
    }

    @Override
    public void glDeleteRenderbuffer(int renderbuffer) {
        warnNotImplemented("glDeleteRenderbuffer");
    }

    @Override
    public void glDeleteRenderbuffers(int n, IntBuffer renderbuffers) {
        warnNotImplemented("glDeleteRenderbuffers");
    }

    @Override
    public void glDeleteShader(int shader) {
        warnNotImplemented("glDeleteShader");
    }

    @Override
    public void glDetachShader(int program, int shader) {
        warnNotImplemented("glDetachShader");
    }

    @Override
    public void glDisableVertexAttribArray(int index) {
        warnNotImplemented("glDisableVertexAttribArray");
    }

    @Override
    public void glDrawElements(int mode, int count, int type, int indices) {
        warnNotImplemented("glDrawElements");
    }

    @Override
    public void glEnableVertexAttribArray(int index) {
        warnNotImplemented("glEnableVertexAttribArray");
    }

    @Override
    public void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        warnNotImplemented("glFramebufferRenderbuffer");
    }

    @Override
    public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        warnNotImplemented("glFramebufferTexture2D");
    }

    @Override
    public int glGenBuffer() {
        warnNotImplemented("glGenBuffer");
        return 0;
    }

    @Override
    public void glGenBuffers(int n, IntBuffer buffers) {
        warnNotImplemented("glGenBuffers");
    }

    @Override
    public void glGenerateMipmap(int target) {
        warnNotImplemented("glGenerateMipmap");
    }

    @Override
    public int glGenFramebuffer() {
        warnNotImplemented("glGenFramebuffer");
        return 0;
    }

    @Override
    public void glGenFramebuffers(int n, IntBuffer framebuffers) {
        warnNotImplemented("glGenFramebuffers");
    }

    @Override
    public int glGenRenderbuffer() {
        warnNotImplemented("glGenRenderbuffer");
        return 0;
    }

    @Override
    public void glGenRenderbuffers(int n, IntBuffer renderbuffers) {
        warnNotImplemented("glGenRenderbuffers");
    }

    @Override
    public String glGetActiveAttrib(int program, int index, IntBuffer size, IntBuffer type) {
        warnNotImplemented("glGetActiveAttrib");
        return "";
    }

    @Override
    public String glGetActiveUniform(int program, int index, IntBuffer size, IntBuffer type) {
        warnNotImplemented("glGetActiveUniform");
        return "";
    }

    @Override
    public void glGetAttachedShaders(int program, int maxcount, Buffer count, IntBuffer shaders) {
        warnNotImplemented("glGetAttachedShaders");
    }

    @Override
    public int glGetAttribLocation(int program, String name) {
        warnNotImplemented("glGetAttribLocation");
        return 0;
    }

    @Override
    public void glGetBooleanv(int pname, Buffer params) {
        warnNotImplemented("glGetBooleanv");
    }

    @Override
    public void glGetBufferParameteriv(int target, int pname, IntBuffer params) {
        warnNotImplemented("glGetBufferParameteriv");
    }

    @Override
    public void glGetFloatv(int pname, FloatBuffer params) {
        warnNotImplemented("glGetFloatv");
    }

    @Override
    public void glGetFramebufferAttachmentParameteriv(int target, int attachment, int pname, IntBuffer params) {
        warnNotImplemented("glGetFramebufferAttachmentParameteriv");
    }

    @Override
    public void glGetProgramiv(int program, int pname, IntBuffer params) {
        warnNotImplemented("glGetProgramiv");
    }

    @Override
    public String glGetProgramInfoLog(int program) {
        warnNotImplemented("glGetProgramInfoLog");
        return "";
    }

    @Override
    public void glGetRenderbufferParameteriv(int target, int pname, IntBuffer params) {
        warnNotImplemented("glGetRenderbufferParameteriv");
    }

    @Override
    public void glGetShaderiv(int shader, int pname, IntBuffer params) {
        warnNotImplemented("glGetShaderiv");
    }

    @Override
    public String glGetShaderInfoLog(int shader) {
        warnNotImplemented("glGetShaderInfoLog");
        return "";
    }

    @Override
    public void glGetShaderPrecisionFormat(int shadertype, int precisiontype, IntBuffer range, IntBuffer precision) {
        warnNotImplemented("glGetShaderPrecisionFormat");
    }

    @Override
    public void glGetTexParameterfv(int target, int pname, FloatBuffer params) {
        warnNotImplemented("glGetTexParameterfv");
    }

    @Override
    public void glGetTexParameteriv(int target, int pname, IntBuffer params) {
        warnNotImplemented("glGetTexParameteriv");
    }

    @Override
    public void glGetUniformfv(int program, int location, FloatBuffer params) {
        warnNotImplemented("glGetUniformfv");
    }

    @Override
    public void glGetUniformiv(int program, int location, IntBuffer params) {
        warnNotImplemented("glGetUniformiv");
    }

    @Override
    public int glGetUniformLocation(int program, String name) {
        warnNotImplemented("glGetUniformLocation");
        return 0;
    }

    @Override
    public void glGetVertexAttribfv(int index, int pname, FloatBuffer params) {
        warnNotImplemented("glGetVertexAttribfv");
    }

    @Override
    public void glGetVertexAttribiv(int index, int pname, IntBuffer params) {
        warnNotImplemented("glGetVertexAttribiv");
    }

    @Override
    public void glGetVertexAttribPointerv(int index, int pname, Buffer pointer) {
        warnNotImplemented("glGetVertexAttribPointerv");
    }

    @Override
    public boolean glIsBuffer(int buffer) {
        warnNotImplemented("glIsBuffer");
        return false;
    }

    @Override
    public boolean glIsEnabled(int cap) {
        warnNotImplemented("glIsEnabled");
        return false;
    }

    @Override
    public boolean glIsFramebuffer(int framebuffer) {
        warnNotImplemented("glIsFramebuffer");
        return false;
    }

    @Override
    public boolean glIsProgram(int program) {
        warnNotImplemented("glIsProgram");
        return false;
    }

    @Override
    public boolean glIsRenderbuffer(int renderbuffer) {
        warnNotImplemented("glIsRenderbuffer");
        return false;
    }

    @Override
    public boolean glIsShader(int shader) {
        warnNotImplemented("glIsShader");
        return false;
    }

    @Override
    public boolean glIsTexture(int texture) {
        warnNotImplemented("glIsTexture");
        return false;
    }

    @Override
    public void glLinkProgram(int program) {
        warnNotImplemented("glLinkProgram");
    }

    @Override
    public void glReleaseShaderCompiler() {
        warnNotImplemented("glReleaseShaderCompiler");
    }

    @Override
    public void glRenderbufferStorage(int target, int internalformat, int width, int height) {
        warnNotImplemented("glRenderbufferStorage");
    }

    @Override
    public void glSampleCoverage(float value, boolean invert) {
        warnNotImplemented("glSampleCoverage");
    }

    @Override
    public void glShaderBinary(int n, IntBuffer shaders, int binaryformat, Buffer binary, int length) {
        warnNotImplemented("glShaderBinary");
    }

    @Override
    public void glShaderSource(int shader, String string) {
        warnNotImplemented("glShaderSource");
    }

    @Override
    public void glStencilFuncSeparate(int face, int func, int ref, int mask) {
        warnNotImplemented("glStencilFuncSeparate");
    }

    @Override
    public void glStencilMaskSeparate(int face, int mask) {
        warnNotImplemented("glStencilMaskSeparate");
    }

    @Override
    public void glStencilOpSeparate(int face, int fail, int zfail, int zpass) {
        warnNotImplemented("glStencilOpSeparate");
    }

    @Override
    public void glTexParameterfv(int target, int pname, FloatBuffer params) {
        warnNotImplemented("glTexParameterfv");
    }

    @Override
    public void glTexParameteri(int target, int pname, int param) {
        // Texture filter/wrap parameters are handled by VulkanTexture.setFilter/setWrap
        // which bypass GL20 and recreate the Vulkan sampler directly.
    }

    @Override
    public void glTexParameteriv(int target, int pname, IntBuffer params) {
        warnNotImplemented("glTexParameteriv");
    }

    @Override
    public void glUniform1f(int location, float x) {
        warnNotImplemented("glUniform1f");
    }

    @Override
    public void glUniform1fv(int location, int count, FloatBuffer v) {
        warnNotImplemented("glUniform1fv");
    }

    @Override
    public void glUniform1fv(int location, int count, float[] v, int offset) {
        warnNotImplemented("glUniform1fv");
    }

    @Override
    public void glUniform1i(int location, int x) {
        warnNotImplemented("glUniform1i");
    }

    @Override
    public void glUniform1iv(int location, int count, IntBuffer v) {
        warnNotImplemented("glUniform1iv");
    }

    @Override
    public void glUniform1iv(int location, int count, int[] v, int offset) {
        warnNotImplemented("glUniform1iv");
    }

    @Override
    public void glUniform2f(int location, float x, float y) {
        warnNotImplemented("glUniform2f");
    }

    @Override
    public void glUniform2fv(int location, int count, FloatBuffer v) {
        warnNotImplemented("glUniform2fv");
    }

    @Override
    public void glUniform2fv(int location, int count, float[] v, int offset) {
        warnNotImplemented("glUniform2fv");
    }

    @Override
    public void glUniform2i(int location, int x, int y) {
        warnNotImplemented("glUniform2i");
    }

    @Override
    public void glUniform2iv(int location, int count, IntBuffer v) {
        warnNotImplemented("glUniform2iv");
    }

    @Override
    public void glUniform2iv(int location, int count, int[] v, int offset) {
        warnNotImplemented("glUniform2iv");
    }

    @Override
    public void glUniform3f(int location, float x, float y, float z) {
        warnNotImplemented("glUniform3f");
    }

    @Override
    public void glUniform3fv(int location, int count, FloatBuffer v) {
        warnNotImplemented("glUniform3fv");
    }

    @Override
    public void glUniform3fv(int location, int count, float[] v, int offset) {
        warnNotImplemented("glUniform3fv");
    }

    @Override
    public void glUniform3i(int location, int x, int y, int z) {
        warnNotImplemented("glUniform3i");
    }

    @Override
    public void glUniform3iv(int location, int count, IntBuffer v) {
        warnNotImplemented("glUniform3iv");
    }

    @Override
    public void glUniform3iv(int location, int count, int[] v, int offset) {
        warnNotImplemented("glUniform3iv");
    }

    @Override
    public void glUniform4f(int location, float x, float y, float z, float w) {
        warnNotImplemented("glUniform4f");
    }

    @Override
    public void glUniform4fv(int location, int count, FloatBuffer v) {
        warnNotImplemented("glUniform4fv");
    }

    @Override
    public void glUniform4fv(int location, int count, float[] v, int offset) {
        warnNotImplemented("glUniform4fv");
    }

    @Override
    public void glUniform4i(int location, int x, int y, int z, int w) {
        warnNotImplemented("glUniform4i");
    }

    @Override
    public void glUniform4iv(int location, int count, IntBuffer v) {
        warnNotImplemented("glUniform4iv");
    }

    @Override
    public void glUniform4iv(int location, int count, int[] v, int offset) {
        warnNotImplemented("glUniform4iv");
    }

    @Override
    public void glUniformMatrix2fv(int location, int count, boolean transpose, FloatBuffer value) {
        warnNotImplemented("glUniformMatrix2fv");
    }

    @Override
    public void glUniformMatrix2fv(int location, int count, boolean transpose, float[] value, int offset) {
        warnNotImplemented("glUniformMatrix2fv");
    }

    @Override
    public void glUniformMatrix3fv(int location, int count, boolean transpose, FloatBuffer value) {
        warnNotImplemented("glUniformMatrix3fv");
    }

    @Override
    public void glUniformMatrix3fv(int location, int count, boolean transpose, float[] value, int offset) {
        warnNotImplemented("glUniformMatrix3fv");
    }

    @Override
    public void glUniformMatrix4fv(int location, int count, boolean transpose, FloatBuffer value) {
        warnNotImplemented("glUniformMatrix4fv");
    }

    @Override
    public void glUniformMatrix4fv(int location, int count, boolean transpose, float[] value, int offset) {
        warnNotImplemented("glUniformMatrix4fv");
    }

    @Override
    public void glUseProgram(int program) {
        warnNotImplemented("glUseProgram");
    }

    @Override
    public void glValidateProgram(int program) {
        warnNotImplemented("glValidateProgram");
    }

    @Override
    public void glVertexAttrib1f(int indx, float x) {
        warnNotImplemented("glVertexAttrib1f");
    }

    @Override
    public void glVertexAttrib1fv(int indx, FloatBuffer values) {
        warnNotImplemented("glVertexAttrib1fv");
    }

    @Override
    public void glVertexAttrib2f(int indx, float x, float y) {
        warnNotImplemented("glVertexAttrib2f");
    }

    @Override
    public void glVertexAttrib2fv(int indx, FloatBuffer values) {
        warnNotImplemented("glVertexAttrib2fv");
    }

    @Override
    public void glVertexAttrib3f(int indx, float x, float y, float z) {
        warnNotImplemented("glVertexAttrib3f");
    }

    @Override
    public void glVertexAttrib3fv(int indx, FloatBuffer values) {
        warnNotImplemented("glVertexAttrib3fv");
    }

    @Override
    public void glVertexAttrib4f(int indx, float x, float y, float z, float w) {
        warnNotImplemented("glVertexAttrib4f");
    }

    @Override
    public void glVertexAttrib4fv(int indx, FloatBuffer values) {
        warnNotImplemented("glVertexAttrib4fv");
    }

    @Override
    public void glVertexAttribPointer(int indx, int size, int type, boolean normalized, int stride, Buffer ptr) {
        warnNotImplemented("glVertexAttribPointer");
    }

    @Override
    public void glVertexAttribPointer(int indx, int size, int type, boolean normalized, int stride, int ptr) {
        warnNotImplemented("glVertexAttribPointer");
    }

    @Override
    public void glClearColor(float red, float green, float blue, float alpha) {
        String msg = "glClearColor: " + red + "," + green + "," + blue + "," + alpha;
        //if (Gdx.app != null) Gdx.app.log("VulkanGL20Impl", msg);
        //else System.out.println("VulkanGL20Impl: " + msg);
        VulkanWindow currentWin = graphics.getCurrentWindow(); // Add getCurrentWindow() to VulkanGraphics
        if (currentWin != null) {
            currentWin.getConfig().initialBackgroundColor.set(red, green, blue, alpha);
        }
    }

    @Override
    public void glClearDepthf(float depth) {
        warnNotImplemented("glClearDepthf");
    }

    @Override
    public void glClearStencil(int s) {
        warnNotImplemented("glClearStencil");
    }

    @Override
    public void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        warnNotImplemented("glColorMask");
    }

    @Override
    public void glClear(int mask) {
        String msg = "glClear: mask=" + mask;
        //if (Gdx.app != null) Gdx.app.log("VulkanGL20Impl", msg);
        //else System.out.println("VulkanGL20Impl: " + msg);
        // Actual clear is part of render pass loadOp. This is a hint.
    }

    private static final HashSet<String> warnedMethods = new HashSet<>();

    /** Logs a warning once per method name for unimplemented GL20 methods that are silently ignored. */
    private static void warnNotImplemented(String methodName) {
        if (warnedMethods.add(methodName)) {
            String message = "VulkanGL20Impl: '" + methodName + "' is not implemented (Vulkan backend). Call ignored.";
            if (Gdx.app != null) Gdx.app.debug("VulkanGL20Impl", message);
            else System.out.println(message);
        }
    }

    /** Logs an error and throws for GL20 methods that should not be called silently. */
    private void notImplemented(String methodName) {
        String message = "VulkanGL20Impl: Method '" + methodName + "' is not implemented yet.";
        if (Gdx.app != null) Gdx.app.error("VulkanGL20Impl", message);
        else System.err.println(message);
        throw new GdxRuntimeException(message);
    }

    @Override
    public void glActiveTexture(int texture) {
        activeTextureUnit = texture - GL20.GL_TEXTURE0;
    }

    @Override
    public void glBindTexture(int target, int textureHandle) {
        // The `target` (e.g., GL_TEXTURE_2D) can often be ignored in a basic
        // Vulkan backend that only supports 2D textures.

        // Store the provided handle in the array at the currently active unit's index.
        if (activeTextureUnit >= 0 && activeTextureUnit < boundTextureHandles.length) {
            boundTextureHandles[activeTextureUnit] = textureHandle;
        }
    }

    @Override
    public void glBlendFunc(int sfactor, int dfactor) {
        // Blend state is baked into Vulkan pipelines. VulkanSpriteBatch handles this
        // internally via pipeline variants. This call is a no-op at the GL level.
    }

    @Override
    public void glLineWidth(float width) {
        notImplemented("glLineWidth");
    }

    @Override
    public void glPixelStorei(int pname, int param) {
        warnNotImplemented("glPixelStorei");
    }

    @Override
    public void glPolygonOffset(float factor, float units) {
        warnNotImplemented("glPolygonOffset");
    }

    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, Buffer pixels) {
        warnNotImplemented("glReadPixels");
    }

    // ... MANY MORE ...
    @Override
    public void glCompressedTexImage2D(int target, int level, int internalformat, int width, int height, int border, int imageSize, java.nio.Buffer data) {
        notImplemented("glCompressedTexImage2D");
    }

    @Override
    public void glCompressedTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int imageSize, java.nio.Buffer data) {
        notImplemented("glCompressedTexSubImage2D");
    }

    @Override
    public void glCopyTexImage2D(int target, int level, int internalformat, int x, int y, int width, int height, int border) {
        warnNotImplemented("glCopyTexImage2D");
    }

    @Override
    public void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        warnNotImplemented("glCopyTexSubImage2D");
    }

    @Override
    public void glCullFace(int mode) {
        warnNotImplemented("glCullFace");
    }

    @Override
    public void glDeleteTextures(int n, IntBuffer textures) {
        // VulkanTexture manages its own lifecycle via dispose(). GL texture deletion is a no-op.
    }

    @Override
    public void glDeleteTexture(int texture) {
        // VulkanTexture manages its own lifecycle via dispose(). GL texture deletion is a no-op.
    }

    @Override
    public void glDepthFunc(int func) {
        warnNotImplemented("glDepthFunc");
    }

    @Override
    public void glDepthMask(boolean flag) {
        warnNotImplemented("glDepthMask");
    }

    @Override
    public void glDepthRangef(float zNear, float zFar) {
        warnNotImplemented("glDepthRangef");
    }

    @Override
    public void glDisable(int cap) {
        if (cap == GL20.GL_SCISSOR_TEST) {
            //Gdx.app.log("VulkanGL20Impl", "glDisable(GL_SCISSOR_TEST) called. Setting scissor to full viewport.");
            VkCommandBuffer currentCB = graphics.getCurrentVkCommandBuffer();
            if (currentCB == null) return;
            int fbWidth = graphics.getBackBufferWidth();
            int fbHeight = graphics.getBackBufferHeight();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkRect2D.Buffer vkScissorRectBuffer = VkRect2D.calloc(1, stack);
                vkScissorRectBuffer.get(0)
                        .offset(it -> it.x(0).y(0))
                        .extent(it -> it.width(fbWidth).height(fbHeight));
                VK10.vkCmdSetScissor(currentCB, 0, vkScissorRectBuffer);
            }
        } else {
            // Log other glDisable calls
            Gdx.app.log("VulkanGL20Impl", "glDisable called for cap: " + cap);
        }
    }

    @Override
    public void glDrawArrays(int mode, int first, int count) {
        warnNotImplemented("glDrawArrays");
    }

    @Override
    public void glDrawElements(int mode, int count, int type, Buffer indices) {
        warnNotImplemented("glDrawElements");
    }

    @Override
    public void glEnable(int cap) {
        if (cap == GL20.GL_SCISSOR_TEST) {
            //Gdx.app.log("VulkanGL20Impl", "glEnable(GL_SCISSOR_TEST) called. (Usually a no-op for Vulkan if pipeline has dynamic scissor).");
            // Typically no specific Vulkan command needed here if your pipelines
            // are created with VK_DYNAMIC_STATE_SCISSOR.
        } else {
            Gdx.app.log("VulkanGL20Impl", "glEnable called for cap: " + cap);
        }
    }

    @Override
    public void glFinish() {
        warnNotImplemented("glFinish");
    }

    @Override
    public void glFlush() {
        warnNotImplemented("glFlush");
    }

    @Override
    public void glFrontFace(int mode) {
        warnNotImplemented("glFrontFace");
    }

    @Override
    public void glGenTextures(int n, IntBuffer textures) {
        warnNotImplemented("glGenTextures");
    }

    @Override
    public int glGenTexture() {
        warnNotImplemented("glGenTexture");
        return 0;
    }

    @Override
    public int glGetError() {
        warnNotImplemented("glGetError");
        return 0;
    }

    @Override
    public void glGetIntegerv(int pname, IntBuffer params) {
        warnNotImplemented("glGetIntegerv");
    }

    @Override
    public String glGetString(int name) {
        warnNotImplemented("glGetString");
        return "";
    }

    @Override
    public void glHint(int target, int mode) {
        warnNotImplemented("glHint");
    }
    // ... (continue for all GL20 methods)
}