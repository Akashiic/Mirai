package com.seibel.distanthorizons.core.file.fullDatafile.V2;

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV1;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V1.FullDataSourceProviderV1;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DataMigratorV1 implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	/** how many data sources should be pulled down for migration at once */
	private static final int MIGRATION_BATCH_COUNT = FullDataUpdatePropagatorV2.NUMBER_OF_PARENT_UPDATE_TASKS_PER_THREAD;

	
	
	
	private final FullDataUpdaterV2 dataUpdater;
	
	
	private boolean migrationStartMessageQueued = false;
	
	private long legacyDeletionCount = -1;
	private long migrationCount = -1;
	private boolean migrationStoppedWithError = false;
	
	/**
	 * Interrupting the migration thread pool doesn't work well and may corrupt the database
	 * vs gracefully shutting down the thread ourselves. 
	 */
	public final AtomicBoolean migrationThreadRunning = new AtomicBoolean(true);
	private final FullDataSourceProviderV1<IDhLevel> v1DataSourceProvider;
	private final AtomicBoolean closeRequested = new AtomicBoolean();
	private final AtomicBoolean workerClaimed = new AtomicBoolean();
	private final CompletableFuture<Void> closedFuture = new CompletableFuture<>();
	
	private final String levelId;
	private final File saveDir;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public DataMigratorV1(
			FullDataUpdaterV2 dataUpdater,
			IDhLevel level, String levelId, File saveDir
		) throws SQLException, IOException
	{
		this(dataUpdater, new FullDataSourceProviderV1<>(level, saveDir), levelId, saveDir,
				ThreadPoolUtil.getFullDataMigrationExecutor());
	}

	DataMigratorV1(FullDataUpdaterV2 dataUpdater, FullDataSourceProviderV1<IDhLevel> v1DataSourceProvider,
			String levelId, File saveDir, Executor executor)
	{
		this.dataUpdater = dataUpdater;
		this.v1DataSourceProvider = v1DataSourceProvider;
		this.saveDir = saveDir;
		this.levelId = levelId;
		try
		{
			if (executor == null) { throw new RejectedExecutionException("Migration executor is unavailable"); }
			executor.execute(this::convertLegacyDataSources);
		}
		catch (RuntimeException | Error error)
		{
			this.close();
			LOGGER.warn("Unable to start migration for level [" + this.levelId + "]. Legacy data is retained.", error);
			if (error instanceof Error) { throw (Error) error; }
		}
	}
	
	
	//=======================//
	// data source migration //
	//=======================//
	
	private void convertLegacyDataSources()
	{
		// close() can claim work that was queued but never started (including abandoned executor queues).
		if (!this.workerClaimed.compareAndSet(false, true)) { return; }
		boolean migratedAny = false;
		try
		{
			if (this.closeRequested.get()) { return; }
			LOGGER.debug("Attempting to migrate data sources for [" + this.levelId + "]-[" + this.saveDir + "]...");
			this.legacyDeletionCount = this.v1DataSourceProvider.repo.getUnusedDataSourceCount();
			while (!this.closeRequested.get() && this.legacyDeletionCount > 0)
			{
				ArrayList<String> unused = this.v1DataSourceProvider.repo.getUnusedDataSourcePositionStringList(50);
				if (unused.isEmpty() || this.closeRequested.get()) { break; }
				this.showMigrationStartMessage();
				this.v1DataSourceProvider.repo.deleteUnusedLegacyData(unused);
				this.legacyDeletionCount -= unused.size();
			}
			if (this.closeRequested.get()) { return; }

			this.migrationCount = this.v1DataSourceProvider.getDataSourceMigrationCount();
			while (!this.closeRequested.get())
			{
				ArrayList<FullDataSourceV1> sources = this.v1DataSourceProvider.getDataSourcesToMigrate(MIGRATION_BATCH_COUNT);
				if (sources.isEmpty()) { break; }
				this.showMigrationStartMessage();
				for (FullDataSourceV1 legacy : sources)
				{
					if (this.closeRequested.get()) { break; }
					try (FullDataSourceV2 converted = FullDataSourceV2.createFromLegacyDataSourceV1(legacy))
					{
						converted.applyToParent = true;
						// Already on the dedicated migration worker. Completing one record here means
						// shutdown never depends on a future abandoned in another executor's queue.
						this.dataUpdater.updateDataSourceForMigration(converted);
						if (this.closeRequested.get()) { break; }
						this.v1DataSourceProvider.repo.deleteWithKey(legacy.getPos());
						this.migrationCount--;
						migratedAny = true;
					}
					catch (CancellationException error)
					{
						// A closing destination is not a corrupt V1 record. Retry on a later load.
						this.closeRequested.set(true);
						break;
					}
					catch (Exception error)
					{
						// Preserve the source and stop this attempt; marking it migrated after a swallowed
						// write error would make recovery impossible.
						throw new IllegalStateException("Migration failed at [" + DhSectionPos.toString(legacy.getPos()) + "]", error);
					}
				}
			}
			if (!this.closeRequested.get())
			{
				this.migrationCount = 0;
				if (migratedAny || this.migrationStartMessageQueued) { this.showMigrationEndMessage(true); }
			}
		}
		catch (Exception error)
		{
			this.migrationStoppedWithError = true;
			LOGGER.warn("Migration stopped for [" + this.levelId + "]. Unconfirmed legacy records are retained.", error);
			this.showMigrationEndMessage(false);
		}
		finally
		{
			this.finishAndCloseProvider();
		}
	}

	private void finishAndCloseProvider()
	{
		this.migrationThreadRunning.set(false);
		try
		{
			this.v1DataSourceProvider.close();
			this.closedFuture.complete(null);
		}
		catch (RuntimeException | Error error)
		{
			this.closedFuture.completeExceptionally(error);
			LOGGER.error("Unable to close the legacy provider for [" + this.levelId + "].", error);
		}
	}

	public CompletableFuture<Void> getClosedFuture() { return this.closedFuture; }
	
	
	private void showMigrationStartMessage()
	{
		if (this.migrationStartMessageQueued)
		{
			return;
		}
		this.migrationStartMessageQueued = true;
		
		ClientApi.INSTANCE.queueSlowChatMessage(
				"Old Distant Horizons data is being migrated for ["+this.levelId+"]. \n" +
				"While migrating LODs may load slowly \n" +
				"and DH world gen will be disabled. \n" +
				"You can see migration progress in the F3 menu."
		);
	}
	
	private void showMigrationEndMessage(boolean success)
	{
		if (success)
		{
			ClientApi.INSTANCE.queueSlowChatMessage("Distant Horizons data migration for ["+this.levelId+"] completed.");
		}
		else
		{
			ClientApi.INSTANCE.queueSlowChatMessage(
					"Distant Horizons data migration for ["+this.levelId+"] stopped. \n" +
					"Some data may not have been migrated."
			);
		}
	}
	
	
	
	//===========//
	// debugging //
	//===========//
	
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		// migration
		boolean migrationErrored = this.migrationStoppedWithError;
		if (!migrationErrored)
		{
			long legacyDeletionCount = this.legacyDeletionCount;
			if (legacyDeletionCount > 0)
			{
				messageList.add("  Migrating - Deleting #: " + F3Screen.NUMBER_FORMAT.format(legacyDeletionCount));
			}
			
			long migrationCount = this.migrationCount;
			if (migrationCount > 0)
			{
				messageList.add("  Migrating - Conversion #: " + F3Screen.NUMBER_FORMAT.format(migrationCount));
			}
		}
		else
		{
			messageList.add("  Migration Failed");
		}
	}
	
	
	
	//===========//
	// overrides //
	//===========//
	
	@Override
	public void close()
	{
		this.closeRequested.set(true);
		this.migrationThreadRunning.set(false);
		if (this.workerClaimed.compareAndSet(false, true)) { this.finishAndCloseProvider(); }
		// An active worker owns cleanup in finally. Never join/interrupt SQL on the render thread.
	}
	
	
	
}
