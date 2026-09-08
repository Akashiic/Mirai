package com.seibel.distanthorizons.core.multiplayer.fullData;

import com.seibel.distanthorizons.core.network.NetworkDecodeLimits;
import com.seibel.distanthorizons.core.network.messages.base.CloseReasonMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSplitMessage;
import com.seibel.distanthorizons.core.network.session.NetworkSession;
import com.seibel.distanthorizons.core.network.session.SessionClosedException;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.TimerUtil;
import io.netty.buffer.ByteBuf;

import java.util.ArrayDeque;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.IntSupplier;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

/** One player's bounded outgoing transfers; queue entries own one retained DTO view each. */
public class FullDataPayloadSender implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final int TICK_RATE = 20;
	public static final int FULL_DATA_SPLIT_SIZE_IN_BYTES = 30 * 1024;
	static final int MAX_QUEUED_BYTES = 64 * 1024 * 1024;
	static final int MAX_QUEUED_TRANSFERS = 128;
	/** 20MiB/s theoretical ceiling per sender; this is a work budget, not a throughput claim. */
	static final int MAX_BYTES_PER_TICK = 1024 * 1024;

	private static final Timer UPLOAD_TIMER = TimerUtil.CreateTimer("FullDataPayloadSender");
	private final TimerTask tickTimerTask = TimerUtil.createTimerTask(this::tick);
	private final NetworkSession session;
	private final IntSupplier maxKBpsSupplier;
	private final ArrayDeque<PendingTransfer> transferQueue = new ArrayDeque<>();
	private final SharedBandwidthLimit sharedBandwidthLimit;
	private long queuedBytes;
	private boolean closed;

	public FullDataPayloadSender(NetworkSession session, IntSupplier maxKBpsSupplier, SharedBandwidthLimit sharedBandwidthLimit)
	{
		this(session, maxKBpsSupplier, sharedBandwidthLimit, true);
	}

	/** Deterministic tests use manual ticks; production always schedules the timer. */
	FullDataPayloadSender(NetworkSession session, IntSupplier maxKBpsSupplier,
		SharedBandwidthLimit sharedBandwidthLimit, boolean scheduleTimer)
	{
		this.session = session;
		this.maxKBpsSupplier = maxKBpsSupplier;
		this.sharedBandwidthLimit = sharedBandwidthLimit;
		if (scheduleTimer) { UPLOAD_TIMER.scheduleAtFixedRate(this.tickTimerTask, 0, 1000 / TICK_RATE); }
	}

	@Override
	public synchronized void close()
	{
		if (this.closed) { return; }
		this.closed = true;
		this.tickTimerTask.cancel();
		UPLOAD_TIMER.purge();
		this.sharedBandwidthLimit.setSenderActive(this, false);
		Throwable failure = null;
		PendingTransfer transfer;
		while ((transfer = this.transferQueue.pollFirst()) != null)
		{
			try { transfer.finish(false); }
			catch (RuntimeException | Error error)
			{
				if (failure == null) { failure = error; }
				else if (failure != error) { failure.addSuppressed(error); }
			}
		}
		this.queuedBytes = 0;
		if (failure instanceof Error) { throw (Error) failure; }
		if (failure != null) { throw (RuntimeException) failure; }
	}

	public synchronized void sendInChunks(FullDataPayload payload, Runnable sendFinalMessage)
	{
		this.sendInChunks(payload, sendFinalMessage, () -> true, () -> { });
	}

	/** onFinished runs exactly once on success, cancellation, rejection or close. */
	public synchronized void sendInChunks(FullDataPayload payload, Runnable sendFinalMessage,
		BooleanSupplier stillValid, Runnable onFinished)
	{
		boolean queued = false;
		try
		{
			if (this.closed || this.session.isClosed()) { this.close(); return; }
			if (!stillValid.getAsBoolean()) { return; }
			int length = payload.dtoBuffer.writerIndex();
			if (length <= 0 || length > NetworkDecodeLimits.MAX_DTO_BYTES
				|| this.transferQueue.size() >= MAX_QUEUED_TRANSFERS
				|| this.queuedBytes + length > MAX_QUEUED_BYTES)
			{
				this.rejectSession("LOD outgoing queue budget exceeded");
				return;
			}
			this.transferQueue.addLast(new PendingTransfer(payload, sendFinalMessage, stillValid, onFinished));
			queued = true;
			this.queuedBytes += length;
			this.sharedBandwidthLimit.setSenderActive(this, true);
		}
		finally { if (!queued) { onFinished.run(); } }
	}

	/** Session rejection completes peer-side pending requests without aborting a broadcast to other players. */
	private void rejectSession(String reason)
	{
		try { this.session.sendMessage(new CloseReasonMessage(reason)); }
		catch (RuntimeException notificationError)
		{
			LOGGER.error("Unable to notify peer of LOD session closure", notificationError);
		}
		finally
		{
			try { this.close(); }
			catch (RuntimeException cleanupError)
			{
				LOGGER.error("LOD sender cleanup failed", cleanupError);
			}
			finally
			{
				try { this.session.close(new SessionClosedException(reason)); }
				catch (RuntimeException sessionError)
				{
					LOGGER.error("LOD session cleanup failed", sessionError);
				}
			}
		}
	}

	synchronized void tick()
	{
		if (this.closed) { return; }
		if (this.session.isClosed()) { this.close(); return; }
		if (this.transferQueue.isEmpty())
		{
			this.sharedBandwidthLimit.setSenderActive(this, false);
			return;
		}
		try
		{
			int bandwidthShare = this.sharedBandwidthLimit.getBandwidthShare();
			int configuredRate = this.maxKBpsSupplier.getAsInt();
			int effectiveRate = configuredRate <= 0 ? bandwidthShare : Math.min(configuredRate, bandwidthShare);
			int bytesToSend = effectiveRate > 0
				? (int) Math.min(MAX_BYTES_PER_TICK, ((long) effectiveRate * 1000L) / TICK_RATE + 1L)
				: 0;

			while (!this.transferQueue.isEmpty())
			{
				PendingTransfer transfer = this.transferQueue.peekFirst();
				if (!transfer.stillValid.getAsBoolean())
				{
					this.transferQueue.removeFirst();
					this.queuedBytes -= transfer.originalBytes;
					transfer.finish(false);
					continue;
				}
				if (bytesToSend <= 0) { break; }
				int chunkSize = Math.min(Math.min(bytesToSend, FULL_DATA_SPLIT_SIZE_IN_BYTES), transfer.buffer.readableBytes());
				boolean first = transfer.buffer.readerIndex() == 0;
				FullDataSplitMessage chunk = FullDataSplitMessage.copyForSending(
					transfer.bufferId, transfer.buffer.readSlice(chunkSize), first);
				this.session.sendMessage(chunk);
				if (this.session.isClosed()) { this.close(); return; }
				bytesToSend -= chunkSize;
				if (!transfer.buffer.isReadable())
				{
					this.transferQueue.removeFirst();
					this.queuedBytes -= transfer.originalBytes;
					transfer.finish(true);
					if (this.closed || this.session.isClosed()) { this.close(); return; }
				}
			}
			if (this.transferQueue.isEmpty()) { this.sharedBandwidthLimit.setSenderActive(this, false); }
		}
		catch (RuntimeException error)
		{
			// A failed encoder/callback must not kill the shared upload timer for every player.
			LOGGER.error("Failed to send LOD transfer; closing this DH session", error);
			this.rejectSession("LOD transfer failed");
		}
	}

	private static final class PendingTransfer
	{
		private final int bufferId;
		private final ByteBuf buffer;
		private final int originalBytes;
		private final Runnable sendFinalMessage;
		private final BooleanSupplier stillValid;
		private final Runnable onFinished;
		private final AtomicBoolean finished = new AtomicBoolean();
		private PendingTransfer(FullDataPayload payload, Runnable sendFinalMessage, BooleanSupplier stillValid, Runnable onFinished)
		{
			this.bufferId = payload.dtoBufferId;
			this.buffer = payload.dtoBuffer.duplicate().readerIndex(0).retain();
			this.originalBytes = this.buffer.readableBytes();
			this.sendFinalMessage = sendFinalMessage;
			this.stillValid = stillValid;
			this.onFinished = onFinished;
		}

		private void finish(boolean success)
		{
			if (!this.finished.compareAndSet(false, true)) { return; }
			try
			{
				this.buffer.release();
				if (success && this.stillValid.getAsBoolean()) { this.sendFinalMessage.run(); }
			}
			finally { this.onFinished.run(); }
		}
	}
}
