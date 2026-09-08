#version 330 core

in vec2 texCoord;

out vec4 fragColor;

// inverted model view matrix and projection matrix
uniform mat4 uDhInvMvmProj;

uniform sampler2D uDhDepthTexture;
uniform sampler2D uMcColorTexture;
uniform sampler2D uDhColorTexture;

uniform float uStartFadeBlockDistance;
uniform float uEndFadeBlockDistance;



vec3 calcViewPosition(float fragmentDepth, mat4 invMvmProj) 
{
    // normalized device coordinates
    vec4 ndc = vec4(texCoord.xy, fragmentDepth, 1.0);
    ndc.xyz = ndc.xyz * 2.0 - 1.0;

    vec4 eyeCoord = invMvmProj * ndc;
    return eyeCoord.xyz / eyeCoord.w;
}

/**
 * Composes uncovered Minecraft pixels and fades distant DH terrain back
 * into Minecraft's image before the final, non-blended texture copy.
 */
void main() 
{
    // includes both the vanilla chunks as well as DH
    vec4 combinedMcDhColor = texture(uMcColorTexture, texCoord);
    // just the DH render pass
    vec4 dhColor = texture(uDhColorTexture, texCoord);



    // ignore anything that DH hasn't drawn to
    if (dhColor.a == 0.0f)
    {
        // if not done vanilla clouds will render incorrectly at night
        fragColor = vec4(combinedMcDhColor.rgb, 1.0);
        return;
    }
    
    
    float dhFragmentDepth = texture(uDhDepthTexture, texCoord).r;
    vec3 dhVertexWorldPos = calcViewPosition(dhFragmentDepth, uDhInvMvmProj);
    float dhFragmentDistance = length(dhVertexWorldPos.xzy);
    
    
    // Smoothly transition between combinedMcDhColor and uDhColorTexture
    // as the depth increases from the camera. Reversed smoothstep edges have
    // undefined GLSL results; invert the result, not the ordered edges.
    float fadeStep = 1.0 - smoothstep(uStartFadeBlockDistance, uEndFadeBlockDistance, dhFragmentDistance);
    fragColor = mix(combinedMcDhColor, dhColor, fadeStep);
    fragColor.a = 1.0;
    
}
