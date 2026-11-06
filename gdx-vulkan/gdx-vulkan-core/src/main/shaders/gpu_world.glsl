// The "world" buffer of VulkanGpuInstances (plan, Phase 5): one resource buffer holds everything the GPU-driven passes share, bound as Set 0's storage
// binding by the compute shaders and by the draw. Addressed in uints; floats are read with uintBitsToFloat. Offsets are in uints from the buffer start.
//
//   header (HEADER_UINTS), rewritten by the CPU every frame (the shadow block only does something when setShadow was called):
//     H_VIEWPROJ  16 floats  camera.combined (columns)
//     H_VIEW      16 floats  camera.view (columns)
//     H_CAMERA     4 floats  camera position xyz, near
//     H_PROJ       4 floats  far, projection scale x (P00), projection scale y (P11), bounding radius of the mesh
//     H_COUNTS     4 uints   instance count, light count, screen width, screen height
//     H_CLUSTER    4 uints   clusters in x, y, z, maximum lights per cluster
//     H_OFFSETS    4 uints   instances, visible list, indirect command, lights
//     H_OFFSETS2   4 uints   cluster lists, mesh index count, first user uint, -
//     H_SHADOW    76 uints   4 x 16 floats: the shadow cascades' projection * view matrices (cascade 0 is the plain shadow map's); 4 floats: the cascade split
//                            distances (cascade 1 starts at the first, ...); 4 ints: the texture slots of the cascades' depth maps; 4 uints: PCF offset (float), cascade count
//                            (0: no shadows), -, -
//   instances      12 uints each: {x, y, z, scale}, {quaternion x, y, z, w}, {user state, 4 floats}
//   visible list   one uint per instance: the indices that passed the frustum test (written by gpu_cull.comp)
//   indirect       5 uints: VkDrawIndexedIndirectCommand {indexCount, instanceCount, firstIndex, vertexOffset, firstInstance}
//   lights         8 floats each: {x, y, z, radius}, {r, g, b, intensity}
//   cluster lists  1 + maxPerCluster uints each: the count, then the light indices (written by light_cull.comp)
//   user region    free for the application's own compute shaders (a boids simulation's grid, say)
#ifndef GDX_WORLD_GLSL
#define GDX_WORLD_GLSL

const uint H_VIEWPROJ = 0u, H_VIEW = 16u, H_CAMERA = 32u, H_PROJ = 36u, H_COUNTS = 40u, H_CLUSTER = 44u, H_OFFSETS = 48u, H_OFFSETS2 = 52u,
    H_SHADOW = 56u, H_SHADOW_SPLITS = 120u, H_SHADOW_SLOTS = 124u, H_SHADOW_PARAMS = 128u, HEADER_UINTS = 132u;
const uint INSTANCE_UINTS = 12u;

#endif
