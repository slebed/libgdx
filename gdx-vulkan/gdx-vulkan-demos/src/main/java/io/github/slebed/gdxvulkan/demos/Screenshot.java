package io.github.slebed.gdxvulkan.demos;

import java.nio.ByteBuffer;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.BufferUtils;

/**
 * {@code -Ddemo.screenshot=file.png} saves a picture of a demo after {@code -Ddemo.frames} frames (60) and leaves, on either backend: for the README, for a look at
 * a demo without sitting in front of it, and for the smoke test.
 */
final class Screenshot {
    private Screenshot () {}

    static String file () {
        return System.getProperty("demo.screenshot");
    }

    static int frames () {
        return Integer.getInteger("demo.frames", 60);
    }

    /** @param rgba width x height pixels, row 0 at the top */
    static void write (byte[] rgba, int width, int height, String file) {
        final Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        final ByteBuffer pixels = pixmap.getPixels();
        BufferUtils.copy(rgba, 0, pixels, rgba.length);
        PixmapIO.writePNG(new FileHandle(file), pixmap);
        pixmap.dispose();
    }
}
