/*
 * This file is part of the Distant Horizons mod
 * licensed under the GNU LGPL v3 License.
 * Copyright (C) 2020 James Seibel
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, version 3.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.forge17;

import com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.WorldEvent;

import com.seibel.distanthorizons.common.AbstractModInitializer;
import com.seibel.distanthorizons.common.util.ProxyUtil;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.ServerApi;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * This handles all events sent to the client.
 */
public class ForgeClientProxy implements AbstractModInitializer.IEventProxy 
{

    private static final IMinecraftClientWrapper MC = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
    private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	//=======//
	// setup //
	//=======//
	//region
	
    @Override
    public void registerEvents()
    {
        try
        {
            ForgeMiraiClientDiagnostics.registerCommand();
            FMLCommonHandler.instance().bus().register(new ForgeMiraiClientDiagnostics());
        }
        catch (RuntimeException | LinkageError failure)
        { MiraiDiagnostics.error(MiraiDiagnostics.ErrorKind.INITIALIZATION, failure); }
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance()
            .bus()
            .register(this);
		
        ForgePluginPacketSender.setPacketHandler((player, message) -> 
        {
            if (player == null)
            {
                ClientApi.INSTANCE.pluginMessageReceived(message);
            }
            else
            {
                ServerApi.INSTANCE.pluginMessageReceived(player, message);
            }
        });
    }
	
	//endregion
	
	
	
    //==============//
    // chunk events //
    //==============//
	//region

    @SubscribeEvent
    public void diagnosticClientLoad(WorldEvent.Load event)
    { if (event.world != null && event.world.isRemote) { ForgeMiraiDiagnostics.world("client_load", event.world); } }

	@SubscribeEvent
	public void clientLevelUnloadEvent(WorldEvent.Unload event)
	{
		if (event.world == null || !event.world.isRemote) { return; }
		ForgeMiraiDiagnostics.world("client_unload", event.world);
		LegacyClientRendererQuiescence.onWorldUnload(event.world);
		ClientApi.INSTANCE.discardWaitingChunksForWorld(event.world);
		com.seibel.distanthorizons.core.world.AbstractDhWorld world = SharedApi.getAbstractDhWorld();
		if (world instanceof com.seibel.distanthorizons.core.world.DhClientWorld)
		{
			// Invalidate accepted network callbacks before another WorldClient can use their keys.
			// Level resources retain their existing coordinated unload/close path.
			((com.seibel.distanthorizons.core.world.DhClientWorld) world).pluginChannelApi.onClientLevelUnload();
		}
	}
	
    @SubscribeEvent
    public void clickBlockEvent(PlayerInteractEvent event) 
    {
        final World level = event.world;
        final int blockX = event.x;
        final int blockZ = event.z;
        if (level != null && level.isRemote
            && Minecraft.getMinecraft().theWorld == level
            && MC.clientConnectedToDedicatedServer())
		{
            if (LegacyClientRendererQuiescence.suppressAndRemember(level, blockX >> 4, blockZ >> 4)) { return; }
            ILevelWrapper wrappedLevel = ProxyUtil.getLevelWrapper(level);
            if (SharedApi.isChunkAtBlockPosAlreadyUpdating(wrappedLevel, blockX, blockZ))
			{
                return;
            }
			
            Minecraft.getMinecraft()
	            // add to render thread executor
                .addScheduledTask(() -> 
                {
                    if (Minecraft.getMinecraft().theWorld != level
                        || !MC.clientConnectedToDedicatedServer())
                    {
                        return;
                    }
                    if (LegacyClientRendererQuiescence.suppressAndRemember(level, blockX >> 4, blockZ >> 4)) { return; }
                    Chunk chunk = level.getChunkFromBlockCoords(blockX, blockZ);
                    ChunkWrapper chunkWrapper = new ChunkWrapper(chunk, wrappedLevel);
                    SharedApi.INSTANCE.applyChunkUpdate(chunkWrapper, wrappedLevel, true);
                });
        }
    }
	
	public static void onClientChunkFilled(Chunk chunk)
	{
		if (!MC.clientConnectedToDedicatedServer())
		{
			return;
		}
		
		World level = chunk.worldObj;
		if (level == null || !level.isRemote)
		{
			return;
		}
		if (LegacyClientRendererQuiescence.suppressAndRemember(level, chunk.xPosition, chunk.zPosition)) { return; }
		
		ILevelWrapper wrappedLevel = ProxyUtil.getLevelWrapper(level);
		ChunkWrapper chunkWrapper = new ChunkWrapper(chunk, wrappedLevel);
		if (!ChunkWrapper.canSaveChunk(chunk))
		{
            MiraiDiagnostics.count(MiraiDiagnostics.Counter.CHUNK_NOT_READY);
			LOGGER.info(
				"Skipping client chunk update for not-ready chunk [" + chunk.xPosition + "," + chunk.zPosition + "].");
			return;
		}
		
		MiraiDiagnostics.count(MiraiDiagnostics.Counter.CHUNK_SUBMITTED);
		SharedApi.INSTANCE.applyChunkUpdate(chunkWrapper, wrappedLevel, true);
	}
	
	//endregion

    @SubscribeEvent
    public void clientTickEvent(TickEvent.ClientTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END) { LegacyClientRendererQuiescence.clientTick(); }
    }
	
	
	
    //==============//
    // key bindings //
    //==============//
	//region

    @SubscribeEvent
    public void registerKeyBindings(InputEvent.KeyInputEvent event) 
    {
        if (Minecraft.getMinecraft().thePlayer == null) 
		{
            return;
        }
        /*
         * TODO if (event.getAction() != GLFW.GLFW_PRESS)
         * {
         * return;
         * }
         */

        // TODO ClientApi.INSTANCE.keyPressedEvent(event.getKey());
    }
	
	//endregion
	
	
	
    //===========//
    // rendering //
    //===========//
	//region

    @SubscribeEvent
    public void afterLevelRenderEvent(TickEvent.RenderTickEvent event)
    {
		if (event.phase == TickEvent.Phase.START)
		{
			ThreadPoolUtil.onClientRenderFrame(Minecraft.getMinecraft().theWorld != null && Minecraft.getMinecraft().currentScreen == null && Minecraft.getMinecraft().inGameHasFocus);
			AbstractModInitializer.updateShaderTransparencyPolicy();
		}
		// Angelica owns the depth attachment when our framebuffer mixin is excluded.
		if (Loader.isModLoaded("angelica"))
		{
			return;
		}
	    if (event.type.equals(TickEvent.RenderTickEvent.Type.RENDER))
	    {
		    boolean framebufferMixinWasEnabled = MixinFlags.framebufferMixinEnabled;
		    MixinFlags.framebufferMixinEnabled = true;
            try 
            {
                if (!framebufferMixinWasEnabled) 
				{
                    // The splash screen may have created MC's main framebuffer before our redirect was enabled.
                    // Rebuild it once after the first real render tick so the depth attachment becomes a texture.
                    Framebuffer framebuffer = Minecraft.getMinecraft()
                        .getFramebuffer();
                    if (framebuffer != null) 
					{
                        framebuffer.createBindFramebuffer(framebuffer.framebufferWidth, framebuffer.framebufferHeight);
                    }
                }
            } 
			catch (Throwable e) 
			{
                LOGGER.error("Unexpected error in afterLevelRenderEvent: [" + e.getMessage() + "].", e);
            }
        }
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) 
    {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.isCanceled()
	        || !mc.gameSettings.showDebugInfo)
        {
			return;
        }

        F3Screen.addStringToDisplay(event.right);
        event.right.add(com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper.INSTANCE
                .getLegacyHandoffDebugString());
    }
	
	//endregion
	
	
	
}
