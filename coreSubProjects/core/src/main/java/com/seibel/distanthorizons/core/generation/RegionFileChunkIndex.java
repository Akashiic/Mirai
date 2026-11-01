package com.seibel.distanthorizons.core.generation;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Read-only answer to "is this chunk saved?" from Anvil region file headers (<code>r.X.Z.mca</code>). <br>
 * Deliberately not using Minecraft's <code>RegionFileCache</code>: opening a missing region there creates the file.
 * A chunk counts as saved when its location entry has a non-zero sector offset and sector count.
 * Chunks that are loaded but never saved yet count as missing (conservative for pregen).
 */
public final class RegionFileChunkIndex
{
	private static final int REGION_WIDTH = 32;
	private static final int HEADER_BYTES = REGION_WIDTH * REGION_WIDTH * 4;

	private RegionFileChunkIndex() { }


	/** true when every chunk in [minChunkX, minChunkX + width) x [minChunkZ, minChunkZ + width) is saved in regionFolder */
	public static boolean allChunksSaved(File regionFolder, int minChunkX, int minChunkZ, int width) throws IOException
	{
		int maxChunkX = minChunkX + width - 1;
		int maxChunkZ = minChunkZ + width - 1;
		for (int regionX = Math.floorDiv(minChunkX, REGION_WIDTH); regionX <= Math.floorDiv(maxChunkX, REGION_WIDTH); regionX++)
		{
			for (int regionZ = Math.floorDiv(minChunkZ, REGION_WIDTH); regionZ <= Math.floorDiv(maxChunkZ, REGION_WIDTH); regionZ++)
			{
				int[] locations = readLocations(new File(regionFolder, "r." + regionX + "." + regionZ + ".mca"));
				if (locations == null)
				{
					return false;
				}

				int fromX = Math.max(minChunkX, regionX * REGION_WIDTH);
				int toX = Math.min(maxChunkX, regionX * REGION_WIDTH + REGION_WIDTH - 1);
				int fromZ = Math.max(minChunkZ, regionZ * REGION_WIDTH);
				int toZ = Math.min(maxChunkZ, regionZ * REGION_WIDTH + REGION_WIDTH - 1);
				for (int chunkX = fromX; chunkX <= toX; chunkX++)
				{
					for (int chunkZ = fromZ; chunkZ <= toZ; chunkZ++)
					{
						int location = locations[Math.floorMod(chunkX, REGION_WIDTH) + Math.floorMod(chunkZ, REGION_WIDTH) * REGION_WIDTH];
						if ((location >>> 8) == 0 || (location & 0xFF) == 0)
						{
							return false;
						}
					}
				}
			}
		}
		return true;
	}

	/** @return the 1024 location entries, or null when the region file does not exist or has no complete header */
	private static int[] readLocations(File regionFile) throws IOException
	{
		if (!regionFile.isFile() || regionFile.length() < HEADER_BYTES)
		{
			return null;
		}

		byte[] header = new byte[HEADER_BYTES];
		try (RandomAccessFile file = new RandomAccessFile(regionFile, "r"))
		{
			file.readFully(header);
		}
		catch (FileNotFoundException e)
		{
			return null;
		}

		int[] locations = new int[REGION_WIDTH * REGION_WIDTH];
		for (int i = 0; i < locations.length; i++)
		{
			locations[i] = ((header[i * 4] & 0xFF) << 24) | ((header[i * 4 + 1] & 0xFF) << 16) | ((header[i * 4 + 2] & 0xFF) << 8) | (header[i * 4 + 3] & 0xFF);
		}
		return locations;
	}

}
