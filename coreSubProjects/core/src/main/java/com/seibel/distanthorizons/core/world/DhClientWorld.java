/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.world;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.ClientPluginChannelApi;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.file.structure.ClientOnlySaveStructure;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.multiplayer.client.ClientNetworkState;
import com.seibel.distanthorizons.core.util.TimerUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class DhClientWorld extends AbstractDhWorld implements IDhClientWorld
{
	public final ClientOnlySaveStructure saveStructure;
	public final ClientNetworkState networkState = new ClientNetworkState();
	
	
	private final ConcurrentHashMap<String, DhClientLevel> clientLevelByDhId;
	/**
	 * Having a set of level wrappers is done to handle an issue where the client 
	 * level would get unloaded when jumping back and forth between dimensions. <br><br>
	 *
	 * We might have more than one {@link ILevelWrapper} pointing to the same {@link IDhLevel}
	 * since they're not immediately unloaded, and we don't want to unload the {@link IDhLevel}
	 * until all the {@link ILevelWrapper} for that {@link IDhLevel} have been unloaded. 
	 * Any stale {@link IDhLevel} references should disappear on their own after about 
	 * 30 seconds or so thanks to the automatic cleanup.
	 */
	private final Map<String, Set<IClientLevelWrapper>> clientLevelWrapperSetByDhId = new ConcurrentHashMap<>();
	
	private final Timer clientTickTimer = TimerUtil.CreateTimer("ClientTickTimer");
	private final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
	/** Serializes level ownership changes with the world shutdown snapshot. */
	private final Object levelLifecycleLock = new Object();
	/** A load/unload callback can request shutdown while holding the reentrant monitor. */
	private boolean deferredClose;
	
	public final ClientPluginChannelApi pluginChannelApi = new ClientPluginChannelApi();
	private static final long FIRST_LEVEL_LOAD_DELAY_IN_MS = 1_000;
	/** Delay loading the first level to give the server some time to respond with level to actually load */
	private long allowLoadingLevelsAfter = 0;
	private final Set</* ClientLevel */ Object> levelInitRequestedClientLevels = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
	
	
	//==============//
	// constructors //
	//==============//
	
	public DhClientWorld()
	{
		super(EWorldEnvironment.CLIENT_ONLY);
		
		this.saveStructure = new ClientOnlySaveStructure();
		this.clientLevelByDhId = new ConcurrentHashMap<>();
		
		LOGGER.info("Started DhWorld of type " + this.environment);
		
		this.pluginChannelApi.onJoinServer(networkState.getSession());
		// ClientApi starts the handshake after publishing this world and its executor.
		
		this.clientTickTimer.scheduleAtFixedRate(new TimerTask()
		{
			@Override
			public void run()
			{
				if (!DhClientWorld.this.closing.get())
				{
					DhClientWorld.this.clientLevelByDhId.values().forEach(DhClientLevel::clientTick);
				}
			}
		}, 0, IDhClientWorld.TICK_RATE_IN_MS);
	}
	
	
	
	//=========//
	// methods //
	//=========//
	
	@Override
	public DhClientLevel getOrLoadLevel(@NotNull ILevelWrapper wrapper)
	{
		if (this.closing.get() || !(wrapper instanceof IClientLevelWrapper)) { return null; }
		try
		{
			IClientLevelWrapper clientLevelWrapper = (IClientLevelWrapper) wrapper;
			// The inactivity timer holds the wrapper monitor while entering unloadLevel.
			// Never acquire that wrapper monitor while holding levelLifecycleLock.
			clientLevelWrapper.markAccessed();
			synchronized (this.levelLifecycleLock)
			{
				if (this.closing.get()) { return null; }
				String identifier = wrapper.getDhIdentifier();
				DhClientLevel storedLevel = this.clientLevelByDhId.get(identifier);
				if (storedLevel != null && storedLevel.getClientLevelWrapper() == wrapper) { return storedLevel; }
				if (storedLevel != null) { this.unloadLevel(storedLevel.getLevelWrapper()); }
				if (this.closing.get()) { return null; }
				return this.createClientLevel(clientLevelWrapper, identifier);
			}
		}
		finally { this.finishDeferredClose(); }
	}
	private DhClientLevel createClientLevel(@NotNull IClientLevelWrapper clientLevelWrapper, String identifier)
	{
		DhClientLevel level = null;
		boolean published = false;
		try
		{
			if (!this.ensureLevelKeyWhenAvailable(clientLevelWrapper) || this.closing.get())
			{
				return null;
			}
			
			level = new DhClientLevel(this.saveStructure, clientLevelWrapper, this.networkState);
			// Publish ownership before invoking extension callbacks: a reentrant unload or
			// close must find the newly constructed level and dispose it exactly once.
			this.clientLevelByDhId.put(identifier, level);
			published = true;
			this.clientLevelWrapperSetByDhId.computeIfAbsent(identifier, (dhId) -> Collections.synchronizedSet(new HashSet<>())).add(clientLevelWrapper);
			if (this.closing.get()) { return null; }
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent.EventParam(clientLevelWrapper));
			if (this.closing.get() || this.clientLevelByDhId.get(identifier) != level) { return null; }
			ClientApi.INSTANCE.loadWaitingChunksForLevel(clientLevelWrapper);
			return !this.closing.get() && this.clientLevelByDhId.get(identifier) == level ? level : null;
		}
		catch (Exception | Error e)
		{
			if (level != null && (!published || this.clientLevelByDhId.remove(identifier, level)))
			{
				this.clientLevelByDhId.remove(identifier, level);
				this.clientLevelWrapperSetByDhId.remove(identifier);
				try { this.closeDetachedLevel(level); }
				catch (RuntimeException | Error cleanupError) { appendCloseFailure(e, cleanupError); }
			}
			if (e instanceof Error) { throw (Error) e; }
			LOGGER.fatal("Failed to load client level, error: ["+e.getMessage()+"].", e);
			
			String r = MinecraftTextFormat.RED;
			String y = MinecraftTextFormat.YELLOW;
			String cf = MinecraftTextFormat.CLEAR_FORMATTING;
			
			ClientApi.INSTANCE.queueSlowChatMessage(
				r + "Distant Horizons: Client level loading failed." + cf + "\n" +
				"Unable to load level ["+y+identifier+cf+"], LODs may not appear. See log for more information. \n" +
				"");
			
			return null;
		}
	}
	
	private boolean ensureLevelKeyWhenAvailable(@NotNull IClientLevelWrapper clientLevelWrapper)
	{
		if (!this.pluginChannelApi.allowLevelLoading(clientLevelWrapper))
		{
			LOGGER.debug("Client levels in this connection are managed by the server, skipping auto-load of: ["+clientLevelWrapper+"]");
			
			// Instead of attempting to load themselves, send the config and wait for a server provided level key
			this.sendLevelInitRequestIfNeed(clientLevelWrapper);
			return false;
		}
		
		if (clientLevelWrapper instanceof IServerKeyedClientLevel)
		{
			this.sendLevelInitRequestIfNeed(clientLevelWrapper);
		}
		
		// Make non-keyed levels wait some delay since first attempt to load anything,
		// so the server can reply to the level key request
		if (!(clientLevelWrapper instanceof IServerKeyedClientLevel))
		{
			this.sendLevelInitRequestIfNeed(clientLevelWrapper);
			
			if (this.allowLoadingLevelsAfter == 0)
			{
				this.allowLoadingLevelsAfter = System.currentTimeMillis() + FIRST_LEVEL_LOAD_DELAY_IN_MS;
			}
			
			return System.currentTimeMillis() >= this.allowLoadingLevelsAfter;
		}
		
		return true;
	}
	
	private void sendLevelInitRequestIfNeed(@NotNull IClientLevelWrapper clientLevelWrapper)
	{
		Object clientLevelObject = clientLevelWrapper.getWrappedMcObject();
		if (clientLevelObject != null
			&& this.levelInitRequestedClientLevels.add(clientLevelObject))
		{
			this.networkState.sendLevelInitRequest(clientLevelWrapper.getDimensionName());
		}
	}
	
	@Override
	public DhClientLevel getLevel(@NotNull ILevelWrapper wrapper)
	{
		if (this.closing.get() || !(wrapper instanceof IClientLevelWrapper)) { return null; }
		DhClientLevel level = this.clientLevelByDhId.get(wrapper.getDhIdentifier());
		if (level == null) { return null; }
		IClientLevelWrapper currentWrapper = level.getClientLevelWrapper();
		if (currentWrapper != wrapper)
		{
			Object requestedWorld = ((IClientLevelWrapper) wrapper).getWrappedMcObject();
			// Identical persisted identifiers can belong to successive WorldClient
			// instances. A delayed update from the old visit must not resolve the new level.
			if (requestedWorld == null || currentWrapper == null || currentWrapper.getWrappedMcObject() != requestedWorld)
			{
				return null;
			}
		}
		return this.closing.get() ? null : level;
	}
	
	@Override
	public Iterable<? extends IDhLevel> getAllLoadedLevels() { return this.clientLevelByDhId.values(); }
	@Override
	public int getLoadedLevelCount() { return this.clientLevelByDhId.size(); }
	
	@Override
	public boolean unloadLevel(@NotNull ILevelWrapper wrapper)
	{
		if (this.closing.get() || !(wrapper instanceof IClientLevelWrapper)) { return false; }
		try
		{
			synchronized (this.levelLifecycleLock)
			{
				if (this.closing.get()) { return false; }
				String identifier = wrapper.getDhIdentifier();
				DhClientLevel level = this.clientLevelByDhId.get(identifier);
				Set<IClientLevelWrapper> wrapperSet = this.clientLevelWrapperSetByDhId.get(identifier);
				if (level == null || wrapperSet == null || !wrapperSet.remove(wrapper)) { return false; }
				boolean closeLevel = wrapperSet.isEmpty();
				if (closeLevel)
				{
					// Remove ownership before invoking callbacks, which may re-enter unload/close.
					this.clientLevelByDhId.remove(identifier, level);
					this.clientLevelWrapperSetByDhId.remove(identifier);
				}
				Throwable failure = null;
				try { wrapper.onUnload(); }
				catch (RuntimeException | Error error) { failure = error; }
				try { ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent.EventParam(wrapper)); }
				catch (RuntimeException | Error error) { failure = appendCloseFailure(failure, error); }
				if (closeLevel)
				{
					try { level.close(); }
					catch (RuntimeException | Error error) { failure = appendCloseFailure(failure, error); }
				}
				rethrowCloseFailure(failure);
				return true;
			}
		}
		finally { this.finishDeferredClose(); }
	}

	@Override
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		super.addDebugMenuStringsToList(messageList);
		this.networkState.addDebugMenuStringsToList(messageList);
	}
	
	@Override
	public void close()
	{
		boolean reentrant = Thread.holdsLock(this.levelLifecycleLock);
		Set<DhClientLevel> levelsToClose;
		synchronized (this.levelLifecycleLock)
		{
			if (!this.closing.compareAndSet(false, true)) { return; }
			if (reentrant)
			{
				// Joining workers inside a load listener would retain the lifecycle monitor.
				// The outermost level operation completes shutdown after leaving it.
				this.deferredClose = true;
				return;
			}
			levelsToClose = this.detachLevelsForClose();
		}
		this.closeDetachedLevels(levelsToClose);
	}

	private void finishDeferredClose()
	{
		if (Thread.holdsLock(this.levelLifecycleLock)) { return; }
		Set<DhClientLevel> levelsToClose;
		synchronized (this.levelLifecycleLock)
		{
			if (!this.deferredClose) { return; }
			this.deferredClose = false;
			levelsToClose = this.detachLevelsForClose();
		}
		this.closeDetachedLevels(levelsToClose);
	}

	/** Called only with levelLifecycleLock held; cleanup and joins happen after releasing it. */
	private Set<DhClientLevel> detachLevelsForClose()
	{
		Set<DhClientLevel> levels = new HashSet<>(this.clientLevelByDhId.values());
		this.clientLevelByDhId.clear();
		this.clientLevelWrapperSetByDhId.clear();
		this.levelInitRequestedClientLevels.clear();
		return levels;
	}

	private void closeDetachedLevels(Set<DhClientLevel> levelsToClose)
	{
		// Stop subsequent scheduled ticks before closing resources they consume.
		this.clientTickTimer.cancel();
		Throwable failure = null;
		try { this.networkState.close(); }
		catch (RuntimeException | Error error) { failure = error; }
		try { this.pluginChannelApi.reset(); }
		catch (RuntimeException | Error error) { failure = appendCloseFailure(failure, error); }

		ArrayList<CompletableFuture<Void>> closeFutures = new ArrayList<>();
		for (DhClientLevel level : levelsToClose)
		{
			try
			{
				IClientLevelWrapper wrapper = level.getClientLevelWrapper();
				if (wrapper != null)
				{
					wrapper.onUnload();
					ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelUnloadEvent.class,
						new DhApiLevelUnloadEvent.EventParam(wrapper));
				}
			}
			catch (RuntimeException | Error error) { failure = appendCloseFailure(failure, error); }

			CompletableFuture<Void> future = new CompletableFuture<>();
			closeFutures.add(future);
			Runnable closeLevel = () ->
			{
				try
				{
					level.close();
					future.complete(null);
				}
				catch (Throwable error) { future.completeExceptionally(error); }
			};
			try { new Thread(closeLevel, "client level shutdown").start(); }
			catch (RuntimeException | Error error)
			{
				try { level.close(); }
				catch (RuntimeException | Error cleanupError)
				{
					if (error != cleanupError) { error.addSuppressed(cleanupError); }
				}
				future.completeExceptionally(error);
			}
		}

		for (CompletableFuture<Void> future : closeFutures)
		{
			try { future.join(); }
			catch (RuntimeException error) { failure = appendCloseFailure(failure, error); }
		}
		try { this.saveStructure.close(); }
		catch (RuntimeException | Error error) { failure = appendCloseFailure(failure, error); }
		rethrowCloseFailure(failure);
		LOGGER.info("Closed DhWorld of type [" + this.environment + "].");
	}

	private void closeDetachedLevel(DhClientLevel level)
	{
		Throwable failure = null;
		IClientLevelWrapper wrapper = level.getClientLevelWrapper();
		try { if (wrapper != null) { wrapper.onUnload(); } }
		catch (RuntimeException | Error error) { failure = error; }
		try { if (wrapper != null) { ApiEventInjector.INSTANCE.fireAllEvents(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent.EventParam(wrapper)); } }
		catch (RuntimeException | Error error) { failure = appendCloseFailure(failure, error); }
		try { level.close(); }
		catch (RuntimeException | Error error) { failure = appendCloseFailure(failure, error); }
		rethrowCloseFailure(failure);
	}

	private static void rethrowCloseFailure(Throwable failure)
	{
		if (failure instanceof Error) { throw (Error) failure; }
		if (failure != null) { throw (RuntimeException) failure; }
	}

	private static Throwable appendCloseFailure(Throwable first, Throwable additional)
	{
		if (first == null) { return additional; }
		if (first != additional) { first.addSuppressed(additional); }
		return first;
	}
	
}
