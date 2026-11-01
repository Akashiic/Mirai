package com.seibel.distanthorizons.core.multiplayer.fullData;

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics;
import com.seibel.distanthorizons.core.network.NetworkDecodeLimits;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSplitMessage;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Session-owned bounded reassembly. Continuations never extend a transfer's deadline. */
public class FullDataPayloadReceiver implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final Timer CLEANUP_TIMER = new Timer("DH-LOD-reassembly-cleanup", true);
	private final Map<Integer, Transfer> buffersById = new HashMap<>();
	private final int maxTransferBytes;
	private final int maxSessionBytes;
	private final int maxTransfers;
	private final int maxFragments;
	private final long lifetimeNanos;
	private final LongSupplier clock;
	private final TimerTask cleanupTask;
	private long bufferedBytes;
	/** admission order; ties in createdNanos must not make eviction depend on HashMap iteration order */
	private long nextSequence;
	private boolean closed;

	public FullDataPayloadReceiver()
	{
		this(NetworkDecodeLimits.MAX_DTO_BYTES, 64 * 1024 * 1024, 128,
			TimeUnit.MINUTES.toNanos(5), System::nanoTime);
	}

	FullDataPayloadReceiver(int maxTransferBytes, int maxSessionBytes, int maxTransfers,
		long lifetimeNanos, LongSupplier clock)
	{
		this(maxTransferBytes, maxSessionBytes, maxTransfers, lifetimeNanos, clock, 65536);
	}

	FullDataPayloadReceiver(int maxTransferBytes, int maxSessionBytes, int maxTransfers,
		long lifetimeNanos, LongSupplier clock, int maxFragments)
	{
		this.maxTransferBytes = maxTransferBytes;
		this.maxSessionBytes = maxSessionBytes;
		this.maxTransfers = maxTransfers;
		this.maxFragments = maxFragments;
		this.lifetimeNanos = lifetimeNanos;
		this.clock = clock;
		this.cleanupTask = new TimerTask()
		{
			@Override public void run()
			{
				// an exception escaping a TimerTask kills the shared timer for every later session
				try { FullDataPayloadReceiver.this.expireTransfers(); }
				catch (RuntimeException e) { LOGGER.warn("LOD transfer cleanup failed: " + e.getMessage()); }
			}
		};
		CLEANUP_TIMER.schedule(this.cleanupTask, 1000L, 1000L);
	}

	@Override
	public synchronized void close()
	{
		if (this.closed) { return; }
		this.closed = true;
		this.cleanupTask.cancel();
		CLEANUP_TIMER.purge();
		for (Transfer transfer : this.buffersById.values()) { transfer.buffer.release(); }
		this.buffersById.clear();
		this.bufferedBytes = 0;
	}

	/**
	 * Retains accepted data; the ingress dispatcher must release the message's original reference. <br>
	 * Session-wide budgets (transfer count, buffered bytes) are enforced by evicting the oldest other transfers:
	 * an orphaned transfer (cancelled request, suppressed update) must cost at most its own data, never the session.
	 * Only a fragment that is malformed or alone exceeds a per-transfer budget is rejected (which closes the session).
	 */
	public synchronized void receiveChunk(FullDataSplitMessage message)
	{
		boolean adopted = false;
		ByteBuf retained = null;
		try
		{
			if (this.closed) { return; }
			this.expireTransfers();
			int length = message.buffer.readableBytes();
			if (length <= 0 || length > this.maxTransferBytes || length > this.maxSessionBytes)
			{
				throw new IllegalArgumentException("LOD transfer fragment exceeds reassembly budget");
			}
			
			Transfer transfer = this.buffersById.get(message.bufferId);
			if (message.isFirst)
			{
				if (transfer != null)
				{
					// a restarted transfer with the same id replaces the stale one (same sender, same data source)
					this.release(message.bufferId);
					MiraiDiagnostics.count(MiraiDiagnostics.Counter.NETWORK_TRANSFER_REPLACED);
				}
				while (this.buffersById.size() >= this.maxTransfers && this.evictOldestExcept(message.bufferId)) { }
				transfer = new Transfer(Unpooled.compositeBuffer(1024), this.clock.getAsLong(), this.nextSequence++);
				this.buffersById.put(message.bufferId, transfer);
			}
			else if (transfer == null)
			{
				// Expected when a transfer expired, was evicted or its request was cancelled.
				return;
			}
			if ((long) transfer.buffer.readableBytes() + length > this.maxTransferBytes)
			{
				throw new IllegalArgumentException("LOD transfer exceeds per-transfer budget");
			}
			if (transfer.fragments >= this.maxFragments)
			{
				throw new IllegalArgumentException("LOD transfer exceeds fragment budget");
			}
			while (this.bufferedBytes + length > this.maxSessionBytes && this.evictOldestExcept(message.bufferId)) { }
			if (this.bufferedBytes + length > this.maxSessionBytes)
			{
				throw new IllegalArgumentException("LOD transfer exceeds session budget");
			}
			retained = message.buffer.slice().retain();
			transfer.buffer.addComponent(retained);
			adopted = true;
			transfer.buffer.writerIndex(transfer.buffer.writerIndex() + length);
			transfer.fragments++;
			this.bufferedBytes += length;
		}
		finally
		{
			if (!adopted && retained != null) { retained.release(); }
		}
	}
	
	/** @return false when nothing but the protected transfer is left */
	private boolean evictOldestExcept(int protectedBufferId)
	{
		Integer oldestId = null;
		long oldestSequence = Long.MAX_VALUE;
		for (Map.Entry<Integer, Transfer> entry : this.buffersById.entrySet())
		{
			if (entry.getKey() != protectedBufferId && entry.getValue().sequence < oldestSequence)
			{
				oldestId = entry.getKey();
				oldestSequence = entry.getValue().sequence;
			}
		}
		if (oldestId == null)
		{
			return false;
		}
		this.release(oldestId);
		MiraiDiagnostics.count(MiraiDiagnostics.Counter.NETWORK_TRANSFER_EVICTED);
		return true;
	}
	
	private void release(int bufferId)
	{
		Transfer transfer = this.buffersById.remove(bufferId);
		if (transfer != null)
		{
			this.bufferedBytes -= transfer.buffer.readableBytes();
			transfer.buffer.release();
		}
	}
	
	/** for tests and diagnostics */
	synchronized int getBufferedTransferCount() { return this.buffersById.size(); }
	
	public synchronized FullDataSourceV2DTO decodeDataSource(FullDataPayload payload)
	{
		this.expireTransfers();
		Transfer transfer = this.buffersById.remove(payload.dtoBufferId);
		if (transfer == null) { throw new IllegalArgumentException("Missing or expired LOD transfer"); }
		this.bufferedBytes -= transfer.buffer.readableBytes();
		FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateEmptyDataSourceForDecoding();
		try
		{
			dto.decode(transfer.buffer);
			if (transfer.buffer.isReadable()) { throw new IllegalArgumentException("Trailing bytes in LOD transfer"); }
			return dto;
		}
		catch (RuntimeException | Error e)
		{
			dto.close();
			throw e;
		}
		finally { transfer.buffer.release(); }
	}

	/** A closed level must discard its assembled response without decoding or adopting its data. */
	public synchronized void discardPayload(FullDataPayload payload)
	{ this.release(payload.dtoBufferId); }

	private synchronized void expireTransfers()
	{
		long now = this.clock.getAsLong();
		Iterator<Transfer> iterator = this.buffersById.values().iterator();
		while (iterator.hasNext())
		{
			Transfer transfer = iterator.next();
			if (now - transfer.createdNanos >= this.lifetimeNanos)
			{
				iterator.remove();
				this.bufferedBytes -= transfer.buffer.readableBytes();
				transfer.buffer.release();
			}
		}
	}

	private static final class Transfer
	{
		private final CompositeByteBuf buffer;
		private final long createdNanos;
		private final long sequence;
		private int fragments;
		private Transfer(CompositeByteBuf buffer, long createdNanos, long sequence)
		{
			this.buffer = buffer;
			this.createdNanos = createdNanos;
			this.sequence = sequence;
		}
	}
}
