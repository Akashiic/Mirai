package tests;

import com.seibel.distanthorizons.core.network.event.internal.CloseInternalEvent;
import com.seibel.distanthorizons.core.network.session.NetworkSession;
import com.seibel.distanthorizons.core.network.session.SessionClosedException;
import org.junit.Assert;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;

public class NetworkSessionCloseTest
{
	@Test public void normalCloseMarksSessionClosedAndFiresOnce()
	{
		NetworkSession session = new NetworkSession(null);
		AtomicInteger closes = new AtomicInteger();
		session.registerHandler(CloseInternalEvent.class, ignored -> closes.incrementAndGet());
		Assert.assertFalse(session.isClosed());
		session.close();
		Assert.assertTrue(session.isClosed());
		Assert.assertTrue(session.getCloseReason() instanceof SessionClosedException);
		session.close();
		Assert.assertEquals(1, closes.get());
	}
}
