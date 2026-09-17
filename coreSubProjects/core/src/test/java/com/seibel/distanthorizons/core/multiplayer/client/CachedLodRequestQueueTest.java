package com.seibel.distanthorizons.core.multiplayer.client;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.generation.tasks.ERetrievalResultState;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.multiplayer.config.SessionConfig;
import com.seibel.distanthorizons.core.multiplayer.fullData.FullDataPayload;
import com.seibel.distanthorizons.core.multiplayer.fullData.FullDataPayloadReceiver;
import com.seibel.distanthorizons.core.network.messages.AbstractTrackableMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSplitMessage;
import com.seibel.distanthorizons.core.network.session.NetworkSession;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.sql.dto.BeaconBeamDTO;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.world.EWorldEnvironment;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Runs the production client queue with a controllable transport and response executor. */
public class CachedLodRequestQueueTest
{
	private static final long POS = DhSectionPos.encode((byte) 6, 0, 0);
	private static final DhBlockPos2D TARGET = new DhBlockPos2D(0, 0);

	private static <T> T allocate(Class<T> type) throws Exception
	{
		Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
		Field field = unsafeType.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
		return type.cast(allocate.invoke(field.get(null), type));
	}
	private static Field field(Class<?> type, String name) throws Exception
	{
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static final class World extends AbstractDhWorld
	{
		World() { super(EWorldEnvironment.CLIENT_ONLY); }
		@Override public IDhLevel getOrLoadLevel(ILevelWrapper wrapper) { return wrapper.getDhLevel(); }
		@Override public IDhLevel getLevel(ILevelWrapper wrapper) { return wrapper.getDhLevel(); }
		@Override public Iterable<? extends IDhLevel> getAllLoadedLevels() { return Collections.emptyList(); }
		@Override public int getLoadedLevelCount() { return 1; }
		@Override public boolean unloadLevel(ILevelWrapper wrapper) { return false; }
		@Override public void close() { }
	}

	private static final class Level extends DhClientLevel
	{
		IClientLevelWrapper wrapper;
		int beaconApplications;
		// Never invoked: real construction starts the renderer and database machinery.
		private Level() throws SQLException, IOException { super(null, null, null); }
		@Override public ILevelWrapper getLevelWrapper() { return this.wrapper; }
		@Override public IClientLevelWrapper getClientLevelWrapper() { return this.wrapper; }
		@Override public DhBlockPos2D getTargetPosForGeneration() { return TARGET; }
		@Override public void updateBeaconBeamsForSectionPos(long pos, List<BeaconBeamDTO> beams) { this.beaconApplications++; }
	}

	private static final class Config extends SessionConfig
	{
		boolean syncEnabled = true;
		int syncRate = 50;
		@Override public boolean isDistantGenerationEnabled() { return false; }
		@Override public boolean getSynchronizeOnLoad() { return this.syncEnabled; }
		@Override public int getSyncOnLoginRateLimit() { return this.syncRate; }
		@Override public int getMaxSyncOnLoadDistance() { return 256; }
	}

	private static final class Session extends NetworkSession
	{
		Runnable afterSend;
		final List<FullDataSourceRequestMessage> messages = new ArrayList<>();
		final List<CompletableFuture<FullDataSourceResponseMessage>> responses = new ArrayList<>();
		Session() { super(null); }
		@Override @SuppressWarnings("unchecked")
		public <T extends AbstractTrackableMessage> CompletableFuture<T> sendRequest(AbstractTrackableMessage message, Class<T> responseClass)
		{
			Assert.assertEquals(FullDataSourceResponseMessage.class, responseClass);
			this.messages.add((FullDataSourceRequestMessage) message);
			CompletableFuture<FullDataSourceResponseMessage> response = new CompletableFuture<>();
			this.responses.add(response);
			if (this.afterSend != null) { this.afterSend.run(); }
			return (CompletableFuture<T>) (CompletableFuture<?>) response;
		}
	}

	private static final class State extends ClientNetworkState
	{
		Session session;
		boolean ready;
		@Override public boolean isReady() { return this.ready && !this.session.isClosed(); }
		@Override public NetworkSession getSession() { return this.session; }
		@Override public long getServerTimeOffset() { return 60000; }
	}

	private static final class Fixture implements AutoCloseable
	{
		final Object previousWorld;
		final Level level;
		final State state;
		final Config config = new Config();
		final Session session = new Session();
		final FullDataPayloadReceiver receiver = new FullDataPayloadReceiver();
		final SyncOnLoadRequestQueue queue;
		IDhLevel currentLevel;
		Fixture(Supplier<Executor> responseExecutor, int capacity) throws Exception
		{
			this.previousWorld = field(SharedApi.class, "currentWorld").get(null);
			this.level = allocate(Level.class);
			this.currentLevel = this.level;
			this.level.wrapper = (IClientLevelWrapper) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { IClientLevelWrapper.class },
					(proxy, method, args) -> {
						if (method.getName().equals("getDhLevel")) return this.currentLevel;
						if (method.getName().equals("getDhIdentifier") || method.getName().equals("getDimensionName")) return "backend@end:1";
						if (method.getName().equals("getMinHeight")) return 0;
						if (method.getName().equals("getHeight")) return 256;
						if (method.getName().equals("getDelayedSetupComplete")) return true;
						if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
						if (method.getName().equals("equals")) return proxy == args[0];
						if (method.getName().equals("toString")) return "TestEndLevel";
						return null;
					});
			this.state = allocate(State.class);
			this.state.session = this.session;
			this.state.ready = true;
			this.state.sessionConfig = this.config;
			field(ClientNetworkState.class, "fullDataPayloadReceiver").set(this.state, this.receiver);
			field(SharedApi.class, "currentWorld").set(null, new World());
			this.queue = new SyncOnLoadRequestQueue(this.level, this.state, responseExecutor, capacity);
		}
		CompletableFuture<DataSourceRetrievalResult> submit() { return this.queue.submitRequest(POS, FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP); }
		ByteBuf offerPayload(long sectionPos) throws Exception
		{
			ByteBuf encoded = Unpooled.buffer();
			try (FullDataSourceV2 data = FullDataSourceV2.createEmpty(sectionPos);
				 FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateFromDataSource(data, EDhApiDataCompressionMode.Z_STD_BLOCK))
			{
				dto.encode(encoded);
			}
			FullDataSplitMessage fragment = new FullDataSplitMessage(1, encoded, true);
			try { this.receiver.receiveChunk(fragment); }
			finally { encoded.release(); }
			return encoded;
		}
		FullDataSourceResponseMessage payloadResponse()
		{
			FullDataPayload payload = new FullDataPayload();
			payload.dtoBufferId = 1;
			return new FullDataSourceResponseMessage(payload);
		}
		@Override public void close() throws Exception
		{
			try { this.queue.close(); this.receiver.close(); this.session.close(); }
			finally { field(SharedApi.class, "currentWorld").set(null, this.previousWorld); }
		}
	}

	@Test public void disabledRendererQuiescenceRejectsSyncAdmission() throws Exception
	{
		ConfigEntry<EDhApiRendererMode> mode = com.seibel.distanthorizons.core.config.Config.Client.Advanced.Debugging.rendererMode;
		EDhApiRendererMode previous = mode.getTrueValue();
		try (Fixture fixture = new Fixture(() -> Runnable::run, 8))
		{
			mode.setWithoutFiringEvents(EDhApiRendererMode.DISABLED);
			Assert.assertFalse(fixture.queue.canAcceptSyncRequests());
			Assert.assertNull(fixture.queue.trySubmitRequest(POS, FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP));
			Assert.assertFalse(fixture.queue.tick(TARGET));
			Assert.assertTrue(fixture.session.messages.isEmpty());
		}
		finally { mode.setWithoutFiringEvents(previous); }
	}

	@Test public void cachedQueryIsSentWithWorldGenerationDisabledAndClockSkew() throws Exception
	{
		try (Fixture fixture = new Fixture(() -> Runnable::run, 8))
		{
			fixture.submit();
			Assert.assertTrue(fixture.queue.tick(TARGET));
			Assert.assertEquals(1, fixture.session.messages.size());
			Assert.assertEquals(Long.valueOf(FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP), fixture.session.messages.get(0).clientTimestamp);
			Assert.assertEquals("backend@end:1", fixture.session.messages.get(0).getLevelName());
		}
	}

	@Test public void emptyReplyIsNoUpdateAndDoesNotCreateOrApplyEmptyTerrain() throws Exception
	{
		try (Fixture fixture = new Fixture(() -> Runnable::run, 8))
		{
			CompletableFuture<DataSourceRetrievalResult> result = fixture.submit();
			fixture.queue.tick(TARGET);
			fixture.session.responses.get(0).complete(new FullDataSourceResponseMessage());
			Assert.assertEquals(ERetrievalResultState.NO_UPDATE, result.join().state);
			Assert.assertNull(result.join().dataSource);
			Assert.assertEquals(0, fixture.level.beaconApplications);
			Assert.assertEquals(0, fixture.queue.getWaitingTaskCount());
			Assert.assertEquals(0, fixture.queue.getInProgressTaskCount());
		}
	}

	@Test public void duplicateAdmissionSharesFutureAndCapacityRejectsOnlyNewPosition() throws Exception
	{
		try (Fixture fixture = new Fixture(() -> Runnable::run, 1))
		{
			CompletableFuture<DataSourceRetrievalResult> first = fixture.submit();
			Assert.assertSame(first, fixture.submit());
			assertRejected(fixture.queue.submitRequest(DhSectionPos.encode((byte) 6, 1, 0), FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP));
			Assert.assertEquals(1, fixture.queue.getWaitingTaskCount());
			first.cancel(false);
			Assert.assertEquals(0, fixture.queue.getWaitingTaskCount());
			Assert.assertFalse(fixture.submit().isDone());
		}
	}

	@Test public void closeCancelsActiveAndQueuedRequestsWithoutDoubleRelease() throws Exception
	{
		Queue<Runnable> callbacks = new ArrayDeque<>();
		try (Fixture fixture = new Fixture(() -> callbacks::add, 8))
		{
			CompletableFuture<DataSourceRetrievalResult> first = fixture.submit();
			fixture.queue.tick(TARGET);
			CompletableFuture<DataSourceRetrievalResult> waiting = fixture.queue.submitRequest(DhSectionPos.encode((byte) 6, 1, 0), FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP);
			fixture.queue.close();
			Assert.assertTrue(first.isCancelled());
			Assert.assertTrue(waiting.isCancelled());
			while (!callbacks.isEmpty()) callbacks.remove().run();
			fixture.queue.close();
			Assert.assertEquals(0, fixture.queue.getWaitingTaskCount());
			Assert.assertEquals(0, fixture.queue.getInProgressTaskCount());
			assertRejected(fixture.submit());
		}
	}

	@Test public void missingCompressionExecutorDoesNotLeakPendingWork() throws Exception
	{
		try (Fixture fixture = new Fixture(() -> null, 8))
		{
			CompletableFuture<DataSourceRetrievalResult> result = fixture.submit();
			fixture.queue.tick(TARGET);
			ByteBuf encoded = fixture.offerPayload(POS);
			fixture.session.responses.get(0).complete(fixture.payloadResponse());
			Assert.assertTrue(result.isCompletedExceptionally());
			Assert.assertEquals(0, encoded.refCnt());
			Assert.assertEquals(0, fixture.queue.getInProgressTaskCount());
			Assert.assertEquals(0, fixture.queue.getWaitingTaskCount());
		}
	}

	@Test public void rejectedResponseDispatchSettlesRequestAndRestoresPermit() throws Exception
	{
		try (Fixture fixture = new Fixture(() -> task -> { throw new RejectedExecutionException("test executor stopped"); }, 8))
		{
			CompletableFuture<DataSourceRetrievalResult> result = fixture.submit();
			fixture.queue.tick(TARGET);
			ByteBuf encoded = fixture.offerPayload(POS);
			fixture.session.responses.get(0).complete(fixture.payloadResponse());
			Assert.assertTrue(result.isCompletedExceptionally());
			Assert.assertEquals(0, encoded.refCnt());
			Assert.assertEquals(0, fixture.queue.getInProgressTaskCount());
			Assert.assertEquals(0, fixture.queue.getWaitingTaskCount());
		}
	}

	@Test public void validPayloadCompletesWithOwnedDataAndReleasesNetworkBuffer() throws Exception
	{
		try (Fixture fixture = new Fixture(() -> Runnable::run, 8))
		{
			CompletableFuture<DataSourceRetrievalResult> result = fixture.submit();
			fixture.queue.tick(TARGET);
			ByteBuf encoded = fixture.offerPayload(POS);
			fixture.session.responses.get(0).complete(fixture.payloadResponse());
			DataSourceRetrievalResult received = result.join();
			Assert.assertEquals(ERetrievalResultState.SUCCESS, received.state);
			Assert.assertEquals(POS, received.pos);
			try (FullDataSourceV2 data = received.dataSource) { Assert.assertEquals(POS, data.getPos()); }
			Assert.assertEquals(0, encoded.refCnt());
			Assert.assertEquals(0, fixture.level.beaconApplications);
			Assert.assertEquals(0, fixture.queue.getInProgressTaskCount());
		}
	}

	@Test public void queuedResponseCannotApplyAfterDimensionReplacement() throws Exception
	{
		Queue<Runnable> callbacks = new ArrayDeque<>();
		try (Fixture fixture = new Fixture(() -> callbacks::add, 8))
		{
			CompletableFuture<DataSourceRetrievalResult> result = fixture.submit();
			fixture.queue.tick(TARGET);
			ByteBuf encoded = fixture.offerPayload(POS);
			fixture.session.responses.get(0).complete(fixture.payloadResponse());
			fixture.currentLevel = null;
			while (!callbacks.isEmpty()) callbacks.remove().run();
			Assert.assertTrue(result.isCancelled() || result.isCompletedExceptionally());
			Assert.assertEquals(0, fixture.level.beaconApplications);
			Assert.assertEquals(0, fixture.queue.getInProgressTaskCount());
			Assert.assertEquals(0, encoded.refCnt());
		}
	}

	@Test public void coldCoarseSnapshotCannotPropagateIntoNewerChildrenOrReplaceBeacons() throws Exception
	{
		long coarse = DhSectionPos.encode((byte) 8, 0, 0);
		try (Fixture fixture = new Fixture(() -> Runnable::run, 8))
		{
			CompletableFuture<DataSourceRetrievalResult> result = fixture.queue.submitRequest(coarse, FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP);
			fixture.queue.tick(TARGET);
			ByteBuf encoded = fixture.offerPayload(coarse);
			fixture.session.responses.get(0).complete(fixture.payloadResponse());
			try (FullDataSourceV2 data = result.join().dataSource)
			{
				Assert.assertFalse(data.applyToChildren);
				Assert.assertTrue(data.applyToParent);
			}
			Assert.assertEquals(0, fixture.level.beaconApplications);
			Assert.assertEquals(0, encoded.refCnt());
		}
	}

	@Test public void timestampCoarseUpdateRetainsChildPropagationAndBeaconApplication() throws Exception
	{
		long coarse = DhSectionPos.encode((byte) 8, 0, 0);
		try (Fixture fixture = new Fixture(() -> Runnable::run, 8))
		{
			CompletableFuture<DataSourceRetrievalResult> result = fixture.queue.submitRequest(coarse, 1234567890L);
			fixture.queue.tick(TARGET);
			ByteBuf encoded = fixture.offerPayload(coarse);
			fixture.session.responses.get(0).complete(fixture.payloadResponse());
			try (FullDataSourceV2 data = result.join().dataSource)
			{
				Assert.assertTrue(data.applyToChildren);
				Assert.assertTrue(data.applyToParent);
			}
			Assert.assertEquals(1, fixture.level.beaconApplications);
			Assert.assertEquals(0, encoded.refCnt());
		}
	}

	@Test public void closeImmediatelyReleasesAcceptedPayloadBeforeQueuedCallbackRuns() throws Exception
	{
		Queue<Runnable> callbacks = new ArrayDeque<>();
		try (Fixture fixture = new Fixture(() -> callbacks::add, 8))
		{
			CompletableFuture<DataSourceRetrievalResult> result = fixture.submit();
			fixture.queue.tick(TARGET);
			ByteBuf encoded = fixture.offerPayload(POS);
			fixture.session.responses.get(0).complete(fixture.payloadResponse());
			Assert.assertFalse(callbacks.isEmpty());
			Assert.assertEquals(1, encoded.refCnt());
			fixture.queue.close();
			Assert.assertTrue(result.isCancelled());
			Assert.assertEquals(0, encoded.refCnt());
			Assert.assertEquals(0, fixture.queue.getInProgressTaskCount());
			while (!callbacks.isEmpty()) callbacks.remove().run();
			Assert.assertEquals(0, encoded.refCnt());
			Assert.assertEquals(0, fixture.queue.getInProgressTaskCount());
			Assert.assertEquals(0, fixture.level.beaconApplications);
		}
	}

	@Test public void mismatchedSectionIsDisposedWithoutPublishingGeometryOrBeacons() throws Exception
	{
		try (Fixture fixture = new Fixture(() -> Runnable::run, 8))
		{
			CompletableFuture<DataSourceRetrievalResult> result = fixture.submit();
			fixture.queue.tick(TARGET);
			ByteBuf encoded = fixture.offerPayload(DhSectionPos.encode((byte) 6, 100, 100));
			fixture.session.responses.get(0).complete(fixture.payloadResponse());
			Assert.assertFalse("A wrong section must never satisfy the request", result.isDone() && !result.isCompletedExceptionally());
			Assert.assertEquals(0, encoded.refCnt());
			Assert.assertEquals(0, fixture.queue.getInProgressTaskCount());
			Assert.assertEquals(0, fixture.level.beaconApplications);
		}
	}

	@Test public void disablingSynchronizationPreventsTransportDespiteQueuedWork() throws Exception
	{
		try (Fixture fixture = new Fixture(() -> Runnable::run, 8))
		{
			fixture.config.syncEnabled = false;
			fixture.submit();
			Assert.assertFalse(fixture.queue.tick(TARGET));
			Assert.assertTrue(fixture.session.messages.isEmpty());
			Assert.assertEquals(0, fixture.queue.getInProgressTaskCount());
		}
	}

	@Test public void inadmissibleSyncCreatesNoTaskAndResumesWhenReady() throws Exception
	{
		try (Fixture fixture = new Fixture(() -> Runnable::run, 1))
		{
			fixture.state.ready = false;
			Assert.assertNull(fixture.queue.trySubmitRequest(POS, FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP));
			Assert.assertEquals(0, fixture.queue.getWaitingTaskCount());
			fixture.state.ready = true;
			fixture.config.syncEnabled = false;
			Assert.assertNull(fixture.queue.trySubmitRequest(POS, FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP));
			fixture.config.syncEnabled = true;
			fixture.config.syncRate = 0;
			Assert.assertNull(fixture.queue.trySubmitRequest(POS, FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP));
			Assert.assertEquals(0, fixture.queue.getWaitingTaskCount());
			fixture.config.syncRate = 50;
			Assert.assertNotNull(fixture.queue.trySubmitRequest(POS, FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP));
			fixture.queue.tick(TARGET);
			Assert.assertEquals(1, fixture.session.messages.size());
			fixture.queue.close();
			Assert.assertNull(fixture.queue.trySubmitRequest(POS, FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP));
			Assert.assertEquals(0, fixture.queue.getWaitingTaskCount());
		}
	}

	@Test public void nonAllocatingAdmissionHandlesCapacityAndDuplicateAtomically() throws Exception
	{
		try (Fixture fixture = new Fixture(() -> Runnable::run, 1))
		{
			CompletableFuture<DataSourceRetrievalResult> first = fixture.queue.trySubmitRequest(POS, 10L);
			Assert.assertNotNull(first);
			Assert.assertSame(first, fixture.queue.trySubmitRequest(POS, 11L));
			long next = DhSectionPos.encode((byte) 6, 1, 0);
			Assert.assertNull(fixture.queue.trySubmitRequest(next, 12L));
			Assert.assertEquals(1, fixture.queue.getWaitingTaskCount());
			first.cancel(false);
			Assert.assertNotNull(fixture.queue.trySubmitRequest(next, 13L));
			fixture.queue.tick(TARGET);
			Assert.assertEquals(Long.valueOf(60013L), fixture.session.messages.get(0).clientTimestamp);
		}
	}

	@Test public void permissionRevokedDuringSendBatchPausesRemainingRequests() throws Exception
	{
		try (Fixture fixture = new Fixture(() -> Runnable::run, 8))
		{
			fixture.queue.trySubmitRequest(POS, 10L);
			fixture.queue.trySubmitRequest(DhSectionPos.encode((byte) 6, 1, 0), 20L);
			fixture.session.afterSend = () -> fixture.config.syncEnabled = false;
			fixture.queue.tick(TARGET);
			Assert.assertEquals(1, fixture.session.messages.size());
			Assert.assertEquals(2, fixture.queue.getWaitingTaskCount());
			fixture.session.afterSend = null;
			fixture.config.syncEnabled = true;
			fixture.queue.tick(TARGET);
			Assert.assertEquals(2, fixture.session.messages.size());
		}
	}

	@Test public void wrappedTransportCancellationSettlesWithoutRetryOrFailedCount() throws Exception
	{
		try (Fixture fixture = new Fixture(() -> Runnable::run, 8))
		{
			CompletableFuture<DataSourceRetrievalResult> result = fixture.submit();
			fixture.queue.tick(TARGET);
			fixture.session.responses.get(0).completeExceptionally(new CompletionException(new CancellationException("test unload")));
			Assert.assertTrue(result.isCancelled());
			fixture.queue.tick(TARGET);
			Assert.assertEquals(1, fixture.session.messages.size());
			Assert.assertEquals(0, fixture.queue.getWaitingTaskCount());
			Assert.assertEquals(0, fixture.queue.getInProgressTaskCount());
			Assert.assertEquals(0, ((AtomicInteger) field(AbstractFullDataNetworkRequestQueue.class, "failedRequests").get(fixture.queue)).get());
		}
	}

	private static void assertRejected(CompletableFuture<?> result)
	{
		CompletionException exception = Assert.assertThrows(CompletionException.class, result::join);
		Assert.assertTrue(exception.getCause() instanceof RejectedExecutionException);
	}
}
