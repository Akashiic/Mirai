package com.seibel.distanthorizons.core.file.fullDatafile.V2;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV1;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V1.FullDataSourceProviderV1;
import com.seibel.distanthorizons.core.file.structure.ISaveStructure;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV1DTO;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV1Repo;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DataMigratorLifecycleTest
{
	private static class Provider extends FullDataSourceProviderV1<IDhLevel>
	{
		final AtomicInteger closes = new AtomicInteger();
		Provider(File folder) throws Exception { super(null, folder); }
		@Override public void close() { closes.incrementAndGet(); super.close(); }
	}

	@Test public void closeClaimsQueuedWorkAndClosesProviderExactlyOnce() throws Exception
	{
		File dir = Files.createTempDirectory("akashic-migration-queued-").toFile();
		Provider provider = new Provider(dir);
		Queue<Runnable> pending = new ArrayDeque<>();
		DataMigratorV1 migrator = new DataMigratorV1(null, provider, "queued", dir, pending::add);
		migrator.close(); migrator.close();
		migrator.getClosedFuture().get(5, TimeUnit.SECONDS);
		Assert.assertNull(provider.repo.getConnection()); Assert.assertEquals(1, provider.closes.get());
		pending.remove().run(); Assert.assertEquals(1, provider.closes.get());
	}

	@Test public void rejectedExecutionReleasesOwnedProvider() throws Exception
	{
		File dir = Files.createTempDirectory("akashic-migration-rejected-").toFile();
		Provider provider = new Provider(dir);
		DataMigratorV1 migrator = new DataMigratorV1(null, provider, "rejected", dir,
				task -> { throw new RejectedExecutionException("fixture"); });
		migrator.getClosedFuture().get(5, TimeUnit.SECONDS);
		Assert.assertNull(provider.repo.getConnection()); Assert.assertFalse(migrator.migrationThreadRunning.get());
	}

	@Test public void activeWorkerStopsCooperativelyWithoutBlockingClose() throws Exception
	{
		File dir = Files.createTempDirectory("akashic-migration-running-").toFile();
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		Provider provider = new Provider(dir)
		{
			@Override public long getDataSourceMigrationCount()
			{
				entered.countDown();
				try { if (!release.await(5, TimeUnit.SECONDS)) { throw new AssertionError("fixture timed out"); } }
				catch (InterruptedException error) { throw new AssertionError("SQL work was interrupted", error); }
				return 0;
			}
		};
		ExecutorService executor = Executors.newSingleThreadExecutor();
		DataMigratorV1 migrator = new DataMigratorV1(null, provider, "running", dir, executor);
		try
		{
			Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
			// close must return even while the worker is deliberately blocked.
			migrator.close();
			Assert.assertFalse(migrator.getClosedFuture().isDone()); Assert.assertEquals(0, provider.closes.get());
			release.countDown(); migrator.getClosedFuture().get(5, TimeUnit.SECONDS);
			Assert.assertEquals(1, provider.closes.get()); Assert.assertNull(provider.repo.getConnection());
		}
		finally { release.countDown(); migrator.close(); executor.shutdown(); }
	}

	@Test public void failedDestinationWriteRetainsLegacyRecordAndClosesProvider() throws Exception
	{
		File dir = Files.createTempDirectory("akashic-migration-write-failed-").toFile();
		long pos = DhSectionPos.encode((byte) 6, 0, 0);
		Provider provider = new Provider(dir)
		{
			@Override public ArrayList<FullDataSourceV1> getDataSourcesToMigrate(int limit)
			{
				ArrayList<FullDataSourceV1> sources = new ArrayList<>();
				if (this.repo.existsWithKey(pos)) { sources.add(FullDataSourceV1.createEmpty(pos)); }
				return sources;
			}
		};
		try (PreparedStatement insert = provider.repo.createInsertStatement(new FullDataSourceV1DTO(pos, 0, (byte) 0,
				EDhApiWorldGenerationStep.EMPTY, FullDataSourceV1.DATA_TYPE_NAME, (byte) 3, new byte[] { 0 })))
		{
			Assert.assertEquals(1, insert.executeUpdate());
		}
		AtomicInteger writes = new AtomicInteger();
		FullDataUpdaterV2 updater = new FullDataUpdaterV2(null, "fixture")
		{
			@Override void updateDataSourceForMigration(FullDataSourceV2 source)
			{
				writes.incrementAndGet(); throw new IllegalStateException("fixture destination write failed");
			}
		};
		DataMigratorV1 migrator = new DataMigratorV1(updater, provider, "failure", dir, Runnable::run);
		migrator.getClosedFuture().get(5, TimeUnit.SECONDS); Assert.assertEquals(1, writes.get());
		Assert.assertEquals(1, provider.closes.get());
		try (FullDataSourceV1Repo reopened = new FullDataSourceV1Repo("jdbc:sqlite", new File(dir, ISaveStructure.DATABASE_NAME)))
		{
			Assert.assertTrue("Unconfirmed migration deleted its V1 source", reopened.existsWithKey(pos));
			Assert.assertEquals("Failed write incorrectly blacklisted the original source", 1, reopened.getMigrationCount());
		}
	}

	@Test public void strictUpdaterRejectsShutdownInsteadOfReportingSuccessfulNoOp()
	{
		FullDataUpdaterV2 updater = new FullDataUpdaterV2(null, "closed fixture");
		updater.close();
		try (FullDataSourceV2 input = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) 6, 0, 0)))
		{
			try { updater.updateDataSourceForMigration(input); Assert.fail("Shutdown was reported as successful migration"); }
			catch (java.util.concurrent.CancellationException expected) { }
		}
	}
}
