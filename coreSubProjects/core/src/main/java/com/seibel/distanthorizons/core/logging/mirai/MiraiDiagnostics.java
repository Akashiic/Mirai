/* Akashic diagnostic instrumentation; LGPL-3.0, like the surrounding DH core. */
package com.seibel.distanthorizons.core.logging.mirai;

import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-local observations. Never uploads data or changes renderer/world configuration. */
public final class MiraiDiagnostics
{
    public enum Counter {
        COLD_REQUEST, REFRESH_REQUEST, COLD_MISSING, REFRESH_UNCHANGED, REMOTE_FAILED,
        NETWORK_SENT, NETWORK_RECEIVED, NETWORK_FRAGMENT_BYTES, NETWORK_RATE_LIMITED,
        NETWORK_REJECTED, NETWORK_OUT_OF_RANGE, NETWORK_WRONG_POSITION, NETWORK_CANCELLED, NETWORK_QUEUE_FULL, NETWORK_QUEUE_CLOSED,
        COLD_PRESERVED_LOCAL, DB_COMMITTED, DB_FAILED, DB_CANCELLED, CHUNK_NOT_READY, CHUNK_SUBMITTED, DH_ERROR_REPORTS, SYNC_DEFERRED_OVERFLOW,
        GL_STORAGE_UPLOAD_BYTES, GL_DATA_UPLOAD_BYTES, GL_SUB_DATA_UPLOAD_BYTES, GL_UPLOAD_JAVA_FAILURES,
        GPU_SAMPLES, GPU_SAMPLES_DROPPED, GPU_QUERY_FAILURES,
        QUIESCENT_CHUNK_DEFERRED, QUIESCENT_REMOTE_SYNC_SUPPRESSED, QUIESCENT_PARTIAL_UPDATE_SUPPRESSED, QUIESCENT_CATCHUP_SUBMITTED
    }
    public enum Metric { FRAME_INTERVAL, FRAME_EVENT_SPAN, SERVER_TICK_EVENT_SPAN, LOD_RENDER_WALL, LOD_UPDATE_WALL, NETWORK_REQUEST_WALL,
        CLIENT_RENDER_PASS_WALL, CLIENT_DEFERRED_PASS_WALL, RENDER_TASK_QUEUE_WALL, RENDER_TASK_WALL,
        GL_STORAGE_UPLOAD_WALL, GL_DATA_UPLOAD_WALL, GL_SUB_DATA_UPLOAD_WALL,
        GPU_MAIN_PASS, GPU_DEFERRED_PASS, GPU_SAMPLED_PASS_WALL, GPU_OBSERVER_WALL,
        LOD_RENDER_LIST_WALL,
        LOD_UPDATE_LOCK_WAIT, LOD_UPDATE_EXISTS_CHECK, LOD_UPDATE_LOAD, LOD_UPDATE_MERGE,
        LOD_UPDATE_SKYLIGHT, LOD_UPDATE_ENCODE, LOD_UPDATE_DB_SAVE, LOD_UPDATE_NOTIFY }
    public enum ErrorKind { RENDER, DATABASE, NETWORK, SNAPSHOT, INITIALIZATION, DH_LOG }
    private static volatile MiraiLogSession session;
    private static final AtomicInteger marks = new AtomicInteger();
    private MiraiDiagnostics() { }

    /** Starts no disk work on the caller. The JVM property is an opt-out for comparative tests. */
    public static synchronized void start(Path gameDirectory, String physicalSide)
    {
        if (session != null) { return; }
        try
        {
            if ("false".equalsIgnoreCase(System.getProperty("akashic.mirai.diagnostics", "true"))) { return; }
            MiraiLogSession created = new MiraiLogSession(gameDirectory.resolve("logs").resolve("akashic-mirai"),
                    physicalSide, 512, 4 * 1024 * 1024, 4);
            session = created;
            created.start();
            Runtime.getRuntime().addShutdownHook(new Thread(created::close, "Mirai-Diagnostics-Shutdown"));
        }
        catch (RuntimeException failure)
        {
            if (session != null) { session.close(); }
            System.err.println("[Akashic Mirai] Diagnostic startup unavailable: " + failure.getClass().getName());
        }
    }
    public static boolean enabled() { MiraiLogSession s = session; return s != null && s.accepting; }
    static long elapsedMillis(long nanos)
    { MiraiLogSession s = session; return s == null ? -1 : s.elapsedMillis(nanos); }
    public static long startTiming() { return enabled() ? System.nanoTime() : 0; }
    static long traceTimingStart()
    { MiraiLogSession s = session; return s == null ? 0 : s.traceTimingStart(); }
    /** High-detail timing is deliberately opt-in via /miraidiag trace. */
    public static long startTraceTiming() { return traceTimingStart(); }
    /** Reuses one trace decision for nested phases of the same operation. */
    public static long startTracePhase(boolean traceActive) { return traceActive ? System.nanoTime() : 0; }
    public static void finishTiming(Metric metric, long start)
    { if (start != 0) { duration(metric, System.nanoTime() - start); } }
    /**
     * Observes a non-empty upload using the already resolved method. Includes Java/driver waits
     * and restoration; payload bytes are attempted input, not allocated VRAM or GPU time.
     * Uses only existing counters/histograms on the caller; no formatting, queueing or GL calls.
     * Unknown methods are ignored so this observer cannot replace an original upload exception.
     */
    public static void finishBufferUpload(EDhApiGpuUploadMethod method, int bytes, long start, boolean completed)
    {
        MiraiLogSession s = session;
        if (s == null || !s.accepting || start == 0 || bytes <= 0 || method == null) { return; }
        final Counter counter;
        final Metric metric;
        if (method == EDhApiGpuUploadMethod.BUFFER_STORAGE)
        { counter = Counter.GL_STORAGE_UPLOAD_BYTES; metric = Metric.GL_STORAGE_UPLOAD_WALL; }
        else if (method == EDhApiGpuUploadMethod.DATA)
        { counter = Counter.GL_DATA_UPLOAD_BYTES; metric = Metric.GL_DATA_UPLOAD_WALL; }
        else if (method == EDhApiGpuUploadMethod.SUB_DATA)
        { counter = Counter.GL_SUB_DATA_UPLOAD_BYTES; metric = Metric.GL_SUB_DATA_UPLOAD_WALL; }
        else { return; }
        long elapsed = System.nanoTime() - start;
        if (elapsed < 0) { return; }
        s.counters.addAndGet(counter.ordinal(), bytes);
        s.timings[metric.ordinal()].record(elapsed);
        if (!completed) { s.counters.incrementAndGet(Counter.GL_UPLOAD_JAVA_FAILURES.ordinal()); }
    }
    public static void duration(Metric metric, long nanos)
    { MiraiLogSession s = session; if (s != null && s.accepting && nanos >= 0) { s.timings[metric.ordinal()].record(nanos); } }
    public static void count(Counter counter) { add(counter, 1); }
    public static void add(Counter counter, long count)
    { MiraiLogSession s = session; if (s != null && s.accepting && count >= 0) { s.counters.addAndGet(counter.ordinal(), count); } }
    public static void event(String category, String detail)
    { MiraiLogSession s = session; if (s != null && s.accepting) { s.event(category, detail); } }
    public static void metadata(String key, String value)
    { MiraiLogSession s = session; if (s != null && s.accepting) { s.metadata(key, value); } }
    public static void section(Counter counter, long section, String reason)
    {
        count(counter);
        MiraiLogSession s = session;
        if (s != null && s.tracing()) { s.event("section", "counter=" + counter + " section=" + section + " reason=" + reason); }
    }
    public static void error(ErrorKind kind, Throwable failure)
    { MiraiLogSession s = session; if (s != null && s.accepting) { s.error(kind, failure); } }
    public static void ownerThread(boolean client)
    {
        MiraiLogSession s = session;
        if (s != null && s.accepting)
        {
            if (client) { s.clientThreadId = Thread.currentThread().getId(); }
            else { s.serverThreadId = Thread.currentThread().getId(); }
        }
    }
    public static void serverStopped()
    { MiraiLogSession s = session; if (s != null) { s.serverThreadId = 0; s.event("lifecycle", "server_stopped"); } }

    /** Only fixed verbs and a short machine-readable marker label; no arbitrary paths/text. */
    public static String command(String[] args)
    {
        MiraiLogSession s = session;
        if (args.length == 0 || (args.length == 1 && "status".equals(args[0])))
        { return s == null ? "Mirai diagnostics: disabled" : "Mirai diagnostics: " + s.status + "; logs/akashic-mirai/latest.log; trace=" + s.tracing() + "; samples=" + s.stackSamples + "; gpu=" + MiraiGpuProfiler.status() + "; dropped=" + s.dropped.get(); }
        if (s == null || !s.accepting) { return "Mirai diagnostics unavailable. Check startup log or JVM opt-out."; }
        if ("mark".equals(args[0]) && args.length <= 2)
        {
            String label = args.length == 2 ? args[1] : "manual";
            if (!label.matches("[A-Za-z0-9_-]{1,32}")) { return "Marker: use 1-32 letters, digits, _ or -."; }
            int id = marks.incrementAndGet();
            return s.event("mark", "id=" + id + " label=" + label) ? "Mirai MARK " + id + " (" + label + ") queued." : "Mirai marker not queued: diagnostic queue full.";
        }
        if ("trace".equals(args[0]) && args.length <= 2)
        {
            int seconds = 60;
            try { if (args.length == 2) { seconds = Integer.parseInt(args[1]); } }
            catch (NumberFormatException invalid) { return "Trace duration: 10-120 seconds."; }
            if (seconds < 10 || seconds > 120) { return "Trace duration: 10-120 seconds."; }
            s.traceUntilNanos.set(System.nanoTime() + seconds * 1_000_000_000L);
            s.event("trace", "start seconds=" + seconds + "; stack samples are observations, not mod CPU attribution; GLState save/restore wall timing active; no extra GL queries");
            return "Mirai trace enabled for " + seconds + " seconds. Stack sampling has additional overhead.";
        }
        if ("gpu".equals(args[0]) && args.length <= 2)
        {
            if (s.isDedicatedServer()) { return "GPU capture is client-only; use /miraidiag gpu on the Minecraft client."; }
            if (args.length == 2 && "stop".equals(args[1]))
            { MiraiGpuProfiler.stop(); return "Mirai GPU capture stopped; baseline logging continues."; }
            int seconds = 60;
            try { if (args.length == 2) { seconds = Integer.parseInt(args[1]); } }
            catch (NumberFormatException invalid) { return "GPU duration: 10-120 seconds."; }
            return MiraiGpuProfiler.request(seconds);
        }
        if (args.length == 1 && "stop".equals(args[0]))
        { s.traceUntilNanos.set(0); MiraiGpuProfiler.stop(); s.event("trace", "stopped_by_user; baseline_logging_continues"); return "Mirai trace and GPU capture stopped; baseline logging continues."; }
        return "Mirai diagnostics: status | mark [label] | trace [10-120 seconds] | gpu [10-120 seconds|stop] | stop";
    }
}
