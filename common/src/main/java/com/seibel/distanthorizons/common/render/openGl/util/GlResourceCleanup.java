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

package com.seibel.distanthorizons.common.render.openGl.util;

/** Cleanup on the owning GL thread, without losing the original failure. */
public final class GlResourceCleanup
{
	private GlResourceCleanup() { }

	public static void run(Runnable... actions)
	{
		Throwable first = null;
		for (Runnable action : actions)
		{
			try { action.run(); }
			catch (RuntimeException | Error failure)
			{
				if (first == null) first = failure;
				else if (first != failure) first.addSuppressed(failure);
			}
		}
		if (first instanceof RuntimeException) throw (RuntimeException) first;
		if (first instanceof Error) throw (Error) first;
	}

	public static void suppress(Throwable primary, Runnable cleanup)
	{
		try { cleanup.run(); }
		catch (RuntimeException | Error secondary)
		{
			if (primary != secondary) primary.addSuppressed(secondary);
		}
	}
}
