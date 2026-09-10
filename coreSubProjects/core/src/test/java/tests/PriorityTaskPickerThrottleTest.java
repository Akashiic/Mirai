package tests;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
}
