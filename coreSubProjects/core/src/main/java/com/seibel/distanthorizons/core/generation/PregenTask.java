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

package com.seibel.distanthorizons.core.generation;

import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * State machine of one server-side pregen run, separated from Minecraft so it can be tested headless. <br>
 * Sections are visited on a spiral around the origin. {@link #pump()} submits work and must be called
 * periodically (and after each completion, via the wake-up callback); it is also what expires stuck work,
 * so progress never depends on a cache being touched by someone else.
 * <p>
 * Invariants: a section occupies one in-flight slot until it reports back or times out;
 * the run only completes after the spiral is exhausted <i>and</i> nothing is in flight or waiting for retry,
 * so the world-generation queue is not torn down under running tasks.
 */
public class PregenTask extends CompletableFuture<Void>
{
	/** What the pregen asks of the level; implemented by {@link PregenManager} over the real data source provider. */
	public interface ITarget
	{
		/** true when the section should not be visited at all (e.g. its chunks are not saved and the run must not generate terrain) */
		boolean shouldSkip(long sectionPos);
		/** completes with true when the section already has fully generated LOD data */
		CompletableFuture<Boolean> isFullyGenerated(long sectionPos);
		/** @return null when the world-generation queue is not available (yet) */
		@Nullable
		CompletableFuture<?> queueGeneration(long sectionPos);
		/** drops a queued generation of a section that timed out, so the queue depth stays bounded by the in-flight limit */
		default void cancelGeneration(long sectionPos) { }
	}

	public static final class Settings
	{
		public IntSupplier maxInFlight = () -> 1;
		/** mean server tick time in milliseconds; 0 when unknown */
		public DoubleSupplier serverTickMs = () -> 0;
		/** submission pauses while {@link #serverTickMs} is above this; non-positive disables throttling */
		public double maxServerTickMs = 0;
		public LongSupplier clockMs = System::currentTimeMillis;
		public long taskTimeoutMs = 120_000;
		public long queueUnavailableTimeoutMs = 30_000;
		/** called with the lowest spiral index that is not yet known to be finished */
		public IntConsumer checkpoint = index -> { };
		/** asks the owner to call {@link #pump()} soon; may be a no-op when the owner pumps on a timer */
		public Runnable wakeUp = () -> { };
	}

	private enum EOutcome { ALREADY_GENERATED, GENERATED, FAILED, TIMED_OUT }

	private static final class InFlight
	{
		final int index;
		final long startMs;
		InFlight(int index, long startMs) { this.index = index; this.startMs = startMs; }
	}


	private final ITarget target;
	private final Settings settings;
	private final long originSectionPos;
	private final int sectionCount;

	// guarded by "this"
	private int nextIndex;
	private final Map<Long, InFlight> inFlightByPos = new HashMap<>();
	private final ArrayDeque<long[]> retryQueue = new ArrayDeque<>(); // {pos, index}
	private long queueUnavailableSinceMs = -1;
	private boolean throttled = false;
	private int alreadyGenerated, generated, skipped, failed, timedOut;



	public PregenTask(ITarget target, Settings settings, long originSectionPos, int sectionCount, int startIndex)
	{
		this.target = target;
		this.settings = settings;
		this.originSectionPos = originSectionPos;
		this.sectionCount = sectionCount;
		this.nextIndex = Math.max(0, Math.min(startIndex, sectionCount));
	}

	/** Largest accepted radius; keeps the spiral's ring arithmetic and the section count far from int overflow. */
	public static final int MAX_CHUNK_RADIUS = 16_384;
	
	/**
	 * Number of minimum-detail sections in the full spiral rings that cover the given chunk radius
	 * around the origin section: side = 2 * ceil(radius / sectionWidth) + 1. <br>
	 * (The upstream formula used an even side, which left the outermost ring partly unvisited.)
	 */
	public static int sectionCountForChunkRadius(int chunkRadius)
	{
		int sectionChunkWidth = 1 << (DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL - 4);
		long rings = (Math.max(0, Math.min(chunkRadius, MAX_CHUNK_RADIUS)) + sectionChunkWidth - 1) / sectionChunkWidth;
		long side = 2 * rings + 1;
		return (int) (side * side);
	}



	//=========//
	// pumping //
	//=========//

	public void pump()
	{
		synchronized (this)
		{
			if (this.isDone())
			{
				return;
			}

			long now = this.settings.clockMs.getAsLong();
			this.expireStuckSections(now);

			if (this.queueUnavailableSinceMs >= 0 && now - this.queueUnavailableSinceMs >= this.settings.queueUnavailableTimeoutMs)
			{
				this.completeExceptionally(new IllegalStateException(
					"The world generation queue did not become available for this level. Nothing was generated for the remaining sections."));
				return;
			}

			this.throttled = this.settings.maxServerTickMs > 0 && this.settings.serverTickMs.getAsDouble() > this.settings.maxServerTickMs;
			if (!this.throttled)
			{
				this.submitSections(now);
			}

			if (this.nextIndex >= this.sectionCount && this.inFlightByPos.isEmpty() && this.retryQueue.isEmpty())
			{
				this.settings.checkpoint.accept(this.sectionCount);
				this.complete(null);
			}
		}
	}

	private void submitSections(long now)
	{
		int maxInFlight = Math.max(1, this.settings.maxInFlight.getAsInt());
		while (!this.isDone() && this.inFlightByPos.size() < maxInFlight)
		{
			long pos;
			int index;
			long[] retry = this.retryQueue.pollFirst();
			if (retry != null)
			{
				pos = retry[0];
				index = (int) retry[1];
			}
			else if (this.nextIndex < this.sectionCount)
			{
				index = this.nextIndex++;
				pos = this.sectionPosOnSpiral(index);
				if (this.target.shouldSkip(pos))
				{
					this.skipped++;
					continue;
				}
			}
			else
			{
				return;
			}

			this.inFlightByPos.put(pos, new InFlight(index, now));
			this.startSection(pos);
			if (this.queueUnavailableSinceMs >= 0)
			{
				// the queue is missing, don't spin through the whole retry queue in one pump
				return;
			}
		}
	}

	/** Called with the lock held; callbacks may run synchronously on this thread or later on any thread. */
	private void startSection(long pos)
	{
		CompletableFuture<Boolean> generatedFuture;
		try
		{
			generatedFuture = this.target.isFullyGenerated(pos);
		}
		catch (RuntimeException e)
		{
			this.finish(pos, EOutcome.FAILED);
			return;
		}

		generatedFuture.whenComplete((isGenerated, throwable) ->
		{
			if (throwable != null || isGenerated == null)
			{
				this.finish(pos, EOutcome.FAILED);
			}
			else if (isGenerated)
			{
				this.finish(pos, EOutcome.ALREADY_GENERATED);
			}
			else
			{
				this.queueSection(pos);
			}
		});
	}

	private void queueSection(long pos)
	{
		CompletableFuture<?> generationFuture;
		try
		{
			generationFuture = this.target.queueGeneration(pos);
		}
		catch (RuntimeException e)
		{
			this.finish(pos, EOutcome.FAILED);
			return;
		}

		if (generationFuture == null)
		{
			this.retryLater(pos);
			return;
		}

		synchronized (this)
		{
			this.queueUnavailableSinceMs = -1;
		}
		generationFuture.whenComplete((result, throwable) -> this.finish(pos, throwable == null ? EOutcome.GENERATED : EOutcome.FAILED));
	}

	private void retryLater(long pos)
	{
		synchronized (this)
		{
			InFlight inFlight = this.inFlightByPos.remove(pos);
			if (inFlight == null || this.isDone())
			{
				return;
			}

			if (this.queueUnavailableSinceMs < 0)
			{
				this.queueUnavailableSinceMs = this.settings.clockMs.getAsLong();
			}
			this.retryQueue.addLast(new long[]{ pos, inFlight.index });
		}
	}

	private void finish(long pos, EOutcome outcome)
	{
		synchronized (this)
		{
			// a late completion after a timeout must not be counted twice
			if (this.inFlightByPos.remove(pos) == null)
			{
				return;
			}
			this.record(outcome);
			this.settings.checkpoint.accept(this.lowestUnfinishedIndex());
		}
		this.settings.wakeUp.run();
	}

	private void expireStuckSections(long now)
	{
		Iterator<Map.Entry<Long, InFlight>> iterator = this.inFlightByPos.entrySet().iterator();
		while (iterator.hasNext())
		{
			Map.Entry<Long, InFlight> entry = iterator.next();
			if (now - entry.getValue().startMs >= this.settings.taskTimeoutMs)
			{
				PregenManager.LOGGER.warn("Pregen of section " + DhSectionPos.toString(entry.getKey()) + " timed out; skipping it.");
				iterator.remove();
				this.record(EOutcome.TIMED_OUT);
				try { this.target.cancelGeneration(entry.getKey()); }
				catch (RuntimeException e) { PregenManager.LOGGER.warn("Unable to cancel the timed-out section: " + e.getMessage()); }
			}
		}
	}

	private void record(EOutcome outcome)
	{
		switch (outcome)
		{
			case ALREADY_GENERATED: this.alreadyGenerated++; break;
			case GENERATED: this.generated++; break;
			case FAILED: this.failed++; break;
			case TIMED_OUT: this.timedOut++; break;
		}
	}

	private int lowestUnfinishedIndex()
	{
		int lowest = this.nextIndex;
		for (InFlight inFlight : this.inFlightByPos.values())
		{
			lowest = Math.min(lowest, inFlight.index);
		}
		for (long[] retry : this.retryQueue)
		{
			lowest = Math.min(lowest, (int) retry[1]);
		}
		return lowest;
	}



	//=========//
	// getters //
	//=========//

	public synchronized int getVisitedCount() { return this.nextIndex; }
	public int getSectionCount() { return this.sectionCount; }
	public synchronized int getAlreadyGeneratedCount() { return this.alreadyGenerated; }
	public synchronized int getGeneratedCount() { return this.generated; }
	public synchronized int getSkippedCount() { return this.skipped; }
	public synchronized int getFailedCount() { return this.failed + this.timedOut; }
	public synchronized int getInFlightCount() { return this.inFlightByPos.size(); }
	public synchronized boolean isThrottled() { return this.throttled; }
	public synchronized boolean isWaitingForQueue() { return this.queueUnavailableSinceMs >= 0; }

	public synchronized String getCountersString()
	{
		return "sections " + this.nextIndex + "/" + this.sectionCount
			+ " (generated " + this.generated + ", already present " + this.alreadyGenerated
			+ ", skipped " + this.skipped + ", failed " + (this.failed + this.timedOut)
			+ ", in flight " + this.inFlightByPos.size() + ")"
			+ (this.throttled ? " [paused: server tick above " + this.settings.maxServerTickMs + " ms]" : "")
			+ (this.queueUnavailableSinceMs >= 0 ? " [waiting for the world generation queue]" : "");
	}



	//========//
	// spiral //
	//========//

	public long sectionPosOnSpiral(int index)
	{
		if (index == 0)
		{
			return this.originSectionPos;
		}
		index--;

		int ringNumber = (int) Math.round(Math.sqrt(Math.floor((double) index / 4) + 1));
		index -= (int) ((long) ringNumber * 8 * (ringNumber - 1) / 2);

		// 0 <= pos <= (ringNumber * 8) - 1
		// ringNumber * 8 - full ring
		// ringNumber * 4 - half-ring
		// ringNumber * 2 - quarter-ring, i.e. one side of it
		// ringNumber - half of quarter-ring

		int x = -ringNumber + 1 + Math.min(index % (ringNumber * 4), ringNumber * 2 - 1);
		int z = ringNumber - Math.max(0, index % (ringNumber * 4) - ringNumber * 2 + 1);

		if (index >= ringNumber * 4)
		{
			x = -x;
			z = -z;
		}

		x += DhSectionPos.getX(this.originSectionPos);
		z += DhSectionPos.getZ(this.originSectionPos);

		return DhSectionPos.encode(DhSectionPos.getDetailLevel(this.originSectionPos), x, z);
	}

}
