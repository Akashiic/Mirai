package com.seibel.distanthorizons.forge17;

import com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics;

import java.util.concurrent.TimeUnit;

import com.seibel.distanthorizons.common.commonMixins.MixinChunkMapCommon;
import com.seibel.distanthorizons.common.util.threading.ServerThreadTaskHandler;
import com.seibel.distanthorizons.common.util.LegacyLoadedTerrainPreparation;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.seibel.distanthorizons.common.AbstractModInitializer;
import com.seibel.distanthorizons.common.util.ProxyUtil;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.common.wrappers.misc.ServerPlayerWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ServerApi;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class ForgeServerProxy implements AbstractModInitializer.IEventProxy
{
    private final boolean isDedicated;
    private long diagnosticTickStart, diagnosticSnapshot;

	
	
	
	//=============//
    // constructor //
    //=============//
	//region

    public ForgeServerProxy(boolean isDedicated) 
    { 
		this.isDedicated = isDedicated;
	    
    }
	
	@Override
	public void registerEvents()
	{
		MinecraftForge.EVENT_BUS.register(this);
		FMLCommonHandler.instance()
			.bus()
			.register(this);
		
		if (this.isDedicated)
		{
			ForgePluginPacketSender.setPacketHandler(ServerApi.INSTANCE::pluginMessageReceived);
		}
	}
	
	//endregion
	
	
	
    //========//
    // events //
    //========//
	//region
	
	public static void serverStopping()
	{
		MiraiDiagnostics.serverStopped();
		LegacyLoadedTerrainPreparation.INSTANCE.onServerStopping();
		ForgePluginPacketSender.clearServerQueue();
		ServerThreadTaskHandler.INSTANCE.cancelPendingTasks();
	}

    // ServerTickEvent (at end)
    @SubscribeEvent
	public void serverTickEvent(TickEvent.ServerTickEvent event)
    {
        if (event.phase == TickEvent.Phase.START)
        {
            MiraiDiagnostics.ownerThread(false);
            diagnosticTickStart = MiraiDiagnostics.startTiming();
        }
        if (event.phase == TickEvent.Phase.END)
        {
            try
            {
                LegacyLoadedTerrainPreparation.INSTANCE.tick();
                ServerThreadTaskHandler.INSTANCE.runTasks(TimeUnit.MILLISECONDS.toNanos(15));
            }
            finally
            {
                MiraiDiagnostics.finishTiming(
                    MiraiDiagnostics.Metric.SERVER_TICK_EVENT_SPAN, diagnosticTickStart);
                diagnosticTickStart = 0;
            }
            diagnosticSnapshot();
        }
	}

    private void diagnosticSnapshot()
    {
        if (!MiraiDiagnostics.enabled()) { return; }
        long now = System.nanoTime();
        if (now - diagnosticSnapshot < 0) { return; }
        diagnosticSnapshot = now + TimeUnit.SECONDS.toNanos(5);
        try
        {
            if (isDedicated) { ForgeMiraiDiagnostics.configuration(); ForgeMiraiDiagnostics.threadPools(); }
            int sampled = 0;
            for (WorldServer item : DimensionManager.getWorlds())
            {
                if (sampled++ >= 8) { break; }
                World world = item;
                MiraiDiagnostics.event("server_world",
                    "dimension=" + world.provider.dimensionId + " native_token=" + System.identityHashCode(world)
                    + " entities=" + world.loadedEntityList.size() + " tiles=" + world.loadedTileEntityList.size()
                    + " players=" + world.playerEntities.size());
            }
        }
        catch (RuntimeException | LinkageError failure)
        { MiraiDiagnostics.error(MiraiDiagnostics.ErrorKind.SNAPSHOT, failure); }
    }

    // ServerLevelLoadEvent
    @SubscribeEvent 
    public void serverLevelLoadEvent(WorldEvent.Load event)
    {
		if (GetEventLevel(event) instanceof WorldServer)
	    {
			ForgeMiraiDiagnostics.world("server_load", event.world);
			ServerApi.INSTANCE.serverLevelLoadEvent(getServerLevelWrapper((WorldServer) GetEventLevel(event)));
		}
	}

    // ServerLevelUnloadEvent
    @SubscribeEvent
	public void serverLevelUnloadEvent(WorldEvent.Unload event)
    {
		if (GetEventLevel(event) instanceof WorldServer)
	    {
			ForgeMiraiDiagnostics.world("server_unload", event.world);
			LegacyLoadedTerrainPreparation.INSTANCE.onWorldUnload((WorldServer) GetEventLevel(event));
			// Make new server level wrapper so it's not cached...
			ServerApi.INSTANCE.serverLevelUnloadEvent(new ServerLevelWrapper((WorldServer) GetEventLevel(event)));
		}
	}

    @SubscribeEvent
	public void serverChunkLoadEvent(ChunkEvent.Load event)
    {
		if (!(event.world instanceof WorldServer))
	    {
			return;
		}
		
		Chunk chunk = event.getChunk();
		ILevelWrapper levelWrapper = ProxyUtil.getLevelWrapper(GetEventLevel(event));
		ChunkWrapper chunkWrapper = new ChunkWrapper(chunk, levelWrapper);
		// Only handle event if chunk is ready, otherwise drop the update - we'll get it later during save
		if (!chunk.isTerrainPopulated || !chunk.isLightPopulated)
	    {
			return;
		}
	    ServerApi.INSTANCE.serverChunkLoadEvent(chunkWrapper, levelWrapper);
	}

    @SubscribeEvent
    public void serverChunkSaveEvent(ChunkDataEvent.Save event) 
    {
        if (!(event.world instanceof WorldServer)) 
		{
            return;
        }
	    MixinChunkMapCommon.onChunkSave((WorldServer) event.world, event.getChunk());
    }

    @SubscribeEvent
    public void playerLoggedInEvent(PlayerEvent.PlayerLoggedInEvent event) 
    { ServerApi.INSTANCE.serverPlayerJoinEvent(getServerPlayerWrapper(event)); }

    @SubscribeEvent
    public void playerLoggedOutEvent(PlayerEvent.PlayerLoggedOutEvent event) 
    { ServerApi.INSTANCE.serverPlayerDisconnectEvent(getServerPlayerWrapper(event)); }

    @SubscribeEvent
    public void playerChangedDimensionEvent(PlayerEvent.PlayerChangedDimensionEvent event) 
    {
        ServerApi.INSTANCE.serverPlayerLevelChangeEvent(
            getServerPlayerWrapper(event),
            getServerLevelWrapper(event.fromDim, event),
            getServerLevelWrapper(event.toDim, event));
    }

    @SubscribeEvent
	public void clickBlockEvent(PlayerInteractEvent event)
    {
		// This proxy is also registered on the physical client for its integrated
		// server. Remote/client-world interactions belong to ForgeClientProxy.
		if (!(event.world instanceof WorldServer) || event.world.isRemote)
		{
			return;
		}

		// Keep inherited field/method accesses owned by World for 1.7.10 reobfuscation.
		final World world = event.world;
		final int blockX = event.x;
		final int blockZ = event.z;
		final ILevelWrapper wrappedLevel = getServerLevelWrapper((WorldServer) world);
		if (DimensionManager.getWorld(world.provider.dimensionId) != world
			|| SharedApi.isChunkAtBlockPosAlreadyUpdating(wrappedLevel, blockX, blockZ))
	    {
			return;
		}
		
		ServerThreadTaskHandler.INSTANCE.queueTask(() ->
		{
			// A dimension may unload/reload while the task is waiting. Checking
			// the existing registration does not cause a dimension to load again.
			if (DimensionManager.getWorld(world.provider.dimensionId) != world)
			{
				return null;
			}
			Chunk chunk = world.getChunkFromBlockCoords(blockX, blockZ);
			ChunkWrapper chunkWrapper = new ChunkWrapper(chunk, wrappedLevel);
			SharedApi.INSTANCE.applyChunkUpdate(chunkWrapper, wrappedLevel, true);
			return null;
		});
	}
	
	//endregion
	
	
	
    //================//
    // helper methods //
    //================//
	//region
	
	private static World GetEventLevel(WorldEvent e) { return e.world; }
	
	private static IServerLevelWrapper getServerLevelWrapper(WorldServer level) 
    { return ServerLevelWrapper.getWrapper(level); }
	
    private static IServerLevelWrapper getServerLevelWrapper(int dim, PlayerEvent event) 
    {
        WorldServer world = (WorldServer) event.player.worldObj;
        WorldServer worldDim = world.func_73046_m()
            .worldServerForDimension(dim);
        return getServerLevelWrapper(worldDim);
    }
	
    private static IServerPlayerWrapper getServerPlayerWrapper(PlayerEvent event) 
    { return ServerPlayerWrapper.getWrapper((EntityPlayerMP) event.player); }
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	private static class ChunkLoadEvent
	{
		public final ChunkWrapper chunkWrapper;
		public final ILevelWrapper level;
		
		/** 
		 * used to track how long this event has
		 * been queued so we can clean up old tasks.
		 */
		public int numberOfTicksSinceQueue = 0;
		
		
		
		private ChunkLoadEvent(ChunkWrapper chunkWrapper, ILevelWrapper level)
		{
			this.chunkWrapper = chunkWrapper;
			this.level = level;
		}
	}
	
}
