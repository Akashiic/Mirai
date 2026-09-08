package com.seibel.distanthorizons.core.multiplayer.server;

import com.seibel.distanthorizons.core.util.ratelimiting.SupplierBasedConcurrencyLimiter;
import org.junit.Assert;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestQuotaLeaseTest
{
	@Test public void responseFailureStillRestoresPermitAndRemovesRequest()
	{
		SupplierBasedConcurrencyLimiter<Object> limiter = new SupplierBasedConcurrencyLimiter<>(() -> 1, ignored -> { });
		Assert.assertTrue(limiter.tryAcquire(null));
		AtomicInteger removed = new AtomicInteger();
		RequestQuotaLease lease = new RequestQuotaLease(limiter::release, removed::incrementAndGet);
		try { lease.finish(() -> { throw new IllegalStateException("injected send failure"); }); Assert.fail(); }
		catch (IllegalStateException expected) { }
		lease.finish(null);
		Assert.assertEquals(1, removed.get());
		Assert.assertTrue(limiter.tryAcquire(null));
		Assert.assertFalse("Double release would allow two simultaneous requests", limiter.tryAcquire(null));
		limiter.release();
	}

	@Test public void cancelCloseAndDeliveryRaceHasOneTerminalOwner() throws Exception
	{
		AtomicInteger released = new AtomicInteger();
		AtomicInteger removed = new AtomicInteger();
		AtomicInteger responses = new AtomicInteger();
		RequestQuotaLease lease = new RequestQuotaLease(released::incrementAndGet, removed::incrementAndGet);
		CountDownLatch start = new CountDownLatch(1);
		Thread[] racers = new Thread[16];
		for (int i = 0; i < racers.length; i++)
		{
			racers[i] = new Thread(() -> { try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); } lease.finish(responses::incrementAndGet); });
			racers[i].start();
		}
		start.countDown();
		for (Thread racer : racers) { racer.join(5000); Assert.assertFalse(racer.isAlive()); }
		Assert.assertEquals(1, released.get()); Assert.assertEquals(1, removed.get()); Assert.assertEquals(1, responses.get());
	}

	@Test public void acceptedTaskDiscardedByShutdownReleasesWithoutRunning()
	{
		AtomicInteger releases = new AtomicInteger(); AtomicInteger removals = new AtomicInteger();
		QueuedResource<Object> resource = new QueuedResource<>(new Object(), ignored -> releases.incrementAndGet(), removals::incrementAndGet);
		resource.discard(); resource.discard(); resource.run(ignored -> Assert.fail("Closed task ran"));
		Assert.assertEquals(1, releases.get()); Assert.assertEquals(1, removals.get());
	}

	@Test public void shutdownCannotReleaseResourceOwnedByRunningWorker() throws Exception
	{
		AtomicInteger releases = new AtomicInteger();
		CountDownLatch running = new CountDownLatch(1); CountDownLatch finish = new CountDownLatch(1);
		QueuedResource<Object> resource = new QueuedResource<>(new Object(), ignored -> releases.incrementAndGet(), () -> { });
		Thread worker = new Thread(() -> resource.run(ignored -> { running.countDown(); try { finish.await(); } catch (InterruptedException e) { throw new AssertionError(e); } }));
		worker.start(); Assert.assertTrue(running.await(5, java.util.concurrent.TimeUnit.SECONDS));
		resource.discard(); Assert.assertEquals(0, releases.get()); finish.countDown(); worker.join(5000);
		Assert.assertFalse(worker.isAlive()); Assert.assertEquals(1, releases.get());
	}

	@Test public void failedResourceCloseStillDropsTrackingReference()
	{
		AtomicInteger removals = new AtomicInteger();
		QueuedResource<Object> resource = new QueuedResource<>(new Object(), ignored -> { throw new IllegalStateException("injected close failure"); }, removals::incrementAndGet);
		try { resource.run(ignored -> { }); Assert.fail(); } catch (IllegalStateException expected) { }
		resource.discard(); Assert.assertEquals(1, removals.get());
	}
}
