/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics;
import com.google.common.cache.CacheBuilder;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.structure.ISaveStructure;
import com.seibel.distanthorizons.core.generation.queues.RemoteWorldRetrievalQueue;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.generation.queues.LodRequestModule;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.generation.tasks.ERetrievalResultState;
import com.seibel.distanthorizons.core.multiplayer.client.SyncOnLoadRequestQueue;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Synchronizes cached server LODs on ordinary render reads, including an empty client cache.
 * Generating missing server data remains owned by {@link LodRequestModule} and {@link RemoteWorldRetrievalQueue}.
 */
public class RemoteFullDataSourceProvider extends GeneratedFullDataSourceProvider
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	@Nullable
	private final SyncOnLoadRequestQueue syncOnLoadRequestQueue;
	private final Set<Long> pendingPositions = ConcurrentHashMap.newKeySet();
	/** Position-only intents; no timestamp, future, payload or recurring timer per section. */
	private final LinkedHashSet<Long> deferredPositions = new LinkedHashSet<>();
	private final AtomicBoolean deferredWorkerPending = new AtomicBoolean();
	private volatile boolean synchronizationClosed;
	private static final int DEFERRED_BATCH_SIZE = 32;
	private final Set<Long> visitedPositions = Collections.newSetFromMap(CacheBuilder.newBuilder()
			.maximumSize(16384)
			.expireAfterWrite(20, TimeUnit.MINUTES)
			.<Long, Boolean>build()
			.asMap());
	private final Set<Long> unavailablePositions = Collections.newSetFromMap(CacheBuilder.newBuilder()
			.maximumSize(16384)
			.expireAfterWrite(1, TimeUnit.MINUTES)
			.<Long, Boolean>build().asMap());

	
	
	
	//=============//
	// constructor //
	//=============//
	
	public RemoteFullDataSourceProvider(
			IDhLevel level, ISaveStructure saveStructure, @Nullable File saveDirOverride, 
			@Nullable SyncOnLoadRequestQueue syncOnLoadRequestQueue
		) throws SQLException, IOException
	{
		super(level, saveStructure, saveDirOverride);
		this.syncOnLoadRequestQueue = syncOnLoadRequestQueue;
	}
	
	
	
	//==================//
	// override methods //
	//==================//
	
	@Override
	public boolean canQueueRetrievalNow() { return this.canQueueRetrievalNow(true); }
	
	@Override
	@Nullable
	public FullDataSourceV2 get(long pos)
	{
		if (this.syncOnLoadRequestQueue != null && !this.synchronizationStopped())
		{
			if (!this.trySynchronize(pos)) { this.deferPosition(pos); }
		}
		// Local data remains available regardless of handshake or synchronization permission.
		return super.get(pos);
	}

	private boolean synchronizationStopped()
	{ return this.synchronizationClosed || this.isShutdownRef.get(); }

	/** True means no deferred intent is needed (already pending, checked, or admitted). */
	private boolean trySynchronize(long pos)
	{
		if (this.synchronizationStopped() || !this.syncOnLoadRequestQueue.canRetainDeferredRequests()) { return true; }
		if (this.unavailablePositions.contains(pos) || this.visitedPositions.contains(pos)
			|| !this.pendingPositions.add(pos)) { return true; }
		boolean submitted = false;
		try
		{
			// Avoid the additional timestamp query and full request allocation before handshake.
			if (!this.syncOnLoadRequestQueue.hasSyncRequestCapacity()) { return false; }
			Long timestamp = this.getTimestampForPos(pos);
			// Do not hold the queue monitor during SQL. Close/admission/publication share this
			// monitor; a close or capacity change during SQL cannot admit stale work.
			synchronized (this.syncOnLoadRequestQueue)
			{
				if (this.synchronizationStopped()) { return true; }
				CompletableFuture<DataSourceRetrievalResult> future = this.syncOnLoadRequestQueue.trySubmitRequest(pos,
					timestamp == null ? FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP : timestamp);
				if (future == null) { return false; }
				submitted = true;
				this.removeDeferredPosition(pos);
				MiraiDiagnostics.section(timestamp == null ? MiraiDiagnostics.Counter.COLD_REQUEST : MiraiDiagnostics.Counter.REFRESH_REQUEST,
					pos, "read_existing_server_cache");
				future.whenComplete((result, failure) -> this.completeSynchronization(pos, timestamp, result, failure));
				return true;
			}
		}
		finally { if (!submitted) { this.pendingPositions.remove(pos); } }
	}

	private void completeSynchronization(long pos, Long timestamp, DataSourceRetrievalResult result, Throwable failure)
	{
		try
		{
			synchronized (this.syncOnLoadRequestQueue)
			{
				if (this.synchronizationStopped() || !this.syncOnLoadRequestQueue.canRetainDeferredRequests())
				{
					if (result != null && result.dataSource != null) { result.dataSource.close(); }
					return;
				}
				if (failure != null && ExceptionUtil.ensureUnwrap(failure) instanceof CancellationException)
				{
					// Queue cancellation already owns NETWORK_CANCELLED. Preserve the
					// existing short retry backoff (e.g. out-of-radius cancellation),
					// without counting a remote failure or marking the position visited.
					this.unavailablePositions.add(pos);
					return;
				}
				if (failure == null && result.state == ERetrievalResultState.NO_UPDATE && timestamp != null)
				{
					MiraiDiagnostics.section(MiraiDiagnostics.Counter.REFRESH_UNCHANGED, pos, "server_no_update_existing_local");
					this.visitedPositions.add(pos);
				}
				else if (failure != null || result.state == ERetrievalResultState.NO_UPDATE)
				{
					MiraiDiagnostics.section(failure == null ? MiraiDiagnostics.Counter.COLD_MISSING : MiraiDiagnostics.Counter.REMOTE_FAILED,
						pos, failure == null ? "server_no_data_unknown_is_not_air" : "request_failed");
					this.unavailablePositions.add(pos);
				}
				else if (result.state == ERetrievalResultState.SUCCESS && result.dataSource != null)
				{
					this.visitedPositions.add(pos);
					this.saveRemoteUpdate(result.dataSource, timestamp == null);
				}
			}
		}
		finally { this.pendingPositions.remove(pos); }
	}

	private void deferPosition(long pos)
	{
		// Never acquire the network queue while holding deferredPositions: stopSynchronization
		// owns the opposite (queue -> deferred) order. Recheck the volatile close fence inside.
		if (this.synchronizationStopped() || !this.syncOnLoadRequestQueue.canRetainDeferredRequests()) { return; }
		synchronized (this.deferredPositions)
		{
			if (this.synchronizationStopped() || this.deferredPositions.contains(pos)) { return; }
			if (this.deferredPositions.size() >= this.syncOnLoadRequestQueue.getRequestCapacity())
			{
				Iterator<Long> iterator = this.deferredPositions.iterator();
				iterator.next();
				iterator.remove();
				// Eviction only forgets intent, never cached terrain. A later ordinary
				// lookup of the evicted position can request synchronization again.
				MiraiDiagnostics.count(MiraiDiagnostics.Counter.SYNC_DEFERRED_OVERFLOW);
			}
			this.deferredPositions.add(pos);
		}
	}

	private void removeDeferredPosition(long pos)
	{ synchronized (this.deferredPositions) { this.deferredPositions.remove(pos); } }

	public int getDeferredSyncCount()
	{ synchronized (this.deferredPositions) { return this.deferredPositions.size(); } }

	protected Executor getSyncFileExecutor() { return ThreadPoolUtil.getFileHandlerExecutor(); }

	/** Called by the existing current-level tick, outside the queue's monitor. */
	public void tickDeferredSynchronization()
	{
		if (this.syncOnLoadRequestQueue == null || this.synchronizationStopped()) { return; }
		if (!this.syncOnLoadRequestQueue.canRetainDeferredRequests())
		{
			synchronized (this.deferredPositions) { this.deferredPositions.clear(); }
			return;
		}
		if (this.getDeferredSyncCount() == 0 || !this.syncOnLoadRequestQueue.hasSyncRequestCapacity()
			|| !this.deferredWorkerPending.compareAndSet(false, true)) { return; }
		try
		{
			Executor executor = this.getSyncFileExecutor();
			if (executor == null) { throw new RejectedExecutionException("LOD sync file worker unavailable"); }
			executor.execute(this::drainDeferredSynchronization);
		}
		catch (RuntimeException | Error failure)
		{
			this.deferredWorkerPending.set(false);
			if (!(failure instanceof RejectedExecutionException)) { throw failure; }
			// Retain all intents. Only a later ordinary tick may retry scheduling.
		}
	}

	private void drainDeferredSynchronization()
	{
		try
		{
			long[] batch = new long[DEFERRED_BATCH_SIZE];
			int size = 0;
			synchronized (this.deferredPositions)
			{
				for (Long pos : this.deferredPositions)
				{
					batch[size++] = pos;
					if (size == batch.length) { break; }
				}
			}
			for (int i = 0; i < size; i++)
			{
				if (this.synchronizationStopped() || !this.syncOnLoadRequestQueue.canRetainDeferredRequests()
						|| !this.syncOnLoadRequestQueue.hasSyncRequestCapacity()) { break; }
				if (!this.trySynchronize(batch[i])) { break; }
				this.removeDeferredPosition(batch[i]);
			}
		}
		finally { this.deferredWorkerPending.set(false); }
	}

	/** Fence deferred admissions and response publication before dismantling the level. */
	public void stopSynchronization()
	{
		if (this.syncOnLoadRequestQueue == null) { return; }
		synchronized (this.syncOnLoadRequestQueue)
		{
			this.synchronizationClosed = true;
			synchronized (this.deferredPositions) { this.deferredPositions.clear(); }
			this.syncOnLoadRequestQueue.close();
		}
	}
	

	private void saveRemoteUpdate(FullDataSourceV2 dataSource, boolean onlyIfAbsent)
	{
		try
		{
			(onlyIfAbsent ? this.updateDataSourceIfAbsentAsync(dataSource) : this.updateDataSourceAsync(dataSource)).whenComplete((unused, failure) ->
			{
				try
				{
					if (failure != null) this.remoteUpdateFailed(dataSource.getPos(), failure);
				}
				finally { dataSource.close(); }
			});
		}
		catch (RuntimeException | Error failure)
		{
			// The updater did not take asynchronous ownership.
			try { this.remoteUpdateFailed(dataSource.getPos(), failure); }
			finally { dataSource.close(); }
			throw failure;
		}
	}

	private void remoteUpdateFailed(long pos, Throwable failure)
	{
		// No automatic retry loop: a later ordinary lookup may request this
		// position again through the existing bounded sync-on-load queue.
		this.unavailablePositions.add(pos);
		this.visitedPositions.remove(pos);
		if (!ExceptionUtil.isInterruptOrReject(failure))
		{
			LOGGER.error("Remote LOD update was not persisted at [" + pos + "]. A later lookup may retry.", failure);
		}
	}

	//==========//
	// shutdown //
	//==========//
	
	@Override
	public void close()
	{
		this.stopSynchronization();
		super.close();
	}
	
}
