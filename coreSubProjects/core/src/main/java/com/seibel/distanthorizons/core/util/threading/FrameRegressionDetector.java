package com.seibel.distanthorizons.core.util.threading;

/**
 * Detects a sustained frame-time regression caused by saturated DH workers. <br><br>
 *
 * Real logs (16 logical CPUs, 12 workers): during LOD bursts the process used 12-13.7 cores and the mean frame
 * went from ~20 ms to 40-78 ms, while no single frame reached the governor's 100 ms "slow" threshold, so it never
 * reacted. This compares each 1 s sample with the player's own baseline instead of an absolute threshold: <br>
 * - baseline = slow EWMA of focused samples taken while DH workers are <b>not</b> saturated (DH isn't the cause); <br>
 * - regressed = workers saturated and mean >= {@link #RATIO} x baseline and >= baseline + {@link #MIN_EXTRA_MS}; <br>
 * - two consecutive regressed samples request one step down; <br>
 * - recovery is allowed only when the mean is back within {@link #RECOVERY_RATIO} x baseline (hysteresis). <br>
 * Without a formed baseline (e.g. workers never idle) it never requests anything: it fails open.
 */
public final class FrameRegressionDetector
{
	static final double RATIO = 1.5;
	static final double MIN_EXTRA_MS = 8.0;
	static final double RECOVERY_RATIO = 1.2;
	static final double BASELINE_ALPHA = 0.1;
	static final int MIN_BASELINE_SAMPLES = 5;
	static final int CONSECUTIVE_SAMPLES = 2;

	private double baselineMs = 0;
	private int baselineSamples = 0;
	private int regressedInARow = 0;
	private boolean lastWithinRecoveryBand = true;
	private double lastMeanMs = 0;


	/**
	 * @param meanFrameMs mean focused frame time of the last sample, 0 if none
	 * @param workersSaturated every allowed DH worker was busy at the sample
	 * @return true when DH concurrency should step down by one
	 */
	public boolean sample(double meanFrameMs, boolean workersSaturated, boolean focused)
	{
		this.lastMeanMs = meanFrameMs;
		if (!focused || meanFrameMs <= 0)
		{
			this.regressedInARow = 0;
			this.lastWithinRecoveryBand = true;
			return false;
		}

		if (!workersSaturated)
		{
			this.baselineMs = this.baselineSamples == 0 ? meanFrameMs : this.baselineMs + (meanFrameMs - this.baselineMs) * BASELINE_ALPHA;
			this.baselineSamples++;
		}

		boolean baselineReady = this.baselineSamples >= MIN_BASELINE_SAMPLES;
		this.lastWithinRecoveryBand = !baselineReady || meanFrameMs <= this.baselineMs * RECOVERY_RATIO;

		boolean regressed = baselineReady && workersSaturated
			&& meanFrameMs >= this.baselineMs * RATIO
			&& meanFrameMs >= this.baselineMs + MIN_EXTRA_MS;
		this.regressedInARow = regressed ? this.regressedInARow + 1 : 0;

		if (this.regressedInARow >= CONSECUTIVE_SAMPLES)
		{
			this.regressedInARow = 0;
			return true;
		}
		return false;
	}

	/** false while the frame time is still above the recovery band, so concurrency must not grow back yet */
	public boolean withinRecoveryBand() { return this.lastWithinRecoveryBand; }

	public double getBaselineMs() { return this.baselineMs; }
	public double getLastMeanMs() { return this.lastMeanMs; }

}
