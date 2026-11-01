package com.seibel.distanthorizons.core.util.threading;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class CountedTaskDequeTest
{
	@Test
	public void sizeAndOrderMatchADequeUnderRandomOperations()
	{
		CountedTaskDeque<Integer> counted = new CountedTaskDeque<>();
		ArrayDeque<Integer> mirror = new ArrayDeque<>();
		Random random = new Random(42);
		for (int i = 0; i < 20_000; i++)
		{
			int op = random.nextInt(10);
			Integer value = i;
			if (op < 4) { counted.add(value); mirror.add(value); }
			else if (op < 6) { counted.addFirst(value); mirror.addFirst(value); }
			else if (op < 9) { Assert.assertEquals(mirror.poll(), counted.poll()); }
			else
			{
				int mod = 2 + random.nextInt(5);
				counted.removeIf(v -> v % mod == 0);
				mirror.removeIf(v -> v % mod == 0);
			}
			Assert.assertEquals(mirror.size(), counted.size());
		}

		List<Integer> fromCounted = new ArrayList<>();
		counted.forEach(fromCounted::add);
		Assert.assertEquals(new ArrayList<>(mirror), fromCounted);

		counted.clear();
		Assert.assertEquals(0, counted.size());
		Assert.assertTrue(counted.isEmpty());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void iteratorCannotBypassTheCounter()
	{
		CountedTaskDeque<Integer> counted = new CountedTaskDeque<>();
		counted.add(1);
		Iterator<Integer> iterator = counted.iterator();
		iterator.next();
		iterator.remove();
	}

	@Test
	public void concurrentProducersAndConsumersLeaveAnExactCount() throws InterruptedException
	{
		CountedTaskDeque<Integer> counted = new CountedTaskDeque<>();
		int threads = 4;
		int perThread = 20_000;
		AtomicInteger polled = new AtomicInteger();
		CountDownLatch done = new CountDownLatch(threads * 2);
		for (int t = 0; t < threads; t++)
		{
			new Thread(() -> { for (int i = 0; i < perThread; i++) { counted.add(i); } done.countDown(); }).start();
			new Thread(() -> { for (int i = 0; i < perThread / 2; i++) { if (counted.poll() != null) { polled.incrementAndGet(); } } done.countDown(); }).start();
		}
		done.await();

		int remaining = 0;
		for (Integer ignored : counted) { remaining++; }
		Assert.assertEquals(threads * perThread - polled.get(), remaining);
		Assert.assertEquals(remaining, counted.size());
	}

}
