
package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backend.vulkan.VulkanApplication;
import com.badlogic.gdx.backend.vulkan.VulkanApplicationConfiguration;
import com.badlogic.gdx.tests.utils.CommandLineOptions;
import com.badlogic.gdx.tests.utils.GdxTestWrapper;
import com.badlogic.gdx.tests.utils.GdxTests;

public class VulkanTestStarter {
	static CommandLineOptions options;

	static {
		try {
			System.out.println("[VulkanTestStarter] Static block: Loading natives...");
			// Use the loader from your backend package
			com.badlogic.gdx.backend.vulkan.VulkanNativesLoader.load();
			System.out.println("[VulkanTestStarter] Static block: Natives loaded successfully.");
		} catch (Throwable t) {
			System.err.println("[VulkanTestStarter] FATAL: Failed to load natives in static block.");
			t.printStackTrace();
			// Optionally rethrow to prevent main from running if natives fail critically
			throw new RuntimeException("Native loading failed in static block", t);
		}
	}

	/** Runs libgdx tests.
	 * <p>
	 * some options can be passed, see {@link CommandLineOptions}
	 *
	 * @param argv command line arguments */
	public static void main (String[] argv) {

		// REMOVE the explicit natives loading try/catch block from here

		options = new CommandLineOptions(argv);

		// config isn't used if launching specific test via options
		// VulkanApplicationConfiguration config = new VulkanApplicationConfiguration();

		if (options.startupTestName != null) {
			ApplicationListener test = GdxTests.newTest(options.startupTestName);
			if (test != null) {
				VulkanApplicationConfiguration vkConfig = new VulkanApplicationConfiguration();
				vkConfig.setTitle(options.startupTestName + " (Vulkan)");
				vkConfig.setWindowedMode(640, 480);
				// This call should now happen AFTER natives are loaded by static block
				new VulkanApplication(new GdxTestWrapper(test, false), vkConfig);
			} else {
				System.err.println("ERROR: Test specified via argument not found: " + options.startupTestName);
				System.exit(1);
			}
		} else {
			System.err.println("ERROR: No startupTestName provided to VulkanTestStarter.");
			System.exit(1);
		}
	}

}
