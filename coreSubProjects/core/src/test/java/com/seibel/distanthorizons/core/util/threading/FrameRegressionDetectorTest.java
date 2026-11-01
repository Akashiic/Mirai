package com.seibel.distanthorizons.core.util.threading;

import org.junit.Assert;
import org.junit.Test;

public class FrameRegressionDetectorTest
{
	private static FrameRegressionDetector withBaseline(double baselineMs)
	{
		FrameRegressionDetector detector = new FrameRegressionDetector();
		for (int i = 0; i < FrameRegressionDetector.MIN_BASELINE_SAMPLES; i++)
		{
			Assert.assertFalse(detector.sample(baselineMs, false, true));
		}
		Assert.assertEquals(baselineMs, detector.getBaselineMs(), 1e-9);
		return detector;
	}

	@Test
	public void sustainedDoublingWithSaturatedWorkersStepsDown()
	{
		// the logged case: ~20 ms baseline, 40-78 ms while 12 workers were busy
		FrameRegressionDetector detector = withBaseline(20);
		Assert.assertFalse("one bad second is not enough", detector.sample(45, true, true));
		Assert.assertTrue(detector.sample(45, true, true));
		Assert.assertFalse("the counter restarts after a step", detector.sample(45, true, true));
		Assert.assertTrue(detector.sample(45, true, true));
	}

	@Test
	public void idleWorkersAreNeverBlamed()
	{
		// a slow scene with idle DH workers is not DH's fault, and it updates the baseline instead
		FrameRegressionDetector detector = withBaseline(20);
		for (int i = 0; i < 10; i++) { Assert.assertFalse(detector.sample(60, false, true)); }
		Assert.assertTrue(detector.getBaselineMs() > 20);
	}

	@Test
	public void smallAbsoluteChangesAtHighFpsAreIgnored()
	{
		// 4 -> 7 ms is 1.75x but still >140 FPS: below the +8 ms floor
		FrameRegressionDetector detector = withBaseline(4);
		for (int i = 0; i < 5; i++) { Assert.assertFalse(detector.sample(7, true, true)); }
	}

	@Test
	public void recoveryWaitsForTheHysteresisBand()
	{
		FrameRegressionDetector detector = withBaseline(20);
		detector.sample(30, true, true);
		Assert.assertFalse("30 ms is above 1.2 x 20 ms", detector.withinRecoveryBand());
		detector.sample(23, true, true);
		Assert.assertTrue(detector.withinRecoveryBand());
	}

	@Test
	public void failsOpenWithoutABaselineOrFocus()
	{
		FrameRegressionDetector detector = new FrameRegressionDetector();
		for (int i = 0; i < 20; i++) { Assert.assertFalse("never idle -> no baseline -> no action", detector.sample(80, true, true)); }
		Assert.assertTrue(detector.withinRecoveryBand());

		FrameRegressionDetector focused = withBaseline(20);
		focused.sample(80, true, true);
		Assert.assertFalse("menus and unfocused windows reset the streak", focused.sample(80, true, false));
		Assert.assertFalse(focused.sample(80, true, true));
	}

}
