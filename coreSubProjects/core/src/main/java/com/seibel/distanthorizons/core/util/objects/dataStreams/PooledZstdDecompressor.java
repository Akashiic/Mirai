package com.seibel.distanthorizons.core.util.objects.dataStreams;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdException;
import com.seibel.distanthorizons.core.network.NetworkDecodeLimits;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;

import java.util.concurrent.ConcurrentLinkedQueue;

public class PooledZstdDecompressor
{
	/** reuse the ZStd native context for a minor speed improvement */
	private static final ConcurrentLinkedQueue<ZstdDecompressCtx> ZSTD_CONTEXT_CACHE = new ConcurrentLinkedQueue<>();
	
	
	
	/**
	 * Replaces {@link Zstd#decompress} so we can use a pooled byte array
	 * which significantly reduces GC pressure.
	 */
	public static ByteArrayList decompressFrame(byte[] src, PhantomArrayListCheckout checkout) throws ZstdException
	{
		long compressedSize = Zstd.findFrameCompressedSize(src, 0);
		if (Zstd.isError(compressedSize) || compressedSize <= 0 || compressedSize > src.length)
		{
			throw new ZstdException(Zstd.errGeneric(), "Invalid compressed frame size");
		}
		long contentSize = Zstd.getFrameContentSize(src, 0, (int) compressedSize);
		if (contentSize < 0 || contentSize > NetworkDecodeLimits.MAX_DECOMPRESSED_BLOB_BYTES)
		{
			throw new ZstdException(Zstd.errGeneric(), "LOD frame exceeds decompression budget or has unknown size");
		}
		
		ByteArrayList destination = checkout.getByteArray(0, (int) contentSize);
		return decompress(src, (int) compressedSize, (int) contentSize, destination);
	}
	private static ByteArrayList decompress(byte[] src, int srcSize, int originalSize, ByteArrayList destination) throws ZstdException
	{
		if (originalSize < 0)
		{
			throw new ZstdException(Zstd.errGeneric(), "Original size should not be negative");
		}
		
		// reuse the ZStd native context for a minor speed improvement
		ZstdDecompressCtx ctx = ZSTD_CONTEXT_CACHE.poll();
		if (ctx == null)
		{
			ctx = new ZstdDecompressCtx();
		}
		
		try
		{
			int size = ctx.decompressByteArray(destination.elements(), 0, destination.size(), src, 0, srcSize);
			if (size != originalSize)
			{
				throw new ZstdException(Zstd.errGeneric(), "Decompressed LOD size differs from its frame header");
			}
			return destination;
		}
		finally
		{
			// Native contexts must be returned even if malformed input makes decompression throw.
			ZSTD_CONTEXT_CACHE.add(ctx);
		}
	}
	
}
