package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.vulkan.VulkanApplication;
import com.badlogic.gdx.backends.vulkan.VulkanApplicationConfiguration;
import com.badlogic.gdx.tests.utils.CommandLineOptions;
import com.badlogic.gdx.tests.utils.GdxTestWrapper;
import com.badlogic.gdx.tests.utils.GdxTests;

public class VulkanTestStarter {
    static CommandLineOptions options;

    /** Runs libgdx tests.
     *
     * some options can be passed, see {@link CommandLineOptions}
     *
     * @param argv command line arguments */
    public static void main (String[] argv) {
        options = new CommandLineOptions(argv);

        VulkanApplicationConfiguration config = new VulkanApplicationConfiguration();
        config.setWindowedMode(640, 800);

        if (options.startupTestName != null) {
            // SCENARIO B: Launched with a test name argument (by ProcessBuilder)
            // Run the specific test using the Vulkan backend
            ApplicationListener test = GdxTests.newTest(options.startupTestName);
            if (test != null) {
                VulkanApplicationConfiguration vkConfig = new VulkanApplicationConfiguration();
                vkConfig.setTitle(options.startupTestName + " (Vulkan)");
                vkConfig.setWindowedMode(640, 480); // Or load specific config?
                // Ensure GL Profiling is OFF for Vulkan test runs
                new VulkanApplication(new GdxTestWrapper(test, false /* logGLErrors=false */), vkConfig);
            } else {
                System.err.println("ERROR: Test specified via argument not found: " + options.startupTestName);
                // Maybe exit or fall back to list view? Fallback requires Lwjgl3 here.
                // launchListView(); // Be careful - this main might not have Lwjgl3 classes readily available if launched oddly. Exit is safer.
                System.exit(1);
            }
        }
    }


}
