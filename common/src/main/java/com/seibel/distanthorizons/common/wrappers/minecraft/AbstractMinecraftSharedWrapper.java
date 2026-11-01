package com.seibel.distanthorizons.common.wrappers.minecraft;

import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import java.util.Arrays;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;

#if MC_VER > MC_1_12_2
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
#endif

#if MC_VER <= MC_1_12_2
#elif MC_VER <= MC_1_21_10
import net.minecraft.resources.ResourceLocation;
#else
import net.minecraft.resources.Identifier;
#endif

#if MC_VER > MC_1_19_2
import net.minecraft.core.registries.Registries;
#elif MC_VER > MC_1_12_2
import net.minecraft.core.Registry;
#endif

public abstract class AbstractMinecraftSharedWrapper implements IMinecraftSharedWrapper
{
	
	@Nullable
	#if MC_VER <= MC_1_12_2
	protected Integer deserializeDimensionResourceKey(String dimensionResourceLocation)
	#else
	protected ResourceKey<Level> deserializeDimensionResourceKey(String dimensionResourceLocation)
	#endif
	{
		#if  MC_VER <= MC_1_12_2
		try
		{
			return Integer.parseInt(dimensionResourceLocation.substring(dimensionResourceLocation.indexOf(":")+1));
		}
		catch (NumberFormatException ignored)
		{
			return null;
		}
		#else
			#if  MC_VER <= MC_1_21_10
			ResourceLocation dimResourceLocation = ResourceLocation.tryParse(dimensionResourceLocation);
			#else
			Identifier dimResourceLocation = Identifier.tryParse(dimensionResourceLocation);
			#endif
			if (dimResourceLocation == null)
			{
				return null;
			}
			
			#if  MC_VER > MC_1_19_2
			ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, dimResourceLocation);
			#else
			ResourceKey<Level> dimensionKey = ResourceKey.create(Registry.DIMENSION_REGISTRY, dimResourceLocation);
			#endif
			
		return dimensionKey;
		#endif
	}
	
	@Override
	public boolean isServerThreadHealthy()
	{
		long[] nanoTicks = this.getServerTickTimesNano();
		if (nanoTicks == null)
		{
			// assume the server is healthy if
			// we can't get the ticks
			return true;
		}
		
		long[] sortedNanoTicks = nanoTicks.clone();
		Arrays.sort(sortedNanoTicks);
		
		// 99 percentile
		int p99Index = (int)Math.ceil(0.99 * sortedNanoTicks.length) - 1;
		double p99Ms = sortedNanoTicks[Math.max(0, p99Index)] * 1e-6; // convert from Nano to Milli
		
		double avgMs = Arrays.stream(sortedNanoTicks).average().orElse(0) * 1e-6; // convert from Nano to Milli
		
		// a tick at 20 TPS has a 50 ms budget: healthy = mean under half of it and p99 within it
		// (upstream 1581a2e6; the old 10/30 ms thresholds were almost never met on a heavy pack)
		return avgMs < 25.0
			&& p99Ms < 50.0;
	}
	private long @Nullable [] getServerTickTimesNano()
	{
		// currently this logic is only implemented for singleplayer servers.
		// Upstream 6758511e also gates dedicated servers; Mirai deliberately does not: with ~25 ms mean ticks
		// being normal on a heavy pack, world-gen threads (ThreadPoolUtil) would pause indefinitely and stall
		// /dh pregen, which throttles itself on the server tick instead (PregenManager, 45 ms).
		if (this.isDedicatedServer())
		{
			return null;
		}
		
		
		#if MC_VER <= MC_1_12_2
		if (Minecraft.getMinecraft().getIntegratedServer() == null)
		{
			return null;
		}
		return Minecraft.getMinecraft().getIntegratedServer().tickTimeArray;
		#elif MC_VER <= MC_1_20_2

		if (Minecraft.getInstance().getSingleplayerServer() == null)
		{
			return null;
		}
		return Minecraft.getInstance().getSingleplayerServer().tickTimes;
		#else
		
		if (Minecraft.getInstance().getSingleplayerServer() == null)
		{
			return null;
		}
		return Minecraft.getInstance().getSingleplayerServer().getTickTimesNanos();
		#endif
	}
	
	
	
}
