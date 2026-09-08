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
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IModAccessor;
#if MC_VER == MC_1_7_10
import net.minecraft.block.Block;
#endif

/**
 * RPLE = Right Proper Lighting Engine <Br>
 * adds colored lighting to MC 1.7.10 <br><br>
 * 
 * Common light-emission queries must not require the client lightmap contract.
 * The graphical IRpleAccessor is registered separately on the physical client.
 */
public interface IRpleCommonAccessor extends IModAccessor
{
	#if MC_VER == MC_1_7_10
	int getColor(Block block, int meta);
	#endif
}
