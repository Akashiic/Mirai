package com.seibel.distanthorizons.common.util;

#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.common.util.threading.ServerThreadTaskHandler;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.config.eventHandlers.IgnoredDimensionCsvHandler;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.world.DhApiWorldProxy;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * Explicit, finite re-submission of resident terrain through DH's normal update pipeline.
 * This does not read region files, acquire tickets, activate chunks, or generate terrain.
 * All mutable state is owned by the server thread. No player reference is retained.
 */
public final class LegacyLoadedTerrainPreparation
{
    public static final LegacyLoadedTerrainPreparation INSTANCE = new LegacyLoadedTerrainPreparation();
    public static final int MAX_CHUNK_RADIUS = 64;
    private static final int MAX_SCANNED_PER_TASK = 32;
    private static final int TICKS_BETWEEN_TASKS = 5;
    private static final long SCAN_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
    private static final DhLogger LOGGER = new DhLoggerBuilder().build();

    private Job active;
    private String lastStatus = "No loaded-terrain scan has run.";

    private LegacyLoadedTerrainPreparation() { }

    public String start(WorldServer world, int blockX, int blockZ, int chunkRadius)
    {
        requireOwnerThread();
        if (this.active != null) throw new IllegalStateException("A loaded-terrain scan is already running.");
        if (chunkRadius < 0 || chunkRadius > MAX_CHUNK_RADIUS)
            throw new IllegalArgumentException("chunkRadius must be between 0 and " + MAX_CHUNK_RADIUS + ".");
        if (Math.abs((long) blockX) + chunkRadius * 16L > 29_000_000L
            || Math.abs((long) blockZ) + chunkRadius * 16L > 29_000_000L)
            throw new IllegalArgumentException("The requested area exceeds the supported coordinate bounds.");
        if (world == null) throw new IllegalArgumentException("The dimension must already be loaded.");
        World baseWorld = world;
        if (DimensionManager.getWorld(baseWorld.provider.dimensionId) != world)
            throw new IllegalArgumentException("The dimension must already be loaded.");
        if (!(baseWorld.getChunkProvider() instanceof ChunkProviderServer))
            throw new IllegalArgumentException("The dimension does not expose the resident server chunk map.");
        ServerLevelWrapper wrapper = ServerLevelWrapper.getWrapper(world);
        if (SharedApi.getAbstractDhWorld() == null || SharedApi.getAbstractDhWorld().getLevel(wrapper) == null)
            throw new IllegalStateException("DH has not loaded this dimension.");
        if (DhApiWorldProxy.INSTANCE.tryGetReadOnly()
            || IgnoredDimensionCsvHandler.INSTANCE.dimensionNameShouldBeIgnored(wrapper.getDimensionName()))
            throw new IllegalStateException("This dimension is read-only or excluded from DH updates.");
        this.active = new Job(world, wrapper, SharedApi.getAbstractDhWorld().getLevel(wrapper), blockX >> 4, blockZ >> 4, chunkRadius);
        return this.status();
    }

    public String status()
    {
        requireOwnerThread();
        return this.active != null ? this.active.describe("RUNNING") : this.lastStatus;
    }

    public String stop()
    {
        requireOwnerThread();
        if (this.active != null) this.finish(this.active, "CANCELLED");
        return this.lastStatus;
    }

    public void onWorldUnload(WorldServer world)
    {
        if (this.active != null && this.active.world == world)
        {
            requireOwnerThread();
            this.finish(this.active, "CANCELLED: dimension unloaded");
        }
    }

    public void onServerStopping()
    {
        if (this.active != null)
        {
            requireOwnerThread();
            this.finish(this.active, "CANCELLED: server stopped");
        }
    }

    /** Called once per END server tick, before the existing task handler is drained. */
    public void tick()
    {
        if (this.active == null) return;
        requireOwnerThread();
        Job job = this.active;
        if (!this.isCurrent(job))
        {
            this.finish(job, "CANCELLED: world/session changed");
            return;
        }
        if (job.pending != null)
        {
            if (!job.pending.isDone()) return;
            try { job.pending.join(); }
            catch (RuntimeException failure)
            {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                this.finish(job, "FAILED: " + cause.getClass().getSimpleName());
                LOGGER.error("Loaded-terrain scan failed; no more chunks will be submitted.", cause);
                if (cause instanceof Error) throw (Error) cause;
                return;
            }
            job.pending = null;
        }
        if (job.visited == job.total)
        {
            this.finish(job, "SCAN COMPLETE (persistence is handled by DH asynchronously)");
            return;
        }
        if (--job.cooldown > 0) return;
        job.cooldown = TICKS_BETWEEN_TASKS;
        // A cancelled deferrable task may remain queued while server health is poor.
        // Its closure must not retain the old world after stop/unload.
        WeakReference<Job> queuedJob = new WeakReference<>(job);
        job.pending = ServerThreadTaskHandler.INSTANCE.queueTask(job.session, () ->
        {
            Job current = queuedJob.get();
            if (current != null && this.active == current && this.isCurrent(current)) this.scanBatch(current);
            return null;
        });
    }

    private void scanBatch(Job job)
    {
        requireOwnerThread();
        long started = System.nanoTime();
        World world = job.world;
        ChunkProviderServer provider = (ChunkProviderServer) world.getChunkProvider();
        for (int checked = 0; checked < MAX_SCANNED_PER_TASK && job.visited < job.total; checked++)
        {
            if (this.active != job || !this.isCurrent(job)) return;
            int index = job.visited++;
            int x = job.minX + index % job.width;
            int z = job.minZ + index / job.width;
            // A direct resident-map lookup cannot fall back to loading/generation.
            Chunk chunk = (Chunk) provider.id2ChunkMap.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(x, z));
            if (chunk == null) job.unloaded++;
            else if (chunk instanceof EmptyChunk || chunk.worldObj != world || chunk.xPosition != x || chunk.zPosition != z
                || !ChunkWrapper.canSaveChunk(chunk) || chunk.getBiomeArray() == null || chunk.getBiomeArray().length != 256)
                job.incomplete++;
            else if (SharedApi.isChunkAtChunkPosAlreadyUpdating(job.wrapper, x, z)) job.busy++;
            else
            {
                // Preserve the existing world-aware biome adapter and update/lighting pipeline.
                // The void API acknowledges submission only, not persistence or full coverage.
                SharedApi.INSTANCE.applyChunkUpdate(new ChunkWrapper(chunk, job.wrapper), job.wrapper, true);
                job.submitted++;
                return; // At most one resident chunk per five ticks (four/second at 20 TPS).
            }
            if (System.nanoTime() - started >= SCAN_BUDGET_NANOS) return;
        }
    }

    private boolean isCurrent(Job job)
    {
        World world = job.world;
        return job.session == ServerThreadTaskHandler.INSTANCE.getSessionId()
            && DimensionManager.getWorld(world.provider.dimensionId) == world
            && SharedApi.getAbstractDhWorld() != null
            && SharedApi.getAbstractDhWorld().getLevel(job.wrapper) == job.dhLevel
            && !DhApiWorldProxy.INSTANCE.tryGetReadOnly()
            && !IgnoredDimensionCsvHandler.INSTANCE.dimensionNameShouldBeIgnored(job.wrapper.getDimensionName());
    }

    private void finish(Job job, String result)
    {
        if (this.active != job) return;
        this.lastStatus = job.describe(result);
        this.active = null;
        if (job.pending != null) job.pending.cancel(false);
        LOGGER.info(this.lastStatus);
    }

    private static void requireOwnerThread()
    {
        if (!ServerThreadTaskHandler.INSTANCE.isServerThread())
            throw new IllegalStateException("Loaded-terrain preparation requires the server owner thread.");
    }

    private static final class Job
    {
        final WorldServer world;
        final ServerLevelWrapper wrapper;
        final IDhLevel dhLevel;
        final long session = ServerThreadTaskHandler.INSTANCE.getSessionId();
        final int minX, minZ, width, total, dimensionId;
        int visited, submitted, unloaded, incomplete, busy, cooldown;
        CompletableFuture<Void> pending;

        Job(WorldServer world, ServerLevelWrapper wrapper, IDhLevel dhLevel, int x, int z, int radius)
        {
            this.world = world;
            this.wrapper = wrapper;
            this.dhLevel = dhLevel;
            this.dimensionId = ((World) world).provider.dimensionId;
            this.minX = x - radius;
            this.minZ = z - radius;
            this.width = 2 * radius + 1;
            this.total = this.width * this.width;
        }

        String describe(String result)
        {
            return "Loaded-terrain " + result + "; dimension=" + this.dimensionId
                + "; scanned=" + this.visited + "/" + this.total + "; submitted=" + this.submitted
                + "; unloaded=" + this.unloaded + "; incomplete=" + this.incomplete + "; alreadyUpdating=" + this.busy
                + ". Submitted does not mean saved; unloaded positions were not loaded or generated.";
        }
    }
}
#endif
