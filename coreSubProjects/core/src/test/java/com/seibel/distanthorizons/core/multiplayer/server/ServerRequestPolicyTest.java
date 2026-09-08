package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.level.AbstractDhServerLevel;
import com.seibel.distanthorizons.core.level.DhServerLevel;
import com.seibel.distanthorizons.core.multiplayer.config.SessionConfig;
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.session.NetworkSession;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateAndConcurrencyLimiter;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import org.junit.Assert;
import org.junit.Test;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Executes production handlers; only Minecraft world/player construction is replaced by fixtures. */
public class ServerRequestPolicyTest
{
	private static final Object UNSAFE;
	private static final Method ALLOCATE;
	static
	{
		try { Class<?> type = Class.forName("sun.misc.Unsafe"); Field field = type.getDeclaredField("theUnsafe"); field.setAccessible(true); UNSAFE = field.get(null); ALLOCATE = type.getMethod("allocateInstance", Class.class); }
		catch (Exception error) { throw new ExceptionInInitializerError(error); }
	}
	private static void set(Object target, Class<?> type, String name, Object value) throws Exception
	{ Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
	private static Object get(Object target, Class<?> type, String name) throws Exception
	{ Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
	private static IServerLevelWrapper level(String name)
	{
		return (IServerLevelWrapper) Proxy.newProxyInstance(ServerRequestPolicyTest.class.getClassLoader(), new Class<?>[]{IServerLevelWrapper.class},
			(proxy, method, args) -> method.getName().equals("getKeyedLevelDimensionName") || method.getName().equals("getDhIdentifier") ? name : null);
	}
	private static class Message extends FullDataSourceRequestMessage
	{
		int rejected;
		@Override public void sendResponse(Exception failure) { this.rejected++; }
	}
	private static class Enabled extends SessionConfig { @Override public boolean getSynchronizeOnLoad() { return true; } }
	private static class Fixture implements AutoCloseable
	{
		final IServerLevelWrapper current = level("current");
		final AtomicReference<IServerLevelWrapper> location = new AtomicReference<>(this.current);
		final NetworkSession session;
		final DhServerLevel target;
		final Message message;
		final ServerPlayerState player;
		Fixture() throws Exception
		{
			IServerPlayerWrapper actor = (IServerPlayerWrapper) Proxy.newProxyInstance(ServerRequestPolicyTest.class.getClassLoader(), new Class<?>[]{IServerPlayerWrapper.class},
				(proxy, method, args) -> method.getName().equals("getLevel") ? this.location.get() : null);
			this.session = new NetworkSession(actor);
			this.target = (DhServerLevel) ALLOCATE.invoke(UNSAFE, DhServerLevel.class);
			set(this.target, AbstractDhServerLevel.class, "serverLevelWrapper", this.current);
			this.message = (Message) ALLOCATE.invoke(UNSAFE, Message.class);
			set(this.message, FullDataSourceRequestMessage.class, "levelName", "current");
			this.message.setSession(this.session);
			this.player = (ServerPlayerState) ALLOCATE.invoke(UNSAFE, ServerPlayerState.class);
			set(this.player, ServerPlayerState.class, "networkSession", this.session);
			set(this.player, ServerPlayerState.class, "sessionConfig", new Enabled());
		}
		@Override public void close() { this.session.close(); }
	}

	@Test public void unavailableExecutorRestoresQuotaAndResponds() throws Exception
	{
		Object oldReader = get(null, ThreadPoolUtil.class, "fileHandlerThreadPool");
		set(null, ThreadPoolUtil.class, "fileHandlerThreadPool", null);
		try (Fixture fixture = new Fixture(); FullDataSourceRequestHandler handler = new FullDataSourceRequestHandler(fixture.target))
		{
			ServerPlayerState.RateLimiterSet limiters = fixture.player.new RateLimiterSet();
			SupplierBasedRateAndConcurrencyLimiter<FullDataSourceRequestMessage> limiter = new SupplierBasedRateAndConcurrencyLimiter<>(() -> 1, ignored -> Assert.fail("Quota unexpectedly exhausted"));
			set(limiters, ServerPlayerState.RateLimiterSet.class, "syncOnLoginRateLimiter", limiter);
			handler.queueLodSyncForRequestMessage(fixture.player, fixture.message, limiters);
			Object concurrency = get(limiter, SupplierBasedRateAndConcurrencyLimiter.class, "concurrencyLimiter");
			AtomicInteger pending = (AtomicInteger) get(concurrency, concurrency.getClass(), "pendingTasks");
			Assert.assertEquals(0, pending.get()); Assert.assertEquals(1, fixture.message.rejected);
			handler.cancelRequest(fixture.message.futureId); handler.close(); Assert.assertEquals(0, pending.get());
		}
		finally { set(null, ThreadPoolUtil.class, "fileHandlerThreadPool", oldReader); }
	}

	@Test public void currentLevelValidatorRejectsOtherWorldEvenWithSameName() throws Exception
	{
		try (Fixture fixture = new Fixture())
		{
			Method validate = AbstractDhServerLevel.class.getDeclaredMethod("validatePlayerInCurrentLevel", AbstractNetworkMessage.class); validate.setAccessible(true);
			Assert.assertTrue((Boolean) validate.invoke(fixture.target, fixture.message));
			fixture.location.set(level("current"));
			Assert.assertFalse((Boolean) validate.invoke(fixture.target, fixture.message)); Assert.assertEquals(1, fixture.message.rejected);
			fixture.location.set(fixture.current); Assert.assertTrue((Boolean) validate.invoke(fixture.target, fixture.message));
			fixture.session.close(); Assert.assertFalse((Boolean) validate.invoke(fixture.target, fixture.message));
		}
	}
}
