package com.seibel.distanthorizons.core.logging.mirai;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Rare picker-shutdown forensics. Never changes cancellation or scheduling policy. */
public final class MiraiPickerShutdownDiagnostics
{
    private static final AtomicLong IDS = new AtomicLong();
    private MiraiPickerShutdownDiagnostics() { }

    public static long begin(int executorCount, int occupiedThreads, double runTimeRatio)
    {
        if (!MiraiDiagnostics.enabled()) { return 0; }
        long id = IDS.incrementAndGet();
        MiraiDiagnostics.event("picker_shutdown", "id=" + id + " begin executors=" + executorCount
                + " occupied=" + occupiedThreads + " runtime_ratio=" + runTimeRatio);
        snapshotDhThreads(id);
        return id;
    }

    public static long startNanos(long id) { return id == 0 ? 0 : System.nanoTime(); }

    public static void executorState(long id, String stage, String name,
            int pickerQueue, int running, int reserved, int activeWorkers, int workerQueue)
    {
        if (id == 0) { return; }
        MiraiDiagnostics.event("picker_shutdown", "id=" + id + " stage=" + stage
                + " executor=" + safe(name) + " picker_queue=" + pickerQueue
                + " running=" + running + " reserved=" + reserved
                + " active_workers=" + activeWorkers + " worker_queue=" + workerQueue);
    }

    public static void taskFinished(long id, long shutdownStartNanos, String executorName,
            String commandClass, long taskRuntimeNanos, long finishedAtNanos, boolean interrupted)
    {
        if (id == 0) { return; }
        long shutdownElapsed = shutdownStartNanos == 0 ? -1 : Math.max(0, finishedAtNanos - shutdownStartNanos);
        MiraiDiagnostics.event("picker_shutdown", "id=" + id + " task_finished executor=" + safe(executorName)
                + " command=" + safe(commandClass) + " task_runtime_ns=" + Math.max(0, taskRuntimeNanos)
                + " shutdown_elapsed_ns=" + shutdownElapsed + " interrupted=" + interrupted);
    }

    public static void executorFinished(long id, long shutdownStartNanos, String name,
            long waitCallNanos, long terminatedAtNanos, boolean forced,
            int pickerQueue, int running, int reserved, int activeWorkers, int workerQueue)
    {
        if (id == 0) { return; }
        long terminatedAfter = (shutdownStartNanos == 0 || terminatedAtNanos == 0)
                ? -1 : Math.max(0, terminatedAtNanos - shutdownStartNanos);
        MiraiDiagnostics.event("picker_shutdown", "id=" + id + " executor=" + safe(name)
                + " terminated_after_ns=" + terminatedAfter + " wait_call_ns=" + Math.max(0, waitCallNanos)
                + " forced=" + forced + " picker_queue=" + pickerQueue + " running=" + running
                + " reserved=" + reserved + " active_workers=" + activeWorkers + " worker_queue=" + workerQueue);
    }

    public static void end(long id, long shutdownStartNanos, boolean completed)
    {
        if (id == 0) { return; }
        long elapsed = shutdownStartNanos == 0 ? -1 : Math.max(0, System.nanoTime() - shutdownStartNanos);
        MiraiDiagnostics.event("picker_shutdown", "id=" + id + " end wall_ns=" + elapsed
                + " completed=" + completed);
    }

    private static void snapshotDhThreads(long id)
    {
        try
        {
            int emitted = 0;
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet())
            {
                Thread thread = entry.getKey();
                if (!thread.isAlive() || !thread.getName().startsWith("DH-")) { continue; }
                StringBuilder detail = new StringBuilder("id=").append(id).append(" thread=").append(safe(thread.getName())).append(" state=").append(thread.getState());
                StackTraceElement[] stack = entry.getValue();
                for (int i = 0; i < Math.min(6, stack.length); i++)
                { detail.append(" <- ").append(safe(stack[i].getClassName())).append('.').append(safe(stack[i].getMethodName())).append(':').append(stack[i].getLineNumber()); }
                MiraiDiagnostics.event("picker_shutdown_thread", detail.toString());
                if (++emitted >= 16) { break; }
            }
        }
        catch (SecurityException unavailable)
        { MiraiDiagnostics.event("picker_shutdown_thread", "id=" + id + " unavailable=SecurityException"); }
    }

    private static String safe(String value)
    {
        if (value == null) { return "unavailable"; }
        return value.replace(' ', '_').replace('\t', '_').replace('\n', '_').replace('\r', '_');
    }
}
