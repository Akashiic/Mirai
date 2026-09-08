package com.seibel.distanthorizons.core.world;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.core.file.structure.LocalSaveStructure;
import com.seibel.distanthorizons.core.level.AbstractDhServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerState;
import com.seibel.distanthorizons.core.multiplayer.server.ServerPlayerStateManager;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractDhServerWorld<TDhServerLevel extends AbstractDhServerLevel> extends AbstractDhWorld implements IDhServerWorld
{
	/** 
	 * Concurrent since levels can be added/remove while other processing is happening.
	 * (Otherwise we may need to just put the logic in a lock.
	 */
	protected final ConcurrentHashMap<ILevelWrapper, TDhServerLevel> dhLevelByLevelWrapper = new ConcurrentHashMap<>();
	public final LocalSaveStructure saveStructure = new LocalSaveStructure();
	
	private final ServerPlayerStateManager serverPlayerStateManager;
	private final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public AbstractDhServerWorld(EWorldEnvironment worldEnvironment)
	{
		super(worldEnvironment);
		this.serverPlayerStateManager = new ServerPlayerStateManager();
	}
	
	
	//=================//
	// player handling //
	//=================//
	
	@Override
	public ServerPlayerStateManager getServerPlayerStateManager()
	{
		return this.serverPlayerStateManager;
	}
	
	@Override
	public void addPlayer(IServerPlayerWrapper serverPlayer)
	{
		ServerPlayerState playerState = this.serverPlayerStateManager.registerJoinedPlayer(serverPlayer);
		AbstractDhServerLevel serverLevel = (AbstractDhServerLevel) this.getOrLoadServerLevel(serverPlayer.getLevel());
		if (serverLevel == null)
		{
			return;
		}
		
		serverLevel.addPlayer(serverPlayer);
		
		Iterator<TDhServerLevel> it = this.dhLevelByLevelWrapper.values().stream().distinct().iterator();
		while (it.hasNext())
		{
			TDhServerLevel level = it.next();
			level.registerNetworkHandlers(playerState);
		}
		
		this.serverPlayerStateManager.handlePluginMessagesFromQueue(playerState);
	}
	
	@Override
	public void removePlayer(IServerPlayerWrapper serverPlayer)
	{
		try
		{
			IServerLevelWrapper playerLevel = serverPlayer.getLevel();
			if (playerLevel == null) { return; }
			TDhServerLevel serverLevel = this.getLevel(playerLevel);
			if (serverLevel != null) { serverLevel.removePlayer(serverPlayer); }
		}
		finally
		{
			// Disconnect must close the session even after its dimension has unloaded.
			this.serverPlayerStateManager.unregisterLeftPlayer(serverPlayer);
		}
	}
	
	@Override
	public void changePlayerLevel(IServerPlayerWrapper player, IServerLevelWrapper originLevel, IServerLevelWrapper destinationLevel)
	{
		this.getLevel(destinationLevel).addPlayer(player);
		this.getLevel(originLevel).removePlayer(player);
	}
	
	
	
	//================//
	// level handling //
	//================//
	
	@Override
	public TDhServerLevel getLevel(@NotNull ILevelWrapper wrapper) { return this.dhLevelByLevelWrapper.get(wrapper); }
	@Override
	public Iterable<? extends IDhLevel> getAllLoadedLevels() 
	{
		// hash set wrapper is used to filter out duplicate levels,
		// which can happen when on a singleplayer world and both a server/client level wrapper
		// are active for the same dimension
		return new HashSet<>(this.dhLevelByLevelWrapper.values()); 
	}
	@Override
	public int getLoadedLevelCount() { return this.dhLevelByLevelWrapper.size(); }
	
	
	
	//================//
	// base overrides //
	//================//
	
	@Override
	public void close()
	{
		if (!this.closing.compareAndSet(false, true)) { return; }
		Throwable failure = null;
		try
		{
			// Stop ingress before closing data consumed by network handlers.
			this.serverPlayerStateManager.close();
		}
		catch (RuntimeException | Error error) { failure = error; }

		ArrayList<CompletableFuture<Void>> closeFutures = new ArrayList<>();
		// A server/client wrapper may point at the same level.
		for (TDhServerLevel level : new HashSet<>(this.dhLevelByLevelWrapper.values()))
		{
			try
			{
				IServerLevelWrapper wrapper = level.getServerLevelWrapper();
				if (wrapper != null)
				{
					wrapper.onUnload();
					ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelUnloadEvent.class,
						new DhApiLevelUnloadEvent.EventParam(wrapper));
				}
			}
			catch (RuntimeException | Error error) { failure = appendCloseFailure(failure, error); }

			CompletableFuture<Void> closeFuture = new CompletableFuture<>();
			closeFutures.add(closeFuture);
			Runnable closeLevel = () ->
			{
				try
				{
					level.close();
					closeFuture.complete(null);
				}
				catch (Throwable error) { closeFuture.completeExceptionally(error); }
			};
			try { new Thread(closeLevel, "level shutdown").start(); }
			catch (RuntimeException | Error error)
			{
				// If thread creation is unavailable, still attempt this level's cleanup.
				try { level.close(); }
				catch (RuntimeException | Error cleanupError)
				{
					if (error != cleanupError) { error.addSuppressed(cleanupError); }
				}
				closeFuture.completeExceptionally(error);
			}
		}

		for (CompletableFuture<Void> future : closeFutures)
		{
			try { future.join(); }
			catch (RuntimeException error) { failure = appendCloseFailure(failure, error); }
		}
		this.dhLevelByLevelWrapper.clear();
		if (failure instanceof Error) { throw (Error) failure; }
		if (failure != null) { throw (RuntimeException) failure; }
		LOGGER.info("Closed DhWorld of type [" + this.environment + "].");
	}

	private static Throwable appendCloseFailure(Throwable first, Throwable additional)
	{
		if (first == null) { return additional; }
		if (first != additional) { first.addSuppressed(additional); }
		return first;
	}
	
}
