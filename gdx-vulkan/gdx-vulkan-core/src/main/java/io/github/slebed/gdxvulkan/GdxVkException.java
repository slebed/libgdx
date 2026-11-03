package io.github.slebed.gdxvulkan;

/** Base type for all gdx-vulkan failures. Unchecked, like GdxRuntimeException. */
public class GdxVkException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GdxVkException(String message) { super(message); }
    public GdxVkException(String message, Throwable cause) { super(message, cause); }
}
