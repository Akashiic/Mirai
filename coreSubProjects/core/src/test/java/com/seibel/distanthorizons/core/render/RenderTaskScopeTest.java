package com.seibel.distanthorizons.core.render;

import org.junit.Test;
import java.io.File;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import static org.junit.Assert.*;

/** Exercises the real queue loop with headless tasks; owns a separate loader to isolate globals. */
public class RenderTaskScopeTest
{
    @Test public void nestedQueueExecutionRestoresOuterTask() throws Exception
    {
        try (QueueFixture fixture = new QueueFixture())
        {
            fixture.add("outer", () -> {
                Object outer = fixture.current(); assertNotNull(outer);
                fixture.add("inner", () -> {
                    assertNotNull(fixture.current()); assertNotSame(outer, fixture.current());
                });
                fixture.drain();
                assertSame("Nested draining must restore the active outer scope", outer, fixture.current());
            });
            fixture.drain(); assertNull(fixture.current()); assertEquals(0, fixture.queue.size());
        }
    }

    @Test public void propagatedErrorClearsTaskScopeWithoutSwallowingError() throws Exception
    {
        try (QueueFixture fixture = new QueueFixture())
        {
            AssertionError original = new AssertionError("synthetic queue failure");
            fixture.add("failing", () -> { assertNotNull(fixture.current()); throw original; });
            fixture.add("next", () -> { });
            try { fixture.drain(); fail("The original Error must propagate"); }
            catch (AssertionError actual) { assertSame(original, actual); }
            assertNull("A failed task must not remain reported as running", fixture.current());
            assertEquals("The failing run must not consume subsequent work", 1, fixture.queue.size());
            fixture.drain(); assertNull(fixture.current()); assertEquals(0, fixture.queue.size());
        }
    }

    static final class QueueFixture implements AutoCloseable
    {
        final URLClassLoader loader;
        final Object handler;
        final Queue<Object> queue;
        final Method run;
        final Method current;
        final Constructor<?> taskConstructor;
        final Timer timer;
        final ClassLoader originalContextLoader;
        final Queue<Runnable> callbacks = new ArrayDeque<>();
        boolean rejectScheduling;

        QueueFixture() throws Exception { this(false); }

        @SuppressWarnings("unchecked")
        QueueFixture(boolean client) throws Exception
        {
            LinkedHashSet<URL> urls = new LinkedHashSet<>();
            // Optional first entry permits the exact same tests to reject the baseline queue bytecode.
            String baseline = System.getProperty("mirai.queue.test.baseline");
            if (baseline != null) { urls.add(new File(baseline).toURI().toURL()); }
            for (ClassLoader cl = getClass().getClassLoader(); cl != null; cl = cl.getParent())
            { if (cl instanceof URLClassLoader) { urls.addAll(Arrays.asList(((URLClassLoader) cl).getURLs())); } }
            for (String path : System.getProperty("java.class.path").split(File.pathSeparator))
            { urls.add(new File(path).toURI().toURL()); }
            loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getSystemClassLoader().getParent());
            originalContextLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> wrapper = loader.loadClass("com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper");
            Object shared = Proxy.newProxyInstance(loader, new Class<?>[]{wrapper}, (proxy, method, args) -> {
                if (method.getName().equals("isDedicatedServer")) { return !client; }
                if (method.getReturnType() == boolean.class) { return true; }
                if (method.getReturnType() == int.class) { return 0; }
                return null;
            });
            Object injector = loader.loadClass("com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector").getField("INSTANCE").get(null);
            Class<?> bindable = loader.loadClass("com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable");
            injector.getClass().getMethod("bind", Class.class, bindable).invoke(injector, wrapper, shared);
            if (client)
            {
                Class<?> clientWrapper = loader.loadClass("com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper");
                Object mcClient = Proxy.newProxyInstance(loader, new Class<?>[]{clientWrapper}, (proxy, method, args) -> {
                    if (method.getName().equals("executeOnRenderThread"))
                    {
                        if (rejectScheduling) { throw new java.util.concurrent.RejectedExecutionException("test"); }
                        callbacks.add((Runnable) args[0]);
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) { return true; }
                    if (method.getReturnType() == int.class) { return 0; }
                    return null;
                });
                injector.getClass().getMethod("bind", Class.class, bindable).invoke(injector, clientWrapper, mcClient);
                Class<?> renderWrapper = loader.loadClass("com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper");
                Object render = Proxy.newProxyInstance(loader, new Class<?>[]{renderWrapper}, (proxy, method, args) -> {
                    if (method.getName().equals("getFrameLimit")) { return 240; }
                    if (method.getReturnType() == boolean.class) { return true; }
                    if (method.getReturnType() == int.class) { return 0; }
                    return null;
                });
                injector.getClass().getMethod("bind", Class.class, bindable).invoke(injector, renderWrapper, render);
            }
            Class<?> handlerClass = loader.loadClass("com.seibel.distanthorizons.core.render.RenderThreadTaskHandler");
            handler = handlerClass.getField("INSTANCE").get(null);
            run = handlerClass.getDeclaredMethod("runRenderThreadTasks", long.class); run.setAccessible(true);
            current = handlerClass.getMethod("getCurrentlyRunningTask");
            Field queueField = handlerClass.getDeclaredField("RENDER_THREAD_RUNNABLE_QUEUE"); queueField.setAccessible(true);
            queue = (Queue<Object>) queueField.get(null);
            Field timerField = handlerClass.getDeclaredField("TIMER"); timerField.setAccessible(true); timer = (Timer) timerField.get(null);
            timer.cancel(); // Tests invoke maintenance explicitly; never race a live timer.
            taskConstructor = loader.loadClass(handlerClass.getName() + "$QueuedRunnable").getConstructor(String.class, Runnable.class, StackTraceElement[].class);
        }
        void add(String name, Runnable task)
        { try { queue.add(taskConstructor.newInstance(name, task, null)); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } }
        Object current()
        { try { return current.invoke(handler); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } }
        void drain()
        {
            try { run.invoke(handler, Long.MAX_VALUE); }
            catch (InvocationTargetException wrapped)
            {
                Throwable cause = wrapped.getCause();
                if (cause instanceof Error) { throw (Error) cause; }
                throw new AssertionError(cause);
            }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
        void stale() throws Exception
        {
            Field field = handler.getClass().getDeclaredField("nanoSinceTasksRun"); field.setAccessible(true);
            field.setLong(handler, System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(2));
        }
        void cleanupTick() throws Exception
        {
            Method tick = handler.getClass().getDeclaredMethod("manualCleanupTick"); tick.setAccessible(true);
            tick.invoke(handler);
        }
        @Override public void close() throws Exception
        {
            try
            {
                queue.clear(); timer.cancel(); callbacks.clear();
                Object context = loader.loadClass("org.apache.logging.log4j.LogManager").getMethod("getContext", boolean.class).invoke(null, false);
                context.getClass().getMethod("stop").invoke(context);
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(originalContextLoader);
                loader.close();
            }
        }
    }
}
