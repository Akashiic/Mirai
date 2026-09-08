package com.seibel.distanthorizons.core.render;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real handler and queue; a controlled client executor replaces Minecraft/GL. */
public class RenderTaskMaintenanceTest
{
    @Test public void stalledClientGetsOnlyOneMaintenanceCallback() throws Exception
    {
        try (RenderTaskScopeTest.QueueFixture f = new RenderTaskScopeTest.QueueFixture(true))
        {
            f.add("work", () -> {}); f.stale();
            for (int i = 0; i < 20; i++) { f.cleanupTick(); }
            assertEquals("A stalled executor must not accumulate cleanup callbacks", 1, f.callbacks.size());
            f.callbacks.remove().run();
            assertTrue(f.queue.isEmpty());
            f.add("later", () -> {}); f.stale(); f.cleanupTick();
            assertEquals("Completion must permit later maintenance", 1, f.callbacks.size());
        }
    }

    @Test public void resumedRenderingMakesOldCallbackRedundant() throws Exception
    {
        try (RenderTaskScopeTest.QueueFixture f = new RenderTaskScopeTest.QueueFixture(true))
        {
            f.add("old", () -> {}); f.stale(); f.cleanupTick();
            f.drain();
            AtomicInteger ran = new AtomicInteger(); f.add("new", ran::incrementAndGet);
            f.callbacks.remove().run();
            assertEquals("A stale callback must not drain the next frame's work", 0, ran.get());
            assertEquals(1, f.queue.size());
            f.drain(); assertEquals(1, ran.get());
        }
    }

    @Test public void maintenanceUsesNormalFrameBudget() throws Exception
    {
        try (RenderTaskScopeTest.QueueFixture f = new RenderTaskScopeTest.QueueFixture(true))
        {
            f.add("slow-driver-substitute", () -> {
                try { Thread.sleep(8); } catch (InterruptedException e) { throw new AssertionError(e); }
            });
            AtomicInteger next = new AtomicInteger(); f.add("next", next::incrementAndGet);
            f.stale(); f.cleanupTick(); f.callbacks.remove().run();
            assertEquals("Maintenance must yield after a task exceeds the frame budget", 0, next.get());
            assertEquals(1, f.queue.size()); f.drain(); assertEquals(1, next.get());
        }
    }

    @Test public void emptyQueueDoesNotScheduleMaintenance() throws Exception
    {
        try (RenderTaskScopeTest.QueueFixture f = new RenderTaskScopeTest.QueueFixture(true))
        { f.stale(); f.cleanupTick(); assertTrue(f.callbacks.isEmpty()); }
    }

    @Test public void rejectedSchedulingReleasesAdmission() throws Exception
    {
        try (RenderTaskScopeTest.QueueFixture f = new RenderTaskScopeTest.QueueFixture(true))
        {
            f.add("work", () -> {}); f.stale(); f.rejectScheduling = true;
            f.cleanupTick(); // No exception may escape and permanently terminate Timer.
            f.rejectScheduling = false; f.cleanupTick(); assertEquals(1, f.callbacks.size());
            f.callbacks.remove().run(); assertTrue(f.queue.isEmpty());
        }
    }

    @Test public void failingCallbackReleasesAdmissionAndPreservesNextTask() throws Exception
    {
        try (RenderTaskScopeTest.QueueFixture f = new RenderTaskScopeTest.QueueFixture(true))
        {
            AssertionError error = new AssertionError("synthetic failure");
            f.add("fail", () -> { throw error; }); f.add("next", () -> {});
            f.stale(); f.cleanupTick();
            try { f.callbacks.remove().run(); fail("Expected original error"); }
            catch (AssertionError actual) { assertSame(error, actual); }
            assertEquals(1, f.queue.size()); assertNull(f.current());
            f.stale(); f.cleanupTick(); assertEquals(1, f.callbacks.size());
            f.callbacks.remove().run(); assertTrue(f.queue.isEmpty());
        }
    }

    @Test public void normalTaskEnqueueDoesNotCaptureProducerStack() throws Exception
    {
        try (RenderTaskScopeTest.QueueFixture f = new RenderTaskScopeTest.QueueFixture(true))
        {
            f.handler.getClass().getMethod("queueRunningOnRenderThread", String.class, Runnable.class)
                    .invoke(f.handler, "ordinary-task", (Runnable) () -> {});
            Object task = f.queue.peek(); assertNotNull(task);
            assertNull("Player builds should not allocate a stack per task by default", task.getClass().getField("stackTrace").get(task));
        }
    }

    @Test public void explicitStackCaptureRemainsAvailable() throws Exception
    {
        String key = "akashic.mirai.renderTaskStacks";
        String previous = System.getProperty(key);
        System.setProperty(key, "true");
        try (RenderTaskScopeTest.QueueFixture f = new RenderTaskScopeTest.QueueFixture(true))
        {
            f.handler.getClass().getMethod("queueRunningOnRenderThread", String.class, Runnable.class)
                    .invoke(f.handler, "diagnostic-task", (Runnable) () -> {});
            Object task = f.queue.peek(); assertNotNull(task);
            StackTraceElement[] stack = (StackTraceElement[]) task.getClass().getField("stackTrace").get(task);
            assertNotNull(stack); assertTrue(stack.length > 0);
        }
        finally
        {
            if (previous == null) { System.clearProperty(key); }
            else { System.setProperty(key, previous); }
        }
    }

    @Test public void budgetPreservesFractionalMillisecondsAndPositiveProgress() throws Exception
    {
        try (RenderTaskScopeTest.QueueFixture f = new RenderTaskScopeTest.QueueFixture(false))
        {
            java.lang.reflect.Method budget = f.handler.getClass().getDeclaredMethod("taskBudgetNanos", int.class);
            budget.setAccessible(true);
            assertEquals(8_333_333L, budget.invoke(null, 60));
            assertEquals(2_083_333L, budget.invoke(null, 240));
            assertEquals(2_083_333L, budget.invoke(null, 0));
            assertEquals(2_083_333L, budget.invoke(null, 1));
            assertEquals(1L, budget.invoke(null, Integer.MAX_VALUE));
        }
    }
}
