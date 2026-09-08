package com.seibel.distanthorizons.core.render.renderer;

import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderObjectFactory;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.Assert.*;

/** Executes the actual callbacks registered by CloudRenderHandler, with no GPU or global bindings. */
public class CloudRenderCameraTest
{
	private boolean previousClouds;
	private boolean previousLayers;
	private int previousDistance;

	@Before public void configureClouds()
	{
		this.previousClouds = Config.Client.Advanced.Graphics.GenericRendering.enableCloudRendering.get();
		this.previousLayers = Config.Client.Advanced.Graphics.GenericRendering.enableMultiLayerClouds.get();
		this.previousDistance = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get();
		Config.Client.Advanced.Graphics.GenericRendering.enableCloudRendering.setWithoutFiringEvents(true);
		Config.Client.Advanced.Graphics.GenericRendering.enableMultiLayerClouds.setWithoutFiringEvents(true);
		Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.setWithoutFiringEvents(2048);
	}

	@After public void restoreConfiguration()
	{
		Config.Client.Advanced.Graphics.GenericRendering.enableCloudRendering.setWithoutFiringEvents(this.previousClouds);
		Config.Client.Advanced.Graphics.GenericRendering.enableMultiLayerClouds.setWithoutFiringEvents(this.previousLayers);
		Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.setWithoutFiringEvents(this.previousDistance);
	}

	@Test public void callbacksUseCurrentPassSnapshotForOriginsAndNeverRequeryCamera() throws Exception
	{
		Fixture fixture = new Fixture();
		RenderParams params = new RenderParams();
		DhVec3d snapshot = new DhVec3d(fixture.width * 7 + 37, 73, fixture.width * 9 + 23);
		params.exactCameraPosition = snapshot;
		fixture.render(params);
		assertEquals(363, fixture.groups.size());
		assertEquals(0, fixture.cameraQueries);
		Group center = fixture.group(0, 0, 0);
		assertEquals(fixture.width * 9.5, center.origin.z, 0);
		assertTrue(Math.abs(center.origin.x - fixture.width * 7.5) < fixture.width);
		assertEquals(356, center.origin.y, 0);
		assertSame(snapshot, params.exactCameraPosition);
		assertEquals(73, snapshot.y, 0); // Culling must not flatten the shared camera snapshot.

		// Reusing a RenderParams instance is normal; a cached previous-pass position would be stale.
		params.exactCameraPosition = new DhVec3d(-fixture.width * 3 - 17, 101, -fixture.width * 4 - 19);
		fixture.render(params);
		assertEquals(-fixture.width * 4.5, center.origin.z, 0);
		assertTrue(Math.abs(center.origin.x + fixture.width * 3.5) < fixture.width);
		assertEquals(0, fixture.cameraQueries);

		RenderParams anotherPass = new RenderParams();
		anotherPass.exactCameraPosition = new DhVec3d(0, 0, fixture.width * 2 + 9);
		fixture.render(anotherPass);
		assertEquals(fixture.width * 2.5, center.origin.z, 0);
		assertEquals(0, fixture.cameraQueries);
	}

	@Test public void realCallbacksPreserveCullingColorsAndEveryGroupsInvalidation() throws Exception
	{
		Fixture fixture = new Fixture();
		RenderParams params = new RenderParams();
		params.exactCameraPosition = new DhVec3d(0, 0, 0);
		fixture.color = new Color(71, 119, 173, 225);
		fixture.render(params);
		assertTrue("central 3x3 remains visible", fixture.group(0, 0, 0).active);
		assertTrue("forward cloud uses pass camera rather than distant wrapper camera", fixture.group(0, 0, 2).active);
		assertFalse("cloud entirely behind camera is culled", fixture.group(0, 0, -3).active);
		fixture.assertColorsAndInvalidations(1);

		fixture.render(params);
		fixture.assertColorsAndInvalidations(1); // No unnecessary rebuild while the color is unchanged.
		fixture.color = new Color(21, 31, 41, 205);
		fixture.render(params);
		fixture.assertColorsAndInvalidations(2); // Includes culled groups and shared box instances.

		fixture.lookZ = -1;
		fixture.render(params);
		assertFalse(fixture.group(0, 0, 2).active);
		assertTrue(fixture.group(0, 0, -3).active);
		fixture.lookZ = 1;
		Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.setWithoutFiringEvents(128);
		fixture.render(params);
		assertFalse("distance culling is retained", fixture.group(0, 0, 2).active);
		assertTrue("distance never removes central safety group", fixture.group(0, 0, 0).active);
		assertEquals(0, fixture.cameraQueries);
	}

	@Test public void disabledAndSingleLayerModesRetainTheirCallbackBehavior() throws Exception
	{
		Fixture fixture = new Fixture();
		DhApiRenderParam apiParams = new DhApiRenderParam();
		Config.Client.Advanced.Graphics.GenericRendering.enableCloudRendering.setWithoutFiringEvents(false);
		fixture.render(apiParams);
		for (Group group : fixture.groups)
		{
			assertFalse(group.active);
			assertEquals(0, group.invalidations);
		}
		assertEquals(0, fixture.cameraQueries);
		Config.Client.Advanced.Graphics.GenericRendering.enableCloudRendering.setWithoutFiringEvents(true);
		Config.Client.Advanced.Graphics.GenericRendering.enableMultiLayerClouds.setWithoutFiringEvents(false);
		fixture.cameraPosition = new DhVec3d(17, 33, fixture.width * 3 + 8);
		fixture.render(apiParams);
		assertEquals("API fallback queries once per active-layer callback", 121, fixture.cameraQueries);
		assertEquals(fixture.width * 3.5, fixture.group(0, 0, 0).origin.z, 0);
		for (int y = 1; y <= 2; y++)
			for (int x = -5; x <= 5; x++)
				for (int z = -5; z <= 5; z++)
				{
					assertFalse(fixture.group(y, x, z).active);
					assertEquals(0, fixture.group(y, x, z).invalidations);
				}
	}

	@Test public void missingSnapshotFallsBackWithoutRetainingAnEarlierPass() throws Exception
	{
		Fixture fixture = new Fixture();
		RenderParams params = new RenderParams();
		params.exactCameraPosition = new DhVec3d(0, 0, fixture.width * 8);
		Group center = fixture.group(0, 0, 0);
		center.beforeRender.accept(params);
		params.exactCameraPosition = null;
		fixture.cameraPosition = new DhVec3d(0, 0, fixture.width * 4 + 9);
		center.beforeRender.accept(params);
		assertEquals(fixture.width * 4.5, center.origin.z, 0);
		assertEquals(1, fixture.cameraQueries);
		assertSame(fixture.cameraPosition, CloudRenderHandler.getCameraPosition(null, fixture.renderWrapper));
		assertEquals(2, fixture.cameraQueries);
	}

	@Test public void allocationFreeCullingMatchesPreviousDecisionsAtBoundaries() throws Exception
	{
		Fixture fixture = new Fixture();
		CullingAccess culling = new CullingAccess(fixture);
		float[] origins = {0, -0.0f, 128, -128, Math.nextDown(6144f), 6144, Math.nextUp(6144f),
			-6144, 16_777_216, -16_777_216, Float.MIN_VALUE, Float.MAX_VALUE,
			Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
		DhVec3f[] looks = {new DhVec3f(), new DhVec3f(1, 0, 0), new DhVec3f(-1, 0, 0),
			new DhVec3f(0, 1, 0), new DhVec3f(0, -1, 0), new DhVec3f(0, 0, 1), new DhVec3f(0, 0, -1),
			new DhVec3f(3, -4, 5), new DhVec3f(0.0031622776f, 0, 0), new DhVec3f(Float.MIN_VALUE, 0, 0),
			new DhVec3f(Float.NaN, 1, 0), new DhVec3f(0, Float.POSITIVE_INFINITY, 0),
			new DhVec3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)};
		DhVec3d[] cameras = {new DhVec3d(), new DhVec3d(128, 356, 128),
			new DhVec3d(-30_000_000.25, 256, 30_000_000.75), new DhVec3d(Double.MIN_VALUE, -0.0, -Double.MIN_VALUE),
			new DhVec3d(Double.NaN, 0, 0), new DhVec3d(0, Double.POSITIVE_INFINITY, 0)};
		for (int radius : new int[] {128, 256, 2048})
		{
			Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.setWithoutFiringEvents(radius);
			for (float origin : origins)
				for (DhVec3f look : looks)
					for (DhVec3d camera : cameras)
					{
						culling.assertSameDecision(origin, 356, -origin, 1, 2, -2, camera, look);
						culling.assertSameDecision(origin, Float.NaN, origin, 16, 1, -1, camera, look);
					}
		}
	}

	@Test public void cullingScratchesAreOverwrittenForChangingCamerasAndDirections() throws Exception
	{
		Fixture fixture = new Fixture();
		CullingAccess culling = new CullingAccess(fixture);
		Random random = new Random(0x4d49524149L);
		for (int i = 0; i < 12_000; i++)
		{
			DhVec3d camera = new DhVec3d(random.nextDouble() * 60_000_000 - 30_000_000,
				random.nextDouble() * 2048 - 1024, random.nextDouble() * 60_000_000 - 30_000_000);
			float x = (float) (camera.x + random.nextDouble() * 100_000 - 50_000);
			float y = (float) (camera.y + random.nextDouble() * 2048 - 1024);
			float z = (float) (camera.z + random.nextDouble() * 100_000 - 50_000);
			DhVec3f look = new DhVec3f(random.nextFloat() * 4 - 2, random.nextFloat() * 4 - 2, random.nextFloat() * 4 - 2);
			culling.assertSameDecision(x, y, z, 1 + random.nextInt(257), random.nextInt(11) - 5,
				random.nextInt(11) - 5, camera, look);
		}
	}

	/** Invokes the production private method; the oracle retains the pre-optimization algorithm. */
	private static final class CullingAccess
	{
		final Fixture fixture;
		final Constructor<?> paramsConstructor;
		final Method method;

		CullingAccess(Fixture fixture) throws Exception
		{
			this.fixture = fixture;
			Class<?> paramsClass = Class.forName(CloudRenderHandler.class.getName() + "$CloudParams");
			this.paramsConstructor = paramsClass.getDeclaredConstructor(int.class, int.class, int.class, int.class);
			this.paramsConstructor.setAccessible(true);
			this.method = CloudRenderHandler.class.getDeclaredMethod("shouldCloudBeCulled", float.class, float.class,
				float.class, paramsClass, DhVec3d.class);
			this.method.setAccessible(true);
		}

		void assertSameDecision(float x, float y, float z, int textureWidth, int offsetX, int offsetZ,
				DhVec3d camera, DhVec3f look) throws Exception
		{
			DhVec3d cameraBefore = new DhVec3d(camera);
			DhVec3f lookBefore = new DhVec3f(look);
			this.fixture.sharedLook = look;
			boolean expected = previousCulling(x, y, z, textureWidth * 128, offsetX, offsetZ, camera, new DhVec3f(look));
			Object params = this.paramsConstructor.newInstance(textureWidth, 0, offsetX, offsetZ);
			boolean actual = (Boolean) this.method.invoke(this.fixture.handler, x, y, z, params, camera);
			assertEquals("Previous culling decision must be unchanged", expected, actual);
			assertEquals(Double.doubleToRawLongBits(cameraBefore.x), Double.doubleToRawLongBits(camera.x));
			assertEquals(Double.doubleToRawLongBits(cameraBefore.y), Double.doubleToRawLongBits(camera.y));
			assertEquals(Double.doubleToRawLongBits(cameraBefore.z), Double.doubleToRawLongBits(camera.z));
			assertEquals(Float.floatToRawIntBits(lookBefore.x), Float.floatToRawIntBits(look.x));
			assertEquals(Float.floatToRawIntBits(lookBefore.y), Float.floatToRawIntBits(look.y));
			assertEquals(Float.floatToRawIntBits(lookBefore.z), Float.floatToRawIntBits(look.z));
		}
	}

	private static boolean previousCulling(float x, float y, float z, int width, int offsetX, int offsetZ,
			DhVec3d cameraPos, DhVec3f cameraLookAtVector)
	{
		if (offsetX >= -1 && offsetX <= 1 && offsetZ >= -1 && offsetZ <= 1) return false;
		DhVec3d[] corners = {new DhVec3d(x, y, z), new DhVec3d(x, y, z + width),
			new DhVec3d(x + width, y, z), new DhVec3d(x + width, y, z + width)};
		cameraLookAtVector.normalize();
		double renderDistance = Math.max(Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get(), 256)
			* LodUtil.CHUNK_WIDTH * 1.5;
		boolean allOutsideRenderDistance = true;
		boolean allBehindCamera = true;
		for (DhVec3d corner : corners)
		{
			DhVec3d cornerNoHeight = new DhVec3d(corner);
			cornerNoHeight.y = 0;
			DhVec3d cameraPosNoHeight = new DhVec3d(cameraPos);
			cameraPosNoHeight.y = 0;
			if (cornerNoHeight.getDistance(cameraPosNoHeight) <= renderDistance) allOutsideRenderDistance = false;
			DhVec3f toCorner = new DhVec3f((float) (corner.x - cameraPos.x), (float) (corner.y - cameraPos.y),
				(float) (corner.z - cameraPos.z));
			toCorner.normalize();
			if (cameraLookAtVector.dotProduct(toCorner) > 0) allBehindCamera = false;
		}
		return allOutsideRenderDistance || allBehindCamera;
	}

	private static <T> T proxy(Class<T> type, InvocationHandler handler)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
	}

	private static Object defaultReturn(Method method)
	{
		if (method.getReturnType() == boolean.class) { return false; }
		if (method.getReturnType() == int.class) { return 0; }
		if (method.getReturnType() == long.class) { return 0L; }
		return null;
	}

	private static final class Group implements InvocationHandler
	{
		final List<DhApiRenderableBox> boxes;
		final IDhApiRenderableBoxGroup api;
		Consumer<DhApiRenderParam> beforeRender;
		DhApiVec3d origin;
		boolean active;
		int invalidations;
		int colorTraversals;

		Group(List<DhApiRenderableBox> boxes)
		{
			// Like the real group, copy the list and retain its shared box instances.
			this.boxes = new ArrayList<>(boxes);
			this.api = proxy(IDhApiRenderableBoxGroup.class, this);
		}

		@SuppressWarnings("unchecked")
		@Override public Object invoke(Object proxy, Method method, Object[] args)
		{
			switch (method.getName())
			{
				case "setPreRenderFunc": this.beforeRender = (Consumer<DhApiRenderParam>) args[0]; return null;
				case "setOriginBlockPos": this.origin = (DhApiVec3d) args[0]; return null;
				case "setActive": this.active = (Boolean) args[0]; return null;
				case "triggerBoxChange": this.invalidations++; return null;
				case "forEach": this.colorTraversals++; this.boxes.forEach((Consumer<DhApiRenderableBox>) args[0]); return null;
				case "iterator": throw new AssertionError("Color updates should traverse the group's backing list directly");
				default: return defaultReturn(method);
			}
		}
	}

	private static final class Fixture
	{
		final List<Group> groups = new ArrayList<>();
		final List<IDhApiRenderableBoxGroup> registered = new ArrayList<>();
		final IMinecraftRenderWrapper renderWrapper;
		final CloudRenderHandler handler;
		final int width;
		DhVec3d cameraPosition = new DhVec3d(10_000_000, 500, 10_000_000);
		Color color = new Color(71, 119, 173);
		float lookZ = 1;
		DhVec3f sharedLook;
		int cameraQueries;

		@SuppressWarnings("unchecked")
		Fixture() throws Exception
		{
			try (InputStream texture = CloudRenderHandler.class.getClassLoader().getResourceAsStream("assets/distanthorizons/textures/clouds.png"))
			{
				assertNotNull("Real cloud texture must be included in the test classpath", texture);
				this.width = ImageIO.read(texture).getWidth() * 128;
			}
			this.renderWrapper = proxy(IMinecraftRenderWrapper.class, (object, method, args) ->
			{
				if (method.getName().equals("getCameraExactPosition")) { this.cameraQueries++; return this.cameraPosition; }
				if (method.getName().equals("getLookAtVector")) { return this.sharedLook != null ? this.sharedLook : new DhVec3f(0, 0, this.lookZ); }
				return defaultReturn(method);
			});
			IClientLevelWrapper client = proxy(IClientLevelWrapper.class, (object, method, args) ->
			{
				if (method.getName().equals("getCloudColor")) { return this.color; }
				if (method.getName().equals("getMaxHeight")) { return 256; }
				return defaultReturn(method);
			});
			IDhClientLevel level = proxy(IDhClientLevel.class, (object, method, args) ->
			{
				if (method.getName().equals("getClientLevelWrapper") || method.getName().equals("getLevelWrapper")) { return client; }
				return defaultReturn(method);
			});
			IDhGenericRenderer renderer = proxy(IDhGenericRenderer.class, (object, method, args) ->
			{
				if (method.getName().equals("add")) { this.registered.add((IDhApiRenderableBoxGroup) args[0]); }
				return defaultReturn(method);
			});
			IDhApiCustomRenderObjectFactory factory = proxy(IDhApiCustomRenderObjectFactory.class, (object, method, args) ->
			{
				if (method.getName().equals("createRelativePositionedGroup"))
				{
					Group group = new Group((List<DhApiRenderableBox>) args[2]);
					this.groups.add(group);
					return group.api;
				}
				return defaultReturn(method);
			});
			this.handler = new CloudRenderHandler(level, renderer, this.renderWrapper, factory);
			assertEquals(363, this.registered.size());
		}

		Group group(int layer, int x, int z) { return this.groups.get((2 - layer) * 121 + (x + 5) * 11 + z + 5); }
		void render(DhApiRenderParam params) { for (Group group : this.groups) { group.beforeRender.accept(params); } }
		void assertColorsAndInvalidations(int expected)
		{
			for (Group group : this.groups)
			{
				assertFalse("Texture must produce nonempty cloud geometry", group.boxes.isEmpty());
				for (DhApiRenderableBox box : group.boxes) { assertEquals(this.color, box.color); }
				assertEquals(expected, group.invalidations);
				assertEquals(expected, group.colorTraversals);
			}
		}
	}
}
