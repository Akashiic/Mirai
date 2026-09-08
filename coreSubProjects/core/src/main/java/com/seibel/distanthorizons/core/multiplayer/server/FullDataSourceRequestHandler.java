package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataSourceProvider;
import com.seibel.distanthorizons.core.level.AbstractDhServerLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.multiplayer.fullData.FullDataPayload;
import com.seibel.distanthorizons.core.network.exceptions.RequestRejectedException;
import com.seibel.distanthorizons.core.network.exceptions.SectionRequiresSplittingException;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceResponseMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedRateAndConcurrencyLimiter;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-level requests. Every acquired quota has one idempotent terminal owner. */
public class FullDataSourceRequestHandler implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder()
		.fileLevelConfig(Config.Common.Logging.logNetworkEventToFile).build();
	private final AbstractDhServerLevel serverLevel;
	private final ThreadPoolExecutor tickerThread;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final Set<QueuedResource<FullDataSourceV2>> queuedResources = ConcurrentHashMap.newKeySet();
	private final ConcurrentMap<Long, DataSourceRequestGroup> requestGroupsByPos = new ConcurrentHashMap<>();
	private final ConcurrentMap<Long, DataSourceRequestGroup.RequestData> pendingRequests = new ConcurrentHashMap<>();

	private GeneratedFullDataSourceProvider fullDataSourceProvider() { return this.serverLevel.serverside.fullDataFileHandler; }

	public FullDataSourceRequestHandler(AbstractDhServerLevel serverLevel)
	{
		this.serverLevel = serverLevel;
		this.tickerThread = ThreadUtil.makeSingleDaemonThreadPool("DataSource Request Ticker [" + serverLevel.getServerLevelWrapper().getDhIdentifier() + "]");
		this.tickerThread.execute(this::tickLoop);
	}

	private DataSourceRequestGroup.RequestData acquire(ServerPlayerState player, FullDataSourceRequestMessage message,
		ServerPlayerState.RateLimiterSet limiters, SupplierBasedRateAndConcurrencyLimiter<FullDataSourceRequestMessage> limiter)
	{
		if (!this.serverLevel.isPlayerInCurrentLevel(player.networkSession) || this.closed.get())
		{
			if (!player.networkSession.isClosed()) { message.sendResponse(new RequestRejectedException("Requested level is no longer active.")); }
			return null;
		}
		if (!limiter.tryAcquire(message)) { return null; }
		DataSourceRequestGroup.RequestData request = new DataSourceRequestGroup.RequestData(player, message, limiters);
		request.quota = new RequestQuotaLease(limiter::release, () -> this.pendingRequests.remove(message.futureId, request));
		if (this.pendingRequests.putIfAbsent(message.futureId, request) != null)
		{
			request.quota.finish(null);
			return null;
		}
		if (!this.isAllowed(request)) { this.reject(request, "Requested level is no longer active."); return null; }
		return request;
	}

	private boolean isAllowed(DataSourceRequestGroup.RequestData request)
	{
		return request.quota.isPending() && !this.closed.get()
			&& this.serverLevel.isPlayerInCurrentLevel(request.serverPlayerState.networkSession);
	}

	private void reject(DataSourceRequestGroup.RequestData request, String reason)
	{
		this.respondException(request, new RequestRejectedException(reason));
	}

	private void respondException(DataSourceRequestGroup.RequestData request, Exception failure)
	{
		try
		{
			request.quota.finish(() ->
			{
				if (!request.serverPlayerState.networkSession.isClosed()) { request.message.sendResponse(failure); }
			});
		}
		catch (RuntimeException notificationFailure) { LOGGER.error("Unable to notify LOD requester", notificationFailure); }
	}

	private void respond(DataSourceRequestGroup.RequestData request, FullDataPayload payload)
	{
		if (!this.isAllowed(request)) { this.reject(request, "Requested level is no longer active."); return; }
		request.quota.finish(() ->
		{
			// The dimension can change while an upload waits for bandwidth.
			if (this.serverLevel.isPlayerInCurrentLevel(request.serverPlayerState.networkSession) && !this.closed.get())
			{ request.message.sendResponse(new FullDataSourceResponseMessage(payload)); }
			else if (!request.serverPlayerState.networkSession.isClosed())
			{ request.message.sendResponse(new RequestRejectedException("Requested level is no longer active.")); }
		});
	}

	private void send(DataSourceRequestGroup.RequestData request, FullDataPayload payload)
	{
		if (!this.isAllowed(request)) { this.reject(request, "Requested level is no longer active."); return; }
		request.serverPlayerState.fullDataPayloadSender.sendInChunks(payload,
			() -> this.respond(request, payload), () -> this.isAllowed(request),
			() -> this.reject(request, "LOD transfer did not complete."));
	}

	public void queueLodSyncForRequestMessage(ServerPlayerState player, FullDataSourceRequestMessage message, ServerPlayerState.RateLimiterSet limiters)
	{
		if (!player.sessionConfig.getSynchronizeOnLoad())
		{ message.sendResponse(new RequestRejectedException("Operation is disabled in config.")); return; }
		DataSourceRequestGroup.RequestData request = this.acquire(player, message, limiters, limiters.syncOnLoginRateLimiter);
		if (request == null) { return; }
		AbstractExecutorService reader = ThreadPoolUtil.getFileHandlerExecutor();
		AbstractExecutorService compressor = ThreadPoolUtil.getNetworkCompressionExecutor();
		if (reader == null || compressor == null)
		{ this.reject(request, "LOD workers are unavailable."); return; }
		try
		{
			reader.execute(() -> this.readSync(request, compressor));
		}
		catch (RuntimeException failure) { this.reject(request, "LOD read could not be scheduled."); }
	}

	private void readSync(DataSourceRequestGroup.RequestData request, AbstractExecutorService compressor)
	{
		FullDataSourceV2 data = null;
		try
		{
			if (!this.isAllowed(request)) { this.reject(request, "Requested level is no longer active."); return; }
			long timestamp = request.message.clientTimestamp == null ? -1 : request.message.clientTimestamp;
			Long serverTimestamp = this.fullDataSourceProvider().getTimestampForPos(request.sectionPos());
			if (serverTimestamp == null || serverTimestamp <= timestamp) { this.respond(request, null); return; }
			data = this.fullDataSourceProvider().get(request.sectionPos());
			if (data == null) { this.respond(request, null); return; }
			final FullDataSourceV2 ownedData = data;
			data = null; // queueData assumes ownership even if submission fails
			this.queueData(compressor, ownedData, value -> this.compressAndSend(request, value),
				() -> this.reject(request, "LOD compression could not complete."));
		}
		catch (RuntimeException failure)
		{
			LOGGER.error("Failed to read or schedule LOD sync", failure);
			this.reject(request, "LOD data could not be read.");
		}
		finally { if (data != null) { data.close(); } }
	}

	private void compressAndSend(DataSourceRequestGroup.RequestData request, FullDataSourceV2 data)
	{
		FullDataPayload payload = null;
		try
		{
			if (!this.isAllowed(request)) { this.reject(request, "Requested level is no longer active."); return; }
			payload = new FullDataPayload(data, this.serverLevel.beaconBeamRepo.getAllBeamsForPos(request.sectionPos()));
			this.send(request, payload);
		}
		catch (RuntimeException failure)
		{
			LOGGER.error("Failed to compress or queue LOD sync", failure);
			this.reject(request, "LOD data could not be sent.");
		}
		finally { if (payload != null) { payload.dtoBuffer.release(); } }
	}

	public void queueWorldGenForRequestMessage(ServerPlayerState player, FullDataSourceRequestMessage message, ServerPlayerState.RateLimiterSet limiters)
	{
		if (!player.sessionConfig.isDistantGenerationEnabled())
		{ message.sendResponse(new RequestRejectedException("Operation is disabled in config.")); return; }
		DataSourceRequestGroup.RequestData request = this.acquire(player, message, limiters, limiters.generationRequestRateLimiter);
		if (request != null) { this.doQueueWorldGenForRequestMessage(request); }
	}

	private void doQueueWorldGenForRequestMessage(DataSourceRequestGroup.RequestData request)
	{
		try
		{
			while (this.isAllowed(request))
			{
				DataSourceRequestGroup group = this.requestGroupsByPos.computeIfAbsent(request.sectionPos(), DataSourceRequestGroup::new);
				if (!group.tryAddRequest(request))
				{ Thread.yield(); continue; }
				if (!this.isAllowed(request)) { this.cancelRequest(request.futureId()); return; }
				this.tryFulfillDataSourceRequestGroup(group);
				return;
			}
			this.reject(request, "Requested level is no longer active.");
		}
		catch (RuntimeException failure)
		{
			LOGGER.error("Unable to queue LOD generation", failure);
			this.reject(request, "LOD generation could not be scheduled.");
			this.removeFromGroup(request);
		}
	}

	public void cancelRequest(long requestId)
	{
		DataSourceRequestGroup.RequestData request = this.pendingRequests.get(requestId);
		if (request == null) { return; }
		request.quota.finish(null);
		this.removeFromGroup(request);
	}

	private void removeFromGroup(DataSourceRequestGroup.RequestData request)
	{
		DataSourceRequestGroup group = this.requestGroupsByPos.get(request.sectionPos());
		if (group == null) { return; }
		group.tryRemoveRequest(request.futureId(), requests ->
		{
			// Keep the closed group published until cancellation finishes. A replacement
			// request must not have its newly queued generation cancelled by this group.
			try { this.fullDataSourceProvider().removeRetrievalRequestIf(pos -> pos == group.pos); }
			catch (RuntimeException cancellationFailure) { LOGGER.error("Unable to remove cancelled LOD retrieval", cancellationFailure); }
			finally { this.requestGroupsByPos.remove(group.pos, group); }
			FullDataSourceV2 data = group.takeFullDataSource();
			try { if (data != null) { data.close(); } }
			finally
			{
				for (DataSourceRequestGroup.RequestData transfer : requests) { this.doQueueWorldGenForRequestMessage(transfer); }
			}
		});
	}

	private void failGroup(DataSourceRequestGroup group, Exception failure)
	{
		if (!group.tryClose()) { return; }
		this.requestGroupsByPos.remove(group.pos, group);
		try
		{
			FullDataSourceV2 data = group.takeFullDataSource();
			if (data != null) { data.close(); }
		}
		finally
		{
			for (DataSourceRequestGroup.RequestData request : group.requestMessages.values()) { this.respondException(request, failure); }
		}
	}

	private boolean hasAllowedRequest(DataSourceRequestGroup group)
	{
		for (DataSourceRequestGroup.RequestData request : group.requestMessages.values())
		{ if (this.isAllowed(request)) { return true; } }
		return false;
	}

	private void tryFulfillDataSourceRequestGroup(DataSourceRequestGroup group)
	{
		if (group.isClosed.get() || group.fullDataSource != null || !group.retrievalPending.compareAndSet(false, true)) { return; }
		try
		{
			if (!this.hasAllowedRequest(group))
			{ group.retrievalPending.set(false); this.failGroup(group, new RequestRejectedException("Requested level is no longer active.")); return; }
			this.fullDataSourceProvider().getAsync(group.pos).whenComplete((data, failure) ->
			{

				try
				{
					if (failure != null || data == null)
					{ this.failGroup(group, new RequestRejectedException("Generated LOD data could not be read.")); return; }
					if (group.isClosed.get() || this.closed.get()) { return; }
					if (this.fullDataSourceProvider().generationStepsAreFullyGenerated(data.columnGenerationSteps))
					{
						if (group.trySetFullDataSource(data)) { data = null; }
						return;
					}
					if (DhSectionPos.getDetailLevel(group.pos) > (Config.Common.WorldGenerator.distantGeneratorMode.get() == EDhApiDistantGeneratorMode.INTERNAL_SERVER
						? DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL : this.fullDataSourceProvider().lowestDataDetailLevel()))
					{ this.failGroup(group, new SectionRequiresSplittingException()); }
					else if (group.isWorldGenTaskComplete()) { /* Retry on the next bounded ticker iteration. */ }
					else if (this.hasAllowedRequest(group)) { this.fullDataSourceProvider().queuePositionForRetrieval(group.pos); }
					else { this.failGroup(group, new RequestRejectedException("Requested level is no longer active.")); }
				}
				catch (RuntimeException error)
				{
					LOGGER.error("Failed to fulfill generated LOD request", error);
					this.failGroup(group, new RequestRejectedException("Generated LOD data could not be processed."));
				}
				finally
				{
					try { if (data != null) { data.close(); } }
					catch (RuntimeException closeFailure)
					{ this.failGroup(group, new RequestRejectedException("Generated LOD cleanup failed.")); }
					finally { group.retrievalPending.set(false); }
				}
			});
		}
		catch (RuntimeException failure)
		{
			group.retrievalPending.set(false);
			this.failGroup(group, new RequestRejectedException("Generated LOD read could not be scheduled."));
		}
	}

	public void onWorldGenTaskComplete(long pos)
	{
		DataSourceRequestGroup group = this.requestGroupsByPos.get(pos);
		if (group != null) { group.markWorldGenTaskComplete(); this.tryFulfillDataSourceRequestGroup(group); }
	}

	private void tickLoop()
	{
		try
		{
			while (!Thread.interrupted())
			{
				Thread.sleep(20);
				try { this.tick(); }
				catch (RuntimeException failure) { LOGGER.error("LOD request tick failed", failure); }
			}
		}
		catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
	}

	private void tick()
	{
		for (DataSourceRequestGroup.RequestData request : this.pendingRequests.values())
		{
			if (!this.isAllowed(request))
			{ this.reject(request, "Requested level is no longer active."); this.removeFromGroup(request); }
		}
		for (Map.Entry<Long, DataSourceRequestGroup> entry : this.requestGroupsByPos.entrySet())
		{
			DataSourceRequestGroup group = entry.getValue();
			if (group.fullDataSource == null)
			{
				if (group.isWorldGenTaskComplete()) { this.tryFulfillDataSourceRequestGroup(group); }
				continue;
			}
			if (!group.tryClose()) { continue; }
			this.requestGroupsByPos.remove(entry.getKey(), group);
			FullDataSourceV2 data = group.takeFullDataSource();
			try
			{
				AbstractExecutorService executor = ThreadPoolUtil.getNetworkCompressionExecutor();
				if (executor == null) { throw new RejectedExecutionException("LOD compressor is unavailable"); }
				final FullDataSourceV2 ownedData = data;
				data = null; // queueData owns cleanup even if its failure response throws
				this.queueData(executor, ownedData, value -> this.sendGroup(group, value), () ->
				{
					for (DataSourceRequestGroup.RequestData request : group.requestMessages.values())
					{ this.reject(request, "LOD compression could not complete."); }
				});
			}
			catch (RuntimeException failure)
			{
				try { if (data != null) { data.close(); } }
				finally
				{
					for (DataSourceRequestGroup.RequestData request : group.requestMessages.values())
					{ this.reject(request, "LOD compression could not be scheduled."); }
				}
			}
		}
	}

	private void sendGroup(DataSourceRequestGroup group, FullDataSourceV2 data)
	{
		FullDataPayload payload = null;
		boolean queued = false;
		try
		{
			boolean active = false;
			for (DataSourceRequestGroup.RequestData request : group.requestMessages.values()) { active |= this.isAllowed(request); }
			if (!active) { return; }
			payload = new FullDataPayload(data, this.serverLevel.beaconBeamRepo.getAllBeamsForPos(group.pos));
			for (DataSourceRequestGroup.RequestData request : group.requestMessages.values()) { this.send(request, payload); }
			queued = true;
		}
		catch (RuntimeException failure) { queued = false; LOGGER.error("Failed to compress or send generated LOD", failure); }
		finally
		{
			if (payload != null) { payload.dtoBuffer.release(); }
			// Successful queue entries own the request until their terminal callback.
			if (!queued)
			{
				for (DataSourceRequestGroup.RequestData request : group.requestMessages.values())
				{ this.reject(request, "LOD data could not be sent."); }
			}
		}
	}

	private void queueData(AbstractExecutorService executor, FullDataSourceV2 data,
		Consumer<FullDataSourceV2> action, Runnable failureResponse)
	{
		// Keep accepted-but-not-started tasks reachable for level shutdown: executor.shutdownNow()
		// is permitted to discard Runnable objects without invoking them.
		final java.util.concurrent.atomic.AtomicReference<QueuedResource<FullDataSourceV2>> owner = new java.util.concurrent.atomic.AtomicReference<>();
		owner.set(new QueuedResource<>(data, FullDataSourceV2::close, () -> this.queuedResources.remove(owner.get())));
		this.queuedResources.add(owner.get());
		try
		{
			if (this.closed.get()) { owner.get().discard(); failureResponse.run(); return; }
			executor.execute(() ->
			{
				try { owner.get().run(action); }
				catch (RuntimeException failure)
				{
					LOGGER.error("LOD datasource processing or cleanup failed", failure);
					failureResponse.run();
				}
			});
		}
		catch (RuntimeException failure)
		{
			try { owner.get().discard(); }
			catch (RuntimeException closeFailure) { LOGGER.error("LOD datasource cleanup failed", closeFailure); }
			finally { failureResponse.run(); }
		}
	}

	@Override public void close()
	{
		if (!this.closed.compareAndSet(false, true)) { return; }
		this.tickerThread.shutdownNow();
		for (QueuedResource<FullDataSourceV2> resource : this.queuedResources)
		{
			try { resource.discard(); }
			catch (RuntimeException failure) { LOGGER.error("LOD datasource cleanup failed", failure); }
		}
		for (DataSourceRequestGroup.RequestData request : this.pendingRequests.values())
		{ this.reject(request, "Requested level was closed."); }
		for (DataSourceRequestGroup group : this.requestGroupsByPos.values())
		{
			try { this.failGroup(group, new RequestRejectedException("Requested level was closed.")); }
			catch (RuntimeException failure) { LOGGER.error("LOD group cleanup failed", failure); }
		}
	}
}
