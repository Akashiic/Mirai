package tests;

import com.seibel.distanthorizons.core.generation.RegionFileChunkIndex;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Region header reads for the pregen "existing" mode (AOTM-19). */
public class RegionFileChunkIndexTest
{
	@Rule
	public TemporaryFolder temp = new TemporaryFolder();

	/** writes an 8 KiB Anvil header marking the given chunks (region-local coordinates) as saved */
	private static void writeRegion(File folder, int regionX, int regionZ, int[][] savedLocalChunks) throws IOException
	{
		try (RandomAccessFile file = new RandomAccessFile(new File(folder, "r." + regionX + "." + regionZ + ".mca"), "rw"))
		{
			file.setLength(8192);
			for (int[] chunk : savedLocalChunks)
			{
				file.seek(4L * (chunk[0] + chunk[1] * 32));
				file.writeInt((2 << 8) | 1); // sector offset 2, one sector
			}
		}
	}

	@Test
	public void squareInsideOneRegion() throws IOException
	{
		File folder = this.temp.newFolder("region");
		int[][] saved = new int[36][];
		for (int i = 0; i < 36; i++) { saved[i] = new int[]{ i % 6, i / 6 }; }
		writeRegion(folder, 0, 0, saved);

		Assert.assertTrue(RegionFileChunkIndex.allChunksSaved(folder, 0, 0, 6));
		Assert.assertFalse(RegionFileChunkIndex.allChunksSaved(folder, 0, 0, 7));
		Assert.assertFalse(RegionFileChunkIndex.allChunksSaved(folder, 1, 1, 6));
	}

	@Test
	public void negativeCoordinatesAndRegionBorders() throws IOException
	{
		File folder = this.temp.newFolder("region");
		writeRegion(folder, -1, -1, new int[][]{ { 31, 31 } });
		writeRegion(folder, 0, -1, new int[][]{ { 0, 31 } });
		writeRegion(folder, -1, 0, new int[][]{ { 31, 0 } });
		writeRegion(folder, 0, 0, new int[][]{ { 0, 0 } });

		Assert.assertTrue(RegionFileChunkIndex.allChunksSaved(folder, -1, -1, 2));
		Assert.assertTrue(RegionFileChunkIndex.allChunksSaved(folder, -1, -1, 1));
		Assert.assertFalse(RegionFileChunkIndex.allChunksSaved(folder, -2, -1, 2));
	}

	@Test
	public void missingOrTruncatedRegionMeansNotSavedAndCreatesNothing() throws IOException
	{
		File folder = this.temp.newFolder("region");
		Assert.assertFalse(RegionFileChunkIndex.allChunksSaved(folder, 0, 0, 1));
		Assert.assertFalse("a lookup must never create region files", new File(folder, "r.0.0.mca").exists());

		try (RandomAccessFile file = new RandomAccessFile(new File(folder, "r.0.0.mca"), "rw"))
		{
			file.setLength(100);
		}
		Assert.assertFalse(RegionFileChunkIndex.allChunksSaved(folder, 0, 0, 1));
	}

	@Test
	public void zeroSectorCountIsNotSaved() throws IOException
	{
		File folder = this.temp.newFolder("region");
		try (RandomAccessFile file = new RandomAccessFile(new File(folder, "r.0.0.mca"), "rw"))
		{
			file.setLength(8192);
			file.writeInt(2 << 8); // offset without sectors
		}
		Assert.assertFalse(RegionFileChunkIndex.allChunksSaved(folder, 0, 0, 1));
	}

}
