package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.core.level.IKeyedClientLevelManager;
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.network.messages.base.LevelInitMessage;
import com.seibel.distanthorizons.core.network.session.NetworkSession;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.world.EWorldEnvironment;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Queue;

public class ClientPluginChannelLifecycleTest
{
	private static IClientLevelWrapper level(Object mcWorld)
	{
		return (IClientLevelWrapper) Proxy.newProxyInstance(ClientPluginChannelLifecycleTest.class.getClassLoader(),
				new Class<?>[] { IClientLevelWrapper.class }, (proxy, method, args) ->
				{
					if (method.getName().equals("getWrappedMcObject")) { return mcWorld; }
					if (method.getName().equals("getDimensionName")) { return "overworld:0"; }
					if (method.getName().equals("getDelayedSetupComplete")) { return true; }
					return null;
				});
	}

	private static final class World extends AbstractDhWorld
	{
		World() { super(EWorldEnvironment.CLIENT_ONLY); }
		@Override public IDhLevel getOrLoadLevel(ILevelWrapper wrapper) { return null; }
		@Override public IDhLevel getLevel(ILevelWrapper wrapper) { return null; }
		@Override public Iterable<? extends IDhLevel> getAllLoadedLevels() { return Collections.emptyList(); }
		@Override public int getLoadedLevelCount() { return 0; }
		@Override public boolean unloadLevel(ILevelWrapper wrapper) { return false; }
		@Override public void close() { }
	}

	private static final class Fixture
	{
		final Queue<Runnable> queue = new ArrayDeque<>();
		final NetworkSession session = new NetworkSession(null);
		IClientLevelWrapper currentLevel = level(new Object());
		AbstractDhWorld world = new World();
		int applications;
		boolean enabled;
		String appliedKey;
		final IKeyedClientLevelManager manager = (IKeyedClientLevelManager) Proxy.newProxyInstance(
				ClientPluginChannelLifecycleTest.class.getClassLoader(), new Class<?>[] { IKeyedClientLevelManager.class },
				(proxy, method, args) ->
				{
					if (method.getName().equals("setServerKeyedLevel")) { applications++; enabled = true; appliedKey = (String) args[3]; }
					if (method.getName().equals("disable")) { enabled = false; }
					if (method.getName().equals("isEnabled")) { return enabled; }
					if (method.getName().equals("getDelayedSetupComplete")) { return true; }
					return null;
				});
		final ClientPluginChannelApi api = new ClientPluginChannelApi(manager, () -> currentLevel, () -> world, queue::add);
		Fixture() { api.onJoinServer(session); }
		void receive() { api.onLevelInitMessage(session, new LevelInitMessage("overworld:0", "backend", "world@overworld:0")); }
		void drain() { while (!queue.isEmpty()) { queue.remove().run(); } }
	}

	@Test public void acceptedMessageAppliesOnlyToSameWorldAndSession()
	{
		Fixture f = new Fixture(); f.receive(); f.drain();
		Assert.assertEquals(1, f.applications);
	}
	@Test public void resetInvalidatesAcceptedRenderCallback()
	{
		Fixture f = new Fixture(); f.receive(); f.api.reset(); f.currentLevel = level(new Object()); f.drain();
		Assert.assertEquals(0, f.applications); Assert.assertFalse(f.enabled);
	}
	@Test public void unloadInvalidatesAcceptedCallbackWithinSameConnection()
	{
		Fixture f = new Fixture(); f.receive(); f.api.onClientLevelUnload(); f.drain();
		Assert.assertEquals(0, f.applications); Assert.assertTrue(f.api.allowLevelLoading(f.currentLevel));
		f.receive(); f.drain(); Assert.assertEquals(1, f.applications);
	}
	@Test public void replacingDhWorldOrMinecraftWorldRejectsCallback()
	{
		Fixture a = new Fixture(); a.receive(); a.world = new World(); a.drain(); Assert.assertEquals(0, a.applications);
		Fixture b = new Fixture(); b.receive(); b.currentLevel = level(new Object()); b.drain(); Assert.assertEquals(0, b.applications);
	}
	@Test public void oldSessionCannotResetReplacementOrSubmitNewCallbacks()
	{
		Fixture f = new Fixture(); f.receive(); NetworkSession replacement = new NetworkSession(null);
		f.api.onJoinServer(replacement); f.session.close(); f.receive(); f.drain();
		Assert.assertSame(replacement, f.api.networkSession); Assert.assertEquals(0, f.applications);
	}
	@Test public void acceptedMessageSnapshotsItsStrings()
	{
		Fixture f = new Fixture(); LevelInitMessage message = new LevelInitMessage("overworld:0", "backend", "world@overworld:0");
		f.api.onLevelInitMessage(f.session, message); message.levelKey = "different@overworld:0"; f.drain();
		Assert.assertEquals("world@overworld:0", f.appliedKey);
	}
	@Test public void invalidKeyCannotEnterRenderQueue()
	{
		Fixture f = new Fixture();
		try { f.api.onLevelInitMessage(f.session, new LevelInitMessage("overworld:0", "../escape", "world@overworld:0")); Assert.fail(); }
		catch (IllegalArgumentException expected) { }
		Assert.assertTrue(f.queue.isEmpty());
	}
}
