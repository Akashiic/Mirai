#version 330 core

in vec2 texCoord;

out vec4 fragColor;

uniform sampler2D gSSAOMap;
uniform sampler2D gDepthMap;
uniform vec2 gViewSize;
uniform int gBlurRadius;
uniform float gNear;
uniform float gFar;


float linearizeDepth(const in float depth) {
    return (gNear * gFar) / (depth * (gNear - gFar) + gFar);
}

float Gaussian(const in float sigma, const in float x) {
    return exp(-(x*x) / (2.0 * (sigma*sigma)));
}

float SpatialGaussianWeight(const in int offset, const in float w0, const in float w1, const in float w2, const in float w3) {
    int distance = abs(offset);
    if (distance == 0) return w0;
    if (distance == 1) return w1;
    if (distance == 2) return w2;
    return w3;
}

float BilateralGaussianBlur(const in vec2 texcoord, const in float linearDepth, const in float g_sigmaV) {
    float g_sigmaX = 1.6;

    int radius = clamp(gBlurRadius, 1, 3);

    // Spatial Gaussian terms depend only on the integer tap offset.
    // Cache the exact same Gaussian evaluations once per fragment instead of
    // recomputing them for every tap in the 2D kernel.
    // X and Y use the exact same sigma (1.6), so their spatial weights are identical.
    float g0 = Gaussian(g_sigmaX, 0.0);
    float g1 = Gaussian(g_sigmaX, 1.0);
    float g2 = Gaussian(g_sigmaX, 2.0);
    float g3 = Gaussian(g_sigmaX, 3.0);
    
    vec2 pixelSize = 1.0 / gViewSize;

    float accum = 0.0;
    float total = 0.0;
    for (int iy = -radius; iy <= radius; iy++) {
        float fy = SpatialGaussianWeight(iy, g0, g1, g2, g3);

        for (int ix = -radius; ix <= radius; ix++) {
            float fx = SpatialGaussianWeight(ix, g0, g1, g2, g3);

            vec2 sampleTex = texcoord + ivec2(ix, iy) * pixelSize;
            float sampleValue = textureLod(gSSAOMap, sampleTex, 0).r;
            float sampleDepth = textureLod(gDepthMap, sampleTex, 0).r;
            float sampleLinearDepth = linearizeDepth(sampleDepth);

            float depthDiff = abs(sampleLinearDepth - linearDepth);
            float fv = Gaussian(g_sigmaV, depthDiff);

            float weight = fx*fy*fv;
            accum += weight * sampleValue;
            total += weight;
        }
    }

    if (total <= 1.e-4) return 1.0;
    return accum / total;
}


void main()
{
    fragColor = vec4(1.0);
    
    float fragmentDepth = textureLod(gDepthMap, texCoord, 0).r;

    // a fragment depth of "1" means the fragment wasn't drawn to,
    // we only want to apply SSAO to LODs, not to the sky outside the LODs
    if (fragmentDepth < 1) 
    {
        if (gBlurRadius > 0) 
        {
            float fragmentDepthLinear = linearizeDepth(fragmentDepth);
            fragColor.a = BilateralGaussianBlur(texCoord, fragmentDepthLinear, 1.6);
        }
        else 
        {
            fragColor.a = texelFetch(gSSAOMap, ivec2(gl_FragCoord.xy), 0).r;
        }
    }
}
