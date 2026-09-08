package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.multiplayer.client.SyncOnLoadRequestQueue;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import com.seibel.distanthorizons.core.multiplayer.client.AbstractFullDataNetworkRequestQueue;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual provider.get() + SQLite reads; transport and updater completion are controlled boundaries. */
public class CachedLodProviderTest
{
	@Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();
	private static final long POS = DhSectionPos.encode((byte) 6, -6, 4);

	private static <T> T allocate(Class<T> type) throws Exception
	{
		Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
		Field field = unsafeType.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
		return type.cast(allocate.invoke(field.get(null), type));
	}
	private static void set(Object object, Class<?> type, String name, Object value) throws Exception
	{
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		field.set(object, value);
	}
	private static boolean isCheckedOut(PhantomArrayListCheckout checkout) throws Exception
	{
		Field field = FullDataSourceV2.class.getDeclaredField("ARRAY_LIST_POOL");
		field.setAccessible(true);
		return ((PhantomArrayListPool) field.get(null)).phantomRefToCheckout.containsValue(checkout);
	}

	private static final class RecordingQueue extends SyncOnLoadRequestQueue
	{
		List<Long> timestamps = new ArrayList<>();
		List<CompletableFuture<DataSourceRetrievalResult>> replies = new ArrayList<>();
		boolean ready = true;
		boolean full;
		@Override public synchronized boolean canAcceptSyncRequests() { return this.ready && !this.isClosed(); }
		@Override public synchronized boolean hasSyncRequestCapacity() { return this.canAcceptSyncRequests() && !this.full; }
		private RecordingQueue() { super(null, null); }
		@Override public CompletableFuture<DataSourceRetrievalResult> trySubmitRequest(long pos, Long timestamp)
		{
			if (!this.hasSyncRequestCapacity()) { return null; }
			this.timestamps.add(timestamp);
			CompletableFuture<DataSourceRetrievalResult> result = new CompletableFuture<>();
			this.replies.add(result);
			return result;
		}
	}

	private static final class Provider extends RemoteFullDataSourceProvider
	{
		int writes;
		int coldWrites;
		int timestampReads;
		Runnable afterTimestampRead;
		Executor executor;
		@Override protected Executor getSyncFileExecutor() { return this.executor; }
		@Override public Long getTimestampForPos(long pos)
		{
			this.timestampReads++;
			Long timestamp = super.getTimestampForPos(pos);
			if (this.afterTimestampRead != null) { this.afterTimestampRead.run(); }
			return timestamp;
		}
		CompletableFuture<Void> writeAcknowledgement;
		private Provider() throws SQLException, IOException { super(null, null, null, null); }
		@Override public CompletableFuture<Void> updateDataSourceAsync(FullDataSourceV2 input)
		{
			this.writes++;
			return this.writeAcknowledgement;
		}
		@Override public CompletableFuture<Void> updateDataSourceIfAbsentAsync(FullDataSourceV2 input)
		{
			this.writes++;
			this.coldWrites++;
			return this.writeAcknowledgement;
		}
		@Override protected FullDataSourceV2 createDataSourceFromDto(FullDataSourceV2DTO dto) throws InterruptedException, IOException, com.seibel.distanthorizons.core.util.objects.DataCorruptedException
		{ return dto.createUnitTestDataSource(); }
	}

	private final class Fixture implements AutoCloseable
	{
		final FullDataSourceV2Repo repo;
		final Provider provider;
		final RecordingQueue queue;
		final AtomicBoolean shutdown = new AtomicBoolean();
		final Set<Long> visited = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
		final Set<Long> unavailable = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
		Fixture(boolean networkAvailable) throws Exception
		{
			this.repo = new FullDataSourceV2Repo(AbstractDhRepo.DEFAULT_DATABASE_TYPE, new File(temporaryFolder.newFolder(), "lod.sqlite"));
			this.queue = new RecordingQueue();
			this.queue.timestamps = new ArrayList<>();
			this.queue.replies = new ArrayList<>();
			this.provider = allocate(Provider.class);
			this.provider.writeAcknowledgement = new CompletableFuture<>();
			this.provider.executor = Runnable::run;
			// Keep database reads real; omit only constructor side effects (workers/debug registration).
			set(this.provider, FullDataSourceProviderV2.class, "repo", this.repo);
			set(this.provider, FullDataSourceProviderV2.class, "isShutdownRef", this.shutdown);
			set(this.provider, RemoteFullDataSourceProvider.class, "syncOnLoadRequestQueue", networkAvailable ? this.queue : null);
			set(this.provider, RemoteFullDataSourceProvider.class, "visitedPositions", this.visited);
			set(this.provider, RemoteFullDataSourceProvider.class, "unavailablePositions", this.unavailable);
			set(this.provider, RemoteFullDataSourceProvider.class, "pendingPositions", Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>()));
			set(this.provider, RemoteFullDataSourceProvider.class, "deferredPositions", new LinkedHashSet<Long>());
			set(this.provider, RemoteFullDataSourceProvider.class, "deferredWorkerPending", new AtomicBoolean());
		}
		void read() { try (FullDataSourceV2 ignored = this.provider.get(POS)) { } }
		void save(long timestamp) throws Exception
		{
			try (FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS);
				FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateFromDataSource(data, EDhApiDataCompressionMode.Z_STD_BLOCK))
			{
				dto.lastModifiedUnixDateTime = timestamp;
				this.repo.saveChecked(dto);
			}
		}
		@Override public void close() { this.repo.close(); }
	}

	@Test public void emptyClientDatabaseRequestsExistingServerDataWithoutGeneration() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			Assert.assertNull(fixture.repo.getTimestampForPos(POS));
			fixture.read();
			Assert.assertEquals(Collections.singletonList(FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP), fixture.queue.timestamps);
			fixture.read();
			Assert.assertEquals(1, fixture.queue.timestamps.size());
			Assert.assertEquals(0, fixture.provider.writes);
		}
	}

	@Test public void missingServerSectionIsNotPersistedAsAirAndHasQueryCooldown() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			fixture.read();
			fixture.queue.replies.get(0).complete(DataSourceRetrievalResult.CreateNoUpdate(POS));
			Assert.assertNull(fixture.repo.getTimestampForPos(POS));
			Assert.assertEquals(0, fixture.provider.writes);
			Assert.assertTrue(fixture.unavailable.contains(POS));
			Assert.assertFalse(fixture.visited.contains(POS));
			fixture.read();
			Assert.assertEquals(1, fixture.queue.timestamps.size());
			fixture.unavailable.clear(); // Model expiration without wall-clock sleeps.
			fixture.read();
			Assert.assertEquals(2, fixture.queue.timestamps.size());
		}
	}

	@Test public void unchangedExistingSectionKeepsNormalRefreshIntervalAndTimestamp() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			try (FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS);
				 FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateFromDataSource(data, EDhApiDataCompressionMode.Z_STD_BLOCK))
			{
				dto.lastModifiedUnixDateTime = 1234567890L;
				fixture.repo.saveChecked(dto);
			}
			Long timestamp = fixture.repo.getTimestampForPos(POS);
			Assert.assertNotNull(timestamp);
			fixture.read();
			Assert.assertEquals(Collections.singletonList(timestamp), fixture.queue.timestamps);
			fixture.queue.replies.get(0).complete(DataSourceRetrievalResult.CreateNoUpdate(POS));
			Assert.assertTrue(fixture.visited.contains(POS));
			Assert.assertFalse(fixture.unavailable.contains(POS));
			Assert.assertEquals(0, fixture.provider.writes);
			fixture.read();
			Assert.assertEquals(1, fixture.queue.timestamps.size());
			fixture.visited.clear(); // Model normal refresh expiry; no background polling.
			fixture.read();
			Assert.assertEquals(2, fixture.queue.timestamps.size());
			Assert.assertEquals(timestamp, fixture.queue.timestamps.get(1));
		}
	}

	@Test public void payloadRemainsOwnedUntilWriteAcknowledgement() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			fixture.read();
			FullDataSourceV2 incoming = FullDataSourceV2.createEmpty(POS);
			PhantomArrayListCheckout checkout = incoming.getPhantomArrayCheckoutForUnitTesting();
			fixture.queue.replies.get(0).complete(DataSourceRetrievalResult.CreateSuccess(POS, incoming));
			Assert.assertEquals(1, fixture.provider.writes);
			Assert.assertEquals(1, fixture.provider.coldWrites);
			Assert.assertTrue(isCheckedOut(checkout));
			fixture.provider.writeAcknowledgement.complete(null);
			Assert.assertFalse(isCheckedOut(checkout));
		}
	}

	@Test public void failedWriteReleasesPayloadAndAllowsLaterOrdinaryRetry() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			fixture.read();
			FullDataSourceV2 incoming = FullDataSourceV2.createEmpty(POS);
			PhantomArrayListCheckout checkout = incoming.getPhantomArrayCheckoutForUnitTesting();
			fixture.queue.replies.get(0).complete(DataSourceRetrievalResult.CreateSuccess(POS, incoming));
			fixture.provider.writeAcknowledgement.completeExceptionally(new RejectedExecutionException("test writer stopped"));
			Assert.assertFalse(isCheckedOut(checkout));
			Assert.assertFalse(fixture.visited.contains(POS));
			Assert.assertTrue(fixture.unavailable.contains(POS));
			fixture.read();
			Assert.assertEquals(1, fixture.queue.timestamps.size());
			fixture.unavailable.clear();
			fixture.read();
			Assert.assertEquals(2, fixture.queue.timestamps.size());
		}
	}

	@Test public void unavailableNetworkAndClosedProviderDoNotSubmitQueries() throws Exception
	{
		try (Fixture fixture = new Fixture(false))
		{
			fixture.read();
			Assert.assertTrue(fixture.queue.timestamps.isEmpty());
		}
		try (Fixture fixture = new Fixture(true))
		{
			fixture.shutdown.set(true);
			fixture.read();
			Assert.assertTrue(fixture.queue.timestamps.isEmpty());
		}
	}

	@Test public void timestampUpdateUsesNormalMergePathRatherThanColdInsertion() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			try (FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS);
				 FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateFromDataSource(data, EDhApiDataCompressionMode.Z_STD_BLOCK))
			{
				dto.lastModifiedUnixDateTime = 1234567890L;
				fixture.repo.saveChecked(dto);
			}
			fixture.read();
			FullDataSourceV2 incoming = FullDataSourceV2.createEmpty(POS);
			fixture.queue.replies.get(0).complete(DataSourceRetrievalResult.CreateSuccess(POS, incoming));
			Assert.assertEquals(1, fixture.provider.writes);
			Assert.assertEquals(0, fixture.provider.coldWrites);
			fixture.provider.writeAcknowledgement.complete(null);
		}
	}

	@Test public void noHandshakeRetainsOnlyUniqueIntentAndStillReadsLocalData() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			fixture.queue.ready = false;
			fixture.save(123L);
			for (int i = 0; i < 20; i++)
			{
				try (FullDataSourceV2 local = fixture.provider.get(POS)) { Assert.assertNotNull(local); }
				fixture.provider.tickDeferredSynchronization();
			}
			Assert.assertEquals(0, fixture.provider.timestampReads);
			Assert.assertTrue(fixture.queue.replies.isEmpty());
			Assert.assertEquals(1, fixture.provider.getDeferredSyncCount());
		}
	}

	@Test public void lateHandshakeReadsTheCurrentTimestampWithoutAnotherRenderLookup() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			fixture.queue.ready = false;
			fixture.read();
			fixture.save(987654321L);
			Long currentTimestamp = fixture.repo.getTimestampForPos(POS);
			Assert.assertNotNull(currentTimestamp);
			fixture.queue.ready = true;
			fixture.provider.tickDeferredSynchronization();
			Assert.assertEquals(Collections.singletonList(currentTimestamp), fixture.queue.timestamps);
			Assert.assertEquals(1, fixture.provider.timestampReads);
			Assert.assertEquals(0, fixture.provider.getDeferredSyncCount());
		}
	}

	@Test public void lateHandshakeForEmptyCacheRetainsReadOnlySentinel() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			fixture.queue.ready = false;
			fixture.read();
			fixture.queue.ready = true;
			fixture.provider.tickDeferredSynchronization();
			Assert.assertEquals(Collections.singletonList(FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP), fixture.queue.timestamps);
		}
	}

	@Test public void capacityLossDuringSqlRetainsIntentAndRereadsTimestampOnRetry() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			fixture.provider.afterTimestampRead = () -> fixture.queue.full = true;
			fixture.read();
			Assert.assertTrue(fixture.queue.replies.isEmpty());
			Assert.assertEquals(1, fixture.provider.getDeferredSyncCount());
			fixture.provider.afterTimestampRead = null;
			fixture.save(456L);
			Long currentTimestamp = fixture.repo.getTimestampForPos(POS);
			Assert.assertNotNull(currentTimestamp);
			fixture.queue.full = false;
			fixture.provider.tickDeferredSynchronization();
			Assert.assertEquals(Collections.singletonList(currentTimestamp), fixture.queue.timestamps);
			Assert.assertEquals(0, fixture.provider.getDeferredSyncCount());
		}
	}

	@Test public void permissionLossAfterWorkerSchedulingPausesWithoutExtraSql() throws Exception
	{
		Queue<Runnable> workers = new ArrayDeque<>();
		try (Fixture fixture = new Fixture(true))
		{
			fixture.provider.executor = workers::add;
			fixture.queue.ready = false;
			fixture.read();
			fixture.queue.ready = true;
			fixture.provider.tickDeferredSynchronization();
			fixture.queue.ready = false;
			workers.remove().run();
			Assert.assertEquals(0, fixture.provider.timestampReads);
			Assert.assertEquals(1, fixture.provider.getDeferredSyncCount());
			fixture.queue.ready = true;
			fixture.provider.tickDeferredSynchronization();
			workers.remove().run();
			Assert.assertEquals(1, fixture.queue.replies.size());
		}
	}

	@Test public void deferredWorkUsesOneWorkerAndAtMost32PositionsPerTick() throws Exception
	{
		Queue<Runnable> workers = new ArrayDeque<>();
		try (Fixture fixture = new Fixture(true))
		{
			fixture.provider.executor = workers::add;
			fixture.queue.ready = false;
			for (int i = 0; i < 70; i++)
			{ try (FullDataSourceV2 ignored = fixture.provider.get(DhSectionPos.encode((byte) 6, i, 0))) { } }
			fixture.queue.ready = true;
			for (int i = 0; i < 10; i++) { fixture.provider.tickDeferredSynchronization(); }
			Assert.assertEquals(1, workers.size());
			workers.remove().run();
			Assert.assertEquals(32, fixture.queue.replies.size());
			Assert.assertEquals(38, fixture.provider.getDeferredSyncCount());
			Assert.assertTrue(workers.isEmpty());
			fixture.provider.tickDeferredSynchronization();
			workers.remove().run();
			Assert.assertEquals(64, fixture.queue.replies.size());
			Assert.assertEquals(6, fixture.provider.getDeferredSyncCount());
		}
	}

	@Test public void rejectedFileExecutorRetainsIntentsWithoutInternalRetryLoop() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			int[] executions = { 0 };
			fixture.provider.executor = task -> { executions[0]++; throw new RejectedExecutionException("test full"); };
			fixture.queue.ready = false;
			fixture.read();
			fixture.queue.ready = true;
			fixture.provider.tickDeferredSynchronization();
			Assert.assertEquals(1, executions[0]);
			Assert.assertEquals(0, fixture.provider.timestampReads);
			Assert.assertEquals(1, fixture.provider.getDeferredSyncCount());
			fixture.provider.executor = Runnable::run;
			fixture.provider.tickDeferredSynchronization();
			Assert.assertEquals(1, fixture.queue.replies.size());
		}
	}

	@Test public void overflowForgetsOldestIntentButAllowsOrdinaryReadToRetry() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			set(fixture.queue, AbstractFullDataNetworkRequestQueue.class, "requestCapacity", 2);
			fixture.queue.ready = false;
			fixture.read();
			for (int i = 0; i < 3; i++)
			{ try (FullDataSourceV2 ignored = fixture.provider.get(DhSectionPos.encode((byte) 6, i, 0))) { } }
			Assert.assertEquals(2, fixture.provider.getDeferredSyncCount());
			Assert.assertTrue(fixture.queue.replies.isEmpty());
			fixture.queue.ready = true;
			fixture.provider.tickDeferredSynchronization();
			Assert.assertEquals(2, fixture.queue.replies.size());
			fixture.read();
			Assert.assertEquals(3, fixture.queue.replies.size());
			Assert.assertEquals(0, fixture.provider.getDeferredSyncCount());
		}
	}

	@Test public void closeBeforeDeferredWorkerRunsClearsIntentsAndDoesNoSql() throws Exception
	{
		Queue<Runnable> workers = new ArrayDeque<>();
		try (Fixture fixture = new Fixture(true))
		{
			fixture.provider.executor = workers::add;
			fixture.queue.ready = false;
			fixture.read();
			fixture.queue.ready = true;
			fixture.provider.tickDeferredSynchronization();
			fixture.provider.stopSynchronization();
			workers.remove().run();
			Assert.assertEquals(0, fixture.provider.timestampReads);
			Assert.assertEquals(0, fixture.provider.getDeferredSyncCount());
			Assert.assertTrue(fixture.queue.replies.isEmpty());
		}
	}

	@Test public void closeBetweenTimestampReadAndAdmissionCreatesNoRequest() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			fixture.provider.afterTimestampRead = fixture.provider::stopSynchronization;
			fixture.read();
			Assert.assertEquals(1, fixture.provider.timestampReads);
			Assert.assertTrue(fixture.queue.replies.isEmpty());
			Assert.assertEquals(0, fixture.provider.getDeferredSyncCount());
		}
	}

	@Test public void responseAfterProviderCloseDisposesDataWithoutPublication() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			fixture.read();
			fixture.provider.stopSynchronization();
			FullDataSourceV2 incoming = FullDataSourceV2.createEmpty(POS);
			PhantomArrayListCheckout checkout = incoming.getPhantomArrayCheckoutForUnitTesting();
			fixture.queue.replies.get(0).complete(DataSourceRetrievalResult.CreateSuccess(POS, incoming));
			Assert.assertEquals(0, fixture.provider.writes);
			Assert.assertFalse(isCheckedOut(checkout));
		}
	}

	@Test public void wrappedCancellationKeepsShortBackoffAndAllowsRetryAfterExpiry() throws Exception
	{
		try (Fixture fixture = new Fixture(true))
		{
			fixture.read();
			fixture.queue.replies.get(0).completeExceptionally(new CompletionException(new CancellationException("test unload")));
			Assert.assertTrue(fixture.unavailable.contains(POS));
			Assert.assertTrue(fixture.visited.isEmpty());
			fixture.read();
			Assert.assertEquals(1, fixture.queue.replies.size());
			fixture.unavailable.clear(); // Model expiry of the unchanged one-minute backoff.
			fixture.read();
			Assert.assertEquals(2, fixture.queue.replies.size());
		}
	}
}
