package com.badlogic.gdx.tools.vulkanbindinggen;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.tools.vulkanbindinggen.parser.VulkanSpecParser;
import com.badlogic.gdx.tools.vulkanbindinggen.ui.BindingGeneratorUI;
import com.badlogic.gdx.tools.vulkanbindinggen.ui.SkinManager;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

public class BindingGeneratorApp extends ApplicationAdapter {
    private Stage stage;
    private Skin skin;
    private VulkanSpecParser specParser;
    private BindingGeneratorUI generatorUI;

    @Override
    public void create() {
        // 1. Load and Parse Vulkan Specification
        // For simplicity, parse on the main thread. For very large specs or slower machines,
        // consider a loading screen and background thread.
        specParser = new VulkanSpecParser();
        try {
            // Load from the classpath where copyAssets put it
            specParser.parse(Gdx.files.classpath("vk.xml"));
            System.out.println("Successfully parsed vk.xml.");
            System.out.println("Found " + specParser.getCommands().size() + " commands.");
            System.out.println("Found " + specParser.getCoreVersions().size() + " core versions.");
            System.out.println("Found " + specParser.getExtensions().size() + " extensions.");
        } catch (Exception e) {
            Gdx.app.error("PARSER", "Failed to parse vk.xml", e);
            // Handle error appropriately - maybe show a dialog and exit
            Gdx.app.exit();
            return; // Stop creation if parsing fails
        }

        // 2. Create UI Skin
        skin = SkinManager.createBasicSkin(); // Use our custom skin creator

        // 3. Create Stage
        stage = new Stage(new ScreenViewport());
        Gdx.input.setInputProcessor(stage);

        // 4. Create Main UI Layout
        generatorUI = new BindingGeneratorUI(skin, specParser);
        stage.addActor(generatorUI.buildUI()); // Build and add the main table to the stage

        // 5. Initial UI population (done inside BindingGeneratorUI constructor or buildUI)
    }

    @Override
    public void render() {
        // Clear screen
        Gdx.gl.glClearColor(0.1f, 0.1f, 0.1f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // Update and draw stage
        stage.act(Math.min(Gdx.graphics.getDeltaTime(), 1 / 30f)); // Cap delta time
        stage.draw();
    }

    @Override
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
        // Optional: notify the UI layout if it needs resizing logic beyond the viewport
        if (generatorUI != null) {
            generatorUI.resize(width, height);
        }
    }

    @Override
    public void dispose() {
        if (stage != null) {
            stage.dispose();
        }
        if (skin != null) {
            skin.dispose();
        }
        System.out.println("Application disposed.");
    }
}