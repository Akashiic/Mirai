package com.seibel.distanthorizons.core.util.threading;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * A {@link ConcurrentLinkedDeque} with an O(1) size. <br><br>
 *
 * {@code ConcurrentLinkedDeque.size()} walks the whole deque, and the task picker asks for the LOD builder's queue
 * size on every pick (overload checks) while holding its lock, so with thousands of queued chunks every pick paid
 * an O(n) walk. The counter changes only after an element really entered or left (removals use the boolean
 * results), so it never drifts; like {@code size()} it is only weakly consistent while other threads mutate it.
 */
public final class CountedTaskDeque<E> implements Iterable<E>
{
	private final ConcurrentLinkedDeque<E> deque = new ConcurrentLinkedDeque<>();
	private final AtomicInteger size = new AtomicInteger();


	public void add(E element)
	{
		this.deque.add(element);
		this.size.incrementAndGet();
	}

	public void addFirst(E element)
	{
		this.deque.addFirst(element);
		this.size.incrementAndGet();
	}

	public E poll()
	{
		E element = this.deque.poll();
		if (element != null)
		{
			this.size.decrementAndGet();
		}
		return element;
	}

	/** removes each matching element once; only elements this call actually removed are counted */
	public boolean removeIf(Predicate<? super E> filter)
	{
		boolean removed = false;
		for (E element : this.deque)
		{
			if (filter.test(element) && this.deque.removeFirstOccurrence(element))
			{
				this.size.decrementAndGet();
				removed = true;
			}
		}
		return removed;
	}

	public void clear()
	{
		while (this.poll() != null) { }
	}

	/** O(1); never negative even while an add's increment is still in flight */
	public int size() { return Math.max(0, this.size.get()); }

	public boolean isEmpty() { return this.deque.isEmpty(); }

	/** read-only: a removal through the iterator would bypass the counter */
	@Override
	public Iterator<E> iterator()
	{
		Iterator<E> iterator = this.deque.iterator();
		return new Iterator<E>()
		{
			@Override public boolean hasNext() { return iterator.hasNext(); }
			@Override public E next() { return iterator.next(); }
		};
	}

}
