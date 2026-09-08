package com.seibel.distanthorizons.common.wrappers.worldGeneration;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.common.util.threading.ServerThreadTaskHandler;
import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.common.wrappers.modAccessor.IHodgePodgeCommonAccessor;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.params.GlobalWorldGenParams;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateQueueManager;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.WorldChunkUpdateManager;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.MinecraftTextFormat;
import com.seibel.distanthorizons.core.generation.DhLightingEngine;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.TimerUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IC2meAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modLoader.IForgeMain;
import com.seibel.distanthorizons.coreapi.ModInfo;

import org.jetbrains.annotations.Nullable;
#if MC_VER <= MC_1_7_10
import com.seibel.distanthorizons.common.backports.ChunkPos;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.ForgeChunkManager;
#elif MC_VER <= MC_1_12_2
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.ForgeChunkManager;
#else
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
#endif

#if MC_VER <= MC_1_12_2
#elif MC_VER <= MC_1_20_4
import net.minecraft.world.level.chunk.ChunkStatus;
#else
import net.minecraft.world.level.chunk.status.ChunkStatus;
#endif

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

public class InternalServerGenerator implements AutoCloseable
{
	public static final DhLogger LOGGER = new DhLoggerBuilder()
			.name("LOD World Gen - Internal Server")
			.fileLevelConfig(Config.Common.Logging.logWorldGenEventToFile)
			.build();
	
	public static final DhLogger CHUNK_LOAD_LOGGER = new DhLoggerBuilder()
			.name("LOD Chunk Loading")
			.fileLevelConfig(Config.Common.Logging.logWorldGenChunkLoadEventToFile)
			.build();
	
	#if MC_VER <= MC_1_12_2
	private static final IForgeMain FORGE_MAIN = SingletonInjector.INSTANCE.get(IForgeMain.class);
	#endif
	
	private static final IC2meAccessor C2ME_ACCESSOR = ModAccessorInjector.INSTANCE.get(IC2meAccessor.class);
	private static final IHodgePodgeCommonAccessor HODGE_PODGE_ACCESSOR = ModAccessorInjector.INSTANCE.get(IHodgePodgeCommonAccessor.class);
	
	/**
	 * Used to revert the ignore logic in {@link SharedApi} so
	 * that a given chunk pos can be handled again.
	 * A timer is used so we don't have to inject into MC's code and it works sell enough
	 * most of the time.
	 * If a chunk does get through due the timeout not being long enough that isn't the end of the world.
	 */
	private static final int MS_TO_IGNORE_CHUNK_AFTER_COMPLETION = 5_000;
	
	
	#if MC_VER <= MC_1_12_2
	#elif MC_VER < MC_1_21_5
	private static final TicketType<ChunkPos> DH_SERVER_GEN_TICKET = TicketType.create("dh_server_gen_ticket", Comparator.comparingLong(ChunkPos::toLong));
	#elif MC_VER < MC_1_21_9
	private static final TicketType DH_SERVER_GEN_TICKET = new TicketType(/* timeout, 0 = disabled*/0L, /* persist */ false, TicketType.TicketUse.LOADING);
	#else
	private static final TicketType DH_SERVER_GEN_TICKET = new TicketType(/* timeout, 0 = disabled*/0L, /* flags */TicketType.FLAG_LOADING);
	#endif
	
	private static boolean c2meMissingWarningLogged = false;
	
	
	private final GlobalWorldGenParams params;
	private final IDhServerLevel dhServerLevel;
	@Nullable
	private final ChunkUpdateQueueManager updateManager;
	private final Timer chunkSaveIgnoreTimer = TimerUtil.CreateTimer("ChunkSaveIgnoreTimer");
	private final Object lifecycleLock = new Object();
	private volatile boolean closed;
	private int activeGenerations;
	private final Set<DhChunkPos> ownedIgnorePositions = ConcurrentHashMap.newKeySet();
	#if MC_VER <= MC_1_12_2
	private final long serverSessionId = ServerThreadTaskHandler.INSTANCE.getSessionId();
	private final ChunkTicketPool<DhChunkPos, ForgeChunkManager.Ticket> chunkTickets;
	#endif

	#if MC_VER <= MC_1_12_2
	/**
	 * Older Minecraft needs neighboring chunks loaded.
	 * This map tracks how many in-flight generation events currently need each chunk pos loaded.
	 */
	private final ConcurrentHashMap<DhChunkPos, Integer> generationChunkRefCountMap = new ConcurrentHashMap<>();
	#if MC_VER <= MC_1_7_10
	private final ChunkSimulationOwnership<DhChunkPos> simulationOwnership = new ChunkSimulationOwnership<>();
	#endif
	#endif
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public InternalServerGenerator(GlobalWorldGenParams params, IDhServerLevel dhServerLevel)
	{
		this.params = params;
		this.dhServerLevel = dhServerLevel;
		this.updateManager = WorldChunkUpdateManager.INSTANCE.getByLevelWrapper(this.dhServerLevel.getServerLevelWrapper());

		#if MC_VER <= MC_1_12_2
		// Ticket allocation and release occur lazily on the world owner thread.
		// Several bounded tickets replace the old reflection override of maxDepth.
		this.chunkTickets = new ChunkTicketPool<>(
			() -> ForgeChunkManager.requestTicket(FORGE_MAIN, params.mcServerLevel, ForgeChunkManager.Type.NORMAL),
			(ticket) -> {
				int configuredDepth = ticket.getChunkListDepth();
				// Forge treats nonpositive depth as unlimited. Keep our bookkeeping
				// bounded without changing the ticket or rejecting that valid setting.
				return configuredDepth > 0 ? configuredDepth : 256;
			},
			(ticket, pos) -> {
				#if MC_VER <= MC_1_7_10
				ForgeChunkManager.forceChunk(ticket, new ChunkCoordIntPair(pos.getX(), pos.getZ()));
				#else
				ForgeChunkManager.forceChunk(ticket, new ChunkPos(pos.getX(), pos.getZ()));
				#endif
			},
			(ticket, pos) -> {
				#if MC_VER <= MC_1_7_10
				ForgeChunkManager.unforceChunk(ticket, new ChunkCoordIntPair(pos.getX(), pos.getZ()));
				#else
				ForgeChunkManager.unforceChunk(ticket, new ChunkPos(pos.getX(), pos.getZ()));
				#endif
			}, ForgeChunkManager::releaseTicket);
		#endif
	}

	private void requireRunning(GenerationEvent event)
	{
		if (this.closed || event.future.isCancelled() || Thread.currentThread().isInterrupted())
			throw new CancellationException("Mirai world generation was closed or cancelled.");
	}

	@Override
	public void close()
	{
		boolean finish;
		synchronized (this.lifecycleLock)
		{
			if (this.closed) return;
			this.closed = true;
			this.chunkSaveIgnoreTimer.cancel();
			finish = this.activeGenerations == 0;
		}
		if (finish) this.finishClosing();
	}

	private void finishClosing()
	{
		#if MC_VER <= MC_1_12_2
		java.util.function.Supplier<Void> cleanup = () -> {
			GenerationChunkLease.runCleanupSteps(
				this.chunkTickets::close,
				() -> {
					#if MC_VER <= MC_1_7_10
					if (HODGE_PODGE_ACCESSOR != null)
						this.simulationOwnership.releaseAll(pos -> HODGE_PODGE_ACCESSOR.allowChunkSimulation(this.params.mcServerLevel, pos.getX(), pos.getZ()));
					#endif
				},
				this::clearOwnedIgnores);
			return null;
		};
		if (ServerThreadTaskHandler.INSTANCE.isServerThread()
			&& ServerThreadTaskHandler.INSTANCE.getSessionId() == this.serverSessionId)
		{
			cleanup.get();
		}
		else
		{
			ServerThreadTaskHandler.INSTANCE.queueEssentialTask(this.serverSessionId, cleanup)
				.whenComplete((ignored, failure) -> {
					if (failure != null && !ExceptionUtil.isShutdownException(failure))
						LOGGER.error("Failed to finish closing Mirai chunk tickets.", failure);
				});
		}
		#else
		this.clearOwnedIgnores();
		#endif
	}

	private void clearOwnedIgnores()
	{
		ArrayList<Runnable> steps = new ArrayList<>();
		for (DhChunkPos pos : new ArrayList<>(this.ownedIgnorePositions)) steps.add(() -> this.removeOwnedIgnore(pos));
		GenerationChunkLease.runCleanupSteps(steps.toArray(new Runnable[0]));
	}

	private void markChunkIgnored(DhChunkPos pos)
	{
		if (this.updateManager != null) {
			this.ownedIgnorePositions.add(pos);
			this.updateManager.addPosToIgnore(pos);
		}
	}
	private void removeOwnedIgnore(DhChunkPos pos)
	{
		if (this.updateManager != null && this.ownedIgnorePositions.contains(pos)) {
			this.updateManager.removePosToIgnore(pos);
			this.ownedIgnorePositions.remove(pos);
		}
	}
	
	
	
	#if MC_VER <= MC_1_12_2
	//===================================//
	// neighbor-chunk reference counting //
	//===================================//
	
	/**
	 * Marks the given pos as needed by one more generation event. <br>
	 * Must be called from the server thread.
	 *
	 * @return true if this was the first reference, meaning the chunk needs to be loaded.
	 */
	private boolean acquireChunkRef(DhChunkPos chunkPos)
	{
		Integer refCount = this.generationChunkRefCountMap.get(chunkPos);
		int newRefCount = (refCount == null) ? 1 : (refCount + 1);
		this.generationChunkRefCountMap.put(chunkPos, newRefCount);
		return newRefCount == 1;
	}
	
	/**
	 * Marks the given pos as no longer needed by one generation event. <br>
	 * Must be called from the server thread.
	 *
	 * @return true if this was the last reference, meaning the chunk can be released.
	 */
	private boolean releaseChunkRef(DhChunkPos chunkPos)
	{
		Integer refCount = this.generationChunkRefCountMap.get(chunkPos);
		if (refCount == null)
		{
			// could happen during shutdown
			CHUNK_LOAD_LOGGER.debug("Chunk ["+chunkPos+"] was released without being acquired.");
			return false;
		}
		
		if (refCount > 1)
		{
			this.generationChunkRefCountMap.put(chunkPos, refCount - 1);
			return false;
		}
		
		this.generationChunkRefCountMap.remove(chunkPos);
		return true;
	}
	
	/** @return true if no generation event currently needs the given pos loaded. */
	private boolean chunkRefCountIsZero(DhChunkPos chunkPos) { return !this.generationChunkRefCountMap.containsKey(chunkPos); }

	#endif
	
	
	
	//============//
	// generation //
	//============//
	
	public void generateChunksViaInternalServer(GenerationEvent genEvent)
	{
		synchronized (this.lifecycleLock) {
			this.requireRunning(genEvent);
			this.activeGenerations++;
		}
		Throwable originalFailure = null;
		try { this.generateChunksWhileOpen(genEvent); }
		catch (RuntimeException | Error failure) { originalFailure = failure; throw failure; }
		finally {
			boolean finish;
			synchronized (this.lifecycleLock) {
				this.activeGenerations--;
				finish = this.closed && this.activeGenerations == 0;
			}
			if (finish) {
				try { this.finishClosing(); }
				catch (RuntimeException | Error cleanupFailure) {
					if (originalFailure == null) throw cleanupFailure;
					if (cleanupFailure != originalFailure) originalFailure.addSuppressed(cleanupFailure);
				}
			}
		}
	}

	private void generateChunksWhileOpen(GenerationEvent genEvent)
	{
		this.runValidation();
		#if MC_VER <= MC_1_12_2
		ArrayList<GenerationChunkLease<DhChunkPos>> requestedChunkLeases = new ArrayList<>();
		#endif
		Throwable generationFailure = null;
		
		try
		{
			//=====================//
			// create gen requests //
			//=====================//
			
			#if MC_VER <= MC_1_12_2
			ArrayList<CompletableFuture<Chunk>> getChunkFutureList = new ArrayList<>();
			#else
			ArrayList<CompletableFuture<ChunkAccess>> getChunkFutureList = new ArrayList<>();
			#endif
			
			{
				Iterator<ChunkPos> chunkPosIterator = ChunkPosGenStream.getIterator(genEvent.minPos.getX(), genEvent.minPos.getZ(), genEvent.widthInChunks, 0);
				while (chunkPosIterator.hasNext())
				{
					this.requireRunning(genEvent);
					ChunkPos chunkPos = chunkPosIterator.next();
					
					#if MC_VER <= MC_1_12_2
					CompletableFuture<Chunk> requestChunkFuture;
					#else
					CompletableFuture<ChunkAccess> requestChunkFuture;
					#endif
					
					#if MC_VER <= MC_1_12_2
					GenerationChunkLease<DhChunkPos> lease = new GenerationChunkLease<>();
					// Publish ownership before scheduling, including submission failures.
					requestedChunkLeases.add(lease);
					requestChunkFuture = this.requestChunkFromServerAsync(chunkPos, lease, genEvent)
					#else
					requestChunkFuture = this.requestChunkFromServerAsync(chunkPos)
					#endif
							// log errors if necessary
							.whenCompleteAsync(
								(chunk, throwable) ->
								{
									// unwrap the CompletionException if necessary
									Throwable actualThrowable = throwable;
									while (actualThrowable instanceof CompletionException)
									{
										actualThrowable = actualThrowable.getCause();
									}
									
									if (actualThrowable != null)
									{
										// some exceptions don't have a message
										String throwableMessage = (actualThrowable.getMessage() != null)
											? actualThrowable.getMessage()
											: actualThrowable.getClass().getSimpleName();

										// ignore expected shutdown exceptions
										boolean isShutdownException =
											ExceptionUtil.isShutdownException(actualThrowable)
											|| throwableMessage.contains("Unloaded chunk");
										if (!isShutdownException)
										{
											CHUNK_LOAD_LOGGER.warn("DistantHorizons: Couldn't load chunk [" + chunkPos + "] from server, error: [" + throwableMessage + "].", actualThrowable);
										}
									}
								});
					
					getChunkFutureList.add(requestChunkFuture);
				}
			}
			
			
			
			//==============================//
			// wait for generation requests //
			//==============================//
			
			// Join-ing each thread will prevent DH from working on anything else
			// but will also prevent over-queuing world gen tasks.
			// If C2ME is present the CPU will still be well utilized.
			
			ArrayList<IChunkWrapper> chunkWrappers = new ArrayList<>();
			for (int i = 0; i < getChunkFutureList.size(); i++)
			{
				#if MC_VER <= MC_1_12_2
				CompletableFuture<Chunk> getChunkFuture;
				Chunk chunk;
				#else
				CompletableFuture<ChunkAccess> getChunkFuture;
				ChunkAccess chunk;
				#endif
				
				getChunkFuture = getChunkFutureList.get(i);
				this.requireRunning(genEvent);
				chunk = getChunkFuture.join();
				if (chunk != null)
				{
					ChunkWrapper chunkWrapper = new ChunkWrapper(chunk, this.dhServerLevel.getLevelWrapper());
					chunkWrapper.createDhHeightMaps();
					chunkWrappers.add(chunkWrapper);
				}
			}
			
			
			
			//==========================//
			// process generated chunks //
			//==========================//
			
			int maxSkyLight = this.dhServerLevel.getServerLevelWrapper().hasSkyLight() ? LodUtil.MAX_MC_LIGHT : LodUtil.MIN_MC_LIGHT;
			for (int i = 0; i < chunkWrappers.size(); i++)
			{
				this.requireRunning(genEvent);
				ChunkWrapper chunkWrapper = (ChunkWrapper)chunkWrappers.get(i);
				
				// pre-generated chunks should have lighting but new ones won't
				if (!chunkWrapper.isDhBlockLightingCorrect())
				{
					DhLightingEngine.INSTANCE.bakeChunkBlockLighting(chunkWrapper, chunkWrappers, maxSkyLight);
				}
				
				this.dhServerLevel.updateBeaconBeamsForChunk(chunkWrapper, chunkWrappers);
				this.requireRunning(genEvent);
				genEvent.resultConsumer.accept(chunkWrapper);
			}
		}
		catch (RuntimeException | Error failure)
		{
			generationFailure = failure;
			throw failure;
		}
		finally
		{
			ArrayList<CompletableFuture<Void>> releaseFutures = new ArrayList<>();

			#if MC_VER <= MC_1_12_2
			// Release only references actually acquired by each submitted request.
			// Essential cleanup can overtake deferred acquisition: closing the lease
			// also prevents that pending request from acquiring chunks afterwards.
			for (GenerationChunkLease<DhChunkPos> lease : requestedChunkLeases)
			{
				releaseFutures.add(this.releaseChunkFromServerAsync(this.params.mcServerLevel, lease));
			}
			#else
			Iterator<ChunkPos> chunkPosIterator = ChunkPosGenStream.getIterator(genEvent.minPos.getX(), genEvent.minPos.getZ(), genEvent.widthInChunks, 0);
			while (chunkPosIterator.hasNext())
			{
				ChunkPos chunkPos = chunkPosIterator.next();
				releaseFutures.add(this.releaseChunkFromServerAsync(this.params.mcServerLevel, chunkPos));
			}
			#endif

			// Wait for every cleanup even if an earlier one fails, otherwise a
			// generation failure could hide incomplete cleanup of other requests.
			RuntimeException cleanupFailure = null;
			for (CompletableFuture<Void> releaseFuture : releaseFutures)
			{
				try
				{
					this.joinServerCleanupFuture(releaseFuture);
				}
				catch (RuntimeException failure)
				{
					if (cleanupFailure == null) cleanupFailure = failure;
					else if (cleanupFailure != failure) cleanupFailure.addSuppressed(failure);
				}
			}
			
			// The provider drains queued unloads in its normal server tick.
			// Do not repeatedly pump the shared queue inside one essential task.
			if (cleanupFailure != null)
			{
				if (generationFailure == null) throw cleanupFailure;
				if (generationFailure != cleanupFailure) generationFailure.addSuppressed(cleanupFailure);
			}
		}
	}
	private void joinServerCleanupFuture(CompletableFuture<Void> future)
	{
		try
		{
			future.join();
		}
		catch (RuntimeException e)
		{
			if (!ExceptionUtil.isShutdownException(e))
			{
				throw e;
			}
		}
	}
	private void runValidation()
	{
		// DH thread check
		if (!DhApi.isDhThread()
			&& ModInfo.IS_DEV_BUILD)
		{
			throw new IllegalStateException("Internal server generation should be called from one of DH's world gen thread. Current thread: ["+Thread.currentThread().getName()+"]");
		}
		
		#if MC_VER > MC_1_12_2
		// C2ME present?
		if (C2ME_ACCESSOR == null
			&& !c2meMissingWarningLogged)
		{
			c2meMissingWarningLogged = true;
			
			String c2meWarning = "C2ME missing, \n" +
				"low CPU usage and slow world gen speeds expected. \n" +
				"DH is set to use MC's internal server for world gen \n" +
				"this mode is less efficient unless a mod like C2ME is present."
				;
			
			if (Config.Common.Logging.Warning.showSlowWorldGenSettingWarnings.get())
			{
				String message =
					MinecraftTextFormat.ORANGE + "Distant Horizons: slow world gen." + MinecraftTextFormat.CLEAR_FORMATTING + "\n" +
						c2meWarning;
				ClientApi.INSTANCE.queueChatMessage(message);
			}
			
			LOGGER.warn(c2meWarning);
		}
		#endif
	}

	private void scheduleRemovePosToIgnore(DhChunkPos chunkPos)
	{
		synchronized (this.lifecycleLock)
		{
			if (this.closed) {
				this.removeOwnedIgnore(chunkPos);
				return;
			}
			this.chunkSaveIgnoreTimer.schedule(new TimerTask()
			{
				@Override
				public void run()
				{
					#if MC_VER <= MC_1_12_2
					ServerThreadTaskHandler.INSTANCE.queueEssentialTask(InternalServerGenerator.this.serverSessionId, () ->
					{
						if (InternalServerGenerator.this.chunkRefCountIsZero(chunkPos))
							InternalServerGenerator.this.removeOwnedIgnore(chunkPos);
						return null;
					});
					#else
					InternalServerGenerator.this.removeOwnedIgnore(chunkPos);
					#endif
				}
			}, MS_TO_IGNORE_CHUNK_AFTER_COMPLETION);
		}
	}

	#if MC_VER <= MC_1_12_2
	private CompletableFuture<Chunk> requestChunkFromServerAsync(ChunkPos chunkPos, GenerationChunkLease<DhChunkPos> lease, GenerationEvent genEvent)
	#else
	private CompletableFuture<ChunkAccess> requestChunkFromServerAsync(ChunkPos chunkPos)
	#endif
	{
		#if MC_VER <= MC_1_12_2
		{
			WorldServer level = this.params.mcServerLevel;
			
			return ServerThreadTaskHandler.INSTANCE.queueTask(this.serverSessionId, () ->
			{
				this.requireRunning(genEvent);
				lease.requireOpen();
				try
				{
					ChunkProviderServer provider = (ChunkProviderServer) level.getChunkProvider();
					// A neighbor load can populate the target before the loop reaches
					// its center. Own its ignore marker before any neighbor is loaded.
					DhChunkPos targetDhPos = McObjectConverter.convert(chunkPos);
					boolean firstTargetReference = this.acquireChunkRef(targetDhPos);
					lease.recordAcquired(targetDhPos);
					if (firstTargetReference && this.updateManager != null)
					{
						this.markChunkIgnored(targetDhPos);
					}

					// load neighbors first so the target chunk can fully populate.
					// adjacent generation events share these border chunks, so a reference count
					// is used to only load each chunk once and to track who still needs it.
					for (int dx = -1; dx <= 1; dx++)
					{
						for (int dz = -1; dz <= 1; dz++)
						{
							int neighborPosX = chunkPos.x + dx;
							int neighborPosZ = chunkPos.z + dz;
						
							DhChunkPos neighborDhPos = new DhChunkPos(neighborPosX, neighborPosZ);
							boolean targetPosition = dx == 0 && dz == 0;
							boolean firstReference = targetPosition ? firstTargetReference : this.acquireChunkRef(neighborDhPos);
							if (!targetPosition)
							{
								lease.recordAcquired(neighborDhPos);
							}
							if (!firstReference)
							{
								// another generation event already has this chunk loaded
								continue;
							}
						
							if (!targetPosition && this.updateManager != null)
							{
								this.markChunkIgnored(neighborDhPos);
							}

							#if MC_VER <= MC_1_7_10
							if (HODGE_PODGE_ACCESSOR != null)
							{
								this.simulationOwnership.preventIfEnabled(neighborDhPos,
									HODGE_PODGE_ACCESSOR::isSimulationSuppressionEnabled,
									() -> HODGE_PODGE_ACCESSOR.preventChunkSimulation(level, neighborPosX, neighborPosZ));
							}
							#endif
						
							this.chunkTickets.acquire(neighborDhPos);

							// the target chunk itself is loaded below, at the requested generation step
							if (dx == 0 && dz == 0)
							{
								continue;
							}

							if (!provider.chunkExists(neighborPosX, neighborPosZ))
							{
								#if MC_VER <= MC_1_7_10
								provider.loadChunk(neighborPosX, neighborPosZ);
								#else
								provider.provideChunk(neighborPosX, neighborPosZ);
								#endif
							}
						}
					}
				
					// load the target chunk itself, at the requested generation step
					#if MC_VER <= MC_1_7_10
					return provider.loadChunk(chunkPos.x, chunkPos.z);
					#else
					return provider.provideChunk(chunkPos.x, chunkPos.z);
					#endif
				}
				catch (RuntimeException | Error acquisitionFailure)
				{
					// Roll back before another server task can mistake a partial
					// acquisition for a fully retained, loaded neighborhood.
					try
					{
						this.releaseChunkLeaseOnServerThread(level, lease);
					}
					catch (RuntimeException | Error cleanupFailure)
					{
						if (cleanupFailure != acquisitionFailure)
						{
							acquisitionFailure.addSuppressed(cleanupFailure);
						}
					}
					throw acquisitionFailure;
				}
			});
		}
		#else
		{
			return CompletableFuture.supplyAsync(() ->
			{
				ServerLevel level = this.params.mcServerLevel;
				
				// ignore chunk update events for this position
				if (this.updateManager != null)
				{
					this.updateManager.addPosToIgnore(McObjectConverter.convert(chunkPos));
				}
				
				#if MC_VER < MC_1_21_5
				int chunkLevel = 33; // 33 is equivalent to FULL Chunk
				level.getChunkSource().distanceManager.addTicket(DH_SERVER_GEN_TICKET, chunkPos, chunkLevel, chunkPos);
				#else
				level.getChunkSource().addTicketWithRadius(DH_SERVER_GEN_TICKET, chunkPos, 0);
				#endif
				
				// probably not the most optimal to run updates here, but fast enough
				level.getChunkSource().distanceManager.runAllUpdates(level.getChunkSource().chunkMap);
				
				ChunkHolder chunkHolder = level.getChunkSource().chunkMap
					.getUpdatingChunkIfPresent(
						#if MC_VER <= MC_1_21_11 chunkPos.toLong() #else chunkPos.pack() #endif
					);
				if (chunkHolder == null)
				{
					throw new IllegalStateException("No chunk chunkHolder for pos ["+chunkPos+"] after ticket has been added.");
				}
				
				// Note: ChunkStatus.FEATURES would be slightly faster than FULL, but can cause issues
				// with other mods where they need lighting/full chunk data.
				#if MC_VER <= MC_1_20_4
				return chunkHolder.getOrScheduleFuture(ChunkStatus.FULL, level.getChunkSource().chunkMap)
						.thenApply(result -> result.left().orElseThrow(() -> new RuntimeException(result.right().get().toString()))); // can throw if the server is shutting down
				#elif MC_VER <= MC_1_20_6
				return chunkHolder.getOrScheduleFuture(ChunkStatus.FULL, level.getChunkSource().chunkMap)
						.thenApply(result -> result.orElseThrow(() -> new RuntimeException(result.toString()))); // can throw if the server is shutting down
				#else
				return chunkHolder.scheduleChunkGenerationTask(ChunkStatus.FULL, level.getChunkSource().chunkMap)
						.thenApply(result -> result.orElseThrow(() -> new RuntimeException(result.getError()))); // can throw if the server is shutting down
				#endif
				
			}, this.params.mcServerLevel.getChunkSource().chunkMap.mainThreadExecutor)
			.thenCompose(Function.identity());
		}
		#endif
	}
	/**
	 * mitigates out of memory issues in the vanilla chunk system. <br>
	 * See: https://github.com/pop4959/Chunky/pull/383
	 */
	#if MC_VER <= MC_1_12_2
	private CompletableFuture<Void> releaseChunkFromServerAsync(WorldServer level, GenerationChunkLease<DhChunkPos> lease)
	#else
	private CompletableFuture<Void> releaseChunkFromServerAsync(ServerLevel level, ChunkPos chunkPos)
	#endif
	{
		#if MC_VER <= MC_1_12_2
		return ServerThreadTaskHandler.INSTANCE.queueEssentialTask(this.serverSessionId, () ->
		{
			this.releaseChunkLeaseOnServerThread(level, lease);
			return null;
		});
		#else
		CompletableFuture<Void> removeTicketFuture = new CompletableFuture<>();
		level.getChunkSource().chunkMap.mainThreadExecutor.execute(() ->
		{
			try
			{
				#if MC_VER < MC_1_21_5
				int chunkLevel = 33; // 33 is equivalent to FULL Chunk
				level.getChunkSource().distanceManager.removeTicket(DH_SERVER_GEN_TICKET, chunkPos, chunkLevel, chunkPos);
				#else
				level.getChunkSource().removeTicketWithRadius(DH_SERVER_GEN_TICKET, chunkPos, 0);
				#endif

				level.getChunkSource().chunkMap.tick(() -> false);

				#if MC_VER > MC_1_16_5
				level.entityManager.tick();
				#endif


				// give MC a few seconds to save the chunk before
				// we can process update events there again
				// (versions <= 1.12.2 do this per released pos instead)
				this.chunkSaveIgnoreTimer.schedule(new TimerTask()
				{
					@Override
					public void run()
					{
						if (InternalServerGenerator.this.updateManager != null)
						{
							InternalServerGenerator.this.updateManager.removePosToIgnore(McObjectConverter.convert(chunkPos));
						}
					}
				}, MS_TO_IGNORE_CHUNK_AFTER_COMPLETION);

			}
			catch (Exception e)
			{
				LOGGER.warn("Failed to release chunk ["+chunkPos+"] back to internal server. Error: ["+e.getMessage()+"]", e);
			}
			finally
			{
				removeTicketFuture.complete(null);
			}
		});
		return removeTicketFuture;
		#endif
	}
	#if MC_VER <= MC_1_12_2
	/** Invoked only from acquisition/essential tasks on the server thread. */
	private void releaseChunkLeaseOnServerThread(WorldServer level, GenerationChunkLease<DhChunkPos> lease)
	{
		lease.close(neighborDhPos ->
		{
			if (!this.releaseChunkRef(neighborDhPos))
			{
				return; // Another request still owns a reference to this position.
			}
			int neighborPosX = neighborDhPos.getX();
			int neighborPosZ = neighborDhPos.getZ();
			GenerationChunkLease.runCleanupSteps(
				() ->
				{
					this.chunkTickets.releasePosition(neighborDhPos);
				},
				() ->
				{
					#if MC_VER <= MC_1_7_10
					if (HODGE_PODGE_ACCESSOR != null)
					{
						this.simulationOwnership.allowIfOwned(neighborDhPos,
							() -> HODGE_PODGE_ACCESSOR.allowChunkSimulation(level, neighborPosX, neighborPosZ));
					}
					#endif
				},
				() ->
				{
					ChunkProviderServer provider = (ChunkProviderServer) level.getChunkProvider();
					#if MC_VER <= MC_1_7_10
					// Preserve vanilla/player ownership and the provider's unload path.
					if (!level.getPlayerManager().func_152621_a(neighborPosX, neighborPosZ))
					{
						provider.dropChunk(neighborPosX, neighborPosZ);
					}
					#else
					Chunk chunk = provider.getLoadedChunk(neighborPosX, neighborPosZ);
					if (chunk != null)
					{
						provider.queueUnload(chunk);
					}
					#endif
				},
				() -> this.scheduleRemovePosToIgnore(neighborDhPos));
		});
	}
	#endif
	
}
