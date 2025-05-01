package com.badlogic.gdx.tests.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.tools.vulkanbindinggen.BindingGeneratorApp;

public class VulkanBindingGeneratorLauncher {
    public static void main(String[] arg) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Vulkan Binding Generator");
        config.setWindowedMode(1024, 768);
        config.useVsync(true);
        config.setForegroundFPS(60);
        // Required for Scene2D FileChoosers if added later, but not for this core task
        // config.setFilesystemAssetResolutionStrategy(FilesystemAssetResolutionStrategy.DIRECT);
        new Lwjgl3Application(new BindingGeneratorApp(), config);
    }
}