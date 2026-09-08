package com.seibel.distanthorizons.core.file.fullDatafile.V2;

import com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics;
import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.IDataSourceUpdateListenerFunc;
import com.seibel.distanthorizons.core.generation.DhLightingEngine;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.threading.PositionalLockProvider;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class FullDataUpdaterV2 implements IDebugRenderable, AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	protected final PositionalLockProvider updateLockProvider = new PositionalLockProvider();
	/**
	 * generally just used for debugging,
	 * keeps track of which positions are currently locked.
	 */
	public final Set<Long> lockedPosSet = ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<Long, AtomicInteger> queuedUpdateCountsByPos = new ConcurrentHashMap<>();
	
	private final ArrayList<IDataSourceUpdateListenerFunc<FullDataSourceV2>> dateSourceUpdateListeners = new ArrayList<>();
	/** using a read write lock since most operations will be reads */
	private final ReentrantReadWriteLock updateListenerReadWriteLock = new ReentrantReadWriteLock();
	
	private final String levelId;
	private final AtomicBoolean isShutdownRef = new AtomicBoolean(false);
	private final Object lifecycleLock = new Object();
	private int activeWrites;
	private final Set<UpdateTask> pendingUpdates = ConcurrentHashMap.newKeySet();
	private final CompletableFuture<Void> closedFuture = new CompletableFuture<>();
	
	private final FullDataSourceProviderV2 provider;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public FullDataUpdaterV2(FullDataSourceProviderV2 provider, String levelId)
	{
		this.provider = provider;
		this.levelId = levelId;
		
	}
	
	//endregion
	
	
	
	//===============//
	// data updating //
	//===============//
	//region
	
	/**
	 * Completes normally only after the input was merged and its database write acknowledged.
	 * The caller retains ownership of inputDataSource until this future terminates.
	 * Cancellation succeeds only before the worker starts using that input.
	 */
	public CompletableFuture<Void> updateDataSourceAsync(@NotNull FullDataSourceV2 inputDataSource)
	{ return this.updateDataSourceAsync(inputDataSource, false); }

	/** Cold remote snapshots may initialize missing records only. Completion permits a skipped write. */
	public CompletableFuture<Void> updateDataSourceIfAbsentAsync(@NotNull FullDataSourceV2 inputDataSource)
	{ return this.updateDataSourceAsync(inputDataSource, true); }

	private CompletableFuture<Void> updateDataSourceAsync(@NotNull FullDataSourceV2 inputDataSource, boolean onlyIfAbsent)
	{
		Objects.requireNonNull(inputDataSource, "inputDataSource");
		if (this.isShutdownRef.get()) { return failedUpdate(new CancellationException("Data updater is closed")); }
		AbstractExecutorService executor = this.getUpdateExecutor();
		if (executor == null || executor.isShutdown())
		{
			return failedUpdate(new RejectedExecutionException("Data update executor is unavailable"));
		}

		UpdateTask task = new UpdateTask(inputDataSource, onlyIfAbsent);
		this.markUpdateStart(task.pos);
		this.pendingUpdates.add(task);
		if (this.isShutdownRef.get()) { task.cancelBeforeStart(new CancellationException("Data updater is closed")); }
		else
		{
			try { executor.execute(task); }
			catch (RuntimeException | Error failure)
			{
				task.cancelBeforeStart(failure);
				if (failure instanceof Error) { throw (Error) failure; }
			}
		}
		return task.future;
	}

	/** Kept as an overridable executor boundary for controlled lifecycle tests. */
	protected AbstractExecutorService getUpdateExecutor() { return ThreadPoolUtil.getChunkToLodBuilderExecutor(); }

	private static CompletableFuture<Void> failedUpdate(Throwable failure)
	{
		CompletableFuture<Void> result = new CompletableFuture<>();
		result.completeExceptionally(failure);
		return result;
	}

	/** Migration may delete its source only after this strict, synchronous write succeeds. */
	void updateDataSourceForMigration(@NotNull FullDataSourceV2 inputData) { this.updateDataSource(inputData); }

	/** Returns only after an acknowledged write; shutdown and write errors propagate. */
	public void updateDataSource(@NotNull FullDataSourceV2 inputData)
	{ this.updateDataSource(inputData, false); }

	private void updateDataSource(@NotNull FullDataSourceV2 inputData, boolean onlyIfAbsent)
	{
		Objects.requireNonNull(inputData, "inputData");
		long updatePos = inputData.getPos();
		ReentrantLock updateLock = this.updateLockProvider.getLock(updatePos);
		this.beginWrite();
		boolean locked = false;
		boolean committed = false;
        long diagnosticStart = MiraiDiagnostics.startTiming();
		try
		{
			updateLock.lock();
			locked = true;
			if (this.isShutdownRef.get()) { throw new CancellationException("Data updater closed before write"); }
			this.lockedPosSet.add(updatePos);
			// Check at commit time, under the same lock as local/live updates. A
			// timestamp check before queuing leaves a stale-snapshot overwrite race.
            if (onlyIfAbsent && this.provider.repo.existsWithKeyChecked(updatePos))
            {
                MiraiDiagnostics.section(MiraiDiagnostics.Counter.COLD_PRESERVED_LOCAL, updatePos, "local_record_already_exists");
                return;
            }
			try (FullDataSourceV2 recipientDataSource = this.provider.get(updatePos))
			{
				if (recipientDataSource == null) { throw new CancellationException("Data destination is unavailable"); }
				boolean dataModified = recipientDataSource.updateFromDataSource(inputData);
				if (dataModified && DhSectionPos.getDetailLevel(updatePos) == DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL)
				{
					DhLightingEngine.INSTANCE.bakeDataSourceSkyLight(recipientDataSource,
							this.provider.levelWrapper.hasSkyLight() ? LodUtil.MAX_MC_LIGHT : LodUtil.MIN_MC_LIGHT);
				}
				// An unchanged merge can target an empty, never-persisted record. Always
				// obtain an actual write acknowledgement before hashes/migration advance.
				try (FullDataSourceV2DTO dto = this.createDtoFromDataSource(recipientDataSource))
				{
					if (dto == null) { throw new IllegalStateException("Unable to encode data source"); }
					if (!this.provider.repo.saveChecked(dto)) { throw new IllegalStateException("Data source write was not committed"); }
					committed = true;
                    MiraiDiagnostics.count(MiraiDiagnostics.Counter.DB_COMMITTED);
				}
				if (dataModified) { this.notifyCommittedUpdate(recipientDataSource); }
			}
		}
		catch (Throwable failure)
		{
			// Fatal VM/linkage errors must reach the worker/caller even after a commit.
			// Such failure does not imply that the acknowledged write was rolled back.
			if (failure instanceof Error) { throw (Error) failure; }
			if (committed)
			{
				// Cleanup/observation cannot turn a completed write into a retryable
				// persistence failure. In particular, migration must not replay side effects.
				LOGGER.error("Post-commit cleanup failed at [" + DhSectionPos.toString(updatePos) + "].", failure);
			}
			else
			{
                MiraiDiagnostics.count(failure instanceof CancellationException ? MiraiDiagnostics.Counter.DB_CANCELLED : MiraiDiagnostics.Counter.DB_FAILED);
                if (!(failure instanceof CancellationException)) { MiraiDiagnostics.error(MiraiDiagnostics.ErrorKind.DATABASE, failure); }
				if (failure instanceof RuntimeException) { throw (RuntimeException) failure; }
				throw new CompletionException(failure);
			}
		}
		finally
		{
			if (locked)
			{
				this.lockedPosSet.remove(updatePos);
				updateLock.unlock();
			}
			this.endWrite();
            MiraiDiagnostics.finishTiming(MiraiDiagnostics.Metric.LOD_UPDATE_WALL, diagnosticStart);
		}
	}

	private void notifyCommittedUpdate(FullDataSourceV2 dataSource)
	{
		ArrayList<IDataSourceUpdateListenerFunc<FullDataSourceV2>> listeners;
		ReentrantReadWriteLock.ReadLock readLock = this.updateListenerReadWriteLock.readLock();
		readLock.lock();
		try { listeners = new ArrayList<>(this.dateSourceUpdateListeners); }
		finally { readLock.unlock(); }
		// Do not call external listeners while holding the registration lock: listeners
		// may add/remove themselves. Failures do not suppress subsequent observers.
		for (IDataSourceUpdateListenerFunc<FullDataSourceV2> listener : listeners)
		{
			if (listener == null) { continue; }
			try { listener.OnDataSourceUpdated(dataSource); }
			catch (RuntimeException failure) { LOGGER.error("Data update listener failed after commit at [" + DhSectionPos.toString(dataSource.getPos()) + "].", failure); }
		}
	}

	private FullDataSourceV2DTO createDtoFromDataSource(FullDataSourceV2 dataSource)
	{
		try
		{
			EDhApiDataCompressionMode compressionModeEnum = Config.Common.LodBuilding.dataCompression.get();
			return FullDataSourceV2DTO.CreateFromDataSource(dataSource, compressionModeEnum);
		}
		catch (IOException failure) { throw new CompletionException("Unable to encode data source", failure); }
	}

	private void beginWrite()
	{
		synchronized (this.lifecycleLock)
		{
			if (this.isShutdownRef.get()) { throw new CancellationException("Data updater is closed"); }
			this.activeWrites++;
		}
	}

	private void endWrite()
	{
		synchronized (this.lifecycleLock) { this.activeWrites--; }
		this.tryCompleteClose();
	}

	private void tryCompleteClose()
	{
		boolean complete;
		synchronized (this.lifecycleLock)
		{
			complete = this.isShutdownRef.get() && this.activeWrites == 0 && this.pendingUpdates.isEmpty();
		}
		if (complete) { this.closedFuture.complete(null); }
	}

	private final class UpdateTask implements Runnable
	{
		private final long pos;
		private FullDataSourceV2 input;
		private final boolean onlyIfAbsent;
		private final AtomicInteger state = new AtomicInteger(); // queued=0, running=1, terminal=2
		private final UpdateFuture future = new UpdateFuture();

		private UpdateTask(FullDataSourceV2 input, boolean onlyIfAbsent)
		{ this.input = input; this.pos = input.getPos(); this.onlyIfAbsent = onlyIfAbsent; }

		@Override
		public void run()
		{
			if (!this.state.compareAndSet(0, 1)) { return; }
			Throwable failure = null;
			try { FullDataUpdaterV2.this.updateDataSource(this.input, this.onlyIfAbsent); }
			catch (Throwable error) { failure = error; }
			finally
			{
				this.state.set(2);
				this.finish(failure);
			}
			if (failure instanceof Error) { throw (Error) failure; }
		}

		private boolean cancelBeforeStart(Throwable failure)
		{
			if (!this.state.compareAndSet(0, 2)) { return false; }
			this.finish(failure);
			return true;
		}

		private void finish(Throwable failure)
		{
			this.input = null;
			FullDataUpdaterV2.this.markUpdateEnd(this.pos);
			this.future.finish(failure);
			FullDataUpdaterV2.this.pendingUpdates.remove(this);
			FullDataUpdaterV2.this.tryCompleteClose();
		}

		private final class UpdateFuture extends CompletableFuture<Void>
		{
			private void finish(Throwable failure)
			{
				if (failure == null) { super.complete(null); }
				else { super.completeExceptionally(failure); }
			}
			@Override public boolean cancel(boolean mayInterruptIfRunning)
			{ return UpdateTask.this.cancelBeforeStart(new CancellationException("Data update cancelled before execution")); }
			@Override public boolean complete(Void value) { return false; }
			@Override public boolean completeExceptionally(Throwable failure) { return false; }
			@Override public void obtrudeValue(Void value) { throw new UnsupportedOperationException("Data update completion is owned by its worker"); }
			@Override public void obtrudeException(Throwable failure) { throw new UnsupportedOperationException("Data update completion is owned by its worker"); }
		}
	}

	//endregion
	
	
	
	//========//
	// events //
	//========//
	//region
	
	public void addDataSourceUpdateListener(IDataSourceUpdateListenerFunc<FullDataSourceV2> listener)
	{
		ReentrantReadWriteLock.WriteLock writeLock = this.updateListenerReadWriteLock.writeLock();
		try
		{
			writeLock.lock();
			this.dateSourceUpdateListeners.add(listener);
		}
		finally
		{
			writeLock.unlock();
		}
	}
	public void removeDataSourceUpdateListener(IDataSourceUpdateListenerFunc<FullDataSourceV2> listener)
	{
		ReentrantReadWriteLock.WriteLock writeLock = this.updateListenerReadWriteLock.writeLock();
		try
		{
			writeLock.lock();
			this.dateSourceUpdateListeners.remove(listener);
		}
		finally
		{
			writeLock.unlock();
		}
	}
	
	//endregion
	
	
	
	//==================//
	// debugger methods //
	//==================//
	//region
	
	/** used for debugging to track which positions are queued for updating */
	private void markUpdateStart(long dataSourcePos)
	{
		this.queuedUpdateCountsByPos.compute(dataSourcePos, (pos, atomicCount) ->
		{
			if (atomicCount == null)
			{
				atomicCount = new AtomicInteger(0);
			}
			atomicCount.incrementAndGet();
			return atomicCount;
		});
	}
	/** used for debugging to track which positions are queued for updating */
	private void markUpdateEnd(long dataSourcePos)
	{
		this.queuedUpdateCountsByPos.compute(dataSourcePos, (pos, atomicCount) ->
		{
			if (atomicCount != null && atomicCount.decrementAndGet() <= 0)
			{
				atomicCount = null;
			}
			return atomicCount;
		});
	}
	
	//endregion
	
	
	
	//===========//
	// overrides //
	//===========//
	//region
	
	@Override
	public void debugRender(AbstractDebugWireframeRenderer renderer)
	{
		this.lockedPosSet
				.forEach((pos) -> { renderer.renderBox(new AbstractDebugWireframeRenderer.Box(pos, -32f, 74f, 0.15f, Color.PINK)); });
		
		this.queuedUpdateCountsByPos
				.forEach((pos, updateCountRef) -> { renderer.renderBox(new AbstractDebugWireframeRenderer.Box(pos, -32f, 80f + (updateCountRef.get() * 16f), 0.20f, Color.WHITE)); });
	}
	
	@Override
	public void close()
	{
		this.isShutdownRef.set(true);
		for (UpdateTask task : this.pendingUpdates)
		{
			task.cancelBeforeStart(new CancellationException("Data updater closed before execution"));
		}
		this.tryCompleteClose();
	}

	/** Resolves after accepted workers have released their input and recipient ownership. */
	public CompletableFuture<Void> getClosedFuture() { return this.closedFuture; }
	
	//endregion
	
	
	
}
