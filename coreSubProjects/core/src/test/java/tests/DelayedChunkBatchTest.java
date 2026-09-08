package tests;

import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.delayedSaveCache.DelayedDataSourceSaveCache;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.AbstractPhantomArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.Test;
import testItems.wrappers.TestBiomeWrapper;
import testItems.wrappers.TestBlockStateWrapper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class DelayedChunkBatchTest
{
	@Test
	public void detachedBWaitsForAAndNeverPublishesItsHashWithA() throws Exception
	{
		List<Map<DhChunkPos, Integer>> calledHashes = new ArrayList<>();
		List<FullDataSourceV2> calledData = new ArrayList<>();
		CompletableFuture<Void> a = new CompletableFuture<>();
		CompletableFuture<Void> b = new CompletableFuture<>();
		try (PausedCache cache = new PausedCache((data, hashes) ->
		{
			calledData.add(data); calledHashes.add(hashes);
			return calledHashes.size() == 1 ? a : b;
		}))
		{
			write(cache, 0, 10, 101); cache.flush();
			write(cache, 1, 20, 202); cache.flush();
			cache.start(1); // deliberately invert callback arrival, not detach order
			assertTrue(calledHashes.isEmpty());
			cache.start(0);
			assertEquals(1, calledHashes.size());
			assertEquals(Integer.valueOf(101), calledHashes.get(0).get(new DhChunkPos(0, 0)));
			assertFalse(calledHashes.get(0).containsKey(new DhChunkPos(1, 0)));
			assertTrue(isOpen(calledData.get(0)));
			a.complete(null);
			assertEquals(2, calledHashes.size());
			assertFalse(isOpen(calledData.get(0)));
			assertEquals(Integer.valueOf(202), calledHashes.get(1).get(new DhChunkPos(1, 0)));
			assertTrue(isOpen(calledData.get(1)));
			b.complete(null);
			assertFalse(isOpen(calledData.get(1)));
			cache.start(0); cache.start(1); // duplicate handling cannot close/replay again
			assertEquals(2, calledHashes.size());
		}
	}

	@Test
	public void sameChunkA1A2KeepsDataAndHashOrderWhenLaterFutureCompletesFirst() throws Exception
	{
		List<Integer> heights = new ArrayList<>();
		List<Integer> hashes = new ArrayList<>();
		CompletableFuture<Void> a1 = new CompletableFuture<>();
		CompletableFuture<Void> a2 = new CompletableFuture<>();
		try (PausedCache cache = new PausedCache((data, batch) ->
		{
			heights.add(FullDataPointUtil.getHeight(data.getColumnAtRelPos(0, 0).getLong(0)));
			hashes.add(batch.get(new DhChunkPos(0, 0)));
			return heights.size() == 1 ? a1 : a2;
		}))
		{
			write(cache, 0, 10, 11); cache.flush(); cache.start(0);
			write(cache, 0, 20, 22); cache.flush(); cache.start(1);
			a2.complete(null);
			assertEquals(1, heights.size());
			a1.complete(null);
			assertEquals(java.util.Arrays.asList(10, 20), heights);
			assertEquals(java.util.Arrays.asList(11, 22), hashes);
		}
	}

	@Test
	public void coalescingKeepsLatestPerChunkHashAndImmutableBatchMetadata() throws Exception
	{
		List<Map<DhChunkPos, Integer>> calls = new ArrayList<>();
		try (PausedCache cache = new PausedCache((data, hashes) ->
		{
			calls.add(hashes);
			assertEquals(20, FullDataPointUtil.getHeight(data.getColumnAtRelPos(0, 0).getLong(0)));
			assertEquals(30, FullDataPointUtil.getHeight(data.getColumnAtRelPos(16, 0).getLong(0)));
			return CompletableFuture.completedFuture(null);
		}))
		{
			write(cache, 0, 10, 11); write(cache, 0, 20, 22); write(cache, 1, 30, 33);
			cache.flush(); cache.start(0);
			assertEquals(1, calls.size());
			assertEquals(2, calls.get(0).size());
			assertEquals(Integer.valueOf(22), calls.get(0).get(new DhChunkPos(0, 0)));
			assertEquals(Integer.valueOf(33), calls.get(0).get(new DhChunkPos(1, 0)));
			try { calls.get(0).clear(); fail("Batch metadata must be immutable"); }
			catch (UnsupportedOperationException expected) { }
		}
	}

	@Test
	public void synchronousCallbackFailureClosesSourceAndAllowsNextBatch() throws Exception
	{
		List<FullDataSourceV2> calls = new ArrayList<>();
		try (PausedCache cache = new PausedCache((data, hashes) ->
		{
			calls.add(data);
			if (calls.size() == 1) { throw new IllegalStateException("injected synchronous callback failure"); }
			return CompletableFuture.completedFuture(null);
		}))
		{
			write(cache, 0, 10, 11); cache.flush();
			write(cache, 0, 20, 22); cache.flush();
			cache.start(1); cache.start(0);
			assertEquals(2, calls.size());
			assertFalse(isOpen(calls.get(0))); assertFalse(isOpen(calls.get(1)));
		}
	}

	@Test
	public void closeRetainsAcceptedSourceUntilTerminalAndCancelsUnstartedBatch() throws Exception
	{
		List<FullDataSourceV2> calls = new ArrayList<>();
		CompletableFuture<Void> accepted = new CompletableFuture<>();
		PausedCache cache = new PausedCache((data, hashes) -> { calls.add(data); return accepted; });
		write(cache, 0, 10, 11); cache.flush(); cache.start(0);
		write(cache, 0, 20, 22); cache.flush(); cache.start(1);
		FullDataSourceV2 queued = source(cache.detached.get(1));
		cache.close();
		assertTrue(isOpen(calls.get(0))); // accepted worker still owns this source
		try { write(cache, 0, 30, 33); fail("Closed cache must reject new ownership"); }
		catch (CancellationException expected) { }
		accepted.completeExceptionally(new CancellationException("updater closed"));
		assertEquals(1, calls.size());
		assertFalse(isOpen(calls.get(0))); assertFalse(isOpen(queued));
		cache.close();
	}

	@Test(timeout = 10000)
	public void closeDuringContainerInsertionDoesNotLeaveAnUnregisteredPendingBatch() throws Exception
	{
		CountDownLatch beforeInsert = new CountDownLatch(1);
		CountDownLatch resumeInsert = new CountDownLatch(1);
		List<FullDataSourceV2> created = new ArrayList<>();
		ExecutorService worker = Executors.newSingleThreadExecutor();
		DelayedDataSourceSaveCache cache = new DelayedDataSourceSaveCache((data, hashes) ->
		{
			fail("Closed cache must not submit the late batch");
			return CompletableFuture.completedFuture(null);
		}, Integer.MAX_VALUE)
		{
			@Override protected DataSourceSaveObjContainer createEmptySaveObjContainer(long pos)
			{
				DataSourceSaveObjContainer container = super.createEmptySaveObjContainer(pos);
				try
				{
					created.add(source(container));
					beforeInsert.countDown();
					if (!resumeInsert.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Insert was not resumed"); }
				}
				catch (Exception failure) { throw new AssertionError(failure); }
				return container;
			}
		};
		try
		{
			Future<?> pending = worker.submit(() ->
			{
				try (FullDataSourceV2 input = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) 6, 0, 0)))
				{
					cache.writeChunkToMemoryAndQueueSave(input, new DhChunkPos(0, 0), 7);
				}
			});
			assertTrue(beforeInsert.await(5, TimeUnit.SECONDS));
			cache.close(); // must return while the writer is deliberately paused
			resumeInsert.countDown();
			pending.get(5, TimeUnit.SECONDS);
			assertEquals(0, cache.getUnsavedCount());
			assertEquals(1, created.size());
			assertFalse(isOpen(created.get(0)));
		}
		finally
		{
			resumeInsert.countDown();
			cache.close();
			worker.shutdownNow();
		}
	}

	private static void write(PausedCache cache, int chunkX, int height, int hash) throws Exception
	{
		try (FullDataSourceV2 data = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) 6, 0, 0)))
		{
			int id = data.mapping.addIfNotPresentAndGetId(new TestBiomeWrapper("batch"), new TestBlockStateWrapper("solid"));
			LongArrayList column = data.getColumnAtRelPos(chunkX * 16, 0);
			column.add(FullDataPointUtil.encode(id, height, 0, (byte) 0, (byte) 15));
			data.setSingleColumn(column, chunkX * 16, 0, EDhApiWorldGenerationStep.LIGHT, EDhApiWorldCompressionMode.VISUALLY_EQUAL);
			cache.writeChunkToMemoryAndQueueSave(data, new DhChunkPos(chunkX, 0), hash);
		}
	}

	private static boolean isOpen(FullDataSourceV2 data) throws Exception
	{
		Field poolField = AbstractPhantomArrayList.class.getDeclaredField("phantomArrayListPool"); poolField.setAccessible(true);
		Field refField = AbstractPhantomArrayList.class.getDeclaredField("phantomReference"); refField.setAccessible(true);
		Object pool = poolField.get(data);
		Field activeField = pool.getClass().getDeclaredField("phantomRefToCheckout"); activeField.setAccessible(true);
		return ((Map<?, ?>) activeField.get(pool)).containsKey(refField.get(data));
	}

	private static FullDataSourceV2 source(DelayedDataSourceSaveCache.DataSourceSaveObjContainer container) throws Exception
	{
		Field field = container.getClass().getDeclaredField("dataSource"); field.setAccessible(true);
		return (FullDataSourceV2) field.get(container);
	}

	private static final class PausedCache extends DelayedDataSourceSaveCache
	{
		final List<DataSourceSaveObjContainer> detached = new ArrayList<>();
		PausedCache(ISaveBatchDataSourceFunc save) { super(save, Integer.MAX_VALUE); }
		@Override protected void handleDataSourceRemoval(DataSourceSaveObjContainer container) { this.detached.add(container); }
		void start(int index) { super.handleDataSourceRemoval(this.detached.get(index)); }
	}
}
