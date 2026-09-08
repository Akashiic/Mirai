package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.multiplayer.fullData.SharedBandwidthLimit;
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import com.seibel.distanthorizons.core.network.session.SessionClosedException;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class ServerPlayerStateManager implements AutoCloseable
{
	/** Object-count limits; the transport must independently bound decoded payload sizes. */
	static final int MAX_QUEUED_MESSAGES_PER_PLAYER = 128;
	static final int MAX_PREJOIN_MESSAGES_PER_PLAYER = 8;
	static final int MAX_PREJOIN_PLAYERS = 64;
	static final long PREJOIN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

	private final ConcurrentMap<IServerPlayerWrapper, ServerPlayerState> connectedPlayerStateByPlayerWrapper = new ConcurrentHashMap<>();
	/** All queue/map transitions use lifecycleLock; callbacks must execute outside it. */
	private final Map<IServerPlayerWrapper, MessageQueueState> messageQueueByPlayerWrapper = new HashMap<>();
	private final Object lifecycleLock = new Object();
	private boolean closed;
	private long droppedMessageCount;

	private final SharedBandwidthLimit sharedBandwidthLimit = new SharedBandwidthLimit();


	//========================//
	// player joining/leaving //
	//========================//

	public ServerPlayerState registerJoinedPlayer(IServerPlayerWrapper serverPlayer)
	{
		Objects.requireNonNull(serverPlayer, "serverPlayer");
		synchronized (this.lifecycleLock)
		{
			if (this.closed)
			{
				throw new IllegalStateException("Server player state manager is closed");
			}
			ServerPlayerState existing = this.connectedPlayerStateByPlayerWrapper.get(serverPlayer);
			if (existing != null)
			{
				return existing;
			}

			this.pruneExpiredPrejoinQueues(System.nanoTime());
			ServerPlayerState playerState = new ServerPlayerState(serverPlayer, this.sharedBandwidthLimit);
			this.messageQueueByPlayerWrapper.computeIfAbsent(serverPlayer, ignored -> new MessageQueueState());
			this.connectedPlayerStateByPlayerWrapper.put(serverPlayer, playerState);
			// AbstractDhServerWorld installs level handlers before explicitly enabling the drain.
			return playerState;
		}
	}

	public void unregisterLeftPlayer(IServerPlayerWrapper serverPlayer)
	{
		if (serverPlayer == null)
		{
			return;
		}
		ServerPlayerState playerState;
		synchronized (this.lifecycleLock)
		{
			playerState = this.connectedPlayerStateByPlayerWrapper.remove(serverPlayer);
			MessageQueueState queue = this.messageQueueByPlayerWrapper.remove(serverPlayer);
			if (queue != null)
			{
				queue.messageQueue.clear();
			}
		}
		if (playerState != null)
		{
			closePlayerState(playerState);
		}
	}


	//==========//
	// messages //
	//==========//

	public void handlePluginMessage(IServerPlayerWrapper player, AbstractNetworkMessage message)
	{
		// Neo/Forge can deliver a message before PlayerLoggedIn, or without a player.
		if (player == null || message == null)
		{
			return;
		}

		ServerPlayerState playerState;
		MessageQueueState queue;
		boolean overloaded = false;
		synchronized (this.lifecycleLock)
		{
			if (this.closed)
			{
				return;
			}
			playerState = this.connectedPlayerStateByPlayerWrapper.get(player);
			if (playerState != null && playerState.networkSession.isClosed())
			{
				return;
			}
			queue = this.messageQueueByPlayerWrapper.get(player);
			if (playerState == null)
			{
				// Expiry is lazy and uses the first arrival, never refreshed by flooding.
				this.pruneExpiredPrejoinQueues(System.nanoTime());
				queue = this.messageQueueByPlayerWrapper.get(player);
				if (queue == null && this.messageQueueByPlayerWrapper.size()
						- this.connectedPlayerStateByPlayerWrapper.size() >= MAX_PREJOIN_PLAYERS)
				{
					this.droppedMessageCount++;
					return;
				}
			}
			if (queue == null)
			{
				queue = new MessageQueueState();
				this.messageQueueByPlayerWrapper.put(player, queue);
			}
			if (queue.rejected)
			{
				return;
			}

			int limit = playerState == null ? MAX_PREJOIN_MESSAGES_PER_PLAYER : MAX_QUEUED_MESSAGES_PER_PLAYER;
			if (queue.messageQueue.size() >= limit)
			{
				this.droppedMessageCount++;
				if (playerState == null)
				{
					// Preserve the bounded early handshake; refuse additional prejoin messages.
					return;
				}
				queue.rejected = true;
				queue.messageQueue.clear();
				overloaded = true;
			}
			else
			{
				queue.messageQueue.addLast(message);
			}
		}

		if (overloaded)
		{
			// Close only DH's session; Minecraft's connection remains under the server's control.
			playerState.networkSession.close(new SessionClosedException("Incoming DH message queue limit exceeded"));
		}
		else if (playerState != null)
		{
			this.handlePluginMessagesFromQueue(playerState, queue);
		}
	}

	public void handlePluginMessagesFromQueue(ServerPlayerState playerState)
	{
		Objects.requireNonNull(playerState, "playerState");
		MessageQueueState queue;
		synchronized (this.lifecycleLock)
		{
			if (this.connectedPlayerStateByPlayerWrapper.get(playerState.getServerPlayer()) != playerState)
			{
				return;
			}
			queue = this.messageQueueByPlayerWrapper.get(playerState.getServerPlayer());
			if (queue == null)
			{
				return;
			}
			queue.ready = true;
		}
		this.handlePluginMessagesFromQueue(playerState, queue);
	}

	private void handlePluginMessagesFromQueue(ServerPlayerState playerState, MessageQueueState queue)
	{
		while (true)
		{
			synchronized (this.lifecycleLock)
			{
				if (!this.canDrain(playerState, queue) || queue.isBeingDrained || queue.messageQueue.isEmpty())
				{
					return;
				}
				queue.isBeingDrained = true;
			}
			try
			{
				while (true)
				{
					AbstractNetworkMessage message;
					synchronized (this.lifecycleLock)
					{
						if (!this.canDrain(playerState, queue) || (message = queue.messageQueue.pollFirst()) == null)
						{
							break;
						}
					}
					playerState.networkSession.tryHandleMessage(message);
				}
			}
			finally
			{
				synchronized (this.lifecycleLock)
				{
					queue.isBeingDrained = false;
				}
			}
			// Recheck after releasing ownership: an enqueue racing the last poll must not be stranded.
		}
	}

	/** Called only under lifecycleLock. Stale drains cannot consume a replacement session's queue. */
	private boolean canDrain(ServerPlayerState playerState, MessageQueueState queue)
	{
		return !this.closed && queue.ready && !queue.rejected && !playerState.networkSession.isClosed()
				&& this.connectedPlayerStateByPlayerWrapper.get(playerState.getServerPlayer()) == playerState
				&& this.messageQueueByPlayerWrapper.get(playerState.getServerPlayer()) == queue;
	}

	private void pruneExpiredPrejoinQueues(long now)
	{
		Iterator<Map.Entry<IServerPlayerWrapper, MessageQueueState>> it = this.messageQueueByPlayerWrapper.entrySet().iterator();
		while (it.hasNext())
		{
			Map.Entry<IServerPlayerWrapper, MessageQueueState> entry = it.next();
			if (!this.connectedPlayerStateByPlayerWrapper.containsKey(entry.getKey())
					&& now - entry.getValue().createdNanos >= PREJOIN_TIMEOUT_NANOS)
			{
				this.droppedMessageCount += entry.getValue().messageQueue.size();
				entry.getValue().messageQueue.clear();
				it.remove();
			}
		}
	}


	//==========//
	// shutdown //
	//==========//

	@Override
	public void close()
	{
		Collection<ServerPlayerState> players;
		synchronized (this.lifecycleLock)
		{
			if (this.closed)
			{
				return;
			}
			this.closed = true;
			players = new ArrayList<>(this.connectedPlayerStateByPlayerWrapper.values());
			this.connectedPlayerStateByPlayerWrapper.clear();
			for (MessageQueueState queue : this.messageQueueByPlayerWrapper.values())
			{
				queue.messageQueue.clear();
			}
			this.messageQueueByPlayerWrapper.clear();
		}
		Throwable failure = null;
		for (ServerPlayerState playerState : players)
		{
			try
			{
				closePlayerState(playerState);
			}
			catch (RuntimeException | Error e)
			{
				if (failure == null) failure = e;
				else if (failure != e) failure.addSuppressed(e);
			}
		}
		if (failure instanceof RuntimeException) throw (RuntimeException) failure;
		if (failure instanceof Error) throw (Error) failure;
	}

	private static void closePlayerState(ServerPlayerState playerState)
	{
		try
		{
			playerState.networkSession.close(new SessionClosedException("Server player session ended"));
		}
		finally
		{
			playerState.close();
		}
	}


	//=========//
	// getters //
	//=========//

	@Nullable
	public ServerPlayerState getConnectedPlayer(IServerPlayerWrapper player) { return this.connectedPlayerStateByPlayerWrapper.get(player); }
	public Collection<ServerPlayerState> getConnectedPlayers() { return this.connectedPlayerStateByPlayerWrapper.values(); }
	public Iterable<ServerPlayerState> getReadyPlayers() { return this.getConnectedPlayers().stream().filter(ServerPlayerState::isReady)::iterator; }
	public long getDroppedMessageCount() { synchronized (this.lifecycleLock) { return this.droppedMessageCount; } }

	private static class MessageQueueState
	{
		final ArrayDeque<AbstractNetworkMessage> messageQueue = new ArrayDeque<>();
		final long createdNanos = System.nanoTime();
		boolean ready;
		boolean rejected;
		boolean isBeingDrained;
	}
}
