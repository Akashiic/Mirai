package com.seibel.distanthorizons.common.wrappers.worldGeneration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Owns platform tickets without overriding their configured capacity.
 * Every operation, including close, belongs to the world/server thread.
 * Chunk reference counts are owned by the caller; each position enters once.
 */
public final class ChunkTicketPool<P, T> implements AutoCloseable
{
    private final Supplier<T> createTicket;
    private final ToIntFunction<T> capacity;
    private final BiConsumer<T, P> force;
    private final BiConsumer<T, P> unforce;
    private final Consumer<T> release;
    private final Map<T, Integer> used = new LinkedHashMap<>();
    private final Map<P, T> byPosition = new LinkedHashMap<>();
    private boolean closed;

    public ChunkTicketPool(Supplier<T> createTicket, ToIntFunction<T> capacity,
            BiConsumer<T, P> force, BiConsumer<T, P> unforce, Consumer<T> release)
    {
        this.createTicket = createTicket;
        this.capacity = capacity;
        this.force = force;
        this.unforce = unforce;
        this.release = release;
    }

    public void acquire(P position)
    {
        if (this.closed) throw new CancellationException("Chunk ticket pool is closed.");
        if (this.byPosition.containsKey(position)) throw new IllegalStateException("Chunk already owns a ticket.");
        T ticket = null;
        for (Map.Entry<T, Integer> entry : this.used.entrySet())
        {
            if (entry.getValue() < this.capacity.applyAsInt(entry.getKey()))
            {
                ticket = entry.getKey();
                break;
            }
        }
        if (ticket == null)
        {
            ticket = this.createTicket.get();
            if (ticket == null) throw new IllegalStateException("Forge denied a required Mirai chunk ticket; generation was not retained.");
            this.used.put(ticket, 0);
            try
            {
                if (this.capacity.applyAsInt(ticket) <= 0)
                    throw new IllegalStateException("Forge granted a chunk ticket with no usable capacity.");
            }
            catch (RuntimeException | Error failure)
            {
                try { this.release.accept(ticket); this.used.remove(ticket); }
                catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        // Publish before force, so a partly successful platform call can be rolled back.
        this.byPosition.put(position, ticket);
        this.used.put(ticket, this.used.get(ticket) + 1);
        try { this.force.accept(ticket, position); }
        catch (RuntimeException | Error failure)
        {
            try { this.releasePosition(position); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public void releasePosition(P position)
    {
        T ticket = this.byPosition.get(position);
        if (ticket == null) return;
        // If unforce fails, retain ownership so final close can retry the whole ticket.
        this.unforce.accept(ticket, position);
        this.byPosition.remove(position);
        int remaining = this.used.get(ticket) - 1;
        this.used.put(ticket, remaining);
        if (remaining == 0)
        {
            this.release.accept(ticket);
            this.used.remove(ticket);
        }
    }

    @Override
    public void close()
    {
        this.closed = true;
        Throwable first = null;
        for (T ticket : new ArrayList<>(this.used.keySet()))
        {
            try
            {
                this.release.accept(ticket);
                this.used.remove(ticket);
                this.byPosition.values().removeIf(value -> value == ticket);
            }
            catch (RuntimeException | Error failure)
            {
                if (first == null) first = failure;
                else if (first != failure) first.addSuppressed(failure);
            }
        }
        if (first instanceof Error) throw (Error) first;
        if (first != null) throw (RuntimeException) first;
    }
}
