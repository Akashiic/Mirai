package com.seibel.distanthorizons.core.multiplayer.fullData;

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
			@Override public void run() { FullDataPayloadReceiver.this.expireTransfers(); }
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

	/** Retains accepted data; the ingress dispatcher must release the message's original reference. */
	public synchronized void receiveChunk(FullDataSplitMessage message)
	{
		boolean adopted = false;
		ByteBuf retained = null;
		try
		{
			if (this.closed) { return; }
			this.expireTransfers();
			Transfer transfer = this.buffersById.get(message.bufferId);
			int length = message.buffer.readableBytes();
			if (length <= 0 || length > this.maxTransferBytes
				|| this.bufferedBytes + length > this.maxSessionBytes)
			{
				throw new IllegalArgumentException("LOD transfer exceeds reassembly budget");
			}
			if (message.isFirst)
			{
				if (transfer != null || this.buffersById.size() >= this.maxTransfers)
				{
					throw new IllegalArgumentException("Duplicate or excessive LOD transfer");
				}
				transfer = new Transfer(Unpooled.compositeBuffer(1024), this.clock.getAsLong());
				this.buffersById.put(message.bufferId, transfer);
			}
			else if (transfer == null)
			{
				// Expected when a transfer expired or its request was cancelled.
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
	{
		Transfer transfer = this.buffersById.remove(payload.dtoBufferId);
		if (transfer != null)
		{
			this.bufferedBytes -= transfer.buffer.readableBytes();
			transfer.buffer.release();
		}
	}

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
		private int fragments;
		private Transfer(CompositeByteBuf buffer, long createdNanos)
		{
			this.buffer = buffer;
			this.createdNanos = createdNanos;
		}
	}
}
