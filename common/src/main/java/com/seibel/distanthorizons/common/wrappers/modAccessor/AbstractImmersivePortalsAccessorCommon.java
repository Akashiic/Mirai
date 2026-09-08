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

package com.seibel.distanthorizons.common.wrappers.modAccessor;

#if MC_VER <= MC_1_12_2
public abstract class AbstractImmersivePortalsAccessorCommon {}

#else
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.AbstractImmersivePortalsAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;

#if MC_VER > MC_1_19_2
#else
#endif

#if MC_VER < MC_1_17_1
import java.lang.reflect.Field;
#endif

public abstract class AbstractImmersivePortalsAccessorCommon extends AbstractImmersivePortalsAccessor
{
	// We don't use the fields in RenderStates because they are not volatile.
	@Nullable
	public static volatile ClientLevel actualLevel;
	@Nullable
	public static volatile DhBlockPos actualBlockPos;
	@Nullable
	public static volatile DhChunkPos actualChunkPos;
	@Nullable
	public static volatile DhVec3d actualCameraPos;
	
	
	
	@Override
	@Nullable
	public DhBlockPos getActualPlayerBlockPos() { return actualBlockPos; }
	
	@Override
	@Nullable
	public DhChunkPos getActualPlayerChunkPos() { return actualChunkPos; }
	
	@Override
	@Nullable
	public IClientLevelWrapper getActualClientLevelWrapper() { return ClientLevelWrapper.getWrapper(actualLevel, false); }
	
	@Override
	@Nullable
	public DhVec3d getActualCameraPos() { return actualCameraPos; }
	
}

#endif
