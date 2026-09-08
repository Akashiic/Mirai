package tests;

import com.seibel.distanthorizons.core.network.NetworkDecodeLimits;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSplitMessage;
import com.seibel.distanthorizons.core.multiplayer.fullData.FullDataPayload;
import com.seibel.distanthorizons.core.util.objects.dataStreams.BoundedDecodeInputStream;
import com.seibel.distanthorizons.core.util.objects.dataStreams.PooledZstdDecompressor;
import com.github.luben.zstd.ZstdException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import org.junit.Assert;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

public class NetworkDecodeBoundsTest
{
	@Test public void fabricatedLengthsNeverGrowDestination()
	{
		for (int length : new int[] { -1, 1, Integer.MAX_VALUE, NetworkDecodeLimits.MAX_DTO_BYTES + 1 })
		{
			ByteBuf wire = Unpooled.buffer().writeInt(length);
			ByteArrayList destination = new ByteArrayList(0);
			try
			{
				Assert.assertThrows(IllegalArgumentException.class, () -> NetworkDecodeLimits.readByteArray(wire, destination));
				Assert.assertEquals(0, destination.elements().length);
			}
			finally { wire.release(); }
		}
	}

	@Test public void byteArrayRoundTripsExactly()
	{
		ByteBuf wire = Unpooled.buffer().writeInt(3).writeBytes(new byte[] { 7, 8, 9 });
		try
		{
			ByteArrayList destination = new ByteArrayList(0);
			NetworkDecodeLimits.readByteArray(wire, destination);
			Assert.assertArrayEquals(new byte[] { 7, 8, 9 }, destination.toByteArray());
			Assert.assertFalse(wire.isReadable());
		}
		finally { wire.release(); }
	}

	@Test public void malformedFragmentsAllocateNoCopiedBuffer()
	{
		for (int length : new int[] { -1, 0, 10, Integer.MAX_VALUE })
		{
			ByteBuf wire = Unpooled.buffer().writeInt(1).writeInt(length).writeBoolean(true);
			try
			{
				FullDataSplitMessage message = new FullDataSplitMessage();
				Assert.assertThrows(IllegalArgumentException.class, () -> message.decode(wire));
				Assert.assertNull(message.buffer);
			}
			finally { wire.release(); }
		}
	}

	@Test public void beaconsRequireTheirActualEncodedBytes()
	{
		ByteBuf wire = Unpooled.buffer().writeInt(1).writeInt(Integer.MAX_VALUE);
		try { Assert.assertThrows(IllegalArgumentException.class, () -> new FullDataPayload().decode(wire)); }
		finally { wire.release(); }
	}

	@Test public void primitiveReadsCannotBypassDecompressionBudget() throws Exception
	{
		try (DataInputStream stream = new DataInputStream(new BoundedDecodeInputStream(
			new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5 }), 4)))
		{
			Assert.assertEquals(0x01020304, stream.readInt());
			Assert.assertThrows(IOException.class, stream::readByte);
		}
		try (BoundedDecodeInputStream stream = new BoundedDecodeInputStream(new ByteArrayInputStream(new byte[4]), 4))
		{
			Assert.assertEquals(4L, stream.skip(4));
			Assert.assertEquals(-1, stream.read());
		}
	}

	@Test public void oversizedZstdHeaderRejectedBeforeCheckout()
	{
		// Single-segment Zstd frame: 64MiB+1 declared bytes and one empty final raw block.
		byte[] frame = { 0x28, (byte)0xb5, 0x2f, (byte)0xfd, (byte)0xa0,
			1, 0, 0, 4, 1, 0, 0 };
		Assert.assertThrows(ZstdException.class, () -> PooledZstdDecompressor.decompressFrame(frame, null));
	}
}
