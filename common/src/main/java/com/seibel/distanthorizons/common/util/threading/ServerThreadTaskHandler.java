package com.seibel.distanthorizons.common.util.threading;

import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Queues work that must run on the Minecraft server thread. The platform's
 * server-tick callback is responsible for calling {@link #runTasks(long)}.
 */
public class ServerThreadTaskHandler
{
	public static final ServerThreadTaskHandler INSTANCE = new ServerThreadTaskHandler();

	private final ConcurrentLinkedQueue<QueuedTask<?>> deferrableTaskQueue = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<QueuedTask<?>> essentialTaskQueue = new ConcurrentLinkedQueue<>();
	private IMinecraftSharedWrapper mcSharedWrapper = null;
	private static final int MAX_DEFERRED_TASKS = 4096;
	private final AtomicInteger deferredTaskCount = new AtomicInteger();
	private final Object lifecycleLock = new Object();
	private volatile boolean isShutdown = true;
	private volatile long sessionId;
	private volatile Thread serverThread;



	private ServerThreadTaskHandler() { }



	/**
	 * Queues a task that may be deferred to a later tick while the server thread is unhealthy. <br>
	 * Use this for work that only adds load, like requesting a chunk.
	 */
	public <T> CompletableFuture<T> queueTask(Supplier<T> task)
	{ return this.queueTask(this.sessionId, task); }

	public <T> CompletableFuture<T> queueTask(long expectedSession, Supplier<T> task)
	{ return addTask(this.deferrableTaskQueue, expectedSession, task, true); }

	/**
	 * Queues a task that runs even while the server thread is unhealthy. <br>
	 * Use this for work that removes load, like releasing a chunk. Deferring that
	 * work would keep chunks loaded exactly when memory pressure is at its worst.
	 */
	public <T> CompletableFuture<T> queueEssentialTask(Supplier<T> task)
	{
		return this.queueEssentialTask(this.sessionId, task);
	}

	/** Delayed cleanup must retain the session that owns its resources. */
	public <T> CompletableFuture<T> queueEssentialTask(long expectedSession, Supplier<T> task)
	{ return addTask(this.essentialTaskQueue, expectedSession, task, false); }

	public long getSessionId() { return this.sessionId; }
	public boolean isServerThread() { return Thread.currentThread() == this.serverThread; }

	private <T> CompletableFuture<T> addTask(ConcurrentLinkedQueue<QueuedTask<?>> taskQueue,
		long expectedSession, Supplier<T> task, boolean deferred)
	{
		Objects.requireNonNull(task, "task");
		CompletableFuture<T> future = new CompletableFuture<>();
		synchronized (this.lifecycleLock)
		{
			if (this.isShutdown || expectedSession != this.sessionId)
			{
				cancelFuture(future);
			}
			else if (deferred && this.deferredTaskCount.get() >= MAX_DEFERRED_TASKS)
			{
				future.completeExceptionally(new RejectedExecutionException("Mirai server task queue is full."));
			}
			else
			{
				if (deferred) this.deferredTaskCount.incrementAndGet();
				taskQueue.add(new QueuedTask<>(task, future, expectedSession, deferred));
			}
		}

		return future;
	}

	/**
	 * Runs queued tasks on the server thread. <br>
	 * Essential tasks run first and are never gated on server health, deferrable
	 * tasks only run once the server thread is healthy. Both draw from the same
	 * time budget, and at least one task is run even when that task alone
	 * exceeds the supplied budget.
	 */
	public void runTasks(long maxRunTimeNano)
	{
		if (this.isShutdown) return;
		if (!this.isServerThread()) throw new IllegalStateException("Mirai server tasks require the server owner thread.");
		long deadlineNano = System.nanoTime() + maxRunTimeNano;

		// note: if essential tasks keep using up the whole budget then deferrable
		// tasks will starve. That's acceptable, since essential tasks only exist
		// because deferrable ones already ran, so the backlog drains instead of deadlocking.
		if (!runQueueUntilDeadline(this.essentialTaskQueue, deadlineNano))
		{
			return;
		}

		if (this.deferrableTaskQueue.isEmpty())
		{
			return;
		}

		if (!this.deferrableTasksCanRun())
		{
			return;
		}

		runQueueUntilDeadline(this.deferrableTaskQueue, deadlineNano);
	}
	
	private boolean deferrableTasksCanRun()
	{
		if (!Config.Common.WorldGenerator.limitIfServerUnhealthy.get())
		{
			return true;
		}

		return mcSharedWrapper == null || mcSharedWrapper.isServerThreadHealthy();
	}

	/** @return true if the queue was emptied, false if the deadline was reached first. */
	private boolean runQueueUntilDeadline(ConcurrentLinkedQueue<QueuedTask<?>> taskQueue, long deadlineNano)
	{
		QueuedTask<?> queuedTask;
		while ((queuedTask = taskQueue.poll()) != null)
		{
			if (queuedTask.deferred) this.deferredTaskCount.decrementAndGet();
			if (this.isShutdown || queuedTask.sessionId != this.sessionId) cancelFuture(queuedTask.future);
			else queuedTask.run();
			if (System.nanoTime() >= deadlineNano)
			{
				return false;
			}
		}

		return true;
	}

	/** Completes queued tasks exceptionally without running them. */
	public void cancelPendingTasks()
	{
		List<QueuedTask<?>> cancelled;
		synchronized (this.lifecycleLock)
		{
			this.isShutdown = true;
			this.sessionId++;
			cancelled = this.drainPendingTasks();
		}
		for (QueuedTask<?> task : cancelled) cancelFuture(task.future);
	}

	/** Prepares this singleton handler for a new Minecraft server session. */
	public void reset()
	{
		List<QueuedTask<?>> cancelled;
		synchronized (this.lifecycleLock)
		{
			this.sessionId++;
			cancelled = this.drainPendingTasks();
			this.serverThread = Thread.currentThread();
			this.isShutdown = false;
			if (mcSharedWrapper == null) {
				mcSharedWrapper = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class);
			}
		}
		for (QueuedTask<?> task : cancelled) cancelFuture(task.future);
	}

	private List<QueuedTask<?>> drainPendingTasks()
	{
		List<QueuedTask<?>> cancelled = new ArrayList<>();
		QueuedTask<?> queuedTask;
		while ((queuedTask = this.essentialTaskQueue.poll()) != null) cancelled.add(queuedTask);
		while ((queuedTask = this.deferrableTaskQueue.poll()) != null) {
			this.deferredTaskCount.decrementAndGet();
			cancelled.add(queuedTask);
		}
		return cancelled;
	}
	private static void cancelFuture(CompletableFuture<?> future)
	{
		future.completeExceptionally(
			new CancellationException("The Minecraft server stopped before the queued task could run."));
	}

	private static class QueuedTask<T>
	{
		private final Supplier<T> task;
		private final CompletableFuture<T> future;
		private final long sessionId;
		private final boolean deferred;

		private QueuedTask(Supplier<T> task, CompletableFuture<T> future, long sessionId, boolean deferred)
		{
			this.task = task;
			this.future = future;
			this.sessionId = sessionId;
			this.deferred = deferred;
		}

		private void run()
		{
			if (this.deferred && this.future.isCancelled()) return;
			try
			{
				this.future.complete(this.task.get());
			}
			catch (Throwable throwable)
			{
				this.future.completeExceptionally(throwable);
			}
		}
	}
}
