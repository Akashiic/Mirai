package com.seibel.distanthorizons.forge17;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.jar.ModJarInfo;
import com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics;
import com.seibel.distanthorizons.coreapi.ModInfo;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeVersion;
import java.util.concurrent.ConcurrentHashMap;

/** Shared physical-side adapter: no Minecraft client/OpenGL classes may be linked here. */
public final class ForgeMiraiDiagnostics
{
    private static final ConcurrentHashMap<String, String> lastValues = new ConcurrentHashMap<>();
    private ForgeMiraiDiagnostics() { }
    static void value(String key, Object value)
    {
        if (!MiraiDiagnostics.enabled()) { return; }
        String text = String.valueOf(value);
        if (!text.equals(lastValues.put(key, text))) { MiraiDiagnostics.metadata(key, text); }
    }
    public static void initialized()
    {
        if (!MiraiDiagnostics.enabled()) { return; }
        try
        {
            value("identity.version", ModInfo.VERSION);
            value("identity.commit", ModJarInfo.Git_Commit);
            value("identity.branch", ModJarInfo.Git_Branch);
            value("identity.forge", ForgeVersion.getVersion());
            int count = 0;
            for (ModContainer mod : Loader.instance().getActiveModList())
            {
                if (count < 450) { value("mod." + mod.getModId(), mod.getVersion()); }
                count++;
            }
            value("inventory.total_mods", count);
            value("inventory.omitted_mods", Math.max(0, count - 450));
            configuration();
        }
        catch (RuntimeException | LinkageError failure) { MiraiDiagnostics.error(MiraiDiagnostics.ErrorKind.INITIALIZATION, failure); }
    }
    /** Only an explicit list of numeric/boolean/enum options. Never dump arbitrary config strings. */
    static void configuration()
    {
        value("config.render_enabled", Config.Client.quickEnableRendering.get());
        value("config.lod_radius", Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get());
        value("config.horizontal_quality", Config.Client.Advanced.Graphics.Quality.horizontalQuality.get());
        value("config.vertical_quality", Config.Client.Advanced.Graphics.Quality.verticalQuality.get());
        value("config.max_resolution", Config.Client.Advanced.Graphics.Quality.maxHorizontalResolution.get());
        value("config.transparency", Config.Client.Advanced.Graphics.Quality.transparency.get());
        value("config.dither", Config.Client.Advanced.Graphics.Quality.ditherDhFade.get());
        value("config.vanilla_fade", Config.Client.Advanced.Graphics.Quality.vanillaFadeMode.get());
        value("config.ssao", Config.Client.Advanced.Graphics.enableSsao.get());
        value("config.aa", Config.Client.Advanced.Graphics.enableAntiAliasing.get());
        value("config.dh_fog", Config.Client.Advanced.Graphics.Fog.enableDhFog.get());
        value("config.vanilla_fog", Config.Client.Advanced.Graphics.Fog.enableVanillaFog.get());
        value("config.cave_culling", Config.Client.Advanced.Graphics.Culling.enableCaveCulling.get());
        value("config.frustum_disabled", Config.Client.Advanced.Graphics.Culling.disableFrustumCulling.get());
        value("config.debug_colors", Config.Client.Advanced.Debugging.debugRenderingColors.get());
        value("config.white_world", Config.Client.Advanced.Debugging.enableWhiteWorld.get());
        value("config.lod_only", Config.Client.Advanced.Debugging.lodOnlyMode.get());
        value("config.wireframe", Config.Client.Advanced.Debugging.renderWireframe.get());
        value("config.renderer_mode", Config.Client.Advanced.Debugging.rendererMode.get());
        value("config.worker_threads", Config.Common.MultiThreading.numberOfThreads.get());
        value("config.worker_ratio", Config.Common.MultiThreading.threadRunTimeRatio.get());
        value("config.distant_generation", Config.Common.WorldGenerator.enableDistantGeneration.get());
        value("config.server_generation", Config.Server.enableServerGeneration.get());
        value("config.sync_on_load", Config.Server.synchronizeOnLoad.get());
        value("config.sync_distance", Config.Server.maxSyncOnLoadRequestDistance.get());
        value("config.player_bandwidth", Config.Server.playerBandwidthLimit.get());
        value("config.global_bandwidth", Config.Server.globalBandwidthLimit.get());
    }
    static void world(String event, World world)
    {
        if (!MiraiDiagnostics.enabled() || world == null) { return; }
        try
        {
            MiraiDiagnostics.event("world", "event=" + event + " remote=" + world.isRemote
                    + " dimension=" + world.provider.dimensionId + " native_token=" + System.identityHashCode(world)
                    + " provider=" + world.provider.getClass().getName());
        }
        catch (RuntimeException | LinkageError failure) { MiraiDiagnostics.error(MiraiDiagnostics.ErrorKind.SNAPSHOT, failure); }
    }
    static void threadPools()
    {
        pool("LOD_Builder", com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil.getChunkToLodBuilderExecutor());
        pool("Render_Load", com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil.getRenderLoadingExecutor());
        pool("Network", com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil.getNetworkCompressionExecutor());
        pool("File_Handler", com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil.getFileHandlerExecutor());
        pool("World_Gen_Import", com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil.getWorldGenExecutor());
    }
    private static void pool(String name, com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker.Executor pool)
    {
        // ConcurrentLinkedQueue.size() traverses the entire backlog. Never invoke
        // scheduler canRun() callbacks just to observe a pool, either.
        int queued = pool == null ? 0 : pool.getQueueSizeUpTo(256);
        MiraiDiagnostics.event("threads", "pool=" + name + (pool == null ? " available=false" : " queued_observed=" + (queued == 256 ? ">=256" : Integer.toString(queued))
            + " completed=" + pool.getCompletedTaskCount() + " running=" + pool.getRunningTaskCount()
            + " threads=" + pool.getPoolSize() + " average_task_wall_ms=" + pool.getAverageRunTimeInMs()));
    }
}
