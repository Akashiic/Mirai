package com.seibel.distanthorizons.common.render.openGl;

import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine;
import com.seibel.distanthorizons.common.render.openGl.generic.GlGenericObjectRenderer;
import com.seibel.distanthorizons.common.render.openGl.generic.GlGenericObjectVertexContainer;
import com.seibel.distanthorizons.common.render.openGl.glObject.GlDummyUniformData;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.antialiasing.GlDhTaaRenderer;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.fade.GlDhFarFadeRenderer;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.fade.GlVanillaFadeRenderer;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.fog.GlDhFogRenderer;
import com.seibel.distanthorizons.common.render.openGl.postProcessing.ssao.GlDhSSAORenderer;
import com.seibel.distanthorizons.common.render.openGl.test.GlTestTriangleRenderer;
import com.seibel.distanthorizons.core.render.EDhRenderDepth;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.*;

public class GlDhRenderApiDefinition extends AbstractDhRenderApiDefinition
{
	//=========//
	// getters //
	//=========//
	//region
	
	public String getEngineName() { return "OpenGL"; }
	
	public EDhRenderDepth getRenderDepth() 
	{
		// reversed Z shouldn't be supported on OpenGL due
		// to that breaking Iris shaders
		return EDhRenderDepth.FORWARD_Z; 
	}
	
	public EDhApiRenderingApi getRenderApi() { return EDhApiRenderingApi.OPEN_GL; }
	public EDhApiRenderingEngine getRenderingEngine() { return EDhApiRenderingEngine.OPEN_GL; }
	public boolean isNativeRenderer() { return true; }
	
	//endregion
	
	
	
	//============//
	// singletons //
	//============//
	//region
	
	@Override public IDhMetaRenderer getMetaRenderer() { return GlDhMetaRenderer.INSTANCE; }
	@Override public IDhTerrainRenderer getTerrainRenderer() { return GlDhTerrainRenderer.INSTANCE; }
	@Override public IDhSsaoRenderer getSsaoRenderer() { return GlDhSSAORenderer.INSTANCE; }
	@Override public IDhFogRenderer getFogRenderer() { return GlDhFogRenderer.INSTANCE; }
	@Override public IDhFarFadeRenderer getFarFadeRenderer() { return GlDhFarFadeRenderer.INSTANCE; }
	@Override public IDhAntiAliasRenderer getAntiAliasRenderer() { return GlDhTaaRenderer.INSTANCE; }
	@Override public AbstractDebugWireframeRenderer getDebugWireframeRenderer() { return GlDhDebugWireframeRenderer.INSTANCE; }
	
	@Override public IDhVanillaFadeRenderer getVanillaFadeRenderer() { return GlVanillaFadeRenderer.INSTANCE; }
	@Override public IDhTestTriangleRenderer getTestTriangleRenderer() { return GlTestTriangleRenderer.INSTANCE; }
	
	//endregion
	
	
	
	//===========//
	// factories //
	//===========//
	//region
	
	@Override public IDhGenericRenderer createGenericRenderer() { return new GlGenericObjectRenderer(); }
	
	@Override public IVertexBufferWrapper createVboWrapper(String name) { return new GLVertexBuffer(); }
	@Override public ILodContainerUniformBufferWrapper createLodContainerUniformWrapper() { return new GlDummyUniformData(); }
	@Override public IDhGenericObjectVertexBufferContainer createGenericVboContainer() { return new GlGenericObjectVertexContainer(); }
	
	//endregion
	
	
	
}
