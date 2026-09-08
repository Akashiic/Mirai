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

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.seibel.distanthorizons.common.wrappers.block.IBiomeHandler;
import com.seibel.distanthorizons.common.wrappers.modAccessor.IGregTechCommonAccessor;
import com.seibel.distanthorizons.common.wrappers.modAccessor.IHodgePodgeCommonAccessor;
import com.seibel.distanthorizons.common.wrappers.modAccessor.IRpleCommonAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.*;
import com.seibel.distanthorizons.core.wrapperInterfaces.modLoader.IForgeMain;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.forge17.modAccessor.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;

import com.seibel.distanthorizons.common.AbstractModInitializer;
import com.seibel.distanthorizons.common.util.threading.ServerThreadTaskHandler;
import com.seibel.distanthorizons.core.api.internal.ServerApi;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerAboutToStartEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Initialize and setup the Mod. <br>
 * If you are looking for the real start of the mod
 * check out the ClientProxy.
 */
@Mod(modid = "distanthorizons", name = ModInfo.READABLE_NAME, version = ModInfo.VERSION, dependencies = "after:angelica;")
public class ForgeMain extends AbstractModInitializer implements IForgeMain
{

    @Mod.Instance
    public static Object instance;

    private static final boolean DISABLE_SERVER_FOR_TESTING = false;

    public static boolean isHodgePodgeInstalled = false;

    private boolean modCompatInitialized;
    private HodgePodgeAccessor hodgePodgeAccessor;
	
	private Consumer<MinecraftServer> eventHandlerStartServer;
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public ForgeMain()
	{
		SingletonInjector.INSTANCE.bind(IForgeMain.class, this);
	}
	
	//endregion
	
	
	
	//=======//
	// setup //
	//=======//
	//region
	
    @Mod.EventHandler
    public void diagnosticPreInit(cpw.mods.fml.common.event.FMLPreInitializationEvent event)
    {
        MiraiDiagnostics.start(
                event.getModConfigurationDirectory().toPath().toAbsolutePath().getParent(), event.getSide().name());
    }

    @Mod.EventHandler
    public void diagnosticPostInit(cpw.mods.fml.common.event.FMLPostInitializationEvent event)
    { ForgeMiraiDiagnostics.initialized(); }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) 
    {
        // MinecraftRenderWrapper and other bindings capture accessors in static fields.
        // Register before onInitializeClient/onInitializeServer loads those consumers.
        this.initializeModCompat();

        if (event.getSide() == Side.CLIENT)
        {
            this.onInitializeClient();
        }
        else if (!DISABLE_SERVER_FOR_TESTING)
        {
            this.onInitializeServer();
        }

        ForgeChunkManager.setForcedChunkLoadingCallback(
            instance,
            (List<ForgeChunkManager.Ticket> tickets, World world) -> chunkLoadedCallback());
    }
	private boolean enableGregTechAccessor()
	{
		try
		{
			Class.forName("gregtech.api.interfaces.IBlockWithTextures");
			return true;
		}
		catch (ClassNotFoundException e)
		{
			return false;
		}
	}
	private void chunkLoadedCallback() { }
	
	//endregion
	
	
	
	//=====================//
	// server world events //
	//=====================//
	//region
	
	@Mod.EventHandler
	public void onServerAboutToStart(FMLServerAboutToStartEvent event)
	{
		if (DISABLE_SERVER_FOR_TESTING)
		{
			return;
		}

		this.logHodgePodgeCapability();
		ServerThreadTaskHandler.INSTANCE.reset();
		
		if (this.eventHandlerStartServer != null)
		{
			this.eventHandlerStartServer.accept(event.getServer());
		}
	}
	
	// ServerWorldLoadEvent
    @Mod.EventHandler
    public void dedicatedWorldLoadEvent(FMLServerAboutToStartEvent event)
    {
	    if (DISABLE_SERVER_FOR_TESTING)
	    {
		    return;
	    }
		
	    ServerApi.INSTANCE.serverLoadEvent(
		    event.getServer()
			    .isDedicatedServer());
    }
	
    // ServerWorldUnloadEvent
    @Mod.EventHandler
    public void serverWorldUnloadEvent(FMLServerStoppingEvent event) 
    {
        if (DISABLE_SERVER_FOR_TESTING) 
		{
            return;
        }
		
        ServerApi.INSTANCE.serverUnloadEvent();
    }
	
    @Mod.EventHandler
    public void serverWorldUnloadEvent(FMLServerStoppedEvent event) 
    {
        if (DISABLE_SERVER_FOR_TESTING) 
		{
            return;
        }
		
        ForgeServerProxy.serverStopping();
    }
	
	//endregion
	
	
	
	//========================//
	// AbstractModInitializer //
	//========================//
	//region
	
    @Override
    protected void createInitialSharedBindings() 
    {
        SingletonInjector.INSTANCE.bind(IModChecker.class, ModChecker.INSTANCE);
        SingletonInjector.INSTANCE.bind(IPluginPacketSender.class, new ForgePluginPacketSender());
	    SingletonInjector.INSTANCE.bind(IBiomeHandler.class, BiomeHandler.INSTANCE);
    }
	
	@Override
	protected void createInitialClientBindings() { }
	@Override
    protected IEventProxy createClientProxy() { return new ForgeClientProxy(); }
	
    @Override
    protected IEventProxy createServerProxy(boolean isDedicated) { return new ForgeServerProxy(isDedicated); }
	
    @Override
    protected void initializeModCompat()
    {
        // AbstractModInitializer calls this again after creating its bindings.
        if (this.modCompatInitialized)
        {
            return;
        }

        // Use Forge's loader here: DH's IModChecker binding does not exist yet.
        isHodgePodgeInstalled = Loader.isModLoaded("hodgepodge");
        if (isHodgePodgeInstalled)
        {
            this.hodgePodgeAccessor = new HodgePodgeAccessor();
            this.addModCompatAccessor(IHodgePodgeAccessor.class, () -> this.hodgePodgeAccessor);
            this.addModCompatAccessor(IHodgePodgeCommonAccessor.class, () -> this.hodgePodgeAccessor);
        }
        this.logHodgePodgeCapability();

        if (Loader.isModLoaded("rple"))
        {
            this.addModCompatAccessor(IRpleCommonAccessor.class, RpleCommonAccessor::new);
        }

        // Effective side is logical/thread-dependent. Optional render accessors
        // must only be linked on the physical client, including an integrated server.
        if (FMLCommonHandler.instance().getSide() == Side.CLIENT)
        {
            this.initializeClientModCompat();
        }

        this.modCompatInitialized = true;
    }

    @SideOnly(Side.CLIENT)
    private void initializeClientModCompat()
    {
        if (Loader.isModLoaded("angelica"))
        {
            AngelicaAccessor angelicaAccessor = new AngelicaAccessor();
            angelicaAccessor.throwIfUnsupportedAngelicaVersion();
            this.addModCompatAccessor(IAngelicaAccessor.class, () -> angelicaAccessor);
            this.addModCompatAccessor(IIrisAccessor.class, IrisAccessor::new);
        }

        if (Loader.isModLoaded("gregtech") && this.enableGregTechAccessor())
        {
            GregTechAccessor gregTechAccessor = new GregTechAccessor();
            this.addModCompatAccessor(IGregTechAccessor.class, () -> gregTechAccessor);
            this.addModCompatAccessor(IGregTechCommonAccessor.class, () -> gregTechAccessor);
        }

        if (Loader.isModLoaded("rple"))
        {
            RpleAccessor rpleAccessor = new RpleAccessor();
            this.addModCompatAccessor(IRpleAccessor.class, () -> rpleAccessor);
        }
    }

    private void logHodgePodgeCapability()
    {
        if (this.hodgePodgeAccessor == null)
        {
            ForgeMiraiDiagnostics.value("integration.hodgepodge_simulation", "ABSENT");
            LOGGER.info("Hodgepodge simulation suppression: ABSENT. Distant generation may tick loaded chunks.");
        }
        else if (!this.hodgePodgeAccessor.isSimulationSuppressionEnabled())
        {
            ForgeMiraiDiagnostics.value("integration.hodgepodge_simulation", "INACTIVE");
            LOGGER.warn("Hodgepodge simulation suppression: INACTIVE (addSimulationDistance_WIP=false). "
                + "Distant generation may tick loaded chunks; the experimental setting will not be changed automatically.");
        }
        else
        {
            ForgeMiraiDiagnostics.value("integration.hodgepodge_simulation", "ENABLED");
            LOGGER.info("Hodgepodge simulation suppression: ENABLED in configuration. "
                + "This does not certify suppression on hybrid servers or near players.");
        }
    }
	
    @Override
    protected void subscribeClientStartedEvent(Runnable eventHandler) { eventHandler.run(); }
	
	@Override
	protected void subscribeServerStartingEvent(Consumer<MinecraftServer> eventHandler)
	{ this.eventHandlerStartServer = eventHandler; }
	
	@Override
	protected void runDelayedSetup() { SingletonInjector.INSTANCE.runDelayedSetup(); }
	
	//region
	
	
	
}
