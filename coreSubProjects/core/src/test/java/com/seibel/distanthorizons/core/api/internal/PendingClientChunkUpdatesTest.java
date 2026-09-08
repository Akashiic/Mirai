package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.objects.Pair;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PendingClientChunkUpdatesTest
{
	private static IClientLevelWrapper level(Object world, String dimension)
	{
		return (IClientLevelWrapper) Proxy.newProxyInstance(PendingClientChunkUpdatesTest.class.getClassLoader(),
				new Class<?>[] { IClientLevelWrapper.class }, (proxy, method, args) ->
				{
					if (method.getName().equals("getWrappedMcObject")) { return world; }
					if (method.getName().equals("getDimensionName")) { return dimension; }
					if (method.getName().equals("getDhIdentifier")) { return "same-identifier"; }
					if (method.getName().equals("hashCode")) { return System.identityHashCode(proxy); }
					if (method.getName().equals("equals")) { return proxy == args[0]; }
					return null;
				});
	}

	private static IChunkWrapper chunk(List<IClientLevelWrapper> copiedTo)
	{
		return (IChunkWrapper) Proxy.newProxyInstance(PendingClientChunkUpdatesTest.class.getClassLoader(),
				new Class<?>[] { IChunkWrapper.class }, (proxy, method, args) ->
				{
					if (method.getName().equals("copyWithLevel"))
					{
						copiedTo.add((IClientLevelWrapper) args[0]);
						return proxy;
					}
					if (method.getName().equals("equals")) { return proxy == args[0]; }
					return null;
				});
	}

	@Test public void lobbyChunkCannotBeAdoptedByCrucibleWithSameDimensionName()
	{
		Map<Pair<IClientLevelWrapper, DhChunkPos>, IChunkWrapper> pending = new ConcurrentHashMap<>();
		IClientLevelWrapper lobby = level(new Object(), "overworld:0");
		IClientLevelWrapper crucible = level(new Object(), "overworld:0");
		List<IClientLevelWrapper> copied = new ArrayList<>();
		pending.put(new Pair<>(lobby, new DhChunkPos(1, 2)), chunk(copied));
		Assert.assertEquals(0, PendingClientChunkUpdates.applyForLevel(pending, crucible, c -> Assert.fail("stale chunk applied")));
		Assert.assertTrue(copied.isEmpty());
		Assert.assertEquals(1, pending.size());
		PendingClientChunkUpdates.discardForWorld(pending, lobby.getWrappedMcObject());
		Assert.assertTrue(pending.isEmpty());
	}

	@Test public void serverKeyedReplacementAdoptsChunksOfTheSameMinecraftWorld()
	{
		Map<Pair<IClientLevelWrapper, DhChunkPos>, IChunkWrapper> pending = new ConcurrentHashMap<>();
		Object world = new Object();
		IClientLevelWrapper plain = level(world, "the_end:1");
		IClientLevelWrapper keyed = level(world, "the_end:1");
		List<IClientLevelWrapper> copied = new ArrayList<>();
		IChunkWrapper expected = chunk(copied);
		pending.put(new Pair<>(plain, new DhChunkPos(-5, 8)), expected);
		Assert.assertEquals(1, PendingClientChunkUpdates.applyForLevel(pending, keyed, c -> Assert.assertSame(expected, c)));
		Assert.assertSame(keyed, copied.get(0));
		Assert.assertTrue(pending.isEmpty());
	}

	@Test public void rapidEndReturnDoesNotAdoptPriorVisitOrDiscardNewVisit()
	{
		Map<Pair<IClientLevelWrapper, DhChunkPos>, IChunkWrapper> pending = new ConcurrentHashMap<>();
		IClientLevelWrapper prior = level(new Object(), "the_end:1");
		IClientLevelWrapper current = level(new Object(), "the_end:1");
		IClientLevelWrapper overworld = level(new Object(), "overworld:0");
		List<IClientLevelWrapper> copied = new ArrayList<>();
		IChunkWrapper oldChunk = chunk(copied), currentChunk = chunk(copied), otherChunk = chunk(copied);
		pending.put(new Pair<>(prior, new DhChunkPos(0, 0)), oldChunk);
		pending.put(new Pair<>(current, new DhChunkPos(0, 0)), currentChunk);
		pending.put(new Pair<>(overworld, new DhChunkPos(0, 0)), otherChunk);
		PendingClientChunkUpdates.discardForWorld(pending, prior.getWrappedMcObject());
		Assert.assertEquals(2, pending.size());
		Assert.assertEquals(1, PendingClientChunkUpdates.applyForLevel(pending, current, c -> Assert.assertSame(currentChunk, c)));
		Assert.assertEquals(1, pending.size());
		Assert.assertSame(otherChunk, pending.values().iterator().next());
	}

	@Test public void nullNativeWorldOnlyMatchesTheExactWrapper()
	{
		Map<Pair<IClientLevelWrapper, DhChunkPos>, IChunkWrapper> pending = new ConcurrentHashMap<>();
		IClientLevelWrapper source = level(null, "overworld:0");
		IClientLevelWrapper unrelated = level(null, "overworld:0");
		pending.put(new Pair<>(source, new DhChunkPos(0, 0)), chunk(new ArrayList<>()));
		Assert.assertEquals(0, PendingClientChunkUpdates.applyForLevel(pending, unrelated, c -> Assert.fail()));
		PendingClientChunkUpdates.discardForWorld(pending, null);
		Assert.assertEquals(1, pending.size());
		Assert.assertEquals(1, PendingClientChunkUpdates.applyForLevel(pending, source, c -> { }));
		Assert.assertTrue(pending.isEmpty());
	}

	@Test public void applyingOldEntryDoesNotRemoveItsConcurrentReplacement()
	{
		Map<Pair<IClientLevelWrapper, DhChunkPos>, IChunkWrapper> pending = new ConcurrentHashMap<>();
		IClientLevelWrapper source = level(new Object(), "overworld:0");
		Pair<IClientLevelWrapper, DhChunkPos> key = new Pair<>(source, new DhChunkPos(0, 0));
		IChunkWrapper old = chunk(new ArrayList<>()), replacement = chunk(new ArrayList<>());
		pending.put(key, old);
		Assert.assertEquals(1, PendingClientChunkUpdates.applyForLevel(pending, source, c -> pending.put(key, replacement)));
		Assert.assertSame(replacement, pending.get(key));
	}

	@Test public void failureKeepsThePendingUpdateForRetry()
	{
		Map<Pair<IClientLevelWrapper, DhChunkPos>, IChunkWrapper> pending = new ConcurrentHashMap<>();
		IClientLevelWrapper source = level(new Object(), "the_end:1");
		IChunkWrapper expected = chunk(new ArrayList<>());
		pending.put(new Pair<>(source, new DhChunkPos(0, 0)), expected);
		try
		{
			PendingClientChunkUpdates.applyForLevel(pending, source, c -> { throw new IllegalStateException("injected"); });
			Assert.fail();
		}
		catch (IllegalStateException expectedFailure) { Assert.assertEquals("injected", expectedFailure.getMessage()); }
		Assert.assertSame(expected, pending.values().iterator().next());
	}
}
