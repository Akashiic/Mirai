package com.seibel.distanthorizons.core.network;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;

/** Hard allocation ceilings; the serialized wire and database schemas are unchanged. */
public final class NetworkDecodeLimits
{
	public static final int MAX_DTO_BYTES = 32 * 1024 * 1024;
	public static final int MAX_DECOMPRESSED_BLOB_BYTES = 64 * 1024 * 1024;
	public static final int MAX_COLUMN_DATA_POINTS = 4096;
	public static final int MAX_SECTION_DATA_POINTS = 4 * 1024 * 1024;
	public static final int MAX_MAPPING_ENTRIES = 1024 * 1024;

	private NetworkDecodeLimits() { }

	/** Validate actual input before allowing fastutil to grow its backing array. */
	public static void readByteArray(ByteBuf in, ByteArrayList destination)
	{
		int length = in.readInt();
		if (length < 0 || length > MAX_DTO_BYTES || length > in.readableBytes())
		{
			throw new IllegalArgumentException("Invalid LOD byte array length: " + length);
		}
		destination.size(length);
		in.readBytes(destination.elements(), 0, length);
	}
}
