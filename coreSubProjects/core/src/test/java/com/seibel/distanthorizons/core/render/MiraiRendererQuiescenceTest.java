package com.seibel.distanthorizons.core.render;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import org.junit.Test;

import static org.junit.Assert.*;

public class MiraiRendererQuiescenceTest
{
    @Test public void policyTracksEffectiveDisabledRendererMode()
    {
        ConfigEntry<EDhApiRendererMode> mode = Config.Client.Advanced.Debugging.rendererMode;
        EDhApiRendererMode previous = mode.getTrueValue();
        assertNull(mode.getApiValue());
        assertFalse(mode.mcVersionOverridePresent());
        try
        {
            mode.setWithoutFiringEvents(EDhApiRendererMode.DISABLED);
            assertTrue(MiraiRendererQuiescence.isActive());
            mode.setWithoutFiringEvents(EDhApiRendererMode.DEFAULT);
            assertFalse(MiraiRendererQuiescence.isActive());
        }
        finally { mode.setWithoutFiringEvents(previous); }
    }

    @Test public void primitiveBacklogIsBoundedDeduplicatedAndOrdered()
    {
        MiraiRendererQuiescence.PositionBacklog backlog = new MiraiRendererQuiescence.PositionBacklog(2);
        backlog.offer(11L);
        backlog.offer(22L);
        backlog.offer(11L);
        assertEquals(2, backlog.size());
        backlog.offer(33L);
        assertEquals("Oldest unique intent must be evicted", 22L, backlog.pollFirst());
        assertEquals(33L, backlog.pollFirst());
        assertEquals(MiraiRendererQuiescence.NO_POSITION, backlog.pollFirst());
    }

    @Test public void requeueMovesBusyChunkBehindOtherCatchUpWork()
    {
        MiraiRendererQuiescence.PositionBacklog backlog = new MiraiRendererQuiescence.PositionBacklog(3);
        backlog.offer(1L); backlog.offer(2L); backlog.offer(3L);
        long first = backlog.pollFirst();
        assertEquals(1L, first);
        backlog.requeue(first);
        assertEquals(2L, backlog.pollFirst());
        assertEquals(3L, backlog.pollFirst());
        assertEquals(1L, backlog.pollFirst());
    }
}
