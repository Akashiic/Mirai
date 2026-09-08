package com.seibel.distanthorizons.common.wrappers.worldGeneration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * One request's exact chunk-reference ownership. All methods must run on the
 * server thread; publication to that thread is handled by the task queue.
 * Closing before a deferred acquisition runs prevents that acquisition from
 * retaining anything after its generation event has already finished.
 */
final class GenerationChunkLease<P>
{
    private final List<P> acquiredPositions = new ArrayList<>(9);
    private boolean closed;

    void requireOpen()
    {
        if (this.closed)
        {
            throw new CancellationException("Chunk request was released before acquisition completed.");
        }
    }

    /** Call immediately after, and only after, a reference was incremented. */
    void recordAcquired(P position)
    {
        this.requireOpen();
        this.acquiredPositions.add(position);
    }

    /**
     * Idempotently consumes this lease. A failing release must not prevent the
     * other references owned by this request from being released.
     */
    void close(Consumer<P> release)
    {
        if (this.closed)
        {
            return;
        }
        this.closed = true;
        Throwable failure = null;
        for (P position : this.acquiredPositions)
        {
            try
            {
                release.accept(position);
            }
            catch (RuntimeException | Error e)
            {
                if (failure == null)
                {
                    failure = e;
                }
                else if (failure != e)
                {
                    failure.addSuppressed(e);
                }
            }
        }
        this.acquiredPositions.clear();
        rethrow(failure);
    }

    /** A failing ticket hook must not skip simulation/ignore-state cleanup. */
    static void runCleanupSteps(Runnable... steps)
    {
        Throwable failure = null;
        for (Runnable step : steps)
        {
            try
            {
                step.run();
            }
            catch (RuntimeException | Error e)
            {
                if (failure == null)
                {
                    failure = e;
                }
                else if (failure != e)
                {
                    failure.addSuppressed(e);
                }
            }
        }
        rethrow(failure);
    }

    private static void rethrow(Throwable failure)
    {
        if (failure instanceof Error)
        {
            throw (Error) failure;
        }
        if (failure != null)
        {
            throw (RuntimeException) failure;
        }
    }
}
