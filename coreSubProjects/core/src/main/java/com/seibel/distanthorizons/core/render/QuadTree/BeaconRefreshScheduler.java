// SPDX-License-Identifier: LGPL-3.0-only
package com.seibel.distanthorizons.core.render.QuadTree;

import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Per-tree, tick-driven fixed delay. No timer thread and no sleeping IO worker. */
final class BeaconRefreshScheduler
{
    static final long REFRESH_DELAY_NANOS = TimeUnit.SECONDS.toNanos(2);
    private final LongSupplier nanoTime;
    private final BooleanSupplier ownerClosing;
    private final Consumer<DhBlockPos2D> refresh;
    // All mutable state below is guarded by this; work never runs under this monitor.
    private Submission pending;
    private boolean coolingDown;
    private long lastFinishedNanos;

    BeaconRefreshScheduler(BooleanSupplier ownerClosing, Consumer<DhBlockPos2D> refresh)
    { this(System::nanoTime, ownerClosing, refresh); }

    BeaconRefreshScheduler(LongSupplier nanoTime, BooleanSupplier ownerClosing, Consumer<DhBlockPos2D> refresh)
    {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.ownerClosing = Objects.requireNonNull(ownerClosing, "ownerClosing");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
    }

    boolean trySubmit(ExecutorService executor, DhBlockPos2D playerPos)
    {
        if (executor == null || executor.isShutdown() || this.ownerClosing.getAsBoolean()) { return false; }
        final Submission submission;
        synchronized (this)
        {
            if (this.ownerClosing.getAsBoolean()) { return false; }
            // Picker shutdown can abandon queued work. Revoke only a task that never started.
            if (this.pending != null && !this.pending.started && this.pending.executor.isShutdown())
            { this.pending = null; }
            if (this.pending != null) { return false; }
            // Subtraction supports nanoTime's arbitrary origin and signed wraparound.
            if (this.coolingDown && this.nanoTime.getAsLong() - this.lastFinishedNanos < REFRESH_DELAY_NANOS)
            { return false; }
            submission = new Submission(executor, playerPos);
            this.pending = submission;
        }
        try { executor.execute(submission); return true; }
        catch (RejectedExecutionException rejected)
        {
            if (this.cancelBeforeStart(submission)) { return false; }
            throw rejected; // Do not swallow an exception thrown by inline work.
        }
        catch (RuntimeException | Error failure)
        { this.cancelBeforeStart(submission); throw failure; }
    }

    private synchronized boolean cancelBeforeStart(Submission submission)
    {
        if (submission.started) { return false; }
        if (this.pending == submission) { this.pending = null; }
        return true;
    }

    private final class Submission implements Runnable
    {
        private final ExecutorService executor;
        private final DhBlockPos2D playerPos;
        private boolean started;

        Submission(ExecutorService executor, DhBlockPos2D playerPos)
        { this.executor = executor; this.playerPos = playerPos; }

        @Override public void run()
        {
            synchronized (BeaconRefreshScheduler.this)
            {
                // Revoked stale tasks cannot run, or release a newer task's reservation.
                if (pending != this || this.started) { return; }
                this.started = true;
            }
            try
            {
                if (!ownerClosing.getAsBoolean()) { refresh.accept(this.playerPos); }
            }
            finally
            {
                synchronized (BeaconRefreshScheduler.this)
                {
                    lastFinishedNanos = nanoTime.getAsLong();
                    coolingDown = true;
                    pending = null;
                }
            }
        }
    }
}
