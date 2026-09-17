package com.seibel.distanthorizons.core.render.QuadTree;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/** Disabled rendering must not create new geometry/GPU work, while preserving catch-up state. */
public class LodRenderDisabledModeTest
{
    @Test public void disabledModeShortCircuitsBeforeInstanceDependencies() throws Exception
    {
        ConfigEntry<EDhApiRendererMode> mode = Config.Client.Advanced.Debugging.rendererMode;
        EDhApiRendererMode previous = mode.getTrueValue();
        assertNull("Test requires no renderer API override", mode.getApiValue());
        assertFalse("Renderer mode must not have a version override", mode.mcVersionOverridePresent());
        try
        {
            mode.setWithoutFiringEvents(EDhApiRendererMode.DISABLED);
            LodRenderSection section = allocate(LodRenderSection.class);
            assertFalse(section.uploadRenderDataToGpuAsync());
            assertTrue("Suppressed work must remain eligible for catch-up", section.renderDataDirty);
        }
        finally
        {
            mode.setWithoutFiringEvents(previous);
        }
    }

    @Test public void dirtyPublishedBufferStaysRenderableAndRequestsRefresh() throws Exception
    {
        ConfigEntry<EDhApiRendererMode> mode = Config.Client.Advanced.Debugging.rendererMode;
        EDhApiRendererMode previous = mode.getTrueValue();
        assertNull("Test requires no renderer API override", mode.getApiValue());
        assertFalse("Renderer mode must not have a version override", mode.mcVersionOverridePresent());
        try
        {
            mode.setWithoutFiringEvents(EDhApiRendererMode.DEFAULT);
            assertTrue(LodRenderSection.gpuRenderWorkEnabled());

            LodRenderSection section = allocate(LodRenderSection.class);
            section.renderBufferContainer = allocate(LodBufferContainer.class);
            section.renderDataDirty = true;

            assertTrue("Existing geometry remains available during catch-up", section.canRender());
            assertFalse("Dirty geometry must be scheduled for a refresh", section.gpuUploadComplete());
        }
        finally
        {
            mode.setWithoutFiringEvents(previous);
        }
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
