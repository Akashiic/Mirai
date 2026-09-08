package com.seibel.distanthorizons.forge17.network;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

/** Bounded raw ingress for operations that must execute on the server tick. */
public final class BoundedPacketQueue<P>
{
    public static final int MAX_PENDING = 512;
    public static final int MAX_PENDING_PER_PEER = 64;
    public static final int MAX_PEERS = 1024;
    public static final long MAX_AGE_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final double BURST = 512;
    private static final double PER_SECOND = 256;

    private final ArrayDeque<Entry<P>> pending = new ArrayDeque<>();
    // Values deliberately do not reference keys. Disconnects also remove them
    // explicitly, while a missed disconnect cannot permanently retain players.
    private final Map<P, PeerState> peers = new WeakHashMap<>();

    public synchronized boolean offer(P peer, byte[] frame, String rejection, long now)
    {
        PeerState state = this.peers.get(peer);
        if (state == null)
        {
            if (this.peers.size() >= MAX_PEERS)
            {
                return false;
            }
            state = new PeerState(now);
            this.peers.put(peer, state);
        }
        if (state.rejected)
        {
            return false;
        }

        state.tokens = Math.min(BURST, state.tokens
                + Math.max(0L, now - state.lastRefill) * PER_SECOND / 1_000_000_000D);
        state.lastRefill = now;
        if (rejection == null && (frame == null || frame.length < 1
                || frame.length > LegacyPacketCodec.MAX_SERVERBOUND_BYTES))
        {
            rejection = "Invalid serverbound frame size";
        }
        if (rejection == null && (state.tokens < 1 || state.pending >= MAX_PENDING_PER_PEER
                || this.pending.size() >= MAX_PENDING))
        {
            rejection = "Serverbound plugin ingress limit exceeded";
        }
        if (rejection != null)
        {
            this.reject(peer, state);
            if (this.pending.size() < MAX_PENDING)
            {
                this.pending.add(new Entry<>(peer, null, rejection, now));
                state.pending++;
            }
            return false;
        }
        state.tokens--;
        state.pending++;
        this.pending.add(new Entry<>(peer, frame, null, now));
        return true;
    }

    public synchronized Entry<P> poll(long now)
    {
        Entry<P> entry = this.pending.poll();
        if (entry == null)
        {
            return null;
        }
        PeerState state = this.peers.get(entry.peer);
        if (state != null)
        {
            state.pending--;
            if (entry.rejection == null && now - entry.enqueuedAt >= MAX_AGE_NANOS)
            {
                this.reject(entry.peer, state);
                return new Entry<>(entry.peer, null, "Serverbound plugin frame expired", now);
            }
        }
        return entry;
    }

    /** Disable this peer's DH ingress after a decoder error; one close event suffices. */
    public synchronized void reject(P peer)
    {
        PeerState state = this.peers.get(peer);
        if (state != null)
        {
            this.reject(peer, state);
        }
    }

    private void reject(P peer, PeerState state)
    {
        state.rejected = true;
        this.removePending(peer);
        state.pending = 0;
    }

    public synchronized void disconnect(P peer)
    {
        this.removePending(peer);
        this.peers.remove(peer);
    }

    public synchronized void clear()
    {
        this.pending.clear();
        this.peers.clear();
    }

    public synchronized int size() { return this.pending.size(); }

    private void removePending(P peer)
    {
        Iterator<Entry<P>> iterator = this.pending.iterator();
        while (iterator.hasNext())
        {
            if (iterator.next().peer == peer)
            {
                iterator.remove();
            }
        }
    }

    public static final class Entry<P>
    {
        public final P peer;
        public final byte[] frame;
        public final String rejection;
        private final long enqueuedAt;

        private Entry(P peer, byte[] frame, String rejection, long enqueuedAt)
        {
            this.peer = peer;
            this.frame = frame;
            this.rejection = rejection;
            this.enqueuedAt = enqueuedAt;
        }
    }

    private static final class PeerState
    {
        private int pending;
        private boolean rejected;
        private double tokens = BURST;
        private long lastRefill;
        private PeerState(long now) { this.lastRefill = now; }
    }
}
