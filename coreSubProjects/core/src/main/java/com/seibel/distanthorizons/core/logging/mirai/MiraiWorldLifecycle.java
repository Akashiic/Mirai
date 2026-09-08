/* Akashic diagnostic instrumentation; LGPL-3.0, like the surrounding DH core. */
package com.seibel.distanthorizons.core.logging.mirai;

import java.util.concurrent.atomic.AtomicLong;

/** Rare world-transition observations. No world identifiers, GL work or disk IO on the caller. */
public final class MiraiWorldLifecycle
{
    public enum Phase {
        OLD_WORLD_CLOSE, REJECTED_WORLD_CLOSE, THREAD_POOLS_SETUP, THREAD_POOLS_SHUTDOWN,
        DATABASE_CLOSE, TEXTURES_CLEAR, EXPLICIT_GC, WORLD_LOAD_EVENT, WORLD_UNLOAD_EVENT
    }
    private static final AtomicLong sequence = new AtomicLong();
    private MiraiWorldLifecycle() { }

    /** Preserves the action's thread, order and exception. Durations are wall time, never GPU time. */
    public static void run(Phase phase, Runnable action)
    {
        if (!MiraiDiagnostics.enabled()) { action.run(); return; }
        long id = sequence.incrementAndGet();
        emit("id=" + id + " phase=" + phase + " state=begin");
        long start = System.nanoTime();
        boolean completed = false;
        try
        {
            action.run();
            completed = true;
        }
        finally
        {
            long wall = System.nanoTime() - start;
            emit("id=" + id + " phase=" + phase + " state=end completed=" + completed + " wall_ns=" + wall);
        }
    }

    private static void emit(String detail)
    {
        // Diagnostic admission failure must not skip cleanup or replace its exception.
        // The existing bounded queue accounts for drops; no caller waits for the writer.
        try { MiraiDiagnostics.event("world_lifecycle", detail); }
        catch (RuntimeException unavailable) { /* The underlying operation remains authoritative. */ }
    }
}
