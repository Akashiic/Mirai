package com.seibel.distanthorizons.core.multiplayer.fullData;

import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSplitMessage;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Assert;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;

public class FullDataPayloadReceiverTest
{
	private static FullDataSplitMessage fragment(int id, int size, boolean first)
	{
		return new FullDataSplitMessage(id, Unpooled.buffer(size).writeZero(size), first);
	}

	/** Models ClientApi's unconditional release of the ingress reference. */
	private static void receive(FullDataPayloadReceiver receiver, FullDataSplitMessage message)
	{
		try { receiver.receiveChunk(message); }
		finally { message.buffer.release(); }
	}

	@Test public void duplicateStartReplacesTheStaleTransfer()
	{
		FullDataSplitMessage first = fragment(1, 2, true);
		FullDataSplitMessage duplicate = fragment(1, 3, true);
		try (FullDataPayloadReceiver receiver = new FullDataPayloadReceiver(8, 16, 2, 100, () -> 0))
		{
			receive(receiver, first);
			receive(receiver, duplicate);
			Assert.assertEquals("the stale transfer's retained slice must be released", 0, first.buffer.refCnt());
			Assert.assertEquals(1, duplicate.buffer.refCnt());
			Assert.assertEquals(1, receiver.getBufferedTransferCount());
		}
		Assert.assertEquals(0, duplicate.buffer.refCnt());
	}

	/** AOTM-03: orphaned transfers (suppressed updates, cancelled requests) must never close the session. */
	@Test public void orphanedTransfersBeyondTheLimitEvictTheOldest()
	{
		AtomicLong now = new AtomicLong();
		java.util.List<FullDataSplitMessage> sent = new java.util.ArrayList<>();
		try (FullDataPayloadReceiver receiver = new FullDataPayloadReceiver(8, 1024, 128, Long.MAX_VALUE, now::get))
		{
			for (int id = 0; id < 129; id++)
			{
				now.set(id);
				FullDataSplitMessage message = fragment(id, 1, true);
				sent.add(message);
				receive(receiver, message);
			}
			Assert.assertEquals(128, receiver.getBufferedTransferCount());
			Assert.assertEquals("the oldest transfer is the one evicted", 0, sent.get(0).buffer.refCnt());
			Assert.assertEquals(1, sent.get(128).buffer.refCnt());
		}
		for (FullDataSplitMessage message : sent) { Assert.assertEquals(0, message.buffer.refCnt()); }
	}

	@Test public void continuationNeverRenewsAbsoluteDeadline()
	{
		AtomicLong now = new AtomicLong();
		FullDataSplitMessage first = fragment(1, 2, true);
		FullDataSplitMessage second = fragment(1, 2, false);
		FullDataSplitMessage late = fragment(1, 2, false);
		try (FullDataPayloadReceiver receiver = new FullDataPayloadReceiver(8, 16, 2, 10, now::get))
		{
			receive(receiver, first);
			now.set(9);
			receive(receiver, second);
			now.set(10);
			receive(receiver, late);
			Assert.assertEquals(0, first.buffer.refCnt());
			Assert.assertEquals(0, second.buffer.refCnt());
			Assert.assertEquals(0, late.buffer.refCnt());
		}
	}

	@Test public void admissionEnforcesBudgets()
	{
		try (FullDataPayloadReceiver receiver = new FullDataPayloadReceiver(4, 6, 2, 100, () -> 0))
		{
			receive(receiver, fragment(1, 4, true));
			// a single transfer above its own budget is malformed and still rejected
			Assert.assertThrows(IllegalArgumentException.class, () -> receive(receiver, fragment(1, 1, false)));
			Assert.assertThrows(IllegalArgumentException.class, () -> receive(receiver, fragment(9, 5, true)));
			receive(receiver, fragment(2, 2, true));
			// the session byte budget evicts the oldest other transfer instead of failing
			receive(receiver, fragment(3, 1, true));
			Assert.assertEquals(2, receiver.getBufferedTransferCount());
		}
		try (FullDataPayloadReceiver receiver = new FullDataPayloadReceiver(8, 32, 1, 100, () -> 0))
		{
			FullDataSplitMessage older = fragment(1, 1, true);
			receive(receiver, older);
			receive(receiver, fragment(2, 1, true));
			Assert.assertEquals(1, receiver.getBufferedTransferCount());
			Assert.assertEquals(0, older.buffer.refCnt());
		}
	}

	@Test public void validTransferRoundTripsAndReleasesAllFragments()
	{
		ByteBuf encoded = Unpooled.buffer();
		try (FullDataSourceV2DTO original = FullDataSourceV2DTO.CreateEmptyDataSourceForDecoding())
		{
			original.pos = 123;
			original.dataFormatVersion = 2;
			original.encode(encoded);
		}
		FullDataSplitMessage first = new FullDataSplitMessage(1, encoded.readSlice(9).copy(), true);
		FullDataSplitMessage second = new FullDataSplitMessage(1, encoded.readSlice(encoded.readableBytes()).copy(), false);
		encoded.release();
		try (FullDataPayloadReceiver receiver = new FullDataPayloadReceiver())
		{
			receive(receiver, first);
			receive(receiver, second);
			FullDataPayload payload = new FullDataPayload();
			payload.dtoBufferId = 1;
			try (FullDataSourceV2DTO decoded = receiver.decodeDataSource(payload))
			{
				Assert.assertEquals(123, decoded.pos);
				Assert.assertEquals(2, decoded.dataFormatVersion);
			}
			Assert.assertEquals(0, first.buffer.refCnt());
			Assert.assertEquals(0, second.buffer.refCnt());
		}
	}

	@Test public void corruptDecodeReleasesBufferAndRestoresSessionBudget()
	{
		ByteBuf corrupt = Unpooled.buffer().writeLong(0).writeInt(0).writeInt(Integer.MAX_VALUE);
		FullDataSplitMessage first = new FullDataSplitMessage(1, corrupt, true);
		try (FullDataPayloadReceiver receiver = new FullDataPayloadReceiver(16, 16, 1, 100, () -> 0))
		{
			receive(receiver, first);
			FullDataPayload payload = new FullDataPayload();
			payload.dtoBufferId = 1;
			Assert.assertThrows(IllegalArgumentException.class, () -> receiver.decodeDataSource(payload));
			Assert.assertEquals(0, first.buffer.refCnt());
			receive(receiver, fragment(2, 16, true));
		}
	}

	@Test public void fragmentLimitRejectsBeforeRetainingInput()
	{
		FullDataSplitMessage refused = fragment(1, 1, false);
		try (FullDataPayloadReceiver receiver = new FullDataPayloadReceiver(32, 64, 2, 100, () -> 0, 2))
		{
			receive(receiver, fragment(1, 1, true));
			receive(receiver, fragment(1, 1, false));
			Assert.assertThrows(IllegalArgumentException.class, () -> receive(receiver, refused));
			Assert.assertEquals(0, refused.buffer.refCnt());
		}
	}
}
