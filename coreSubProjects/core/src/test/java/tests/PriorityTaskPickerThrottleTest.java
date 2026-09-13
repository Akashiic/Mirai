package tests;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression for a global picker slot being reserved behind post-task throttle. */
public class PriorityTaskPickerThrottleTest
{
    @Test
    public void throttledExecutorCannotReserveTheOnlyGlobalSlot() throws Exception
    {
        Config.Client.threadPresetSetting.get();
        int oldThreads = Config.Common.MultiThreading.numberOfThreads.get();
        double oldRatio = Config.Common.MultiThreading.threadRunTimeRatio.get();
        PriorityTaskPicker picker = null;
        try
        {
            Config.Common.MultiThreading.numberOfThreads.setWithoutFiringEvents(1);
            Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(1.0);
            picker = new PriorityTaskPicker();
            PriorityTaskPicker.Executor first = picker.createExecutor("Throttle owner");
            PriorityTaskPicker.Executor second = picker.createExecutor("Independent work");

            CountDownLatch warmDone = new CountDownLatch(1);
            second.execute(() ->
            {
                try { Thread.sleep(250); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { warmDone.countDown(); }
            });
            assertTrue("warm-up executor did not finish", warmDone.await(2, TimeUnit.SECONDS));
            // The latch is inside the Runnable; let afterExecute finish at ratio 1.0.
            Thread.sleep(100);

            Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(0.01);
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch independentRan = new CountDownLatch(1);
            first.execute(() ->
            {
                firstStarted.countDown();
                try { Thread.sleep(80); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            first.execute(() -> { });
            second.execute(independentRan::countDown);

            assertTrue("independent pool starved behind another pool's throttle",
                    independentRan.await(1500, TimeUnit.MILLISECONDS));
        }
        finally
        {
            if (picker != null) { picker.shutdownNow(); }
            Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(oldRatio);
            Config.Common.MultiThreading.numberOfThreads.setWithoutFiringEvents(oldThreads);
        }
    }

    @Test
    public void throttleBackpressurePreservesExecutorFifoOrder() throws Exception
    {
        Config.Client.threadPresetSetting.get();
        int oldThreads = Config.Common.MultiThreading.numberOfThreads.get();
        double oldRatio = Config.Common.MultiThreading.threadRunTimeRatio.get();
        PriorityTaskPicker picker = null;
        try
        {
            Config.Common.MultiThreading.numberOfThreads.setWithoutFiringEvents(1);
            Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(0.1);
            picker = new PriorityTaskPicker();
            PriorityTaskPicker.Executor executor = picker.createExecutor("fifo");
            StringBuffer order = new StringBuffer();
            CountDownLatch done = new CountDownLatch(3);
            executor.execute(() -> { order.append('A'); try { Thread.sleep(80); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } finally { done.countDown(); } });
            executor.execute(() -> { order.append('B'); done.countDown(); });
            executor.execute(() -> { order.append('C'); done.countDown(); });
            assertTrue("FIFO workload did not drain", done.await(3, TimeUnit.SECONDS));
            assertTrue("throttle reordered executor FIFO: " + order, "ABC".contentEquals(order));
        }
        finally
        {
            if (picker != null) { picker.shutdownNow(); }
            Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(oldRatio);
            Config.Common.MultiThreading.numberOfThreads.setWithoutFiringEvents(oldThreads);
        }
    }

    @Test
    public void contendedSchedulerPassCannotLoseQueuedWork() throws Exception
    {
        Config.Client.threadPresetSetting.get();
        int oldThreads = Config.Common.MultiThreading.numberOfThreads.get();
        double oldRatio = Config.Common.MultiThreading.threadRunTimeRatio.get();
        PriorityTaskPicker picker = null;
        CountDownLatch releasePass = new CountDownLatch(1);
        try
        {
            Config.Common.MultiThreading.numberOfThreads.setWithoutFiringEvents(1);
            Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(1.0);
            picker = new PriorityTaskPicker();
            PriorityTaskPicker.Executor target = picker.createExecutor("lost-wakeup-target");
            CountDownLatch passInsideBlocker = new CountDownLatch(1);
            picker.createExecutor("lost-wakeup-blocker", () ->
            {
                passInsideBlocker.countDown();
                try { releasePass.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return true;
            });
            PriorityTaskPicker finalPicker = picker;
            Thread holder = new Thread(finalPicker::requestWork, "picker-lock-holder");
            holder.start();
            assertTrue("scheduler pass never entered blocker", passInsideBlocker.await(1, TimeUnit.SECONDS));
            CountDownLatch ran = new CountDownLatch(1);
            target.execute(ran::countDown);
            releasePass.countDown();
            holder.join(1000);
            assertTrue("scheduler lost work queued during lock contention", ran.await(1, TimeUnit.SECONDS));
        }
        finally
        {
            releasePass.countDown();
            if (picker != null) { picker.shutdownNow(); }
            Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(oldRatio);
            Config.Common.MultiThreading.numberOfThreads.setWithoutFiringEvents(oldThreads);
        }
    }

    @Test
    public void instantaneousTasksLeaveGlobalOccupancyAtZero() throws Exception
    {
        Config.Client.threadPresetSetting.get();
        int oldThreads = Config.Common.MultiThreading.numberOfThreads.get();
        double oldRatio = Config.Common.MultiThreading.threadRunTimeRatio.get();
        PriorityTaskPicker picker = null;
        try
        {
            Config.Common.MultiThreading.numberOfThreads.setWithoutFiringEvents(4);
            Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(1.0);
            picker = new PriorityTaskPicker();
            PriorityTaskPicker.Executor first = picker.createExecutor("instant-a");
            PriorityTaskPicker.Executor second = picker.createExecutor("instant-b");
            CountDownLatch done = new CountDownLatch(2000);
            for (int i = 0; i < 1000; i++)
            {
                first.execute(done::countDown);
                second.execute(done::countDown);
            }
            assertTrue("instant tasks did not drain", done.await(8, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (picker.getOccupiedThreadCount() != 0 && System.nanoTime() < deadline) { Thread.yield(); }
            assertTrue("global occupancy leaked or underflowed: " + picker.getOccupiedThreadCount(),
                    picker.getOccupiedThreadCount() == 0);
        }
        finally
        {
            if (picker != null) { picker.shutdownNow(); }
            Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(oldRatio);
            Config.Common.MultiThreading.numberOfThreads.setWithoutFiringEvents(oldThreads);
        }
    }
    @Test
    public void gracefulShutdownWaitsForActiveTaskWithoutInterruptingIt() throws Exception
    {
        Config.Client.threadPresetSetting.get();
        int oldThreads = Config.Common.MultiThreading.numberOfThreads.get();
        double oldRatio = Config.Common.MultiThreading.threadRunTimeRatio.get();
        PriorityTaskPicker picker = null;
        CountDownLatch release = new CountDownLatch(1);
        try
        {
            Config.Common.MultiThreading.numberOfThreads.setWithoutFiringEvents(1);
            Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(1.0);
            picker = new PriorityTaskPicker();
            PriorityTaskPicker.Executor executor = picker.createExecutor("shutdown-contract");
            CountDownLatch started = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean(false);
            executor.execute(() ->
            {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException e) { interrupted.set(true); Thread.currentThread().interrupt(); }
            });
            assertTrue("active task did not start", started.await(1, TimeUnit.SECONDS));
            PriorityTaskPicker finalPicker = picker;
            Thread shutdown = new Thread(finalPicker::shutdownNow, "picker-shutdown-contract");
            shutdown.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (!executor.isShutdown() && System.nanoTime() < deadline) { Thread.yield(); }
            assertTrue("shutdown was not signaled", executor.isShutdown());
            assertTrue("graceful shutdown returned before active task completed", shutdown.isAlive());
            assertFalse("graceful shutdown interrupted active task", interrupted.get());
            release.countDown();
            shutdown.join(2_000);
            assertFalse("shutdown did not finish after active task completed", shutdown.isAlive());
            assertFalse("active task was interrupted during graceful shutdown", interrupted.get());
            assertTrue("executor did not terminate", executor.isTerminated());
            assertTrue("termination timestamp was not recorded", executor.getTerminatedAtNanos() > 0);
            picker = null;
        }
        finally
        {
            release.countDown();
            if (picker != null) { picker.shutdownNow(); }
            Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(oldRatio);
            Config.Common.MultiThreading.numberOfThreads.setWithoutFiringEvents(oldThreads);
        }
    }

}
