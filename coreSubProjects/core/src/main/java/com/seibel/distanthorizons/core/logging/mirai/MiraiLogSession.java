package com.seibel.distanthorizons.core.logging.mirai;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** Owns all filesystem work. Queue entries contain bounded strings, never worlds, packets or Throwables. */
final class MiraiLogSession implements AutoCloseable
{
    static final int MAX_DETAIL = 2048;
    final AtomicLongArray counters = new AtomicLongArray(MiraiDiagnostics.Counter.values().length);
    final MiraiTiming[] timings = new MiraiTiming[MiraiDiagnostics.Metric.values().length];
    final AtomicLong dropped = new AtomicLong();
    private final AtomicLongArray errorCounts = new AtomicLongArray(MiraiDiagnostics.ErrorKind.values().length);
    private final AtomicLongArray errorNext = new AtomicLongArray(MiraiDiagnostics.ErrorKind.values().length);
    private final ArrayBlockingQueue<Entry> queue;
    private final Path directory;
    private final long maxBytes;
    private final int archiveCount;
    private final String side;
    private final String sessionId = UUID.randomUUID().toString();
    private final long startedNanos = System.nanoTime();
    private final long startedEpochMillis = System.currentTimeMillis();
    private final LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
    private final Thread worker;
    volatile boolean accepting = true;
    volatile String status = "starting";
    volatile long traceUntilNanos, clientThreadId, serverThreadId;
    volatile long stackSamples;
    private BufferedWriter output;
    private long bytes, lastFlush, lastSnapshot, lastTrace;
    private boolean traceWasActive;

    MiraiLogSession(Path directory, String side, int capacity, long maxBytes, int archiveCount)
    {
        if (capacity < 1 || maxBytes < 4096 || archiveCount < 1 || archiveCount > 8) { throw new IllegalArgumentException(); }
        this.directory = directory.toAbsolutePath().normalize();
        this.side = bounded(side, 16); this.maxBytes = maxBytes; this.archiveCount = archiveCount;
        this.queue = new ArrayBlockingQueue<>(capacity);
        for (int i = 0; i < timings.length; i++) { timings[i] = new MiraiTiming(); }
        this.worker = new Thread(this::run, "Mirai-Diagnostics-Writer");
        this.worker.setDaemon(true);
    }
    void start() { worker.start(); }
    boolean tracing() { return accepting && traceUntilNanos != 0 && System.nanoTime() - traceUntilNanos < 0; }
    boolean event(String kind, String detail) { return offer(new Entry(bounded(kind, 64), bounded(detail, MAX_DETAIL), false)); }
    void metadata(String key, String value) { offer(new Entry(bounded(key, 96), bounded(value, 512), true)); }
    private boolean offer(Entry entry)
    {
        if (!accepting) { return false; }
        if (queue.offer(entry)) { return true; }
        dropped.incrementAndGet(); return false;
    }
    void error(MiraiDiagnostics.ErrorKind kind, Throwable failure)
    {
        errorCounts.incrementAndGet(kind.ordinal());
        long now = System.nanoTime(), next = errorNext.get(kind.ordinal());
        if (next != 0 && now - next < 0) { return; }
        if (!errorNext.compareAndSet(kind.ordinal(), next, now + TimeUnit.SECONDS.toNanos(30))) { return; }
        try
        {
            // Arbitrary exception messages can contain addresses, paths or packet text.
            // Keep type/cause/frames; the original DH logger retains its usual exception.
            StringBuilder text = new StringBuilder(kind.name());
            Throwable current = failure;
            for (int cause = 0; current != null && cause < 4; cause++)
            {
                text.append(" cause=").append(current.getClass().getName());
                StackTraceElement[] stack = current.getStackTrace();
                for (int i = 0; i < Math.min(12, stack.length); i++)
                {
                    text.append(" <- ").append(stack[i].getClassName()).append('.').append(stack[i].getMethodName())
                            .append(':').append(stack[i].getLineNumber());
                }
                current = current.getCause();
                if (current == failure) { break; }
            }
            event("error", text.toString());
        }
        catch (RuntimeException ignored) { event("error", kind + " stack_unavailable"); }
    }
    @Override public void close()
    {
        accepting = false;
        if (Thread.currentThread() != worker)
        {
            try { worker.join(1000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
    void awaitTermination(long millis) throws InterruptedException { worker.join(millis); }
    int queued() { return queue.size(); }
    boolean isDedicatedServer() { return "SERVER".equalsIgnoreCase(side); }
    long elapsedMillis(long nanos) { return Math.max(0, nanos - startedNanos) / 1_000_000L; }

    private void run()
    {
        try
        {
            Path parent = directory.getParent();
            rejectLink(parent); rejectLink(directory);
            Files.createDirectories(directory);
            Path lockPath = directory.resolve("writer.lock");
            rejectLink(lockPath);
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock lock = channel.tryLock())
            {
                if (lock == null) { throw new IOException("Diagnostic directory already owned by another process"); }
                rotate();
                status = "recording";
                for (String key : new String[]{"java.version", "java.vendor", "java.vm.name", "os.name", "os.version", "os.arch"})
                { remember("environment." + key, System.getProperty(key, "unavailable")); }
                remember("environment.processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
                remember("policy", "5s summaries; max4MiB/segment+" + archiveCount + " archives; queue=" + queue.remainingCapacity()
                        + "; no uploads/chat/credentials/JVM arguments; CPU model and GPU time unavailable unless explicitly measured");
                remember("timing.render_scope", "CLIENT_RENDER_PASS_WALL and CLIENT_DEFERRED_PASS_WALL include the whole client DH pass; queue/task/upload/LOD measurements overlap and must not be summed; all are elapsed wall time including waits, not GPU time");
                remember("timing.upload_scope", "GL_*_UPLOAD_WALL includes buffer preparation and state restoration; GL_*_UPLOAD_BYTES is attempted input payload, not allocated VRAM; GL_UPLOAD_JAVA_FAILURES counts uploads that exit exceptionally, including partial work");
                remember("timing.render_list_scope", "LOD_RENDER_LIST_WALL includes selection, list construction and culling; nested within LOD_RENDER_WALL, not pure culling or exclusive CPU time; do not add to enclosing pass");
                remember("timing.world_lifecycle_scope", "world_lifecycle begin/end pairs share id and fixed phase; wall_ns measures caller action including waits, not GPU time; phases can nest and must not be summed blindly; missing end can mean incomplete coverage or dropped events; EXPLICIT_GC measures System.gc call return only");
                remember("timing.gpu_scope", "GPU_MAIN_PASS/GPU_DEFERRED_PASS and gpu_sample stages are asynchronous GL_TIMESTAMP completion intervals, not GPU busy time or whole Minecraft frame; nested stages overlap PASS, may include CPU submission gaps; absent stages omitted, zero is valid; results arrive in later windows");
                remember("timing.gpu_observer", "GPU_SAMPLED_PASS_WALL pairs CPU wall with sampled GPU pass; GPU_OBSERVER_WALL sums individual observer calls including polling/formatting, not per-frame cost; capture is opt-in for 10-120s, every fourth invocation per pass kind, six slots; unavailable samples skipped, no forced synchronization");
                long now = System.nanoTime(); lastSnapshot = lastFlush = lastTrace = now;
                while (accepting || !queue.isEmpty())
                {
                    Entry entry = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (entry != null)
                    {
                        if (entry.metadata) { remember(entry.kind, entry.detail); }
                        else { write(entry.nanos, entry.kind, entry.detail); }
                    }
                    now = System.nanoTime();
                    if (now - lastSnapshot >= TimeUnit.SECONDS.toNanos(5))
                    { snapshot(now - lastSnapshot); lastSnapshot = now; }
                    boolean tracing = tracing();
                    if (tracing && now - lastTrace >= TimeUnit.MILLISECONDS.toNanos(250))
                    { sampleStacks(); lastTrace = now; }
                    if (traceWasActive && !tracing) { write(now, "trace", "finished; baseline_logging_continues"); }
                    traceWasActive = tracing;
                    if (now - lastFlush >= TimeUnit.SECONDS.toNanos(1)) { output.flush(); lastFlush = now; }
                }
                snapshot(System.nanoTime() - lastSnapshot);
                write(System.nanoTime(), "session_end", "normal_shutdown; queued=" + queue.size() + " dropped_total=" + dropped.get());
                output.close(); output = null; status = "closed";
            }
        }
        catch (Exception | LinkageError failure)
        {
            status = "failed:" + failure.getClass().getSimpleName();
            System.err.println("[Akashic Mirai] Dedicated diagnostic log stopped: " + failure.getClass().getName());
        }
        finally
        {
            accepting = false; queue.clear();
            if (output != null) { try { output.close(); } catch (IOException ignored) { } }
        }
    }
    private void remember(String key, String value) throws IOException
    {
        if (metadata.size() < 512 || metadata.containsKey(key)) { metadata.put(key, bounded(value, 512)); }
        else { write(System.nanoTime(), "metadata_limit", "entry_not_retained_for_rotation=" + bounded(key, 96)); }
        write(System.nanoTime(), "metadata", key + "=" + bounded(value, 512));
    }
    private void snapshot(long interval) throws IOException
    {
        StringBuilder data = new StringBuilder("window_ms=").append(interval / 1_000_000L)
                .append(" dropped_total=").append(dropped.get()).append(" queue=").append(queue.size());
        for (MiraiDiagnostics.Counter counter : MiraiDiagnostics.Counter.values())
        { data.append(' ').append(counter).append('=').append(counters.getAndSet(counter.ordinal(), 0)); }
        for (MiraiDiagnostics.ErrorKind error : MiraiDiagnostics.ErrorKind.values())
        { data.append(" errors_").append(error).append('=').append(errorCounts.getAndSet(error.ordinal(), 0)); }
        write(System.nanoTime(), "counters", data.toString());
        for (MiraiDiagnostics.Metric metric : MiraiDiagnostics.Metric.values())
        { write(System.nanoTime(), "timing", metric + " " + MiraiTiming.describe(timings[metric.ordinal()].drain())); }
        try { runtimeSnapshot(); }
        catch (RuntimeException | LinkageError unavailable)
        { write(System.nanoTime(), "jvm", "optional_management_metrics_unavailable=" + unavailable.getClass().getName()); }
    }
    private void runtimeSnapshot() throws IOException
    {
        Runtime runtime = Runtime.getRuntime();
        long gcCount = 0, gcMs = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans())
        { if (bean.getCollectionCount() >= 0) { gcCount += bean.getCollectionCount(); } if (bean.getCollectionTime() >= 0) { gcMs += bean.getCollectionTime(); } }
        write(System.nanoTime(), "jvm", "heap_used=" + (runtime.totalMemory() - runtime.freeMemory()) + " heap_committed=" + runtime.totalMemory()
                + " heap_max=" + runtime.maxMemory() + " gc_count_total=" + gcCount + " gc_ms_total=" + gcMs
                + " live_threads=" + ManagementFactory.getThreadMXBean().getThreadCount()
                + " process_cpu_ns_total=" + processCpuTime()
                + " system_ram_total=" + osMetric("getTotalMemorySize", "getTotalPhysicalMemorySize")
                + " system_ram_free=" + osMetric("getFreeMemorySize", "getFreePhysicalMemorySize")
                + " process_cpu_load_estimate=" + osMetric("getProcessCpuLoad", "getProcessCpuLoad")
                + " native_vram_bytes=unavailable cpu_model=unavailable");
        for (java.lang.management.BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class))
        { write(System.nanoTime(), "jvm_buffer_pool", "name=" + bounded(pool.getName(), 64) + " count=" + pool.getCount() + " bytes=" + pool.getMemoryUsed() + "; excludes other native allocations and GPU memory"); }
    }
    private static String osMetric(String current, String legacy)
    {
        try
        {
            Class<?> type = Class.forName("com.sun.management.OperatingSystemMXBean");
            java.lang.reflect.Method method;
            try { method = type.getMethod(current); } catch (NoSuchMethodException older) { method = type.getMethod(legacy); }
            Object bean = ManagementFactory.getOperatingSystemMXBean();
            return type.isInstance(bean) ? String.valueOf(method.invoke(bean)) : "unavailable";
        }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return "unavailable"; }
    }
    private static long processCpuTime()
    {
        try
        {
            Class<?> type = Class.forName("com.sun.management.OperatingSystemMXBean");
            Object bean = ManagementFactory.getOperatingSystemMXBean();
            return type.isInstance(bean) ? (Long) type.getMethod("getProcessCpuTime").invoke(bean) : -1;
        }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return -1; }
    }
    private void sampleStacks() throws IOException
    {
        try
        {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            sampleThread(bean, "client_main", clientThreadId);
            sampleThread(bean, "server_main", serverThreadId);
        }
        catch (RuntimeException unsupported)
        { write(System.nanoTime(), "stack_sample", "unavailable=" + unsupported.getClass().getName()); traceUntilNanos = 0; }
    }
    private void sampleThread(ThreadMXBean bean, String role, long id) throws IOException
    {
        if (id <= 0) { return; }
        ThreadInfo info = bean.getThreadInfo(id, 24);
        if (info == null) { return; }
        long cpu = bean.isThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled() ? bean.getThreadCpuTime(id) : -1;
        StringBuilder text = new StringBuilder("observation_only role=").append(role).append(" state=").append(info.getThreadState())
                .append(" thread_cpu_ns_total=").append(cpu).append(" mod_attribution=none");
        for (StackTraceElement frame : info.getStackTrace())
        { text.append(" <- ").append(frame.getClassName()).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber()); }
        write(System.nanoTime(), "stack_sample", text.toString());
        stackSamples++;
    }
    private void rotate() throws IOException
    {
        if (output != null) { output.close(); output = null; }
        for (int i = 1; i <= archiveCount; i++) { rejectNonRegular(directory.resolve("previous-" + i + ".log")); }
        Path latest = directory.resolve("latest.log"); rejectNonRegular(latest);
        // Startup without a latest file must not shift/drop an unrelated older session.
        if (Files.exists(latest, LinkOption.NOFOLLOW_LINKS))
        {
            Files.deleteIfExists(directory.resolve("previous-" + archiveCount + ".log"));
            for (int i = archiveCount - 1; i >= 1; i--)
            {
                Path source = directory.resolve("previous-" + i + ".log");
                if (Files.exists(source)) { Files.move(source, directory.resolve("previous-" + (i + 1) + ".log"), StandardCopyOption.REPLACE_EXISTING); }
            }
            Files.move(latest, directory.resolve("previous-1.log"), StandardCopyOption.REPLACE_EXISTING);
        }
        output = Files.newBufferedWriter(latest, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        bytes = 0;
        raw(System.nanoTime(), "session", "schema=2 id=" + sessionId + " physical_side=" + side + "; durations=elapsed wall time except GPU_MAIN_PASS/GPU_DEFERRED_PASS and gpu_sample completion intervals; metadata may be replayed after rotation");
        int skipped = 0;
        for (Map.Entry<String, String> entry : metadata.entrySet())
        {
            String line = line(System.nanoTime(), "metadata", entry.getKey() + "=" + entry.getValue());
            long length = line.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + length < maxBytes / 2) { output.write(line); bytes += length; } else { skipped++; }
        }
        if (skipped > 0) { raw(System.nanoTime(), "metadata_replay_limit", "omitted=" + skipped + "; see previous segments"); }
    }
    private void write(long nanos, String kind, String message) throws IOException
    {
        String line = line(nanos, kind, message);
        long length = line.getBytes(StandardCharsets.UTF_8).length;
        if (bytes + length > maxBytes) { rotate(); }
        // Small test limits and worst-case escaped input also respect the segment bound.
        if (bytes + length > maxBytes) { dropped.incrementAndGet(); return; }
        output.write(line); bytes += length;
    }
    private void raw(long nanos, String kind, String message) throws IOException
    { String line = line(nanos, kind, message); output.write(line); bytes += line.getBytes(StandardCharsets.UTF_8).length; }
    private String line(long nanos, String kind, String message)
    {
        return "{\"utc\":\"" + Instant.ofEpochMilli(startedEpochMillis + Math.max(0, nanos - startedNanos) / 1_000_000L) + "\",\"elapsed_ms\":" + Math.max(0, nanos - startedNanos) / 1_000_000L
                + ",\"session\":\"" + sessionId + "\",\"kind\":\"" + escape(bounded(kind, 96))
                + "\",\"message\":\"" + escape(bounded(message, MAX_DETAIL)) + "\"}\n";
    }
    static String bounded(String value, int length)
    { return value == null ? "unavailable" : value.length() <= length ? value : value.substring(0, length - 12) + "[truncated]"; }
    static String escape(String value)
    {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') { result.append('\\').append(c); }
            else if (c < 32 || Character.isSurrogate(c))
            { String hex = Integer.toHexString(c); result.append("\\u"); for (int j = hex.length(); j < 4; j++) { result.append('0'); } result.append(hex); }
            else { result.append(c); }
        }
        return result.toString();
    }
    private static void rejectLink(Path path) throws IOException
    { if (Files.isSymbolicLink(path)) { throw new IOException("Refusing symbolic diagnostic path"); } }
    private static void rejectNonRegular(Path path) throws IOException
    { if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { throw new IOException("Refusing nonregular diagnostic file"); } }
    private static final class Entry
    {
        final long nanos = System.nanoTime();
        final String kind, detail;
        final boolean metadata;
        Entry(String kind, String detail, boolean metadata) { this.kind = kind; this.detail = detail; this.metadata = metadata; }
    }
}
