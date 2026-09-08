package com.seibel.distanthorizons.core.world;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.util.TimerUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the production client world and real JDK shutdown scheduling with boundary fakes. */
public final class DhClientWorldRegressionTest
{
    private static int assertions;
    public static void main(String[] args) throws Exception
    {
        networkAndPluginFailuresDoNotSkipLevelsOrTimerCleanup();
        exceptionalWorkersCompleteFuturesAndClearEveryMap();
        wrapperAndEventFailuresStillCloseLevels();
        aliasesCloseExactlyOnceAndCloseIsIdempotent();
        nullWrapperStillClosesItsLevel();
        workersStartBeforeWaitingForCompletion();
        closedWorldDeniesLookupAndCreation();
        concurrentConstructorAndCloseCannotOrphanLevel();
        reentrantCloseFromLoadListenerRunsAfterMonitorExit();
        loadListenerFailureClosesOwnedLevel();
        reentrantUnloadDuringLoadClosesExactlyOnce();
        unloadFailureStillClosesAndRemovesOwnership();
        constructorReentrantCloseCannotPublishAfterCleanup();
        inactivityCleanupDoesNotInvertWrapperAndWorldLocks();
        identifierIsCapturedBeforeConstruction();
        staleNativeWorldCannotResolveReplacementWithSameIdentifier();
        sameNativeWorldAliasCanResolveItsLevel();
        nullNativeWorldDoesNotMatchAnotherWrapper();
        System.out.println("DhClientWorld regressions passed: " + assertions + " assertions.");
    }

    private static void networkAndPluginFailuresDoNotSkipLevelsOrTimerCleanup() throws Exception
    {
        DhClientWorld world = world();
        TimerUtil.TrackingTimer timer = TimerUtil.lastTimer;
        DhClientLevel first = add(world, "first", new Wrapper("first"));
        DhClientLevel second = add(world, "second", new Wrapper("second"));
        Error networkFailure = new LinkageError("network cleanup failed");
        RuntimeException pluginFailure = new IllegalStateException("plugin reset failed");
        world.networkState.closeFailure = networkFailure;
        world.networkState.closeHook = () -> check(timer.cancelCalls == 1, "Timer was not cancelled before network teardown");
        world.pluginChannelApi.resetFailure = pluginFailure;
        Throwable actual = capture(world::close);
        check(actual == networkFailure, "Network failure identity was lost");
        check(contains(actual, pluginFailure), "Plugin reset failure disappeared from report");
        check(first.closeCalls.get() == 1 && second.closeCalls.get() == 1, "Network/plugin failures skipped level cleanup");
        check(((Wrapper) first.wrapper).unloadCalls == 1 && ((Wrapper) second.wrapper).unloadCalls == 1,
                "Network/plugin failures skipped wrapper unload");
        check(world.networkState.closeCalls == 1 && world.pluginChannelApi.resetCalls == 1,
                "One first-stage failure skipped the other cleanup stage");
        assertMapsCleared(world);
        assertTimerCancelled(timer);
        world.close();
        check(world.networkState.closeCalls == 1 && world.pluginChannelApi.resetCalls == 1 && timer.cancelCalls == 1,
                "Retry repeated failed shutdown work");
    }

    private static void exceptionalWorkersCompleteFuturesAndClearEveryMap() throws Exception
    {
        DhClientWorld world = world();
        TimerUtil.TrackingTimer timer = TimerUtil.lastTimer;
        DhClientLevel first = add(world, "first", new Wrapper("first"));
        DhClientLevel second = add(world, "second", new Wrapper("second"));
        DhClientLevel third = add(world, "third", new Wrapper("third"));
        RuntimeException firstFailure = new IllegalStateException("worker runtime failure");
        Error secondFailure = new LinkageError("worker linkage failure");
        first.closeFailure = firstFailure;
        second.closeFailure = secondFailure;
        Throwable actual = capture(world::close);
        check(contains(actual, firstFailure), "Worker runtime failure was hidden");
        check(contains(actual, secondFailure), "Worker Error was hidden");
        check(first.closeCalls.get() == 1 && second.closeCalls.get() == 1 && third.closeCalls.get() == 1,
                "One failed worker skipped another level");
        assertMapsCleared(world);
        assertTimerCancelled(timer);
        // The external runner terminates this Java process after five seconds if join is left pending.
    }

    private static void wrapperAndEventFailuresStillCloseLevels() throws Exception
    {
        DhClientWorld world = world();
        DhClientLevel unloadFault = add(world, "unload", new Wrapper("unload"));
        DhClientLevel eventFault = add(world, "event", new Wrapper("event"));
        DhClientLevel unaffected = add(world, "normal", new Wrapper("normal"));
        Error unloadFailure = new LinkageError("wrapper unload failed");
        RuntimeException eventFailure = new IllegalStateException("unload event failed");
        ((Wrapper) unloadFault.wrapper).unloadFailure = unloadFailure;
        AtomicInteger successfulEvents = new AtomicInteger();
        ApiEventInjector.INSTANCE.handler = event ->
        {
            if (((DhApiLevelUnloadEvent.EventParam) event).wrapper == eventFault.wrapper) throw eventFailure;
            successfulEvents.incrementAndGet();
        };
        Throwable actual = capture(world::close);
        check(contains(actual, unloadFailure) && contains(actual, eventFailure), "Unload/event failures were not preserved");
        check(unloadFault.closeCalls.get() == 1 && eventFault.closeCalls.get() == 1 && unaffected.closeCalls.get() == 1,
                "Unload/event failure skipped level close");
        check(successfulEvents.get() == 1, "Fault affected an unrelated unload event");
        assertMapsCleared(world);
        assertTimerCancelled(TimerUtil.lastTimer);
    }

    private static void aliasesCloseExactlyOnceAndCloseIsIdempotent() throws Exception
    {
        DhClientWorld world = world();
        TimerUtil.TrackingTimer timer = TimerUtil.lastTimer;
        DhClientLevel level = add(world, "original", new Wrapper("original"));
        levels(world).put("alias", level);
        wrappers(world).put("alias", new HashSet<>(Collections.singleton(level.wrapper)));
        AtomicInteger unloadEvents = new AtomicInteger();
        ApiEventInjector.INSTANCE.handler = event -> unloadEvents.incrementAndGet();
        check(world.getLoadedLevelCount() == 2, "Fixture did not contain two aliases");
        world.close();
        world.close();
        check(level.closeCalls.get() == 1, "Aliases/repeated shutdown closed a level more than once");
        check(((Wrapper) level.wrapper).unloadCalls == 1 && unloadEvents.get() == 1, "Aliases duplicated wrapper/event cleanup");
        check(world.networkState.closeCalls == 1 && world.pluginChannelApi.resetCalls == 1 && timer.cancelCalls == 1,
                "Repeated close repeated network/plugin/timer cleanup");
        assertMapsCleared(world);
        assertTimerCancelled(timer);
    }

    private static void nullWrapperStillClosesItsLevel() throws Exception
    {
        DhClientWorld world = world();
        DhClientLevel level = new DhClientLevel(world.saveStructure, null, world.networkState);
        levels(world).put("null-wrapper", level);
        AtomicInteger events = new AtomicInteger();
        ApiEventInjector.INSTANCE.handler = event -> events.incrementAndGet();
        world.close();
        check(level.closeCalls.get() == 1, "Null wrapper prevented level cleanup");
        check(events.get() == 0, "Null wrapper was passed to unload listeners");
        assertMapsCleared(world);
        assertTimerCancelled(TimerUtil.lastTimer);
    }

    private static void workersStartBeforeWaitingForCompletion() throws Exception
    {
        DhClientWorld world = world();
        DhClientLevel first = add(world, "first", new Wrapper("first"));
        DhClientLevel second = add(world, "second", new Wrapper("second"));
        CountDownLatch bothStarted = new CountDownLatch(2);
        Runnable barrier = () ->
        {
            bothStarted.countDown();
            try
            {
                if (!bothStarted.await(2, TimeUnit.SECONDS)) throw new AssertionError("Workers were joined before both could start");
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        };
        first.closeHook = barrier;
        second.closeHook = barrier;
        world.close();
        check(bothStarted.getCount() == 0 && first.closeCalls.get() == 1 && second.closeCalls.get() == 1,
                "Client level workers did not all start before join");
        assertMapsCleared(world);
        assertTimerCancelled(TimerUtil.lastTimer);
    }

    private static void closedWorldDeniesLookupAndCreation() throws Exception
    {
        DhClientWorld world = world();
        KeyedWrapper wrapper = new KeyedWrapper("closed");
        DhClientLevel level = world.getOrLoadLevel(wrapper);
        check(level != null && world.getLevel(wrapper) == level, "Fixture failed to load real world path");
        world.close();
        DhClientLevel.lastCreated = null;
        check(world.getLevel(wrapper) == null, "Closed world returned a closed level");
        check(world.getOrLoadLevel(new KeyedWrapper("late")) == null, "Closed world accepted a new level");
        check(DhClientLevel.lastCreated == null, "Closed world invoked a level constructor");
        check(!world.unloadLevel(wrapper), "Closed world repeated level unload");
        check(level.closeCalls.get() == 1 && world.saveStructure.closeCalls == 1, "Closed-world resources did not close once");
        assertMapsCleared(world);
    }

    private static void concurrentConstructorAndCloseCannotOrphanLevel() throws Exception
    {
        DhClientWorld world = world();
        KeyedWrapper wrapper = new KeyedWrapper("racing");
        CountDownLatch constructorEntered = new CountDownLatch(1);
        CountDownLatch releaseConstructor = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        DhClientLevel.constructorHook = level ->
        {
            constructorEntered.countDown();
            await(releaseConstructor);
            check(world.saveStructure.closeCalls == 0, "Save structure closed during level construction");
        };
        Thread creator = new Thread(() -> { try { world.getOrLoadLevel(wrapper); } catch (Throwable e) { failure.set(e); } });
        creator.start();
        await(constructorEntered);
        CountDownLatch closeEntered = new CountDownLatch(1);
        Thread closer = new Thread(() -> { closeEntered.countDown(); try { world.close(); } catch (Throwable e) { failure.set(e); } });
        closer.start();
        await(closeEntered);
        releaseConstructor.countDown();
        creator.join(2000);
        closer.join(2000);
        check(!creator.isAlive() && !closer.isAlive(), "Concurrent creation/close deadlocked");
        check(failure.get() == null, "Concurrent creation/close failed: " + failure.get());
        check(DhClientLevel.lastCreated.closeCalls.get() == 1, "Constructed level was orphaned or double-closed");
        check(wrapper.unloadCalls == 1 && world.saveStructure.closeCalls == 1, "Concurrent shutdown skipped ownership cleanup");
        assertMapsCleared(world);
    }

    private static void reentrantCloseFromLoadListenerRunsAfterMonitorExit() throws Exception
    {
        DhClientWorld world = world();
        KeyedWrapper wrapper = new KeyedWrapper("reentrant");
        AtomicInteger unloadEvents = new AtomicInteger();
        ApiEventInjector.INSTANCE.handler = event ->
        {
            if (event instanceof DhApiLevelLoadEvent.EventParam)
            {
                check(world.getLevel(wrapper) == DhClientLevel.lastCreated, "Load listener cannot see owned level");
                DhClientLevel.lastCreated.closeHook = () ->
                {
                    // The worker must acquire the lifecycle monitor: a join under that monitor deadlocks.
                    try { synchronized (field(world, "levelLifecycleLock")) { check(true, "monitor acquired"); } }
                    catch (Exception e) { throw new AssertionError(e); }
                    check(world.getOrLoadLevel(new KeyedWrapper("worker-reentry")) == null, "Shutdown worker reloaded a level");
                };
                world.close();
                check(world.getLevel(wrapper) == null, "Reentrant close did not immediately deny level access");
                check(world.saveStructure.closeCalls == 0, "Reentrant close disposed resources inside its load callback");
            }
            else { unloadEvents.incrementAndGet(); }
        };
        check(world.getOrLoadLevel(wrapper) == null, "Load returned a level closed by its own listener");
        check(DhClientLevel.lastCreated.closeCalls.get() == 1, "Reentrant close lost its unpublished level");
        check(wrapper.unloadCalls == 1 && unloadEvents.get() == 1, "Reentrant close duplicated wrapper lifecycle");
        check(ClientApi.INSTANCE.loadWaitingCalls == 0, "Chunks were loaded after callback requested world close");
        check(world.saveStructure.closeCalls == 1, "Deferred shutdown did not finish before load returned");
        assertMapsCleared(world);
    }

    private static void loadListenerFailureClosesOwnedLevel() throws Exception
    {
        DhClientWorld world = world();
        KeyedWrapper wrapper = new KeyedWrapper("load-failure");
        AtomicInteger unloadEvents = new AtomicInteger();
        ApiEventInjector.INSTANCE.handler = event ->
        {
            if (event instanceof DhApiLevelLoadEvent.EventParam) { throw new IllegalStateException("load callback failed"); }
            unloadEvents.incrementAndGet();
        };
        check(world.getOrLoadLevel(wrapper) == null, "Failed load callback still returned a level");
        DhClientLevel failed = DhClientLevel.lastCreated;
        check(failed.closeCalls.get() == 1 && wrapper.unloadCalls == 1 && unloadEvents.get() == 1,
                "Failed load callback orphaned its constructed level");
        check(world.getLevel(wrapper) == null && world.getLoadedLevelCount() == 0, "Failed load remained published");
        world.close();
        check(failed.closeCalls.get() == 1, "World close repeated failed-level cleanup");
        assertMapsCleared(world);
    }

    private static void reentrantUnloadDuringLoadClosesExactlyOnce() throws Exception
    {
        DhClientWorld world = world();
        KeyedWrapper wrapper = new KeyedWrapper("unload-in-load");
        ApiEventInjector.INSTANCE.handler = event ->
        {
            if (event instanceof DhApiLevelLoadEvent.EventParam) { check(world.unloadLevel(wrapper), "Reentrant unload missed constructed level"); }
            else { check(!world.unloadLevel(wrapper), "Reentrant unload repeated level disposal"); }
        };
        check(world.getOrLoadLevel(wrapper) == null, "Load returned a level unloaded by its own callback");
        DhClientLevel level = DhClientLevel.lastCreated;
        check(level.closeCalls.get() == 1 && wrapper.unloadCalls == 1, "Reentrant unload duplicated cleanup");
        check(ClientApi.INSTANCE.loadWaitingCalls == 0, "Unloaded level received waiting chunks");
        world.close();
        check(level.closeCalls.get() == 1, "World close repeated reentrant unload cleanup");
        assertMapsCleared(world);
    }

    private static void unloadFailureStillClosesAndRemovesOwnership() throws Exception
    {
        DhClientWorld world = world();
        KeyedWrapper wrapper = new KeyedWrapper("unload-failure");
        DhClientLevel level = world.getOrLoadLevel(wrapper);
        wrapper.unloadFailure = new IllegalStateException("wrapper failed");
        Throwable failure = capture(() -> world.unloadLevel(wrapper));
        check(failure == wrapper.unloadFailure && level.closeCalls.get() == 1, "Wrapper failure skipped level disposal");
        check(world.getLevel(wrapper) == null && !world.unloadLevel(wrapper), "Failed unload retained or reused ownership");
        world.close();
        check(level.closeCalls.get() == 1, "World close repeated failed unload cleanup");
        assertMapsCleared(world);
    }

    private static void constructorReentrantCloseCannotPublishAfterCleanup() throws Exception
    {
        DhClientWorld world = world();
        KeyedWrapper wrapper = new KeyedWrapper("constructor-close");
        DhClientLevel.constructorHook = level -> world.close();
        check(world.getOrLoadLevel(wrapper) == null, "Constructor-close returned a level");
        check(DhClientLevel.lastCreated.closeCalls.get() == 1, "Constructor-close orphaned level before publication");
        check(world.saveStructure.closeCalls == 1 && wrapper.unloadCalls == 1, "Constructor-close skipped final cleanup");
        check(ClientApi.INSTANCE.loadWaitingCalls == 0, "Constructor-close delivered waiting chunks");
        assertMapsCleared(world);
    }

    private static void inactivityCleanupDoesNotInvertWrapperAndWorldLocks() throws Exception
    {
        DhClientWorld world = world();
        CountDownLatch cleanupOwnsWrapper = new CountDownLatch(1);
        CountDownLatch markAccessAttempted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        KeyedWrapper wrapper = new KeyedWrapper("wrapper-lock")
        {
            @Override public void markAccessed()
            {
                markAccessAttempted.countDown();
                synchronized (this) { }
            }
        };
        // Match ClientLevelWrapper.tickCleanup(): own wrapper before entering world.unloadLevel.
        Thread cleanup = new Thread(() ->
        {
            try
            {
                synchronized (wrapper)
                {
                    cleanupOwnsWrapper.countDown();
                    await(markAccessAttempted);
                    world.unloadLevel(wrapper);
                }
            }
            catch (Throwable e) { failure.set(e); }
        });
        cleanup.setDaemon(true);
        cleanup.start();
        await(cleanupOwnsWrapper);
        Thread render = new Thread(() ->
        {
            try { world.getOrLoadLevel(wrapper); }
            catch (Throwable e) { failure.set(e); }
        });
        render.setDaemon(true);
        render.start();
        cleanup.join(1000);
        render.join(1000);
        check(!cleanup.isAlive() && !render.isAlive(), "Inactivity cleanup inverted wrapper/world lock order");
        check(failure.get() == null, "Wrapper lock fixture failed: " + failure.get());
        check(world.getLevel(wrapper) != null, "Creation did not resume after wrapper cleanup");
        world.close();
        check(DhClientLevel.lastCreated.closeCalls.get() == 1, "Lock-order test orphaned created level");
        assertMapsCleared(world);
    }

    private static void identifierIsCapturedBeforeConstruction() throws Exception
    {
        DhClientWorld world = world();
        KeyedWrapper wrapper = new KeyedWrapper("captured-id")
        {
            @Override public String getDhIdentifier()
            {
                if (DhClientLevel.lastCreated != null) { throw new IllegalStateException("identifier unavailable after construction"); }
                return super.getDhIdentifier();
            }
        };
        check(world.getOrLoadLevel(wrapper) != null, "Publication re-evaluated an identifier after taking ownership");
        world.close();
        check(DhClientLevel.lastCreated.closeCalls.get() == 1, "Identifier failure orphaned constructed level");
        assertMapsCleared(world);
    }

    private static void staleNativeWorldCannotResolveReplacementWithSameIdentifier() throws Exception
    {
        DhClientWorld world = world();
        KeyedWrapper priorVisit = new KeyedWrapper("the_end:1");
        KeyedWrapper currentVisit = new KeyedWrapper("the_end:1");
        DhClientLevel oldLevel = world.getOrLoadLevel(priorVisit);
        DhClientLevel currentLevel = world.getOrLoadLevel(currentVisit);
        check(oldLevel != currentLevel && oldLevel.closeCalls.get() == 1, "Replacement did not close old End visit");
        check(world.getLevel(priorVisit) == null, "Delayed chunk from old End visit resolved current level");
        check(world.getLevel(currentVisit) == currentLevel, "Current End visit could not resolve its own level");
        world.close();
        check(currentLevel.closeCalls.get() == 1, "End replacement did not close exactly once");
        assertMapsCleared(world);
    }

    private static void sameNativeWorldAliasCanResolveItsLevel() throws Exception
    {
        DhClientWorld world = world();
        Object nativeWorld = new Object();
        KeyedWrapper current = new KeyedWrapper("same-key")
        { @Override public Object getWrappedMcObject() { return nativeWorld; } };
        Wrapper alias = new Wrapper("same-key")
        { @Override public Object getWrappedMcObject() { return nativeWorld; } };
        DhClientLevel expected = world.getOrLoadLevel(current);
        check(world.getLevel(alias) == expected, "Same-world wrapper alias was rejected");
        world.close();
        assertMapsCleared(world);
    }

    private static void nullNativeWorldDoesNotMatchAnotherWrapper() throws Exception
    {
        DhClientWorld world = world();
        KeyedWrapper current = new KeyedWrapper("same-key")
        { @Override public Object getWrappedMcObject() { return null; } };
        Wrapper unrelated = new Wrapper("same-key")
        { @Override public Object getWrappedMcObject() { return null; } };
        DhClientLevel expected = world.getOrLoadLevel(current);
        check(world.getLevel(current) == expected, "Exact null-world wrapper was rejected");
        check(world.getLevel(unrelated) == null, "Two null native worlds were treated as identical");
        world.close();
        assertMapsCleared(world);
    }

    private static void await(CountDownLatch latch)
    {
        try { if (!latch.await(2, TimeUnit.SECONDS)) { throw new AssertionError("Fixture latch timed out"); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private static DhClientWorld world()
    {
        ApiEventInjector.INSTANCE.handler = event -> { };
        DhClientLevel.constructorHook = level -> { };
        DhClientLevel.lastCreated = null;
        ClientApi.INSTANCE.loadWaitingCalls = 0;
        return new DhClientWorld();
    }
    private static DhClientLevel add(DhClientWorld world, String key, Wrapper wrapper) throws Exception
    {
        DhClientLevel level = new DhClientLevel(world.saveStructure, wrapper, world.networkState);
        levels(world).put(key, level);
        wrappers(world).put(key, new HashSet<>(Collections.singleton(wrapper)));
        initRequests(world).add(wrapper);
        return level;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, DhClientLevel> levels(DhClientWorld world) throws Exception
    { return (Map<String, DhClientLevel>) field(world, "clientLevelByDhId"); }
    @SuppressWarnings("unchecked")
    private static Map<String, Set<IClientLevelWrapper>> wrappers(DhClientWorld world) throws Exception
    { return (Map<String, Set<IClientLevelWrapper>>) field(world, "clientLevelWrapperSetByDhId"); }
    @SuppressWarnings("unchecked")
    private static Set<Object> initRequests(DhClientWorld world) throws Exception
    { return (Set<Object>) field(world, "levelInitRequestedClientLevels"); }
    private static Object field(DhClientWorld world, String name) throws Exception
    {
        Field field = DhClientWorld.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(world);
    }
    private static void assertMapsCleared(DhClientWorld world) throws Exception
    {
        check(levels(world).isEmpty(), "Client level map retained closed levels");
        check(wrappers(world).isEmpty(), "Wrapper-set map retained world references");
        check(initRequests(world).isEmpty(), "Pending level-init set retained world references");
    }
    private static void assertTimerCancelled(TimerUtil.TrackingTimer timer)
    {
        check(timer.cancelCalls == 1, "Tick timer was not cancelled exactly once");
        boolean rejected = false;
        try { timer.schedule(new TimerTask() { @Override public void run() { } }, 0); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "JDK timer still accepts work after world shutdown");
    }
    private static Throwable capture(Runnable action)
    {
        try { action.run(); }
        catch (Throwable error) { return error; }
        throw new AssertionError("Expected cleanup failure");
    }
    private static boolean contains(Throwable root, Throwable expected)
    { return contains(root, expected, Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>())); }
    private static boolean contains(Throwable root, Throwable expected, Set<Throwable> seen)
    {
        if (root == expected) return true;
        if (root == null || !seen.add(root)) return false;
        if (contains(root.getCause(), expected, seen)) return true;
        for (Throwable suppressed : root.getSuppressed()) if (contains(suppressed, expected, seen)) return true;
        return false;
    }
    private static void check(boolean condition, String message)
    { assertions++; if (!condition) throw new AssertionError(message); }
    private static class Wrapper implements IClientLevelWrapper
    {
        final String id;
        int unloadCalls;
        Throwable unloadFailure;
        Wrapper(String id) { this.id = id; }
        @Override public String getDhIdentifier() { return this.id; }
        @Override public void onUnload()
        {
            this.unloadCalls++;
            if (this.unloadFailure instanceof RuntimeException) throw (RuntimeException) this.unloadFailure;
            if (this.unloadFailure instanceof Error) throw (Error) this.unloadFailure;
        }
        @Override public void markAccessed() { }
        @Override public Object getWrappedMcObject() { return this; }
        @Override public String getDimensionName() { return this.id; }
    }
    private static class KeyedWrapper extends Wrapper implements IServerKeyedClientLevel
    {
        KeyedWrapper(String id) { super(id); }
        @Override public String getServerKey() { return "test-server"; }
        @Override public String getServerLevelKey() { return this.id; }
    }

}
