package com.seibel.distanthorizons.core.world;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

public class LevelKeyWaitTest
{
	@Test
	public void eachWorldWaitsForItsOwnKey()
	{
		AtomicLong now = new AtomicLong(1_000);
		LevelKeyWait wait = new LevelKeyWait(5_000, now::get);
		Object firstWorld = new Object();

		Assert.assertFalse("the first request starts the wait", wait.mayLoadWithoutKey(firstWorld));
		now.addAndGet(4_999);
		Assert.assertFalse(wait.mayLoadWithoutKey(firstWorld));
		now.addAndGet(1);
		Assert.assertTrue(wait.mayLoadWithoutKey(firstWorld));

		// a later world (backend switch / dimension change) gets its own full wait,
		// the old once-per-connection delay let it load unkeyed immediately
		Object secondWorld = new Object();
		Assert.assertFalse(wait.mayLoadWithoutKey(secondWorld));
		now.addAndGet(4_999);
		Assert.assertFalse(wait.mayLoadWithoutKey(secondWorld));
		now.addAndGet(1);
		Assert.assertTrue(wait.mayLoadWithoutKey(secondWorld));
		Assert.assertTrue("an expired wait stays expired", wait.mayLoadWithoutKey(firstWorld));
	}

	@Test
	public void noWorldOrNoWaitLoadsImmediately()
	{
		LevelKeyWait wait = new LevelKeyWait(5_000, () -> 0);
		Assert.assertTrue(wait.mayLoadWithoutKey(null));
		Assert.assertTrue(new LevelKeyWait(0, () -> 0).mayLoadWithoutKey(new Object()));
	}
	
}
