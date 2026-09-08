package com.seibel.distanthorizons.core.dataObjects.transformers;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.junit.Test;
import testItems.wrappers.TestBiomeWrapper;
import testItems.wrappers.TestBlockStateWrapper;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.Assert.*;

/** Runs the actual DTO decoder and complete renderer transformer with isolated wrapper bindings. */
public class FullDataEmptySentinelTest
{
    @Test public void emptyPartialDtoDoesNotReportBrokenMap() throws Exception { isolated("empty"); }
    @Test public void partialDtoPreservesRealIdZeroWithoutResolvingPlaceholders() throws Exception { isolated("valid"); }
    @Test public void nonEmptyDataWithMissingIdStillReportsBrokenMapping() throws Exception { isolated("invalid"); }

    private static void isolated(String scenario) throws Exception
    {
        LinkedHashSet<URL> urls = new LinkedHashSet<>();
        String baseline = System.getProperty("mirai.sentinel.test.baseline");
        if (baseline != null) { urls.add(new File(baseline).toURI().toURL()); }
        for (ClassLoader cl = FullDataEmptySentinelTest.class.getClassLoader(); cl != null; cl = cl.getParent())
        { if (cl instanceof URLClassLoader) { urls.addAll(Arrays.asList(((URLClassLoader) cl).getURLs())); } }
        for (String path : System.getProperty("java.class.path").split(File.pathSeparator))
        { urls.add(new File(path).toURI().toURL()); }
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getSystemClassLoader().getParent()))
        {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try { loader.loadClass(Scenario.class.getName()).getMethod("run", String.class).invoke(null, scenario); }
            catch (InvocationTargetException failure)
            {
                Throwable cause = failure.getCause();
                if (cause instanceof Error) { throw (Error) cause; }
                if (cause instanceof Exception) { throw (Exception) cause; }
                throw new AssertionError(cause);
            }
            finally
            {
                try
                {
                    Object context = loader.loadClass("org.apache.logging.log4j.LogManager").getMethod("getContext", boolean.class).invoke(null, false);
                    context.getClass().getMethod("stop").invoke(context);
                }
                finally { Thread.currentThread().setContextClassLoader(previous); }
            }
        }
    }

    public static final class Scenario
    {
        public static void run(String scenario) throws Exception
        {
            IWrapperFactory factory = (IWrapperFactory) Proxy.newProxyInstance(Scenario.class.getClassLoader(),
                    new Class<?>[] { IWrapperFactory.class }, (proxy, method, args) -> {
                        if (ObjectOpenHashSet.class.isAssignableFrom(method.getReturnType())) { return new ObjectOpenHashSet<>(); }
                        if (method.getName().startsWith("deserializeBiome")) { return new TestBiomeWrapper((String) args[0]); }
                        if (method.getName().startsWith("deserializeBlockState")) { return new TestBlockStateWrapper((String) args[0]); }
                        if (method.getName().equals("getWaterBlockStateWrapper")) { return new TestBlockStateWrapper("water"); }
                        if (method.getReturnType() == boolean.class) { return true; }
                        return null;
                    });
            SingletonInjector.INSTANCE.bind(IWrapperFactory.class, factory);
            final int[] colorCalls = {0};
            final int color = 0xff6a8c42;
            IClientLevelWrapper level = (IClientLevelWrapper) Proxy.newProxyInstance(Scenario.class.getClassLoader(),
                    new Class<?>[] { IClientLevelWrapper.class }, (proxy, method, args) -> {
                        if (method.getName().equals("getBlockColor")) { colorCalls[0]++; return color; }
                        if (method.getName().equals("getDhIdentifier")) { return "sentinel-test"; }
                        if (method.getReturnType() == boolean.class) { return false; }
                        if (method.getReturnType() == int.class) { return 0; }
                        return null;
                    });
            Config.Client.Advanced.Graphics.Culling.enableCaveCulling.setWithoutFiringEvents(false);
            Config.Client.Advanced.Graphics.Texture.enableTexturedLods.setWithoutFiringEvents(false);

            long pos = DhSectionPos.encode((byte) 12, -1, 0);
            try (FullDataSourceV2 source = FullDataSourceV2.createEmpty(pos))
            {
                if (scenario.equals("invalid"))
                {
                    source.getColumnAtRelPos(1, 0).add(FullDataPointUtil.encode(7, 64, 0, (byte) 0, (byte) 15));
                    source.isEmpty = false;
                    try (ColumnRenderSource rendered = FullDataToRenderDataTransformer.transformFullDataToRenderSource(source, level))
                    {
                        assertNotNull(rendered);
                        assertEquals(RenderDataPointUtil.EMPTY_DATA, rendered.getDataPoint(1, 0, 0));
                        assertTrue("Real missing IDs must remain observable", brokenPositions().contains(pos));
                    }
                    return;
                }
                if (scenario.equals("valid"))
                {
                    int id = source.mapping.addIfNotPresentAndGetId(new TestBiomeWrapper("biome"), new TestBlockStateWrapper("solid"));
                    assertEquals(0, id);
                    source.getColumnAtRelPos(1, 0).add(FullDataPointUtil.encode(id, 64, 0, (byte) 3, (byte) 15));
                    source.setSingleColumn(source.getColumnAtRelPos(1, 0), 1, 0,
                            EDhApiWorldGenerationStep.LIGHT, EDhApiWorldCompressionMode.VISUALLY_EQUAL);
                }
                try (FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateFromDataSource(source, EDhApiDataCompressionMode.UNCOMPRESSED))
                {
                    // The partial repository SELECT exposes the chosen edge as compressedDataByteArray.
                    dto.compressedDataByteArray.clear();
                    dto.compressedDataByteArray.addAll(dto.compressedNorthAdjDataByteArray);
                    try (FullDataSourceV2 decoded = dto.createDataSource(level, EDhDirection.NORTH))
                    {
                        assertFalse(decoded.isEmpty);
                        assertEquals(1, decoded.getColumnAtRelPos(1, 1).size());
                        assertEquals(FullDataPointUtil.EMPTY_DATA_POINT, decoded.getColumnAtRelPos(1, 1).getLong(0));
                        assertEquals(scenario.equals("valid") ? 1 : 0, decoded.mapping.size());
                        try (ColumnRenderSource rendered = FullDataToRenderDataTransformer.transformFullDataToRenderSource(decoded, level))
                        {
                            assertNotNull(rendered);
                            assertFalse("DTO placeholders are not broken mapping IDs", brokenPositions().contains(pos));
                            assertEquals("Only the actual block may resolve its color", scenario.equals("valid") ? 1 : 0, colorCalls[0]);
                            assertEquals(RenderDataPointUtil.EMPTY_DATA, rendered.getDataPoint(1, 1, 0));
                            if (scenario.equals("valid"))
                            {
                                assertEquals("ID 0 retains geometry, color, sky/block light and material",
                                        RenderDataPointUtil.createDataPoint(64, 0, color, 15, 3, (byte) 0), rendered.getDataPoint(1, 0, 0));
                            }
                            else { assertEquals(RenderDataPointUtil.EMPTY_DATA, rendered.getDataPoint(1, 0, 0)); }
                        }
                    }
                }
            }
        }

        private static LongOpenHashSet brokenPositions() throws Exception
        {
            Field field = FullDataToRenderDataTransformer.class.getDeclaredField("BROKEN_POS_SET");
            field.setAccessible(true);
            return (LongOpenHashSet) field.get(null);
        }
    }
}
