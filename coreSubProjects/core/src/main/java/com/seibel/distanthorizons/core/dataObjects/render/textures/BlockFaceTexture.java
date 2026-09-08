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

package com.seibel.distanthorizons.core.dataObjects.render.textures;

import com.seibel.distanthorizons.coreapi.util.ColorUtil;

/**
 * A small texture representing one face of a block state,
 * baked from the block's model so LODs can show the block's
 * actual texture instead of a single flat color. <br><br>
 */
public class BlockFaceTexture
{
	public final int width;
	public final int height;
	/**
	 * Pixel colors in ARGB order. <br>
	 * Indexed via <code>(v * width) + u</code> where (0,0) is the face's top left pixel.
	 */
	public final int[] argbPixels;
	
	/** 
	 * true if these pixels should be multiplied by a position specific tint before rendering (IE grass or leaf colors)
	 * @deprecated currently all textures are treated as tinted = true
	 */
	@Deprecated
	public final boolean tinted;
	public final boolean uploadAsColorRatio;
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	public static BlockFaceTexture createSolidColor(int argbColor)
	{ return new BlockFaceTexture(1, 1, new int[] { argbColor }, false, false); }
	
	public static BlockFaceTexture createErrorGridTexture()
	{
		// similar pink/black grid used by source engine games for missing textures
		int[] argbPixels = new int[] 
		{
			ColorUtil.HOT_PINK, // top left
			ColorUtil.BLACK, // top right
			
			ColorUtil.BLACK, // bottom left
			ColorUtil.HOT_PINK, // bottom right
		};
		return new BlockFaceTexture(2, 2, argbPixels, false, false); 
	}
	
	public static BlockFaceTexture createTexture(int width, int height, int[] argbPixels, boolean tinted)
	{ return new BlockFaceTexture(width, height, argbPixels, tinted, true); }
	
	private BlockFaceTexture(int width, int height, int[] argbPixels, boolean tinted, boolean uploadAsColorRatio)
	{
		this.width = width;
		this.height = height;
		this.argbPixels = argbPixels;
		this.tinted = tinted;
		this.uploadAsColorRatio = uploadAsColorRatio;
	}
	
	//endregion
	
	
	
}
