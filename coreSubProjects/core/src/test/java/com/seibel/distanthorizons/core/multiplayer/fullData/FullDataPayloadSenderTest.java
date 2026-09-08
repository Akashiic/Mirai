package com.seibel.distanthorizons.core.multiplayer.fullData;

import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import com.seibel.distanthorizons.core.network.messages.base.CloseReasonMessage;
import com.seibel.distanthorizons.core.network.messages.fullData.FullDataSplitMessage;
import com.seibel.distanthorizons.core.network.session.NetworkSession;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Assert;
import org.junit.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public class FullDataPayloadSenderTest
{
	private static FullDataPayload payload(int size)
	{
		FullDataPayload payload = new FullDataPayload();
		payload.dtoBufferId = 17;
		payload.dtoBuffer = Unpooled.buffer(size).writeZero(size);
		return payload;
	}

	@Test public void outgoingSnapshotsSurviveCloseAndOriginalRelease()
	{
		FakeSession session = new FakeSession();
		FakeBandwidth bandwidth = new FakeBandwidth(Integer.MAX_VALUE);
		FullDataPayload payload = payload(FullDataPayloadSender.FULL_DATA_SPLIT_SIZE_IN_BYTES + 7);
		AtomicInteger complete = new AtomicInteger();
		try (FullDataPayloadSender sender = new FullDataPayloadSender(session, () -> 0, bandwidth, false))
		{
			sender.sendInChunks(payload, complete::incrementAndGet);
			Assert.assertEquals(2, payload.dtoBuffer.refCnt());
			sender.tick();
			Assert.assertEquals(1, payload.dtoBuffer.refCnt());
			Assert.assertEquals(1, complete.get());
			Assert.assertEquals(2, session.messages.size());
			Assert.assertTrue(bandwidth.active.isEmpty());
		}
		payload.dtoBuffer.release();
		for (AbstractNetworkMessage message : session.messages)
		{
			ByteBuf encoded = Unpooled.buffer();
			try
			{
				message.encode(encoded);
				Assert.assertTrue(encoded.readableBytes() <= FullDataPayloadSender.FULL_DATA_SPLIT_SIZE_IN_BYTES + 9);
			}
			finally { encoded.release(); }
		}
	}

	@Test public void zeroShareDoesNotSendAndCloseReleasesOnlyOwnedReference()
	{
		FakeSession session = new FakeSession();
		FakeBandwidth bandwidth = new FakeBandwidth(0);
		FullDataPayload payload = payload(9);
		FullDataPayloadSender sender = new FullDataPayloadSender(session, () -> 0, bandwidth, false);
		sender.sendInChunks(payload, () -> Assert.fail("Cancelled callback ran"));
		sender.tick();
		Assert.assertTrue(session.messages.isEmpty());
		sender.close();
		sender.close();
		Assert.assertEquals(1, payload.dtoBuffer.refCnt());
		Assert.assertTrue(bandwidth.active.isEmpty());
		sender.sendInChunks(payload, () -> Assert.fail("Closed sender accepted work"));
		Assert.assertEquals(1, payload.dtoBuffer.refCnt());
		payload.dtoBuffer.release();
	}

	@Test public void unlimitedConfigurationHasFiniteTickBudgetWithoutOverflow()
	{
		FakeSession session = new FakeSession();
		FakeBandwidth bandwidth = new FakeBandwidth(Integer.MAX_VALUE);
		FullDataPayload payload = payload(2 * FullDataPayloadSender.MAX_BYTES_PER_TICK);
		try (FullDataPayloadSender sender = new FullDataPayloadSender(session, () -> Integer.MAX_VALUE, bandwidth, false))
		{
			sender.sendInChunks(payload, () -> { });
			sender.tick();
			int bytes = 0;
			for (AbstractNetworkMessage message : session.messages) { bytes += ((FullDataSplitMessage) message).getPayloadSize(); }
			Assert.assertEquals(FullDataPayloadSender.MAX_BYTES_PER_TICK, bytes);
			Assert.assertEquals(2, payload.dtoBuffer.refCnt());
		}
		Assert.assertEquals(1, payload.dtoBuffer.refCnt());
		payload.dtoBuffer.release();
	}

	@Test public void queueOverflowClosesOnlyThisSessionAndReturnsOwnedReferences()
	{
		FakeSession session = new FakeSession();
		FakeBandwidth bandwidth = new FakeBandwidth(0);
		FullDataPayload payload = payload(1);
		try (FullDataPayloadSender sender = new FullDataPayloadSender(session, () -> 0, bandwidth, false))
		{
			for (int i = 0; i <= FullDataPayloadSender.MAX_QUEUED_TRANSFERS; i++) { sender.sendInChunks(payload, () -> { }); }
			Assert.assertTrue(session.isClosed());
			Assert.assertEquals(1, payload.dtoBuffer.refCnt());
			Assert.assertTrue(bandwidth.active.isEmpty());
			Assert.assertTrue(session.messages.get(0) instanceof CloseReasonMessage);
		}
		payload.dtoBuffer.release();
	}

	@Test public void encoderFailureCannotRetainTheQueueOrKillOtherSenders()
	{
		FakeSession session = new FakeSession();
		session.failSplits = true;
		FakeBandwidth bandwidth = new FakeBandwidth(Integer.MAX_VALUE);
		FullDataPayload payload = payload(12);
		try (FullDataPayloadSender sender = new FullDataPayloadSender(session, () -> 0, bandwidth, false))
		{
			sender.sendInChunks(payload, () -> Assert.fail("Failed transfer completed"));
			sender.tick();
			Assert.assertTrue(session.isClosed());
			Assert.assertEquals(1, payload.dtoBuffer.refCnt());
			Assert.assertTrue(bandwidth.active.isEmpty());
		}
		payload.dtoBuffer.release();
	}

	@Test public void terminalCallbackRunsOnceOnSuccessfulDeliveryAndClose()
	{
		FakeSession session = new FakeSession();
		FullDataPayload payload = payload(20);
		AtomicInteger completed = new AtomicInteger(); AtomicInteger terminal = new AtomicInteger();
		try (FullDataPayloadSender sender = new FullDataPayloadSender(session, () -> 0, new FakeBandwidth(Integer.MAX_VALUE), false))
		{
			sender.sendInChunks(payload, completed::incrementAndGet, () -> true, terminal::incrementAndGet);
			sender.tick(); sender.close();
			Assert.assertEquals(1, completed.get()); Assert.assertEquals(1, terminal.get());
		}
		Assert.assertEquals(1, payload.dtoBuffer.refCnt()); payload.dtoBuffer.release();
	}

	@Test public void dimensionChangeCancelsQueuedUploadEvenWithoutBandwidth()
	{
		FakeSession session = new FakeSession(); FullDataPayload payload = payload(20);
		AtomicBoolean currentLevel = new AtomicBoolean(true); AtomicInteger terminal = new AtomicInteger();
		try (FullDataPayloadSender sender = new FullDataPayloadSender(session, () -> 0, new FakeBandwidth(0), false))
		{
			sender.sendInChunks(payload, () -> Assert.fail("Stale payload delivered"), currentLevel::get, terminal::incrementAndGet);
			currentLevel.set(false); sender.tick(); sender.close();
			Assert.assertEquals(1, terminal.get()); Assert.assertTrue(session.messages.isEmpty());
		}
		Assert.assertEquals(1, payload.dtoBuffer.refCnt()); payload.dtoBuffer.release();
	}

	@Test public void rejectionCloseAndEncoderFailureFinishEveryOwnedRequest()
	{
		FullDataPayload payload = payload(20); AtomicInteger terminal = new AtomicInteger();
		FakeSession session = new FakeSession(); session.failSplits = true;
		try (FullDataPayloadSender sender = new FullDataPayloadSender(session, () -> 0, new FakeBandwidth(Integer.MAX_VALUE), false))
		{
			sender.sendInChunks(payload, () -> Assert.fail(), () -> true, terminal::incrementAndGet);
			sender.sendInChunks(payload, () -> Assert.fail(), () -> true, terminal::incrementAndGet);
			sender.tick();
			sender.sendInChunks(payload, () -> Assert.fail(), () -> true, terminal::incrementAndGet);
			Assert.assertEquals(3, terminal.get());
		}
		Assert.assertEquals(1, payload.dtoBuffer.refCnt()); payload.dtoBuffer.release();
	}

	@Test public void throwingFinalCallbackStillFinishesItsRequest()
	{
		FullDataPayload payload = payload(20); AtomicInteger terminal = new AtomicInteger();
		try (FullDataPayloadSender sender = new FullDataPayloadSender(new FakeSession(), () -> 0, new FakeBandwidth(Integer.MAX_VALUE), false))
		{
			sender.sendInChunks(payload, () -> { throw new IllegalStateException("injected final response failure"); }, () -> true, terminal::incrementAndGet);
			sender.tick(); sender.close(); Assert.assertEquals(1, terminal.get());
		}
		Assert.assertEquals(1, payload.dtoBuffer.refCnt()); payload.dtoBuffer.release();
	}

	private static class FakeSession extends NetworkSession
	{
		final List<AbstractNetworkMessage> messages = new ArrayList<>();
		boolean failSplits;
		boolean failClose;
		FakeSession() { super(null); }
		@Override public void sendMessage(AbstractNetworkMessage message)
		{
			if (this.failSplits && message instanceof FullDataSplitMessage) { throw new IllegalStateException("injected encoder failure"); }
			if (this.failClose && message instanceof CloseReasonMessage) { throw new IllegalStateException("injected close notification failure"); }
			this.messages.add(message);
		}
	}

	@Test public void failedCloseNotificationStillCleansUpAndAllowsOtherSender()
	{
		FakeSession failed = new FakeSession();
		failed.failSplits = true;
		failed.failClose = true;
		FakeSession healthy = new FakeSession();
		FakeBandwidth bandwidth = new FakeBandwidth(Integer.MAX_VALUE);
		FullDataPayload payload = payload(12);
		try (FullDataPayloadSender first = new FullDataPayloadSender(failed, () -> 0, bandwidth, false);
			FullDataPayloadSender second = new FullDataPayloadSender(healthy, () -> 0, bandwidth, false))
		{
			first.sendInChunks(payload, () -> Assert.fail("Failed transfer completed"));
			second.sendInChunks(payload, () -> { });
			first.tick();
			Assert.assertTrue(failed.isClosed());
			second.tick();
			Assert.assertFalse(healthy.isClosed());
			Assert.assertEquals(1, healthy.messages.size());
			Assert.assertEquals(1, payload.dtoBuffer.refCnt());
			Assert.assertTrue(bandwidth.active.isEmpty());
		}
		payload.dtoBuffer.release();
	}

	private static class FakeBandwidth extends SharedBandwidthLimit
	{
		final Set<FullDataPayloadSender> active = new HashSet<>();
		final int share;
		FakeBandwidth(int share) { this.share = share; }
		@Override public int getBandwidthShare() { return this.share; }
		@Override public void setSenderActive(FullDataPayloadSender sender, boolean value)
		{
			if (value) { this.active.add(sender); } else { this.active.remove(sender); }
		}
	}
}
