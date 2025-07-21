package com.badlogic.gdx.backend.vulkan;

import com.badlogic.gdx.assets.AssetManager;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelTexture;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.ObjectMap;

public class VulkanModel extends Model {
	public final String id;
	public final Array<VulkanMesh> meshes = new Array<>(true, 1, VulkanMesh.class);
	public final Array<VulkanMaterial> materials = new Array<>(true, 1, VulkanMaterial.class);
	public final Array<VulkanMeshPart> meshParts = new Array<>(true, 1, VulkanMeshPart.class);

	// This model now owns and is responsible for disposing the shaders it creates.
	private final Array<Disposable> disposables = new Array<>();

	public VulkanModel(String id) {
		this.id = id;
	}

	public VulkanModel(ModelData data, AssetManager assetManager) {
		this(data.id);

		ObjectMap<String, VulkanMaterial> materialMap = new ObjectMap<>();
		ObjectMap<String, VulkanMesh> meshMap = new ObjectMap<>();
		// This map will cache shaders to avoid creating duplicates for identical vertex layouts.
		ObjectMap<VulkanVertexAttributes, SimpleUnlitTextureShader> shaderCache = new ObjectMap<>();
		int defaultMaterialCount = 0;
		int defaultMeshCount = 0;

		// 1. Create all VulkanMesh objects and their corresponding Shaders
		for (ModelMesh modelMesh : data.meshes) {
			String meshId = modelMesh.id;
			if (meshId == null) {
				meshId = "default_mesh_" + (defaultMeshCount++);
			}

			VulkanMesh vulkanMesh = new VulkanMesh();
			VulkanVertexAttributes attributes = new VulkanVertexAttributes(modelMesh.attributes);
			vulkanMesh.setVertices(modelMesh.vertices, attributes);

			// Combine indices
			int totalIndices = 0;
			for (ModelMeshPart part : modelMesh.parts) totalIndices += part.indices.length;
			short[] combinedIndices = new short[totalIndices];
			int offset = 0;
			for (ModelMeshPart part : modelMesh.parts) {
				System.arraycopy(part.indices, 0, combinedIndices, offset, part.indices.length);
				offset += part.indices.length;
			}
			vulkanMesh.setIndices(combinedIndices);
			addMesh(vulkanMesh);
			meshMap.put(meshId, vulkanMesh);

			// Create a shader for this mesh's vertex layout if one doesn't exist yet
			if (!shaderCache.containsKey(attributes)) {
				SimpleUnlitTextureShader shader = new SimpleUnlitTextureShader(attributes);
				shaderCache.put(attributes, shader);
				disposables.add(shader); // Add to disposables list
			}
		}

		// 2. Create all materials and assign the correct shader pipeline to them
		for (ModelMaterial modelMat : data.materials) {
			String materialId = modelMat.id;
			if (materialId == null) {
				materialId = "default_material_" + (defaultMaterialCount++);
			}

			VulkanMaterial material = new VulkanMaterial(materialId);
			if (modelMat.diffuse != null) material.setDiffuseColor(modelMat.diffuse);
			if (modelMat.specular != null) material.setSpecularColor(modelMat.specular);

			if (modelMat.textures != null) {
				for (ModelTexture modelTex : modelMat.textures) {
					if (assetManager.isLoaded(modelTex.fileName, VulkanTexture.class)) {
						VulkanTexture texture = assetManager.get(modelTex.fileName, VulkanTexture.class);
						material.setDiffuseTexture(texture);
					}
				}
			}

			// --- THIS IS THE KEY ---
			// Find the vertex attributes for a mesh that uses this material
			VulkanVertexAttributes attributes = findAttributesForMaterial(data, materialId);
			if (attributes != null) {
				SimpleUnlitTextureShader shader = shaderCache.get(attributes);
				if (shader != null) {
					// Assign the pipeline from the cached shader to the material
					material.setPipelineBundle(shader.pipelineBundle);
				}
			}

			addMaterial(material);
			materialMap.put(materialId, material);
		}

		// 3. Create the final, renderable VulkanMeshParts
		for (ModelNode node : data.nodes) {
			if (node.parts != null) {
				for (ModelNodePart nodePart : node.parts) {
					ModelMeshPart meshPartData = findMeshPart(data, nodePart.meshPartId);
					if (meshPartData == null) continue;

					String meshIdToFind = node.meshId != null ? node.meshId : findMeshIdForPart(data, nodePart.meshPartId);
					VulkanMesh vulkanMesh = meshMap.get(meshIdToFind);
					VulkanMaterial vulkanMaterial = materialMap.get(nodePart.materialId);

					if (vulkanMesh != null && vulkanMaterial != null) {
						int partOffset = calculateMeshPartOffset(data, meshIdToFind, nodePart.meshPartId);
						int partSize = meshPartData.indices.length;

						VulkanMeshPart newPart = new VulkanMeshPart(
								nodePart.meshPartId, vulkanMesh, partOffset, partSize,
								meshPartData.primitiveType, vulkanMaterial
						);
						addMeshPart(newPart);
					}
				}
			}
		}
	}

	public void addMesh(VulkanMesh mesh) {
		if (mesh == null) throw new GdxRuntimeException("Mesh cannot be null");
		meshes.add(mesh);
	}

	public void addMaterial(VulkanMaterial material) {
		if (material == null) throw new GdxRuntimeException("Material cannot be null");
		materials.add(material);
	}

	public void addMeshPart(VulkanMeshPart meshPart) {
		if (meshPart == null) throw new GdxRuntimeException("MeshPart cannot be null");
		meshParts.add(meshPart);
	}

	@Override
	public void dispose() {
		for (VulkanMesh mesh : meshes) {
			if (mesh != null) mesh.dispose();
		}
		meshes.clear();

		// Dispose the shaders created by this model
		for (Disposable disposable : disposables) {
			disposable.dispose();
		}
		disposables.clear();

		materials.clear();
		meshParts.clear();
	}

	// Helper methods from before...
	private VulkanVertexAttributes findAttributesForMaterial(ModelData data, String materialId) {
		for (ModelNode node : data.nodes) {
			if (node.parts != null) {
				for (ModelNodePart part : node.parts) {
					if (part.materialId.equals(materialId)) {
						String meshId = node.meshId != null ? node.meshId : findMeshIdForPart(data, part.meshPartId);
						for (ModelMesh mesh : data.meshes) {
							String currentMeshId = mesh.id != null ? mesh.id : findMeshIdForPart(data, part.meshPartId);
							if (currentMeshId.equals(meshId)) {
								return new VulkanVertexAttributes(mesh.attributes);
							}
						}
					}
				}
			}
		}
		return data.meshes.size > 0 ? new VulkanVertexAttributes(data.meshes.get(0).attributes) : null;
	}

	private ModelMeshPart findMeshPart(ModelData data, String id) {
		for (ModelMesh mesh : data.meshes) {
			for (ModelMeshPart part : mesh.parts) {
				if (part.id.equals(id)) return part;
			}
		}
		return null;
	}

	private String findMeshIdForPart(ModelData data, String partId) {
		int meshIndex = 0;
		for (ModelMesh mesh : data.meshes) {
			for (ModelMeshPart part : mesh.parts) {
				if (part.id.equals(partId)) {
					return mesh.id != null ? mesh.id : "default_mesh_" + meshIndex;
				}
			}
			meshIndex++;
		}
		return null;
	}

	private int calculateMeshPartOffset(ModelData data, String meshId, String partId) {
		int meshIndex = 0;
		for (ModelMesh mesh : data.meshes) {
			String currentMeshId = mesh.id != null ? mesh.id : "default_mesh_" + meshIndex;
			if (currentMeshId.equals(meshId)) {
				int offset = 0;
				for (ModelMeshPart part : mesh.parts) {
					if (part.id.equals(partId)) return offset;
					offset += part.indices.length;
				}
			}
			meshIndex++;
		}
		return 0;
	}
}