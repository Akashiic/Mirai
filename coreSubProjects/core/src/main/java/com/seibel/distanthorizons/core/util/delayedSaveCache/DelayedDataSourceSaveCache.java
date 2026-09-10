package com.seibel.distanthorizons.core.util.delayedSaveCache;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Coalesces full data and its chunk hashes under one positional ownership lock. */
public class DelayedDataSourceSaveCache extends AbstractDelayedSaveCache<FullDataSourceV2, DelayedDataSourceSaveCache.DataSourceSaveObjContainer>
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().name(DelayedDataSourceSaveCache.class.getSimpleName()).build();
	private final ISaveBatchDataSourceFunc onSaveTimeoutAsyncFunc;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final Object writerLifecycle = new Object();
	private int activeWriters;
	private final ConcurrentHashMap<Long, CompletableFuture<Void>> saveTailByPosition = new ConcurrentHashMap<>();

	public DelayedDataSourceSaveCache(@NotNull ISaveDataSourceFunc onSaveTimeoutAsyncFunc, int saveDelayInMs)
	{
		this((source, hashes) -> onSaveTimeoutAsyncFunc.saveAsync(source), saveDelayInMs);
	}

	public DelayedDataSourceSaveCache(@NotNull ISaveBatchDataSourceFunc onSaveTimeoutAsyncFunc, int saveDelayInMs)
	{
		super(saveDelayInMs);
		this.onSaveTimeoutAsyncFunc = Objects.requireNonNull(onSaveTimeoutAsyncFunc);
	}

	public void writeToMemoryAndQueueSave(@NotNull FullDataSourceV2 inputObj)
	{
		this.writeToMemoryAndQueueSave(inputObj.getPos(), inputObj);
	}

	@Override
	public DataSourceSaveObjContainer writeToMemoryAndQueueSave(long inputPos, @Nullable FullDataSourceV2 inputObj)
	{
		this.beginWrite();
		try { return super.writeToMemoryAndQueueSave(inputPos, inputObj); }
		finally { this.endWrite(); }
	}

	/** Input data and this hash enter exactly the same batch before it can be detached. */
	public void writeChunkToMemoryAndQueueSave(@NotNull FullDataSourceV2 inputObj, DhChunkPos chunkPos, int chunkHash)
	{
		long sectionPos = inputObj.getPos();
		if (DhSectionPos.encodeContaining(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, chunkPos) != sectionPos)
		{
			throw new IllegalArgumentException("Chunk hash does not belong to this full-resolution section");
		}
		ReentrantLock lock = this.saveLockContainer.getLockForPos(sectionPos);
		this.beginWrite();
		try
		{
			lock.lock();
			try
			{
				DataSourceSaveObjContainer container = super.writeToMemoryAndQueueSave(sectionPos, inputObj);
				container.chunkHashes.put(chunkPos, chunkHash);
			}
			finally { lock.unlock(); }
		}
		finally { this.endWrite(); }
	}

	private void beginWrite()
	{
		synchronized (this.writerLifecycle)
		{
			this.ensureOpenForWrite();
			this.activeWriters++;
		}
	}

	private void endWrite()
	{
		boolean drain;
		synchronized (this.writerLifecycle)
		{
			this.activeWriters--;
			drain = this.closed.get() && this.activeWriters == 0;
		}
		if (drain) { this.flush(); }
	}

	@Override
	protected void ensureOpenForWrite()
	{
		if (this.closed.get()) { throw new CancellationException("Delayed full-data cache is closed"); }
	}

	@Override
	protected DataSourceSaveObjContainer createEmptySaveObjContainer(long inputPos)
	{
		return new DataSourceSaveObjContainer(inputPos);
	}

	@Override
	protected boolean requiresRemovalGracePeriod() { return false; }

	@Override
	protected void onContainerDetached(long pos, @NotNull DataSourceSaveObjContainer container)
	{
		// Detach order, not callback arrival order, determines each section's save order.
		container.previousSave = this.saveTailByPosition.put(pos, container.finished);
		container.savedHashes = Collections.unmodifiableMap(new HashMap<>(container.chunkHashes));
	}

	@Override
	protected void handleDataSourceRemoval(@NotNull DataSourceSaveObjContainer container)
	{
		if (!container.handlingStarted.compareAndSet(false, true)) { return; }
		CompletableFuture<Void> previous = container.previousSave;
		if (previous == null) { previous = CompletableFuture.completedFuture(null); }
		previous.handle((ignored, previousFailure) -> null).thenCompose(ignored ->
		{
			if (this.closed.get()) { return failedFuture(new CancellationException("Delayed full-data cache is closed")); }
			try
			{
				return Objects.requireNonNull(this.onSaveTimeoutAsyncFunc.saveAsync(container.dataSource, container.savedHashes), "Save callback returned null");
			}
			catch (Throwable failure) { return failedFuture(failure); }
		}).whenComplete((ignored, failure) ->
		{
			Throwable terminalFailure = failure;
			try { container.dataSource.close(); }
			catch (Throwable closeFailure)
			{
				if (terminalFailure == null) { terminalFailure = closeFailure; }
				else { terminalFailure.addSuppressed(closeFailure); }
			}
			this.saveTailByPosition.remove(container.dataSource.getPos(), container.finished);
			if (terminalFailure == null) { container.finished.complete(null); }
			else
			{
				container.finished.completeExceptionally(terminalFailure);
				if (!ExceptionUtil.isShutdownException(terminalFailure))
				{
					LOGGER.error("Unable to persist full-data batch [" + DhSectionPos.toString(container.dataSource.getPos()) + "].", terminalFailure);
				}
			}
		});
	}

	private static <T> CompletableFuture<T> failedFuture(Throwable failure)
	{
		CompletableFuture<T> result = new CompletableFuture<>();
		result.completeExceptionally(failure);
		return result;
	}

	@Override
	public void close()
	{
		boolean drain;
		synchronized (this.writerLifecycle)
		{
			if (!this.closed.compareAndSet(false, true)) { return; }
			drain = this.activeWriters == 0;
		}
		super.close();
		// Queued batches are detached and released; an accepted worker keeps its source
		// until its own terminal future, with no blocking join on the world/render thread.
		if (drain) { this.flush(); }
	}

	@FunctionalInterface
	public interface ISaveDataSourceFunc
	{
		CompletableFuture<Void> saveAsync(@NotNull FullDataSourceV2 inputDataSource);
	}

	@FunctionalInterface
	public interface ISaveBatchDataSourceFunc
	{
		/** The map is an immutable snapshot belonging to this datasource only. */
		CompletableFuture<Void> saveAsync(@NotNull FullDataSourceV2 inputDataSource, Map<DhChunkPos, Integer> chunkHashes);
	}

	public static class DataSourceSaveObjContainer extends AbstractSaveObjContainer<FullDataSourceV2>
	{
		private final @NotNull FullDataSourceV2 dataSource;
		private final Map<DhChunkPos, Integer> chunkHashes = new HashMap<>();
		private Map<DhChunkPos, Integer> savedHashes = Collections.emptyMap();
		private CompletableFuture<Void> previousSave;
		private final CompletableFuture<Void> finished = new CompletableFuture<>();
		private final AtomicBoolean handlingStarted = new AtomicBoolean();

		public DataSourceSaveObjContainer(long inputPos)
		{
			this.dataSource = FullDataSourceV2.createEmpty(inputPos);
		}

		@Override
		public void update(@Nullable FullDataSourceV2 newObj)
		{
			this.dataSource.updateFromDataSource(Objects.requireNonNull(newObj));
		}
	}
}
