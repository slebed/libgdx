package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.resolvers.InternalFileHandleResolver;
import com.badlogic.gdx.backend.vulkan.VulkanStage;
import com.badlogic.gdx.backend.vulkan.VulkanTextureAtlasLoader;
import com.badlogic.gdx.backend.vulkan.VulkanTextureLoader;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.List;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.tests.g3d.BaseG3dHudTest;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.Scaling;
import com.badlogic.gdx.utils.StringBuilder;
import com.badlogic.gdx.utils.viewport.ScalingViewport;

public abstract class VulkanBaseG3dHudTest extends VulkanBaseG3dTest {
    public final static int PREF_HUDWIDTH = 640;
    public final static int PREF_HUDHEIGHT = 480;
    public final static float rotationSpeed = 0.02f * 360f; // degrees per second
    public final static float moveSpeed = 0.25f; // cycles per second
    protected Label fpsLabel;
    protected BaseG3dHudTest.CollapsableWindow modelsWindow;
    protected CheckBox gridCheckBox, rotateCheckBox, moveCheckBox;

    protected VulkanStage hud;
    protected float hudWidth, hudHeight;
    protected Skin skin;

    //protected String[] models = new String[] { "g3d/knight.g3db"};

    protected String[] models = new String[] {"car.obj", "cube.obj", "scene.obj", "scene2.obj", "wheel.obj", "g3d/invaders.g3dj",
            "g3d/head.g3db", "g3d/knight.g3dj", "g3d/knight.g3db", "g3d/monkey.g3db", "g3d/ship.obj", "g3d/shapes/cube_1.0x1.0.g3dj",
            "g3d/shapes/cube_1.5x1.5.g3dj", "g3d/shapes/sphere.g3dj", "g3d/shapes/teapot.g3dj", "g3d/shapes/torus.g3dj"};

    protected final Matrix4 transform = new Matrix4();
    protected float rotation, movement;
    protected float moveRadius = 2f;

    @Override
    public void create () {
        super.create();

        createHudWidgets();

        Gdx.input.setInputProcessor(new InputMultiplexer(hud, this, inputController));
    }

    /**
     * Creates the actual UI widgets and adds them to the 'hud' Stage.
     */
    protected void createHudWidgets() {
        hud = new VulkanStage(new ScalingViewport(Scaling.fit, PREF_HUDWIDTH, PREF_HUDHEIGHT));
        hudWidth = hud.getWidth();
        hudHeight = hud.getHeight();

        FileHandleResolver resolver = new InternalFileHandleResolver();
        AssetManager assetManager = new AssetManager();
        assetManager.setLoader(Texture.class, new VulkanTextureLoader(resolver));
        assetManager.setLoader(TextureAtlas.class, new VulkanTextureAtlasLoader(resolver));

        String skinPath = "data/uiskin.json";
        try {
            assetManager.load(skinPath, Skin.class);
            assetManager.finishLoading(); // Important to block here
            skin = assetManager.get(skinPath, Skin.class);
        } catch (Exception e) {
            assetManager.dispose();
            throw new GdxRuntimeException("Failed to load uiskin via AssetManager", e);
        }

        final List<String> modelsList = new List<>(skin);
        modelsList.setItems(models);
        modelsList.addListener(new ClickListener() {
            @Override
            public void clicked (InputEvent event, float x, float y) {
                if (!modelsWindow.isCollapsed() && getTapCount() == 2) {
                    onModelClicked(modelsList.getSelected());
                    modelsWindow.collapse();
                }
            }
        });
        modelsWindow = addListWindow(modelsList, 0, -1);

        final Table table = new Table(skin);
        table.setFillParent(true);
        table.align(Align.topLeft);
        hud.addActor(table);

        fpsLabel = new Label("FPS: ", skin);
        table.add(fpsLabel).pad(5).row();

        gridCheckBox = new CheckBox("Show grid", skin);
        gridCheckBox.setChecked(showAxes);
        gridCheckBox.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                showAxes = gridCheckBox.isChecked();
            }
        });
        table.add(gridCheckBox).pad(5).left().row();

        rotateCheckBox = new CheckBox("Rotate", skin);
        rotateCheckBox.setChecked(true);
        table.add(rotateCheckBox).pad(5).left().row();

        moveCheckBox = new CheckBox("Move", skin);
        moveCheckBox.setChecked(false);
        table.add(moveCheckBox).pad(5).left().row();
    }

    protected BaseG3dHudTest.CollapsableWindow addListWindow (List<String> list, float x, float y) {
        BaseG3dHudTest.CollapsableWindow window = new BaseG3dHudTest.CollapsableWindow("Models", skin);
        window.row();
        ScrollPane pane = new ScrollPane(list, skin);
        pane.setFadeScrollBars(false);
        window.add(pane);
        window.pack();
        window.pack();
        if (window.getHeight() > hudHeight) {
            window.setHeight(hudHeight);
        }
        window.setX(x < 0 ? hudWidth - (window.getWidth() - (x + 1)) : x);
        window.setY(y < 0 ? hudHeight - (window.getHeight() - (y + 1)) : y);
        window.layout();
        window.collapse();
        hud.addActor(window);
        pane.setScrollX(0);
        pane.setScrollY(0);
        return window;
    }

    protected abstract void onModelClicked (final String name);

    protected void getStatus(final StringBuilder stringBuilder) {
        stringBuilder.append("FPS: ").append(Gdx.graphics.getFramesPerSecond());
        fpsLabel.setText(stringBuilder);
    }

    private final StringBuilder stringBuilder = new StringBuilder();
    @Override
    public void render () {
        transform.idt();
        if (rotateCheckBox.isChecked())
            transform.rotate(Vector3.Y, rotation = (rotation + rotationSpeed * Gdx.graphics.getDeltaTime()) % 360);
        if (moveCheckBox.isChecked()) {
            movement = (movement + moveSpeed * Gdx.graphics.getDeltaTime()) % 1f;
            final float sm = MathUtils.sin(movement * MathUtils.PI2);
            final float cm = MathUtils.cos(movement * MathUtils.PI2);
            transform.trn(0, moveRadius * cm, moveRadius * sm);
        }

        super.render();

        stringBuilder.setLength(0);
        getStatus(stringBuilder);
        fpsLabel.setText(stringBuilder);
        hud.act(Gdx.graphics.getDeltaTime());
        hud.draw();
    }

    @Override
    public void resize (int width, int height) {
        super.resize(width, height);
        hud.getViewport().update(width, height, true);
        hudWidth = hud.getWidth();
        hudHeight = hud.getHeight();
    }

    @Override
    public void dispose () {
        super.dispose();
        skin.dispose();
        skin = null;
    }
}
