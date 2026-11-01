package com.seibel.distanthorizons.core.world;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;

/**
 * Decides how long a client level without a server-provided key waits before it is loaded with a
 * temporary, connection-local LOD cache. <br><br>
 *
 * The wait is per Minecraft world instance: behind a proxy every backend switch and dimension change
 * creates a new world, and the server answers its level-key request 1-2 s later. A single delay per
 * connection (the previous behaviour) only covered the first world; every later one loaded into a fresh
 * temporary cache, downloaded and uploaded its LODs, then threw them away when the keyed level replaced it.
 * Worlds are held weakly, so a closed world never stays reachable from here.
 */
public final class LevelKeyWait
{
	/** Long enough for the observed 1-2 s key round trip, short enough to only delay LODs on servers without DH. */
	public static final long DEFAULT_WAIT_MS = 5_000;

	private final long waitMs;
	private final LongSupplier clockMs;
	private final Map<Object, Long> deadlineByWorld = new WeakHashMap<>();


	public LevelKeyWait() { this(DEFAULT_WAIT_MS, System::currentTimeMillis); }

	public LevelKeyWait(long waitMs, LongSupplier clockMs)
	{
		this.waitMs = waitMs;
		this.clockMs = clockMs;
	}


	/**
	 * @param world the Minecraft world instance (never the DH wrapper, which can be recreated for the same world)
	 * @return true once this world has waited long enough for its key; the first call starts its wait
	 */
	public synchronized boolean mayLoadWithoutKey(Object world)
	{
		if (world == null)
		{
			// nothing to key the wait on
			return true;
		}

		long now = this.clockMs.getAsLong();
		Long deadline = this.deadlineByWorld.get(world);
		if (deadline == null)
		{
			this.deadlineByWorld.put(world, now + this.waitMs);
			return this.waitMs <= 0;
		}
		return now >= deadline;
	}


}
