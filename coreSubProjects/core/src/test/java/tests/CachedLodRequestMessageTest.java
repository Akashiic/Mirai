package tests;

import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSourceRequestMessage;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Tests the existing wire envelope as well as the read-only query timestamp contract. */
public class CachedLodRequestMessageTest
{
	private static FullDataSourceRequestMessage message() throws Exception
	{
		// Message construction requires a live Minecraft world. Serialization does not;
		// bypass only that environment-dependent constructor, as other network tests do.
		Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
		Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		Method allocate = unsafeType.getMethod("allocateInstance", Class.class);
		return (FullDataSourceRequestMessage) allocate.invoke(unsafeField.get(null), FullDataSourceRequestMessage.class);
	}

	@Test public void generationTimestampAndCacheQueryHaveDistinctWireRepresentations() throws Exception
	{
		for (Long timestamp : new Long[] { null, FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP, 0L, 1234567890L, Long.MAX_VALUE })
		{
			FullDataSourceRequestMessage original = message();
			Field name = FullDataSourceRequestMessage.class.getDeclaredField("levelName");
			name.setAccessible(true);
			name.set(original, "backend@end:1");
			original.sectionPos = DhSectionPos.encode((byte) 6, -4, 12);
			original.clientTimestamp = timestamp;
			original.futureId = 12345;
			ByteBuf encoded = Unpooled.buffer();
			try
			{
				original.encode(encoded);
				FullDataSourceRequestMessage decoded = message();
				decoded.decode(encoded);
				Assert.assertEquals(original.getLevelName(), decoded.getLevelName());
				Assert.assertEquals(original.sectionPos, decoded.sectionPos);
				Assert.assertEquals(timestamp, decoded.clientTimestamp);
				Assert.assertEquals(original.futureId, decoded.futureId);
				Assert.assertFalse(encoded.isReadable());
			}
			finally { encoded.release(); }
		}
	}

	@Test public void sentinelAndGenerationModeAreNeverChangedByClockOffset()
	{
		for (long offset : new long[] { Long.MIN_VALUE, -60000L, 0L, 60000L, Long.MAX_VALUE })
		{
			Assert.assertNull(FullDataSourceRequestMessage.toServerTimestamp(null, offset));
			Assert.assertEquals(Long.valueOf(FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP),
					FullDataSourceRequestMessage.toServerTimestamp(FullDataSourceRequestMessage.CACHE_ONLY_TIMESTAMP, offset));
		}
	}

	@Test public void normalTimestampsUseServerClockWithoutEnteringSentinelMode()
	{
		Assert.assertEquals(Long.valueOf(1500L), FullDataSourceRequestMessage.toServerTimestamp(1000L, 500L));
		Assert.assertEquals(Long.valueOf(500L), FullDataSourceRequestMessage.toServerTimestamp(1000L, -500L));
		Assert.assertEquals(Long.valueOf(0L), FullDataSourceRequestMessage.toServerTimestamp(1000L, -1001L));
		Assert.assertEquals(Long.valueOf(0L), FullDataSourceRequestMessage.toServerTimestamp(1000L, Long.MIN_VALUE));
	}

	@Test public void overflowingClockArithmeticSaturatesInsteadOfWrapping()
	{
		Assert.assertEquals(Long.valueOf(Long.MAX_VALUE), FullDataSourceRequestMessage.toServerTimestamp(Long.MAX_VALUE, 1L));
		Assert.assertEquals(Long.valueOf(Long.MAX_VALUE), FullDataSourceRequestMessage.toServerTimestamp(1000L, Long.MAX_VALUE));
		Assert.assertEquals(Long.valueOf(Long.MAX_VALUE - 1L), FullDataSourceRequestMessage.toServerTimestamp(Long.MAX_VALUE, -1L));
	}
}
