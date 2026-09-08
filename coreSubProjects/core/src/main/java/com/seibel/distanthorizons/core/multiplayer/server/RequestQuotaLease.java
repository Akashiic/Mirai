package com.seibel.distanthorizons.core.multiplayer.server;

import java.util.concurrent.atomic.AtomicBoolean;

/** One successfully acquired concurrency permit, owned until any terminal outcome. */
final class RequestQuotaLease
{
	private final AtomicBoolean finished = new AtomicBoolean();
	private final Runnable release;
	private final Runnable remove;

	RequestQuotaLease(Runnable release, Runnable remove)
	{
		this.release = release;
		this.remove = remove;
	}

	boolean isPending() { return !this.finished.get(); }

	void finish(Runnable response)
	{
		if (!this.finished.compareAndSet(false, true)) { return; }
		try { if (response != null) { response.run(); } }
		finally
		{
			try { this.release.run(); }
			finally { this.remove.run(); }
		}
	}
}
