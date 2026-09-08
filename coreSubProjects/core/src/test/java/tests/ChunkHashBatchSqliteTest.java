package tests;

import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.sql.repo.ChunkHashRepo;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class ChunkHashBatchSqliteTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void successfulBatchCommitsBothHashesAndRestoresAutoCommit() throws Exception
	{
		try (ChunkHashRepo repo = new ChunkHashRepo("jdbc:sqlite", this.temporary.newFile("success.sqlite")))
		{
			Map<DhChunkPos, Integer> hashes = hashes();
			repo.saveBatchChecked(hashes);
			for (Map.Entry<DhChunkPos, Integer> entry : hashes.entrySet())
			{
				assertEquals(entry.getValue().intValue(), repo.getByKey(entry.getKey()).chunkHash);
			}
			assertTrue(repo.getConnection().getAutoCommit());
		}
	}

	@Test
	public void secondWriteFailureRollsBackFirstHashAndAllowsLaterRetry() throws Exception
	{
		try (ChunkHashRepo repo = new ChunkHashRepo("jdbc:sqlite", this.temporary.newFile("failure.sqlite")))
		{
			try (Statement statement = repo.getConnection().createStatement())
			{
				statement.execute("CREATE TRIGGER reject_second BEFORE INSERT ON ChunkHash WHEN NEW.ChunkPosX=1 BEGIN SELECT RAISE(ABORT,'injected second write failure'); END");
			}
			try { repo.saveBatchChecked(hashes()); fail("Expected real SQLite trigger failure"); }
			catch (SQLException expected) { assertTrue(expected.getMessage().contains("injected second write failure")); }
			assertNull(repo.getByKey(new DhChunkPos(0, 0)));
			assertNull(repo.getByKey(new DhChunkPos(1, 0)));
			assertTrue(repo.getConnection().getAutoCommit());
			try (Statement statement = repo.getConnection().createStatement()) { statement.execute("DROP TRIGGER reject_second"); }
			repo.saveBatchChecked(hashes());
			assertEquals(22, repo.getByKey(new DhChunkPos(1, 0)).chunkHash);
		}
	}

	@Test
	public void batchRefusesAnExistingTransactionWithoutCommittingIt() throws Exception
	{
		try (ChunkHashRepo repo = new ChunkHashRepo("jdbc:sqlite", this.temporary.newFile("transaction.sqlite")))
		{
			Connection connection = repo.getConnection();
			connection.setAutoCommit(false);
			try { repo.saveBatchChecked(hashes()); fail("Expected ownership rejection"); }
			catch (SQLException expected) { assertTrue(expected.getMessage().contains("existing transaction")); }
			assertFalse(connection.getAutoCommit());
			connection.rollback();
			connection.setAutoCommit(true);
			assertNull(repo.getByKey(new DhChunkPos(0, 0)));
		}
	}

	private static Map<DhChunkPos, Integer> hashes()
	{
		Map<DhChunkPos, Integer> hashes = new LinkedHashMap<>();
		hashes.put(new DhChunkPos(0, 0), 11);
		hashes.put(new DhChunkPos(1, 0), 22);
		return hashes;
	}
}
