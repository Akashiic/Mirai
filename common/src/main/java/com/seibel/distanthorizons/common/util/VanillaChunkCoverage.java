package com.seibel.distanthorizons.common.util;

import java.lang.ref.WeakReference;

/**
 * Conservative legacy vanilla/LOD handoff, based on received chunks rather than
 * the client's distance slider. Call only on the client world owner's thread.
 * Received chunks are not a guarantee that vanilla has finished their meshes.
 */
public final class VanillaChunkCoverage
{
    public static final int MAX_RADIUS = 32;
    public static final int OVERLAP_CHUNKS = 2;

    @FunctionalInterface
    public interface ChunkProbe
    {
        boolean isReceived(int chunkX, int chunkZ);
    }

    private WeakReference<Object> world = new WeakReference<>(null);
    private WeakReference<Object> provider = new WeakReference<>(null);
    private int tick;
    private int cameraChunkX;
    private int cameraChunkZ;
    private int configuredDistance;
    private int receivedRadius = -1;
    private int effectiveDistance;
    private int probeCount;
    private boolean sampled;

    /**
     * Cache only the numeric result. No world, provider, chunk or probe is retained
     * strongly. Movement across chunks and configuration/world changes invalidate
     * immediately; other coverage changes are visible by the next local tick.
     */
    public int getDistance(Object world, Object provider, int tick,
            int cameraChunkX, int cameraChunkZ, int configuredDistance, ChunkProbe probe)
    {
        if (world == null || provider == null || probe == null)
        {
            this.clear(configuredDistance);
            return 0;
        }
        if (this.sampled && this.world.get() == world && this.provider.get() == provider
                && this.tick == tick && this.cameraChunkX == cameraChunkX
                && this.cameraChunkZ == cameraChunkZ && this.configuredDistance == configuredDistance)
        {
            return this.effectiveDistance;
        }

        // Do not publish a cache hit if a provider throws during its lookup.
        this.sampled = false;
        this.receivedRadius = -1;
        this.effectiveDistance = 0;
        this.probeCount = 0;
        if (this.world.get() != world) this.world = new WeakReference<>(world);
        if (this.provider.get() != provider) this.provider = new WeakReference<>(provider);
        this.tick = tick;
        this.cameraChunkX = cameraChunkX;
        this.cameraChunkZ = cameraChunkZ;
        this.configuredDistance = configuredDistance;

        int limit = Math.max(0, Math.min(MAX_RADIUS, configuredDistance));
        int radius = 0;
        if (limit > 0 && this.test(probe, cameraChunkX, cameraChunkZ))
        {
            for (int ring = 1; ring <= limit; ring++)
            {
                if (!this.hasCompleteRing(probe, cameraChunkX, cameraChunkZ, ring)) break;
                radius = ring;
            }
        }
        this.receivedRadius = radius;
        this.effectiveDistance = Math.min(limit, Math.max(0, radius - OVERLAP_CHUNKS));
        this.sampled = true;
        return this.effectiveDistance;
    }

    private boolean hasCompleteRing(ChunkProbe probe, int centerX, int centerZ, int radius)
    {
        // Long arithmetic prevents wraparound when checking coordinate boundaries.
        long minX = (long) centerX - radius;
        long maxX = (long) centerX + radius;
        long minZ = (long) centerZ - radius;
        long maxZ = (long) centerZ + radius;
        if (minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE
                || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE) return false;

        for (long x = minX; x <= maxX; x++)
        {
            if (!this.test(probe, (int) x, (int) minZ)
                    || !this.test(probe, (int) x, (int) maxZ)) return false;
        }
        // Corners were tested above; do not count/read them twice.
        for (long z = minZ + 1; z < maxZ; z++)
        {
            if (!this.test(probe, (int) minX, (int) z)
                    || !this.test(probe, (int) maxX, (int) z)) return false;
        }
        return true;
    }

    private boolean test(ChunkProbe probe, int x, int z)
    {
        this.probeCount++;
        return probe.isReceived(x, z);
    }

    public void clear(int configuredDistance)
    {
        this.world.clear();
        this.provider.clear();
        this.configuredDistance = configuredDistance;
        this.receivedRadius = -1;
        this.effectiveDistance = 0;
        this.probeCount = 0;
        this.sampled = false;
    }

    public int getConfiguredDistance() { return this.configuredDistance; }
    public int getReceivedRadius() { return this.receivedRadius; }
    public int getEffectiveDistance() { return this.effectiveDistance; }
    public int getProbeCount() { return this.probeCount; }
}
