#version 450

// Inputs from Vertex Buffer
layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;     // Normals are now required
layout(location = 2) in vec2 inTexCoord;   // TexCoords might shift location if normal is added

// Uniform Buffer Objects (UBOs)
layout(set = 0, binding = 0) uniform TransformUBO {
    mat4 model;
    mat4 view;
    mat4 proj;
} transformData;

// Outputs to Fragment Shader
layout(location = 0) out vec3 fragPosition_viewspace;
layout(location = 1) out vec3 fragNormal_viewspace;
layout(location = 2) out vec2 fragTexCoord;

void main() {
    // Calculate position in clip space
    gl_Position = transformData.proj * transformData.view * transformData.model * vec4(inPosition, 1.0);

    // Calculate position in view space (for lighting calculations in fragment shader)
    fragPosition_viewspace = vec3(transformData.view * transformData.model * vec4(inPosition, 1.0));

    // Transform normal to view space
    // Use the upper-left 3x3 of the inverse transpose of the model-view matrix for normals
    // This correctly handles non-uniform scaling.
    // If only uniform scaling, mat3(transformData.view * transformData.model) * inNormal would also work.
    mat3 normalMatrix = mat3(transpose(inverse(transformData.view * transformData.model)));
    fragNormal_viewspace = normalize(normalMatrix * inNormal);

    // Pass texture coordinates through
    fragTexCoord = inTexCoord;
}
