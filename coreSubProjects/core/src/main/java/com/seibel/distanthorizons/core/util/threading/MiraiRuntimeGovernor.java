package com.seibel.distanthorizons.core.util.threading;

import com.seibel.distanthorizons.core.config.Config;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Client-side runtime guardrail for background DH concurrency.
 * It never changes persistent config or visual quality; when disabled, the
 * effective limit is exactly the configured thread count.
 */
public final class MiraiRuntimeGovernor
{
    static final long SAMPLE_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final int RECOVERY_SAMPLES = 8;
    // like every akashic.mirai.* rollback switch, only an explicit "false" turns it off
    private static final boolean ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("akashic.mirai.runtimeGovernor", "true").trim());
    private static final Runtime RUNTIME = Runtime.getRuntime();
    private static final List<GarbageCollectorMXBean> GC_BEANS = ManagementFactory.getGarbageCollectorMXBeans();

    private static volatile int effectiveLimit = -1;
    private static volatile double heapRatio;
    private static volatile double gcDutyRatio;
    private static volatile String reason = "warming";

    private static long previousFrameNanos;
    private static boolean previousGameplayFocused;
    private static long lastSampleNanos;
    private static long lastGcMillis = -1L;
    private static int slowFrames;
    private static int severeFrames;
    private static int healthySamples;
    private static int lastConfigured = -1;
    /** focused frame time accumulated over the current sample window */
    private static long focusedFrameNanos;
    private static int focusedFrames;
    private static final FrameRegressionDetector FRAME_REGRESSION = new FrameRegressionDetector();

    private MiraiRuntimeGovernor() { }

    /** Called from the physical 1.7.10 client render owner. Returns true when the effective limit changes. */
    public static boolean onClientFrame(boolean gameplayFocused) { return onClientFrame(gameplayFocused, -1); }
    /** @param occupiedWorkers DH workers busy right now, or -1 if unknown (then saturation is never assumed) */
    public static boolean onClientFrame(boolean gameplayFocused, int occupiedWorkers)
    {
        long now = System.nanoTime();
        synchronized (MiraiRuntimeGovernor.class)
        {
            if (previousFrameNanos != 0L && gameplayFocused && previousGameplayFocused)
            {
                long frameNanos = now - previousFrameNanos;
                if (frameNanos >= TimeUnit.MILLISECONDS.toNanos(100)) { slowFrames++; }
                if (frameNanos >= TimeUnit.MILLISECONDS.toNanos(250)) { severeFrames++; }
                focusedFrameNanos += frameNanos;
                focusedFrames++;
            }
            previousFrameNanos = now;
            previousGameplayFocused = gameplayFocused;
            if (lastSampleNanos != 0L && now - lastSampleNanos < SAMPLE_NANOS) { return false; }
            return sample(now, gameplayFocused, occupiedWorkers);
        }
    }

    private static boolean sample(long now, boolean gameplayFocused, int occupiedWorkers)
    {
        int configured = Math.max(1, Config.Common.MultiThreading.numberOfThreads.get());
        if (effectiveLimit < 1 || configured != lastConfigured)
        {
            effectiveLimit = configured;
            lastConfigured = configured;
            healthySamples = 0;
        }
        if (!ENABLED)
        {
            boolean changed = effectiveLimit != configured;
            effectiveLimit = configured;
            reason = "disabled";
            resetWindow(now);
            return changed;
        }

        long used = RUNTIME.totalMemory() - RUNTIME.freeMemory();
        long maximum = Math.max(1L, RUNTIME.maxMemory());
        heapRatio = Math.min(1.0, Math.max(0.0, (double) used / (double) maximum));
        long gcMillis = readGcMillis();
        long elapsedMillis = lastSampleNanos == 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(now - lastSampleNanos);
        gcDutyRatio = lastGcMillis < 0L || elapsedMillis <= 0L ? 0.0
                : Math.min(1.0, Math.max(0.0, (double) (gcMillis - lastGcMillis) / (double) elapsedMillis));
        lastGcMillis = gcMillis;

        Pressure pressure = classifyPressure(configured, heapRatio, gcDutyRatio,
                gameplayFocused ? slowFrames : 0, gameplayFocused ? severeFrames : 0);
        int before = effectiveLimit;
        double meanFrameMs = focusedFrames == 0 ? 0.0 : focusedFrameNanos / (double) focusedFrames / 1_000_000.0;
        boolean saturated = occupiedWorkers > 0 && occupiedWorkers >= effectiveLimit;
        boolean frameRegression = FRAME_REGRESSION.sample(meanFrameMs, saturated, gameplayFocused);
        if (pressure.limit < effectiveLimit)
        {
            effectiveLimit = pressure.limit;
            healthySamples = 0;
        }
        else if (pressure.limit > effectiveLimit)
        {
            boolean healthy = heapRatio < 0.75 && gcDutyRatio < 0.05
                    && (!gameplayFocused || (slowFrames == 0 && severeFrames == 0))
                    && FRAME_REGRESSION.withinRecoveryBand();
            if (healthy && ++healthySamples >= RECOVERY_SAMPLES)
            {
                effectiveLimit = Math.min(pressure.limit, effectiveLimit + 1);
                healthySamples = 0;
            }
            else if (!healthy) { healthySamples = 0; }
        }
        else { healthySamples = 0; }
        reason = pressure.reason;
        if (frameRegression && effectiveLimit == before)
        {
            // saturated workers are doubling the frame time: give the client thread one core back, but never
            // below half of the configured workers, so LOD loading slows down instead of stopping
            int floor = Math.max(1, (configured + 1) / 2);
            if (effectiveLimit > floor)
            {
                effectiveLimit--;
                healthySamples = 0;
                reason = "frame_regression";
            }
        }
        resetWindow(now);
        return before != effectiveLimit;
    }

    static Pressure classifyPressure(int configured, double heap, double gcDuty, int slow, int severe)
    {
        if (heap >= 0.93 || gcDuty >= 0.50 || severe >= 2)
            return new Pressure(1, "critical");
        if (heap >= 0.88 || gcDuty >= 0.25 || severe >= 1)
            return new Pressure(Math.max(1, (configured + 1) / 2), "high");
        if (heap >= 0.80 || gcDuty >= 0.10 || slow >= 3)
            return new Pressure(Math.max(1, configured - 1), "elevated");
        return new Pressure(configured, "normal");
    }

    public static int getEffectiveThreadLimit(int configured)
    {
        if (!ENABLED) { return Math.max(1, configured); }
        int current = effectiveLimit;
        return current < 1 ? Math.max(1, configured) : Math.max(1, Math.min(configured, current));
    }

    public static String getDiagnosticString()
    {
        int configured = Math.max(1, Config.Common.MultiThreading.numberOfThreads.get());
        return String.format(Locale.ROOT,
                "enabled=%s configured=%d effective=%d heap_pct=%.1f gc_duty_pct=%.1f reason=%s recovery=%d/%d frame_ms=%.1f frame_baseline_ms=%.1f",
                ENABLED, configured, getEffectiveThreadLimit(configured), heapRatio * 100.0,
                gcDutyRatio * 100.0, reason, healthySamples, RECOVERY_SAMPLES,
                FRAME_REGRESSION.getLastMeanMs(), FRAME_REGRESSION.getBaselineMs());
    }

    private static void resetWindow(long now)
    {
        lastSampleNanos = now;
        slowFrames = 0;
        severeFrames = 0;
        focusedFrameNanos = 0L;
        focusedFrames = 0;
    }

    private static long readGcMillis()
    {
        long total = 0L;
        for (GarbageCollectorMXBean bean : GC_BEANS)
        {
            long value = bean.getCollectionTime();
            if (value > 0L) { total += value; }
        }
        return total;
    }

    static final class Pressure
    {
        final int limit;
        final String reason;
        Pressure(int limit, String reason)
        {
            this.limit = limit;
            this.reason = reason;
        }
    }
}
