package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;

import javax.annotation.CheckForNull;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

class DataSourceRequestGroup
{
	public final long pos;
	
	/**
	 * If this variable is true, we definitely know that generation is complete and there's no need for checking column gen steps
	 */
	private volatile boolean worldGenTaskComplete = false;
	
	void markWorldGenTaskComplete()
	{
		this.worldGenTaskComplete = true;
	}
	
	boolean isWorldGenTaskComplete()
	{
		return this.worldGenTaskComplete;
	}
	
	@CheckForNull
	public volatile FullDataSourceV2 fullDataSource = null;
	public final AtomicBoolean retrievalPending = new AtomicBoolean();
	
	public final ConcurrentMap<Long, RequestData> requestMessages = new ConcurrentHashMap<>();
	public final AtomicBoolean isClosed = new AtomicBoolean();
	
	
	DataSourceRequestGroup(long pos)
	{
		this.pos = pos;
	}
	
	public synchronized boolean tryClose()
	{
		return this.isClosed.compareAndSet(false, true);
	}
	
	public synchronized boolean tryAddRequest(RequestData requestData)
	{
		if (this.isClosed.get()) { return false; }
		this.requestMessages.put(requestData.futureId(), requestData);
		return true;
	}

	/** A late read may not publish its pooled datasource into an already closed group. */
	public synchronized boolean trySetFullDataSource(FullDataSourceV2 data)
	{
		if (this.isClosed.get() || this.fullDataSource != null) { return false; }
		this.fullDataSource = data;
		return true;
	}

	public synchronized FullDataSourceV2 takeFullDataSource()
	{
		FullDataSourceV2 data = this.fullDataSource;
		this.fullDataSource = null;
		return data;
	}
	
	
	public RequestData tryRemoveRequest(long requestId, IHangingRequestTransferConsumer hangingRequestTransferConsumer)
	{
		RequestData removed = this.requestMessages.remove(requestId);
		
		if (this.requestMessages.isEmpty() && this.tryClose())
		{
			hangingRequestTransferConsumer.accept(this.requestMessages.values());
		}
		
		return removed;
	}
	
	
	static class RequestData
	{
		public final ServerPlayerState serverPlayerState;
		public final ServerPlayerState.RateLimiterSet rateLimiterSet;
		
		public final FullDataSourceRequestMessage message;
		public RequestQuotaLease quota;
		public long futureId() { return this.message.futureId; }
		public long sectionPos() { return this.message.sectionPos; }
		
		RequestData(ServerPlayerState serverPlayerState, FullDataSourceRequestMessage message, ServerPlayerState.RateLimiterSet rateLimiterSet)
		{
			this.serverPlayerState = serverPlayerState;
			this.rateLimiterSet = rateLimiterSet;
			this.message = message;
		}
		
	}
	
	/**
	 * While closing this group, some requests may slip through and end up lost. <br>
	 * This is a workaround that allows the caller to transfer these requests to a new group.
	 */
	@FunctionalInterface
	interface IHangingRequestTransferConsumer extends Consumer<Collection<RequestData>> { }
	
}
