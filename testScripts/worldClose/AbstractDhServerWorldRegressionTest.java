package com.seibel.distanthorizons.core.world;

import com.seibel.distanthorizons.core.level.AbstractDhServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses the actual world production source with collaborators replaced only at boundaries. */
public final class AbstractDhServerWorldRegressionTest
{
    private static int assertions;

    public static void main(String[] args)
    {
        managerFailureStillClosesEveryLevel();
        exceptionalWorkersCompleteTheirFuturesAndPreserveEveryFailure();
        unloadAndEventFailuresDoNotSkipLevelClose();
        aliasedLevelsCloseExactlyOnceAndShutdownIsIdempotent();
        nullWrapperStillClosesItsLevel();
        levelShutdownTasksStartBeforeJoining();
        disconnectAlwaysReleasesPlayerSession();
        System.out.println("AbstractDhServerWorld regressions passed: " + assertions + " assertions.");
    }

    private static void managerFailureStillClosesEveryLevel()
    {
        World world = world();
        Level first = world.add(new Wrapper());
        Level second = world.add(new Wrapper());
        RuntimeException expected = new IllegalStateException("manager shutdown failure");
        world.getServerPlayerStateManager().closeFailure = expected;
        Throwable actual = capture(world::close);
        check(actual == expected, "Manager failure was hidden or replaced");
        check(first.closeCalls.get() == 1 && second.closeCalls.get() == 1, "Manager failure skipped level cleanup");
        check(first.wrapper.unloadCalls == 1 && second.wrapper.unloadCalls == 1, "Manager failure skipped unload callbacks");
        check(world.getLoadedLevelCount() == 0, "Failed shutdown retained level map");
        world.close();
        check(world.getServerPlayerStateManager().closeCalls == 1, "Retry repeated failed manager cleanup");
        check(first.closeCalls.get() == 1 && second.closeCalls.get() == 1, "Retry repeated level cleanup");
    }

    private static void exceptionalWorkersCompleteTheirFuturesAndPreserveEveryFailure()
    {
        World world = world();
        Level first = world.add(new Wrapper());
        Level second = world.add(new Wrapper());
        Level third = world.add(new Wrapper());
        RuntimeException firstFailure = new IllegalArgumentException("first level worker failed");
        Error secondFailure = new LinkageError("second level worker failed");
        first.closeFailure = firstFailure;
        second.closeFailure = secondFailure;
        Throwable observed = capture(world::close);
        check(contains(observed, firstFailure), "Worker RuntimeException absent from reported causes");
        check(contains(observed, secondFailure), "Worker Error absent from reported causes");
        check(first.closeCalls.get() == 1 && second.closeCalls.get() == 1 && third.closeCalls.get() == 1,
                "One failing worker prevented another level from closing");
        check(world.getLoadedLevelCount() == 0, "Worker failure left map populated");
        // The runner kills the Java process after five seconds if join never completes.
    }

    private static void unloadAndEventFailuresDoNotSkipLevelClose()
    {
        World world = world();
        Level unloadFailureLevel = world.add(new Wrapper());
        Level eventFailureLevel = world.add(new Wrapper());
        Level unaffected = world.add(new Wrapper());
        Error unloadFailure = new LinkageError("wrapper unload failure");
        RuntimeException eventFailure = new IllegalStateException("event listener failure");
        unloadFailureLevel.wrapper.unloadFailure = unloadFailure;
        AtomicInteger successfulEvents = new AtomicInteger();
        ApiEventInjector.INSTANCE.handler = event ->
        {
            if (event.wrapper == eventFailureLevel.wrapper) throw eventFailure;
            successfulEvents.incrementAndGet();
        };
        Throwable actual = capture(world::close);
        check(contains(actual, unloadFailure), "Wrapper unload failure was lost");
        check(contains(actual, eventFailure), "Event listener failure was lost");
        check(unloadFailureLevel.closeCalls.get() == 1, "Wrapper failure skipped its level.close");
        check(eventFailureLevel.closeCalls.get() == 1, "Event failure skipped its level.close");
        check(unaffected.closeCalls.get() == 1 && successfulEvents.get() == 1, "Fault skipped unaffected level/event");
        check(world.getLoadedLevelCount() == 0, "Unload/event failures prevented map cleanup");
    }

    private static void aliasedLevelsCloseExactlyOnceAndShutdownIsIdempotent()
    {
        World world = world();
        Level level = world.add(new Wrapper());
        world.alias(new Wrapper(), level);
        AtomicInteger events = new AtomicInteger();
        ApiEventInjector.INSTANCE.handler = ignored -> events.incrementAndGet();
        check(world.getLoadedLevelCount() == 2, "Test did not create two map aliases");
        world.close();
        world.close();
        check(level.closeCalls.get() == 1, "Two wrappers or repeated close duplicated level.close");
        check(level.wrapper.unloadCalls == 1 && events.get() == 1, "Aliased wrapper/event cleanup duplicated");
        check(world.getServerPlayerStateManager().closeCalls == 1, "World close was not idempotent");
        check(world.getLoadedLevelCount() == 0, "Successful shutdown retained aliases");
    }

    private static void nullWrapperStillClosesItsLevel()
    {
        World world = world();
        Level level = new Level(null);
        world.alias(new Wrapper(), level);
        AtomicInteger events = new AtomicInteger();
        ApiEventInjector.INSTANCE.handler = ignored -> events.incrementAndGet();
        world.close();
        check(level.closeCalls.get() == 1, "Null wrapper skipped level.close");
        check(events.get() == 0, "Null wrapper was passed to unload event");
    }

    private static void levelShutdownTasksStartBeforeJoining()
    {
        World world = world();
        Level first = world.add(new Wrapper());
        Level second = world.add(new Wrapper());
        CountDownLatch bothStarted = new CountDownLatch(2);
        Runnable barrier = () ->
        {
            bothStarted.countDown();
            try
            {
                if (!bothStarted.await(2, TimeUnit.SECONDS)) throw new AssertionError("Sequential scheduling blocked shutdown");
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        };
        first.closeHook = barrier;
        second.closeHook = barrier;
        world.close();
        check(bothStarted.getCount() == 0, "Level workers were not started before joining");
        check(first.closeCalls.get() == 1 && second.closeCalls.get() == 1, "Parallel cleanup missed a level");
    }

    private static void disconnectAlwaysReleasesPlayerSession()
    {
        World world = world();
        world.removePlayer(() -> null);
        check(world.getServerPlayerStateManager().unregisterCalls == 1, "Null level skipped session unregister");
        Wrapper missing = new Wrapper();
        world.removePlayer(() -> missing);
        check(world.getServerPlayerStateManager().unregisterCalls == 2, "Unloaded dimension skipped unregister");
        Level level = world.add(new Wrapper());
        RuntimeException expected = new IllegalStateException("remove player failed");
        level.removeFailure = expected;
        check(capture(() -> world.removePlayer(() -> level.wrapper)) == expected, "Remove failure was hidden");
        check(world.getServerPlayerStateManager().unregisterCalls == 3, "Remove callback failure skipped unregister");
        world.close();
    }

    private static World world()
    {
        ApiEventInjector.INSTANCE.handler = event -> { };
        return new World();
    }
    private static Throwable capture(Runnable action)
    {
        try { action.run(); }
        catch (Throwable error) { return error; }
        throw new AssertionError("Expected a cleanup error");
    }
    private static boolean contains(Throwable root, Throwable expected)
    {
        return contains(root, expected, Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>()));
    }
    private static boolean contains(Throwable root, Throwable expected, Set<Throwable> visited)
    {
        if (root == expected) return true;
        if (root == null || !visited.add(root)) return false;
        if (contains(root.getCause(), expected, visited)) return true;
        for (Throwable suppressed : root.getSuppressed()) if (contains(suppressed, expected, visited)) return true;
        return false;
    }
    private static void check(boolean condition, String message)
    {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void fail(Throwable error)
    {
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
    }
    private static final class Wrapper implements IServerLevelWrapper
    {
        int unloadCalls;
        Throwable unloadFailure;
        @Override public void onUnload() { this.unloadCalls++; fail(this.unloadFailure); }
    }
    private static final class Level extends AbstractDhServerLevel
    {
        final Wrapper wrapper;
        final AtomicInteger closeCalls = new AtomicInteger();
        Throwable closeFailure;
        RuntimeException removeFailure;
        Runnable closeHook = () -> { };
        Level(Wrapper wrapper) { this.wrapper = wrapper; }
        @Override public IServerLevelWrapper getServerLevelWrapper() { return this.wrapper; }
        @Override public void close()
        {
            this.closeCalls.incrementAndGet();
            this.closeHook.run();
            fail(this.closeFailure);
        }
        @Override public void removePlayer(IServerPlayerWrapper player)
        {
            if (this.removeFailure != null) throw this.removeFailure;
        }
    }
    private static final class World extends AbstractDhServerWorld<Level>
    {
        World() { super(EWorldEnvironment.SERVER_ONLY); }
        Level add(Wrapper wrapper)
        {
            Level level = new Level(wrapper);
            this.dhLevelByLevelWrapper.put(wrapper, level);
            return level;
        }
        void alias(ILevelWrapper wrapper, Level level) { this.dhLevelByLevelWrapper.put(wrapper, level); }
        @Override public IDhLevel getOrLoadLevel(ILevelWrapper wrapper) { return this.getLevel(wrapper); }
        @Override public boolean unloadLevel(ILevelWrapper wrapper) { return this.dhLevelByLevelWrapper.remove(wrapper) != null; }
    }
}
