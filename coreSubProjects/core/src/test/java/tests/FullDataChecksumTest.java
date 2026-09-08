package tests;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.generation.DhLightingEngine;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.Test;
import testItems.wrappers.TestBiomeWrapper;
import testItems.wrappers.TestBlockStateWrapper;

import static org.junit.Assert.*;

public class FullDataChecksumTest
{
	@Test
	public void serializedChecksumIncludesMetadataChangedAfterHashWasCached() throws Exception
	{
		try (FullDataSourceV2 data = createData())
		{
			int cachedHash = data.hashCode();
			data.setSingleColumn(data.getColumnAtRelPos(1, 1), 1, 1,
					EDhApiWorldGenerationStep.SURFACE, EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS);
			assertFreshRoundTrip(data, cachedHash);
		}
	}

	@Test
	public void serializedChecksumIncludesColumnsChangedAfterHashWasCached() throws Exception
	{
		try (FullDataSourceV2 data = createData())
		{
			int cachedHash = data.hashCode();
			LongArrayList column = data.getColumnAtRelPos(1, 1);
			column.set(0, FullDataPointUtil.setHeight(column.getLong(0), 65));
			assertFreshRoundTrip(data, cachedHash);
		}
	}

	@Test
	public void serializedChecksumIncludesProductionSkyLightBakeAfterCachedHash() throws Exception
	{
		try (FullDataSourceV2 data = createData())
		{
			LongArrayList column = data.getColumnAtRelPos(1, 1);
			int id = FullDataPointUtil.getId(column.getLong(0));
			column.set(0, FullDataPointUtil.encode(id, 32, 32, (byte) 0, (byte) 15));
			column.add(FullDataPointUtil.encode(id, 32, 0, (byte) 0, (byte) 15));
			int cachedHash = data.hashCode();
			DhLightingEngine.INSTANCE.bakeDataSourceSkyLight(data, 15);
			assertEquals(15, FullDataPointUtil.getSkyLight(column.getLong(0)));
			assertEquals(0, FullDataPointUtil.getSkyLight(column.getLong(1)));
			assertFreshRoundTrip(data, cachedHash);
		}
	}

	private static FullDataSourceV2 createData() throws Exception
	{
		FullDataSourceV2 data = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) 6, -12, 6));
		int id = data.mapping.addIfNotPresentAndGetId(
				new TestBiomeWrapper("checksum-biome"), new TestBlockStateWrapper("checksum-solid"));
		LongArrayList column = data.getColumnAtRelPos(1, 1);
		column.add(FullDataPointUtil.encode(id, 64, 0, (byte) 0, (byte) 15));
		data.setSingleColumn(column, 1, 1, EDhApiWorldGenerationStep.LIGHT, EDhApiWorldCompressionMode.VISUALLY_EQUAL);
		return data;
	}

	private static void assertFreshRoundTrip(FullDataSourceV2 data, int cachedHash) throws Exception
	{
		try (FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateFromDataSource(data, EDhApiDataCompressionMode.Z_STD_BLOCK);
			 FullDataSourceV2 decoded = dto.createUnitTestDataSource())
		{
			assertNotEquals("The fixture must change content covered by the checksum", cachedHash, decoded.hashCode());
			assertEquals("Checksum must describe the serialized content", decoded.hashCode(), dto.dataChecksum);
			assertEquals(decoded.hashCode(), data.calculateCurrentDataChecksum());
			assertEquals("Serialization must preserve the existing hashCode cache contract", cachedHash, data.hashCode());
			assertEquals(data.getColumnAtRelPos(1, 1), decoded.getColumnAtRelPos(1, 1));
			assertEquals(data.columnGenerationSteps, decoded.columnGenerationSteps);
			assertEquals(data.columnWorldCompressionMode, decoded.columnWorldCompressionMode);
		}
	}
}
