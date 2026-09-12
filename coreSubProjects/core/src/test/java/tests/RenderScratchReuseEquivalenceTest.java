package tests;

import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;

/** Locks allocation-free render scratch math to the fresh-object formulation. */
public class RenderScratchReuseEquivalenceTest
{
    private static final int ITERATIONS = 2048;

    @Test public void setAndInvertMatchFreshMatricesBitForBit()
    {
        Random random = new Random(0x14A110C0L);
        DhMat4f scratch = new DhMat4f();
        for (int i = 0; i < ITERATIONS; i++)
        {
            DhMat4f source = affine(random);
            poison(scratch, random);
            scratch.set(source);
            assertBits(new DhMat4f(source), scratch);

            DhMat4f expectedInverse = new DhMat4f(source);
            expectedInverse.invert();
            scratch.set(source);
            scratch.invert();
            assertBits(expectedInverse, scratch);
        }
    }
    @Test public void projectionModelViewReuseMatchesFreshAllocationBitForBit()
    {
        Random random = new Random(0x14B22C0L);
        DhMat4f scratch = new DhMat4f();
        for (int i = 0; i < ITERATIONS; i++)
        {
            DhMat4f projection = affine(random);
            DhMat4f modelView = affine(random);
            DhMat4f expected = new DhMat4f(projection);
            expected.multiply(modelView);

            poison(scratch, random);
            scratch.set(projection);
            scratch.multiply(modelView);
            assertBits(expected, scratch);
        }
    }

    @Test public void directBoxTransformReuseMatchesFactoryPathBitForBit()
    {
        Random random = new Random(0x14C33C0L);
        DhMat4f projectionMvm = new DhMat4f();
        DhMat4f transform = new DhMat4f();
        DhMat4f scale = new DhMat4f();        for (int i = 0; i < ITERATIONS; i++)
        {
            DhMat4f projection = affine(random);
            DhMat4f modelView = affine(random);
            float tx = signed(random, 5000.0f);
            float ty = signed(random, 5000.0f);
            float tz = signed(random, 5000.0f);
            float sx = positive(random, 128.0f);
            float sy = positive(random, 128.0f);
            float sz = positive(random, 128.0f);

            DhMat4f expected = new DhMat4f(projection);
            expected.multiply(modelView);
            DhMat4f expectedTransform = DhMat4f.createTranslateMatrix(tx, ty, tz);
            expectedTransform.multiply(DhMat4f.createScaleMatrix(sx, sy, sz));
            expected.multiply(expectedTransform);

            poison(projectionMvm, random);
            poison(transform, random);
            poison(scale, random);
            projectionMvm.set(projection);
            projectionMvm.multiply(modelView);            transform.setIdentity();
            transform.m03 = tx;
            transform.m13 = ty;
            transform.m23 = tz;
            scale.setIdentity();
            scale.m00 = sx;
            scale.m11 = sy;
            scale.m22 = sz;
            transform.multiply(scale);
            projectionMvm.multiply(transform);
            assertBits(expected, projectionMvm);
        }
    }

    private static DhMat4f affine(Random random)
    {
        DhMat4f matrix = new DhMat4f();
        matrix.setIdentity();
        matrix.m00 = positive(random, 4.0f);
        matrix.m11 = positive(random, 4.0f);
        matrix.m22 = positive(random, 4.0f);
        matrix.m03 = signed(random, 1000.0f);
        matrix.m13 = signed(random, 1000.0f);
        matrix.m23 = signed(random, 1000.0f);
        return matrix;
    }
    private static void poison(DhMat4f matrix, Random random)
    {
        matrix.m00 = signed(random, 9.0f); matrix.m01 = signed(random, 9.0f);
        matrix.m02 = signed(random, 9.0f); matrix.m03 = signed(random, 9.0f);
        matrix.m10 = signed(random, 9.0f); matrix.m11 = signed(random, 9.0f);
        matrix.m12 = signed(random, 9.0f); matrix.m13 = signed(random, 9.0f);
        matrix.m20 = signed(random, 9.0f); matrix.m21 = signed(random, 9.0f);
        matrix.m22 = signed(random, 9.0f); matrix.m23 = signed(random, 9.0f);
        matrix.m30 = signed(random, 9.0f); matrix.m31 = signed(random, 9.0f);
        matrix.m32 = signed(random, 9.0f); matrix.m33 = signed(random, 9.0f);
    }

    private static float positive(Random random, float scale)
    { return 0.25f + random.nextFloat() * scale; }

    private static float signed(Random random, float scale)
    { return (random.nextFloat() * 2.0f - 1.0f) * scale; }

    private static void assertBits(DhApiMat4f expected, DhApiMat4f actual)
    {
        float[] a = expected.getValuesAsArray();
        float[] b = actual.getValuesAsArray();
        for (int i = 0; i < 16; i++)
        { assertEquals("matrix element " + i, Float.floatToIntBits(a[i]), Float.floatToIntBits(b[i])); }
    }
}
