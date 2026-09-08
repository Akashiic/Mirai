import com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics;
import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;

/** Isolated instrumentation microbenchmark. Not an FPS, GPU, or full-snapshot benchmark. */
public final class DiagnosticHotPathProbe {
    private static volatile long sink;
    private static void loop(int iterations, int mode) {
        long value = 0;
        for (int i = 0; i < iterations; i++) {
            long start = MiraiDiagnostics.startTiming();
            if (mode == 0) {
                MiraiDiagnostics.count(MiraiDiagnostics.Counter.CHUNK_SUBMITTED);
                MiraiDiagnostics.finishTiming(MiraiDiagnostics.Metric.LOD_RENDER_WALL, start);
            } else {
                long queue = mode == 2 ? MiraiDiagnostics.startTiming() : 0;
                long task = mode == 2 ? MiraiDiagnostics.startTiming() : 0;
                long upload = MiraiDiagnostics.startTiming();
                EDhApiGpuUploadMethod method = (i % 3 == 0) ? EDhApiGpuUploadMethod.BUFFER_STORAGE
                        : (i % 3 == 1) ? EDhApiGpuUploadMethod.DATA : EDhApiGpuUploadMethod.SUB_DATA;
                MiraiDiagnostics.finishBufferUpload(method, 4096, upload, (i & 1023) != 0);
                if (mode == 2) {
                    MiraiDiagnostics.finishTiming(MiraiDiagnostics.Metric.RENDER_TASK_WALL, task);
                    MiraiDiagnostics.finishTiming(MiraiDiagnostics.Metric.RENDER_TASK_QUEUE_WALL, queue);
                    MiraiDiagnostics.finishTiming(MiraiDiagnostics.Metric.LOD_RENDER_WALL, start);
                    MiraiDiagnostics.finishTiming((i & 1) == 0 ? MiraiDiagnostics.Metric.CLIENT_RENDER_PASS_WALL
                            : MiraiDiagnostics.Metric.CLIENT_DEFERRED_PASS_WALL, start);
                }
            }
            value += i & 7;
        }
        sink = value;
    }
    public static void main(String[] args) throws Exception {
        boolean enabled = Boolean.parseBoolean(args[0]);
        int mode = Integer.parseInt(args[1]);
        Path directory = Files.createTempDirectory("mirai-diagnostic-probe-");
        System.setProperty("akashic.mirai.diagnostics", Boolean.toString(enabled));
        MiraiDiagnostics.start(directory, "PROBE");
        if (enabled) {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!MiraiDiagnostics.command(new String[]{"status"}).contains("recording")) {
                if (System.nanoTime() >= deadline) { throw new AssertionError("Writer did not start"); }
                Thread.sleep(10);
            }
        }
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) { throw new AssertionError("Allocation accounting unavailable on probe JVM"); }
        bean.setThreadAllocatedMemoryEnabled(true); // Probe only. Production diagnostics never does this.
        long thread = Thread.currentThread().getId();
        long[] elapsedRounds = new long[5];
        long[] allocatedRounds = new long[5];
        loop(3_000_000, mode);
        for (int i = 0; i < 10; i++) { bean.getThreadAllocatedBytes(thread); }
        int iterations = 1_000_000;
        for (int round = 0; round < 5; round++) {
            long allocated = bean.getThreadAllocatedBytes(thread);
            long start = System.nanoTime();
            loop(iterations, mode);
            long elapsed = System.nanoTime() - start;
            long bytes = bean.getThreadAllocatedBytes(thread) - allocated;
            elapsedRounds[round] = elapsed;
            allocatedRounds[round] = bytes;
        }
        // Format only after all measurements, avoiding print/JIT activity between measured rounds.
        for (int round = 0; round < 5; round++) {
            System.out.println("{\"enabled\":" + enabled + ",\"mode\":" + mode + ",\"round\":" + round + ",\"iterations\":" + iterations
                    + ",\"elapsed_ns\":" + elapsedRounds[round] + ",\"caller_allocated_bytes\":" + allocatedRounds[round] + "}");
        }
        Field field = MiraiDiagnostics.class.getDeclaredField("session");
        field.setAccessible(true);
        AutoCloseable session = (AutoCloseable) field.get(null);
        if (session != null) { session.close(); }
    }
}
