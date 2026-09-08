package com.seibel.distanthorizons.core.util;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import testItems.wrappers.TestBiomeWrapper;
import testItems.wrappers.TestBlockStateWrapper;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.*;

public class ChunkDataCoverageTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void emptyAndPartiallyPopulatedChunksCannotUseHashFastPath() throws Exception
	{
		DhChunkPos pos = new DhChunkPos(-47, 24);
		try (FullDataSourceV2 data = createCoveredChunk(pos))
		{
			assertTrue(ChunkDataCoverage.hasCompleteChunkColumns(data, pos));
			data.getColumnAtRelPos(16, 0).clear();
			assertFalse(ChunkDataCoverage.hasCompleteChunkColumns(data, pos));
			assertFalse(ChunkDataCoverage.hasCompleteChunkColumns(data, new DhChunkPos(-46, 24)));
			assertFalse(ChunkDataCoverage.hasCompleteChunkColumns(data, new DhChunkPos(0, 0)));
		}
	}

	@Test
	public void downsampledOrUnlitDataDoesNotCertifyFullResolutionCoverage() throws Exception
	{
		DhChunkPos pos = new DhChunkPos(0, 0);
		try (FullDataSourceV2 data = createCoveredChunk(pos))
		{
			data.columnGenerationSteps.set(0, EDhApiWorldGenerationStep.DOWN_SAMPLED.value);
			assertFalse(ChunkDataCoverage.hasCompleteChunkColumns(data, pos));
			data.columnGenerationSteps.set(0, EDhApiWorldGenerationStep.SURFACE.value);
			assertFalse(ChunkDataCoverage.hasCompleteChunkColumns(data, pos));
		}
	}

	@Test
	public void persistedVoidChunkKeepsFastPathButMissingAndEmptySectionsDoNot() throws Exception
	{
		DhChunkPos pos = new DhChunkPos(-47, 24);
		try (FullDataSourceV2Repo repo = new FullDataSourceV2Repo("jdbc:sqlite", this.temporary.newFile("coverage.sqlite"));
			 FullDataSourceV2 data = createCoveredChunk(pos))
		{
			assertFalse(ChunkDataCoverage.hasPersistedFullResolutionData(repo, pos));
			try (FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateFromDataSource(data, EDhApiDataCompressionMode.Z_STD_BLOCK))
			{
				assertTrue(repo.saveChecked(dto));
			}
			// ID 0 is accepted as a normal AIR/void representation; coverage never
			// requires a solid block or resolves the client registry on the worker.
			assertTrue(ChunkDataCoverage.hasPersistedFullResolutionData(repo, pos));
			assertFalse(ChunkDataCoverage.hasPersistedFullResolutionData(repo, new DhChunkPos(-46, 24)));
			try (FullDataSourceV2DTO stored = repo.getByKey(data.getPos()))
			{
				assertNotNull("Coverage checks must not remove the stored section", stored);
			}
		}
	}

	@Test
	public void corruptBlobDoesNotLeakAllocatedSourcesOrDeleteItsStoredRecord() throws Exception
	{
		DhChunkPos pos = new DhChunkPos(0, 0);
		try (FullDataSourceV2Repo repo = new FullDataSourceV2Repo("jdbc:sqlite", this.temporary.newFile("corrupt.sqlite"));
			 FullDataSourceV2DTO invalid = FullDataSourceV2DTO.CreateEmptyDataSourceForDecoding())
		{
			invalid.pos = DhSectionPos.encodeContaining(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, pos);
			invalid.dataFormatVersion = 2;
			invalid.compressionModeValue = EDhApiDataCompressionMode.UNCOMPRESSED.value;
			assertTrue(repo.saveChecked(invalid));
			int activeBefore = activeDataSourceCount();
			for (int i = 0; i < 10; i++) { assertFalse(ChunkDataCoverage.hasPersistedFullResolutionData(repo, pos)); }
			assertEquals("Failed inspection must return its source ownership", activeBefore, activeDataSourceCount());
			try (FullDataSourceV2DTO stored = repo.getByKey(invalid.pos)) { assertNotNull(stored); }
		}
	}

	private static int activeDataSourceCount() throws Exception
	{
		Field poolField = FullDataSourceV2.class.getDeclaredField("ARRAY_LIST_POOL"); poolField.setAccessible(true);
		Object pool = poolField.get(null);
		Field activeField = pool.getClass().getDeclaredField("phantomRefToCheckout"); activeField.setAccessible(true);
		return ((Map<?, ?>) activeField.get(pool)).size();
	}

	private static FullDataSourceV2 createCoveredChunk(DhChunkPos pos) throws Exception
	{
		FullDataSourceV2 data = FullDataSourceV2.createEmpty(DhSectionPos.encodeContaining(DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL, pos));
		data.mapping.addIfNotPresentAndGetId(new TestBiomeWrapper("void"), new TestBlockStateWrapper("AIR")
		{
			@Override public boolean isAir() { return true; }
			@Override public boolean isSolid() { return false; }
			@Override public int getOpacity() { return 0; }
		});
		int startX = (pos.getX() & 3) * 16;
		int startZ = (pos.getZ() & 3) * 16;
		for (int x = startX; x < startX + 16; x++)
		{
			for (int z = startZ; z < startZ + 16; z++)
			{
				LongArrayList column = data.getColumnAtRelPos(x, z);
				column.add(FullDataPointUtil.encode(0, 256, 0, (byte) 0, (byte) 15));
				data.setSingleColumn(column, x, z, EDhApiWorldGenerationStep.LIGHT, EDhApiWorldCompressionMode.VISUALLY_EQUAL);
			}
		}
		return data;
	}
}
