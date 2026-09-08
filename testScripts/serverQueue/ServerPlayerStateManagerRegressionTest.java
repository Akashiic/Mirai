package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Compiles the production manager with explicit dependency fakes, never a copy of its algorithm. */
public final class ServerPlayerStateManagerRegressionTest
{
    private static int assertions;
    public static void main(String[] args) throws Exception
    {
        prejoinWaitsForHandlersAndPreservesOrder();
        prejoinPeerAndMessageLimits();
        prejoinExpiryAndDisconnectCleanup();
        overflowClosesDhSessionWithoutBlockingOtherPlayers();
        failedHandlerReleasesDrainOwnership();
        staleDrainCannotConsumeNewSession();
        closeReleasesEveryoneEvenWhenOneCloseFails();
        System.out.println("ServerPlayerStateManager regressions passed: " + assertions + " assertions.");
    }

    private static void prejoinWaitsForHandlersAndPreservesOrder() throws Exception
    {
        ServerPlayerStateManager manager = new ServerPlayerStateManager();
        Player player = new Player();
        manager.handlePluginMessage(player, message(1));
        ServerPlayerState state = manager.registerJoinedPlayer(player);
        manager.handlePluginMessage(player, message(2));
        check(state.networkSession.handled.isEmpty(), "Messages ran before level handlers were installed");
        check(manager.registerJoinedPlayer(player) == state, "Duplicate join leaked/replaced session");
        manager.handlePluginMessagesFromQueue(state);
        manager.handlePluginMessage(player, message(3));
        check(state.networkSession.handled.equals(Arrays.asList(1, 2, 3)), "FIFO or immediate post-ready delivery broke");
        manager.unregisterLeftPlayer(player);
        check(queues(manager).isEmpty(), "Disconnect retained queue");
        check(state.networkSession.isClosed() && state.closeCalls == 1, "Disconnect did not close session/resources");
    }

    private static void prejoinPeerAndMessageLimits() throws Exception
    {
        ServerPlayerStateManager manager = new ServerPlayerStateManager();
        Player first = new Player();
        for (int index = 0; index < 32; index++) manager.handlePluginMessage(first, message(index));
        check(queueSize(manager, first) == ServerPlayerStateManager.MAX_PREJOIN_MESSAGES_PER_PLAYER,
                "Prejoin message limit not enforced");
        for (int index = 1; index < 1000; index++) manager.handlePluginMessage(new Player(), message(index));
        check(queues(manager).size() == ServerPlayerStateManager.MAX_PREJOIN_PLAYERS, "Prejoin peer map is unbounded");
        check(manager.getDroppedMessageCount() == 32 - 8 + 1000 - 64, "Rejected messages not accounted");
        ServerPlayerState state = manager.registerJoinedPlayer(first);
        manager.handlePluginMessagesFromQueue(state);
        check(state.networkSession.handled.equals(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7)), "Prejoin cap lost first handshake");
        manager.close();
        check(queues(manager).isEmpty(), "Shutdown retained prejoin peers");
    }

    private static void prejoinExpiryAndDisconnectCleanup() throws Exception
    {
        ServerPlayerStateManager manager = new ServerPlayerStateManager();
        Player pending = new Player();
        Player connected = new Player();
        manager.handlePluginMessage(pending, message(1));
        ServerPlayerState active = manager.registerJoinedPlayer(connected);
        manager.handlePluginMessage(connected, message(2));
        Method prune = ServerPlayerStateManager.class.getDeclaredMethod("pruneExpiredPrejoinQueues", long.class);
        prune.setAccessible(true);
        prune.invoke(manager, System.nanoTime() + ServerPlayerStateManager.PREJOIN_TIMEOUT_NANOS + 1);
        check(!queues(manager).containsKey(pending), "Expired prejoin queue survived cleanup");
        check(queues(manager).containsKey(connected), "Expiry removed joined player before readiness");
        manager.handlePluginMessagesFromQueue(active);
        check(active.networkSession.handled.equals(Arrays.asList(2)), "Expiry discarded a joined player's message");
        manager.handlePluginMessage(pending, message(3));
        manager.unregisterLeftPlayer(pending);
        check(!queues(manager).containsKey(pending), "Disconnect without join retained pending queue");
        manager.close();
    }

    private static void overflowClosesDhSessionWithoutBlockingOtherPlayers() throws Exception
    {
        ServerPlayerStateManager manager = new ServerPlayerStateManager();
        Player player = new Player();
        ServerPlayerState state = manager.registerJoinedPlayer(player);
        manager.handlePluginMessagesFromQueue(state);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        state.networkSession.handler = ignored -> { entered.countDown(); await(release); };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread consumer = start(() -> manager.handlePluginMessage(player, message(-1)), failure);
        try
        {
            await(entered);
            for (int index = 0; index < 128; index++) manager.handlePluginMessage(player, message(index));
            check(queueSize(manager, player) == 128, "Connected queue did not retain exact permitted burst");
            ServerPlayerState other = manager.registerJoinedPlayer(new Player());
            manager.handlePluginMessagesFromQueue(other);
            manager.handlePluginMessage(other.getServerPlayer(), message(99));
            check(other.networkSession.handled.equals(Arrays.asList(99)), "One handler held global lifecycle lock");
            manager.handlePluginMessage(player, message(128));
            check(state.networkSession.isClosed(), "Overflow did not close DH session");
            check(queueSize(manager, player) == 0, "Overflow retained queued payloads");
            check(manager.getDroppedMessageCount() == 1, "Overflow not counted");
        }
        finally { release.countDown(); join(consumer, failure); manager.close(); }
    }

    private static void failedHandlerReleasesDrainOwnership() throws Exception
    {
        ServerPlayerStateManager manager = new ServerPlayerStateManager();
        Player player = new Player();
        manager.handlePluginMessage(player, message(1));
        manager.handlePluginMessage(player, message(2));
        ServerPlayerState state = manager.registerJoinedPlayer(player);
        AtomicBoolean first = new AtomicBoolean(true);
        state.networkSession.handler = ignored -> { if (first.getAndSet(false)) throw new IllegalStateException("injected"); };
        boolean failed = false;
        try { manager.handlePluginMessagesFromQueue(state); }
        catch (IllegalStateException expected) { failed = true; }
        check(failed, "Injected handler failure did not reach harness");
        manager.handlePluginMessage(player, message(3));
        check(state.networkSession.handled.equals(Arrays.asList(1, 2, 3)), "Failure permanently wedged the drain lock");
        check(queueSize(manager, player) == 0, "Messages left stranded after retry");
        manager.close();
    }

    private static void staleDrainCannotConsumeNewSession() throws Exception
    {
        ServerPlayerStateManager manager = new ServerPlayerStateManager();
        Player player = new Player();
        ServerPlayerState old = manager.registerJoinedPlayer(player);
        manager.handlePluginMessagesFromQueue(old);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        old.networkSession.handler = ignored -> { entered.countDown(); await(release); };
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread consumer = start(() -> manager.handlePluginMessage(player, message(1)), failure);
        try
        {
            await(entered);
            manager.handlePluginMessage(player, message(2));
            manager.unregisterLeftPlayer(player);
            ServerPlayerState replacement = manager.registerJoinedPlayer(player);
            manager.handlePluginMessage(player, message(10));
            manager.handlePluginMessagesFromQueue(old);
            check(replacement.networkSession.handled.isEmpty(), "Stale flush enabled a new session before handlers");
            manager.handlePluginMessagesFromQueue(replacement);
            check(replacement.networkSession.handled.equals(Arrays.asList(10)), "New session consumed stale messages");
        }
        finally { release.countDown(); join(consumer, failure); }
        check(old.networkSession.handled.equals(Arrays.asList(1)), "Stale drain continued after disconnect");
        manager.close();
    }

    private static void closeReleasesEveryoneEvenWhenOneCloseFails() throws Exception
    {
        ServerPlayerStateManager manager = new ServerPlayerStateManager();
        ServerPlayerState first = manager.registerJoinedPlayer(new Player());
        ServerPlayerState second = manager.registerJoinedPlayer(new Player());
        first.closeFailure = new IllegalStateException("injected cleanup failure");
        boolean failed = false;
        try { manager.close(); }
        catch (IllegalStateException expected) { failed = expected == first.closeFailure; }
        check(failed, "Cleanup failure was hidden");
        check(first.closeCalls == 1 && second.closeCalls == 1, "One cleanup failure skipped another player");
        check(first.networkSession.isClosed() && second.networkSession.isClosed(), "Session close markers missing");
        check(queues(manager).isEmpty() && manager.getConnectedPlayers().isEmpty(), "Shutdown retained state");
        manager.close();
        manager.handlePluginMessage(new Player(), message(1));
        manager.handlePluginMessagesFromQueue(first);
        check(queues(manager).isEmpty(), "Traffic/stale drain resurrected a closed manager");
        boolean refused = false;
        try { manager.registerJoinedPlayer(new Player()); }
        catch (IllegalStateException expected) { refused = true; }
        check(refused, "Join resurrected a closed manager");
        check(first.closeCalls == 1 && second.closeCalls == 1, "Close was not idempotent");
    }

    private static AbstractNetworkMessage message(int id) { return new AbstractNetworkMessage(id); }
    private static Map<?, ?> queues(ServerPlayerStateManager manager) throws Exception
    {
        Field field = ServerPlayerStateManager.class.getDeclaredField("messageQueueByPlayerWrapper");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(manager);
    }
    private static int queueSize(ServerPlayerStateManager manager, IServerPlayerWrapper player) throws Exception
    {
        Object state = queues(manager).get(player);
        Field field = state.getClass().getDeclaredField("messageQueue");
        field.setAccessible(true);
        return ((Queue<?>) field.get(state)).size();
    }
    private static Thread start(Runnable action, AtomicReference<Throwable> failure)
    {
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable thrown) { failure.set(thrown); } });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
    private static void join(Thread thread, AtomicReference<Throwable> failure) throws Exception
    {
        thread.join(5000);
        check(!thread.isAlive(), "Drain thread failed to terminate");
        if (failure.get() != null) throw new AssertionError("Drain thread failed", failure.get());
    }
    private static void await(CountDownLatch latch)
    {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Latch timeout/deadlock"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void check(boolean condition, String message)
    {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static final class Player implements IServerPlayerWrapper { }
}
