package com.seibel.distanthorizons.core.multiplayer.client;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.generation.queues.RemoteWorldRetrievalQueue;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.world.DhApiWorldProxy;
import javax.annotation.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;

/**
 * Read-only synchronization of cached server LODs. Missing client records use the
 * timestamp sentinel and therefore never opt into RemoteWorldRetrievalQueue generation.
 * The existing synchronizeOnLoad permission, radius, bandwidth and rate limits apply.
 */
public class SyncOnLoadRequestQueue extends AbstractFullDataNetworkRequestQueue
{
	//=============//
	// constructor //
	//=============//
	
	public SyncOnLoadRequestQueue(DhClientLevel level, ClientNetworkState networkState)
	{ this(level, networkState, ThreadPoolUtil::getNetworkCompressionExecutor, 4096); }

	SyncOnLoadRequestQueue(DhClientLevel level, ClientNetworkState networkState, Supplier<Executor> executor, int capacity)
	{ super(networkState, level, true, Config.Client.Advanced.Debugging.DebugWireframe.showNetworkSyncOnLoadQueue, executor, capacity); }
	
	
	
	//=========//
	// getters //
	//=========//
	
	@Override
	protected int getRequestRateLimit() { return this.networkState.sessionConfig.getSyncOnLoginRateLimit(); }
	@Override
	protected boolean sectionInAllowedGenerationRadius(long sectionPos, DhBlockPos2D targetPos)
	{
		return DhSectionPos.getChebyshevSignedBlockDistance(sectionPos, targetPos) <= this.networkState.sessionConfig.getMaxSyncOnLoadDistance() * 16;
	}
	@Override
	protected boolean onBeforeRequest(long sectionPos, CompletableFuture<DataSourceRetrievalResult> future) { return true; }
	
	@Override
	protected String getQueueName() { return "Sync On Login Queue"; }
	@Override
	protected boolean canSendRequests() { return this.canAcceptSyncRequests(); }

	/** Readiness is also checked at atomic admission, after a deferred timestamp read. */
	public synchronized boolean canAcceptSyncRequests()
	{
		return !this.isClosed() && this.networkState.isReady()
			&& !this.networkState.getSession().isClosed()
			&& this.networkState.sessionConfig.getSynchronizeOnLoad()
			&& this.getRequestRateLimit() > 0
			&& !DhApiWorldProxy.INSTANCE.tryGetReadOnly()
			&& this.level.getClientLevelWrapper().getDhLevel() == this.level;
	}

	public synchronized boolean hasSyncRequestCapacity()
	{ return this.canAcceptSyncRequests() && this.getWaitingTaskCount() < this.getRequestCapacity(); }

	@Nullable
	public synchronized CompletableFuture<DataSourceRetrievalResult> trySubmitRequest(long pos, Long timestamp)
	{
		return this.canAcceptSyncRequests() ? this.trySubmitRequestIfCapacity(pos, timestamp) : null;
	}
	
	
	
	//==================//
	// request handling //
	//==================//
	
	@Override
	public boolean tick(DhBlockPos2D targetPos)
	{
		if (!this.canAcceptSyncRequests())
		{
			return false;
		}
		
		return super.tick(targetPos);
	}
	
	
	
}
