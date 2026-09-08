package com.seibel.distanthorizons.core.render.renderer.cullingFrustum;

import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.*;

/** Differential tests against the pre-0.1.5 culling path using the real JOML. */
public class DhFrustumBoundsTest
{
	private static DhMat4f fromJoml(Matrix4f matrix)
	{
		DhMat4f result = new DhMat4f();
		result.set(matrix);
		return result;
	}

	private static final class PreviousFrustum
	{
		private final FrustumIntersection frustum = new FrustumIntersection();
		private final Vector3f boundsMin = new Vector3f();
		private final Vector3f boundsMax = new Vector3f();
		private int minY;
		private int maxY;

		void update(int minY, int maxY, DhApiMat4f matrix)
		{
			this.minY = minY;
			this.maxY = maxY;
			Matrix4f projection = new Matrix4f(DhMat4f.createJomlMatrix(matrix));
			this.frustum.set(projection);
			new Matrix4f(projection).invert().frustumAabb(this.boundsMin, this.boundsMax);
		}

		boolean intersects(int x, int z, int width)
		{
			return this.frustum.testAab(new Vector3f(x, this.minY, z),
				new Vector3f(x + width, this.maxY, z + width));
		}
	}

	@Test
	public void perspectiveScenesPreserveEveryVisibilityDecision()
	{
		Random random = new Random(0x4d49524149L);
		DhFrustumBounds current = new DhFrustumBounds();
		PreviousFrustum previous = new PreviousFrustum();
		int[][] dimensions = {{0, 256}, {0, 128}, {-64, 320}, {-2048, 2048}};
		float[] aspects = {16f / 9f, 4f / 3f, 9f / 16f, 32f / 9f};
		for (int scene = 0; scene < 128; scene++)
		{
			int[] height = dimensions[scene % dimensions.length];
			float cameraX = (scene % 8 == 0 ? 29_990_000 : random.nextInt(65536) - 32768);
			float cameraZ = (scene % 8 == 1 ? -29_990_000 : random.nextInt(65536) - 32768);
			float cameraY = height[0] + random.nextFloat() * (height[1] - height[0]);
			Matrix4f matrix = new Matrix4f().perspective(
				(float) Math.toRadians(30 + random.nextInt(111)), aspects[scene % aspects.length],
				0.1f, 8192f).rotateX(random.nextFloat() * 3f - 1.5f)
				.rotateY(random.nextFloat() * 6.28f).translate(-cameraX, -cameraY, -cameraZ);
			DhMat4f dhMatrix = fromJoml(matrix);
			previous.update(height[0], height[1], dhMatrix);
			current.update(height[0], height[1], dhMatrix);
			for (int section = 0; section < 1024; section++)
			{
				int width = 1 << (4 + random.nextInt(7));
				int x = (int) cameraX + random.nextInt(18000) - 9000;
				int z = (int) cameraZ + random.nextInt(18000) - 9000;
				assertEquals("scene " + scene + ", section " + section,
					previous.intersects(x, z, width), current.intersects(x, z, width, 6));
			}
			// Camera-inside-column case, including camera at large negative coordinates.
			int x = (int) Math.floor(cameraX) - 8, z = (int) Math.floor(cameraZ) - 8;
			assertEquals(previous.intersects(x, z, 16), current.intersects(x, z, 16, 4));
		}
	}

	@Test
	public void exactPlaneAndAdjacentFloatBoundariesRemainInclusive()
	{
		DhFrustumBounds current = new DhFrustumBounds();
		PreviousFrustum previous = new PreviousFrustum();
		float[] scales = {Math.nextAfter(1f, 0d), 1f, Math.nextAfter(1f, 2d)};
		for (float scale : scales)
		{
			DhMat4f matrix = fromJoml(new Matrix4f().scaling(scale));
			previous.update(-1, 1, matrix);
			current.update(-1, 1, matrix);
			for (int x = -3; x <= 3; x++)
				for (int z = -3; z <= 3; z++)
					for (int width = 0; width <= 3; width++)
						assertEquals(previous.intersects(x, z, width), current.intersects(x, z, width, 0));
		}
		current.update(-1, 1, fromJoml(new Matrix4f()));
		assertTrue("exact plane contact is retained", current.intersects(1, 0, 0, 0));
		assertFalse("box outside plane remains culled", current.intersects(2, 0, 0, 0));
	}

	@Test
	public void updatingAnotherFrustumCannotChangeThisInstancesPlanes()
	{
		DhFrustumBounds first = new DhFrustumBounds(), second = new DhFrustumBounds();
		DhMat4f input = fromJoml(new Matrix4f());
		first.update(-1, 1, input);
		second.update(500, 600, fromJoml(new Matrix4f().translation(100, 100, 100)));
		input.m00 = Float.NaN;
		assertTrue("no shared scratch/caller matrix state", first.intersects(0, 0, 1, 0));
		assertFalse(second.intersects(0, 0, 1, 0));
		first.update(500, 600, fromJoml(new Matrix4f()));
		assertFalse("dimension height replaced", first.intersects(0, 0, 1, 0));
		first.update(-1, 1, fromJoml(new Matrix4f()));
		assertTrue("returning to dimension refreshes planes", first.intersects(0, 0, 1, 0));
	}

	@Test
	public void unusualMatricesPreserveExistingCullingSemantics()
	{
		Matrix4f[] matrices = {new Matrix4f().zero(), new Matrix4f().scaling(-1f, 1f, -1f),
			new Matrix4f().ortho(-512, 512, -256, 256, 0.1f, 4096f),
			new Matrix4f().perspective((float) Math.toRadians(70), 1.5f, 4096f, 0.1f)};
		DhFrustumBounds current = new DhFrustumBounds();
		PreviousFrustum previous = new PreviousFrustum();
		for (Matrix4f matrix : matrices)
		{
			DhMat4f dhMatrix = fromJoml(matrix);
			current.update(-64, 320, dhMatrix);
			previous.update(-64, 320, dhMatrix);
			for (int x = -4096; x <= 4096; x += 128)
				for (int z = -4096; z <= 4096; z += 128)
					assertEquals(previous.intersects(x, z, 64), current.intersects(x, z, 64, 6));
		}
	}
}
