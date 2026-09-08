package com.seibel.distanthorizons.core.multiplayer.server;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Ownership can pass either to a running task or to shutdown, never to both. */
final class QueuedResource<T>
{
	private final T resource;
	private final Consumer<T> release;
	private final Runnable remove;
	private final AtomicBoolean claimed = new AtomicBoolean();

	QueuedResource(T resource, Consumer<T> release, Runnable remove)
	{
		this.resource = resource;
		this.release = release;
		this.remove = remove;
	}

	void run(Consumer<T> action)
	{
		if (!this.claimed.compareAndSet(false, true)) { return; }
		try { action.accept(this.resource); }
		finally { this.release(); }
	}

	void discard()
	{
		if (this.claimed.compareAndSet(false, true)) { this.release(); }
	}

	private void release()
	{
		try { this.release.accept(this.resource); }
		finally { this.remove.run(); }
	}
}
