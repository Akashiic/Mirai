package com.seibel.distanthorizons.core.render;

import org.junit.Test;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.Assert.*;

/** Real list construction and diagnostics, isolated from game globals; no GL context is used. */
public class RenderListTimingTest
{
    @Test public void successfulSelectionIsUnchangedAndUsesOnlyBoundedAggregates() throws Exception
    {
        try (Fixture fixture = new Fixture(true))
        {
            for (int i = 0; i < 10000; i++) { fixture.build(); }
            fixture.assertSelection();
            long[] timing = fixture.timing();
            assertEquals("Each completed list build must be observed exactly once", 10000, timing[0]);
            assertTrue(timing[1] >= 0);
            fixture.assertNoEventsOrDiskWork();
        }
    }

    @Test public void originalFailurePropagatesAndStillClosesTimingScope() throws Exception
    {
        try (Fixture fixture = new Fixture(true))
        {
            AssertionError original = new AssertionError("synthetic list source failure");
            fixture.replaceSections(new ArrayList<Object>() {
                @Override public int size() { throw original; }
            });
            try { fixture.build(); fail("The original list-construction error must propagate"); }
            catch (AssertionError actual) { assertSame(original, actual); }
            assertEquals("A failed list build must close its timing scope once", 1, fixture.timing()[0]);
            fixture.assertNoEventsOrDiskWork();
        }
    }

    @Test public void disabledDiagnosticsPreserveSelectionWithoutRecording() throws Exception
    {
        try (Fixture fixture = new Fixture(false))
        {
            fixture.build();
            fixture.assertSelection();
            assertEquals("An opted-out build must not record timings", 0, fixture.timing()[0]);
            fixture.assertNoEventsOrDiskWork();
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final RenderTaskScopeTest.QueueFixture queue = new RenderTaskScopeTest.QueueFixture(true);
        final ClassLoader loader = queue.loader;
        final Path root = Files.createTempDirectory("mirai-list-timing-");
        final Path notCreated = root.resolve("not-created");
        final Object session;
        final Object tree;
        final Object handler;
        final Object first;
        final Object second;
        final Method build;
        final Field diagnosticSession;
        final Object unsafe;
        final Method allocate;

        Fixture(boolean enabled) throws Exception
        {
            queue.drain(); // Establish the actual render-thread owner without starting the timer.
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeClass.getDeclaredField("theUnsafe"); unsafeField.setAccessible(true);
            unsafe = unsafeField.get(null); allocate = unsafeClass.getMethod("allocateInstance", Class.class);

            // Constructor-free world/section fixtures avoid databases and real client resources.
            // The production populateListWithEnabledRenderSections method and list loop still run.
            tree = allocate("com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree");
            set(tree, "enabledRenderSectionLock", new ReentrantLock());
            first = allocate("com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer");
            second = allocate("com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer");
            ArrayList<Object> sections = new ArrayList<>();
            sections.add(section(first, true)); sections.add(null);
            sections.add(section(null, true)); sections.add(section(second, false));
            sections.add(section(second, true));
            replaceSections(sections);

            Class<?> culling = loader.loadClass("com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics$Culling");
            Object disabledCulling = culling.getField("disableFrustumCulling").get(null);
            disabledCulling.getClass().getMethod("setWithoutFiringEvents", Object.class).invoke(disabledCulling, true);
            Class<?> handlerClass = loader.loadClass("com.seibel.distanthorizons.core.render.RenderBufferHandler");
            handler = handlerClass.getConstructor(tree.getClass()).newInstance(tree);
            build = handlerClass.getMethod("buildRenderList", loader.loadClass("com.seibel.distanthorizons.core.render.RenderParams"));

            Class<?> sessionClass = loader.loadClass("com.seibel.distanthorizons.core.logging.mirai.MiraiLogSession");
            Constructor<?> constructor = sessionClass.getDeclaredConstructor(Path.class, String.class, int.class, long.class, int.class);
            constructor.setAccessible(true); session = constructor.newInstance(notCreated, "TEST", 2, 4096L, 4);
            diagnosticSession = loader.loadClass("com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics").getDeclaredField("session");
            diagnosticSession.setAccessible(true); diagnosticSession.set(null, enabled ? session : null);
        }
        Object allocate(String name) throws Exception { return allocate.invoke(unsafe, loader.loadClass(name)); }
        Object section(Object container, boolean enabled) throws Exception
        {
            Object section = allocate("com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection");
            set(section, "renderBufferContainer", container); set(section, "renderingEnabled", enabled);
            return section;
        }
        void replaceSections(ArrayList<Object> sections) throws Exception { set(tree, "enabledSections", sections); }
        static void set(Object target, String name, Object value) throws Exception
        { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        void build() throws Exception
        {
            try { build.invoke(handler, new Object[]{null}); }
            catch (InvocationTargetException wrapped)
            {
                Throwable cause = wrapped.getCause();
                if (cause instanceof Error) { throw (Error) cause; }
                if (cause instanceof Exception) { throw (Exception) cause; }
                throw new AssertionError(cause);
            }
        }
        void assertSelection() throws Exception
        {
            Object selected = handler.getClass().getMethod("getColumnRenderBuffers").invoke(handler);
            assertEquals(2, selected.getClass().getMethod("size").invoke(selected));
            Method get = selected.getClass().getMethod("get", int.class);
            assertSame("Original section order must be preserved", first, get.invoke(selected, 0));
            assertSame("Disabled and null containers must stay excluded", second, get.invoke(selected, 1));
        }
        long[] timing() throws Exception
        {
            Class<?> metricClass = loader.loadClass("com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics$Metric");
            Object metric = metricClass.getField("LOD_RENDER_LIST_WALL").get(null);
            Field timings = session.getClass().getDeclaredField("timings"); timings.setAccessible(true);
            Object timing = ((Object[]) timings.get(session))[((Enum<?>) metric).ordinal()];
            Method drain = timing.getClass().getDeclaredMethod("drain"); drain.setAccessible(true);
            return (long[]) drain.invoke(timing);
        }
        void assertNoEventsOrDiskWork() throws Exception
        {
            Method queued = session.getClass().getDeclaredMethod("queued"); queued.setAccessible(true);
            assertEquals("Timing must not enqueue a per-build event", 0, queued.invoke(session));
            assertFalse("Timing must not start the diagnostic writer", Files.exists(notCreated));
        }
        @Override public void close() throws Exception
        {
            try
            {
                diagnosticSession.set(null, null);
                Method close = session.getClass().getDeclaredMethod("close"); close.setAccessible(true); close.invoke(session);
                assertFalse(Files.exists(notCreated)); Files.delete(root);
            }
            finally { queue.close(); }
        }
    }
}
