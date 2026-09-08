package com.seibel.distanthorizons.core.file.fullDatafile.V2;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public class FullDataUpdatePropagatorV2 implements IDebugRenderable, AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	
	/** indicates how long the update queue thread should wait between queuing ticks */
	protected static final int PROPAGATE_QUEUE_THREAD_DELAY_IN_MS = 250;
	
	public static final int NUMBER_OF_PARENT_UPDATE_TASKS_PER_THREAD = 10;
	
	/** how many parent update tasks can be in the queue at once */
	public static int getMaxPropagateTaskCount() { return NUMBER_OF_PARENT_UPDATE_TASKS_PER_THREAD * Config.Common.MultiThreading.numberOfThreads.get(); }
	
	
	
	/**
	 * Tracks which positions are currently being updated
	 * to prevent duplicate concurrent updates.
	 */
	private final Set<Long> updatingPosSet = ConcurrentHashMap.newKeySet();
	
	/**
	 * It'd be better if we could be told when changes are available,
	 * then run the update thread, but having a constantly running background
	 * thread is simpler to deal with and gets the job done.
	 */
	public final ThreadPoolExecutor updateQueueProcessor;
	
	private final AtomicBoolean isShutdownRef = new AtomicBoolean(false);
	private final String levelId;
	
	
	private final FullDataSourceProviderV2 provider;
	private final FullDataUpdaterV2 dataUpdater;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public FullDataUpdatePropagatorV2(FullDataSourceProviderV2 provider, FullDataUpdaterV2 dataUpdater, String levelId)
	{
		this.provider = provider;
		this.dataUpdater = dataUpdater;
		this.levelId = levelId;
		
		// update propagation doesn't need to be run on the server since only the highest detail level is needed
		this.updateQueueProcessor = ThreadUtil.makeSingleThreadPool("Update Propagate Queue [" + this.levelId + "]");
		this.updateQueueProcessor.execute(this::runUpdateQueue);
	}
	
	//endregion
	
	
	
	//================//
	// parent updates //
	//================//
	//region
	
	private void runUpdateQueue()
	{
		while (!this.isShutdownRef.get() && !Thread.interrupted())
		{
			try
			{
				Thread.sleep(PROPAGATE_QUEUE_THREAD_DELAY_IN_MS);
				
				PriorityTaskPicker.Executor executor = ThreadPoolUtil.getUpdatePropagatorExecutor();
				if (executor == null 
					|| executor.isTerminated())
				{
					continue;
				}
				
				// update positions closest to the player (if not on a server)
				// to make world gen appear faster
				DhBlockPos targetBlockPos = DhBlockPos.ZERO;
				if (MC_CLIENT != null 
					&& MC_CLIENT.playerExists())
				{
					targetBlockPos = MC_CLIENT.getPlayerBlockPos();
				}
				
				this.runParentUpdates(executor, targetBlockPos);
				
				if (Config.Common.LodBuilding.Experimental.upsampleLowerDetailLodsToFillHoles.get())
				{
					this.runChildUpdates(executor, targetBlockPos);
				}
				
			}
			catch (InterruptedException ignored)
			{
				Thread.currentThread().interrupt();
			}
			catch (Exception e)
			{
				LOGGER.error("Unexpected error in the parent update queue thread. Error: " + e.getMessage(), e);
			}
		}
	}
	/** will always apply updates */
	private void runParentUpdates(PriorityTaskPicker.Executor executor, DhBlockPos targetBlockPos)
	{
		int maxUpdateTaskCount = getMaxPropagateTaskCount();
		
		// queue parent updates
		if (executor.getQueueSize() > maxUpdateTaskCount
			|| this.updatingPosSet.size() > maxUpdateTaskCount)
		{
			return;
		}
		
		
		// get the positions that need to be applied to their parents
		LongArrayList parentUpdatePosList = this.provider.repo.getPositionsToUpdate(targetBlockPos.getX(), targetBlockPos.getZ(), maxUpdateTaskCount);
		
		// combine updates together based on their parent
		HashMap<Long, HashSet<Long>> updatePosByParentPos = new HashMap<>();
		for (Long pos : parentUpdatePosList)
		{
			updatePosByParentPos.compute(DhSectionPos.getParentPos(pos), (parentPos, updatePosSet) ->
			{
				if (updatePosSet == null)
				{
					updatePosSet = new HashSet<>();
				}
				updatePosSet.add(pos);
				return updatePosSet;
			});
		}
		
		// queue the updates
		for (Long parentUpdatePos : updatePosByParentPos.keySet())
		{
			// stop if there are already a bunch of updates queued
			if (this.updatingPosSet.size() > maxUpdateTaskCount
				|| executor.getQueueSize() > maxUpdateTaskCount)
			{
				break;
			}
			
			// skip any already-queued positions
			if (!this.updatingPosSet.add(parentUpdatePos))
			{
				continue;
			}
			
			try
			{
				executor.execute(() -> this.propagateChildrenToParent(parentUpdatePos, updatePosByParentPos.get(parentUpdatePos)));
			}
			catch (RejectedExecutionException ignore)
			{ this.updatingPosSet.remove(parentUpdatePos); }
			catch (Exception e)
			{
				this.updatingPosSet.remove(parentUpdatePos);
				throw e;
			}
		}
	}
	/** stops if it finds any LOD data */
	private void runChildUpdates(PriorityTaskPicker.Executor executor, DhBlockPos targetBlockPos)
	{
		int maxUpdateTaskCount = getMaxPropagateTaskCount();
		
		// queue child updates
		if (executor.getQueueSize() < maxUpdateTaskCount
			&& this.updatingPosSet.size() < maxUpdateTaskCount)
		{
			// get the positions that need to be applied to their children
			LongArrayList childUpdatePosList = this.provider.repo.getChildPositionsToUpdate(targetBlockPos.getX(), targetBlockPos.getZ(), maxUpdateTaskCount);
			
			// queue the updates
			for (long parentUpdatePos : childUpdatePosList)
			{
				// stop if there are already a bunch of updates queued
				if (this.updatingPosSet.size() > maxUpdateTaskCount
					|| executor.getQueueSize() > maxUpdateTaskCount)
				{
					break;
				}
				
				// skip already updating positions
				if (!this.updatingPosSet.add(parentUpdatePos))
				{
					continue;
				}
				
				try
				{
					executor.execute(() -> this.propagateParentToChildren(parentUpdatePos));
				}
				catch (RejectedExecutionException ignore)
				{ this.updatingPosSet.remove(parentUpdatePos); }
				catch (Exception e)
				{
					this.updatingPosSet.remove(parentUpdatePos);
					throw e;
				}
			}
		}
	}
	
	private void propagateChildrenToParent(long parentPos, Set<Long> childPositions)
	{
		ReentrantLock parentLock = this.dataUpdater.updateLockProvider.getLock(parentPos);
		ArrayList<Long> lockedChildren = new ArrayList<>(4);
		boolean parentLocked = false;
		try
		{
			if (this.isShutdownRef.get() || !parentLock.tryLock()) return;
			parentLocked = true;
			this.dataUpdater.lockedPosSet.add(parentPos);
			ArrayList<Long> children = new ArrayList<>(childPositions);
			if (children.size() > 4) throw new IllegalArgumentException("A section has at most four direct children");
			Collections.sort(children);
			for (long childPos : children)
			{
				if (DhSectionPos.getParentPos(childPos) != parentPos)
					throw new IllegalArgumentException("Unexpected propagation child");
				ReentrantLock childLock = this.dataUpdater.updateLockProvider.getLock(childPos);
				// Never wait while acquiring another level of the tree. Busy work
				// keeps its SQL flag and is picked up by the existing bounded queue.
				if (!childLock.tryLock()) return;
				lockedChildren.add(childPos);
				this.dataUpdater.lockedPosSet.add(childPos);
			}
			try (FullDataSourceV2 parent = this.provider.get(parentPos))
			{
				if (parent == null) return;
				for (long childPos : children)
				{
					try (FullDataSourceV2 child = this.provider.get(childPos))
					{
						if (child == null) return;
						parent.updateFromDataSource(child);
					}
				}
				if (DhSectionPos.getDetailLevel(parentPos) < FullDataSourceProviderV2.ROOT_SECTION_DETAIL_LEVEL)
					parent.applyToParent = true;
				this.dataUpdater.updateDataSource(parent);
				// Keep child locks through commit and flag clearing. Otherwise a new
				// child update could be erased by this older snapshot's completion.
				for (long childPos : children) this.provider.repo.setApplyToParent(childPos, false);
			}
		}
		catch (Exception failure)
		{
			LOGGER.error("Parent LOD propagation was not completed at [" + DhSectionPos.toString(parentPos) + "].", failure);
		}
		finally
		{
			for (int i = lockedChildren.size() - 1; i >= 0; i--)
			{
				long childPos = lockedChildren.get(i);
				this.dataUpdater.lockedPosSet.remove(childPos);
				this.dataUpdater.updateLockProvider.getLock(childPos).unlock();
			}
			if (parentLocked)
			{
				this.dataUpdater.lockedPosSet.remove(parentPos);
				parentLock.unlock();
			}
			this.updatingPosSet.remove(parentPos);
		}
	}

	private void propagateParentToChildren(long parentPos)
	{
		ReentrantLock parentLock = this.dataUpdater.updateLockProvider.getLock(parentPos);
		boolean parentLocked = false;
		try
		{
			if (this.isShutdownRef.get() || !parentLock.tryLock()) return;
			parentLocked = true;
			this.dataUpdater.lockedPosSet.add(parentPos);
			try (FullDataSourceV2 parent = this.provider.get(parentPos))
			{
				if (parent == null) return;
				for (int i = 0; i < 4; i++)
				{
					long childPos = DhSectionPos.getChildByIndex(parentPos, i);
					ReentrantLock childLock = this.dataUpdater.updateLockProvider.getLock(childPos);
					if (!childLock.tryLock()) return;
					this.dataUpdater.lockedPosSet.add(childPos);
					try (FullDataSourceV2 child = this.provider.get(childPos))
					{
						if (child == null) return;
						child.updateFromDataSource(parent);
						if (DhSectionPos.getDetailLevel(childPos) != DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL)
							child.applyToChildren = true;
						this.dataUpdater.updateDataSource(child);
					}
					finally
					{
						this.dataUpdater.lockedPosSet.remove(childPos);
						childLock.unlock();
					}
				}
				// A failed or unavailable child leaves the parent dirty for retry.
				// The parent lock prevents a newer parent write racing this clear.
				this.provider.repo.setApplyToChild(parentPos, false);
			}
		}
		catch (Exception failure)
		{
			LOGGER.error("Child LOD propagation was not completed at [" + DhSectionPos.toString(parentPos) + "].", failure);
		}
		finally
		{
			if (parentLocked)
			{
				this.dataUpdater.lockedPosSet.remove(parentPos);
				parentLock.unlock();
			}
			this.updatingPosSet.remove(parentPos);
		}
	}

	//endregion
	
	
	
	//===========//
	// overrides //
	//===========//
	//region
	
	@Override
	public void debugRender(AbstractDebugWireframeRenderer renderer)
	{
		this.updatingPosSet
				.forEach((pos) -> { renderer.renderBox(new AbstractDebugWireframeRenderer.Box(pos, -32f, 80f, 0.20f, Color.MAGENTA)); });
	}
	
	@Override
	public void close()
	{
		this.isShutdownRef.set(true);
		if (this.updateQueueProcessor != null)
		{
			this.updateQueueProcessor.shutdownNow();
		}
	}
	
	//endregion
	
	
	
}
