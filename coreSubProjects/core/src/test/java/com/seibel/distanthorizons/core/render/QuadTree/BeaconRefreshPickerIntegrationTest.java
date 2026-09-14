package com.seibel.distanthorizons.core.render.QuadTree;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class BeaconRefreshPickerIntegrationTest
{
    @Test public void realPickerReleasesIoDuringCooldown() throws Exception
    {
        try (Fixture f = new Fixture())
        {
            AtomicInteger calls = new AtomicInteger();
            BeaconRefreshScheduler gate = new BeaconRefreshScheduler(() -> 0L, () -> false, p -> calls.incrementAndGet());
            long start = System.nanoTime();
            assertTrue(gate.trySubmit(f.io, new DhBlockPos2D(0, 0)));
            assertEquals("free", f.io.submit(() -> "free").get(1, TimeUnit.SECONDS));
            long ioWall = System.nanoTime() - start;
            assertEquals(1, calls.get()); assertFalse(gate.trySubmit(f.io, new DhBlockPos2D(0, 0)));
            start = System.nanoTime(); f.picker.shutdownNow();
            long shutdownWall = System.nanoTime() - start;
            assertTrue("shutdown retained idle delay", shutdownWall < TimeUnit.SECONDS.toNanos(1));
            System.out.println("BEACON_PICKER_PROBE io_marker_ns=" + ioWall + " shutdown_ns=" + shutdownWall);
        }
    }

    @Test public void realPickerStillWaitsForActiveQueryWithoutInterruptingIt() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), stopped = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(), closing = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (Fixture f = new Fixture())
        {
            BeaconRefreshScheduler gate = new BeaconRefreshScheduler(closing::get, p -> {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) { failure.set(new AssertionError("query not released")); } }
                catch (InterruptedException e) { interrupted.set(true); Thread.currentThread().interrupt(); }
            });
            assertTrue(gate.trySubmit(f.io, new DhBlockPos2D(0, 0)));
            assertTrue(entered.await(2, TimeUnit.SECONDS)); closing.set(true);
            Thread shutdown = new Thread(() -> {
                try { f.picker.shutdownNow(); } catch (Throwable t) { failure.set(t); }
                finally { stopped.countDown(); }
            }, "beacon-test-shutdown");
            shutdown.start();
            try
            {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (!f.io.isShutdown() && System.nanoTime() - deadline < 0) { Thread.yield(); }
                assertTrue("shutdown was not signalled", f.io.isShutdown());
                assertFalse("active query abandoned", stopped.await(75, TimeUnit.MILLISECONDS));
                assertFalse(interrupted.get()); release.countDown();
                assertTrue(stopped.await(3, TimeUnit.SECONDS)); assertNull(failure.get()); assertFalse(interrupted.get());
            }
            finally { release.countDown(); shutdown.join(5000); assertFalse(shutdown.isAlive()); }
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final int oldThreads;
        final double oldRatio;
        final PriorityTaskPicker picker;
        final PriorityTaskPicker.Executor io;
        Fixture()
        {
            Config.Client.threadPresetSetting.get();
            oldThreads = Config.Common.MultiThreading.numberOfThreads.get();
            oldRatio = Config.Common.MultiThreading.threadRunTimeRatio.get();
            Config.Common.MultiThreading.numberOfThreads.setWithoutFiringEvents(1);
            Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(1.0);
            picker = new PriorityTaskPicker(); io = picker.createExecutor("beacon-IO-probe");
        }
        @Override public void close()
        {
            try { if (!io.isShutdown()) { picker.shutdownNow(); } }
            finally
            {
                Config.Common.MultiThreading.numberOfThreads.setWithoutFiringEvents(oldThreads);
                Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(oldRatio);
            }
        }
    }
}
