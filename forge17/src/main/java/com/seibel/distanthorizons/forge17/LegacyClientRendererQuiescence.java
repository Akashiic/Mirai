package com.seibel.distanthorizons.forge17;

import com.seibel.distanthorizons.common.util.ProxyUtil;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.logging.mirai.MiraiDiagnostics;
import com.seibel.distanthorizons.core.render.MiraiRendererQuiescence;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import java.lang.ref.WeakReference;

/** Legacy-client catch-up for chunks intentionally ignored while DH rendering is disabled. */
final class LegacyClientRendererQuiescence
{
    static final int MAX_DEFERRED_CHUNKS = 4096;
    static final int TICKS_BETWEEN_CATCH_UP = 5;
    private static final MiraiRendererQuiescence.PositionBacklog BACKLOG =
            new MiraiRendererQuiescence.PositionBacklog(MAX_DEFERRED_CHUNKS);

    private static volatile boolean hasDeferred;
    private static WeakReference<World> deferredWorld = new WeakReference<>(null);
    private static int cooldown;

    private LegacyClientRendererQuiescence() { }

    static boolean suppressAndRemember(World world, int chunkX, int chunkZ)
    {
        if (!MiraiRendererQuiescence.isActive()) { return false; }
        remember(world, chunkX, chunkZ);
        MiraiDiagnostics.count(MiraiDiagnostics.Counter.QUIESCENT_CHUNK_DEFERRED);
        return true;
    }

    private static void remember(World world, int chunkX, int chunkZ)
    {
        if (world == null) { return; }
        synchronized (BACKLOG)
        {
            World previous = deferredWorld.get();
            if (previous != world)
            {
                BACKLOG.clear();
                deferredWorld = new WeakReference<>(world);
            }
            BACKLOG.offer(encode(chunkX, chunkZ));
            hasDeferred = !BACKLOG.isEmpty();
        }
    }

    static void onWorldUnload(World world)
    {
        synchronized (BACKLOG)
        {
            if (deferredWorld.get() == world)
            {
                BACKLOG.clear();
                deferredWorld.clear();
                hasDeferred = false;
                cooldown = 0;
            }
        }
    }

    static void clientTick()
    {
        if (!hasDeferred || MiraiRendererQuiescence.isActive()) { return; }
        if (cooldown > 0) { cooldown--; return; }
        cooldown = TICKS_BETWEEN_CATCH_UP - 1;

        World current = Minecraft.getMinecraft().theWorld;
        World world;
        long encoded;
        synchronized (BACKLOG)
        {
            world = deferredWorld.get();
            if (world == null || world != current)
            {
                BACKLOG.clear();
                hasDeferred = false;
                return;
            }
            encoded = BACKLOG.pollFirst();
            hasDeferred = !BACKLOG.isEmpty();
        }
        if (encoded == MiraiRendererQuiescence.NO_POSITION) { return; }

        int chunkX = decodeX(encoded);
        int chunkZ = decodeZ(encoded);
        if (!world.getChunkProvider().chunkExists(chunkX, chunkZ)) { return; }

        Chunk chunk = world.getChunkFromChunkCoords(chunkX, chunkZ);
        if (chunk == null || chunk.worldObj != world || !ChunkWrapper.canSaveChunk(chunk))
        {
            BACKLOG.requeue(encoded);
            hasDeferred = true;
            return;
        }

        ILevelWrapper wrappedLevel = ProxyUtil.getLevelWrapper(world);
        if (SharedApi.isChunkAtChunkPosAlreadyUpdating(wrappedLevel, chunkX, chunkZ))
        {
            BACKLOG.requeue(encoded);
            hasDeferred = true;
            return;
        }

        MiraiDiagnostics.count(MiraiDiagnostics.Counter.QUIESCENT_CATCHUP_SUBMITTED);
        SharedApi.INSTANCE.applyChunkUpdate(new ChunkWrapper(chunk, wrappedLevel), wrappedLevel, true);
    }

    static int deferredCount() { return BACKLOG.size(); }

    static long encode(int chunkX, int chunkZ)
    { return (chunkX & 0xffffffffL) | ((long) chunkZ << 32); }
    static int decodeX(long encoded) { return (int) encoded; }
    static int decodeZ(long encoded) { return (int) (encoded >> 32); }
}
