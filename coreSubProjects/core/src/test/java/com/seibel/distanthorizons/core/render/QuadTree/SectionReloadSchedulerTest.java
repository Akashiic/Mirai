package com.seibel.distanthorizons.core.render.QuadTree;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SectionReloadSchedulerTest
{
	private static ArrayDeque<Long> queue(long... positions)
	{
		ArrayDeque<Long> queue = new ArrayDeque<>();
		for (long pos : positions) { queue.add(pos); }
		return queue;
	}

	@Test
	public void duplicatesCollapseAndFifoOrderIsKept()
	{
		SectionReloadScheduler scheduler = new SectionReloadScheduler(250);
		// a data change at 1 queues 1 + its neighbours; a change at 2 overlaps them
		scheduler.offerAll(queue(1, 2, 3, 2, 1, 4, 3));
		List<Long> started = new ArrayList<>();
		scheduler.process(0, pos -> { started.add(pos); return SectionReloadScheduler.EResult.STARTED; });
		Assert.assertEquals(Arrays.asList(1L, 2L, 3L, 4L), started);
		Assert.assertEquals(0, scheduler.pendingCount());
	}

	@Test
	public void aBurstOnOneSectionBuildsOncePerInterval()
	{
		SectionReloadScheduler scheduler = new SectionReloadScheduler(250);
		List<Long> startedAt = new ArrayList<>();
		long[] now = { 0 };

		for (int tick = 0; tick < 50; tick++) // 50 ticks of 20 ms with a request each tick = 1 s
		{
			now[0] = tick * 20L;
			scheduler.offerAll(queue(7));
			scheduler.process(now[0], pos -> { startedAt.add(now[0]); return SectionReloadScheduler.EResult.STARTED; });
		}

		// 50 requests -> one build per 250 ms window, the first immediately
		Assert.assertEquals(Arrays.asList(0L, 260L, 520L, 780L), startedAt);
		Assert.assertEquals("the last request is still served after the window", 1, scheduler.pendingCount());
		scheduler.process(1_040, pos -> SectionReloadScheduler.EResult.STARTED);
		Assert.assertEquals(0, scheduler.pendingCount());
	}

	@Test
	public void anIsolatedRequestIsNotDelayed()
	{
		SectionReloadScheduler scheduler = new SectionReloadScheduler(250);
		scheduler.offerAll(queue(5));
		scheduler.process(0, pos -> SectionReloadScheduler.EResult.STARTED);

		// much later, a new change is served on the first tick
		scheduler.offerAll(queue(5));
		List<Long> started = new ArrayList<>();
		scheduler.process(10_000, pos -> { started.add(pos); return SectionReloadScheduler.EResult.STARTED; });
		Assert.assertEquals(Arrays.asList(5L), started);
	}

	@Test
	public void retryKeepsTheRequestAndDropRemovesIt()
	{
		SectionReloadScheduler scheduler = new SectionReloadScheduler(250);
		scheduler.offerAll(queue(1, 2));
		scheduler.process(0, pos -> pos == 1 ? SectionReloadScheduler.EResult.RETRY : SectionReloadScheduler.EResult.DROP);
		Assert.assertEquals(1, scheduler.pendingCount());

		// a build that was running finishes: the kept request is served with the current data
		List<Long> started = new ArrayList<>();
		scheduler.process(20, pos -> { started.add(pos); return SectionReloadScheduler.EResult.STARTED; });
		Assert.assertEquals(Arrays.asList(1L), started);
		Assert.assertEquals(0, scheduler.pendingCount());
	}

	@Test
	public void throttledSectionsDoNotBlockOthers()
	{
		SectionReloadScheduler scheduler = new SectionReloadScheduler(250);
		scheduler.offerAll(queue(1));
		scheduler.process(0, pos -> SectionReloadScheduler.EResult.STARTED);

		scheduler.offerAll(queue(1, 2));
		List<Long> started = new ArrayList<>();
		scheduler.process(100, pos -> { started.add(pos); return SectionReloadScheduler.EResult.STARTED; });
		Assert.assertEquals(Arrays.asList(2L), started);
		Assert.assertEquals(1, scheduler.pendingCount());
	}

}
