package com.seibel.distanthorizons.core.logging.mirai;

import com.seibel.distanthorizons.core.logging.mirai.MiraiGpuQueryRing.Stage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

/** Exercises the real facade with the real query ring, without a native context or writer thread. */
public class MiraiGpuProfilerTest
{
    private MiraiLogSession session;
    private Path unusedDirectory;
    private Backend backend;
    private int factoryCalls;

    @Before public void setup() throws Exception
    {
        resetFacade();
        unusedDirectory = Paths.get(System.getProperty("java.io.tmpdir"), "mirai-gpu-unstarted-" + UUID.randomUUID());
        session = new MiraiLogSession(unusedDirectory, "TEST", 8, 4096, 1);
        set(MiraiDiagnostics.class, "session", session);
        backend = new Backend();
        MiraiGpuProfiler.installBackendFactory(() -> { factoryCalls++; return backend; });
    }

    @After public void cleanup() throws Exception
    {
        // The fake's original context is restored solely for safe test cleanup.
        backend.current = true;
        MiraiGpuProfiler.stop(); MiraiGpuProfiler.maintenance();
        resetFacade();
        session.close(); set(MiraiDiagnostics.class, "session", null);
        assertFalse("The GPU observer must not start the writer or perform disk work", Files.exists(unusedDirectory));
    }

    @Test public void offPathPerformsNoFactoryOrBackendCalls()
    {
        for (int i = 0; i < 100; i++)
        {
            assertFalse(MiraiGpuProfiler.beginPass(false));
            assertEquals(-1, MiraiGpuProfiler.beginStage(Stage.OPAQUE));
            MiraiGpuProfiler.endStage(-1); MiraiGpuProfiler.endPass(true); MiraiGpuProfiler.maintenance();
        }
        assertEquals("off", MiraiGpuProfiler.status());
        assertEquals(0, factoryCalls); assertEquals(0, backend.calls); assertEquals(0, backend.contextChecks);
        assertEquals(0, counter(MiraiDiagnostics.Counter.GPU_QUERY_FAILURES));
    }

    @Test public void commandsOnlyRequestAndStopWithoutAnyGlWork()
    {
        assertTrue(MiraiDiagnostics.command(new String[]{"gpu", "9"}).contains("10-120"));
        assertTrue(MiraiDiagnostics.command(new String[]{"gpu", "121"}).contains("10-120"));
        assertTrue(MiraiDiagnostics.command(new String[]{"gpu", "invalid"}).contains("10-120"));
        assertEquals("off", MiraiGpuProfiler.status());
        assertTrue(MiraiDiagnostics.command(new String[]{"gpu", "10"}).contains("requested"));
        assertEquals("requested", MiraiGpuProfiler.status());
        assertTrue(MiraiDiagnostics.command(new String[]{"status"}).contains("gpu=requested"));
        assertTrue(MiraiDiagnostics.command(new String[]{"gpu", "stop"}).contains("stopped"));
        assertEquals("off", MiraiGpuProfiler.status());
        MiraiDiagnostics.command(new String[]{"gpu"});
        MiraiDiagnostics.command(new String[]{"stop"});
        assertEquals("off", MiraiGpuProfiler.status());
        assertEquals(0, factoryCalls); assertEquals(0, backend.calls); assertEquals(0, backend.contextChecks);
    }

    @Test public void disabledBaselinePreventsNativeCaptureEvenWhenDirectlyRequested()
    {
        session.accepting = false;
        MiraiGpuProfiler.request(10);
        for (int i = 0; i < 8; i++) { assertFalse(MiraiGpuProfiler.beginPass(false)); }
        assertEquals(0, factoryCalls); assertEquals(0, backend.calls);
    }

    @Test public void missingFactoryWaitsAndCanStartWhenRendererRegisters()
    {
        MiraiGpuProfiler.installBackendFactory(null); MiraiGpuProfiler.request(10);
        assertFalse(MiraiGpuProfiler.beginPass(false));
        assertEquals("waiting_for_renderer", MiraiGpuProfiler.status());
        assertEquals(0, backend.calls);
        MiraiGpuProfiler.installBackendFactory(() -> { factoryCalls++; return backend; });
        start(false); MiraiGpuProfiler.endPass(true);
        assertEquals("recording", MiraiGpuProfiler.status());
        assertEquals(1, factoryCalls); assertEquals(Stage.values().length * 2, backend.created);
    }

    @Test public void unsupportedFactoryAndNarrowCountersFallBackBeforeCreatingQueries()
    {
        MiraiGpuProfiler.installBackendFactory(() -> { factoryCalls++; return null; });
        MiraiGpuProfiler.request(10); assertFalse(MiraiGpuProfiler.beginPass(false));
        assertEquals("unsupported", MiraiGpuProfiler.status());
        assertFalse(MiraiGpuProfiler.beginPass(false)); assertEquals(1, factoryCalls);
        MiraiGpuProfiler.installBackendFactory(() -> { factoryCalls++; return backend; });
        for (int bits : new int[]{0, 30, 32, 63})
        {
            backend.bits = bits; MiraiGpuProfiler.request(10);
            assertFalse(MiraiGpuProfiler.beginPass(false));
            assertEquals("unsupported", MiraiGpuProfiler.status());
            assertTrue(MiraiDiagnostics.enabled());
        }
        assertEquals(0, backend.created); assertEquals(0, backend.reads);
    }

    @Test public void nativeLinkageFailureDisablesOnlyObserverAndClosesPartialCapture()
    {
        backend.failNativeTimestamp = true; MiraiGpuProfiler.request(10);
        for (int i = 0; i < 4; i++) { assertFalse(MiraiGpuProfiler.beginPass(false)); }
        assertTrue(MiraiGpuProfiler.status().startsWith("failed:UnsatisfiedLinkError"));
        assertEquals(1, counter(MiraiDiagnostics.Counter.GPU_QUERY_FAILURES));
        assertEquals(Stage.values().length * 2, backend.deleted.size());
        assertTrue(MiraiDiagnostics.enabled()); assertTrue(session.accepting);
        int calls = backend.calls;
        MiraiGpuProfiler.endStage(1); MiraiGpuProfiler.endPass(false);
        assertFalse(MiraiGpuProfiler.beginPass(false)); assertEquals(calls, backend.calls);
    }

    @Test public void availabilityAndReadExceptionsAreContainedAndReleaseQueries()
    {
        for (String operation : new String[]{"available", "result"})
        {
            backend = new Backend(); MiraiGpuProfiler.request(10); start(false);
            MiraiGpuProfiler.endPass(true); backend.readyAll(); backend.failOperation = operation;
            assertFalse(MiraiGpuProfiler.beginPass(false));
            assertTrue(MiraiGpuProfiler.status().startsWith("failed:"));
            assertEquals(Stage.values().length * 2, backend.deleted.size()); assertTrue(MiraiDiagnostics.enabled());
        }
        assertEquals(2, counter(MiraiDiagnostics.Counter.GPU_QUERY_FAILURES));
        assertEquals(0, counter(MiraiDiagnostics.Counter.GPU_SAMPLES));
    }

    @Test public void incompleteAndExplicitlyInvalidatedPassesAreNeverPublished()
    {
        MiraiGpuProfiler.request(10); start(false);
        int token = MiraiGpuProfiler.beginStage(Stage.OPAQUE);
        MiraiGpuProfiler.endStage(token); MiraiGpuProfiler.endPass(false);
        backend.readyAll(); start(false);
        token = MiraiGpuProfiler.beginStage(Stage.OPAQUE);
        MiraiGpuProfiler.invalidatePass(); MiraiGpuProfiler.endStage(token); MiraiGpuProfiler.endPass(true);
        backend.readyAll(); assertFalse(MiraiGpuProfiler.beginPass(false));
        assertEquals(2, counter(MiraiDiagnostics.Counter.GPU_SAMPLES_DROPPED));
        assertEquals(0, counter(MiraiDiagnostics.Counter.GPU_SAMPLES)); assertEquals(0, backend.reads);
        assertEquals("recording", MiraiGpuProfiler.status());
    }

    @Test public void stopDefersDeletionToRenderMaintenanceWithoutReadingPendingResults()
    {
        MiraiGpuProfiler.request(10); start(false); MiraiGpuProfiler.endPass(true);
        int calls = backend.calls;
        MiraiDiagnostics.command(new String[]{"gpu", "stop"});
        assertEquals(calls, backend.calls); assertTrue(backend.deleted.isEmpty());
        MiraiGpuProfiler.maintenance();
        assertEquals(Stage.values().length * 2, backend.deleted.size()); assertEquals(0, backend.reads);
        assertEquals("off", MiraiGpuProfiler.status());
        assertTrue(MiraiDiagnostics.enabled());
    }

    @Test public void expiredCaptureIsReleasedByMenuMaintenanceWithoutAnotherPass() throws Exception
    {
        MiraiGpuProfiler.request(10); start(false); MiraiGpuProfiler.endPass(true);
        set(MiraiGpuProfiler.class, "untilNanos", System.nanoTime() - 1);
        MiraiGpuProfiler.maintenance();
        assertEquals(Stage.values().length * 2, backend.deleted.size()); assertEquals(0, backend.reads);
        assertEquals("off", MiraiGpuProfiler.status());
    }

    @Test public void requestExpiresInMenusEvenBeforeBackendBecomesAvailable() throws Exception
    {
        MiraiGpuProfiler.installBackendFactory(null); MiraiGpuProfiler.request(10);
        assertFalse(MiraiGpuProfiler.beginPass(false));
        assertEquals("waiting_for_renderer", MiraiGpuProfiler.status());
        set(MiraiGpuProfiler.class, "untilNanos", System.nanoTime() - 1);
        MiraiGpuProfiler.maintenance();
        assertEquals("off", MiraiGpuProfiler.status());
        assertEquals(0, backend.calls); assertEquals(0, backend.contextChecks);
    }

    @Test public void dedicatedServerRejectsGpuCommandBeforeRequestingNativeWork() throws Exception
    {
        session.close();
        session = new MiraiLogSession(unusedDirectory, "SERVER", 8, 4096, 1);
        set(MiraiDiagnostics.class, "session", session);
        String response = MiraiDiagnostics.command(new String[]{"gpu", "10"});
        assertTrue(response, response.toLowerCase(java.util.Locale.ROOT).contains("client"));
        assertEquals("off", MiraiGpuProfiler.status());
        assertFalse(MiraiGpuProfiler.beginPass(false));
        assertEquals(0, factoryCalls); assertEquals(0, backend.calls);
        assertTrue(MiraiDiagnostics.enabled());
    }

    @Test public void changedContextNeverDeletesOrReadsForeignQueryNames()
    {
        MiraiGpuProfiler.request(10); start(false); MiraiGpuProfiler.endPass(true);
        int calls = backend.calls; backend.current = false;
        MiraiGpuProfiler.maintenance();
        assertEquals("context_changed", MiraiGpuProfiler.status());
        assertEquals(calls, backend.calls); assertEquals(0, backend.deleted.size()); assertEquals(0, backend.reads);
        assertFalse(MiraiGpuProfiler.beginPass(false));
        MiraiGpuProfiler.stop(); MiraiGpuProfiler.maintenance();
        assertEquals(calls, backend.calls); assertEquals(0, backend.deleted.size());
        assertTrue(MiraiDiagnostics.enabled());
    }

    @Test public void reentrantPassDisablesObserverAndOuterFinallyDoesNotTouchFreedQueries()
    {
        MiraiGpuProfiler.request(10); start(false);
        int token = MiraiGpuProfiler.beginStage(Stage.OPAQUE);
        assertFalse(MiraiGpuProfiler.beginPass(true));
        assertTrue(MiraiGpuProfiler.status().startsWith("failed:IllegalStateException"));
        assertEquals(Stage.values().length * 2, backend.deleted.size());
        int calls = backend.calls;
        MiraiGpuProfiler.endStage(token); MiraiGpuProfiler.endPass(true);
        assertEquals(calls, backend.calls); assertEquals(0, counter(MiraiDiagnostics.Counter.GPU_SAMPLES));
        assertTrue(MiraiDiagnostics.enabled());
    }

    @Test public void nestedPassAfterStopOrExpiryCannotContaminateActiveOuterCapture() throws Exception
    {
        for (boolean expire : new boolean[]{false, true})
        {
            backend = new Backend(); MiraiGpuProfiler.request(10); start(false);
            int outerToken = MiraiGpuProfiler.beginStage(Stage.OPAQUE);
            if (expire) { set(MiraiGpuProfiler.class, "untilNanos", System.nanoTime() - 1); }
            else { MiraiGpuProfiler.stop(); }
            assertEquals(2, backend.stamped.size());
            assertFalse(MiraiGpuProfiler.beginPass(true));
            assertEquals(Stage.values().length * 2, backend.deleted.size());
            int callsAfterCleanup = backend.calls;
            int innerToken = MiraiGpuProfiler.beginStage(Stage.SSAO);
            assertEquals(-1, innerToken);
            MiraiGpuProfiler.endStage(innerToken);
            MiraiGpuProfiler.endStage(outerToken); MiraiGpuProfiler.endPass(true);
            assertEquals(callsAfterCleanup, backend.calls);
            assertEquals(0, backend.reads); assertEquals(0, counter(MiraiDiagnostics.Counter.GPU_SAMPLES));
            assertEquals("off", MiraiGpuProfiler.status()); assertTrue(MiraiDiagnostics.enabled());
        }
    }

    @Test public void validSamplesAndReportingStayBoundedWhenWriterCannotDrain()
    {
        MiraiGpuProfiler.request(120);
        for (int i = 0; i < 200; i++)
        {
            boolean owned = MiraiGpuProfiler.beginPass(false);
            if (owned)
            {
                int token = MiraiGpuProfiler.beginStage(Stage.OPAQUE);
                MiraiGpuProfiler.endStage(token); MiraiGpuProfiler.endPass(true); backend.readyAll();
            }
        }
        assertFalse(MiraiGpuProfiler.beginPass(false));
        assertEquals(50, counter(MiraiDiagnostics.Counter.GPU_SAMPLES));
        assertEquals(0, counter(MiraiDiagnostics.Counter.GPU_QUERY_FAILURES));
        assertEquals(Stage.values().length * 2, backend.created); assertEquals(8, session.queued()); assertTrue(session.dropped.get() > 0);
        assertEquals("recording", MiraiGpuProfiler.status());
        assertTrue(MiraiDiagnostics.enabled());
    }

    @Test public void terrainStatsAreLimitedToSampledPass() throws Exception
    {
        assertFalse(MiraiGpuProfiler.collectingPassDetails());
        MiraiGpuProfiler.terrainPassStats(true, 99, 99, 99, 99, 99, 99, 99);
        assertEquals(0, eventDetails("gpu_terrain").size());
        MiraiGpuProfiler.request(10); start(true);
        assertTrue(MiraiGpuProfiler.collectingPassDetails());
        MiraiGpuProfiler.terrainPassStats(true, 12, 10, 18, 15, 400, 600, 3);
        MiraiGpuProfiler.endPass(true);
        assertFalse(MiraiGpuProfiler.collectingPassDetails());
        List<String> details = eventDetails("gpu_terrain");
        assertEquals(1, details.size());
        assertEquals("pass=deferred capture=1 terrain=opaque candidates=12 containers=10 vbo_slots=18 draws=15 vertices=400 indices=600 max_vbos_per_container=3", details.get(0));
    }

    @Test public void sampleSchemaPairsCpuGpuAndOriginAcrossSeparateCaptures() throws Exception
    {
        for (int capture = 0; capture < 2; capture++)
        {
            MiraiGpuProfiler.request(10); start(capture == 1);
            int token = MiraiGpuProfiler.beginStage(Stage.OPAQUE);
            MiraiGpuProfiler.endStage(token); MiraiGpuProfiler.endPass(true);
            assertEquals(capture, counter(MiraiDiagnostics.Counter.GPU_SAMPLES));
            backend.readyAll(); assertFalse(MiraiGpuProfiler.beginPass(capture == 1));
            MiraiGpuProfiler.stop(); MiraiGpuProfiler.maintenance();
        }
        List<String> samples = sampleDetails();
        assertEquals(2, samples.size());
        for (int i = 0; i < samples.size(); i++)
        {
            String text = samples.get(i);
            assertTrue(text, text.startsWith(i == 0 ? "pass=main " : "pass=deferred "));
            assertTrue(text, text.contains(" capture=" + (i + 1) + " sequence=4 "));
            assertTrue(text, text.matches(".* capture_start_elapsed_ms=[0-9]+ readback_delay_ns=[0-9]+ lod_stage=true cpu_wall_ns=[0-9]+ gpu_completion_ns=PASS:3,OPAQUE:1,"));
            assertFalse(text, text.contains("SSAO:"));
            assertTrue(text.length() < MiraiLogSession.MAX_DETAIL);
        }
        assertEquals(2, counter(MiraiDiagnostics.Counter.GPU_SAMPLES));
    }

    private void start(boolean deferred)
    {
        for (int i = 0; i < 4; i++) { if (MiraiGpuProfiler.beginPass(deferred)) { return; } }
        fail("No sampled pass within cadence; status=" + MiraiGpuProfiler.status());
    }
    private long counter(MiraiDiagnostics.Counter counter) { return session.counters.get(counter.ordinal()); }
    private List<String> sampleDetails() throws Exception { return eventDetails("gpu_sample"); }
    private List<String> eventDetails(String expectedKind) throws Exception
    {
        Field queue = MiraiLogSession.class.getDeclaredField("queue"); queue.setAccessible(true);
        List<String> result = new ArrayList<>();
        for (Object entry : (Iterable<?>) queue.get(session))
        {
            Field kind = entry.getClass().getDeclaredField("kind"); kind.setAccessible(true);
            Field detail = entry.getClass().getDeclaredField("detail"); detail.setAccessible(true);
            if (expectedKind.equals(kind.get(entry))) { result.add((String) detail.get(entry)); }
        }
        return result;
    }
    private static void set(Class<?> type, String name, Object value) throws Exception
    { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(null, value); }
    private static void resetFacade() throws Exception
    {
        set(MiraiGpuProfiler.class, "factory", null); set(MiraiGpuProfiler.class, "ring", null);
        set(MiraiGpuProfiler.class, "backend", null); set(MiraiGpuProfiler.class, "untilNanos", 0L);
        set(MiraiGpuProfiler.class, "status", "off"); set(MiraiGpuProfiler.class, "passInvalid", false);
        set(MiraiGpuProfiler.class, "sampledPassActive", false); set(MiraiGpuProfiler.class, "sampledPassDeferred", false);
        set(MiraiGpuProfiler.class, "captureId", 0L);
    }
    private static final class Backend extends MiraiGpuQueryRingTest.FakeBackend implements MiraiGpuProfiler.ContextBackend
    {
        boolean current = true, failNativeTimestamp;
        int bits = 64, contextChecks;
        @Override public boolean isCurrentContext() { contextChecks++; return current; }
        @Override public int counterBits() { calls++; assertTrue(current); return bits; }
        @Override public void timestamp(int query)
        {
            assertTrue("Timestamp issued in a foreign context", current); super.timestamp(query);
            if (failNativeTimestamp) { throw new UnsatisfiedLinkError("synthetic-native-query-failure"); }
        }
        @Override public void delete(int query) { assertTrue("Query deleted in a foreign context", current); super.delete(query); }
        @Override public boolean available(int query) { assertTrue("Query polled in a foreign context", current); return super.available(query); }
        @Override public long result(int query) { assertTrue("Query read in a foreign context", current); return super.result(query); }
    }
}
