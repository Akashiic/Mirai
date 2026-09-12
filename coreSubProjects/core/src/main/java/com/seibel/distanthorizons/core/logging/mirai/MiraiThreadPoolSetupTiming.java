package com.seibel.distanthorizons.core.logging.mirai;

import java.util.concurrent.atomic.AtomicLong;

/** Rare lifecycle instrumentation. Never changes pool policy or scheduling. */
public final class MiraiThreadPoolSetupTiming
{
    public enum Phase
    {
        OLD_PICKER_SHUTDOWN, PICKER_CREATE, NETWORK_COMPRESSION,
        NETWORK_CLIENT_HANDLER, IO, RENDER_LOADER, LOD_BUILDER,
        UPDATE_PROPAGATOR, WORLD_GEN, BEACON_CULLING, FULL_DATA_MIGRATION
    }

    private static final AtomicLong IDS = new AtomicLong();
    private MiraiThreadPoolSetupTiming() { }

    public static long begin()
    {
        if (!MiraiDiagnostics.enabled()) { return 0; }
        long id = IDS.incrementAndGet();
        MiraiDiagnostics.event("thread_pool_setup", "id=" + id + " begin");
        return id;
    }

    public static void run(long id, Phase phase, Runnable action)
    {
        if (id == 0)
        {
            action.run();
            return;
        }
        long start = System.nanoTime();
        boolean completed = false;
        try
        {
            action.run();
            completed = true;
        }
        finally
        {
            long elapsed = System.nanoTime() - start;
            MiraiDiagnostics.event("thread_pool_setup", "id=" + id + " phase=" + phase
                    + " wall_ns=" + Math.max(0, elapsed) + " completed=" + completed);
        }
    }

    public static void end(long id, long setupStart, boolean completed)
    {
        if (id == 0) { return; }
        long elapsed = System.nanoTime() - setupStart;
        MiraiDiagnostics.event("thread_pool_setup", "id=" + id + " end wall_ns="
                + Math.max(0, elapsed) + " completed=" + completed);
    }

    public static long overallStart(long id)
    {
        return id == 0 ? 0 : System.nanoTime();
    }
}
