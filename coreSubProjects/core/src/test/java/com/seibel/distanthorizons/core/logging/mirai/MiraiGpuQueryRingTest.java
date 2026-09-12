package com.seibel.distanthorizons.core.logging.mirai;

import com.seibel.distanthorizons.core.logging.mirai.MiraiGpuQueryRing.Stage;
import org.junit.Test;
import java.util.*;
import java.util.function.LongSupplier;
import static org.junit.Assert.*;

/** Strict GL_TIMESTAMP fake: availability of a later query makes prior timestamps available. */
public class MiraiGpuQueryRingTest
{
    @Test public void disabledCadenceAllocatesNothingAndBalancesNoOpStages()
    {
        Fixture f = new Fixture();
        assertEquals(0, f.backend.created);
        for (int i = 0; i < 3; i++)
        {
            assertFalse(f.ring.beginPass(false));
            assertEquals(-1, f.ring.beginStage(Stage.OPAQUE));
            f.ring.endStage(-1); f.ring.endPass(true);
        }
        assertEquals(0, f.backend.calls);
        f.ring.close(); f.ring.close();
        assertFalse(f.ring.beginPass(false));
        assertEquals(0, f.backend.calls);
    }

    @Test public void mainAndDeferredUseIndependentCadence()
    {
        Fixture f = new Fixture();
        for (int i = 1; i <= 4; i++)
        {
            for (boolean deferred : new boolean[]{false, true})
            {
                assertEquals(i == 4, f.ring.beginPass(deferred));
                f.ring.endPass(true);
            }
        }
        assertEquals(Stage.values().length * 2 * 2, f.backend.created);
        assertTrue(f.sink.samples.isEmpty());
        f.backend.readyAll();
        assertFalse(f.ring.beginPass(false));
        assertEquals(2, f.sink.samples.size());
        assertFalse(f.sink.samples.get(0).deferred);
        assertTrue(f.sink.samples.get(1).deferred);
        assertEquals(7, f.sink.samples.get(0).sequence);
        assertEquals(8, f.sink.samples.get(1).sequence);
        f.ring.close();
    }

    @Test public void resultIsDeferredUntilClosingTimestampMakesEarlierQueriesAvailable()
    {
        Fixture f = new Fixture();
        f.start(false);
        int token = f.ring.beginStage(Stage.OPAQUE);
        f.ring.endStage(token); f.clock.now = 50; f.ring.endPass(true);
        assertEquals(0, f.backend.reads);
        f.backend.readyAll();
        int delayed = f.backend.stamped.get(f.backend.stamped.size() - 1);
        f.backend.queries.get(delayed).ready = false;
        for (int i = 0; i < 20; i++)
        {
            boolean owned = f.ring.beginPass(true);
            if (owned) { f.ring.endPass(false); }
        }
        assertEquals(0, f.backend.reads);
        assertTrue(f.sink.samples.isEmpty());
        f.backend.queries.get(delayed).ready = true;
        f.ring.beginPass(false);
        assertEquals(1, f.sink.samples.size());
        Sample sample = f.sink.samples.get(0);
        assertEquals(50, sample.cpu);
        assertEquals(3, sample.durations[Stage.PASS.ordinal()]);
        assertEquals(1, sample.durations[Stage.OPAQUE.ordinal()]);
        assertEquals(-1, sample.durations[Stage.SSAO.ordinal()]);
        assertEquals(4, f.backend.reads);
        f.ring.close();
    }

    @Test public void ringExhaustionDropsWithoutWaitingGrowingOrReusingPendingQueries()
    {
        Fixture f = new Fixture();
        for (int packet = 0; packet < 6; packet++) { f.start(false); f.ring.endPass(true); }
        assertEquals(Stage.values().length * 2 * 6, f.backend.created);
        for (int i = 0; i < 4; i++)
        {
            int polls = f.backend.polls;
            assertFalse(f.ring.beginPass(false));
            assertTrue(f.backend.polls - polls <= 6);
        }
        assertEquals(Collections.singletonList("ring-full"), f.sink.drops);
        assertEquals(Stage.values().length * 2 * 6, f.backend.created);
        assertEquals(0, f.backend.reads);
        f.backend.readyAll();
        assertFalse(f.ring.beginPass(false));
        assertEquals(6, f.sink.samples.size());
        f.start(false); f.ring.endPass(true);
        assertEquals(Stage.values().length * 2 * 6, f.backend.created);
        f.ring.close();
        assertEquals(Stage.values().length * 2 * 6, f.backend.deleted.size());
    }

    @Test public void distinctNestedStagesKeepIndependentIntervals()
    {
        Fixture f = new Fixture(); f.start(false);
        int opaque = f.ring.beginStage(Stage.OPAQUE);
        int ssao = f.ring.beginStage(Stage.SSAO);
        f.ring.endStage(ssao); f.ring.endStage(opaque); f.ring.endPass(true);
        f.backend.readyAll(); f.ring.beginPass(false);
        long[] duration = f.sink.samples.get(0).durations;
        assertEquals(5, duration[Stage.PASS.ordinal()]);
        assertEquals(3, duration[Stage.OPAQUE.ordinal()]);
        assertEquals(1, duration[Stage.SSAO.ordinal()]);
        f.ring.close();
    }

    @Test public void ssaoSubStagesNestInsideTheOuterSsaoInterval()
    {
        Fixture f = new Fixture(); f.start(false);
        int ssao = f.ring.beginStage(Stage.SSAO);
        int raw = f.ring.beginStage(Stage.SSAO_RAW);
        f.ring.endStage(raw);
        int apply = f.ring.beginStage(Stage.SSAO_APPLY);
        f.ring.endStage(apply);
        f.ring.endStage(ssao); f.ring.endPass(true);
        f.backend.readyAll(); f.ring.beginPass(false);
        long[] duration = f.sink.samples.get(0).durations;
        assertEquals(7, duration[Stage.PASS.ordinal()]);
        assertEquals(5, duration[Stage.SSAO.ordinal()]);
        assertEquals(1, duration[Stage.SSAO_RAW.ordinal()]);
        assertEquals(1, duration[Stage.SSAO_APPLY.ordinal()]);
        f.ring.close();
    }

    @Test public void abortedAndUnbalancedPassesDrainWithoutReadingOrPublishing()
    {
        for (boolean completed : new boolean[]{false, true})
        {
            Fixture f = new Fixture(); f.start(false); f.ring.beginStage(Stage.OPAQUE);
            f.ring.endPass(completed);
            assertEquals(completed ? "unclosed-stage" : "incomplete-pass", f.sink.drops.get(0));
            f.ring.beginPass(false);
            assertEquals(0, f.backend.reads);
            f.backend.readyAll(); f.ring.beginPass(false);
            assertEquals(0, f.backend.reads);
            assertTrue(f.sink.samples.isEmpty());
            f.start(false); f.ring.endPass(true);
            assertEquals(Stage.values().length * 2, f.backend.created);
            f.ring.close();
        }
    }

    @Test public void exceptionInObservedWorkCanCloseStageAndAbortInFinally()
    {
        Fixture f = new Fixture(); f.start(false);
        boolean completed = false;
        try
        {
            int token = f.ring.beginStage(Stage.OPAQUE);
            try { throw new IllegalArgumentException("render-work-failed"); }
            finally { f.ring.endStage(token); }
        }
        catch (IllegalArgumentException expected) { assertEquals("render-work-failed", expected.getMessage()); }
        finally { f.ring.endPass(completed); }
        f.backend.readyAll(); f.ring.beginPass(false);
        assertTrue(f.sink.samples.isEmpty()); assertEquals(0, f.backend.reads);
        assertEquals(Collections.singletonList("incomplete-pass"), f.sink.drops);
        f.ring.close();
    }

    @Test public void reentryThrowsAndCloseMakesOutstandingFinallyCallsHarmless()
    {
        Fixture f = new Fixture(); f.start(false);
        int token = f.ring.beginStage(Stage.OPAQUE);
        expect(IllegalStateException.class, () -> f.ring.beginPass(true));
        f.ring.close(); f.ring.endStage(token); f.ring.endPass(true); f.ring.close();
        assertFalse(f.ring.beginPass(false));
        assertEquals(Stage.values().length * 2, f.backend.deleted.size());
        assertEquals(0, f.backend.reads); assertTrue(f.sink.samples.isEmpty());
    }

    @Test public void duplicateAndStaleTokensNeverOverwriteQueryObjects()
    {
        Fixture f = new Fixture(); f.start(false);
        int old = f.ring.beginStage(Stage.OPAQUE); f.ring.endStage(old);
        expect(IllegalStateException.class, () -> f.ring.endStage(old));
        expect(IllegalStateException.class, () -> f.ring.beginStage(Stage.OPAQUE));
        expect(IllegalArgumentException.class, () -> f.ring.beginStage(Stage.PASS));
        f.ring.endPass(true); f.backend.readyAll(); f.start(false);
        int current = f.ring.beginStage(Stage.OPAQUE);
        assertNotEquals(old, current);
        expect(IllegalStateException.class, () -> f.ring.endStage(old));
        f.ring.endStage(current); f.ring.endPass(true); f.ring.close();
    }

    @Test public void zeroTimestampsAndSignedAndUnsigned64WrapAreValid()
    {
        for (long[] pair : new long[][]{{0, 0, 0}, {Long.MAX_VALUE - 3, Long.MIN_VALUE + 3, 7}, {-3, 3, 6}})
        {
            Fixture f = new Fixture(); f.start(false); f.ring.endPass(true);
            f.backend.setValue(0, pair[0]); f.backend.setValue(1, pair[1]);
            f.backend.readyAll(); f.ring.beginPass(false);
            assertEquals(pair[2], f.sink.samples.get(0).durations[0]);
            assertTrue(f.sink.drops.isEmpty()); f.ring.close();
        }
    }

    @Test public void negativeDeltaDropsPacketInsteadOfPublishingInvalidMetric()
    {
        Fixture f = new Fixture(); f.start(false); f.ring.endPass(true);
        f.backend.setValue(0, 100); f.backend.setValue(1, 99);
        f.backend.readyAll(); f.ring.beginPass(false);
        assertTrue(f.sink.samples.isEmpty());
        assertEquals(Collections.singletonList("invalid-gpu-duration"), f.sink.drops);
        f.ring.close();
    }

    @Test public void delayedReadPreservesCaptureOriginAndSeparatesReadDelay()
    {
        Fixture f = new Fixture(); f.clock.now = 100; f.start(false);
        f.clock.now = 160; f.ring.endPass(true);
        f.clock.now = 500; f.ring.beginPass(false);
        assertTrue(f.sink.samples.isEmpty());
        f.clock.now = 900; f.backend.readyAll(); f.ring.beginPass(false);
        Sample sample = f.sink.samples.get(0);
        assertEquals(4, sample.sequence); assertEquals(100, sample.started);
        assertEquals(60, sample.cpu); assertEquals(740, sample.readDelay);
        f.ring.close();
    }

    @Test public void negativeReadDelayDoesNotPublishOrReadGpuResults()
    {
        Fixture f = new Fixture(); f.clock.now = 100; f.start(false);
        f.clock.now = 200; f.ring.endPass(true);
        f.clock.now = 199; f.backend.readyAll(); f.ring.beginPass(false);
        assertTrue(f.sink.samples.isEmpty()); assertEquals(0, f.backend.reads);
        assertEquals(Collections.singletonList("invalid-read-delay"), f.sink.drops);
        f.ring.close();
    }

    @Test public void cpuClockWrapIsSupportedAndNegativeCpuDeltaIsDiscarded()
    {
        Fixture wrap = new Fixture(); wrap.clock.now = Long.MAX_VALUE - 2; wrap.start(false);
        wrap.clock.now = Long.MIN_VALUE + 2; wrap.ring.endPass(true);
        wrap.backend.readyAll(); wrap.ring.beginPass(false);
        assertEquals(5, wrap.sink.samples.get(0).cpu); wrap.ring.close();
        Fixture backwards = new Fixture(); backwards.clock.now = 20; backwards.start(false);
        backwards.clock.now = 19; backwards.ring.endPass(true);
        backwards.backend.readyAll(); backwards.ring.beginPass(false);
        assertTrue(backwards.sink.samples.isEmpty()); assertEquals(0, backwards.backend.reads);
        assertEquals(Collections.singletonList("invalid-cpu-duration"), backwards.sink.drops);
        backwards.ring.close();
    }

    @Test public void partialCreationCleansAllNamesAndPreservesPrimaryFailure()
    {
        Fixture f = new Fixture(); f.backend.failCreateAt = 4; f.backend.failDelete = 2;
        RuntimeException failure = expect(RuntimeException.class, () -> f.start(false));
        assertEquals("create-failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(new HashSet<>(Arrays.asList(1, 2, 3)), f.backend.deleted);
        f.ring.close(); f.ring.close();
        assertEquals(3, f.backend.deleted.size());
    }

    @Test public void zeroQueryIdFailsAndCleansEarlierNames()
    {
        Fixture f = new Fixture(); f.backend.zeroCreateAt = 3;
        expect(IllegalStateException.class, () -> f.start(false));
        assertEquals(new HashSet<>(Arrays.asList(1, 2)), f.backend.deleted);
        f.ring.close();
    }

    @Test public void backendFailureAtSubmissionAvailabilityOrReadLeavesAllNamesCloseable()
    {
        for (String operation : Arrays.asList("timestamp", "available", "result"))
        {
            Fixture f = new Fixture();
            if (operation.equals("timestamp"))
            {
                f.backend.failOperation = operation;
                expect(RuntimeException.class, () -> f.start(false));
            }
            else
            {
                f.start(false); f.ring.endPass(true); f.backend.readyAll();
                f.backend.failOperation = operation;
                expect(RuntimeException.class, () -> f.ring.beginPass(false));
            }
            f.ring.close(); assertEquals(Stage.values().length * 2, f.backend.deleted.size());
            assertTrue(f.sink.samples.isEmpty());
        }
    }

    @Test public void closeAttemptsEveryDeleteDespiteBackendFailureAndNeverReadsPending()
    {
        Fixture f = new Fixture(); f.start(false); f.ring.endPass(true);
        f.start(true); f.ring.beginStage(Stage.OPAQUE);
        f.backend.failDelete = 1;
        expect(RuntimeException.class, f.ring::close);
        assertEquals(Stage.values().length * 2 * 2, f.backend.deleted.size()); assertEquals(0, f.backend.reads);
        f.ring.close(); assertEquals(Stage.values().length * 2 * 2, f.backend.deleted.size());
    }

    @Test public void publishedArraysAreNotReusedAndSinkFailureDoesNotLeakNames()
    {
        Fixture f = new Fixture(); f.start(false); f.ring.endPass(true);
        f.backend.readyAll(); f.ring.beginPass(false);
        long[] first = f.sink.samples.get(0).durations; first[0] = 123456;
        f.start(false); f.ring.endPass(true); f.backend.readyAll();
        f.sink.failSample = true;
        expect(RuntimeException.class, () -> f.ring.beginPass(false));
        assertEquals(123456, first[0]);
        f.ring.close(); assertEquals(Stage.values().length * 2, f.backend.deleted.size());
    }

    static <T extends Throwable> T expect(Class<T> type, Runnable action)
    {
        try { action.run(); }
        catch (Throwable failure)
        {
            if (type.isInstance(failure)) { return type.cast(failure); }
            throw new AssertionError("Unexpected exception", failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static final class Clock implements LongSupplier
    {
        long now;
        @Override public long getAsLong() { return now; }
    }
    private static final class Fixture
    {
        final FakeBackend backend = new FakeBackend();
        final RecordingSink sink = new RecordingSink();
        final Clock clock = new Clock();
        final MiraiGpuQueryRing ring = new MiraiGpuQueryRing(backend, sink, clock);
        void start(boolean deferred)
        {
            for (int i = 0; i < 4; i++) { if (ring.beginPass(deferred)) { return; } }
            fail("No sampled pass within its four-invocation cadence");
        }
    }
    private static final class Sample
    {
        final boolean deferred;
        final long cpu;
        final long sequence, started, readDelay;
        final long[] durations;
        Sample(boolean deferred, long sequence, long started, long cpu, long readDelay, long[] durations)
        { this.deferred = deferred; this.sequence = sequence; this.started = started; this.cpu = cpu; this.readDelay = readDelay; this.durations = durations; }
    }
    private static final class RecordingSink implements MiraiGpuQueryRing.Sink
    {
        final List<Sample> samples = new ArrayList<>();
        final List<String> drops = new ArrayList<>();
        boolean failSample;
        @Override public void sample(boolean deferred, long sequence, long started, long cpu, long readDelay, long[] durations)
        {
            if (failSample) { throw new RuntimeException("sink-failed"); }
            samples.add(new Sample(deferred, sequence, started, cpu, readDelay, durations));
        }
        @Override public void dropped(String reason) { drops.add(reason); }
    }
    static class FakeBackend implements MiraiGpuQueryRing.Backend
    {
        final Map<Integer, Query> queries = new LinkedHashMap<>();
        final Set<Integer> deleted = new HashSet<>();
        final List<Integer> stamped = new ArrayList<>();
        int calls, created, polls, reads, failCreateAt, zeroCreateAt, failDelete;
        String failOperation;
        long sequence;
        @Override public int create()
        {
            calls++; created++;
            if (created == failCreateAt) { throw new RuntimeException("create-failed"); }
            if (created == zeroCreateAt) { return 0; }
            queries.put(created, new Query()); return created;
        }
        @Override public void delete(int query)
        {
            calls++; assertTrue("Query deleted twice", deleted.add(query));
            assertNotNull(queries.remove(query));
            if (query == failDelete) { throw new RuntimeException("delete-failed"); }
        }
        @Override public void timestamp(int query)
        {
            calls++; Query state = object(query);
            assertTrue("Pending query overwritten", !state.issued || state.ready);
            state.issued = true; state.ready = false; state.checked = false; state.order = sequence++; state.value = state.order;
            stamped.add(query); failIf("timestamp");
        }
        @Override public boolean available(int query)
        {
            calls++; polls++; Query state = object(query); assertTrue(state.issued);
            failIf("available");
            if (state.ready)
            {
                for (Query prior : queries.values())
                { if (prior.issued && prior.order <= state.order) { prior.ready = true; prior.checked = true; } }
            }
            return state.ready;
        }
        @Override public long result(int query)
        {
            calls++; reads++; Query state = object(query);
            assertTrue("Unavailable/unchecked result read", state.issued && state.ready && state.checked);
            failIf("result"); return state.value;
        }
        void readyAll() { for (Query query : queries.values()) { if (query.issued) { query.ready = true; } } }
        void setValue(int stampIndex, long value) { object(stamped.get(stampIndex)).value = value; }
        private Query object(int query) { Query result = queries.get(query); assertNotNull("Unknown query " + query, result); return result; }
        private void failIf(String operation) { if (operation.equals(failOperation)) { throw new RuntimeException(operation + "-failed"); } }
        static final class Query { boolean issued, ready, checked; long value, order; }
    }
}
