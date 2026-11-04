package com.badlogic.gdx.backends.lwjgl3;

import io.github.slebed.gdxvulkan.desktop.VulkanDesktopConfiguration;

/**
 * Reads a stock {@link Lwjgl3ApplicationConfiguration}, whose fields are package-private and have no getters, so that
 * {@code new VulkanDesktopApplication(listener, lwjgl3Config)} can take the configuration an existing game already has. This class lives in
 * libGDX's package for that access only; it changes nothing in libGDX.
 */
public final class VulkanConfigurations {
    private VulkanConfigurations () {}

    /** The settings of a stock configuration that mean something here. OpenGL context settings (GL version, colour bits, samples) are ignored. */
    public static VulkanDesktopConfiguration from (Lwjgl3ApplicationConfiguration c) {
        final VulkanDesktopConfiguration v = new VulkanDesktopConfiguration();
        v.title = c.title;
        v.width = c.windowWidth;
        v.height = c.windowHeight;
        v.x = c.windowX;
        v.y = c.windowY;
        v.resizable = c.windowResizable;
        v.decorated = c.windowDecorated;
        v.maximized = c.windowMaximized;
        v.fullscreen = c.fullscreenMode != null;
        v.vsync = c.vSyncEnabled;
        v.foregroundFPS = c.foregroundFPS;
        v.disableAudio = c.disableAudio;
        v.depthBuffer = c.depth > 0;
        v.audioDeviceSimultaneousSources = c.audioDeviceSimultaneousSources;
        v.audioDeviceBufferCount = c.audioDeviceBufferCount;
        v.audioDeviceBufferSize = c.audioDeviceBufferSize;
        v.preferencesDirectory = c.preferencesDirectory;
        if (c.initialBackgroundColor != null) {
            v.clearRed = c.initialBackgroundColor.r;
            v.clearGreen = c.initialBackgroundColor.g;
            v.clearBlue = c.initialBackgroundColor.b;
            v.clearAlpha = c.initialBackgroundColor.a;
        }
        return v;
    }
}
