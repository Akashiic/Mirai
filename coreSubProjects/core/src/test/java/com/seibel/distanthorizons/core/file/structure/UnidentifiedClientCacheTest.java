package com.seibel.distanthorizons.core.file.structure;

import org.junit.Assert;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Map;

public class UnidentifiedClientCacheTest
{
	@Test public void separatesEndpointsAndWorldInstancesAndRetainsSameWorldPath() throws Exception
	{
		try (UnidentifiedClientCache cache = new UnidentifiedClientCache())
		{
			Object world = new Object();
			File first = cache.folder("proxy.example:25565", world, "0000000000000@overworld:0");
			Assert.assertEquals(first, cache.folder("PROXY.EXAMPLE:25565", world, "0000000000000@overworld:0"));
			Assert.assertNotEquals(first, cache.folder("proxy.example:25566", world, "0000000000000@overworld:0"));
			Assert.assertNotEquals(first, cache.folder("proxy.example:25565", new Object(), "0000000000000@overworld:0"));
		}
	}

	@Test public void ownerCloseDeletesOnlyItsTemporaryDataAndIsIdempotent() throws Exception
	{
		Path outside = Files.createTempFile("akashic-cache-outside-", ".txt");
		try
		{
			UnidentifiedClientCache cache = new UnidentifiedClientCache();
			File folder = cache.folder("proxy.example", new Object(), "overworld:0");
			Files.createDirectories(folder.toPath());
			Files.write(folder.toPath().resolve("cache.sqlite"), new byte[] { 1 });
			try { Files.createSymbolicLink(folder.toPath().resolve("outside-link"), outside); }
			catch (UnsupportedOperationException | java.io.IOException | SecurityException ignored) { }
			cache.close(); cache.close();
			Assert.assertFalse(folder.exists()); Assert.assertTrue(Files.exists(outside));
			try { cache.folder("proxy.example", new Object(), "overworld:0"); Assert.fail("Closed storage accepted new world"); }
			catch (IllegalStateException expected) { }
		}
		finally { Files.deleteIfExists(outside); }
	}

	@Test public void endpointHashDoesNotCollapseRemovedCharacters()
	{
		Assert.assertNotEquals(UnidentifiedClientCache.endpointKey("[::1]:25565"), UnidentifiedClientCache.endpointKey("1:25565"));
		Assert.assertNotEquals(UnidentifiedClientCache.endpointKey("host:25565"), UnidentifiedClientCache.endpointKey("host:25566"));
	}

	@Test public void temporaryNamesHaveBoundedLengthRegardlessOfServerName() throws Exception
	{
		try (UnidentifiedClientCache cache = new UnidentifiedClientCache())
		{
			Path path = cache.folder(new String(new char[10000]), new Object(), new String(new char[10000])).toPath();
			Path ownedRoot = path.getParent().getParent().getParent();
			Assert.assertTrue(ownedRoot.relativize(path).toString().length() <= 102);
			Assert.assertEquals(64, UnidentifiedClientCache.endpointKey("proxy.example").length());
		}
	}
	@Test public void equalWorldObjectsStillReceiveDifferentIdentityNamespaces() throws Exception
	{
		try (UnidentifiedClientCache cache = new UnidentifiedClientCache())
		{
			Object firstWorld = new EqualWorld();
			Object secondWorld = new EqualWorld();
			Assert.assertEquals(firstWorld, secondWorld);
			Assert.assertEquals(firstWorld.hashCode(), secondWorld.hashCode());
			File first = cache.folder("proxy.example", firstWorld, "overworld:0");
			File second = cache.folder("proxy.example", secondWorld, "overworld:0");
			Assert.assertNotEquals(first, second);
			Assert.assertEquals(first, cache.folder("proxy.example", firstWorld, "overworld:0"));
			Assert.assertEquals(second, cache.folder("proxy.example", secondWorld, "overworld:0"));
		}
	}

	@Test public void releasedIdentityIsExpungedWithoutDeletingActiveProviderFiles() throws Exception
	{
		try (UnidentifiedClientCache cache = new UnidentifiedClientCache())
		{
			Object releasedWorld = new EqualWorld();
			Object activeWorld = new EqualWorld();
			File oldFolder = cache.folder("proxy.example", releasedWorld, "overworld:0");
			File activeFolder = cache.folder("proxy.example", activeWorld, "overworld:0");
			Files.createDirectories(oldFolder.toPath());
			Path providerFile = oldFolder.toPath().resolve("still-open.sqlite");
			Files.write(providerFile, new byte[] { 1 });
			Map<?, ?> names = names(cache);
			Assert.assertEquals(2, names.size());
			WeakReference<?> releasedKey = null;
			for (Object key : names.keySet())
			{
				Assert.assertTrue("World identity key must be weak", key instanceof WeakReference);
				if (((WeakReference<?>) key).get() == releasedWorld) { releasedKey = (WeakReference<?>) key; }
			}
			Assert.assertNotNull(releasedKey);
			// Deterministic GC boundary: clear/enqueue the real production weak key.
			releasedKey.clear();
			Assert.assertTrue(releasedKey.enqueue());
			Assert.assertEquals(activeFolder, cache.folder("proxy.example", activeWorld, "overworld:0"));
			Assert.assertEquals(1, names.size());
			Assert.assertFalse(names.containsKey(releasedKey));
			Assert.assertTrue("Name expiry must not delete files owned by an active provider", Files.exists(providerFile));
			for (Object value : names.values()) { Assert.assertTrue("Name values must not retain worlds", value instanceof String); }
		}
	}

	@Test public void noStrongReferenceKeepsReleasedWorldAlive() throws Exception
	{
		try (UnidentifiedClientCache cache = new UnidentifiedClientCache())
		{
			WeakReference<Object> released = createUnownedWorld(cache);
			awaitCollection(released);
			Object activeWorld = new Object();
			cache.folder("proxy.example", activeWorld, "overworld:0");
			Assert.assertEquals(1, names(cache).size());
		}
	}

	private static WeakReference<Object> createUnownedWorld(UnidentifiedClientCache cache)
	{
		Object world = new EqualWorld();
		cache.folder("proxy.example", world, "overworld:0");
		return new WeakReference<>(world);
	}

	static void awaitCollection(WeakReference<?> reference) throws InterruptedException
	{
		for (int attempt = 0; attempt < 40 && reference.get() != null; attempt++)
		{
			System.gc();
			Thread.sleep(25);
		}
		Assert.assertNull("An unloaded object remained strongly reachable after bounded collection attempts", reference.get());
	}

	private static Map<?, ?> names(UnidentifiedClientCache cache) throws Exception
	{
		Field field = UnidentifiedClientCache.class.getDeclaredField("namesByWorld");
		field.setAccessible(true);
		return (Map<?, ?>) field.get(cache);
	}

	private static final class EqualWorld
	{
		@Override public boolean equals(Object other) { return other instanceof EqualWorld; }
		@Override public int hashCode() { return 7; }
	}

}
