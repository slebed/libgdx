package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backend.vulkan.SimpleLitTextureShader;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g3d.utils.CameraInputController;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.tests.utils.GdxTest;
import com.badlogic.gdx.utils.GdxRuntimeException;

import com.badlogic.gdx.backend.vulkan.VulkanMesh;
import com.badlogic.gdx.backend.vulkan.VulkanVertexAttribute;
import com.badlogic.gdx.backend.vulkan.VulkanVertexAttributes;
import com.badlogic.gdx.backend.vulkan.VulkanTexture;
import com.badlogic.gdx.backend.vulkan.VulkanMaterial;

public class Vulkan3DLitTexturedCubeTest extends GdxTest {
    private static final String TAG = "Vulkan3DLitTexturedCubeTest";

    private PerspectiveCamera camera;
    private CameraInputController cameraInputController;

    private VulkanMesh cubeMesh;
    private VulkanTexture cubeTexture;
    private VulkanMaterial cubeMaterial;

    private SimpleLitTextureShader litTextureShader;

    private final Matrix4 modelMatrix = new Matrix4();
    private float rotationAngleDeg = 0f;

    @Override
    public void create() {
        setupCamera();

        this.cubeMesh = setupCubeMeshInternal();
        loadCubeTexture();

        cubeMaterial = new VulkanMaterial("cubeMat");
        cubeMaterial.setDiffuseColor(Color.WHITE);
        cubeMaterial.setOpacity(1.0f);

        litTextureShader = new SimpleLitTextureShader(cubeMesh.getVulkanVertexAttributes());
        litTextureShader.setDiffuseTexture(cubeTexture);

        Gdx.input.setInputProcessor(cameraInputController);
        Gdx.app.log(TAG, "Vulkan3DTexturedCubeTest (User-Friendly) created.");
    }

    private void setupCamera() {
        camera = new PerspectiveCamera(67, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.position.set(2.0f, 1.75f, 2.0f);
        camera.lookAt(0, 0, 0);
        camera.near = 0.1f;
        camera.far = 100f;
        camera.update();
        cameraInputController = new CameraInputController(camera);
    }

    private VulkanMesh setupCubeMeshInternal() {
        float[] vertices = {
                -0.5f, -0.5f, 0.5f, 0.0f, 0.0f, 0.5f, -0.5f, 0.5f, 1.0f, 0.0f, 0.5f, 0.5f, 0.5f, 1.0f, 1.0f, -0.5f, 0.5f, 0.5f, 0.0f, 1.0f,
                -0.5f, -0.5f, -0.5f, 1.0f, 0.0f, 0.5f, -0.5f, -0.5f, 0.0f, 0.0f, 0.5f, 0.5f, -0.5f, 0.0f, 1.0f, -0.5f, 0.5f, -0.5f, 1.0f, 1.0f,
                -0.5f, 0.5f, -0.5f, 0.0f, 1.0f, -0.5f, 0.5f, 0.5f, 0.0f, 0.0f, 0.5f, 0.5f, 0.5f, 1.0f, 0.0f, 0.5f, 0.5f, -0.5f, 1.0f, 1.0f,
                -0.5f, -0.5f, -0.5f, 0.0f, 0.0f, 0.5f, -0.5f, -0.5f, 1.0f, 0.0f, 0.5f, -0.5f, 0.5f, 1.0f, 1.0f, -0.5f, -0.5f, 0.5f, 0.0f, 1.0f,
                0.5f, -0.5f, -0.5f, 1.0f, 0.0f, 0.5f, 0.5f, -0.5f, 1.0f, 1.0f, 0.5f, 0.5f, 0.5f, 0.0f, 1.0f, 0.5f, -0.5f, 0.5f, 0.0f, 0.0f,
                -0.5f, -0.5f, -0.5f, 0.0f, 0.0f, -0.5f, -0.5f, 0.5f, 1.0f, 0.0f, -0.5f, 0.5f, 0.5f, 1.0f, 1.0f, -0.5f, 0.5f, -0.5f, 0.0f, 1.0f
        };
        short[] indices = {
                0, 1, 2, 2, 3, 0, 4, 7, 6, 6, 5, 4, 8, 9, 10, 10, 11, 8,
                12, 13, 14, 14, 15, 12, 16, 17, 18, 18, 19, 16, 20, 21, 22, 22, 23, 20
        };
        VulkanVertexAttribute posAttr = VulkanVertexAttribute.Position(0);
        VulkanVertexAttribute texCoordAttr = VulkanVertexAttribute.TexCoords(0, 1);
        VulkanVertexAttributes attributes = new VulkanVertexAttributes(posAttr, texCoordAttr);
        VulkanMesh mesh = new VulkanMesh();
        mesh.setVertices(vertices, attributes);
        mesh.setIndices(indices);
        return mesh;
    }

    private void loadCubeTexture() {
        FileHandle textureFile = Gdx.files.internal("data/badlogic.jpg");
        if (!textureFile.exists()) throw new GdxRuntimeException("Texture not found: " + textureFile.path());
        cubeTexture = new VulkanTexture(textureFile);
        Gdx.app.log(TAG, "Texture loaded: " + cubeTexture.getFilePath());
    }

    @Override
    public void render() {
        if (litTextureShader == null || cubeMesh == null) {
            Gdx.app.error(TAG, "Render: essential resources not initialized.");
            return;
        }

        if (cameraInputController != null) cameraInputController.update();
        camera.update();

        rotationAngleDeg = (rotationAngleDeg + Gdx.graphics.getDeltaTime() * 45f) % 360f;
        modelMatrix.setToRotation(Vector3.Y, rotationAngleDeg);

        // Single call to the shader to render the mesh
        litTextureShader.render(cubeMesh, modelMatrix, camera.view, camera.projection, cubeMaterial);
    }

    @Override
    public void resize(int width, int height) {
        if (camera != null) {
            camera.viewportWidth = width;
            camera.viewportHeight = height;
            camera.update(true);
        }
    }

    @Override
    public void dispose() {
        if (litTextureShader != null) litTextureShader.dispose();
        if (cubeTexture != null) cubeTexture.dispose();
        if (cubeMesh != null) cubeMesh.dispose();

        if (Gdx.input.getInputProcessor() == cameraInputController) {
            Gdx.input.setInputProcessor(null);
        }
        Gdx.app.log(TAG, "Vulkan3DTexturedCubeTest (User-Friendly) disposed.");
    }
}