package com.seibel.distanthorizons.core.render;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode;
import com.seibel.distanthorizons.core.config.Config;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

/**
 * Mirai policy for making rendererMode=DISABLED a real low-impact client mode.
 * It never changes the configured renderer mode and is a no-op when rendering is enabled.
 */
public final class MiraiRendererQuiescence
{
    private static final boolean ENABLED = !"false".equalsIgnoreCase(
            System.getProperty("akashic.mirai.disabledRendererQuiescence", "true"));
    public static final long NO_POSITION = Long.MIN_VALUE;

    private MiraiRendererQuiescence() { }

    public static boolean isActive()
    {
        return ENABLED && Config.Client.Advanced.Debugging.rendererMode.get() == EDhApiRendererMode.DISABLED;
    }

    /** Primitive bounded FIFO+dedupe; stores coordinates only, never world/chunk objects. */
    public static final class PositionBacklog
    {
        private final int capacity;
        private final LongLinkedOpenHashSet positions = new LongLinkedOpenHashSet();

        public PositionBacklog(int capacity)
        {
            if (capacity < 1) { throw new IllegalArgumentException("capacity must be positive"); }
            this.capacity = capacity;
        }

        public synchronized void offer(long position)
        {
            if (position == NO_POSITION) { throw new IllegalArgumentException("reserved position"); }
            if (!this.positions.add(position)) { return; }
            if (this.positions.size() > this.capacity) { this.positions.removeFirstLong(); }
        }

        public synchronized long pollFirst()
        { return this.positions.isEmpty() ? NO_POSITION : this.positions.removeFirstLong(); }

        public synchronized void requeue(long position)
        {
            if (position == NO_POSITION) { return; }
            this.positions.addAndMoveToLast(position);
            if (this.positions.size() > this.capacity) { this.positions.removeFirstLong(); }
        }

        public synchronized int size() { return this.positions.size(); }
        public synchronized boolean isEmpty() { return this.positions.isEmpty(); }
        public synchronized void clear() { this.positions.clear(); }
    }
}
