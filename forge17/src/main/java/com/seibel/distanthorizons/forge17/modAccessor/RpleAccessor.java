package com.seibel.distanthorizons.forge17.modAccessor;

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IRpleAccessor;
import net.minecraft.client.renderer.texture.DynamicTexture;

import com.falsepattern.rple.api.client.RPLELightMapAPI;

/**
 * 
 * RPLE = Right Proper Lighting Engine <Br>
 * adds colored lighting
 */
public class RpleAccessor extends RpleCommonAccessor implements IRpleAccessor
{
    private final DynamicTexture lightmapTexture;
	private final int[] lightmapColors;
	
	@Override 
	public String getModName() { return "rple"; }
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
    public RpleAccessor() 
    {
        this.lightmapTexture = new DynamicTexture(16, 16);
	    this.lightmapColors = this.lightmapTexture.getTextureData();
    }
	
	//endregion
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	//endregion
	
	
	
	//==========//
	// lightmap //
	//==========//
	//region
	
	@Override
	public void updateLightmap() 
    {
        RPLELightMapAPI.getMixedLightMapData(this.lightmapColors);
	    this.lightmapTexture.updateDynamicTexture();
    }
	
	@Override
	public int getLightmapTextureId() { return this.lightmapTexture.getGlTextureId(); }
	
	//endregion
	
	
	
}
