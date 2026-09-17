package com.seibel.distanthorizons.core.level;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class ClientLevelQuiescenceTest
{
    @Test public void disabledModeShortCircuitsBeforeRendererDependencies() throws Exception
    {
        ConfigEntry<EDhApiRendererMode> mode = Config.Client.Advanced.Debugging.rendererMode;
        EDhApiRendererMode previous = mode.getTrueValue();
        try
        {
            mode.setWithoutFiringEvents(EDhApiRendererMode.DISABLED);
            ClientLevelModule module = allocate(ClientLevelModule.class);
            module.startRenderer();
            module.OnDataSourceUpdated(null);
            assertFalse(module.isRendering());
            Field stopped = ClientLevelModule.class.getDeclaredField("rendererStoppedForQuiescence");
            stopped.setAccessible(true);
            assertTrue(stopped.getBoolean(module));
        }
        finally { mode.setWithoutFiringEvents(previous); }
    }

    private static <T> T allocate(Class<T> type) throws Exception
    {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        return type.cast(allocate.invoke(unsafe, type));
    }
}
