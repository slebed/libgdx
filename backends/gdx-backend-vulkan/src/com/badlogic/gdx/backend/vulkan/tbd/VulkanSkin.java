
package com.badlogic.gdx.backend.vulkan.tbd; // Example package, adjust as needed

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backend.vulkan.VulkanDevice;
import com.badlogic.gdx.files.FileHandle;
// Needed for casting
import com.badlogic.gdx.scenes.scene2d.ui.Skin;

// Import Vulkan specifics

/** A Skin implementation for Vulkan that intercepts TextureAtlas creation to ensure VulkanTextures are used instead of default GL
 * Textures.
 * <p>
 * WARNING: This implementation manually replicates TextureAtlas population and might be fragile or incomplete compared to using
 * AssetManager with custom loaders. */
public class VulkanSkin extends Skin {

	private final VulkanDevice device; // Need device/allocator for texture loading
	private final long vmaAllocator;

	/** Creates a Vulkan-aware skin. It requires the Vulkan context to load textures correctly.
	 *
	 * @param skinFile The FileHandle for the skin JSON file.
	 * @param device The active VulkanDevice.
	 * @param vmaAllocator The active VMA Allocator. */
	public VulkanSkin (FileHandle skinFile, VulkanDevice device, long vmaAllocator) {
		// Call the super constructor that takes the skin file.
		// This constructor will eventually call load(skinFile).
		// load(skinFile) uses a Json loader which, when encountering an atlas file path,
		// should call our overridden newTextureAtlas method below.
		super(skinFile);

		if (device == null || vmaAllocator == 0) {
			throw new IllegalArgumentException("VulkanDevice and VMA Allocator cannot be null/zero for VulkanSkin.");
		}
		this.device = device;
		this.vmaAllocator = vmaAllocator;

		// Note: At this point, the base class constructor has run load(),
		// which *should* have called our overridden newTextureAtlas.
		// We don't need to call load() again here. Check base class source if issues arise.
		if (getAtlas() == null) {
			// This might happen if the JSON didn't specify an atlas or newTextureAtlas failed silently
			Gdx.app.log("VulkanSkin", "Warning: Skin loaded, but no TextureAtlas was found or created for: " + skinFile);
		}
	}

	/** Overridden from Skin. Called during JSON loading when a TextureAtlas dependency is found. This implementation manually
	 * loads VulkanTextures for atlas pages and constructs a TextureAtlas populated with them. */
	/*
	 * @Override protected TextureAtlas newTextureAtlas(FileHandle atlasFile) { Gdx.app.log("VulkanSkin",
	 * "Overridden newTextureAtlas called for: " + atlasFile.path()); if (!atlasFile.exists()) { throw new
	 * GdxRuntimeException("Atlas file not found: " + atlasFile); }
	 * 
	 * // --- Manual Atlas Population ---
	 * 
	 * // 1. Load Atlas Data (defines pages and regions) // Use false for flip - flipping is usually handled by texture coordinates
	 * or draw calls. TextureAtlasData data = new TextureAtlasData(atlasFile, atlasFile.parent(), false);
	 * 
	 * // 2. Create an empty TextureAtlas to populate // This atlas will hold our VulkanTextures TextureAtlas atlas = new
	 * TextureAtlas();
	 * 
	 * // Keep track of loaded textures to add them to the atlas disposal list ObjectMap<TextureAtlasData.Page, VulkanTexture>
	 * loadedPageTextures = new ObjectMap<>();
	 * 
	 * try { // 3. Load VulkanTextures for each unique page defined in the data for (TextureAtlasData.Page page : data.getPages())
	 * { // TextureAtlasData reuses page objects if texture files are the same. // Only load if we haven't processed this page
	 * description before. if (page.texture == null) { if (page.textureFile == null) { throw new
	 * GdxRuntimeException("Atlas page textureFile is null for atlas: " + atlasFile); } Gdx.app.debug("VulkanSkin",
	 * "Loading VulkanTexture for page: " + page.textureFile.path()); VulkanTexture vulkanTexture =
	 * VulkanTexture.loadFromFile(page.textureFile, this.device, this.vmaAllocator);
	 * 
	 * // Store the VulkanTexture on the page object. TextureAtlasData uses this 'texture' field. // We cast because the field type
	 * is Texture, but we know we put a VulkanTexture here. page.texture = (Texture) vulkanTexture;
	 * 
	 * // Add it to our tracking map and the atlas's internal set for disposal. loadedPageTextures.put(page, vulkanTexture);
	 * atlas.getTextures().add(vulkanTexture); // Add the VulkanTexture itself } }
	 * 
	 * // 4. Create AtlasRegions and add them to the atlas for (TextureAtlasData.Region regionData : data.getRegions()) { int width
	 * = regionData.width; int height = regionData.height; // Create the AtlasRegion using the VulkanTexture stored on the page
	 * TextureAtlas.AtlasRegion atlasRegion = new TextureAtlas.AtlasRegion( (Texture) regionData.page.texture, // Get the texture
	 * (our VulkanTexture) from the page regionData.left, regionData.top, // Handle rotation: if rotated, swap width/height for the
	 * AtlasRegion regionData.rotate ? height : width, regionData.rotate ? width : height );
	 * 
	 * // --- Manually copy all other properties --- atlasRegion.index = regionData.index; atlasRegion.name = regionData.name;
	 * atlasRegion.offsetX = regionData.offsetX; atlasRegion.offsetY = regionData.offsetY; atlasRegion.originalWidth =
	 * regionData.originalWidth; atlasRegion.originalHeight = regionData.originalHeight; atlasRegion.rotate = regionData.rotate;
	 * atlasRegion.degrees = regionData.degrees; // Added in newer libGDX versions atlasRegion.names = regionData.names;
	 * atlasRegion.values = regionData.values; // --- END CORRECTION ---
	 * 
	 * // Apply flip if needed if (regionData.flip) atlasRegion.flip(false, true);
	 * 
	 * // Add the fully configured region to the atlas atlas.addRegion(regionData.name, atlasRegion); }
	 * 
	 * Gdx.app.log("VulkanSkin", "Manually populated TextureAtlas with VulkanTextures for: " + atlasFile.path()); return atlas;
	 * 
	 * } catch (Exception e) { // Clean up textures loaded so far if population failed Gdx.app.error("VulkanSkin",
	 * "Failed during manual TextureAtlas population for: " + atlasFile.path(), e); for(VulkanTexture tex :
	 * loadedPageTextures.values()) { if (tex != null) tex.dispose(); } // Also dispose the atlas itself? It should be empty or
	 * partially populated. if(atlas != null) atlas.dispose(); // Should dispose textures added to its set throw new
	 * GdxRuntimeException("Failed to create Vulkan TextureAtlas", e); } }
	 */

	/** Overridden dispose method. The base Skin dispose will dispose the TextureAtlas added to its resources. Since TextureAtlas
	 * dispose calls dispose on its contained Textures, and our VulkanTexture.dispose handles Vulkan resources correctly, this
	 * *should* be sufficient. */
	@Override
	public void dispose () {
		Gdx.app.log("VulkanSkin", "Disposing skin...");
		// The atlas created by our overridden newTextureAtlas is added to the
		// skin's resources map. The base super.dispose() iterates this map
		// and calls dispose() on disposable resources, including the TextureAtlas.
		// TextureAtlas.dispose() iterates its texture set and calls dispose()
		// on each Texture (which will be our VulkanTexture instances).
		super.dispose();
		Gdx.app.log("VulkanSkin", "Skin disposed.");
	}
}
