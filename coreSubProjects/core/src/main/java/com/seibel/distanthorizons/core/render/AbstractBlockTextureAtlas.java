package com.seibel.distanthorizons.core.render;

import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockTextureRegistry;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;

import java.nio.ByteBuffer;

public abstract class AbstractBlockTextureAtlas
{
	/**
	 * Must match the tile lookup in the Blaze LOD fragment shader. <br>
	 * 256 tiles * 16 pixels = a constant 4096 pixel wide texture,
	 * holding every possible tile id at 4096 pixels tall.
	 */
	public static final int TILES_PER_ROW = 256;
	
	/** how many tile rows the atlas starts with and grows by, 1MB of texture per step */
	protected static final int ALLOCATION_ROW_COUNT = 16;
	
	protected static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("BlockTextureAtlas");
	
	
	protected int allocatedTileCount = 0;
	protected int uploadedTileCount = 0;
	
	protected boolean atlasCreated = false;
	
	
	
	//==================//
	// abstract methods //
	//==================//
	//region
	
	protected abstract void tryCreateOrResize(int width, int height);
	
	protected abstract void writeToTexture(
		ByteBuffer pixelBuffer,
		int destinationX, int destinationY,
		int tileWidth, int tileHeight);
	
	
	
	// optional //
	
	/** can be implemented for pre-upload setup */
	protected abstract void beforeWriteToTexture();
	/** Called only after every pending tile was written successfully. */
	protected abstract void afterWriteToTexture();
	/** Restore upload state on both success and failure, including partial setup. */
	protected void abortWriteToTexture() { }

	private interface UploadCleanup extends AutoCloseable
	{
		@Override void close();
	}
	
	//endregion
	
	
	
	//===========//
	// uploading //
	//===========//
	//region
	
	/**
	 * Uploads any newly registered tiles to the GPU,
	 * must be called on the render thread outside an active render pass.
	 */
	public void uploadPendingTiles()
	{
		// make sure we have an atlas of at least 1 item 
		// so we can bind the texture during rendering
		if (!this.atlasCreated)
		{
			this.growAtlas(1);
			this.atlasCreated = true;
		}
		
		
		
		int totalTileCount = BlockTextureRegistry.INSTANCE.getTileCount();
		if (totalTileCount == this.uploadedTileCount)
		{
			// no new tiles need uploading
			return;
		}
		
		if (totalTileCount > this.allocatedTileCount)
		{
			// the atlas isn't big enough to store these new tiles
			this.growAtlas(totalTileCount);
		}
		
		BlockTextureRegistry.PendingTiles pendingTiles = BlockTextureRegistry.INSTANCE.getAndClearPendingUploadTiles();
		if (pendingTiles == null)
		{
			// no new tiles need to be uploaded
			// this shouldn't happen, but just in case
			return;
		}
		
		try (PhantomArrayListCheckout checkout = ARRAY_LIST_POOL.checkoutByteBuffers(1);
			 UploadCleanup cleanup = this::abortWriteToTexture)
		{
			this.beforeWriteToTexture();
			
			ByteBuffer pixelBuffer = checkout.getByteBuffer(0, BlockTextureRegistry.TILE_BYTE_COUNT);
			for (int i = 0; i < pendingTiles.tilePixels.length; i++)
			{
				pixelBuffer.clear();
				pixelBuffer.put(pendingTiles.tilePixels[i]);
				pixelBuffer.flip();
				
				int tileId = pendingTiles.firstTileId + i;
				int destinationX = (tileId % TILES_PER_ROW) * BlockTextureRegistry.TILE_HEIGHT_AND_WIDTH;
				int destinationY = (tileId / TILES_PER_ROW) * BlockTextureRegistry.TILE_HEIGHT_AND_WIDTH;
				
				this.writeToTexture(
					pixelBuffer,
					destinationX,
					destinationY,
					BlockTextureRegistry.TILE_HEIGHT_AND_WIDTH, // width
					BlockTextureRegistry.TILE_HEIGHT_AND_WIDTH  // height
				);
			}
			
			this.afterWriteToTexture();
		}
		catch (RuntimeException | Error failure)
		{
			// The registry handed ownership of this batch to us. A failed upload must
			// not silently lose it; the next explicit retry uploads the complete atlas.
			this.uploadedTileCount = 0;
			try { BlockTextureRegistry.INSTANCE.resetPendingUploads(); }
			catch (RuntimeException | Error cleanupFailure)
			{
				if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
		this.uploadedTileCount = pendingTiles.firstTileId + pendingTiles.tilePixels.length;
	}
	
	private void growAtlas(int minTileCount)
	{
		int newRowCount = Math.max(this.allocatedTileCount / TILES_PER_ROW, ALLOCATION_ROW_COUNT);
		while (newRowCount * TILES_PER_ROW < minTileCount)
		{
			newRowCount *= 2;
		}
		
		int width = TILES_PER_ROW * BlockTextureRegistry.TILE_HEIGHT_AND_WIDTH;
		int height = newRowCount * BlockTextureRegistry.TILE_HEIGHT_AND_WIDTH;
		this.tryCreateOrResize(width, height);
		
		this.allocatedTileCount = newRowCount * TILES_PER_ROW;
		// all tiles need to be re-uploaded into the new texture
		this.uploadedTileCount = 0;
		BlockTextureRegistry.INSTANCE.resetPendingUploads();
	}
	
	//endregion
	
	
	
}
