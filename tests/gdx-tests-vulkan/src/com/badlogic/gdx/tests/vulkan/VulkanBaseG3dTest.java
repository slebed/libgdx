package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.assets.loaders.FileHandleResolver;
import com.badlogic.gdx.assets.loaders.resolvers.InternalFileHandleResolver;
import com.badlogic.gdx.backend.vulkan.*;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g3d.utils.CameraInputController;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.tests.utils.GdxTest;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;

public abstract class VulkanBaseG3dTest extends GdxTest {
    public AssetManager assets;

    public PerspectiveCamera cam;
    public CameraInputController inputController;
    public VulkanModelBatch modelBatch;
    public VulkanModel axesModel;
    public VulkanModelInstance axesInstance;
    public boolean showAxes = true;
    public Array<VulkanModelInstance> instances = new Array<>();
    public final Color bgColor = new Color(0, 0, 0, 1);

    // Shader specifically for rendering the coordinate axes
    private SimpleColorShader axesShader;

    protected boolean loading = false;

    @Override
    public void create () {
        if (assets == null) assets = new AssetManager();

        // Configure AssetManager for Vulkan-specific types
        FileHandleResolver resolver = new InternalFileHandleResolver();
        assets.setLoader(Texture.class, new VulkanTextureLoader(resolver));
        assets.setLoader(TextureAtlas.class, new VulkanTextureAtlasLoader(resolver));
        assets.setLoader(VulkanModel.class, ".g3dj", new VulkanModelLoader(resolver));
        assets.setLoader(VulkanModel.class, ".obj", new VulkanObjLoader(resolver));
        assets.setLoader(VulkanModel.class, ".g3db", new VulkanG3dbLoader(resolver));

        modelBatch = new VulkanModelBatch();

        cam = new PerspectiveCamera(67, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        cam.position.set(10f, 10f, 10f);
        cam.lookAt(0, 0, 0);
        cam.near = 1f;
        cam.far = 300f;
        cam.update();

        // This must be called AFTER the camera and other resources are set up
        createAxes();

        inputController = new CameraInputController(cam);
        Gdx.input.setInputProcessor(inputController);

        // Load skin and create the HUD stage
        assets.load("data/uiskin.json", Skin.class);
        assets.finishLoading();

    }

    private VulkanModel createAxesModel() {
        final float AXES_LEN = 5.0f;
        VulkanModel model = new VulkanModel("axes");
        VulkanVertexAttributes attributes = new VulkanVertexAttributes(
                new VulkanVertexAttribute(Usage.Position, 3, "a_position", 0),
                new VulkanVertexAttribute(Usage.ColorUnpacked, 4, "a_color", 1)
        );

        // Create the shader for the axes. This requires a valid render pass.
        long renderPassHandle = ((VulkanGraphics)Gdx.graphics).getSwapchainRenderPass();
        axesShader = new SimpleColorShader(attributes, renderPassHandle);
        VulkanShaderPipelineBundle axesPipeline = axesShader.getPipelineBundle();

        // Create the mesh and materials, assigning the pipeline to each material
        VulkanMesh xAxisMesh = new VulkanMesh();
        xAxisMesh.setVertices(new float[]{0, 0, 0, 1, 0, 0, 1, AXES_LEN, 0, 0, 1, 0, 0, 1}, attributes);
        xAxisMesh.setIndices(new short[]{0, 1});
        VulkanMaterial redMaterial = new VulkanMaterial("red").setDiffuseColor(Color.RED);
        redMaterial.setPipelineBundle(axesPipeline);
        model.addMesh(xAxisMesh);
        model.addMaterial(redMaterial);
        model.addMeshPart(new VulkanMeshPart("x_axis", xAxisMesh, 0, 2, GL20.GL_LINES, redMaterial));

        VulkanMesh yAxisMesh = new VulkanMesh();
        yAxisMesh.setVertices(new float[]{0, 0, 0, 0, 1, 0, 1, 0, AXES_LEN, 0, 0, 1, 0, 1}, attributes);
        yAxisMesh.setIndices(new short[]{0, 1});
        VulkanMaterial greenMaterial = new VulkanMaterial("green").setDiffuseColor(Color.GREEN);
        greenMaterial.setPipelineBundle(axesPipeline);
        model.addMesh(yAxisMesh);
        model.addMaterial(greenMaterial);
        model.addMeshPart(new VulkanMeshPart("y_axis", yAxisMesh, 0, 2, GL20.GL_LINES, greenMaterial));

        VulkanMesh zAxisMesh = new VulkanMesh();
        zAxisMesh.setVertices(new float[]{0, 0, 0, 0, 0, 1, 1, 0, 0, AXES_LEN, 0, 0, 1, 1}, attributes);
        zAxisMesh.setIndices(new short[]{0, 1});
        VulkanMaterial blueMaterial = new VulkanMaterial("blue").setDiffuseColor(Color.BLUE);
        blueMaterial.setPipelineBundle(axesPipeline);
        model.addMesh(zAxisMesh);
        model.addMaterial(blueMaterial);
        model.addMeshPart(new VulkanMeshPart("z_axis", zAxisMesh, 0, 2, GL20.GL_LINES, blueMaterial));

        return model;
    }

    protected void createAxes () {
        axesModel = createAxesModel();
        axesInstance = new VulkanModelInstance(axesModel);
    }

    protected void onLoaded () {}

    protected void renderInstances (final VulkanModelBatch batch) {
        batch.render(instances);
    }

    @Override
    public void render () {
        if (loading && assets.update()) {
            loading = false;
            onLoaded();
        }

        inputController.update();
        ScreenUtils.clear(bgColor, true);

        // Render "proper" model instances using the model batch
        modelBatch.begin(cam);
        renderInstances(modelBatch);
        modelBatch.end();

        // CORRECTED: Render the axes separately using its dedicated shader
        if (showAxes && axesShader != null) {
            axesShader.begin(cam);
            axesShader.render(axesInstance);
            axesShader.end();
        }

        //hud.act(Gdx.graphics.getDeltaTime());
        //hud.draw();
    }

    @Override
    public void resize(int width, int height) {
        cam.viewportWidth = width;
        cam.viewportHeight = height;
        cam.update();

    }

    @Override
    public void dispose () {
        if (modelBatch != null) modelBatch.dispose();
        if (assets != null) assets.dispose();
        if (axesModel != null) axesModel.dispose();
        if (axesShader != null) axesShader.dispose();

    }
}
