package com.seibel.distanthorizons.forge17;

import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics;
import com.seibel.distanthorizons.core.logging.mirai.MiraiGpuProfiler;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.world.DhClientWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;
import net.minecraftforge.client.ClientCommandHandler;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.Display;
import java.util.concurrent.TimeUnit;

/** Invoked on the physical client owner thread. Only scalar/string snapshots cross into the writer. */
public final class ForgeMiraiClientDiagnostics
{
    private long frameStart, previousFrame, nextSnapshot, nextGpuAttempt;
    private boolean gpuCaptured;
    public static void registerCommand()
    {
        ClientCommandHandler.instance.registerCommand(new CommandBase()
        {
            @Override public String getCommandName() { return "miraidiag"; }
            @Override public String getCommandUsage(ICommandSender sender) { return "/miraidiag status|mark [label]|trace [seconds]|gpu [seconds|stop]|stop"; }
            @Override public int getRequiredPermissionLevel() { return 0; }
            @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }
            @Override public void processCommand(ICommandSender sender, String[] args)
            { sender.addChatMessage(new ChatComponentText(MiraiDiagnostics.command(args))); }
            @Override public int compareTo(Object other) { return 0; }
        });
    }
    @SubscribeEvent public void renderTick(TickEvent.RenderTickEvent event)
    {
        if (event.phase == TickEvent.Phase.START)
        {
            if (Minecraft.getMinecraft().theWorld == null) { MiraiGpuProfiler.stop(); }
            MiraiGpuProfiler.maintenance();
        }
        if (!MiraiDiagnostics.enabled()) { return; }
        long now = System.nanoTime();
        if (event.phase == TickEvent.Phase.START)
        {
            MiraiDiagnostics.ownerThread(true);
            if (previousFrame != 0) { MiraiDiagnostics.duration(MiraiDiagnostics.Metric.FRAME_INTERVAL, now - previousFrame); }
            previousFrame = frameStart = now;
            if (!gpuCaptured && now - nextGpuAttempt >= 0)
            {
                nextGpuAttempt = now + TimeUnit.SECONDS.toNanos(5);
                try
                {
                    if (GLContext.getCapabilities() != null)
                    {
                        ForgeMiraiDiagnostics.value("gpu.vendor", GL11.glGetString(GL11.GL_VENDOR));
                        ForgeMiraiDiagnostics.value("gpu.renderer", GL11.glGetString(GL11.GL_RENDERER));
                        ForgeMiraiDiagnostics.value("gpu.version_driver", GL11.glGetString(GL11.GL_VERSION));
                        ForgeMiraiDiagnostics.value("gpu.opengl32", GLContext.getCapabilities().OpenGL32);
                        ForgeMiraiDiagnostics.value("gpu.arb_timer_query", GLContext.getCapabilities().GL_ARB_timer_query);
                        ForgeMiraiDiagnostics.value("gpu.lwjgl", org.lwjgl.Sys.getVersion());
                        gpuCaptured = true;
                    }
                }
                catch (RuntimeException | LinkageError failure) { MiraiDiagnostics.error(MiraiDiagnostics.ErrorKind.SNAPSHOT, failure); }
            }
        }
        else if (frameStart != 0)
        { MiraiDiagnostics.duration(MiraiDiagnostics.Metric.FRAME_EVENT_SPAN, now - frameStart); frameStart = 0; }
    }
    @SubscribeEvent public void clientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || !MiraiDiagnostics.enabled()) { return; }
        long now = System.nanoTime();
        if (now - nextSnapshot < 0) { return; }
        nextSnapshot = now + TimeUnit.SECONDS.toNanos(5);
        long captureStart = System.nanoTime();
        try
        {
            Minecraft mc = Minecraft.getMinecraft();
            ForgeMiraiDiagnostics.configuration();
            ForgeMiraiDiagnostics.value("minecraft.render_distance", mc.gameSettings.renderDistanceChunks);
            ForgeMiraiDiagnostics.value("minecraft.fps_cap", mc.gameSettings.limitFramerate);
            ForgeMiraiDiagnostics.value("minecraft.vsync", mc.gameSettings.enableVsync);
            ForgeMiraiDiagnostics.value("minecraft.fancy_graphics", mc.gameSettings.fancyGraphics);
            ForgeMiraiDiagnostics.value("minecraft.fov", mc.gameSettings.fovSetting);
            ForgeMiraiDiagnostics.value("minecraft.mipmap_levels", mc.gameSettings.mipmapLevels);
            IIrisAccessor iris = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
            ForgeMiraiDiagnostics.value("integration.shaderpack_active", iris != null && iris.isShaderPackInUse());
            World nativeWorld = mc.theWorld;
            MiraiDiagnostics.event("client", "in_world=" + (nativeWorld != null) + " gui_open=" + (mc.currentScreen != null)
                    + " game_focus=" + mc.inGameHasFocus + " display_active=" + Display.isActive()
                    + " display_width=" + mc.displayWidth + " display_height=" + mc.displayHeight
                    + (nativeWorld == null ? "" : " dimension=" + nativeWorld.provider.dimensionId + " native_token=" + System.identityHashCode(nativeWorld)));
            if (nativeWorld != null) { MiraiDiagnostics.event("handoff", MinecraftRenderWrapper.INSTANCE.getLegacyHandoffObservation()); }
            ForgeMiraiDiagnostics.threadPools();
            AbstractDhWorld world = SharedApi.getAbstractDhWorld();
            if (world instanceof DhClientWorld) { MiraiDiagnostics.event("network_state", ((DhClientWorld) world).networkState.getMiraiDiagnosticString()); }
            if (world != null)
            {
                int sampled = 0;
                for (IDhLevel level : world.getAllLoadedLevels())
                {
                    if (sampled++ >= 8) { MiraiDiagnostics.event("level_limit", "additional_levels_not_sampled"); break; }
                    int token = System.identityHashCode(level.getLevelWrapper());
                    if (level instanceof DhClientLevel) { MiraiDiagnostics.event("level", "wrapper_token=" + token + " native_token=" + System.identityHashCode(level.getLevelWrapper().getWrappedMcObject()) + " " + ((DhClientLevel) level).getMiraiDiagnosticString()); }
                    RenderBufferHandler buffers = level.getRenderBufferHandler();
                    if (buffers != null) { MiraiDiagnostics.event("buffers", "wrapper_token=" + token + " " + buffers.getVboRenderDebugMenuString()); }
                }
            }
        }
        catch (RuntimeException | LinkageError failure) { MiraiDiagnostics.error(MiraiDiagnostics.ErrorKind.SNAPSHOT, failure); }
        finally { MiraiDiagnostics.event("observer_cost", "client_snapshot_us=" + (System.nanoTime() - captureStart) / 1000); }
    }
}
