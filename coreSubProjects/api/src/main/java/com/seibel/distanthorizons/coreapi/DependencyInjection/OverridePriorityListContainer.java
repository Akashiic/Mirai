/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.coreapi.DependencyInjection;

import java.util.List;
import java.util.Collections;
import com.seibel.distanthorizons.api.interfaces.override.IDhApiOverrideable;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IBindable;

import java.util.ArrayList;

/**
 * Contains a list of overrides and their priorities.
 *
 * @author James Seibel
 * @version 2022-9-5
 */
public class OverridePriorityListContainer implements IBindable
{
	/**
	 * Sorted highest priority to lowest. <br>
	 * Immutable snapshot replaced on every write: overrides are read every frame by the render thread
	 * while API users (e.g. Angelica) may bind from other threads (upstream 4539c2cae fixed the same race with a lock).
	 */
	private volatile List<OverridePriorityPair> overridePairList = Collections.emptyList();
	
	
	/** Doesn't do any validation. Writers are serialized by {@link OverrideInjector}. */
	public synchronized void addOverride(IDhApiOverrideable override)
	{
		OverridePriorityPair priorityPair = new OverridePriorityPair(override, override.getPriority());
		ArrayList<OverridePriorityPair> newList = new ArrayList<>(this.overridePairList);
		newList.add(priorityPair);
		
		// same stable sort and comparator as before, so the order of the list is unchanged
		newList.sort((x, y) -> Integer.compare(y.priority, x.priority));
		this.overridePairList = Collections.unmodifiableList(newList);
	}
	
	/** @return true if the override was removed from the list, false otherwise. */
	public synchronized boolean removeOverride(IDhApiOverrideable override)
	{
		ArrayList<OverridePriorityPair> newList = new ArrayList<>(this.overridePairList);
		boolean overrideRemoved = newList.removeIf((pair) -> pair.override.equals(override));
		if (overrideRemoved)
		{
			this.overridePairList = Collections.unmodifiableList(newList);
		}
		return overrideRemoved;
	}
	
	public synchronized boolean clear()
	{
		boolean objectsRemoved = (this.overridePairList.size() > 0);
		this.overridePairList = Collections.emptyList();
		return objectsRemoved;
	}
	
	
	
	// getters (each reads one snapshot) //
	
	public IDhApiOverrideable getOverrideWithLowestPriority()
	{
		List<OverridePriorityPair> list = this.overridePairList;
		if (list.size() == 0)
		{
			return null;
		}
		else
		{
			// last item should have the lowest priority
			return list.get(list.size() - 1).override;
		}
	}
	public IDhApiOverrideable getOverrideWithHighestPriority()
	{
		List<OverridePriorityPair> list = this.overridePairList;
		if (list.size() != 0)
		{
			return list.get(0).override;
		}
		else
		{
			return null;
		}
	}
	public IDhApiOverrideable getCoreOverride()
	{
		List<OverridePriorityPair> list = this.overridePairList;
		int lastIndex = list.size() - 1;
		if (lastIndex >= 0 && list.get(lastIndex).priority == OverrideInjector.CORE_PRIORITY)
		{
			return list.get(lastIndex).override;
		}
		else
		{
			return null;
		}
	}
	/** Returns null if no override with the given priority is found */
	public IDhApiOverrideable getOverrideWithPriority(int priority)
	{
		for (OverridePriorityPair pair : this.overridePairList)
		{
			if (pair.priority == priority)
			{
				return pair.override;
			}
		}
		
		return null;
	}
	
	
	
	private class OverridePriorityPair
	{
		public final IDhApiOverrideable override;
		public final int priority;
		
		public OverridePriorityPair(IDhApiOverrideable newOverride, int newPriority)
		{
			this.override = newOverride;
			this.priority = newPriority;
		}
		
	}
	
}
