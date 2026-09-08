/* Akashic diagnostic instrumentation; LGPL-3.0, like the surrounding DH core. */
package com.seibel.distanthorizons.core.logging.mirai;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Bounded, render-thread-only timestamp observation. The owner supplies a current GL context,
 * a supported 64-bit timestamp backend, and handles backend failures by closing this ring.
 * No result is requested before availability, and pending query names are never reused.
 * GPU intervals describe completion timestamps, including possible command-stream starvation;
 * they are not a measurement of exclusive GPU utilization.
 */
public final class MiraiGpuQueryRing implements AutoCloseable
{
    public enum Stage { PASS, TASKS, OPAQUE, GENERIC_SSAO, SSAO, GENERIC, TRANSPARENT, FOG, FAR_FADE, AA, COMPOSITE }

    /** GL_TIMESTAMP ordering: availability of a later timestamp guarantees earlier timestamps are available. */
    public interface Backend
    {
        int create();
        void delete(int query);
        void timestamp(int query);
        boolean available(int query);
        long result(int query);
    }

    public interface Sink
    {
        /** Array indices are Stage ordinals; -1 denotes an absent stage. The array is owned by the sink. */
        void sample(boolean deferred, long passSequence, long passStartNanos, long passCpuNanos,
                    long readDelayNanos, long[] durationsNanos);
        void dropped(String reason);
    }

    private static final int SLOT_COUNT = 6;
    private static final int SAMPLE_INTERVAL = 4;
    private static final int STAGE_COUNT = Stage.values().length;
    private final Backend backend;
    private final Sink sink;
    private final LongSupplier clock;
    private final Slot[] slots = new Slot[SLOT_COUNT];
    private final int[] cadence = new int[2];
    private Slot active;
    private long passSequence;
    private int nextToken;
    private boolean closed;

    public MiraiGpuQueryRing(Backend backend, Sink sink)
    {
        this(backend, sink, System::nanoTime);
    }

    /** Clock injection keeps the state machine testable without a GL context or wall-clock sleeps. */
    public MiraiGpuQueryRing(Backend backend, Sink sink, LongSupplier clock)
    {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.clock = Objects.requireNonNull(clock, "clock");
        for (int i = 0; i < this.slots.length; i++) { this.slots[i] = new Slot(); }
    }

    /**
     * Polls older packets once, then samples every fourth invocation of each pass type separately.
     * Returns ownership of a capture which must be balanced with endPass in a finally block.
     * Reentry is an instrumentation error: the owner must disable/close, rather than time nested
     * renderer calls as if their stages belonged to the outer pass.
     */
    public boolean beginPass(boolean deferred)
    {
        if (this.closed) { return false; }
        if (this.active != null) { throw new IllegalStateException("GPU capture pass reentry"); }
        this.passSequence++;
        this.poll();
        int kind = deferred ? 1 : 0;
        if (++this.cadence[kind] < SAMPLE_INTERVAL) { return false; }
        this.cadence[kind] = 0;
        Slot slot = null;
        for (Slot candidate : this.slots)
        {
            if (!candidate.pending) { slot = candidate; break; }
        }
        if (slot == null)
        {
            this.sink.dropped("ring-full");
            return false;
        }
        this.initialize(slot);
        Arrays.fill(slot.stageState, 0);
        Arrays.fill(slot.tokens, 0);
        slot.deferred = deferred;
        slot.passSequence = this.passSequence;
        slot.publish = false;
        slot.cpuStart = this.clock.getAsLong();
        this.active = slot;
        slot.stageState[Stage.PASS.ordinal()] = 1;
        this.timestamp(slot, 0);
        return true;
    }

    /** Distinct stages may overlap. Each stage can be recorded only once per captured pass. */
    public int beginStage(Stage stage)
    {
        if (this.closed || this.active == null) { return -1; }
        Objects.requireNonNull(stage, "stage");
        if (stage == Stage.PASS) { throw new IllegalArgumentException("PASS is owned by beginPass/endPass"); }
        int index = stage.ordinal();
        if (this.active.stageState[index] != 0) { throw new IllegalStateException("GPU stage already recorded: " + stage); }
        if (this.nextToken == Integer.MAX_VALUE) { this.nextToken = 0; }
        int token = ++this.nextToken;
        this.active.tokens[index] = token;
        this.active.stageState[index] = 1;
        this.timestamp(this.active, index * 2);
        return token;
    }

    public void endStage(int token)
    {
        if (token < 0 || this.closed || this.active == null) { return; }
        for (int stage = 1; stage < STAGE_COUNT; stage++)
        {
            if (this.active.tokens[stage] == token && this.active.stageState[stage] == 1)
            {
                this.timestamp(this.active, stage * 2 + 1);
                this.active.stageState[stage] = 2;
                return;
            }
        }
        throw new IllegalStateException("GPU stage token is stale or already closed");
    }

    /**
     * Closes the capture without polling its results. Aborted or unbalanced captures remain
     * pending until their closing timestamp is available, then are recycled without reading.
     */
    public void endPass(boolean completed)
    {
        if (this.closed || this.active == null) { return; }
        Slot slot = this.active;
        this.timestamp(slot, 1);
        slot.stageState[0] = 2;
        slot.cpuEnd = this.clock.getAsLong();
        slot.cpuElapsed = slot.cpuEnd - slot.cpuStart;
        boolean balanced = true;
        for (int state : slot.stageState) { if (state == 1) { balanced = false; break; } }
        slot.publish = completed && balanced && slot.cpuElapsed >= 0;
        slot.pending = true;
        this.active = null;
        if (!slot.publish)
        {
            this.sink.dropped(!completed ? "incomplete-pass" : !balanced ? "unclosed-stage" : "invalid-cpu-duration");
        }
    }

    private void timestamp(Slot slot, int index)
    {
        // Query names remain owned even when an operation throws after submitting work.
        this.backend.timestamp(slot.queries[index]);
    }

    private void poll()
    {
        for (Slot slot : this.slots)
        {
            if (!slot.pending) { continue; }
            // PASS end is emitted last. ARB_timer_query guarantees that availability of this
            // timestamp implies availability of all earlier timestamps of the same type.
            // Poll exactly once per pending packet; never spin, flush, or request a waiting result.
            if (!this.backend.available(slot.queries[1])) { continue; }
            if (!slot.publish) { slot.pending = false; continue; }
            long readDelay = this.clock.getAsLong() - slot.cpuEnd;
            if (readDelay < 0)
            {
                slot.pending = false;
                this.sink.dropped("invalid-read-delay");
                continue;
            }
            long[] durations = new long[STAGE_COUNT];
            Arrays.fill(durations, -1);
            boolean valid = true;
            for (int stage = 0; stage < STAGE_COUNT; stage++)
            {
                if (slot.stageState[stage] != 2) { continue; }
                long start = this.backend.result(slot.queries[stage * 2]);
                long end = this.backend.result(slot.queries[stage * 2 + 1]);
                // Modulo-64 subtraction also handles the signed MAX_VALUE/MIN_VALUE boundary.
                // A negative delta cannot represent a supported short capture interval.
                long duration = end - start;
                if (duration < 0) { valid = false; break; }
                durations[stage] = duration;
            }
            slot.pending = false;
            if (valid) { this.sink.sample(slot.deferred, slot.passSequence, slot.cpuStart, slot.cpuElapsed, readDelay, durations); }
            else { this.sink.dropped("invalid-gpu-duration"); }
        }
    }

    private void initialize(Slot slot)
    {
        if (slot.queries[0] != 0) { return; }
        try
        {
            for (int i = 0; i < slot.queries.length; i++)
            {
                int query = this.backend.create();
                if (query == 0) { throw new IllegalStateException("GPU backend returned query zero"); }
                slot.queries[i] = query;
            }
        }
        catch (RuntimeException | Error failure)
        {
            this.deleteQueries(slot, failure);
            throw failure;
        }
    }

    /** Deletes all owned names, including pending queries, without querying or waiting for them. */
    @Override
    public void close()
    {
        if (this.closed) { return; }
        this.closed = true;
        this.active = null;
        Throwable failure = null;
        for (Slot slot : this.slots) { failure = this.deleteQueries(slot, failure); }
        if (failure instanceof RuntimeException) { throw (RuntimeException) failure; }
        if (failure instanceof Error) { throw (Error) failure; }
    }

    private Throwable deleteQueries(Slot slot, Throwable failure)
    {
        slot.pending = false;
        for (int i = 0; i < slot.queries.length; i++)
        {
            int query = slot.queries[i];
            slot.queries[i] = 0;
            if (query == 0) { continue; }
            try { this.backend.delete(query); }
            catch (RuntimeException | Error deletionFailure)
            {
                if (failure == null) { failure = deletionFailure; }
                else if (failure != deletionFailure) { failure.addSuppressed(deletionFailure); }
            }
        }
        return failure;
    }

    private static final class Slot
    {
        final int[] queries = new int[STAGE_COUNT * 2];
        final int[] stageState = new int[STAGE_COUNT];
        final int[] tokens = new int[STAGE_COUNT];
        boolean pending;
        boolean publish;
        boolean deferred;
        long passSequence;
        long cpuStart;
        long cpuEnd;
        long cpuElapsed;
    }
}
