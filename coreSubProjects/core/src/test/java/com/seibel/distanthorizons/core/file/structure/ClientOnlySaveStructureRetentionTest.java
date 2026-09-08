package com.seibel.distanthorizons.core.file.structure;

import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercise the production memoization field with wrapper boundaries; no Minecraft bootstrap. */
public class ClientOnlySaveStructureRetentionTest
{
    @Test public void wrapperMemoizationDoesNotKeepUnloadedWorldAlive() throws Exception
    {
        ClientOnlySaveStructure structure = new ClientOnlySaveStructure();
        try
        {
            Map<ILevelWrapper, File> files = files(structure);
            WeakReference<?>[] released = createUnownedWrapper(files);
            UnidentifiedClientCacheTest.awaitCollection(released[0]);
            UnidentifiedClientCacheTest.awaitCollection(released[1]);
            Assert.assertTrue(files.isEmpty());
        }
        finally { structure.close(); }
    }

    @Test public void wrapperMemoizationPreservesExistingEqualitySemantics() throws Exception
    {
        ClientOnlySaveStructure structure = new ClientOnlySaveStructure();
        try
        {
            Map<ILevelWrapper, File> files = files(structure);
            ILevelWrapper first = wrapper(new Object(), "same-wrapper-key");
            ILevelWrapper second = wrapper(new Object(), "same-wrapper-key");
            File expected = new File("existing-cache");
            files.put(first, expected);
            Assert.assertNotSame(first, second);
            Assert.assertEquals(first, second);
            Assert.assertSame(expected, files.computeIfAbsent(second, ignored -> { Assert.fail("Equivalent wrapper recomputed its folder"); return null; }));
            Assert.assertEquals(1, files.size());
        }
        finally { structure.close(); }
    }

    @Test public void concurrentMemoizationComputesOnlyOnce() throws Exception
    {
        ClientOnlySaveStructure structure = new ClientOnlySaveStructure();
        try
        {
            Map<ILevelWrapper, File> files = files(structure);
            ILevelWrapper wrapper = wrapper(new Object(), "concurrent-wrapper");
            AtomicInteger computations = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch begin = new CountDownLatch(1);
            Thread[] threads = new Thread[8];
            File expected = new File("one-cache");
            for (int index = 0; index < threads.length; index++)
            {
                threads[index] = new Thread(() ->
                {
                    ready.countDown();
                    try
                    {
                        begin.await();
                        Assert.assertSame(expected, files.computeIfAbsent(wrapper, ignored -> { computations.incrementAndGet(); return expected; }));
                    }
                    catch (Throwable error) { failure.compareAndSet(null, error); }
                });
                threads[index].start();
            }
            ready.await();
            begin.countDown();
            for (Thread thread : threads) { thread.join(2000); Assert.assertFalse(thread.isAlive()); }
            if (failure.get() != null) { throw new AssertionError(failure.get()); }
            Assert.assertEquals(1, computations.get());
        }
        finally { structure.close(); }
    }

    private static WeakReference<?>[] createUnownedWrapper(Map<ILevelWrapper, File> files)
    {
        Object world = new Object();
        ILevelWrapper wrapper = wrapper(world, "unloaded-wrapper");
        files.put(wrapper, new File("unloaded-cache"));
        return new WeakReference<?>[] { new WeakReference<>(wrapper), new WeakReference<>(world) };
    }

    private static ILevelWrapper wrapper(Object world, String key)
    {
        return (ILevelWrapper) Proxy.newProxyInstance(ILevelWrapper.class.getClassLoader(), new Class<?>[] { ILevelWrapper.class },
                (proxy, method, args) ->
                {
                    switch (method.getName())
                    {
                        case "getWrappedMcObject": return world;
                        case "hashCode": return key.hashCode();
                        case "equals": return args[0] instanceof ILevelWrapper && key.equals(args[0].toString());
                        case "toString": return key;
                        default: throw new UnsupportedOperationException("Unexpected boundary method: " + method.getName());
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<ILevelWrapper, File> files(ClientOnlySaveStructure structure) throws Exception
    {
        Field field = ClientOnlySaveStructure.class.getDeclaredField("levelWrapperToFileMap");
        field.setAccessible(true);
        return (Map<ILevelWrapper, File>) field.get(structure);
    }
}
