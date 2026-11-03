// The command stream (plan, Phase 2): Java encodes packets into a direct ByteBuffer and one JNI call replays them into the frame's
// command buffer. A packet is an int32 opcode followed by its int32 operands (floats are stored as their bit pattern). Little-endian.
// Mirrored by io.github.slebed.gdxvulkan.CommandStream: keep the two in step.
//
// State follows OpenGL's conventions (the shim writes it): viewport and scissor rectangles are in pixels with the origin at the
// bottom-left of the target; the replay converts to Vulkan (plan, Core decision 6).
#pragma once

#include <cstdint>

namespace gdxvk {
namespace cmd {

enum Op : int32_t {
    BindPipeline = 1,   // id
    PushConstants = 2,  // 32 words: the whole 128-byte block
    Viewport = 3,       // x, y, w, h
    Scissor = 4,        // x, y, w, h
    ScissorEnable = 5,  // 0 / 1
    Clear = 6,          // mask (GL bits), r, g, b, a, depth (float bits), stencil. Colour, depth and stencil; respects the scissor
    BindVertex = 7,     // ring block, offset lo, offset hi
    BindIndexBuffer = 8, // resource buffer id, offset lo, offset hi, index type (0 = u16, 1 = u32)
    BindIndexRing = 9,  // ring block, offset lo, offset hi, index type
    BindTexture = 10,   // texture id (Tier B binds its descriptor set; Tier A needs nothing: the slot travels in the vertices)
    DrawIndexed = 11,   // index count, first index, vertex offset
    Draw = 12,          // vertex count, first vertex
    BindStorage = 13,   // resource buffer id: Set 0's storage binding reads that buffer (whole range) instead of the frame ring's
    BindTarget = 14,    // render-target texture id (or -1 for the swapchain), depth image id (or -1). Ends the open pass; the next starts at the first draw/clear
    DepthState = 15,    // test enable, write enable, compare func (GL enum)
    StencilState = 16,  // test enable, func (GL), ref, compare mask, write mask, sfail, dpfail, dppass (GL enums)
};

constexpr int32_t kGlColorBufferBit = 0x00004000, kGlDepthBufferBit = 0x00000100, kGlStencilBufferBit = 0x00000400;

} // namespace cmd
} // namespace gdxvk
