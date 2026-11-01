package tests;

import com.seibel.distanthorizons.core.generation.PregenTask;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;

/** Headless tests for the server pregen state machine (AOTM-19). */
public class PregenTaskTest
{
	private static final long ORIGIN = DhSectionPos.encode(DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL, 0, 0);

	/** Records every call; generation futures are completed by the test. */
	private static class FakeTarget implements PregenTask.ITarget
	{
		LongPredicate skip = pos -> false;
		LongPredicate alreadyGenerated = pos -> false;
		boolean queueAvailable = true;
		final Map<Long, CompletableFuture<Object>> queued = new LinkedHashMap<>();
		final AtomicInteger queueCalls = new AtomicInteger();

		@Override
		public boolean shouldSkip(long sectionPos) { return this.skip.test(sectionPos); }

		@Override
		public CompletableFuture<Boolean> isFullyGenerated(long sectionPos)
		{ return CompletableFuture.completedFuture(this.alreadyGenerated.test(sectionPos)); }

		@Override
		public CompletableFuture<?> queueGeneration(long sectionPos)
		{
			this.queueCalls.incrementAndGet();
			if (!this.queueAvailable)
			{
				return null;
			}
			CompletableFuture<Object> future = new CompletableFuture<>();
			this.queued.put(sectionPos, future);
			return future;
		}

		void completeAll()
		{
			for (CompletableFuture<Object> future : new ArrayList<>(this.queued.values()))
			{
				future.complete(null);
			}
		}
	}

	private static PregenTask.Settings settings(AtomicLong clock, int maxInFlight)
	{
		PregenTask.Settings settings = new PregenTask.Settings();
		settings.clockMs = clock::get;
		settings.maxInFlight = () -> maxInFlight;
		settings.taskTimeoutMs = 1_000;
		settings.queueUnavailableTimeoutMs = 5_000;
		return settings;
	}



	@Test
	public void completesOnlyAfterInFlightSectionsFinish()
	{
		FakeTarget target = new FakeTarget();
		PregenTask task = new PregenTask(target, settings(new AtomicLong(), 2), ORIGIN, 4, 0);

		task.pump();
		Assert.assertEquals(2, task.getInFlightCount());
		target.completeAll();
		task.pump();
		Assert.assertEquals(4, task.getVisitedCount());
		Assert.assertFalse("spiral exhausted but sections still in flight", task.isDone());

		target.completeAll();
		task.pump();
		Assert.assertTrue(task.isDone());
		Assert.assertFalse(task.isCompletedExceptionally());
		Assert.assertEquals(4, task.getGeneratedCount());
	}

	@Test
	public void alreadyGeneratedSectionsAreNotQueued()
	{
		FakeTarget target = new FakeTarget();
		target.alreadyGenerated = pos -> true;
		PregenTask task = new PregenTask(target, settings(new AtomicLong(), 3), ORIGIN, 9, 0);

		for (int i = 0; i < 5 && !task.isDone(); i++) { task.pump(); }

		Assert.assertTrue(task.isDone());
		Assert.assertEquals(9, task.getAlreadyGeneratedCount());
		Assert.assertEquals(0, target.queueCalls.get());
	}

	@Test
	public void missingQueueRetriesThenFailsWithClearMessage() throws InterruptedException
	{
		AtomicLong clock = new AtomicLong();
		FakeTarget target = new FakeTarget();
		target.queueAvailable = false;
		PregenTask task = new PregenTask(target, settings(clock, 2), ORIGIN, 4, 0);

		task.pump();
		Assert.assertTrue(task.isWaitingForQueue());
		Assert.assertEquals("a missing queue must not occupy slots", 0, task.getInFlightCount());
		clock.addAndGet(4_000);
		task.pump();
		Assert.assertFalse(task.isDone());

		clock.addAndGet(1_000);
		task.pump();
		Assert.assertTrue(task.isCompletedExceptionally());
		try
		{
			task.get();
			Assert.fail();
		}
		catch (ExecutionException e)
		{
			Assert.assertTrue(e.getCause() instanceof IllegalStateException);
			Assert.assertTrue(e.getCause().getMessage().contains("world generation queue"));
		}
	}

	@Test
	public void queueThatAppearsLateIsUsedWithoutLosingSections()
	{
		AtomicLong clock = new AtomicLong();
		FakeTarget target = new FakeTarget();
		target.queueAvailable = false;
		PregenTask task = new PregenTask(target, settings(clock, 2), ORIGIN, 4, 0);

		task.pump();
		task.pump();
		target.queueAvailable = true;
		clock.addAndGet(100);
		for (int i = 0; i < 10 && !task.isDone(); i++)
		{
			task.pump();
			target.completeAll();
		}

		Assert.assertTrue(task.isDone());
		Assert.assertFalse(task.isCompletedExceptionally());
		Assert.assertFalse(task.isWaitingForQueue());
		Assert.assertEquals(4, task.getGeneratedCount());
		Assert.assertEquals(4, target.queued.size());
	}

	@Test
	public void failedSectionDoesNotStallTheRun()
	{
		FakeTarget target = new FakeTarget();
		PregenTask task = new PregenTask(target, settings(new AtomicLong(), 1), ORIGIN, 3, 0);

		task.pump();
		target.queued.values().iterator().next().completeExceptionally(new RuntimeException("generator failed"));
		for (int i = 0; i < 10 && !task.isDone(); i++)
		{
			task.pump();
			target.completeAll();
		}

		Assert.assertTrue(task.isDone());
		Assert.assertEquals(1, task.getFailedCount());
		Assert.assertEquals(2, task.getGeneratedCount());
	}

	@Test
	public void failedReadCountsAsFailure()
	{
		FakeTarget target = new FakeTarget()
		{
			@Override
			public CompletableFuture<Boolean> isFullyGenerated(long sectionPos)
			{
				CompletableFuture<Boolean> failed = new CompletableFuture<>();
				failed.completeExceptionally(new RuntimeException("read failed"));
				return failed;
			}
		};
		PregenTask task = new PregenTask(target, settings(new AtomicLong(), 2), ORIGIN, 4, 0);

		for (int i = 0; i < 5 && !task.isDone(); i++) { task.pump(); }

		Assert.assertTrue(task.isDone());
		Assert.assertEquals(4, task.getFailedCount());
	}

	@Test
	public void stuckSectionTimesOutAndLateCompletionIsIgnored()
	{
		AtomicLong clock = new AtomicLong();
		FakeTarget target = new FakeTarget();
		PregenTask task = new PregenTask(target, settings(clock, 1), ORIGIN, 1, 0);

		task.pump();
		CompletableFuture<Object> stuck = target.queued.values().iterator().next();
		clock.addAndGet(1_000);
		task.pump();
		Assert.assertTrue(task.isDone());
		Assert.assertEquals(1, task.getFailedCount());

		stuck.complete(null);
		Assert.assertEquals(0, task.getGeneratedCount());
		Assert.assertEquals(1, task.getFailedCount());
	}

	@Test
	public void highServerTickPausesSubmission()
	{
		AtomicLong tickMs = new AtomicLong(100);
		FakeTarget target = new FakeTarget();
		PregenTask.Settings settings = settings(new AtomicLong(), 2);
		settings.serverTickMs = tickMs::get;
		settings.maxServerTickMs = 45;
		PregenTask task = new PregenTask(target, settings, ORIGIN, 4, 0);

		task.pump();
		Assert.assertTrue(task.isThrottled());
		Assert.assertEquals(0, task.getVisitedCount());

		tickMs.set(20);
		task.pump();
		Assert.assertFalse(task.isThrottled());
		Assert.assertEquals(2, task.getInFlightCount());
	}

	@Test
	public void skippedSectionsNeverReachTheGenerator()
	{
		FakeTarget target = new FakeTarget();
		target.skip = pos -> true;
		PregenTask task = new PregenTask(target, settings(new AtomicLong(), 2), ORIGIN, 9, 0);

		task.pump();

		Assert.assertTrue(task.isDone());
		Assert.assertEquals(9, task.getSkippedCount());
		Assert.assertEquals(0, target.queueCalls.get());
	}

	@Test
	public void checkpointIsLowestUnfinishedIndexAndResumeSkipsDoneSections()
	{
		List<Integer> checkpoints = new ArrayList<>();
		FakeTarget target = new FakeTarget();
		PregenTask.Settings settings = settings(new AtomicLong(), 2);
		settings.checkpoint = checkpoints::add;
		PregenTask task = new PregenTask(target, settings, ORIGIN, 9, 0);

		task.pump();
		List<CompletableFuture<Object>> first = new ArrayList<>(target.queued.values());
		first.get(1).complete(null); // index 1 done, index 0 still in flight
		Assert.assertEquals(Integer.valueOf(0), checkpoints.get(checkpoints.size() - 1));
		first.get(0).complete(null);
		Assert.assertEquals(Integer.valueOf(2), checkpoints.get(checkpoints.size() - 1));

		FakeTarget resumedTarget = new FakeTarget();
		PregenTask resumed = new PregenTask(resumedTarget, settings(new AtomicLong(), 9), ORIGIN, 9, 2);
		resumed.pump();
		Assert.assertEquals(7, resumedTarget.queued.size());
		Assert.assertFalse(resumedTarget.queued.containsKey(resumed.sectionPosOnSpiral(0)));
		Assert.assertFalse(resumedTarget.queued.containsKey(resumed.sectionPosOnSpiral(1)));
	}

	@Test
	public void cancelStopsSubmission()
	{
		FakeTarget target = new FakeTarget();
		PregenTask task = new PregenTask(target, settings(new AtomicLong(), 1), ORIGIN, 4, 0);

		task.pump();
		task.cancel(true);
		target.completeAll();
		task.pump();

		Assert.assertEquals(1, target.queueCalls.get());
		try
		{
			task.join();
			Assert.fail();
		}
		catch (CancellationException expected) { }
	}

	@Test
	public void spiralCoversTheSquareExactlyOnce()
	{
		PregenTask task = new PregenTask(new FakeTarget(), settings(new AtomicLong(), 1),
			DhSectionPos.encode(DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL, 10, -7), 0, 0);
		int ringRadius = 5;
		int count = (2 * ringRadius + 1) * (2 * ringRadius + 1);

		Set<Long> seen = new HashSet<>();
		for (int i = 0; i < count; i++)
		{
			long pos = task.sectionPosOnSpiral(i);
			Assert.assertTrue("duplicate at index " + i, seen.add(pos));
			Assert.assertTrue(Math.abs(DhSectionPos.getX(pos) - 10) <= ringRadius);
			Assert.assertTrue(Math.abs(DhSectionPos.getZ(pos) + 7) <= ringRadius);
		}
		Assert.assertEquals(count, seen.size());
	}

	@Test
	public void timedOutSectionIsCancelledInTheQueue()
	{
		AtomicLong clock = new AtomicLong();
		List<Long> cancelled = new ArrayList<>();
		FakeTarget target = new FakeTarget()
		{
			@Override public void cancelGeneration(long sectionPos) { cancelled.add(sectionPos); }
		};
		PregenTask task = new PregenTask(target, settings(clock, 1), ORIGIN, 1, 0);
		
		task.pump();
		clock.addAndGet(1_000);
		task.pump();
		
		Assert.assertEquals(1, cancelled.size());
		Assert.assertEquals(Long.valueOf(ORIGIN), cancelled.get(0));
	}
	
	@Test
	public void spiralIndexArithmeticDoesNotOverflowAtTheRadiusLimit()
	{
		PregenTask task = new PregenTask(new FakeTarget(), settings(new AtomicLong(), 1), ORIGIN, 0, 0);
		int count = PregenTask.sectionCountForChunkRadius(PregenTask.MAX_CHUNK_RADIUS);
		long last = task.sectionPosOnSpiral(count - 1);
		int ring = PregenTask.MAX_CHUNK_RADIUS / 4;
		Assert.assertEquals(ring, Math.max(Math.abs(DhSectionPos.getX(last)), Math.abs(DhSectionPos.getZ(last))));
	}
	
	@Test
	public void sectionCountCoversFullRings()
	{
		// full rings: side = 2 * ceil(radius / 4) + 1
		Assert.assertEquals(17 * 17, PregenTask.sectionCountForChunkRadius(32));
		Assert.assertEquals(9, PregenTask.sectionCountForChunkRadius(4));
		Assert.assertEquals(9, PregenTask.sectionCountForChunkRadius(1));
		Assert.assertEquals(1, PregenTask.sectionCountForChunkRadius(0));
		long maxSide = 2L * (PregenTask.MAX_CHUNK_RADIUS / 4) + 1;
		Assert.assertEquals(maxSide * maxSide, PregenTask.sectionCountForChunkRadius(Integer.MAX_VALUE));
	}

}
