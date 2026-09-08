/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding;

import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java representation of one or more OpenGL buffers for rendering.
 *
 * @see ColumnRenderBufferBuilder
 */
public class LodBufferContainer implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IWrapperFactory WRAPPER_FACTORY = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
	private static final AbstractDhRenderApiDefinition RENDER_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	public static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("LodBufferContainer");
	
	
	/** the position closest to minimum X/Z infinity and the level's lowest Y */
	public final DhBlockPos minCornerBlockPos;
	public final long pos;
	
	public boolean buffersUploaded = false;
	private final AtomicBoolean closed = new AtomicBoolean();
	
	public IVertexBufferWrapper[] vboOpaqueWrappers;
	public IVertexBufferWrapper[] vboTransparentWrappers;
	
	public ILodContainerUniformBufferWrapper uniformContainer = WRAPPER_FACTORY.createLodContainerUniformWrapper();
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	private LodBufferContainer(long pos, DhBlockPos minCornerBlockPos)
	{
		this.pos = pos;
		this.minCornerBlockPos = minCornerBlockPos;
		this.vboOpaqueWrappers = new IVertexBufferWrapper[0];
		this.vboTransparentWrappers = new IVertexBufferWrapper[0];
	}
	
	//endregion
	
	
	
	//==================//
	// buffer uploading //
	//==================//
	//region
	
	/** Should be run on a DH thread. */
	public static CompletableFuture<LodBufferContainer> tryMakeAndUploadBuffersAsync(
		long pos, IClientLevelWrapper clientLevelWrapper,
		ArrayList<ByteBuffer> opaqueBuffers,
		ArrayList<ByteBuffer> transparentBuffers
	)
	{
		// new upload needed
		CompletableFuture<LodBufferContainer> future = new CompletableFuture<>();
		
		
		
		//================//
		// create buffers //
		//================//
		//region
		
		DhBlockPos minCornerBlockPos = new DhBlockPos(
			DhSectionPos.getMinCornerBlockX(pos),
			clientLevelWrapper.getMinHeight(),
			DhSectionPos.getMinCornerBlockZ(pos));
		LodBufferContainer bufferContainer = new LodBufferContainer(pos, minCornerBlockPos);
		
		// update arrays to contain buffers
		bufferContainer.vboOpaqueWrappers = resizeWrapperArray(bufferContainer.vboOpaqueWrappers, opaqueBuffers.size());
		bufferContainer.vboTransparentWrappers = resizeWrapperArray(bufferContainer.vboTransparentWrappers, transparentBuffers.size());
		
		final PhantomArrayListCheckout opaqueIndexCheckout;
		try { opaqueIndexCheckout = ARRAY_LIST_POOL.checkoutByteBuffers(opaqueBuffers.size()); }
		catch (RuntimeException | Error failure)
		{
			bufferContainer.close();
			throw failure;
		}
		final PhantomArrayListCheckout transparentIndexCheckout;
		try { transparentIndexCheckout = ARRAY_LIST_POOL.checkoutByteBuffers(transparentBuffers.size()); }
		catch (RuntimeException | Error failure)
		{
			try { opaqueIndexCheckout.close(); }
			finally { bufferContainer.close(); }
			throw failure;
		}
		
		// create CPU index buffers if needed.
		// Mac requires separate IBO objects for each VBO when using OpenGL,
		// all other OS's can share a single IBO for quicker loading times
		@Nullable final ArrayList<ByteBuffer> opaqueIndexBuffers;
		@Nullable final ArrayList<ByteBuffer> transparentIndexBuffers;
		try
		{
			boolean useSingleIbo = RENDER_DEF.useSingleIbo();
			opaqueIndexBuffers = useSingleIbo ? null : bufferContainer.createIndexBuffers(opaqueIndexCheckout, opaqueBuffers);
			transparentIndexBuffers = useSingleIbo ? null : bufferContainer.createIndexBuffers(transparentIndexCheckout, transparentBuffers);
		}
		catch (RuntimeException | Error failure)
		{
			try { closeIndexCheckouts(opaqueIndexCheckout, transparentIndexCheckout); }
			finally { bufferContainer.close(); }
			throw failure;
		}
		
		//endregion
		
		
		
		//=============//
		// create VBOs //
		//=============//
		//region	
		
		CompletableFuture<Void> createFuture = new CompletableFuture<Void>();
		queueUploadWork("LodBufferContainer Setup", createFuture, () ->
		{
			try
			{
				// skip this event if requested
				if (Thread.interrupted()
					|| future.isCancelled())
				{
					throw new InterruptedException();
				}
				
				createBufferWrappers(bufferContainer.vboOpaqueWrappers, opaqueBuffers);
				createBufferWrappers(bufferContainer.vboTransparentWrappers, transparentBuffers);
				
				createFuture.complete(null);
			}
			catch (Exception | LinkageError e)
			{
				if (!ExceptionUtil.isShutdownException(e))
				{
					LOGGER.error("Unexpected issue creating buffers for pos: ["+DhSectionPos.toString(bufferContainer.pos)+"], error: ["+e.getMessage()+"].", e);
				}

				createFuture.completeExceptionally(e);
			}
		});
		
		//endregion
		
		
		
		//====================//
		// upload VBOs to GPU //
		//====================//
		//region
		
		createFuture.exceptionally((Throwable e) ->
		{
			try
			{
				// No upload consumers were started on the failed-setup path.
				if (!ExceptionUtil.isShutdownException(e))
				{
					LOGGER.error("Unexpected issue creating buffer [" + bufferContainer.minCornerBlockPos + "], error: [" + e.getMessage() + "].", e);
				}
				try { bufferContainer.close(); }
				finally { future.completeExceptionally(e); }
			}
			finally { closeIndexCheckouts(opaqueIndexCheckout, transparentIndexCheckout); }
			return null;
		});
		createFuture.thenRun(() ->
		{
			CompletableFuture<Void> opaqueFuture = uploadBuffersAsync(future, bufferContainer.vboOpaqueWrappers, opaqueBuffers, opaqueIndexBuffers);
			CompletableFuture<Void> transparentFuture = uploadBuffersAsync(future, bufferContainer.vboTransparentWrappers, transparentBuffers, transparentIndexBuffers);
			CompletableFuture<Void> uploadFuture = CompletableFuture.allOf(opaqueFuture, transparentFuture);
			uploadFuture.whenComplete((unused, e) ->
			{
				try
				{
					if (e != null)
					{
						if (!ExceptionUtil.isShutdownException(e))
						{
							LOGGER.error("Unexpected issue uploading buffer [" + bufferContainer.minCornerBlockPos + "], error: [" + e.getMessage() + "].", e);
						}
						try { bufferContainer.close(); }
						finally { future.completeExceptionally(e); }
					}
					else
					{
						bufferContainer.buffersUploaded = true;
						if (!future.complete(bufferContainer)) { bufferContainer.close(); }
					}
				}
				finally
				{
					// Observe the private all-consumers future, not the externally cancellable
					// result. Cancelling that result must not return memory under queued uploads.
					closeIndexCheckouts(opaqueIndexCheckout, transparentIndexCheckout);
				}
			});
		});
		
		//endregion
		
		
		
		return future;
	}
	private static void closeIndexCheckouts(PhantomArrayListCheckout opaque, PhantomArrayListCheckout transparent)
	{
		try { opaque.close(); }
		finally { transparent.close(); }
	}
	private static void queueUploadWork(String name, CompletableFuture<Void> completion, Runnable work)
	{
		try { RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread(name, work); }
		catch (RuntimeException | LinkageError failure) { completion.completeExceptionally(failure); }
	}
	
	
	private ArrayList<ByteBuffer> createIndexBuffers(PhantomArrayListCheckout checkout, ArrayList<ByteBuffer> vertexBuffers)
	{
		ArrayList<ByteBuffer> indexBuffers = new ArrayList<>();
		
		for (int i = 0; i < vertexBuffers.size(); i++)
		{
			ByteBuffer vertexBuffer = vertexBuffers.get(i);
			int size = vertexBuffer.limit() - vertexBuffer.position();
			int maxVertexCount = size / LodQuadBuilder.BYTES_PER_VERTEX;
			int quadCount = (maxVertexCount / 4);
			
			ByteBuffer indexBuffer = IndexBufferBuilder.populateBuffer(checkout, i, quadCount);
			indexBuffers.add(indexBuffer);
		}
		
		return indexBuffers;
	}
	
	private static IVertexBufferWrapper[] resizeWrapperArray(IVertexBufferWrapper[] vbos, int newSize)
	{
		if (vbos.length == newSize)
		{
			return vbos;
		}
		
		IVertexBufferWrapper[] newVbos = new IVertexBufferWrapper[newSize];
		System.arraycopy(vbos, 0, newVbos, 0, Math.min(vbos.length, newSize));
		if (newSize < vbos.length)
		{
			for (int i = newSize; i < vbos.length; i++)
			{
				if (vbos[i] != null)
				{
					vbos[i].close();
				}
			}
		}
		return newVbos;
	}
	
	private static void createBufferWrappers(
		IVertexBufferWrapper[] vboWrappers, 
		ArrayList<ByteBuffer> vertexBuffers)
	{
		for (int i = 0; i < vertexBuffers.size(); i++)
		{
			if (i >= vboWrappers.length)
			{
				throw new RuntimeException("Too many vertex buffers!!");
			}
			
			if (vboWrappers[i] == null)
			{
				vboWrappers[i] = WRAPPER_FACTORY.createVboWrapper("distantHorizons:TerrainRenderer");
			}
		}
	}
	
	/** Index buffers should be null if {@link AbstractDhRenderApiDefinition#useSingleIbo()} returns true. */
	private static CompletableFuture<Void> uploadBuffersAsync(
		CompletableFuture<LodBufferContainer> parentFuture,
		IVertexBufferWrapper[] vboWrappers,
		ArrayList<ByteBuffer> vertexBuffers, @Nullable ArrayList<ByteBuffer> indexBuffers
		)
	{
		ArrayList<CompletableFuture<Void>> uploadFutureList = new ArrayList<>();
		int vboIndex = 0;
		try
		{
			for (int i = 0; i < vertexBuffers.size(); i++)
			{
				if (vboIndex >= vboWrappers.length)
				{
					throw new RuntimeException("Too many vertex buffers!!");
				}



				// final variables for use in lambdas //

				final IVertexBufferWrapper finalVboWrapper = vboWrappers[vboIndex];

				final ByteBuffer finalVertexBuffer = vertexBuffers.get(vboIndex);
				// index buffers are optional
				@Nullable final ByteBuffer finalIndexBuffer = (indexBuffers != null) ? indexBuffers.get(vboIndex) : null;

				final int finalVertexCount = vertexByteBufferToVertexCount(finalVertexBuffer);



				//===============//
				// vertex upload //
				//===============//
				//region

				CompletableFuture<Void> vertexUploadFuture = new CompletableFuture<>();
				uploadFutureList.add(vertexUploadFuture);


				queueUploadWork("LodBufferContainer VBO Upload", vertexUploadFuture, () ->
				{
					try
					{
						// skip this event if requested
						if (Thread.interrupted()
							|| parentFuture.isCancelled())
						{
							throw new InterruptedException();
						}

						finalVboWrapper.uploadVertexBuffer(finalVertexBuffer, finalVertexCount);
						vertexUploadFuture.complete(null);
					}
					catch (Exception | LinkageError e)
					{
						LOGGER.error("Failed to upload buffer. Error: [" + e.getMessage() + "].", e);
						vertexUploadFuture.completeExceptionally(e);
					}
				});

				//endregion



				//==============//
				// index upload //
				//==============//
				//region

				if (finalIndexBuffer != null)
				{
					CompletableFuture<Void> indexUploadFuture = new CompletableFuture<>();
					uploadFutureList.add(indexUploadFuture);

					queueUploadWork("LodBufferContainer IBO Upload", indexUploadFuture, () ->
					{
						try
						{
							// skip this event if requested
							if (Thread.interrupted()
								|| parentFuture.isCancelled())
							{
								throw new InterruptedException();
							}

							finalVboWrapper.uploadIndexBuffer(finalIndexBuffer, finalVertexCount);
							indexUploadFuture.complete(null);
						}
						catch (Exception | LinkageError e)
						{
							// The container owns this wrapper and closes it after every upload
							// consumer has finished; this task must not race/double-close it.
							indexUploadFuture.completeExceptionally(e);
						}
					});
				}
				//endregion



				vboIndex++;
			}

			if (vboIndex < vboWrappers.length)
			{
				throw new RuntimeException("Too few vertex buffers!!");
			}
		}
		catch (RuntimeException | LinkageError failure)
		{
			// Account for a synchronous failure after partial submission without finishing
			// before the already queued consumers. Each submitted future still owns its work.
			CompletableFuture<Void> failed = new CompletableFuture<>();
			failed.completeExceptionally(failure);
			uploadFutureList.add(failed);
		}



		// merge futures //

		CompletableFuture<?>[] futureArray = new CompletableFuture[uploadFutureList.size()];
		for (int i = 0; i < uploadFutureList.size(); i++)
		{
			futureArray[i] = uploadFutureList.get(i);
		}
		return CompletableFuture.allOf(futureArray);
	}
	
	//endregion
	
	
	
	//================//
	// helper methods //
	//================//
	//region
	
	private static int vertexByteBufferToVertexCount(ByteBuffer buffer)
	{
		int size = buffer.limit() - buffer.position();
		int vertexCount = size / LodQuadBuilder.BYTES_PER_VERTEX;
		return vertexCount;
	}
	
	/** can be used when debugging */
	public boolean hasNonNullVbos() { return this.vboOpaqueWrappers != null || this.vboTransparentWrappers != null; }
	
	/** can be used when debugging */
	public int vboBufferCount() 
	{
		int count = 0;
		
		if (this.vboOpaqueWrappers != null)
		{
			count += this.vboOpaqueWrappers.length;
		}
		
		if (this.vboTransparentWrappers != null)
		{
			count += this.vboTransparentWrappers.length;
		}
		
		return count;
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	/**
	 * This method is called when object is no longer in use.
	 * Called either after uploadBuffers() returned false (On buffer Upload
	 * thread), or by others when the object is not being used. (not in build,
	 * upload, or render state). 
	 */
	@Override
	public void close()
	{
		this.buffersUploaded = false;
		if (!this.closed.compareAndSet(false, true)) { return; }
		
		RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread("LodBufferContainer Close", () -> 
		{
			tryCloseBufferWrapperArray(this.vboOpaqueWrappers);
			tryCloseBufferWrapperArray(this.vboTransparentWrappers);
			
			this.uniformContainer.close();
		});
	}
	
	private static void tryCloseBufferWrapperArray(@Nullable IVertexBufferWrapper[] bufferWrappers)
	{
		if (bufferWrappers != null)
		{
			for (int i = 0; i < bufferWrappers.length; i++)
			{
				IVertexBufferWrapper buffer = bufferWrappers[i];
				bufferWrappers[i] = null;
				if (buffer != null)
				{
					buffer.close();
				}
			}
		}
	}
	
	//endregion
	
	
	
}
