/* Akashic diagnostic instrumentation; LGPL-3.0, like the surrounding DH core. */
package com.seibel.distanthorizons.core.logging.mirai;

import com.seibel.distanthorizons.core.logging.mirai.MiraiGpuQueryRing.Stage;

/** Optional, bounded GPU observations. Commands never invoke GL; only render hooks do. */
public final class MiraiGpuProfiler
{
    public interface ContextBackend extends MiraiGpuQueryRing.Backend
    {
        boolean isCurrentContext();
        int counterBits();
    }
    public interface BackendFactory { ContextBackend createIfSupported(); }
    private static volatile BackendFactory factory;
    private static volatile long untilNanos;
    private static volatile String status = "off";
    // Render-thread confined. No native resource is held by the diagnostic writer.
    private static ContextBackend backend;
    private static MiraiGpuQueryRing ring;
    private static boolean passInvalid;
    private static long captureId;
    private static final Stage[] STAGES = Stage.values();
    private MiraiGpuProfiler() { }

    public static void installBackendFactory(BackendFactory value) { factory = value; }
    public static String status() { return status; }
    public static String request(int seconds)
    {
        if (seconds < 10 || seconds > 120) { return "GPU duration: 10-120 seconds."; }
        untilNanos = System.nanoTime() + seconds * 1_000_000_000L;
        status = "requested";
        MiraiDiagnostics.event("gpu_capture", "requested seconds=" + seconds + "; every fourth invocation per main/deferred pass; optional measurement has overhead");
        return "Mirai GPU capture requested for " + seconds + " seconds; status reports availability on the next render pass.";
    }
    public static void stop()
    {
        untilNanos = 0;
        status = "off";
    }
    private static boolean requested()
    { long end = untilNanos; return MiraiDiagnostics.enabled() && end != 0 && System.nanoTime() - end < 0; }

    /** Call on the render thread even in menus, so expiration releases pending query objects. */
    public static void maintenance()
    {
        if (ring == null)
        {
            if (untilNanos != 0 && !requested()) { untilNanos = 0; status = "off"; }
            return;
        }
        try
        {
            if (!backend.isCurrentContext())
            {
                // Query names are context-local. Never delete these names in a different context.
                ring = null; backend = null; untilNanos = 0; status = "context_changed";
                MiraiDiagnostics.event("gpu_capture", "context_changed; old query names abandoned, reclaimed when original context is destroyed");
            }
            else if (!requested())
            {
                closeCurrent(); untilNanos = 0; status = "off";
                MiraiDiagnostics.event("gpu_capture", "finished; unread pending samples discarded; baseline logging continues");
            }
        }
        catch (RuntimeException | LinkageError failure) { failed(failure); }
    }

    public static boolean beginPass(boolean deferred)
    {
        if (!requested()) { maintenance(); return false; }
        long started = System.nanoTime();
        try
        {
            maintenance();
            if (!requested()) { return false; }
            if (ring == null)
            {
                BackendFactory provider = factory;
                if (provider == null) { status = "waiting_for_renderer"; return false; }
                backend = provider.createIfSupported();
                if (backend == null || backend.counterBits() != 64)
                {
                    backend = null; untilNanos = 0; status = "unsupported";
                    MiraiDiagnostics.event("gpu_capture", "unsupported; requires timer queries and 64 timestamp counter bits; CPU wall telemetry continues");
                    return false;
                }
                ring = new MiraiGpuQueryRing(backend, new MiraiGpuQueryRing.Sink()
                {
                    @Override public void sample(boolean deferred, long sequence, long origin, long cpu, long delay, long[] nanos)
                    { publish(deferred, sequence, origin, cpu, delay, nanos); }
                    @Override public void dropped(String reason)
                    { MiraiDiagnostics.count(MiraiDiagnostics.Counter.GPU_SAMPLES_DROPPED); }
                });
                captureId++;
                status = "recording";
                MiraiDiagnostics.event("gpu_capture", "recording; GL_TIMESTAMP pairs; counter_bits=64; ring_slots=6; sample_every=4 per pass kind; no full-frame GPU scope");
            }
            status = "recording";
            passInvalid = false;
            return ring.beginPass(deferred);
        }
        catch (RuntimeException | LinkageError failure) { failed(failure); return false; }
        finally { MiraiDiagnostics.duration(MiraiDiagnostics.Metric.GPU_OBSERVER_WALL, System.nanoTime() - started); }
    }
    public static int beginStage(Stage stage)
    {
        if (ring == null) { return -1; }
        long started = System.nanoTime();
        try { return ring.beginStage(stage); }
        catch (RuntimeException | LinkageError failure) { failed(failure); return -1; }
        finally { MiraiDiagnostics.duration(MiraiDiagnostics.Metric.GPU_OBSERVER_WALL, System.nanoTime() - started); }
    }
    public static void endStage(int token)
    {
        if (token < 0 || ring == null) { return; }
        long started = System.nanoTime();
        try { ring.endStage(token); }
        catch (RuntimeException | LinkageError failure) { failed(failure); }
        finally { MiraiDiagnostics.duration(MiraiDiagnostics.Metric.GPU_OBSERVER_WALL, System.nanoTime() - started); }
    }
    public static void endPass(boolean completed)
    {
        if (ring == null) { return; }
        long started = System.nanoTime();
        try { ring.endPass(completed && !passInvalid); }
        catch (RuntimeException | LinkageError failure) { failed(failure); }
        finally { MiraiDiagnostics.duration(MiraiDiagnostics.Metric.GPU_OBSERVER_WALL, System.nanoTime() - started); }
    }
    /** The renderer may catch its own error; such a partial pass is not a successful sample. */
    public static void invalidatePass() { passInvalid = true; }
    private static void publish(boolean deferred, long sequence, long origin, long cpu, long delay, long[] nanos)
    {
        MiraiDiagnostics.count(MiraiDiagnostics.Counter.GPU_SAMPLES);
        MiraiDiagnostics.duration(deferred ? MiraiDiagnostics.Metric.GPU_DEFERRED_PASS : MiraiDiagnostics.Metric.GPU_MAIN_PASS, nanos[0]);
        MiraiDiagnostics.duration(MiraiDiagnostics.Metric.GPU_SAMPLED_PASS_WALL, cpu);
        StringBuilder text = new StringBuilder("pass=").append(deferred ? "deferred" : "main")
                .append(" capture=").append(captureId)
                .append(" sequence=").append(sequence)
                .append(" capture_start_elapsed_ms=").append(MiraiDiagnostics.elapsedMillis(origin))
                .append(" readback_delay_ns=").append(delay)
                .append(" lod_stage=").append(nanos[Stage.OPAQUE.ordinal()] >= 0 || nanos[Stage.TRANSPARENT.ordinal()] >= 0)
                .append(" cpu_wall_ns=").append(cpu).append(" gpu_completion_ns=");
        for (int i = 0; i < STAGES.length; i++)
        { if (nanos[i] >= 0) { text.append(STAGES[i]).append(':').append(nanos[i]).append(','); } }
        MiraiDiagnostics.event("gpu_sample", text.toString());
    }
    private static void failed(Throwable failure)
    {
        untilNanos = 0; status = "failed:" + failure.getClass().getSimpleName();
        MiraiDiagnostics.count(MiraiDiagnostics.Counter.GPU_QUERY_FAILURES);
        MiraiDiagnostics.event("gpu_capture", status + "; optional GPU observer disabled; renderer continues");
        try { closeCurrent(); } catch (RuntimeException | LinkageError ignored) { ring = null; backend = null; }
    }
    private static void closeCurrent()
    {
        MiraiGpuQueryRing previous = ring; ContextBackend owner = backend;
        ring = null; backend = null;
        if (previous != null && owner != null && owner.isCurrentContext()) { previous.close(); }
    }
}
