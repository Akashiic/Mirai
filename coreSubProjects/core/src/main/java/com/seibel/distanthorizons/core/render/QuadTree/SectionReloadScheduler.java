package com.seibel.distanthorizons.core.render.QuadTree;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

import java.util.Queue;
import java.util.function.LongFunction;

/**
 * Coalesces render-section reload requests. Used only by the quad tree's tick thread. <br><br>
 *
 * Every LOD data change queues its section plus the 4 cardinal neighbours (their border geometry depends on it),
 * so a burst of chunk updates re-built and re-uploaded the same sections many times (the 2026-10-30 log: ~73
 * buffer uploads/s for ~15 LOD updates/s, ~376 KB each). Here: <br>
 * - duplicates collapse into one pending entry, kept in FIFO order; <br>
 * - a section re-builds at most once per {@code minIntervalMs}; a request inside that window waits and is then
 *   served once, with the data current at that moment (an isolated request is served immediately); <br>
 * - a request that the section can't start yet (a build is running) stays pending instead of being dropped. <br>
 * Each re-build reads the current data, so the final geometry is the same as re-building on every request;
 * the only cost is a delay of at most {@code minIntervalMs} plus one tick during a burst.
 */
public final class SectionReloadScheduler
{
	public enum EResult
	{
		/** a re-build started; the section's interval begins now */
		STARTED,
		/** the section can't start now (e.g. a build is running); keep the request */
		RETRY,
		/** nothing to do (absent, or not loaded yet, so the normal load path builds it from current data) */
		DROP
	}

	private final long minIntervalMs;
	private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
	private final Long2LongOpenHashMap lastStartMsByPos = new Long2LongOpenHashMap();


	public SectionReloadScheduler(long minIntervalMs) { this.minIntervalMs = minIntervalMs; }


	/**
	 * moves every queued request into the pending set (duplicates collapse, first-arrival order is kept)
	 * @return how many requests were taken from the queue
	 */
	public int offerAll(Queue<Long> incoming)
	{
		int count = 0;
		Long pos;
		while ((pos = incoming.poll()) != null)
		{
			this.pending.add(pos.longValue());
			count++;
		}
		return count;
	}

	/**
	 * runs {@code reload} for every pending section whose interval has elapsed, in FIFO order
	 * @return how many re-builds started
	 */
	public int process(long nowMs, LongFunction<EResult> reload)
	{
		int started = 0;
		// forget starts that can no longer throttle anything, so the map only holds recent sections
		this.lastStartMsByPos.long2LongEntrySet().removeIf(entry -> nowMs - entry.getLongValue() >= this.minIntervalMs);

		LongIterator iterator = this.pending.iterator();
		while (iterator.hasNext())
		{
			long pos = iterator.nextLong();
			if (this.lastStartMsByPos.containsKey(pos))
			{
				// re-built recently: serve this request once the interval has elapsed
				continue;
			}

			EResult result = reload.apply(pos);
			if (result == EResult.STARTED)
			{
				this.lastStartMsByPos.put(pos, nowMs);
				iterator.remove();
				started++;
			}
			else if (result == EResult.DROP)
			{
				iterator.remove();
			}
		}
		return started;
	}

	public int pendingCount() { return this.pending.size(); }

}
