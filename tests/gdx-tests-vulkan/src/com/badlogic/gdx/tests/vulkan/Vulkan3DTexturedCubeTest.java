package com.badlogic.gdx.tests.vulkan;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backend.vulkan.SimpleUnlitTextureShader;
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

public class Vulkan3DTexturedCubeTest extends GdxTest {
    private static final String TAG = "Vulkan3DTexturedCubeTest";

    private PerspectiveCamera camera;
    private CameraInputController cameraInputController;

    private VulkanMesh cubeMesh;
    private VulkanTexture cubeTexture;
    private VulkanMaterial cubeMaterial;

    private SimpleUnlitTextureShader unlitTextureShader;

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
        // Important: The material needs to know about the texture for the shader to use it
        cubeMaterial.setDiffuseTexture(cubeTexture);

        unlitTextureShader = new SimpleUnlitTextureShader(cubeMesh.getVulkanVertexAttributes());

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
        // A properly textured cube needs 24 vertices, 4 for each face,
        // to have unique texture coordinates per face.
        // Vertex data: 3 floats for position (x, y, z), 2 floats for texture coordinates (u, v)
        // The V-coordinate is flipped (1.0 - v) to match Vulkan's coordinate system.
        float[] vertices = {
                // Front face (+Z)
                -0.5f, -0.5f, 0.5f, 0.0f, 1.0f, // 0 - Bottom-left
                0.5f, -0.5f, 0.5f, 1.0f, 1.0f, // 1 - Bottom-right
                0.5f, 0.5f, 0.5f, 1.0f, 0.0f, // 2 - Top-right
                -0.5f, 0.5f, 0.5f, 0.0f, 0.0f, // 3 - Top-left
                // Back face (-Z)
                0.5f, -0.5f, -0.5f, 0.0f, 1.0f, // 4
                -0.5f, -0.5f, -0.5f, 1.0f, 1.0f, // 5
                -0.5f, 0.5f, -0.5f, 1.0f, 0.0f, // 6
                0.5f, 0.5f, -0.5f, 0.0f, 0.0f, // 7
                // Top face (+Y)
                -0.5f, 0.5f, 0.5f, 0.0f, 1.0f, // 8
                0.5f, 0.5f, 0.5f, 1.0f, 1.0f, // 9
                0.5f, 0.5f, -0.5f, 1.0f, 0.0f, // 10
                -0.5f, 0.5f, -0.5f, 0.0f, 0.0f, // 11
                // Bottom face (-Y)
                -0.5f, -0.5f, -0.5f, 0.0f, 1.0f, // 12
                0.5f, -0.5f, -0.5f, 1.0f, 1.0f, // 13
                0.5f, -0.5f, 0.5f, 1.0f, 0.0f, // 14
                -0.5f, -0.5f, 0.5f, 0.0f, 0.0f, // 15
                // Right face (+X)
                0.5f, -0.5f, 0.5f, 0.0f, 1.0f, // 16
                0.5f, -0.5f, -0.5f, 1.0f, 1.0f, // 17
                0.5f, 0.5f, -0.5f, 1.0f, 0.0f, // 18
                0.5f, 0.5f, 0.5f, 0.0f, 0.0f, // 19
                // Left face (-X)
                -0.5f, -0.5f, -0.5f, 0.0f, 1.0f, // 20
                -0.5f, -0.5f, 0.5f, 1.0f, 1.0f, // 21
                -0.5f, 0.5f, 0.5f, 1.0f, 0.0f, // 22
                -0.5f, 0.5f, -0.5f, 0.0f, 0.0f, // 23
        };

        // CORRECTED: Indices are now defined in Counter-Clockwise (CCW) order,
        // which will be rendered correctly by the shader pipeline which is set to expect CW and flips the Y-axis.
        short[] indices = {
                0, 1, 2, 2, 3, 0,    // Front
                4, 5, 6, 6, 7, 4,    // Back
                8, 9, 10, 10, 11, 8,   // Top
                12, 13, 14, 14, 15, 12, // Bottom
                16, 17, 18, 18, 19, 16, // Right
                20, 21, 22, 22, 23, 20, // Left
        };


        // Define vertex attributes with shader locations
        VulkanVertexAttribute posAttr = new VulkanVertexAttribute(VulkanVertexAttributes.Usage.Position, 3, "a_position", 0);
        VulkanVertexAttribute texCoordAttr = new VulkanVertexAttribute(VulkanVertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0", 1);

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
        if (unlitTextureShader == null || cubeMesh == null) {
            Gdx.app.error(TAG, "Render: essential resources not initialized.");
            return;
        }

        cameraInputController.update();

        rotationAngleDeg = (rotationAngleDeg + Gdx.graphics.getDeltaTime() * 45f) % 360f;
        modelMatrix.setToRotation(Vector3.Y, rotationAngleDeg);

        // Use the begin/render/end pattern for the shader.
        // 1. Begin the shader. This binds the pipeline and sets up camera uniforms.
        unlitTextureShader.begin(camera);

        // 2. Render the specific object. This updates object-specific uniforms and issues the draw call.
        unlitTextureShader.render(cubeMesh, cubeMaterial, modelMatrix);

        // 3. End the shader batch.
        unlitTextureShader.end();
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
        if (unlitTextureShader != null) unlitTextureShader.dispose();
        if (cubeTexture != null) cubeTexture.dispose();
        if (cubeMesh != null) cubeMesh.dispose();

        if (Gdx.input.getInputProcessor() == cameraInputController) {
            Gdx.input.setInputProcessor(null);
        }
        Gdx.app.log(TAG, "Vulkan3DTexturedCubeTest (User-Friendly) disposed.");
    }
}
