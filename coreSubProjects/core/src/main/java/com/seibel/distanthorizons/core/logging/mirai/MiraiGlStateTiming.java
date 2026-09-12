package com.seibel.distanthorizons.core.logging.mirai;

/**
 * Fine-grained, trace-only wall timing for OpenGL state snapshots/restores.
 * Recording performs no allocation and never calls OpenGL.
 */
public final class MiraiGlStateTiming
{
    public enum Scope
    {
        MAIN_PASS, SSAO, TAA, FOG, FAR_FADE, VANILLA_FADE,
        APPLY, COPY, BLOCK_TEXTURE_ATLAS, UNSCOPED
    }

    private static final long[] LIMIT_US = {
            25, 50, 100, 250, 500, 1_000, 2_000, 4_000,
            8_000, 16_000, 33_000, 50_000, 100_000, 250_000,
            500_000, 1_000_000, Long.MAX_VALUE
    };
    private static final FineTiming[] SAVE = createTimings();
    private static final FineTiming[] RESTORE = createTimings();

    private MiraiGlStateTiming() { }

    public static long start() { return MiraiDiagnostics.traceTimingStart(); }
    public static void finishSave(Scope scope, long start)
    {
        finish(SAVE, scope, start);
    }

    public static void finishRestore(Scope scope, long start)
    {
        finish(RESTORE, scope, start);
    }

    private static void finish(FineTiming[] timings, Scope scope, long start)
    {
        if (start == 0) { return; }
        long elapsed = System.nanoTime() - start;
        if (elapsed < 0) { return; }
        timings[normalize(scope).ordinal()].record(elapsed);
    }

    static void recordForTest(Scope scope, boolean restore, long nanos)
    {
        (restore ? RESTORE : SAVE)[normalize(scope).ordinal()].record(nanos);
    }

    static String drainDescription()
    {
        StringBuilder out = null;
        for (Scope scope : Scope.values())
        {
            long[] save = SAVE[scope.ordinal()].drain();
            long[] restore = RESTORE[scope.ordinal()].drain();
            if (save[0] == 0 && restore[0] == 0) { continue; }
            if (out == null) { out = new StringBuilder("trace_only=true"); }
            out.append(' ').append(scope).append(" save{")
                    .append(describe(save)).append("} restore{")
                    .append(describe(restore)).append('}');
        }
        return out == null ? null : out.toString();
    }

    private static Scope normalize(Scope scope)
    {
        return scope == null ? Scope.UNSCOPED : scope;
    }

    private static FineTiming[] createTimings()
    {
        FineTiming[] timings = new FineTiming[Scope.values().length];
        for (int i = 0; i < timings.length; i++) { timings[i] = new FineTiming(); }
        return timings;
    }

    private static String describe(long[] values)
    {
        if (values[0] == 0) { return "count=0"; }
        long accumulated = 0;
        long p95 = Long.MAX_VALUE;
        for (int i = 0; i < LIMIT_US.length; i++)
        {
            accumulated += values[i + 3];
            if (accumulated >= (values[0] * 95 + 99) / 100) { p95 = LIMIT_US[i]; break; }
        }
        return "count=" + values[0] + " mean_us=" + (values[1] / values[0] / 1_000)
                + " max_us=" + (values[2] / 1_000)
                + " p95_us=" + (p95 == Long.MAX_VALUE ? ">1000000" : "<=" + p95);
    }

    private static final class FineTiming
    {
        private final long[] buckets = new long[LIMIT_US.length];
        private long count, total, max;

        synchronized void record(long nanos)
        {
            if (nanos < 0) { return; }
            count++;
            total += nanos;
            max = Math.max(max, nanos);
            int index = 0;
            while (index < LIMIT_US.length - 1 && nanos > LIMIT_US[index] * 1_000L) { index++; }
            buckets[index]++;
        }

        synchronized long[] drain()
        {
            long[] result = new long[3 + buckets.length];
            result[0] = count;
            result[1] = total;
            result[2] = max;
            System.arraycopy(buckets, 0, result, 3, buckets.length);
            count = total = max = 0;
            java.util.Arrays.fill(buckets, 0);
            return result;
        }
    }
}
