package com.seibel.distanthorizons.core.api.internal.chunkUpdating;

import java.util.concurrent.locks.LockSupport;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.generation.DhLightingEngine;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.world.EWorldEnvironment;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;


/**
 * @see WorldChunkUpdateManager
 */
public class ChunkUpdateQueueManager implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IMinecraftSharedWrapper MC_SHARED = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class);
	
	/**
	 * how many chunks can be queued for updating per thread + player (in multiplayer), 
	 * used to prevent updates from infinitely pilling up if the user flies around extremely fast 
	 */
	public static final int MAX_UPDATING_CHUNK_COUNT_PER_THREAD_AND_PLAYER = 1_000;
	
	/** how many milliseconds must pass before an overloaded message can be sent in chat or the log */
	public static final int MIN_MS_BETWEEN_OVERLOADED_LOG_MESSAGE = 30_000;
	
	public static final long UPDATE_DEBOUNCE_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
	
	/** how long to wait between queue checks if the queue was empty */
	private static final int PROCESS_QUEUE_DELAY_MS = 50;
	/** longest park while waiting for a queued chunk's debounce, so a moving player's new closest chunk is seen quickly */
	private static final long MAX_DEBOUNCE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
	/** retry delay when a ready chunk couldn't be handed to the builder executor */
	private static final long EXECUTOR_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
	
	/** the queueing thread, unparked when new pre-update work arrives */
	private volatile Thread queueingThreadRef;
	
	
	
	private static long lastOverloadedLogMessageMsTime = 0;
	
	
	
	private final Set<DhChunkPos> ignoredChunkPosSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
	
	private final ExecutorService queueingThread;
	
	public final ChunkPosQueue updateQueue;
	public final ChunkPosQueue preUpdateQueue;
	
	/** 
	 * tracking nearby chunks is necessary for lighting
	 * to propagate across chunk borders.
	 */
	public final Cache<DhChunkPos, IChunkWrapper> queuedChunkWrapperByChunkPos = 
		CacheBuilder.newBuilder()
		// expire after access is so the chunks can be used for their adjacents
		.expireAfterAccess(10, TimeUnit.SECONDS)
		.build();
	
	/** dynamically changes based on the number of threads currently available */
	public int maxSize = 500;
	
	/** used to prevent flickering */
	public long lastMsTimeShownActiveInF3Screen = System.currentTimeMillis();
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public ChunkUpdateQueueManager(String levelId)
	{
		this.updateQueue = new ChunkPosQueue();
		this.preUpdateQueue = new ChunkPosQueue();
		
		this.queueingThread = ThreadUtil.makeSingleThreadPool("Chunk Update Queue ["+levelId+"]");
		this.queueingThread.execute(this::runQueueingLoop);
	}
	
	//endregion
	
	
	
	//==================//
	// list/set methods //
	//==================//
	//region
	
	public boolean contains(DhChunkPos pos) 
	{ 
		return this.updateQueue.contains(pos)
			|| this.ignoredChunkPosSet.contains(pos)	
			|| this.preUpdateQueue.contains(pos); 
	}
	
	public void clear()
	{
		this.updateQueue.clear();
		this.preUpdateQueue.clear();
		this.ignoredChunkPosSet.clear();
	}
	public int getQueuedCount() { return this.updateQueue.getQueuedCount() + this.preUpdateQueue.getQueuedCount(); }
	
	public boolean updateQueuesEmpty()
	{
		return this.updateQueue.isEmpty()
				&& this.preUpdateQueue.isEmpty();
	}
	
	/**
	 * Adds an item to the pre-update queue of chunks that might need to be updated.
	 * If there are no more slots, replaces the item furthest from the center in the update queue.
	 */
	public void addItemToPreUpdateQueue(DhChunkPos pos, ChunkUpdateData updateData)
	{
		this.addItemToQueue(pos, updateData, this.preUpdateQueue);
		
		// the queueing thread may be parked waiting for a debounce or an empty queue
		Thread queueingThread = this.queueingThreadRef;
		if (queueingThread != null)
		{
			LockSupport.unpark(queueingThread);
		}
	}
	
	public void addItemToUpdateQueue(DhChunkPos pos, ChunkUpdateData updateData)
	{ this.addItemToQueue(pos, updateData, this.updateQueue); }
	
	private void addItemToQueue(DhChunkPos pos, ChunkUpdateData updateData, ChunkPosQueue queue)
	{
		int remainingSlots = this.maxSize - this.getQueuedCount();
		
		// If no slots are left, get one by removing the item furthest from the center
		if (remainingSlots <= 0)
		{
			ChunkUpdateData removedData = queue.popFurthest();
			if (removedData != null)
			{
				this.queuedChunkWrapperByChunkPos.invalidate(removedData.chunkWrapper.getChunkPos());
			}
		}
		
		queue.addItem(pos,updateData);
		this.queuedChunkWrapperByChunkPos.put(pos, updateData.chunkWrapper); // replaces the queued chunk if present to use the new data
		
		remainingSlots = this.maxSize - this.getQueuedCount();
		if (remainingSlots <= 0)
		{
			this.sendOverloadMessage();
		}
	}
	
	
	private void sendOverloadMessage()
	{
		// limit how often an overloaded message can be sent
		long msBetweenLastLog = System.currentTimeMillis() - lastOverloadedLogMessageMsTime;
		if (msBetweenLastLog >= MIN_MS_BETWEEN_OVERLOADED_LOG_MESSAGE)
		{
			lastOverloadedLogMessageMsTime = System.currentTimeMillis();
			
			String message = MinecraftTextFormat.ORANGE + "Distant Horizons overloaded, too many chunks queued for LOD processing. " + MinecraftTextFormat.CLEAR_FORMATTING +
					"\nThis may result in holes in your LODs. " +
					"\nFix: move through the world slower, decrease your vanilla render distance, slow down your world pre-generator (IE Chunky), or increase the Distant Horizons' CPU thread counts. " +
					"\nMax queue count [" + this.maxSize + "] ([" + MAX_UPDATING_CHUNK_COUNT_PER_THREAD_AND_PLAYER + "] per thread+players).";
			
			boolean showWarningInChat = Config.Common.Logging.Warning.showUpdateQueueOverloadedChatWarning.get();
			if (showWarningInChat)
			{
				ClientApi.INSTANCE.queueSlowChatMessage(message);
			}
			
			// Don't log warnings in singleplayer or in hosted LAN since it usually isn't a problem (and if it is it's easy to notice).
			// Servers should always log since being overloaded is harder to notice. 
			EWorldEnvironment environment = SharedApi.getEnvironment();
			if (showWarningInChat || environment == EWorldEnvironment.SERVER_ONLY)
			{
				LOGGER.warn(message);
			}
		}
	}
	
	/** 
	 * Tries to return a cloned chunk wrapper from memory.
	 * Returns null if no chunk is available.
	 * <br><br>
	 * This is done instead of accessing the MC level since
	 * accessing the level often requires running on the render or server
	 * thread, which causes stuttering.
	 */
	@Nullable
	public IChunkWrapper tryGetChunk(DhChunkPos pos)
	{
		IChunkWrapper existingWrapper = this.queuedChunkWrapperByChunkPos.getIfPresent(pos);
		if (existingWrapper == null)
		{
			return null;
		}
	
		return existingWrapper.copy();
	}
	
	//endregion
	
	
	
	//=========//
	// ignores //
	//=========//
	//region
	
	public void addPosToIgnore(DhChunkPos chunkPos) { this.ignoredChunkPosSet.add(chunkPos); }
	public void removePosToIgnore(DhChunkPos chunkPos) { this.ignoredChunkPosSet.remove(chunkPos); }
	
	//endregion
	
	
	
	//===================//
	// update processing //
	//===================//
	//region
	
	private void runQueueingLoop()
	{
		this.queueingThreadRef = Thread.currentThread();
		while (!Thread.interrupted())
		{
			try
			{
				long waitNanos = this.processQueueAndGetWaitNanos();
				
				// if the queue is empty, 
				// reduce the rate that we check the queue again
				if (this.updateQueue.isEmpty() 
					&& this.preUpdateQueue.isEmpty())
				{
					// parked instead of sleeping so new pre-update work wakes it immediately
					LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(PROCESS_QUEUE_DELAY_MS));
					
					// clean up to fix expired chunks not being cleaned up if the queue is empty
					this.queuedChunkWrapperByChunkPos.cleanUp();
				}
				else if (waitNanos > 0)
				{
					// The closest chunk is still inside its debounce window (or the builder refused it).
					// This used to loop straight back, popping and re-adding the same chunk and burning a core
					// for as long as any chunk was queued. The dispatch order is unchanged: only idle spins go.
					LockSupport.parkNanos(this, Math.min(waitNanos, MAX_DEBOUNCE_PARK_NANOS));
				}
			}
			catch (Exception e)
			{
				LOGGER.error("Unexpected error in chunk update queueing thread, error: ["+e.getMessage()+"].", e);
			}
		}
	}
	
	public void processQueue()
	{
		this.updateCenterAndMaxSize();
		this.processQueuedChunkPreUpdate();
		this.tryDispatchReadyChunkUpdate();
	}
	
	private void updateCenterAndMaxSize()
	{
		// update the center & max size of the queue manager
		int maxUpdateSizeMultiplier;
		if (MC_CLIENT != null && MC_CLIENT.playerExists())
		{
			// Local worlds & multiplayer
			this.setCenter(MC_CLIENT.getPlayerChunkPos());
			maxUpdateSizeMultiplier = MC_CLIENT.clientConnectedToDedicatedServer() ? 1 : MC_SHARED.getPlayerCount();
		}
		else
		{
			// Dedicated servers
			// Also includes spawn chunks since they're likely to be intentionally utilized with updates
			maxUpdateSizeMultiplier = 1 + MC_SHARED.getPlayerCount();
		}
		
		this.maxSize = MAX_UPDATING_CHUNK_COUNT_PER_THREAD_AND_PLAYER
			* Config.Common.MultiThreading.numberOfThreads.get()
			* maxUpdateSizeMultiplier;
	}
	
	/** @return 0 if work was done, the nanoseconds to wait before the next useful attempt otherwise (-1: nothing queued) */
	private long processQueueAndGetWaitNanos()
	{
		this.updateCenterAndMaxSize();
		boolean preUpdated = this.processQueuedChunkPreUpdate();
		long dispatchWaitNanos = this.tryDispatchReadyChunkUpdate();
		return preUpdated ? 0 : dispatchWaitNanos;
	}
	
	/** @return true if a queued pre-update was taken (whatever its outcome) */
	private boolean processQueuedChunkPreUpdate()
	{
		ChunkUpdateData preUpdateData = this.preUpdateQueue.popClosest();
		if (preUpdateData == null)
		{
			return false;
		}
		
		IDhLevel dhLevel = preUpdateData.dhLevel;
		IChunkWrapper chunkWrapper = preUpdateData.chunkWrapper;
		if (isStaleClientLevel(dhLevel)) { return true; }
		chunkWrapper.createDhHeightMaps();
		
		try
		{
			// check if this chunk has been converted into an LOD already
			boolean checkChunkHash = !Config.Common.LodBuilding.disableUnchangedChunkCheck.get();
			if (checkChunkHash)
			{
				int oldChunkHash = dhLevel.getChunkHash(chunkWrapper.getChunkPos()); // shouldn't happen on the render thread since it may take a few moments to run
				int newChunkHash = chunkWrapper.getBlockBiomeHashCode();
				
				boolean hasNewChunkHash = (oldChunkHash != newChunkHash);
				if (!hasNewChunkHash && com.seibel.distanthorizons.core.util.ChunkDataCoverage.hasPersistedFullResolutionData(
						dhLevel.getFullDataProvider(), chunkWrapper.getChunkPos()))
				{
					// A matching index alone cannot prove that an older async write persisted.
					// Keep the fast path only when the corresponding full-resolution data exists.
					return true;
				}
			}
			
			this.addItemToUpdateQueue(chunkWrapper.getChunkPos(), preUpdateData);
		}
		catch (Exception e)
		{
			if (!ExceptionUtil.isShutdownException(e))
			{ LOGGER.error("Unexpected error when pre-updating chunk at pos: [" + chunkWrapper.getChunkPos() + "]", e); }
		}
		return true;
	}
	
	/** @return 0 if a chunk was dispatched, -1 if nothing is queued, otherwise the nanoseconds until retrying is useful */
	private long tryDispatchReadyChunkUpdate()
	{
		ChunkUpdateData updateData = this.updateQueue.popClosest();
		if (updateData == null)
		{
			return -1;
		}
		
		// wait for UPDATE_DEBOUNCE_NANOS before updating
		// the chunk so we can make sure the neighbors are queued
		long updateWaitNanoTime = System.nanoTime() - updateData.firstQueuedNanoTime;
		if (updateWaitNanoTime < UPDATE_DEBOUNCE_NANOS)
		{
			this.updateQueue.addItem(updateData.chunkWrapper.getChunkPos(), updateData);
			return Math.max(1, UPDATE_DEBOUNCE_NANOS - updateWaitNanoTime);
		}
		
		AbstractExecutorService executor = ThreadPoolUtil.getChunkToLodBuilderExecutor();
		if (executor == null)
		{
			this.updateQueue.addItem(updateData.chunkWrapper.getChunkPos(), updateData);
			return EXECUTOR_RETRY_NANOS;
		}
		
		try
		{
			executor.execute(() -> this.processChunkUpdate(updateData));
		}
		catch (RejectedExecutionException ignore)
		{
			this.updateQueue.addItem(updateData.chunkWrapper.getChunkPos(), updateData);
			return EXECUTOR_RETRY_NANOS;
		}
		return 0;
	}
	
	private void processChunkUpdate(ChunkUpdateData updateData)
	{
		IChunkWrapper chunkWrapper = updateData.chunkWrapper;
		IDhLevel dhLevel = updateData.dhLevel;
		ILevelWrapper levelWrapper = dhLevel.getLevelWrapper();
		if (isStaleClientLevel(dhLevel)) { return; }
		
		// having a list of the nearby chunks is needed for lighting and beacon generation
		ArrayList<IChunkWrapper> nearbyChunkList = this.tryGetNeighborChunkListForChunk(chunkWrapper);
		
		try
		{
			// sky lighting is populated later at the data source level
			DhLightingEngine.INSTANCE.bakeChunkBlockLighting(chunkWrapper, nearbyChunkList, levelWrapper.hasSkyLight() ? LodUtil.MAX_MC_LIGHT : LodUtil.MIN_MC_LIGHT);
			
			dhLevel.updateBeaconBeamsForChunk(chunkWrapper, nearbyChunkList);
			
			int newChunkHash = chunkWrapper.getBlockBiomeHashCode();
			if (!isStaleClientLevel(dhLevel)) { dhLevel.updateChunkAsync(chunkWrapper, newChunkHash); }
		}
		catch (Exception e)
		{
			if (!ExceptionUtil.isShutdownException(e))
			{ LOGGER.error("Unexpected error when updating chunk at pos: [" + chunkWrapper.getChunkPos() + "]", e); }
		}
	}

	private static boolean isStaleClientLevel(IDhLevel dhLevel)
	{
		return dhLevel instanceof IDhClientLevel
				&& dhLevel.getLevelWrapper().getDhLevel() != dhLevel;
	}
	
	private ArrayList<IChunkWrapper> tryGetNeighborChunkListForChunk(IChunkWrapper chunkWrapper)
	{
		// get the neighboring chunk list
		ArrayList<IChunkWrapper> neighborChunkList = new ArrayList<>(9);
		for (int xOffset = -1; xOffset <= 1; xOffset++)
		{
			for (int zOffset = -1; zOffset <= 1; zOffset++)
			{
				if (xOffset == 0 && zOffset == 0)
				{
					// center chunk
					neighborChunkList.add(chunkWrapper);
				}
				else
				{
					// neighboring chunk 
					DhChunkPos neighborPos = new DhChunkPos(chunkWrapper.getChunkPos().getX() + xOffset, chunkWrapper.getChunkPos().getZ() + zOffset);
					IChunkWrapper neighborChunk = this.tryGetChunk(neighborPos);
					if (neighborChunk != null)
					{
						neighborChunkList.add(neighborChunk);
					}
				}
			}
		}
		return neighborChunkList;
	}
	
	//endregion
	
	
	
	//==================//
	// position methods //
	//==================//
	//region
	
	public void setCenter(DhChunkPos newCenter)
	{
		this.updateQueue.setCenter(newCenter);
		this.preUpdateQueue.setCenter(newCenter);
	}
	
	//endregion
	
	
	
	//=========//
	// F3 Menu //
	//=========//
	//region
	
	public String getDebugMenuString()
	{
		String y = MinecraftTextFormat.YELLOW;
		String o = MinecraftTextFormat.ORANGE;
		String cf = MinecraftTextFormat.CLEAR_FORMATTING;
		
		
		String preUpdatingCountStr = F3Screen.NUMBER_FORMAT.format(this.preUpdateQueue.getQueuedCount());
		String updatingCountStr = F3Screen.NUMBER_FORMAT.format(this.updateQueue.getQueuedCount());
		String queuedCountStr = F3Screen.NUMBER_FORMAT.format(this.getQueuedCount());
		
		String maxUpdateCountStr = F3Screen.NUMBER_FORMAT.format(this.maxSize);
		
		return "Queued chunk updates: "+"("+y+preUpdatingCountStr+cf+" + "+o+updatingCountStr+cf+") ["+queuedCountStr+"/"+maxUpdateCountStr+"]";
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public void close() { this.queueingThread.shutdownNow(); }
	
	//endregion
	
	
	
}
