package com.seibel.distanthorizons.common.wrappers.worldGeneration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Standalone ownership/scheduling regressions; does not simulate Minecraft. */
public final class GenerationChunkLeaseRegressionTest
{
    private static int assertions;

    public static void main(String[] args)
    {
        partialAcquisitionPreservesOtherRequest();
        essentialCleanupCanOvertakeDeferredAcquisition();
        failureReleasesEveryOwnedPositionExactlyOnce();
        hookFailureDoesNotSkipOtherCleanupSteps();
        duplicateCleanupDoesNotConsumeAReacquiredReference();
        failedAcquisitionPreservesExternalSimulationSuppression();
        System.out.println("GenerationChunkLease regressions passed: " + assertions + " assertions.");
    }

    private static void partialAcquisitionPreservesOtherRequest()
    {
        List<String> firstNeighborhood = neighborhood(0, 0);
        List<String> secondNeighborhood = neighborhood(1, 0);
        // Includes failure before the first acquisition, at every neighbor,
        // and after all nine increments but before the target load returns.
        for (int acquired = 0; acquired <= 9; acquired++)
        {
            Map<String, Integer> refs = new HashMap<>();
            GenerationChunkLease<String> first = new GenerationChunkLease<>();
            GenerationChunkLease<String> second = new GenerationChunkLease<>();
            for (String position : secondNeighborhood)
            {
                acquire(second, refs, position);
            }
            Map<String, Integer> secondOnly = new HashMap<>(refs);
            for (String position : firstNeighborhood.subList(0, acquired))
            {
                acquire(first, refs, position);
            }
            first.close(position -> release(refs, position));
            check(refs.equals(secondOnly), "Partial rollback consumed another request at prefix " + acquired);
            first.close(position -> release(refs, position));
            check(refs.equals(secondOnly), "Repeated rollback changed ownership");
            second.close(position -> release(refs, position));
            check(refs.isEmpty(), "References remained after both owners completed");
        }
    }

    private static void essentialCleanupCanOvertakeDeferredAcquisition()
    {
        GenerationChunkLease<String> lease = new GenerationChunkLease<>();
        Map<String, Integer> refs = new HashMap<>();
        ArrayDeque<Runnable> deferred = new ArrayDeque<>();
        ArrayDeque<Runnable> essential = new ArrayDeque<>();
        deferred.add(() -> acquire(lease, refs, "late"));
        essential.add(() -> lease.close(position -> release(refs, position)));
        essential.remove().run();
        boolean cancelled = false;
        try
        {
            deferred.remove().run();
        }
        catch (CancellationException expected)
        {
            cancelled = true;
        }
        check(cancelled, "A closed queued request was allowed to run");
        check(refs.isEmpty(), "Deferred request acquired after cleanup completed");
    }

    private static void failureReleasesEveryOwnedPositionExactlyOnce()
    {
        GenerationChunkLease<String> lease = new GenerationChunkLease<>();
        List<String> attempts = new ArrayList<>();
        for (String position : Arrays.asList("one", "two", "three"))
        {
            lease.recordAcquired(position);
        }
        IllegalStateException first = new IllegalStateException("ticket hook failed");
        IllegalArgumentException second = new IllegalArgumentException("another hook failed");
        try
        {
            lease.close(position ->
            {
                attempts.add(position);
                if (position.equals("one")) throw first;
                if (position.equals("two")) throw second;
            });
            throw new AssertionError("Cleanup failure was hidden");
        }
        catch (IllegalStateException observed)
        {
            check(observed == first, "Original cleanup failure was replaced");
            check(observed.getSuppressed().length == 1 && observed.getSuppressed()[0] == second,
                    "Secondary cleanup failure was lost");
        }
        check(attempts.equals(Arrays.asList("one", "two", "three")), "Failure skipped an owned position");
        lease.close(attempts::add);
        check(attempts.size() == 3, "Cleanup retried already consumed references");
    }

    private static void hookFailureDoesNotSkipOtherCleanupSteps()
    {
        List<String> attempts = new ArrayList<>();
        LinkageError original = new LinkageError("incompatible optional hook");
        try
        {
            GenerationChunkLease.runCleanupSteps(
                    () -> { attempts.add("unforce"); throw original; },
                    () -> attempts.add("simulation"),
                    () -> attempts.add("unload"),
                    () -> attempts.add("ignore"));
            throw new AssertionError("Linkage failure was hidden");
        }
        catch (LinkageError observed)
        {
            check(observed == original, "Linkage error identity was lost");
        }
        check(attempts.equals(Arrays.asList("unforce", "simulation", "unload", "ignore")),
                "A failed cleanup stage skipped subsequent stages");
    }

    private static void duplicateCleanupDoesNotConsumeAReacquiredReference()
    {
        Map<String, Integer> refs = new HashMap<>();
        GenerationChunkLease<String> oldRequest = new GenerationChunkLease<>();
        acquire(oldRequest, refs, "shared");
        oldRequest.close(position -> release(refs, position));
        GenerationChunkLease<String> newRequest = new GenerationChunkLease<>();
        acquire(newRequest, refs, "shared");
        oldRequest.close(position -> release(refs, position));
        check(refs.get("shared") == 1, "Stale cleanup consumed a newly acquired reference");
        newRequest.close(position -> release(refs, position));
        check(refs.isEmpty(), "New request did not release its own reference");
    }

    private static void failedAcquisitionPreservesExternalSimulationSuppression()
    {
        ChunkSimulationOwnership<String> simulation = new ChunkSimulationOwnership<>();
        int[] externalCount = {1};
        // The target retention exists, but a neighbor load fails before the
        // target's simulation-suppression hook was reached.
        simulation.allowIfOwned("target", () -> externalCount[0]--);
        check(externalCount[0] == 1, "Rollback consumed another mod's target suppression");

        simulation.preventIfEnabled("target", () -> false, () -> externalCount[0]++);
        simulation.allowIfOwned("target", () -> externalCount[0]--);
        check(externalCount[0] == 1, "Inactive capability created false suppression ownership");

        try
        {
            simulation.preventIfEnabled("target", () -> true,
                    () -> { throw new IllegalStateException("prevention failed before acquisition"); });
            throw new AssertionError("Failed prevention was hidden");
        }
        catch (IllegalStateException expected) { }
        simulation.allowIfOwned("target", () -> externalCount[0]--);
        check(externalCount[0] == 1, "Failed prevention released an external suppression");

        simulation.preventIfEnabled("target", () -> true, () -> externalCount[0]++);
        simulation.preventIfEnabled("target", () -> true, () -> externalCount[0]++);
        check(externalCount[0] == 2, "Generator acquired the same suppression twice");
        simulation.allowIfOwned("target", () -> externalCount[0]--);
        simulation.allowIfOwned("target", () -> externalCount[0]--);
        check(externalCount[0] == 1, "Duplicate cleanup consumed external suppression");
    }

    private static void acquire(GenerationChunkLease<String> lease, Map<String, Integer> refs, String position)
    {
        lease.requireOpen();
        refs.put(position, refs.getOrDefault(position, 0) + 1);
        lease.recordAcquired(position);
    }

    private static void release(Map<String, Integer> refs, String position)
    {
        Integer count = refs.get(position);
        if (count == null) throw new AssertionError("Release without ownership: " + position);
        if (count == 1) refs.remove(position);
        else refs.put(position, count - 1);
    }

    private static List<String> neighborhood(int centerX, int centerZ)
    {
        List<String> positions = new ArrayList<>();
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++)
                positions.add((centerX + x) + "," + (centerZ + z));
        return positions;
    }

    private static void check(boolean value, String message)
    {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
