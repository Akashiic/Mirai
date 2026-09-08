package tests;

import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.IDataSourceUpdateListenerFunc;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataUpdaterV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import testItems.wrappers.TestBiomeWrapper;
import testItems.wrappers.TestBlockStateWrapper;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Actual updater merge/encoding/saveChecked + actual SQLite. A controlled provider read
 * boundary avoids starting Minecraft's propagator/migrator. No SQL/commit mock is used.
 */
public class FullDataUpdaterAcknowledgementTest
{
	private static final long POS = DhSectionPos.encode((byte) 7, 2, 3);
	@Rule public final TemporaryFolder files = new TemporaryFolder();

	@Test
	public void unchangedMergeStillProducesAnAcknowledgedFullDataRecord() throws Exception
	{
		try (Fixture fixture = this.fixture(); FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS))
		{
			fixture.updater.updateDataSource(data);
			Assert.assertTrue(fixture.repo.existsWithKey(POS));
			fixture.updater.updateDataSource(data);
			Assert.assertEquals(2, fixture.repo.acknowledgedWrites.get());
			Assert.assertTrue(fixture.updater.lockedPosSet.isEmpty());
		}
	}

	@Test
	public void migrationEntryPointPreservesStrictAcknowledgement() throws Exception
	{
		try (Fixture fixture = this.fixture(); FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS))
		{
			Method migration = FullDataUpdaterV2.class.getDeclaredMethod("updateDataSourceForMigration", FullDataSourceV2.class);
			migration.setAccessible(true);
			migration.invoke(fixture.updater, data);
			Assert.assertEquals(1, fixture.repo.acknowledgedWrites.get());
			Assert.assertTrue(fixture.repo.existsWithKey(POS));
		}
	}

	@Test
	public void sqliteReadOnlyFailureDoesNotPublishAnUpdate() throws Exception
	{
		try (Fixture fixture = this.fixture(); FullDataSourceV2 data = nonEmptyData())
		{
			AtomicInteger observations = new AtomicInteger();
			fixture.updater.addDataSourceUpdateListener(ignored -> observations.incrementAndGet());
			try (Statement statement = fixture.repo.getConnection().createStatement()) { statement.execute("PRAGMA query_only=ON;"); }
			assertFailure(fixture.updater.updateDataSourceAsync(data), RuntimeException.class);
			Assert.assertEquals(0, fixture.repo.acknowledgedWrites.get());
			Assert.assertEquals(0, observations.get());
			Assert.assertFalse(fixture.repo.existsWithKey(POS));
		}
	}

	@Test
	public void callerTransactionIsNeitherCommittedNorAcknowledged() throws Exception
	{
		try (Fixture fixture = this.fixture(); FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS))
		{
			Connection connection = fixture.repo.getConnection();
			connection.setAutoCommit(false);
			try
			{
				assertFailure(fixture.updater.updateDataSourceAsync(data), IllegalStateException.class);
				Assert.assertFalse(connection.getAutoCommit());
				try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM FullData;"))
				{
					Assert.assertTrue(rows.next());
					Assert.assertEquals(0, rows.getInt(1));
				}
				Assert.assertEquals(0, fixture.repo.acknowledgedWrites.get());
			}
			finally { connection.rollback(); connection.setAutoCommit(true); }
			fixture.updater.updateDataSource(data);
			Assert.assertTrue(fixture.repo.existsWithKey(POS));
		}
	}

	@Test
	public void closedRepositoryCannotReturnSuccessfulUpdate() throws Exception
	{
		try (Fixture fixture = this.fixture(); FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS))
		{
			fixture.repo.close();
			assertFailure(fixture.updater.updateDataSourceAsync(data), IllegalStateException.class);
			Assert.assertEquals(0, fixture.repo.acknowledgedWrites.get());
		}
	}

	@Test
	public void unavailableRecipientIsExceptional() throws Exception
	{
		try (Fixture fixture = this.fixture(); FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS))
		{
			fixture.provider.unavailable = true;
			assertFailure(fixture.updater.updateDataSourceAsync(data), CancellationException.class);
			Assert.assertEquals(0, fixture.repo.acknowledgedWrites.get());
		}
	}

	@Test
	public void readFailureReachesAsyncCaller() throws Exception
	{
		try (Fixture fixture = this.fixture(); FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS))
		{
			fixture.provider.readFailure = new IllegalArgumentException("injected provider failure");
			assertFailure(fixture.updater.updateDataSourceAsync(data), IllegalArgumentException.class);
			Assert.assertEquals(0, fixture.repo.acknowledgedWrites.get());
		}
	}

	@Test
	public void encodingFailureCannotPublishSuccessfulPersistence() throws Exception
	{
		TestBlockStateWrapper unencodable = new TestBlockStateWrapper("test:unencodable")
		{
			@Override public String getSerialString() { throw new IllegalStateException("injected block serialization failure"); }
		};
		try (Fixture fixture = this.fixture(); FullDataSourceV2 data = nonEmptyData(unencodable))
		{
			AtomicInteger observations = new AtomicInteger();
			fixture.updater.addDataSourceUpdateListener(ignored -> observations.incrementAndGet());
			assertFailure(fixture.updater.updateDataSourceAsync(data), IllegalStateException.class);
			Assert.assertEquals(0, fixture.repo.acknowledgedWrites.get());
			Assert.assertEquals(0, observations.get());
			Assert.assertFalse(fixture.repo.existsWithKey(POS));
		}
	}

	@Test
	public void missingAndRejectedExecutorsAreExceptional() throws Exception
	{
		try (Fixture fixture = this.fixture(); FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS))
		{
			fixture.updater.executor = null;
			assertFailure(fixture.updater.updateDataSourceAsync(data), RejectedExecutionException.class);
			fixture.executor.reject = true;
			fixture.updater.executor = fixture.executor;
			assertFailure(fixture.updater.updateDataSourceAsync(data), RejectedExecutionException.class);
			Assert.assertEquals(0, fixture.provider.reads.get());
		}
	}

	@Test
	public void shutdownBeforeSubmissionIsExceptional() throws Exception
	{
		try (Fixture fixture = this.fixture(); FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS))
		{
			fixture.updater.close();
			assertFailure(fixture.updater.updateDataSourceAsync(data), CancellationException.class);
			Assert.assertEquals(0, fixture.provider.reads.get());
		}
	}

	@Test
	public void acceptedButDiscardedTaskTerminatesOnOwnerCloseAndReleasesOnce() throws Exception
	{
		try (Fixture fixture = this.fixture())
		{
			fixture.executor.autoRun = false;
			FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS);
			AtomicInteger releases = new AtomicInteger();
			CompletableFuture<Void> pending = fixture.updater.updateDataSourceAsync(data);
			pending.whenComplete((ignored, error) -> { releases.incrementAndGet(); data.close(); });
			List<Runnable> discarded = fixture.executor.shutdownNow();
			Assert.assertFalse(pending.isDone());
			fixture.updater.close();
			assertFailure(pending, CancellationException.class);
			Assert.assertEquals(1, releases.get());
			for (Runnable task : discarded) { task.run(); }
			Assert.assertEquals(0, fixture.provider.reads.get());
			Assert.assertEquals(1, releases.get());
			Assert.assertTrue(fixture.updater.getClosedFuture().isDone());
		}
	}

	@Test(timeout = 15000)
	public void runningCancellationAndCloseWaitForRealWriteAndInputRelease() throws Exception
	{
		ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
		try (Fixture fixture = this.fixture())
		{
			fixture.updater.executor = worker;
			fixture.provider.entered = new CountDownLatch(1);
			fixture.provider.resume = new CountDownLatch(1);
			FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS);
			AtomicInteger releases = new AtomicInteger();
			CompletableFuture<Void> pending = fixture.updater.updateDataSourceAsync(data);
			CompletableFuture<Void> releaseDone = pending.whenComplete((ignored, error) -> { releases.incrementAndGet(); data.close(); });
			try
			{
				Assert.assertTrue(fixture.provider.entered.await(5, TimeUnit.SECONDS));
				Assert.assertFalse(pending.cancel(true));
				Assert.assertFalse(pending.complete(null));
				Assert.assertFalse(pending.completeExceptionally(new IllegalStateException("external completion")));
				fixture.updater.close();
				Assert.assertFalse(pending.isDone());
				Assert.assertFalse(fixture.updater.getClosedFuture().isDone());
				Assert.assertEquals(0, releases.get());
				fixture.provider.resume.countDown();
				releaseDone.get(5, TimeUnit.SECONDS);
				fixture.updater.getClosedFuture().get(5, TimeUnit.SECONDS);
				Assert.assertEquals(1, releases.get());
				Assert.assertTrue(fixture.repo.existsWithKey(POS));
			}
			finally { fixture.provider.resume.countDown(); }
		}
		finally { worker.shutdownNow(); Assert.assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
	}

	@Test
	public void cancellationBeforeExecutionNeverUsesReleasedInput() throws Exception
	{
		try (Fixture fixture = this.fixture())
		{
			fixture.executor.autoRun = false;
			FullDataSourceV2 data = FullDataSourceV2.createEmpty(POS);
			AtomicInteger releases = new AtomicInteger();
			CompletableFuture<Void> pending = fixture.updater.updateDataSourceAsync(data);
			pending.whenComplete((ignored, error) -> { releases.incrementAndGet(); data.close(); });
			Assert.assertTrue(pending.cancel(false));
			fixture.executor.queue.remove().run();
			Assert.assertEquals(1, releases.get());
			Assert.assertEquals(0, fixture.provider.reads.get());
		}
	}

	@Test(timeout = 10000)
	public void listenerFailureAndSelfRemovalDoNotUndoCommitOrSkipNextListener() throws Exception
	{
		try (Fixture fixture = this.fixture(); FullDataSourceV2 data = nonEmptyData())
		{
			AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
			IDataSourceUpdateListenerFunc<FullDataSourceV2> failing = ignored -> { first.incrementAndGet(); throw new IllegalStateException("injected observer failure"); };
			fixture.updater.addDataSourceUpdateListener(failing);
			fixture.updater.addDataSourceUpdateListener(ignored -> { fixture.updater.removeDataSourceUpdateListener(failing); second.incrementAndGet(); });
			fixture.updater.updateDataSourceAsync(data).get(5, TimeUnit.SECONDS);
			Assert.assertEquals(1, fixture.repo.acknowledgedWrites.get());
			Assert.assertTrue(fixture.repo.existsWithKey(POS));
			Assert.assertEquals(1, first.get());
			Assert.assertEquals(1, second.get());
		}
	}

	@Test public void coldSnapshotQueuedBeforeFreshPartialCommitCannotOverwriteIt() throws Exception
	{
		try (Fixture fixture = this.fixture(); FullDataSourceV2 oldData = nonEmptyData(new TestBlockStateWrapper("test:old"));
			FullDataSourceV2 fresh = nonEmptyData(new TestBlockStateWrapper("test:fresh")))
		{
			// A partially received vanilla section is still authoritative for the
			// entire record under this conservative cold-bootstrap policy.
			for (int i = 1; i < fresh.dataPoints.length; i++) { fresh.dataPoints[i] = null; }
			fixture.executor.autoRun = false;
			CompletableFuture<Void> delayedCold = fixture.updater.updateDataSourceIfAbsentAsync(oldData);
			fixture.updater.updateDataSource(fresh);
			int checksum;
			long timestamp;
			try (FullDataSourceV2DTO persisted = fixture.repo.getByKey(POS))
			{ checksum = persisted.dataChecksum; timestamp = persisted.lastModifiedUnixDateTime; }
			AtomicInteger notifications = new AtomicInteger();
			fixture.updater.addDataSourceUpdateListener(ignored -> notifications.incrementAndGet());
			fixture.executor.queue.removeFirst().run();
			delayedCold.get(5, TimeUnit.SECONDS);
			Assert.assertEquals(1, fixture.repo.acknowledgedWrites.get());
			Assert.assertEquals(0, notifications.get());
			try (FullDataSourceV2DTO persisted = fixture.repo.getByKey(POS))
			{ Assert.assertEquals(checksum, persisted.dataChecksum); Assert.assertEquals(timestamp, persisted.lastModifiedUnixDateTime); }
			Assert.assertTrue(fixture.updater.lockedPosSet.isEmpty());
		}
	}

	@Test public void freshUpdateAfterColdImportStillWinsNormally() throws Exception
	{
		try (Fixture fixture = this.fixture(); FullDataSourceV2 oldData = nonEmptyData(new TestBlockStateWrapper("test:old"));
			FullDataSourceV2 fresh = nonEmptyData(new TestBlockStateWrapper("test:fresh")))
		{
			fixture.updater.updateDataSourceIfAbsentAsync(oldData).get(5, TimeUnit.SECONDS);
			fixture.updater.updateDataSource(fresh);
			Assert.assertEquals(2, fixture.repo.acknowledgedWrites.get());
			try (FullDataSourceV2DTO persisted = fixture.repo.getByKey(POS); FullDataSourceV2 result = persisted.createUnitTestDataSource())
			{ Assert.assertEquals(fresh.calculateCurrentDataChecksum(), persisted.dataChecksum); Assert.assertNotNull(result.dataPoints[0]); }
		}
	}

	@Test public void coldConfirmedAirIsPersistedWhenRecordIsAbsent() throws Exception
	{
		TestBlockStateWrapper air = new TestBlockStateWrapper("minecraft:air")
		{
			@Override public boolean isAir() { return true; }
			@Override public boolean isSolid() { return false; }
			@Override public int getOpacity() { return 0; }
		};
		try (Fixture fixture = this.fixture(); FullDataSourceV2 data = nonEmptyData(air))
		{
			fixture.updater.updateDataSourceIfAbsentAsync(data).get(5, TimeUnit.SECONDS);
			Assert.assertEquals(1, fixture.repo.acknowledgedWrites.get());
			try (FullDataSourceV2DTO persisted = fixture.repo.getByKey(POS); FullDataSourceV2 result = persisted.createUnitTestDataSource())
			{ Assert.assertTrue(data.mapping.getBlockStateWrapper(0).isAir()); Assert.assertEquals(data.calculateCurrentDataChecksum(), persisted.dataChecksum); Assert.assertNotNull(result.dataPoints[0]); }
		}
	}

	@Test public void failedColdExistenceCheckNeverBecomesPermissionToWrite() throws Exception
	{
		try (Fixture fixture = this.fixture(); FullDataSourceV2 data = nonEmptyData())
		{
			try (Statement statement = fixture.repo.getConnection().createStatement()) { statement.execute("ALTER TABLE FullData RENAME TO HiddenFullData;"); }
			assertFailure(fixture.updater.updateDataSourceIfAbsentAsync(data), RuntimeException.class);
			Assert.assertEquals(0, fixture.provider.reads.get());
			Assert.assertEquals(0, fixture.repo.acknowledgedWrites.get());
			Assert.assertTrue(fixture.updater.lockedPosSet.isEmpty());
		}
	}

	private Fixture fixture() throws Exception { return new Fixture(this.files.newFile("updater-" + System.nanoTime() + ".sqlite")); }

	private static void assertFailure(CompletableFuture<Void> future, Class<? extends Throwable> type) throws Exception
	{
		try { future.get(5, TimeUnit.SECONDS); Assert.fail("An unacknowledged write completed normally"); }
		catch (ExecutionException failure) { Assert.assertTrue(failure.getCause().toString(), type.isInstance(failure.getCause())); }
		catch (CancellationException failure) { Assert.assertTrue(failure.toString(), type.isInstance(failure)); }
	}

	private static FullDataSourceV2 nonEmptyData() throws Exception
	{ return nonEmptyData(new TestBlockStateWrapper("test:block")); }

	private static FullDataSourceV2 nonEmptyData(TestBlockStateWrapper block) throws Exception
	{
		FullDataPointIdMap mapping = new FullDataPointIdMap(POS);
		int id = mapping.addIfNotPresentAndGetId(new TestBiomeWrapper("test:biome"), block);
		LongArrayList[] columns = new LongArrayList[FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH];
		for (int i = 0; i < columns.length; i++) { columns[i] = new LongArrayList(new long[] { FullDataPointUtil.encode(id, 1, 0, (byte) 0, (byte) 15) }); }
		byte[] steps = new byte[columns.length], compression = new byte[columns.length];
		Arrays.fill(steps, (byte) 5);
		return FullDataSourceV2.createWithData(POS, mapping, columns, steps, compression);
	}

	@Test(timeout = 15000)
	public void fatalListenerErrorPropagatesWithoutClaimingRollback() throws Exception
	{
		CountDownLatch uncaught = new CountDownLatch(1);
		AssertionError fatal = new AssertionError("injected fatal observer error");
		ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(), task ->
				{
					Thread thread = new Thread(task, "updater-fatal-test");
					thread.setUncaughtExceptionHandler((ignored, failure) -> { if (failure == fatal) { uncaught.countDown(); } });
					return thread;
				});
		try (Fixture fixture = this.fixture(); FullDataSourceV2 data = nonEmptyData())
		{
			fixture.updater.addDataSourceUpdateListener(ignored -> { throw fatal; });
			try { fixture.updater.updateDataSource(data); Assert.fail("Fatal error was swallowed"); }
			catch (AssertionError failure) { Assert.assertSame(fatal, failure); }
			Assert.assertTrue("A fatal observation error does not roll back the committed row", fixture.repo.existsWithKey(POS));
			fixture.updater.executor = worker;
			assertFailure(fixture.updater.updateDataSourceAsync(data), AssertionError.class);
			Assert.assertTrue("The worker's fatal error did not reach its uncaught handler", uncaught.await(5, TimeUnit.SECONDS));
			Assert.assertEquals(2, fixture.repo.acknowledgedWrites.get());
		}
		finally { worker.shutdownNow(); Assert.assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); }
	}

	private static final class Fixture implements AutoCloseable
	{
		final CountingRepo repo;
		final FixtureProvider provider;
		final ManualExecutor executor = new ManualExecutor();
		final TestUpdater updater;
		Fixture(File file) throws Exception
		{
			this.repo = new CountingRepo(file);
			// Test-only allocation skips the real provider constructor, which starts
			// unrelated migrator/propagator services. The updater and JDBC stay real.
			Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
			Field singleton = unsafeClass.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
			Object unsafe = singleton.get(null);
			this.provider = (FixtureProvider) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, FixtureProvider.class);
			Field repoField = FullDataSourceProviderV2.class.getDeclaredField("repo"); repoField.setAccessible(true); repoField.set(this.provider, this.repo);
			this.provider.reads = new AtomicInteger();
			this.updater = new TestUpdater(this.provider, this.executor);
		}
		@Override public void close() throws Exception { this.updater.close(); this.updater.getClosedFuture().get(5, TimeUnit.SECONDS); this.repo.close(); }
	}

	private static final class CountingRepo extends FullDataSourceV2Repo
	{
		final AtomicInteger acknowledgedWrites = new AtomicInteger();
		CountingRepo(File file) throws Exception { super("jdbc:sqlite", file); }
		@Override public boolean saveChecked(FullDataSourceV2DTO dto)
		{
			boolean saved = super.saveChecked(dto);
			if (saved) { this.acknowledgedWrites.incrementAndGet(); }
			return saved;
		}
	}

	private static final class FixtureProvider extends FullDataSourceProviderV2
	{
		AtomicInteger reads;
		boolean unavailable;
		RuntimeException readFailure;
		CountDownLatch entered, resume;
		private FixtureProvider() throws Exception { super(null, null); }
		@Override public FullDataSourceV2 get(long pos)
		{
			this.reads.incrementAndGet();
			if (this.entered != null)
			{
				this.entered.countDown();
				try { if (!this.resume.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Read fixture timed out"); } }
				catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new RuntimeException(failure); }
			}
			if (this.readFailure != null) { throw this.readFailure; }
			return this.unavailable ? null : FullDataSourceV2.createEmpty(pos);
		}
	}

	private static final class TestUpdater extends FullDataUpdaterV2
	{
		AbstractExecutorService executor;
		TestUpdater(FixtureProvider provider, AbstractExecutorService executor) { super(provider, "test"); this.executor = executor; }
		@Override protected AbstractExecutorService getUpdateExecutor() { return this.executor; }
	}

	private static final class ManualExecutor extends AbstractExecutorService
	{
		final ArrayDeque<Runnable> queue = new ArrayDeque<>();
		boolean autoRun = true, stopped, reject;
		@Override public void execute(Runnable task) { if (this.stopped || this.reject) { throw new RejectedExecutionException("controlled executor rejection"); } if (this.autoRun) { task.run(); } else { this.queue.add(task); } }
		@Override public void shutdown() { this.stopped = true; }
		@Override public List<Runnable> shutdownNow() { this.stopped = true; List<Runnable> pending = new ArrayList<>(this.queue); this.queue.clear(); return pending; }
		@Override public boolean isShutdown() { return this.stopped; }
		@Override public boolean isTerminated() { return this.stopped; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return this.stopped; }
	}
}
