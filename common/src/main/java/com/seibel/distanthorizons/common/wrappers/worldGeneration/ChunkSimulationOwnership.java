package com.seibel.distanthorizons.common.wrappers.worldGeneration;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Tracks this generator's successful simulation suppressions separately from
 * chunk retention. All operations are confined to the world's server thread.
 */
final class ChunkSimulationOwnership<P>
{
    private final Set<P> suppressedPositions = new HashSet<>();

    void preventIfEnabled(P position, BooleanSupplier enabled, Runnable prevent)
    {
        if (this.suppressedPositions.contains(position) || !enabled.getAsBoolean())
        {
            return;
        }
        prevent.run();
        this.suppressedPositions.add(position);
    }

    void allowIfOwned(P position, Runnable allow)
    {
        if (this.suppressedPositions.contains(position))
        {
            allow.run();
            this.suppressedPositions.remove(position);
        }
    }

    void releaseAll(Consumer<P> allow)
    {
        ArrayList<Runnable> steps = new ArrayList<>();
        for (P position : new ArrayList<>(this.suppressedPositions))
            steps.add(() -> this.allowIfOwned(position, () -> allow.accept(position)));
        GenerationChunkLease.runCleanupSteps(steps.toArray(new Runnable[0]));
    }
}
