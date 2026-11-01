package com.seibel.distanthorizons.common.util;

#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.core.generation.PregenManager;
import com.seibel.distanthorizons.core.generation.RegionFileChunkIndex;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.io.File;

/** Binds the 1.7.10 server facts that <code>/dh pregen</code> needs: saved-chunk lookup and mean tick time. */
public final class LegacyPregenBindings
{
	private LegacyPregenBindings() { }
	
	public static void bind(MinecraftServer server)
	{
		PregenManager.serverTickMs = () -> meanTickMs(server.tickTimeArray);
		PregenManager.savedChunkIndex = (levelWrapper, minChunkX, minChunkZ, width) ->
		{
			WorldServer world = ((ServerLevelWrapper) levelWrapper).getWrappedMcObject();
			File regionFolder = new File(world.getChunkSaveLocation(), "region");
			return RegionFileChunkIndex.allChunksSaved(regionFolder, minChunkX, minChunkZ, width);
		};
	}
	
	/** racy read of the server's 100-tick ring; good enough for a pause/resume threshold */
	private static double meanTickMs(long[] tickTimesNs)
	{
		long sum = 0;
		for (long tickTimeNs : tickTimesNs)
		{
			sum += tickTimeNs;
		}
		return tickTimesNs.length == 0 ? 0 : sum / (double) tickTimesNs.length / 1_000_000.0;
	}
}
#endif
