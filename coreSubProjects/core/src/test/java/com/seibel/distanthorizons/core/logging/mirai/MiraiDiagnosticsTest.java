package com.seibel.distanthorizons.core.logging.mirai;

import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import org.junit.Test;
import org.junit.After;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class MiraiDiagnosticsTest
{
    private final List<MiraiLogSession> sessions = new ArrayList<>();
    private final List<Path> roots = new ArrayList<>();
    private Path directory() throws Exception { Path path = Files.createTempDirectory("mirai-diag-test-"); roots.add(path); return path; }
    private MiraiLogSession session(Path path, int capacity, long bytes) { MiraiLogSession s = new MiraiLogSession(path, "TEST", capacity, bytes, 4); sessions.add(s); return s; }
    private void ready(MiraiLogSession s) throws Exception
    {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ("starting".equals(s.status) && System.nanoTime() < end) { Thread.sleep(5); }
        assertEquals("recording", s.status);
    }
    private String text(Path file) throws Exception { return new String(Files.readAllBytes(file), StandardCharsets.UTF_8); }
    @After public void cleanup() throws Exception
    {
        for (MiraiLogSession s : sessions) { s.close(); s.awaitTermination(5000); }
        Field field = MiraiDiagnostics.class.getDeclaredField("session"); field.setAccessible(true); field.set(null, null);
        for (Path root : roots)
        {
            try (java.util.stream.Stream<Path> stream = Files.walk(root))
            { for (Path path : (Iterable<Path>) stream.sorted(Comparator.reverseOrder())::iterator) { Files.deleteIfExists(path); } }
        }
    }
    @Test public void fullQueueDropsWithoutStartingAnyDiskWork() throws Exception
    {
        Path root = directory(); Path path = root.resolve("not_created");
        MiraiLogSession s = session(path, 2, 4096);
        assertTrue(s.event("test", "a")); assertTrue(s.event("test", "b")); assertFalse(s.event("test", "c"));
        assertEquals(2, s.queued()); assertEquals(1, s.dropped.get()); assertFalse(Files.exists(path));
        s.close(); assertFalse(s.event("test", "after_close"));
    }
    @Test public void concurrentAdmissionIsBoundedAndAccounted() throws Exception
    {
        MiraiLogSession s = session(directory(), 128, 4096);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try
        {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) { futures.add(pool.submit(() -> { for (int n = 0; n < 1000; n++) { s.event("test", "payload"); } })); }
            for (Future<?> f : futures) { f.get(5, TimeUnit.SECONDS); }
            assertEquals(128, s.queued()); assertEquals(4000, s.queued() + s.dropped.get());
        }
        finally { pool.shutdownNow(); }
    }
    @Test public void restartPreservesPreviousSessionAndWritesFooter() throws Exception
    {
        Path path = directory(); Files.write(path.resolve("latest.log"), "old-session".getBytes(StandardCharsets.UTF_8));
        MiraiLogSession s = session(path, 32, 65536); s.start(); ready(s);
        s.event("mark", "id=1"); s.close(); s.awaitTermination(5000);
        assertEquals("old-session", text(path.resolve("previous-1.log")));
        String current = text(path.resolve("latest.log")); assertTrue(current.contains("id=1")); assertTrue(current.contains("session_end"));
        assertEquals("closed", s.status);
        assertTrue(current.contains("measurements overlap and must not be summed"));
        assertTrue(current.contains("attempted input payload, not allocated VRAM"));
    }
    @Test public void rotationHasFiniteFilesAndSegmentSizeWithMetadataReplay() throws Exception
    {
        Path path = directory(); MiraiLogSession s = session(path, 256, 8192); s.start(); ready(s);
        s.metadata("gpu.renderer", "synthetic-gpu");
        for (int i = 0; i < 150; i++) { s.event("test", String.join("", Collections.nCopies(160, "x")) + i); }
        s.close(); s.awaitTermination(5000);
        assertTrue(text(path.resolve("latest.log")).contains("synthetic-gpu"));
        int count = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(path, "*.log"))
        { for (Path file : files) { count++; assertTrue(Files.size(file) <= 8192); assertTrue(text(file).endsWith("\n")); } }
        assertTrue(count >= 2); assertTrue(count <= 5);
    }
    @Test public void newlineQuotesAndOversizedPayloadCannotInjectRecords() throws Exception
    {
        Path path = directory(); MiraiLogSession s = session(path, 16, 65536); s.start(); ready(s);
        s.event("test", "line\n\r\t\"\\\u0000" + String.join("", Collections.nCopies(6000, "x")));
        s.close(); s.awaitTermination(5000);
        List<String> lines = Files.readAllLines(path.resolve("latest.log"), StandardCharsets.UTF_8);
        long tests = lines.stream().filter(line -> line.contains("\"kind\":\"test\"")).count(); assertEquals(1, tests);
        String line = lines.stream().filter(value -> value.contains("\"kind\":\"test\"")).findFirst().get();
        assertTrue(line.contains("\\u000a\\u000d\\u0009\\\"\\\\\\u0000")); assertTrue(line.contains("[truncated]"));
        assertTrue(line.length() < 13000);
    }
    @Test public void unavailableDirectoryDisablesOnlyDiagnosticWriter() throws Exception
    {
        Path root = directory(); Path file = root.resolve("ordinary-file"); Files.write(file, new byte[]{42});
        MiraiLogSession s = session(file, 8, 4096); s.start(); s.awaitTermination(5000);
        assertTrue(s.status.startsWith("failed:")); assertFalse(s.accepting); assertFalse(s.event("test", "ignored"));
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(file));
    }
    @Test public void symbolicLatestIsNotFollowedOrReplaced() throws Exception
    {
        Path root = directory(); Path target = root.resolve("original"); Files.write(target, new byte[]{7,8});
        Path path = root.resolve("logs"); Files.createDirectory(path);
        try { Files.createSymbolicLink(path.resolve("latest.log"), target); }
        catch (UnsupportedOperationException | java.io.IOException unsupported) { org.junit.Assume.assumeNoException(unsupported); }
        MiraiLogSession s = session(path, 8, 4096); s.start(); s.awaitTermination(5000);
        assertTrue(s.status.startsWith("failed:")); assertArrayEquals(new byte[]{7,8}, Files.readAllBytes(target));
        assertTrue(Files.isSymbolicLink(path.resolve("latest.log")));
    }
    @Test public void concurrentWriterCannotRotateFirstWriterFile() throws Exception
    {
        Path path = directory(); MiraiLogSession first = session(path, 8, 65536); first.start(); ready(first);
        MiraiLogSession second = session(path, 8, 65536); second.start(); second.awaitTermination(5000);
        assertTrue(second.status.startsWith("failed:")); assertFalse(Files.exists(path.resolve("previous-1.log")));
        first.event("mark", "first_still_owns_file"); first.close(); first.awaitTermination(5000);
        assertTrue(text(path.resolve("latest.log")).contains("first_still_owns_file"));
    }
    @Test public void histogramDrainsCoherentCountsAcrossThreads() throws Exception
    {
        MiraiTiming timing = new MiraiTiming(); ExecutorService pool = Executors.newFixedThreadPool(4);
        try
        {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) { futures.add(pool.submit(() -> { for (int n = 0; n < 10000; n++) { timing.record(2_000_000); } })); }
            for (Future<?> f : futures) { f.get(5, TimeUnit.SECONDS); }
            long[] values = timing.drain(); assertEquals(40000, values[0]); assertEquals(80_000_000_000L, values[1]);
            assertEquals(2_000_000, values[2]); assertTrue(MiraiTiming.describe(values).contains("p95_bucket_ms=<=2"));
            assertEquals(0, timing.drain()[0]);
        }
        finally { pool.shutdownNow(); }
    }
    @Test public void errorsAreAggregatedWithoutExceptionMessagesOrUnboundedStacks() throws Exception
    {
        Path path = directory(); MiraiLogSession s = session(path, 32, 65536); s.start(); ready(s);
        RuntimeException error = new RuntimeException("secret-test-token-must-not-be-copied");
        for (int i = 0; i < 1000; i++) { s.error(MiraiDiagnostics.ErrorKind.DATABASE, error); }
        s.close(); s.awaitTermination(5000); String log = text(path.resolve("latest.log"));
        assertFalse(log.contains("secret-test-token")); assertTrue(log.contains("errors_DATABASE=1000"));
        assertEquals(1, log.split("\"kind\":\"error\"", -1).length - 1);
        assertTrue(log.contains("java.lang.RuntimeException"));
    }
    @Test public void traceCommandsAreBoundedAndDoNotEnableThreadCpuMonitoring() throws Exception
    {
        Path path = directory(); MiraiLogSession s = session(path, 64, 65536); s.start(); ready(s);
        Field field = MiraiDiagnostics.class.getDeclaredField("session"); field.setAccessible(true); field.set(null, s);
        boolean before = ManagementFactory.getThreadMXBean().isThreadCpuTimeEnabled();
        assertTrue(MiraiDiagnostics.command(new String[]{"trace", "99999999"}).contains("10-120")); assertEquals(0, s.traceUntilNanos);
        assertTrue(MiraiDiagnostics.command(new String[]{"mark", "../unsafe\n"}).contains("Marker:"));
        assertTrue(MiraiDiagnostics.command(new String[]{"mark", "sky_white"}).contains("queued"));
        MiraiDiagnostics.ownerThread(true);
        assertTrue(MiraiDiagnostics.command(new String[]{"trace", "10"}).contains("10 seconds")); assertTrue(s.tracing());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (s.stackSamples == 0 && System.nanoTime() < deadline) { Thread.sleep(10); }
        assertTrue("A real sample must be captured before stopping", s.stackSamples > 0);
        assertTrue(MiraiDiagnostics.command(new String[]{"stop"}).contains("baseline")); assertFalse(s.tracing());
        assertEquals(before, ManagementFactory.getThreadMXBean().isThreadCpuTimeEnabled());
        s.close(); s.awaitTermination(5000);
        String log = text(path.resolve("latest.log")); assertTrue(log.contains("mod_attribution=none")); assertTrue(log.contains("sky_white"));
    }
    private void install(MiraiLogSession s) throws Exception
    { Field field = MiraiDiagnostics.class.getDeclaredField("session"); field.setAccessible(true); field.set(null, s); }

    @Test public void uploadObservationsRouteResolvedMethodsAndCountAttemptedBytes() throws Exception
    {
        MiraiLogSession s = session(directory().resolve("not_created"), 2, 4096); install(s);
        MiraiDiagnostics.finishBufferUpload(EDhApiGpuUploadMethod.BUFFER_STORAGE, 128, MiraiDiagnostics.startTiming(), true);
        MiraiDiagnostics.finishBufferUpload(EDhApiGpuUploadMethod.DATA, 256, MiraiDiagnostics.startTiming(), true);
        MiraiDiagnostics.finishBufferUpload(EDhApiGpuUploadMethod.SUB_DATA, 512, MiraiDiagnostics.startTiming(), false);
        assertEquals(128, s.counters.get(MiraiDiagnostics.Counter.GL_STORAGE_UPLOAD_BYTES.ordinal()));
        assertEquals(256, s.counters.get(MiraiDiagnostics.Counter.GL_DATA_UPLOAD_BYTES.ordinal()));
        assertEquals(512, s.counters.get(MiraiDiagnostics.Counter.GL_SUB_DATA_UPLOAD_BYTES.ordinal()));
        assertEquals(1, s.counters.get(MiraiDiagnostics.Counter.GL_UPLOAD_JAVA_FAILURES.ordinal()));
        for (MiraiDiagnostics.Metric metric : new MiraiDiagnostics.Metric[]{MiraiDiagnostics.Metric.GL_STORAGE_UPLOAD_WALL,
                MiraiDiagnostics.Metric.GL_DATA_UPLOAD_WALL, MiraiDiagnostics.Metric.GL_SUB_DATA_UPLOAD_WALL})
        { long[] values = s.timings[metric.ordinal()].drain(); assertEquals(1, values[0]); assertTrue(values[1] >= 0); }
        assertEquals(0, s.queued()); assertEquals(0, s.dropped.get());
    }
    @Test public void tenThousandUploadsUseOnlyAggregatesWithoutDiskOrQueueAdmission() throws Exception
    {
        Path path = directory().resolve("not_created"); MiraiLogSession s = session(path, 2, 4096); install(s);
        for (int i = 0; i < 10000; i++)
        { MiraiDiagnostics.finishBufferUpload(EDhApiGpuUploadMethod.BUFFER_STORAGE, 4096, MiraiDiagnostics.startTiming(), true); }
        assertEquals(40_960_000, s.counters.get(MiraiDiagnostics.Counter.GL_STORAGE_UPLOAD_BYTES.ordinal()));
        assertEquals(10000, s.timings[MiraiDiagnostics.Metric.GL_STORAGE_UPLOAD_WALL.ordinal()].drain()[0]);
        assertEquals(0, s.queued()); assertEquals(0, s.dropped.get()); assertFalse(Files.exists(path));
    }
    @Test public void invalidUploadTokensAndClosedSessionsAreIgnored() throws Exception
    {
        MiraiLogSession s = session(directory(), 2, 4096); install(s); long token = MiraiDiagnostics.startTiming();
        MiraiDiagnostics.finishBufferUpload(null, 1, token, false);
        MiraiDiagnostics.finishBufferUpload(EDhApiGpuUploadMethod.AUTO, 1, token, false);
        MiraiDiagnostics.finishBufferUpload(EDhApiGpuUploadMethod.DATA, 0, token, false);
        MiraiDiagnostics.finishBufferUpload(EDhApiGpuUploadMethod.DATA, -1, token, false);
        MiraiDiagnostics.finishBufferUpload(EDhApiGpuUploadMethod.DATA, 1, 0, false);
        s.close();
        MiraiDiagnostics.finishBufferUpload(EDhApiGpuUploadMethod.DATA, 1, token, false);
        for (MiraiDiagnostics.Counter counter : MiraiDiagnostics.Counter.values()) { assertEquals(0, s.counters.get(counter.ordinal())); }
        for (MiraiTiming timing : s.timings) { assertEquals(0, timing.drain()[0]); }
    }
    @Test public void optedOutDiagnosticsDoNotObserveUploadsOrCreateDirectory() throws Exception
    {
        Path path = directory(); String old = System.getProperty("akashic.mirai.diagnostics");
        try
        {
            System.setProperty("akashic.mirai.diagnostics", "false"); MiraiDiagnostics.start(path, "TEST");
            assertFalse(MiraiDiagnostics.enabled()); assertEquals(0, MiraiDiagnostics.startTiming());
            MiraiDiagnostics.finishBufferUpload(EDhApiGpuUploadMethod.DATA, 1024, System.nanoTime(), false);
            assertFalse(Files.exists(path.resolve("logs")));
        }
        finally
        { if (old == null) { System.clearProperty("akashic.mirai.diagnostics"); } else { System.setProperty("akashic.mirai.diagnostics", old); } }
    }

}
