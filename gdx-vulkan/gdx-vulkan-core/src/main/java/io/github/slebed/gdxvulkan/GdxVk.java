package io.github.slebed.gdxvulkan;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Locates and loads the gdx-vulkan native library. Order: {@code -Dgdxvulkan.native.path=/dir/or/file},
 * then {@code java.library.path}, then a resource {@code /natives/<os>-<arch>/<mapped name>} extracted to a temp file.
 */
public final class GdxVk {
    private GdxVk() {}

    private static boolean loaded;

    public static synchronized void loadNatives() {
        if (loaded) return;
        String explicit = System.getProperty("gdxvulkan.native.path");
        if (explicit != null) {
            File f = new File(explicit);
            if (f.isDirectory()) f = new File(f, System.mapLibraryName("gdxvk"));
            try {
                System.load(f.getAbsolutePath());
            } catch (UnsatisfiedLinkError e) {
                throw new VulkanLoaderException("Could not load " + f + ": " + e.getMessage(), e);
            }
            loaded = true;
            return;
        }
        try {
            System.loadLibrary("gdxvk");
            loaded = true;
            return;
        } catch (UnsatisfiedLinkError ignored) {
            // fall through to the bundled resource
        }
        String mapped = System.mapLibraryName("gdxvk");
        String res = "/natives/" + platform() + "/" + mapped;
        try (InputStream in = GdxVk.class.getResourceAsStream(res)) {
            if (in == null) {
                throw new VulkanLoaderException("Native library not found: not on java.library.path and no bundled resource "
                    + res + ". Build gdx-vulkan-core/jni with CMake and pass -Dgdxvulkan.native.path=<dir>.");
            }
            File tmp = File.createTempFile("gdxvk", mapped.substring(mapped.lastIndexOf('.')));
            tmp.deleteOnExit();
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.load(tmp.getAbsolutePath());
            loaded = true;
        } catch (IOException | UnsatisfiedLinkError e) {
            throw new VulkanLoaderException("Failed to load bundled native library " + res + ": " + e.getMessage(), e);
        }
    }

    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String o = os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
        String a = (arch.contains("aarch64") || arch.contains("arm64")) ? "arm64" : "x86_64";
        return o + "-" + a;
    }
}
