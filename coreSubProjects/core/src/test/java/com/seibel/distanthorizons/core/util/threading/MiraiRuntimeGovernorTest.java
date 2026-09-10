package com.seibel.distanthorizons.core.util.threading;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MiraiRuntimeGovernorTest
{
    @Test public void pressureClassificationNeverExceedsConfiguredConcurrency()
    {
        assertEquals(6, MiraiRuntimeGovernor.classifyPressure(6, 0.50, 0.00, 0, 0).limit);
        assertEquals(5, MiraiRuntimeGovernor.classifyPressure(6, 0.81, 0.00, 0, 0).limit);
        assertEquals(3, MiraiRuntimeGovernor.classifyPressure(6, 0.89, 0.00, 0, 0).limit);
        assertEquals(1, MiraiRuntimeGovernor.classifyPressure(6, 0.94, 0.00, 0, 0).limit);
    }

    @Test public void gcAndSevereFramesTriggerTheSameBoundedPressureBands()
    {
        assertEquals(5, MiraiRuntimeGovernor.classifyPressure(6, 0.50, 0.11, 0, 0).limit);
        assertEquals(3, MiraiRuntimeGovernor.classifyPressure(6, 0.50, 0.26, 0, 0).limit);
        assertEquals(1, MiraiRuntimeGovernor.classifyPressure(6, 0.50, 0.51, 0, 0).limit);
        assertEquals(3, MiraiRuntimeGovernor.classifyPressure(6, 0.50, 0.00, 0, 1).limit);
        assertEquals(1, MiraiRuntimeGovernor.classifyPressure(6, 0.50, 0.00, 0, 2).limit);
    }
}
