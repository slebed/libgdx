package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.backend.vulkan.VulkanModel;
import com.badlogic.gdx.backend.vulkan.VulkanModelBatch;
import com.badlogic.gdx.backend.vulkan.VulkanModelInstance;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DirectionalLightsAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.PointLightsAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.environment.PointLight;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.StringBuilder;

public class VulkanBenchmark3dTest extends VulkanBaseG3dHudTest {

    protected Environment environment;
    protected Label vertexCountLabel, textureBindsLabel, shaderSwitchesLabel, drawCallsLabel, lightsLabel;
    protected CheckBox lightingCheckBox, lightsCheckBox;
    protected boolean lighting;
    protected String currentlyLoading;

    @Override
    public void create() {
        super.create();

        randomizeLights();

        showAxes = true;
        lighting = true;

        // Find the table created by the parent and add to it
        Table table = (Table) hud.getActors().get(0); // Assuming the table is the first actor
        table.row();

        lightsLabel = new Label("Lights: 999", skin);
        table.add(lightsLabel).pad(5).left().row();
        vertexCountLabel = new Label("Vertices: 999", skin);
        table.add(vertexCountLabel).pad(5).left().row();
        textureBindsLabel = new Label("Texture bindings: 999", skin);
        table.add(textureBindsLabel).pad(5).left().row();
        shaderSwitchesLabel = new Label("Shader switches: 999", skin);
        table.add(shaderSwitchesLabel).pad(5).left().row();
        drawCallsLabel = new Label("Draw calls: 999", skin);
        table.add(drawCallsLabel).pad(5).left().row();

        Table controlsTable = new Table(skin);
        controlsTable.setFillParent(true);
        controlsTable.align(Align.topRight);
        hud.addActor(controlsTable);

        lightingCheckBox = new CheckBox("Lighting", skin);
        lightingCheckBox.setChecked(lighting);
        lightingCheckBox.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                lighting = lightingCheckBox.isChecked();
            }
        });
        controlsTable.add(lightingCheckBox).pad(5).row();

        lightsCheckBox = new CheckBox("Randomize lights", skin);
        lightsCheckBox.setChecked(false);
        lightsCheckBox.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                lightsCheckBox.setChecked(false);
                randomizeLights();
            }
        });
        controlsTable.add(lightsCheckBox).pad(5).row();
    }

    protected void randomizeLights() {
        int pointLights = MathUtils.random(5);
        int directionalLights = MathUtils.random(5);

        environment = new Environment();
        environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.4f, 0.4f, 0.4f, 1.f));

        for (int i = 0; i < pointLights; i++) {
            environment.add(new PointLight().set(randomColor(), randomPosition(), MathUtils.random(10f)));
        }

        for (int i = 0; i < directionalLights; i++) {
            environment.add(new DirectionalLight().set(randomColor(), randomPosition()));
        }
    }

    protected Color randomColor() {
        return new Color(MathUtils.random(), MathUtils.random(), MathUtils.random(), 1.0f);
    }

    protected Vector3 randomPosition() {
        return new Vector3(MathUtils.random(-10f, 10f), MathUtils.random(2f, 10f), MathUtils.random(-10f, 10f));
    }

    @Override
    protected void getStatus(final StringBuilder stringBuilder) {
        super.getStatus(stringBuilder); // This updates the FPS label

        // Note: VulkanModelBatch doesn't have the same profiler hooks as DefaultShader
        // so these will likely show 0. A custom profiling solution would be needed.
        stringBuilder.setLength(0);
        stringBuilder.append("Draw calls: ").append(modelBatch.renderCalls);
        drawCallsLabel.setText(stringBuilder);

        stringBuilder.setLength(0);
        stringBuilder.append("Shader switches: ").append(modelBatch.shaderSwitches);
        shaderSwitchesLabel.setText(stringBuilder);

        stringBuilder.setLength(0);
        stringBuilder.append("Vertices: ").append(modelBatch.numVertices);
        vertexCountLabel.setText(stringBuilder);

        DirectionalLightsAttribute dirLights = environment.get(DirectionalLightsAttribute.class, DirectionalLightsAttribute.Type);
        PointLightsAttribute pointLights = environment.get(PointLightsAttribute.class, PointLightsAttribute.Type);

        stringBuilder.setLength(0);
        stringBuilder.append("Lights: ");
        stringBuilder.append((dirLights == null ? 0 : dirLights.lights.size) + (pointLights == null ? 0 : pointLights.lights.size));
        stringBuilder.append(", Dir: ");
        stringBuilder.append(dirLights == null ? 0 : dirLights.lights.size);
        stringBuilder.append(", Point: ");
        stringBuilder.append(pointLights == null ? 0 : pointLights.lights.size);
        lightsLabel.setText(stringBuilder);

        modelBatch.resetCounts();
    }

    @Override
    protected void renderInstances(VulkanModelBatch batch) {
        // This is now the correct place for the benchmark's specific render logic
        if (lighting) {
            batch.render(instances, environment);
        } else {
            batch.render(instances);
        }
    }

    protected void onModelClicked(final String name) {
        if (name == null) return;

        currentlyLoading = "data/" + name;
        // CORRECT: Load as VulkanModel.class
        assets.load(currentlyLoading, VulkanModel.class);
        loading = true;
    }

    @Override
    protected void onLoaded() {
        if (currentlyLoading == null || currentlyLoading.length() == 0) return;

        // No cast needed as we loaded the correct type
        VulkanModel model = assets.get(currentlyLoading, VulkanModel.class);
        final VulkanModelInstance instance = new VulkanModelInstance(model);
        instance.transform = new Matrix4().idt();
        instance.transform.setToTranslation(MathUtils.random(-10, 10), MathUtils.random(-10, 10), MathUtils.random(-10, 10));
        instances.add(instance);
        currentlyLoading = null;
    }

    @Override
    public boolean keyUp(int keycode) {
        if (keycode == Input.Keys.SPACE || keycode == Input.Keys.MENU) {
            onModelClicked(models[MathUtils.random(models.length - 1)]);
        }
        return super.keyUp(keycode);
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        onModelClicked(models[MathUtils.random(models.length - 1)]);
        return super.touchUp(screenX, screenY, pointer, button);
    }
}
