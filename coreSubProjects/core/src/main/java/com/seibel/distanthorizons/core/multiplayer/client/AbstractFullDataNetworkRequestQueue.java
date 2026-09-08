package com.seibel.distanthorizons.core.multiplayer.client;

import com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.exceptions.RateLimitedException;
import com.seibel.distanthorizons.core.network.exceptions.RequestOutOfRangeException;
import com.seibel.distanthorizons.core.network.exceptions.RequestRejectedException;
import com.seibel.distanthorizons.core.network.exceptions.SectionRequiresSplittingException;
import com.seibel.distanthorizons.core.network.session.SessionClosedException;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateLimiter;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.world.DhApiWorldProxy;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public abstract class AbstractFullDataNetworkRequestQueue implements IDebugRenderable, AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder()
			.fileLevelConfig(Config.Common.Logging.logNetworkEventToFile)
			.maxCountPerSecond(3)
			.build();
	
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final AbstractDebugWireframeRenderer DEBUG_RENDERER = SingletonInjector.INSTANCE.get(AbstractDebugWireframeRenderer.class);
	
	private static final int MAX_RETRY_ATTEMPTS = 3;
	
	protected static final long SHUTDOWN_TIMEOUT_SECONDS = 5;
	
	
	
	public final ClientNetworkState networkState;
	protected final DhClientLevel level;
	private final boolean changedOnly;
	
	private volatile CompletableFuture<Void> closingFuture = null;
	private final Supplier<Executor> responseExecutor;
	private final int requestCapacity;
	
	protected final ConcurrentMap<Long, NetRequestTask> waitingTasksBySectionPos = new ConcurrentHashMap<>();
	/**
	 * This semaphore prevents a given thread from accidentally locking on the same group
	 * multiple times, as the semaphore is tied to the given thread. <br>
	 * Reentrant Lock isn't used since it would allow the thread to lock on the same group. <br>
	 * the Short.MAX_VALUE is just a very large number that should be larger than the number of
	 * threads we'll have.
	 */
	private final Semaphore pendingTasksSemaphore = new Semaphore(Short.MAX_VALUE, true);
	
	private final AtomicInteger finishedRequests = new AtomicInteger();
	private final AtomicInteger failedRequests = new AtomicInteger();
	private final AtomicInteger noUpdateRequests = new AtomicInteger();
	private final ConfigEntry<Boolean> showDebugWireframeConfig;
	
	private final SupplierBasedRateLimiter<Void> rateLimiter = new SupplierBasedRateLimiter<>(this::getRequestRateLimit);
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public AbstractFullDataNetworkRequestQueue(
			ClientNetworkState networkState, DhClientLevel level,
			boolean changedOnly, ConfigEntry<Boolean> showDebugWireframeConfig)
	{
		this(networkState, level, changedOnly, showDebugWireframeConfig,
			ThreadPoolUtil::getNetworkCompressionExecutor, Integer.MAX_VALUE);
	}

	protected AbstractFullDataNetworkRequestQueue(ClientNetworkState networkState, DhClientLevel level,
		boolean changedOnly, ConfigEntry<Boolean> showDebugWireframeConfig,
		Supplier<Executor> responseExecutor, int requestCapacity)
	{
		if (requestCapacity <= 0) { throw new IllegalArgumentException("Request capacity must be positive"); }
		this.networkState = networkState;
		this.level = level;
		this.changedOnly = changedOnly;
		this.showDebugWireframeConfig = showDebugWireframeConfig;
		this.responseExecutor = responseExecutor;
		this.requestCapacity = requestCapacity;
		if (DEBUG_RENDERER != null) { DEBUG_RENDERER.register(this, this.showDebugWireframeConfig); }
	}
	
	
	
	//==================//
	// abstract methods //
	//==================//
	
	protected abstract int getRequestRateLimit();
	protected abstract boolean sectionInAllowedGenerationRadius(long sectionPos, DhBlockPos2D targetPos);
	protected abstract boolean onBeforeRequest(long sectionPos, CompletableFuture<DataSourceRetrievalResult> future);
	
	protected abstract String getQueueName();
	/** Rechecked before each send so live permission changes pause an existing batch. */
	protected boolean canSendRequests() { return true; }
	
	
	
	//====================//
	// request submitting //
	//====================//
	
	public synchronized CompletableFuture<DataSourceRetrievalResult> submitRequest(long sectionPos, @Nullable Long clientTimestamp)
	{
		CompletableFuture<DataSourceRetrievalResult> result = this.trySubmitRequestIfCapacity(sectionPos, clientTimestamp);
		return result != null ? result : failedRequest(new RejectedExecutionException(
			this.isClosed() ? "LOD request queue is closed" : "LOD request queue is full"));
	}

	/** Null means no task or future was created; callers may retain a compact intent. */
	@Nullable
	protected synchronized CompletableFuture<DataSourceRetrievalResult> trySubmitRequestIfCapacity(long sectionPos, @Nullable Long clientTimestamp)
	{
		if (this.closingFuture != null) { MiraiDiagnostics.count(MiraiDiagnostics.Counter.NETWORK_QUEUE_CLOSED); return null; }
		NetRequestTask existing = this.waitingTasksBySectionPos.get(sectionPos);
		if (existing != null) { return existing.future; }
		if (this.waitingTasksBySectionPos.size() >= this.requestCapacity)
		{ MiraiDiagnostics.count(MiraiDiagnostics.Counter.NETWORK_QUEUE_FULL); return null; }

		NetRequestTask task = new NetRequestTask(sectionPos, clientTimestamp);
		this.waitingTasksBySectionPos.put(sectionPos, task);
		task.future.whenComplete((result, failure) ->
		{
			this.waitingTasksBySectionPos.remove(sectionPos, task);
			if (failure != null)
			{
				boolean cancelled = ExceptionUtil.ensureUnwrap(failure) instanceof CancellationException;
				if (cancelled) { MiraiDiagnostics.count(MiraiDiagnostics.Counter.NETWORK_CANCELLED); }
				else { this.failedRequests.incrementAndGet(); }
				this.discardResponse(task.receivedResponse);
				CompletableFuture<FullDataSourceResponseMessage> networkFuture = task.networkDataSourceFuture;
				if (networkFuture != null) { networkFuture.cancel(false); }
				this.releasePermit(task);
			}
			else if (result.state == com.seibel.distanthorizons.core.generation.tasks.ERetrievalResultState.SUCCESS)
			{ this.finishedRequests.incrementAndGet(); }
			else if (result.state == com.seibel.distanthorizons.core.generation.tasks.ERetrievalResultState.NO_UPDATE)
			{ this.noUpdateRequests.incrementAndGet(); }
		});
		return task.future;
	}

	private static CompletableFuture<DataSourceRetrievalResult> failedRequest(Throwable failure)
	{
		CompletableFuture<DataSourceRetrievalResult> future = new CompletableFuture<>();
		future.completeExceptionally(failure);
		return future;
	}

	public synchronized boolean tick(DhBlockPos2D targetPos)
	{
		if (DhApiWorldProxy.INSTANCE.tryGetReadOnly())
		{
			return false;
		}
		
		if (this.closingFuture != null 
			|| !this.networkState.isReady())
		{
			return false;
		}
		
		// queue requests until the queue is full
		while (this.getInProgressTaskCount() < this.getWaitingTaskCount()
				&& this.getInProgressTaskCount() < this.getRequestRateLimit()
				&& this.canSendRequests()
				&& this.pendingTasksSemaphore.tryAcquire())
		{
			if (!this.rateLimiter.tryAcquire())
			{
				this.pendingTasksSemaphore.release();
				break;
			}
			
			this.sendNextRequest(targetPos);
		}
		
		return true;
	}
	private void sendNextRequest(DhBlockPos2D targetPos)
	{
		Map.Entry<Long, NetRequestTask> nearestMapEntry = this.waitingTasksBySectionPos
			.entrySet().stream()
			.filter(task -> task.getValue().networkDataSourceFuture == null)
			.min(Comparator.comparingInt(mapEntry -> DhSectionPos.getChebyshevSignedBlockDistance(mapEntry.getKey(), targetPos)))
			.orElse(null);
		
		if (nearestMapEntry == null)
		{
			this.pendingTasksSemaphore.release();
			return;
		}
		
		long requestPos = nearestMapEntry.getKey();
		NetRequestTask requestTask = nearestMapEntry.getValue();
		
		if (!this.sectionInAllowedGenerationRadius(requestPos, targetPos))
		{
			requestTask.future.cancel(false);
			this.pendingTasksSemaphore.release();
			return;
		}
		
		if (!this.onBeforeRequest(requestPos, requestTask.future))
		{
			this.pendingTasksSemaphore.release();
			return;
		}
		
		requestTask.holdsPermit.set(true);
		if (requestTask.future.isDone()) { this.releasePermit(requestTask); return; }
		Long offsetEntryTimestamp = FullDataSourceRequestMessage.toServerTimestamp(
			requestTask.updateTimestamp, this.networkState.getServerTimeOffset());
		try
		{
			final long diagnosticSent = MiraiDiagnostics.startTiming();
			CompletableFuture<FullDataSourceResponseMessage> networkFuture = this.networkState.getSession().sendRequest(
				new FullDataSourceRequestMessage(this.level.getLevelWrapper(), requestPos, offsetEntryTimestamp),
				FullDataSourceResponseMessage.class);
			MiraiDiagnostics.count(MiraiDiagnostics.Counter.NETWORK_SENT);
			requestTask.networkDataSourceFuture = networkFuture;
			if (requestTask.future.isDone()) { networkFuture.cancel(false); }
			networkFuture.whenComplete((response, failure) ->
			{
				MiraiDiagnostics.finishTiming(MiraiDiagnostics.Metric.NETWORK_REQUEST_WALL, diagnosticSent);
				requestTask.receivedResponse = response;
				if (requestTask.future.isDone() || this.closingFuture != null)
				{
					this.discardResponse(response);
					this.releasePermit(requestTask);
					return;
				}
				try
				{
					Executor executor = this.responseExecutor.get();
					if (executor == null) { throw new RejectedExecutionException("LOD response worker is unavailable"); }
					executor.execute(() -> this.handleNetResponse(requestTask, response, failure));
				}
				catch (RuntimeException rejected)
				{
					this.discardResponse(response);
					this.releasePermit(requestTask);
					requestTask.future.completeExceptionally(rejected);
				}
			});
		}
		catch (RuntimeException failure)
		{
			this.releasePermit(requestTask);
			requestTask.future.completeExceptionally(failure);
		}
	}

	private void releasePermit(NetRequestTask task)
	{
		if (task.holdsPermit.compareAndSet(true, false)) { this.pendingTasksSemaphore.release(); }
	}

	private void discardResponse(FullDataSourceResponseMessage response)
	{
		if (response != null && response.payload != null)
		{ this.networkState.fullDataPayloadReceiver.discardPayload(response.payload); }
	}

	private void handleNetResponse(NetRequestTask requestTask, FullDataSourceResponseMessage response, Throwable throwable)
	{
		boolean retry = false;
		try
		{
			if (requestTask.future.isDone() || this.closingFuture != null
				|| this.level.getClientLevelWrapper().getDhLevel() != this.level)
			{ this.discardResponse(response); requestTask.future.cancel(false); return; }
			if (throwable != null)
			{
				throw ExceptionUtil.ensureUnwrap(throwable);
			}
			
			MiraiDiagnostics.count(MiraiDiagnostics.Counter.NETWORK_RECEIVED);
			if (response.payload == null)
			{
				LodUtil.assertTrue(this.changedOnly, "Received empty data source response for not changes-only request");
				requestTask.future.complete(DataSourceRetrievalResult.CreateNoUpdate(requestTask.pos));
				return;
			}
			
			
			try(FullDataSourceV2DTO dataSourceDto = this.networkState.fullDataPayloadReceiver.decodeDataSource(response.payload))
			{
				// set application flags based on the received detail level,
				// this is needed so the data sources propagate correctly
				// A cold coarse snapshot must not overwrite finer local chunks that
				// arrived while it was in flight; render reads request their own detail.
				dataSourceDto.applyToChildren = !Objects.equals(requestTask.updateTimestamp, FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP)
					&& DhSectionPos.getDetailLevel(dataSourceDto.pos) > DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL;
				dataSourceDto.applyToParent = DhSectionPos.getDetailLevel(dataSourceDto.pos) < DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL + 12;
				
				
                if (dataSourceDto.pos != requestTask.pos)
                { MiraiDiagnostics.count(MiraiDiagnostics.Counter.NETWORK_WRONG_POSITION); throw new IllegalArgumentException("LOD response position does not match request"); }
				FullDataSourceV2 fullDataSource = dataSourceDto.createDataSource(this.level.getLevelWrapper(), null);
				boolean transferred = false;
				try
				{
					// Publication and close have one order. A late decode belongs to this level
					// only and must not update repositories after the queue closes.
					synchronized (this)
					{
						if (this.closingFuture != null || requestTask.future.isDone()
							|| this.level.getClientLevelWrapper().getDhLevel() != this.level)
						{ requestTask.future.cancel(false); return; }
						// Cold terrain has an atomic insert-only guard in the updater. Beacon
						// snapshots have no equivalent freshness token, so only the existing
						// normal sync/live paths may replace them.
						if (!Objects.equals(requestTask.updateTimestamp, FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP))
						{ this.level.updateBeaconBeamsForSectionPos(dataSourceDto.pos, response.payload.beaconBeams); }
						transferred = requestTask.future.complete(DataSourceRetrievalResult.CreateSuccess(dataSourceDto.pos, fullDataSource));
					}
				}
				finally { if (!transferred) { fullDataSource.close(); } }
			}
		}
		catch (SectionRequiresSplittingException ignored)
		{
			requestTask.future.complete(DataSourceRetrievalResult.CreateSplit());
		}
		catch (SessionClosedException | CancellationException ignored)
		{
			requestTask.future.cancel(false);
		}
		catch (RequestRejectedException e)
		{
            MiraiDiagnostics.count(MiraiDiagnostics.Counter.NETWORK_REJECTED);
			LOGGER.info("Request rejected by the server, message: [" + e.getMessage() + "].");
			requestTask.future.completeExceptionally(e);
		}
		catch (RateLimitedException e)
		{
            MiraiDiagnostics.count(MiraiDiagnostics.Counter.NETWORK_RATE_LIMITED);
			LOGGER.info("Rate limited by server, re-queueing task [" + DhSectionPos.toString(requestTask.pos) + "], message: [" + e.getMessage() + "].");
			
			// Skip all requests for 1 second
			this.rateLimiter.acquireAll();
			
			if (--requestTask.retryAttempts > 0) { retry = true; }
			else { requestTask.future.completeExceptionally(e); }
		}
		catch (RequestOutOfRangeException e)
		{
            MiraiDiagnostics.count(MiraiDiagnostics.Counter.NETWORK_OUT_OF_RANGE);
			LOGGER.debug("Out of range, cancelling task [" + DhSectionPos.toString(requestTask.pos) + "], message: [" + e.getMessage() + "].");
			
			requestTask.future.cancel(false);
		}
		catch (Error fatal)
		{
			requestTask.future.completeExceptionally(fatal);
			throw fatal;
		}
		catch (Throwable e)
		{
			requestTask.retryAttempts--;
            MiraiDiagnostics.error(MiraiDiagnostics.ErrorKind.NETWORK, e);
			LOGGER.error("Unexpected error: ["+e.getMessage()+"] while fetching full data source, attempts left: ["+requestTask.retryAttempts+"] / ["+MAX_RETRY_ATTEMPTS+"]", e);
			
			// Retry logic
			if (requestTask.retryAttempts > 0)
			{
				retry = true;
			}
			else
			{
				requestTask.future.completeExceptionally(e);
			}
		}
		finally
		{
			requestTask.receivedResponse = null;
			this.discardResponse(response);
			// Do not expose a retry until the previous attempt has released its permit.
			synchronized (this)
			{
				this.releasePermit(requestTask);
				if (retry && this.closingFuture == null && !requestTask.future.isDone())
				{ requestTask.networkDataSourceFuture = null; }
			}
		}
	}
	
	
	
	//=========================================//
	// IFullDataSourceRetrievalQueue overrides //
	//=========================================//
	
	public void removeRetrievalRequestIf(DhSectionPos.ICancelablePrimitiveLongConsumer removeIf)
	{
		// remove tasks furthest
		Iterator<Map.Entry<Long, NetRequestTask>> farestTaskIterator = this.waitingTasksBySectionPos
			.entrySet().stream()
			.sorted(Comparator.comparingInt((Map.Entry<Long, NetRequestTask> entry) ->
			{
				Long pos = entry.getKey();
				DhBlockPos2D targetPos = this.level.getTargetPosForGeneration();
				return DhSectionPos.getChebyshevSignedBlockDistance(pos, targetPos);
			}).reversed())
			.iterator();
		
		while (farestTaskIterator.hasNext())
		{
			Map.Entry<Long, NetRequestTask> mapEntry = farestTaskIterator.next();
			long pos = mapEntry.getKey();
			NetRequestTask entry = mapEntry.getValue();
			
			if (removeIf.accept(pos))
			{
				if (entry.networkDataSourceFuture != null)
				{
					entry.networkDataSourceFuture.cancel(false);
				}
				entry.future.cancel(false);
			}
		}
	}
	
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		messageList.add(this.getQueueName() + " [" + this.level.getClientLevelWrapper().getDhIdentifier() + "]");
		messageList.add("Requests: " + this.finishedRequests + " / " + (this.getWaitingTaskCount() + this.finishedRequests.get()) + " (failed: " + this.failedRequests + ", no update: " + this.noUpdateRequests + ", rate limit: " + this.getRequestRateLimit() + ")");
	}
	
	public int getWaitingTaskCount() { return this.waitingTasksBySectionPos.size(); }
	public int getInProgressTaskCount() { return Short.MAX_VALUE - this.pendingTasksSemaphore.availablePermits(); }
	public final int getRequestCapacity() { return this.requestCapacity; }
	public final boolean isClosed() { return this.closingFuture != null; }
	
	
	
	//==========//
	// shutdown //
	//==========//
	
	
	public synchronized CompletableFuture<Void> startClosingAsync(boolean alsoInterruptRunning)
	{
		if (this.closingFuture != null) { return this.closingFuture; }
		this.closingFuture = new CompletableFuture<>();
		// Cancellation disposes already assembled responses and releases each permit once.
		// Workers already decoding cannot publish after the closed flag above.
		for (NetRequestTask task : this.waitingTasksBySectionPos.values())
		{
			task.future.cancel(alsoInterruptRunning);
		}
		this.closingFuture.complete(null);
		return this.closingFuture;
	}

	@Override
	public void close()
	{
		this.startClosingAsync(true);
		if (DEBUG_RENDERER != null) { DEBUG_RENDERER.unregister(this, this.showDebugWireframeConfig); }
	}
	

	//===========//
	// debugging //
	//===========//
	
	@Override
	public void debugRender(AbstractDebugWireframeRenderer renderer)
	{
		if (MC_CLIENT.getWrappedClientLevel() != this.level.getClientLevelWrapper())
		{
			return;
		}
		
		DhBlockPos2D targetPos = this.level.getTargetPosForGeneration();
		for (Map.Entry<Long, NetRequestTask> mapEntry : this.waitingTasksBySectionPos.entrySet())
		{
			long pos = mapEntry.getKey();
			NetRequestTask task = mapEntry.getValue();
			
			Color color;
			if (task.networkDataSourceFuture != null)
			{
				color = Color.RED;
			}
			else
			{
				boolean taskInAllowedGenRadius = this.sectionInAllowedGenerationRadius(pos, targetPos);
				if (taskInAllowedGenRadius)
				{
					color = Color.GRAY;
				}
				else
				{
					color = Color.DARK_GRAY;
				}
			}
			
			renderer.renderBox(new AbstractDebugWireframeRenderer.Box(pos, -32f, 64f, 0.05f, color));
		}
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	protected static class NetRequestTask
	{
		public final long pos;
		
		/** encapsulates the entire request, including client side queuing and the actual server request */
		public final CompletableFuture<DataSourceRetrievalResult> future = new CompletableFuture<>();
		/** will be null if we want to retrieve the LOD regardless of when it was last updated */
		@Nullable
		public final Long updateTimestamp;
		
		
		/** Will be null until the request has been sent to the server */
		@CheckForNull
		public volatile CompletableFuture<FullDataSourceResponseMessage> networkDataSourceFuture;
		private final AtomicBoolean holdsPermit = new AtomicBoolean();
		private volatile FullDataSourceResponseMessage receivedResponse;
		
		/** when this reaches zero then the request will be canceled. */
		public int retryAttempts = MAX_RETRY_ATTEMPTS;
		
		
		
		//=============//
		// constructor //
		//=============//
		
		public NetRequestTask(long pos, @Nullable Long updateTimestamp)
		{
			this.pos = pos;
			this.updateTimestamp = updateTimestamp;
		}
		
	}
	
	
	
}
