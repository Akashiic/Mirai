package com.seibel.distanthorizons.core.logging.mirai;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class MiraiGlStateTimingTest
{
    @After public void cleanup()
    {
        MiraiGlStateTiming.drainDescription();
    }

    @Test public void disabledTraceDoesNotRecord()
    {
        MiraiGlStateTiming.drainDescription();
        long start = MiraiGlStateTiming.start();
        assertEquals(0L, start);
        MiraiGlStateTiming.finishSave(MiraiGlStateTiming.Scope.SSAO, start);
        MiraiGlStateTiming.finishRestore(MiraiGlStateTiming.Scope.SSAO, start);
        assertNull(MiraiGlStateTiming.drainDescription());
    }

    @Test public void fineHistogramIsScopedDescribedAndDrained()
    {
        MiraiGlStateTiming.drainDescription();
        MiraiGlStateTiming.recordForTest(MiraiGlStateTiming.Scope.SSAO, false, 20_000L);
        MiraiGlStateTiming.recordForTest(MiraiGlStateTiming.Scope.SSAO, false, 60_000L);
        MiraiGlStateTiming.recordForTest(MiraiGlStateTiming.Scope.SSAO, false, 120_000L);
        MiraiGlStateTiming.recordForTest(MiraiGlStateTiming.Scope.SSAO, false, 300_000L);        MiraiGlStateTiming.recordForTest(MiraiGlStateTiming.Scope.SSAO, true, 2_000_000L);

        String description = MiraiGlStateTiming.drainDescription();
        assertNotNull(description);
        assertTrue(description.contains("trace_only=true"));
        assertTrue(description.contains("SSAO save{count=4"));
        assertTrue(description.contains("mean_us=125"));
        assertTrue(description.contains("max_us=300"));
        assertTrue(description.contains("p95_us=<=500"));
        assertTrue(description.contains("restore{count=1"));
        assertTrue(description.contains("max_us=2000"));
        assertTrue(description.contains("p95_us=<=2000"));
        assertNull(MiraiGlStateTiming.drainDescription());
    }

    @Test public void nullScopeFallsBackToUnscoped()
    {
        MiraiGlStateTiming.drainDescription();
        MiraiGlStateTiming.recordForTest(null, false, 10_000L);
        String description = MiraiGlStateTiming.drainDescription();
        assertNotNull(description);
        assertTrue(description.contains("UNSCOPED save{count=1"));
    }
}
