package tests;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import testItems.sql.TestPrimaryKeyRepo;
import testItems.sql.TestSingleKeyDto;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** Real SQLite connections, with a driver boundary controlling shutdown interleavings. */
public class DhRepoLifecycleTest
{
	@Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test(timeout = 15000)
	public void connectionCompletingAfterCloseIsNotPublished() throws Exception
	{
		this.assertDelayedAcquisitionIsDiscarded(false);
	}

	@Test(timeout = 15000)
	public void statementCreationToleratesCloseDuringConnectionAcquisition() throws Exception
	{
		this.assertDelayedAcquisitionIsDiscarded(true);
	}

	private void assertDelayedAcquisitionIsDiscarded(boolean prepareStatement) throws Exception
	{
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try (FixtureDriver driver = new FixtureDriver();
			TestPrimaryKeyRepo repo = new TestPrimaryKeyRepo(driver.prefix, this.newDatabase()))
		{
			driver.mode = Mode.BLOCK_CONNECT;
			Future<?> pending = worker.submit(() -> prepareStatement
					? repo.createPreparedStatement("SELECT 1;") : repo.getConnection());
			try
			{
				Assert.assertTrue("Worker did not enter the driver", driver.connectEntered.await(5, TimeUnit.SECONDS));
				repo.close();
				Assert.assertNull(repo.getConnection());
				driver.resumeConnect.countDown();
				Assert.assertNull("Late acquisition must report repository closure", pending.get(5, TimeUnit.SECONDS));
				driver.assertAllConnectionsClosed();
			}
			finally
			{
				driver.resumeConnect.countDown();
			}
		}
		finally
		{
			worker.shutdownNow();
			Assert.assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test(timeout = 15000)
	public void failedConnectionPragmaClosesTheUnpublishedConnection() throws Exception
	{
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try (FixtureDriver driver = new FixtureDriver();
			TestPrimaryKeyRepo repo = new TestPrimaryKeyRepo(driver.prefix, this.newDatabase()))
		{
			driver.mode = Mode.FAIL_INITIALIZATION;
			Future<Connection> pending = worker.submit(repo::getConnection);
			try
			{
				pending.get(5, TimeUnit.SECONDS);
				Assert.fail("Injected PRAGMA failure was not propagated");
			}
			catch (ExecutionException expected)
			{
				Assert.assertTrue(expected.getCause() instanceof RuntimeException);
				Assert.assertTrue(expected.getCause().getCause() instanceof SQLException);
			}
			Assert.assertTrue("Failed initialization retained an open SQLite connection", driver.lastConnection().isClosed());
			Assert.assertFalse("Existing connections must remain usable", repo.getConnection().isClosed());
		}
		finally
		{
			worker.shutdownNow();
			Assert.assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test(timeout = 15000)
	public void failedRepositoryConstructorClosesConnectionsItOpened() throws Exception
	{
		try (FixtureDriver driver = new FixtureDriver())
		{
			driver.mode = Mode.FAIL_SCHEMA;
			try (TestPrimaryKeyRepo ignored = new TestPrimaryKeyRepo(driver.prefix, this.newDatabase()))
			{
				Assert.fail("Injected schema failure was not propagated");
			}
			catch (RuntimeException expected)
			{
				Assert.assertTrue(expected.getCause() instanceof SQLException);
			}
			driver.assertAllConnectionsClosed();
		}
	}

	@Test(timeout = 15000)
	public void runtimeCloseFailureStillAttemptsEveryConnectionAndCanBeRetried() throws Exception
	{
		this.assertCloseFailureAttemptsRemainingConnections(new IllegalStateException("Injected runtime close failure"));
	}

	@Test(timeout = 15000)
	public void errorOnCloseStillAttemptsEveryConnectionAndCanBeRetried() throws Exception
	{
		this.assertCloseFailureAttemptsRemainingConnections(new AssertionError("Injected driver close error"));
	}

	@Test(timeout = 15000)
	public void sqlCloseFailureIsNotReportedAsSuccessfulShutdown() throws Exception
	{
		this.assertCloseFailureAttemptsRemainingConnections(new SQLException("Injected SQL close failure"));
	}

	private void assertCloseFailureAttemptsRemainingConnections(Throwable injectedFailure) throws Exception
	{
		ExecutorService workers = Executors.newFixedThreadPool(2);
		CountDownLatch acquired = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		try (FixtureDriver driver = new FixtureDriver();
			TestPrimaryKeyRepo repo = new TestPrimaryKeyRepo(driver.prefix, this.newDatabase()))
		{
			Future<?> first = workers.submit(() -> this.acquireAndHold(repo, acquired, release));
			Future<?> second = workers.submit(() -> this.acquireAndHold(repo, acquired, release));
			Assert.assertTrue("Both worker connections must exist before shutdown", acquired.await(5, TimeUnit.SECONDS));
			release.countDown();
			first.get(5, TimeUnit.SECONDS);
			second.get(5, TimeUnit.SECONDS);
			Assert.assertEquals(3, driver.connections.size());

			driver.closeFailures.add(injectedFailure);
			try
			{
				repo.close();
				Assert.fail("Failed driver close must prevent successful shutdown");
			}
			catch (RuntimeException | Error expected)
			{
				if (injectedFailure instanceof SQLException) Assert.assertSame(injectedFailure, expected.getCause());
				else Assert.assertSame(injectedFailure, expected);
			}
			Assert.assertEquals("A failure on the first close must not skip any subsequent connection", 3, driver.closeAttempts.get());
			int closedCount = 0;
			for (Connection connection : driver.connections) if (connection.isClosed()) closedCount++;
			Assert.assertEquals(2, closedCount);
			Assert.assertNull("A failed close must not reopen the repository", repo.getConnection());

			repo.close();
			Assert.assertEquals("Only the failed handle should be retried", 4, driver.closeAttempts.get());
			driver.assertAllConnectionsClosed();
			repo.close();
			Assert.assertEquals("Completed shutdown is idempotent", 4, driver.closeAttempts.get());
		}
		finally
		{
			release.countDown();
			workers.shutdownNow();
			Assert.assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	@Test(timeout = 15000)
	public void multipleDriverCloseFailuresAreAggregatedAndAllFailedHandlesRetried() throws Exception
	{
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try (FixtureDriver driver = new FixtureDriver();
			TestPrimaryKeyRepo repo = new TestPrimaryKeyRepo(driver.prefix, this.newDatabase()))
		{
			Assert.assertNotNull(worker.submit(repo::getConnection).get(5, TimeUnit.SECONDS));
			IllegalStateException firstFailure = new IllegalStateException("First driver close failure");
			AssertionError secondFailure = new AssertionError("Second driver close failure");
			driver.closeFailures.add(firstFailure);
			driver.closeFailures.add(secondFailure);
			try
			{
				repo.close();
				Assert.fail("Both close failures must be propagated");
			}
			catch (IllegalStateException expected)
			{
				Assert.assertSame(firstFailure, expected);
				Assert.assertArrayEquals(new Throwable[]{secondFailure}, expected.getSuppressed());
			}
			Assert.assertEquals(2, driver.closeAttempts.get());
			repo.close();
			Assert.assertEquals(4, driver.closeAttempts.get());
			driver.assertAllConnectionsClosed();
		}
		finally
		{
			worker.shutdownNow();
			Assert.assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	private void acquireAndHold(TestPrimaryKeyRepo repo, CountDownLatch acquired, CountDownLatch release)
	{
		Assert.assertNotNull(repo.getConnection());
		acquired.countDown();
		try { Assert.assertTrue(release.await(5, TimeUnit.SECONDS)); }
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new AssertionError(failure);
		}
	}

	@Test(timeout = 15000)
	public void constructorFailurePreservesCleanupFailureAsSuppressed() throws Exception
	{
		try (FixtureDriver driver = new FixtureDriver())
		{
			driver.mode = Mode.FAIL_SCHEMA;
			IllegalStateException closeFailure = new IllegalStateException("Injected constructor cleanup failure");
			driver.closeFailures.add(closeFailure);
			try (TestPrimaryKeyRepo ignored = new TestPrimaryKeyRepo(driver.prefix, this.newDatabase()))
			{
				Assert.fail("Injected schema failure was not propagated");
			}
			catch (RuntimeException expected)
			{
				Assert.assertTrue(expected.getCause() instanceof SQLException);
				Assert.assertTrue(expected.getCause().getMessage().contains("schema initialization"));
				Assert.assertArrayEquals(new Throwable[]{closeFailure}, expected.getSuppressed());
			}
			Assert.assertEquals(1, driver.closeAttempts.get());
		}
	}

	@Test
	public void checkedSaveConfirmsRowsAndRejectsClosedOrFailedWrites() throws Exception
	{
		try (FixtureDriver driver = new FixtureDriver();
			TestPrimaryKeyRepo repo = new TestPrimaryKeyRepo(driver.prefix, this.newDatabase()))
		{
			TestSingleKeyDto dto = new TestSingleKeyDto(17, "migration", 21L, (byte) 8);
			Assert.assertTrue(repo.saveChecked(dto));
			Assert.assertEquals(dto, repo.getByKey(dto.getKey()));
			try (Statement statement = repo.getConnection().createStatement())
			{
				statement.execute("DROP TABLE Test;");
			}
			try
			{
				repo.saveChecked(dto);
				Assert.fail("SQL failure must not acknowledge the write");
			}
			catch (RuntimeException expected)
			{
				Assert.assertTrue(expected.getCause() instanceof SQLException);
			}
			repo.close();
			Assert.assertFalse(repo.saveChecked(dto));
		}
	}

	private File newDatabase() throws Exception
	{
		return this.temporaryFolder.newFile("lifecycle-" + UUID.randomUUID() + ".sqlite");
	}

	@Test
	public void checkedSaveCannotAcknowledgeAnUncommittedTransaction() throws Exception
	{
		try (FixtureDriver driver = new FixtureDriver();
			TestPrimaryKeyRepo repo = new TestPrimaryKeyRepo(driver.prefix, this.newDatabase()))
		{
			Connection connection = repo.getConnection();
			connection.setAutoCommit(false);
			try
			{
				repo.saveChecked(new TestSingleKeyDto(18, "uncommitted", 21L, (byte) 8));
				Assert.fail("An uncommitted destination write must not be acknowledged");
			}
			catch (IllegalStateException expected)
			{
				Assert.assertTrue(expected.getMessage().contains("auto-commit"));
			}
			try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM Test;"))
			{
				Assert.assertTrue(result.next());
				Assert.assertEquals(0, result.getInt(1));
			}
			connection.rollback();
			connection.setAutoCommit(true);
		}
	}

	private enum Mode { PASS, BLOCK_CONNECT, FAIL_INITIALIZATION, FAIL_SCHEMA }

	/** A unique URL namespace prevents interference with other SQLite tests. */
	private static final class FixtureDriver implements Driver, AutoCloseable
	{
		final String prefix = "jdbc:akashic_lifecycle_" + UUID.randomUUID();
		final CountDownLatch connectEntered = new CountDownLatch(1);
		final CountDownLatch resumeConnect = new CountDownLatch(1);
		final List<Connection> connections = new CopyOnWriteArrayList<>();
		final AtomicInteger closeAttempts = new AtomicInteger();
		final Queue<Throwable> closeFailures = new ConcurrentLinkedQueue<>();
		volatile Mode mode = Mode.PASS;

		FixtureDriver() throws SQLException
		{
			DriverManager.registerDriver(this);
		}

		@Override public Connection connect(String url, Properties properties) throws SQLException
		{
			if (!this.acceptsURL(url)) return null;
			Connection real = DriverManager.getConnection("jdbc:sqlite:" + url.substring(this.prefix.length() + 1), properties);
			this.connections.add(real);
			Mode connectionMode = this.mode;
			if (connectionMode == Mode.BLOCK_CONNECT)
			{
				this.connectEntered.countDown();
				try
				{
					if (!this.resumeConnect.await(10, TimeUnit.SECONDS)) throw new SQLException("Fixture connection barrier timed out");
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					throw new SQLException("Fixture connection interrupted", e);
				}
			}
			return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) ->
			{
				if (method.getName().equals("close"))
				{
					this.closeAttempts.incrementAndGet();
					Throwable failure = this.closeFailures.poll();
					if (failure != null) throw failure;
				}
				Object result = invoke(real, method, args);
				if ((connectionMode == Mode.FAIL_INITIALIZATION || connectionMode == Mode.FAIL_SCHEMA) && method.getName().equals("createStatement"))
				{
					Statement statement = (Statement) result;
					return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class}, (p, m, a) ->
					{
						if (connectionMode == Mode.FAIL_INITIALIZATION && m.getName().equals("execute") && ((String) a[0]).startsWith("PRAGMA synchronous"))
						{
							throw new SQLException("Injected PRAGMA initialization failure");
						}
						// Fail the base repository schema probe, before a subclass constructor runs.
						if (connectionMode == Mode.FAIL_SCHEMA && m.getName().equals("execute") && ((String) a[0]).contains("sqlite_master"))
						{
							throw new SQLException("Injected schema initialization failure");
						}
						return invoke(statement, m, a);
					});
				}
				return result;
			});
		}

		Connection lastConnection() { return this.connections.get(this.connections.size() - 1); }
		void assertAllConnectionsClosed() throws SQLException
		{
			Assert.assertFalse("Fixture did not open real SQLite", this.connections.isEmpty());
			for (Connection connection : this.connections) Assert.assertTrue(connection.isClosed());
		}
		@Override public boolean acceptsURL(String url) { return url != null && url.startsWith(this.prefix + ":"); }
		@Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) { return new DriverPropertyInfo[0]; }
		@Override public int getMajorVersion() { return 1; }
		@Override public int getMinorVersion() { return 0; }
		@Override public boolean jdbcCompliant() { return false; }
		@Override public Logger getParentLogger() { return Logger.getGlobal(); }
		@Override public void close() throws SQLException
		{
			this.resumeConnect.countDown();
			DriverManager.deregisterDriver(this);
			for (Connection connection : this.connections) connection.close();
		}

		private static Object invoke(Object target, Method method, Object[] args) throws Throwable
		{
			try { return method.invoke(target, args); }
			catch (InvocationTargetException e) { throw e.getCause(); }
		}
	}
}
