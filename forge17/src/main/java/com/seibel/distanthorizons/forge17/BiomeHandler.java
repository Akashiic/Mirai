package com.seibel.distanthorizons.forge17;

import com.seibel.distanthorizons.common.wrappers.block.BiomeWrapper;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.seibel.distanthorizons.common.wrappers.block.IBiomeHandler;
import com.seibel.distanthorizons.forge17.mixin.MixinBiomeGenBase;
import net.minecraft.world.biome.BiomeGenBase;

/**
 * Tracks all the biomes
 * created in MC 1.7.10 so we
 * can (de)serialize them. <br><br>
 * 
 * Biomes created after the index was built are picked up by a rebuild on the first miss.
 */
public class BiomeHandler implements IBiomeHandler
{
	public static final BiomeHandler INSTANCE = new BiomeHandler(); 
	
	/** written by biome constructors (any thread), read by DH workers */
	private final List<BiomeGenBase> biomeList = new CopyOnWriteArrayList<>();
	/** index plus the list size it was built from, published together */
	private volatile NameIndex nameIndex = null;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private BiomeHandler() {}
	
	//endregion
	
	
	
	//=========//
	// methods //
	//=========//
	//region
	
	/** 
	 * Called by {@link MixinBiomeGenBase} to
	 * populate all possible options.
	 * Biomes are captured in their constructor, before setBiomeName runs,
	 * so names are only read when the index is built.
	 */
	public void addBiome(BiomeGenBase biome) { this.biomeList.add(biome); }
	
	public BiomeGenBase getBiomeByName(String name)
	{
		// lazy initialization since we need to wait for the biome list
		// to finish populating (and names to be set) before we can put together the map
		NameIndex index = this.nameIndex;
		if (index == null)
		{
			index = this.rebuildIndex();
		}
		
		BiomeGenBase biome = index.biomeByName.get(name);
		if (biome == null && index.builtFromSize != this.biomeList.size())
		{
			// a biome was constructed after the index was built (AOTM-02)
			biome = this.rebuildIndex().biomeByName.get(name);
		}
		return biome;
	}
	
	private NameIndex rebuildIndex()
	{
		// May be called from multiple threads; every build is a complete snapshot, so the last publish wins safely.
		List<BiomeGenBase> snapshot = new ArrayList<>(this.biomeList);
		ConcurrentHashMap<String, BiomeGenBase> biomeByName = new ConcurrentHashMap<>();
		for (BiomeGenBase biome : snapshot)
		{
			// nameless biomes are keyed "#<id>"; ConcurrentHashMap rejects null keys
			biomeByName.put(BiomeWrapper.getSerialName(biome), biome);
		}
		NameIndex index = new NameIndex(biomeByName, snapshot.size());
		this.nameIndex = index;
		return index;
	}
	
	private static final class NameIndex
	{
		final ConcurrentHashMap<String, BiomeGenBase> biomeByName;
		final int builtFromSize;
		
		NameIndex(ConcurrentHashMap<String, BiomeGenBase> biomeByName, int builtFromSize)
		{
			this.biomeByName = biomeByName;
			this.builtFromSize = builtFromSize;
		}
	}
	
	//endregion
	
	
	
}
