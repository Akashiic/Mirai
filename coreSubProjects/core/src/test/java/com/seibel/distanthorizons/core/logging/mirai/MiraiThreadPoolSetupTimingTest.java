package com.seibel.distanthorizons.core.logging.mirai;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class MiraiThreadPoolSetupTimingTest
{
    @Test public void disabledDiagnosticsAddsNoTimingRequirement()
    {
        assertEquals(0L, MiraiThreadPoolSetupTiming.begin());
        assertEquals(0L, MiraiThreadPoolSetupTiming.overallStart(0));
        AtomicBoolean ran = new AtomicBoolean();
        MiraiThreadPoolSetupTiming.run(0,
                MiraiThreadPoolSetupTiming.Phase.PICKER_CREATE,
                () -> ran.set(true));
        assertTrue(ran.get());
        MiraiThreadPoolSetupTiming.end(0, 0, true);
    }

    @Test public void disabledInstrumentationNeverSwallowsActionFailure()
    {
        RuntimeException expected = new RuntimeException("expected");
        try
        {
            MiraiThreadPoolSetupTiming.run(0,
                    MiraiThreadPoolSetupTiming.Phase.IO,
                    () -> { throw expected; });
            fail("expected failure");
        }
        catch (RuntimeException actual)
        {
            assertSame(expected, actual);
        }
    }
}
