package com.seibel.distanthorizons.core.network;

import java.util.concurrent.atomic.AtomicBoolean;

/** Counts queued AND executing messages without maintaining a second task queue. */
public final class NetworkIngressBudget
{
    private final int maxMessages;
    private final long maxBytes;
    private int messages;
    private long bytes;

    public NetworkIngressBudget(int maxMessages, long maxBytes)
    {
        if (maxMessages < 1 || maxBytes < 1)
        {
            throw new IllegalArgumentException("Invalid ingress budget");
        }
        this.maxMessages = maxMessages;
        this.maxBytes = maxBytes;
    }

    public synchronized Lease tryAcquire(long bytes)
    {
        if (bytes < 0 || bytes > this.maxBytes || this.messages >= this.maxMessages
                || bytes > this.maxBytes - this.bytes)
        {
            return null;
        }
        this.messages++;
        this.bytes += bytes;
        return new Lease(bytes);
    }

    public synchronized int getMessages() { return this.messages; }
    public synchronized long getBytes() { return this.bytes; }

    public final class Lease implements AutoCloseable
    {
        private final long bytes;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(long bytes) { this.bytes = bytes; }

        @Override
        public void close()
        {
            if (this.closed.compareAndSet(false, true))
            {
                synchronized (NetworkIngressBudget.this)
                {
                    NetworkIngressBudget.this.messages--;
                    NetworkIngressBudget.this.bytes -= this.bytes;
                }
            }
        }
    }
}
