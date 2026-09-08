/*
 * This file is part of the Distant Horizons mod, licensed under GNU LGPL v3.
 */
package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.objects.Pair;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;

import java.util.Map;
import java.util.function.Consumer;

/** Ownership checks for chunks received before their DH client level is ready. */
final class PendingClientChunkUpdates
{
	private PendingClientChunkUpdates() { }

	static int applyForLevel(Map<Pair<IClientLevelWrapper, DhChunkPos>, IChunkWrapper> pending,
			IClientLevelWrapper target, Consumer<IChunkWrapper> apply)
	{
		int applied = 0;
		Object targetWorld = target.getWrappedMcObject();
		for (Map.Entry<Pair<IClientLevelWrapper, DhChunkPos>, IChunkWrapper> entry : pending.entrySet())
		{
			IClientLevelWrapper source = entry.getKey().first;
			// A plain wrapper may be replaced by a server-keyed wrapper for the same
			// WorldClient. Equal dimension names/keys do not identify a live world.
			if (source != target && (targetWorld == null || source.getWrappedMcObject() != targetWorld))
			{
				continue;
			}
			IChunkWrapper chunk = entry.getValue();
			apply.accept(chunk.copyWithLevel(target));
			// Preserve a newer update inserted while this one was being applied.
			pending.remove(entry.getKey(), chunk);
			applied++;
		}
		return applied;
	}

	static void discardForWorld(Map<Pair<IClientLevelWrapper, DhChunkPos>, IChunkWrapper> pending,
			Object unloadedWorld)
	{
		if (unloadedWorld == null) { return; }
		for (Map.Entry<Pair<IClientLevelWrapper, DhChunkPos>, IChunkWrapper> entry : pending.entrySet())
		{
			if (entry.getKey().first.getWrappedMcObject() == unloadedWorld)
			{
				pending.remove(entry.getKey(), entry.getValue());
			}
		}
	}
}
