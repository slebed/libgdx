
package com.badlogic.gdx.tests.vulkan.chooser;

// Use LWJGL3
// Use LWJGL3
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.*;

// For ProcessBuilder

public class VulkanChooserLauncher {

	// No CommandLineOptions needed here unless TestChooser uses them

	public static void main (String[] argv) {
// // This main method ONLY launches the LWJGL3 Test Chooser UI
// Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
// config.setWindowedMode(640, 800);
// config.setTitle("Vulkan Test Selector (Running on LWJGL3)");
// new Lwjgl3Application(new TestChooser(), config);
// }
//
// static class TestChooser extends ApplicationAdapter {
// private Stage stage;
// private Skin skin;
// TextButton lastClickedTestButton;
//
// public void create () {
// System.out.println("TestChooser running on: " + Gdx.app.getType());
//
// final Preferences prefs = Gdx.app.getPreferences("gdx-vulkan-tests"); // Changed name slightly
//
// // ... Stage, Skin, UI setup as before ...
// stage = new Stage(new ScreenViewport());
// Gdx.input.setInputProcessor(stage);
// skin = new Skin(Gdx.files.internal("data/uiskin.json")); // Make sure path is correct
// // ... Table, ScrollPane setup ...
// Table container = new Table();
// stage.addActor(container);
// container.setFillParent(true);
// Table table = new Table();
// ScrollPane scroll = new ScrollPane(table, skin);
// scroll.setSmoothScrolling(false);
// scroll.setFadeScrollBars(false);
// stage.setScrollFocus(scroll);
// int tableSpace = 4;
// table.pad(10).defaults().expandX().space(tableSpace);
//
// for (final String testName : GdxTests.getNames()) {
// final TextButton testButton = new TextButton(testName, skin);
// // testButton.setDisabled(!options.isTestCompatible(testName)); // Compatibility check needs review
// testButton.setName(testName);
// table.add(testButton).fillX();
// table.row();
// testButton.addListener(new ChangeListener() {
// @Override
// public void changed (ChangeEvent event, Actor actor) {
// ApplicationListener test = GdxTests.newTest(testName);
// if (test == null) {
// System.err.println("Test '" + testName + "' not found by GdxTests.");
// return;
// }
//
// System.out.println("Launching test in separate Vulkan process: " + testName);
// prefs.putString("LastTest", testName);
// prefs.flush();
//
// // --- Update button colors ---
// if (testButton != lastClickedTestButton) {
// testButton.setColor(Color.CYAN);
// if (lastClickedTestButton != null) {
// lastClickedTestButton.setColor(Color.WHITE);
// }
// lastClickedTestButton = testButton;
// }
//
// // Inside ChangeListener.changed() method in VulkanChooserLauncher.java
//
// try {
// String javaHome = System.getProperty("java.home");
// String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
// String classpath = System.getProperty("vulkan.test.classpath");
// String assetsPath = System.getProperty("vulkan.test.assetsdir");
//
// if (classpath == null || classpath.isEmpty()) {
// throw new IllegalStateException(/* ... */);
// }
// if (assetsPath == null || assetsPath.isEmpty()) {
// throw new IllegalStateException(/* ... */);
// }
//
// String className = com.badlogic.gdx.tests.vulkan.VulkanTestStarter.class.getCanonicalName();
//
// List<String> command = new ArrayList<String>();
// command.add(javaBin);
// command.add("-cp");
// command.add(classpath);
//
// // ---> Use LWJGL's specific property instead of java.library.path <---
// String tempDir = System.getProperty("java.io.tmpdir");
// command.add("-Dorg.lwjgl.librarypath=" + tempDir);
// // ---------------------------------------------------------------
//
// // Keep LWJGL Debug flags
// command.add("-Dorg.lwjgl.util.Debug=true");
// command.add("-Dorg.lwjgl.util.DebugLoader=true");
//
// // Keep extract path hint? Maybe remove if librarypath is set? Let's remove it for now.
// // command.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + tempDir);
//
// command.add(className);
// command.add(testName);
//
// System.out.println("Launching command: " + String.join(" ", command));
//
// ProcessBuilder builder = new ProcessBuilder(command);
// builder.directory(new File(assetsPath));
// builder.inheritIO();
//
// Process process = builder.start();
// System.out.println("Launched process for test: " + testName);
//
// } catch (Exception e) {
// System.err.println("Failed to launch test process for: " + testName);
// e.printStackTrace();
// }
// }
// });
// }
//
// container.add(scroll).expand().fill();
// container.row();
//
// lastClickedTestButton = (TextButton)table.findActor(prefs.getString("LastTest"));
// if (lastClickedTestButton != null) {
// lastClickedTestButton.setColor(Color.CYAN);
// scroll.layout();
// float scrollY = lastClickedTestButton.getY() + scroll.getScrollHeight() / 2 + lastClickedTestButton.getHeight() / 2
// + tableSpace * 2 + 20;
// scroll.scrollTo(0, scrollY, 0, 0, false, false);
//
// // Since ScrollPane takes some time for scrolling to a position, we just "fake" time
// stage.act(1f);
// stage.act(1f);
// stage.draw();
// }
// }
//
// @Override
// public void render () {
// ScreenUtils.clear(0, 0, 0, 1);
// stage.act();
// stage.draw();
// }
//
// @Override
// public void resize (int width, int height) {
// stage.getViewport().update(width, height, true);
// }
//
// @Override
// public void dispose () {
// skin.dispose();
// stage.dispose();
// }
// }
	}
}
