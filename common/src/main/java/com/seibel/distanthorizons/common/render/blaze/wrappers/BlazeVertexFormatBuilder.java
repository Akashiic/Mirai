package com.seibel.distanthorizons.common.render.blaze.wrappers;

#if MC_VER <= MC_1_21_10
public class BlazeVertexFormatBuilder {}
#else

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.seibel.distanthorizons.common.render.blaze.util.BlazeDhVertexFormatUtil;

public class BlazeVertexFormatBuilder
{
	private final VertexFormat.Builder builder;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public BlazeVertexFormatBuilder()
	{
		#if MC_VER <= MC_26_1_2
		this.builder = VertexFormat.builder();
		#else
		this.builder = VertexFormat.builder(0);
		#endif
	}
	
	//endregion
	
	
	
	//==========//
	// building //
	//==========//
	//region
	
	public BlazeVertexFormatBuilder add(String name, VertexFormatElement element)
	{
		#if MC_VER <= MC_26_1_2
		this.builder.add(name, element);
		#else
		this.builder.addAttribute(name, element.format());
		#endif
		
		return this;
	}
	
	public VertexFormat build() { return this.builder.build(); }
	
	//endregion
	
	
	
}
#endif