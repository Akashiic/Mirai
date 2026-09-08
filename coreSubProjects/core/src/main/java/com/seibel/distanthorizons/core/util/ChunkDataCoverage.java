package com.seibel.distanthorizons.core.util;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/** Validates a persisted chunk-hash fast path without deleting or rewriting cached data. */
public final class ChunkDataCoverage
{
	private ChunkDataCoverage() { }

	/**
	 * Called only by the chunk pre-update worker, never the render/world thread.
	 * Reads one full-resolution section and checks exactly the chunk's 256 columns.
	 * DTO decoding avoids resolving block wrappers and the provider's corruption-deletion path.
	 */
	public static boolean hasPersistedFullResolutionData(FullDataSourceProviderV2 provider, DhChunkPos chunkPos)
			throws InterruptedException
	{
		if (provider == null || provider.repo == null) { return false; }
		return hasPersistedFullResolutionData(provider.repo, chunkPos);
	}

	static boolean hasPersistedFullResolutionData(FullDataSourceV2Repo repo, DhChunkPos chunkPos) throws InterruptedException
	{
		long sectionPos = DhSectionPos.encodeContaining(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, chunkPos);
		try (FullDataSourceV2DTO dto = repo.getByKey(sectionPos))
		{
			if (dto == null) { return false; }
			try (FullDataSourceV2 data = dto.createDataSourceWithoutBlockResolution())
			{
				return hasCompleteChunkColumns(data, chunkPos);
			}
		}
		catch (InterruptedException interrupted)
		{
			Thread.currentThread().interrupt();
			throw interrupted;
		}
		catch (Exception unreadableData)
		{
			// An index cannot prove coverage when its data is unreadable. Allow the
			// ordinary chunk rebuild to repair that position; no global cache reset.
			return false;
		}
	}

	public static boolean hasCompleteChunkColumns(FullDataSourceV2 data, DhChunkPos chunkPos)
	{
		if (data == null || data.getPos() != DhSectionPos.encodeContaining(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, chunkPos))
		{
			return false;
		}
		int startX = (chunkPos.getX() & (FullDataSourceV2.NUMB_OF_CHUNKS_WIDE - 1)) * LodUtil.CHUNK_WIDTH;
		int startZ = (chunkPos.getZ() & (FullDataSourceV2.NUMB_OF_CHUNKS_WIDE - 1)) * LodUtil.CHUNK_WIDTH;
		for (int x = startX; x < startX + LodUtil.CHUNK_WIDTH; x++)
		{
			for (int z = startZ; z < startZ + LodUtil.CHUNK_WIDTH; z++)
			{
				int index = FullDataSourceV2.relativePosToIndex(x, z);
				if (data.columnGenerationSteps.getByte(index) != EDhApiWorldGenerationStep.LIGHT.value) { return false; }
				LongArrayList column = data.getColumnAtRelPos(x, z);
				if (column == null || column.isEmpty() || FullDataPointUtil.getHeight(column.getLong(0)) == 0) { return false; }
				// AIR is valid data for a generated void chunk. No solid-block requirement.
			}
		}
		return true;
	}
}
