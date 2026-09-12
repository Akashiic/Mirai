package tests;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;

/** Locks the 0.1.12 bilateral SSAO blur math while spatial Gaussian values are reused. */
public class SsaoBlurEquivalenceTest
{
    private static final float SIGMA = 1.6f;

    @Test
    public void cachedSpatialGaussianIsBitIdenticalToRepeatedEvaluation()
    {
        Random random = new Random(0x5AA01213L);
        for (int radius = 1; radius <= 3; radius++)
        {
            for (int iteration = 0; iteration < 512; iteration++)
            {
                float[][] values = new float[7][7];
                float[][] depthDiffs = new float[7][7];
                fill(random, values, depthDiffs);
                float baseline = baseline(radius, values, depthDiffs);
                float optimized = cached(radius, values, depthDiffs);
                assertEquals(Float.floatToIntBits(baseline), Float.floatToIntBits(optimized));
            }
        }
    }

    private static void fill(Random random, float[][] values, float[][] depthDiffs)
    {
        for (int y = 0; y < 7; y++)
        {
            for (int x = 0; x < 7; x++)
            {
                values[y][x] = random.nextFloat();
                depthDiffs[y][x] = (random.nextFloat() - 0.5f) * 256.0f;
            }
        }
    }

    private static float gaussian(float sigma, float x)
    {
        return (float)Math.exp(-(x * x) / (2.0f * (sigma * sigma)));
    }

    private static float baseline(int radius, float[][] values, float[][] depthDiffs)
    {
        float accum = 0.0f;
        float total = 0.0f;
        for (int iy = -radius; iy <= radius; iy++)
        {
            float fy = gaussian(SIGMA, iy);
            for (int ix = -radius; ix <= radius; ix++)
            {
                float fx = gaussian(SIGMA, ix);
                float fv = gaussian(SIGMA, Math.abs(depthDiffs[iy + 3][ix + 3]));
                float weight = fx * fy * fv;
                accum += weight * values[iy + 3][ix + 3];
                total += weight;
            }
        }
        return total <= 1.e-4f ? 1.0f : accum / total;
    }

    private static float cached(int radius, float[][] values, float[][] depthDiffs)
    {
        float[] spatial = new float[4];
        for (int i = 0; i <= 3; i++) { spatial[i] = gaussian(SIGMA, i); }
        float accum = 0.0f;
        float total = 0.0f;
        for (int iy = -radius; iy <= radius; iy++)
        {
            float fy = spatial[Math.abs(iy)];
            for (int ix = -radius; ix <= radius; ix++)
            {
                float fx = spatial[Math.abs(ix)];
                float fv = gaussian(SIGMA, Math.abs(depthDiffs[iy + 3][ix + 3]));
                float weight = fx * fy * fv;
                accum += weight * values[iy + 3][ix + 3];
                total += weight;
            }
        }
        return total <= 1.e-4f ? 1.0f : accum / total;
    }
}
