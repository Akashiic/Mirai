package com.seibel.distanthorizons.core.logging.mirai;

/** Fixed histogram: no per-frame allocations; reports bucket upper bounds, not exact quantiles. */
final class MiraiTiming
{
    static final long[] LIMIT_MS = {1, 2, 4, 8, 16, 25, 33, 50, 100, 250, 500, 1000, Long.MAX_VALUE};
    private final long[] buckets = new long[LIMIT_MS.length];
    private long count, total, max;
    synchronized void record(long nanos)
    {
        count++; total += nanos; max = Math.max(max, nanos);
        int i = 0;
        while (i < LIMIT_MS.length - 1 && nanos > LIMIT_MS[i] * 1_000_000L) { i++; }
        buckets[i]++;
    }
    synchronized long[] drain()
    {
        long[] result = new long[3 + buckets.length];
        result[0] = count; result[1] = total; result[2] = max;
        System.arraycopy(buckets, 0, result, 3, buckets.length);
        count = total = max = 0;
        java.util.Arrays.fill(buckets, 0);
        return result;
    }
    static String describe(long[] values)
    {
        long accumulated = 0;
        String p95 = "unavailable";
        for (int i = 0; i < LIMIT_MS.length && values[0] > 0; i++)
        {
            accumulated += values[i + 3];
            if (accumulated >= (values[0] * 95 + 99) / 100)
            { p95 = i == LIMIT_MS.length - 1 ? ">1000" : "<=" + LIMIT_MS[i]; break; }
        }
        return "count=" + values[0] + " mean_us=" + (values[0] == 0 ? 0 : values[1] / values[0] / 1000)
                + " max_us=" + values[2] / 1000 + " p95_bucket_ms=" + p95;
    }
}
