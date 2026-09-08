package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IKeyedClientLevelManager;
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.network.event.internal.CloseInternalEvent;
import com.seibel.distanthorizons.core.network.messages.base.LevelInitMessage;
import com.seibel.distanthorizons.core.network.session.NetworkSession;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * This class is used to manage the level keys.
 */
public class ClientPluginChannelApi
{
	private static final DhLogger LOGGER = new DhLoggerBuilder()
			.fileLevelConfig(Config.Common.Logging.logNetworkEventToFile)
			.build();  
			
	private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IKeyedClientLevelManager KEYED_CLIENT_LEVEL_MANAGER = SingletonInjector.INSTANCE.get(IKeyedClientLevelManager.class);
	
	@Nullable
	public volatile NetworkSession networkSession;
	private final Object lifecycleLock = new Object();
	private long levelEpoch;
	private final IKeyedClientLevelManager keyedLevelManager;
	private final Supplier<IClientLevelWrapper> clientLevelSupplier;
	private final Supplier<AbstractDhWorld> worldSupplier;
	private final Consumer<Runnable> renderQueue;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public ClientPluginChannelApi()
	{
		this(KEYED_CLIENT_LEVEL_MANAGER, () -> MC.getWrappedClientLevel(true), SharedApi::getAbstractDhWorld,
				task -> RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread("ClientPluginChannelApi onLevelInitMessage", task));
	}

	ClientPluginChannelApi(IKeyedClientLevelManager keyedLevelManager, Supplier<IClientLevelWrapper> clientLevelSupplier,
			Supplier<AbstractDhWorld> worldSupplier, Consumer<Runnable> renderQueue)
	{
		this.keyedLevelManager = keyedLevelManager;
		this.clientLevelSupplier = clientLevelSupplier;
		this.worldSupplier = worldSupplier;
		this.renderQueue = renderQueue;
	}
	
	
	
	//============//
	// properties //
	//============//
	
	/** @return true if the level loading is handled by the server */
	public boolean allowLevelLoading(IClientLevelWrapper level)
	{
		return (this.keyedLevelManager.isEnabled() && level instanceof IServerKeyedClientLevel)
				|| !this.keyedLevelManager.isEnabled();
	}
	
	
	
	//================//
	// network events //
	//================//
	
	/** fired when this client connects to a server with DH support */
	public void onJoinServer(@NotNull NetworkSession networkSession)
	{
		Objects.requireNonNull(networkSession);
		synchronized (this.lifecycleLock)
		{
			this.levelEpoch++;
			this.keyedLevelManager.disable();
			this.networkSession = networkSession;
			networkSession.registerHandler(LevelInitMessage.class, msg -> this.onLevelInitMessage(networkSession, msg));
			networkSession.registerHandler(CloseInternalEvent.class, event -> this.onClose(networkSession));
		}
	}

	void onLevelInitMessage(NetworkSession sourceSession, LevelInitMessage msg)
	{
		final String serverKey = msg.serverKey;
		final String levelKey = msg.levelKey;
		final String dimension = msg.dimensionResourceLocation;
		if (serverKey == null || (!serverKey.isEmpty() && !serverKey.matches(LevelInitMessage.SERVER_KEY_REGEX)))
		{
			throw new IllegalArgumentException("Server sent invalid server key.");
		}
		
		if (levelKey == null || !levelKey.matches(LevelInitMessage.LEVEL_KEY_REGEX))
		{
			throw new IllegalArgumentException("Server sent invalid level key.");
		}
		
		if (dimension == null || dimension.length() > LevelInitMessage.MAX_LENGTH)
		{
			throw new IllegalArgumentException("Server sent invalid dimension identifier.");
		}

		synchronized (this.lifecycleLock)
		{
			if (sourceSession == null || sourceSession != this.networkSession || sourceSession.isClosed()) { return; }
			final long acceptedEpoch = this.levelEpoch;
			final AbstractDhWorld acceptedWorld = this.worldSupplier.get();
			final Object acceptedLevel = worldIdentity(this.clientLevelSupplier.get());
			if (acceptedWorld == null) { return; }

			this.renderQueue.accept(() ->
			{
				synchronized (this.lifecycleLock)
				{
					IClientLevelWrapper clientLevel = this.clientLevelSupplier.get();
					if (acceptedEpoch != this.levelEpoch || sourceSession != this.networkSession || sourceSession.isClosed()
							|| this.worldSupplier.get() != acceptedWorld || worldIdentity(clientLevel) != acceptedLevel) { return; }
					IServerKeyedClientLevel existing = this.keyedLevelManager.getServerKeyedLevel(clientLevel);
					if (existing == null || !existing.getServerKey().equals(serverKey) || !existing.getServerLevelKey().equals(levelKey))
					{
						LOGGER.info("Loading level with key: [" + levelKey + "].");
						IServerKeyedClientLevel keyedLevel = this.keyedLevelManager.setServerKeyedLevel(clientLevel, dimension, serverKey, levelKey);
						if (keyedLevel != null) { acceptedWorld.getOrLoadLevel(keyedLevel); }
					}
				}
			});
		}
	}

	private static Object worldIdentity(IClientLevelWrapper level)
	{
		if (level == null) { return null; }
		Object world = level.getWrappedMcObject();
		return world != null ? world : level;
	}

	public void onClientLevelUnload()
	{
		synchronized (this.lifecycleLock)
		{
			this.levelEpoch++;
			// A proxy may have moved to a backend without DH support.
			this.keyedLevelManager.disable();
		}
	}
	
	
	
	//==========//
	// shutdown //
	//==========//
	
	private void onClose(NetworkSession sourceSession)
	{
		synchronized (this.lifecycleLock)
		{
			if (sourceSession == this.networkSession) { this.reset(); }
		}
	}
	public void reset()
	{
		synchronized (this.lifecycleLock)
		{
			this.levelEpoch++;
			this.networkSession = null;
			this.keyedLevelManager.disable();
		}
	}
	
}
