package com.seibel.distanthorizons.core.render.QuadTree;

import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class BeaconRefreshSchedulerTest
{
    private static final long DELAY = TimeUnit.SECONDS.toNanos(2);
    private static final DhBlockPos2D POS = new DhBlockPos2D(13, -29);
    private final AtomicLong clock = new AtomicLong();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicInteger calls = new AtomicInteger();
    private final ManualExecutor io = new ManualExecutor();
    private final BeaconRefreshScheduler gate = new BeaconRefreshScheduler(clock::get, closing::get, p -> calls.incrementAndGet());

    @Test public void firstRunIsImmediateAndDelayHasExactBoundary()
    {
        assertTrue(gate.trySubmit(io, POS)); io.runNext();
        assertEquals(1, calls.get());
        clock.set(DELAY - 1); assertFalse(gate.trySubmit(io, POS));
        clock.set(DELAY); assertTrue(gate.trySubmit(io, POS)); io.runNext();
        assertEquals(2, calls.get());
    }

    @Test public void delayStartsAtCompletionNotSubmissionOrQueueTime()
    {
        BeaconRefreshScheduler slow = new BeaconRefreshScheduler(clock::get, closing::get, p -> clock.addAndGet(7 * DELAY));
        assertTrue(slow.trySubmit(io, POS)); clock.set(5 * DELAY);
        assertFalse(slow.trySubmit(io, POS)); io.runNext();
        assertEquals(12 * DELAY, clock.get());
        clock.addAndGet(DELAY - 1); assertFalse(slow.trySubmit(io, POS));
        clock.incrementAndGet(); assertTrue(slow.trySubmit(io, POS));
    }

    @Test public void signedWraparoundAndNegativeClockOriginRemainValid()
    {
        clock.set(Long.MAX_VALUE - DELAY / 2);
        assertTrue(gate.trySubmit(io, POS)); io.runNext();
        clock.addAndGet(DELAY - 1); assertTrue(clock.get() < 0);
        assertFalse(gate.trySubmit(io, POS)); clock.incrementAndGet();
        assertTrue(gate.trySubmit(io, POS)); io.runNext();
        BeaconRefreshScheduler negative = new BeaconRefreshScheduler(() -> -100L, closing::get, p -> { });
        assertTrue(negative.trySubmit(io, POS));
    }

    @Test public void unavailableExecutorDoesNotReserveWork()
    {
        assertFalse(gate.trySubmit(null, POS));
        ManualExecutor stopped = new ManualExecutor(); stopped.shutdown();
        assertFalse(gate.trySubmit(stopped, POS)); assertTrue(gate.trySubmit(io, POS));
    }

    @Test public void rejectionAllowsImmediateRetry()
    {
        ManualExecutor reject = new ManualExecutor() {
            @Override public void execute(Runnable r) { throw new RejectedExecutionException("race"); }
        };
        assertFalse(gate.trySubmit(reject, POS)); assertTrue(gate.trySubmit(io, POS));
        io.runNext(); assertEquals(1, calls.get());
    }

    @Test public void closeBeforeSubmissionAndBeforeExecutionSkipsWork()
    {
        closing.set(true); assertFalse(gate.trySubmit(io, POS)); assertEquals(0, io.size());
        closing.set(false); assertTrue(gate.trySubmit(io, POS)); closing.set(true);
        io.runNext(); assertEquals(0, calls.get());
    }

    @Test public void failedWorkIsNotSwallowedAndReservationIsReleasedAfterDelay()
    {
        AtomicBoolean fail = new AtomicBoolean(true);
        BeaconRefreshScheduler tested = new BeaconRefreshScheduler(clock::get, closing::get, p -> {
            if (fail.getAndSet(false)) { throw new IllegalStateException("expected"); }
            calls.incrementAndGet();
        });
        assertTrue(tested.trySubmit(io, POS));
        try { io.runNext(); fail("failure swallowed"); } catch (IllegalStateException expected) { }
        assertFalse(tested.trySubmit(io, POS)); clock.set(DELAY);
        assertTrue(tested.trySubmit(io, POS)); io.runNext(); assertEquals(1, calls.get());
    }

    @Test public void inlineBodyRejectionIsNotConfusedWithSubmissionRejection()
    {
        ManualExecutor inline = new ManualExecutor() {
            @Override public void execute(Runnable r) { r.run(); }
        };
        BeaconRefreshScheduler tested = new BeaconRefreshScheduler(clock::get, closing::get, p -> {
            throw new RejectedExecutionException("from work");
        });
        try { tested.trySubmit(inline, POS); fail("failure swallowed"); }
        catch (RejectedExecutionException expected) { assertEquals("from work", expected.getMessage()); }
        assertFalse(tested.trySubmit(io, POS)); clock.set(DELAY);
        assertTrue(tested.trySubmit(io, POS));
    }

    @Test public void executorFailureDoesNotStrandReservation()
    {
        ManualExecutor broken = new ManualExecutor() {
            @Override public void execute(Runnable r) { throw new IllegalStateException("executor"); }
        };
        try { gate.trySubmit(broken, POS); fail("failure swallowed"); } catch (IllegalStateException expected) { }
        assertTrue(gate.trySubmit(io, POS));
    }

    @Test public void fatalWorkFailureStillReleasesReservation()
    {
        BeaconRefreshScheduler tested = new BeaconRefreshScheduler(clock::get, closing::get, p -> { throw new AssertionError("work"); });
        assertTrue(tested.trySubmit(io, POS));
        try { io.runNext(); fail("failure swallowed"); } catch (AssertionError expected) { assertEquals("work", expected.getMessage()); }
        clock.set(DELAY); assertTrue(tested.trySubmit(io, POS));
    }

    @Test public void replacementRevokesOnlyUnstartedWorkAndStaleRunCannotReleaseNewTask()
    {
        assertTrue(gate.trySubmit(io, POS)); Runnable stale = io.take(); io.shutdown();
        ManualExecutor fresh = new ManualExecutor(); assertTrue(gate.trySubmit(fresh, POS));
        stale.run(); assertEquals(0, calls.get());
        assertFalse(gate.trySubmit(fresh, POS)); assertEquals(1, fresh.size());
        fresh.runNext(); assertEquals(1, calls.get());
        stale.run(); assertFalse(gate.trySubmit(fresh, POS));
    }

    @Test public void repeatedRunnableCannotExecuteTwice()
    {
        assertTrue(gate.trySubmit(io, POS)); Runnable task = io.take(); task.run();
        clock.set(DELAY); assertTrue(gate.trySubmit(io, POS)); task.run();
        assertFalse(gate.trySubmit(io, POS)); io.runNext(); assertEquals(2, calls.get());
    }

    @Test public void treesHaveIndependentSchedulesAndKeepPosition()
    {
        AtomicReference<DhBlockPos2D> received = new AtomicReference<>();
        BeaconRefreshScheduler other = new BeaconRefreshScheduler(clock::get, closing::get, received::set);
        assertTrue(gate.trySubmit(io, POS)); io.runNext();
        assertTrue(other.trySubmit(io, POS)); io.runNext();
        assertSame(POS, received.get()); assertFalse(gate.trySubmit(io, POS));
    }

    @Test public void concurrentSubmissionsReserveExactlyOneTask() throws Exception
    {
        ExecutorService callers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try
        {
            for (int i = 0; i < 32; i++) { results.add(callers.submit(() -> { start.await(); return gate.trySubmit(io, POS); })); }
            start.countDown(); int accepted = 0;
            for (Future<Boolean> f : results) { if (f.get(5, TimeUnit.SECONDS)) { accepted++; } }
            assertEquals(1, accepted); assertEquals(1, io.size());
            io.runNext(); assertEquals(1, calls.get());
        }
        finally { start.countDown(); callers.shutdownNow(); assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test public void idleIntervalDoesNotKeepARealIoWorkerOccupied() throws Exception
    {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try
        {
            assertTrue(gate.trySubmit(worker, POS));
            assertEquals("available", worker.submit(() -> "available").get(1, TimeUnit.SECONDS));
            assertEquals(1, calls.get()); assertFalse(gate.trySubmit(worker, POS));
            worker.shutdown(); assertTrue(worker.awaitTermination(1, TimeUnit.SECONDS));
        }
        finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    @Test public void activeWorkSurvivesExecutorReplacementAndGracefulShutdown() throws Exception
    {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        BeaconRefreshScheduler tested = new BeaconRefreshScheduler(clock::get, closing::get, p -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) { throw new AssertionError("not released"); } }
            catch (InterruptedException e) { interrupted.set(true); Thread.currentThread().interrupt(); }
            calls.incrementAndGet();
        });
        try
        {
            assertTrue(tested.trySubmit(worker, POS)); assertTrue(entered.await(2, TimeUnit.SECONDS));
            worker.shutdown(); clock.set(10 * DELAY);
            assertFalse(tested.trySubmit(io, POS)); assertFalse(worker.isTerminated());
            release.countDown(); assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            assertFalse(interrupted.get()); assertEquals(1, calls.get());
            assertFalse(tested.trySubmit(io, POS)); clock.addAndGet(DELAY);
            assertTrue(tested.trySubmit(io, POS));
        }
        finally { release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private static class ManualExecutor extends AbstractExecutorService
    {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private volatile boolean shutdown;
        @Override public synchronized void execute(Runnable r)
        { if (shutdown) { throw new RejectedExecutionException(); } tasks.add(r); }
        synchronized Runnable take() { return tasks.remove(); }
        void runNext() { take().run(); }
        synchronized int size() { return tasks.size(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public synchronized List<Runnable> shutdownNow()
        { shutdown = true; List<Runnable> rest = new ArrayList<>(tasks); tasks.clear(); return rest; }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public synchronized boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
