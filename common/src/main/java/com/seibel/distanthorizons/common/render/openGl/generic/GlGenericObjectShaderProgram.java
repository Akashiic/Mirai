package com.seibel.distanthorizons.common.render.openGl.generic;

import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiGenericObjectShaderProgram;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3i;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute.GlAbstractVertexAttribute;
import com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute.GlVertexPointer;
import com.seibel.distanthorizons.common.wrappers.misc.LightMapWrapper;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3f;

public class GlGenericObjectShaderProgram extends GlShaderProgram implements IDhApiGenericObjectShaderProgram
{
	public static final String VERTEX_SHADER_INSTANCED_PATH = "assets/distanthorizons/shaders/generic/gl/instanced/vert.vert";
	public static final String VERTEX_SHADER_DIRECT_PATH = "assets/distanthorizons/shaders/generic/gl/direct/vert.vert";
	public static final String FRAGMENT_SHADER_INSTANCED_PATH = "assets/distanthorizons/shaders/generic/gl/instanced/frag.frag";
	public static final String FRAGMENT_SHADER_DIRECT_PATH = "assets/distanthorizons/shaders/generic/gl/direct/frag.frag";
	
	public final GlAbstractVertexAttribute va;
	
	
	// shader uniforms
	private final int directShaderTransformUniform;
	private final int directShaderColorUniform;
	
	private final int instancedShaderOffsetChunkUniform;
	private final int instancedShaderOffsetSubChunkUniform;
	private final int instancedShaderCameraChunkPosUniform;
	private final int instancedShaderCameraSubChunkPosUniform;
	private final int instancedShaderProjectionModelViewMatrixUniform;
	
	private final int lightMapUniform;
	private final int skyLightUniform;
	private final int blockLightUniform;
	
	private final int northShadingUniform;
	private final int southShadingUniform;
	private final int eastShadingUniform;
	private final int westShadingUniform;
	private final int topShadingUniform;
	private final int bottomShadingUniform;
	
	// Render-thread-owned scratch values. Uniform uploads copy their values immediately.
	private final DhMat4f projectionMvmScratch = new DhMat4f();
	private final DhMat4f directBoxTransformScratch = new DhMat4f();
	private final DhMat4f directBoxScaleScratch = new DhMat4f();
	private final DhApiVec3i offsetChunkScratch = new DhApiVec3i();
	private final DhVec3f offsetSubChunkScratch = new DhVec3f();
	private final DhApiVec3i cameraChunkScratch = new DhApiVec3i();
	private final DhVec3f cameraSubChunkScratch = new DhVec3f();

	
	
	//=============//
	// constructor //
	//=============//
	
	public GlGenericObjectShaderProgram(boolean useInstancedRendering)
	{
		super(
				useInstancedRendering ? VERTEX_SHADER_INSTANCED_PATH : VERTEX_SHADER_DIRECT_PATH,
				useInstancedRendering ? FRAGMENT_SHADER_INSTANCED_PATH : FRAGMENT_SHADER_DIRECT_PATH,
				"vPosition"
		);
		
		this.va = GlAbstractVertexAttribute.create();
		this.va.bind();
		// Pos
		this.va.setVertexAttribute(0, 0, GlVertexPointer.addVec3Pointer(false));
		this.va.completeAndCheck(Float.BYTES * 3);
		
		this.directShaderTransformUniform = this.tryGetUniformLocation("uTransform");
		this.directShaderColorUniform = this.tryGetUniformLocation("uColor");
		
		this.instancedShaderOffsetChunkUniform = this.tryGetUniformLocation("uOffsetChunk");
		this.instancedShaderOffsetSubChunkUniform = this.tryGetUniformLocation("uOffsetSubChunk");
		this.instancedShaderCameraChunkPosUniform = this.tryGetUniformLocation("uCameraPosChunk");
		this.instancedShaderCameraSubChunkPosUniform = this.tryGetUniformLocation("uCameraPosSubChunk");
		this.instancedShaderProjectionModelViewMatrixUniform = this.tryGetUniformLocation("uProjectionMvm");
		
		this.lightMapUniform = this.getUniformLocation("uLightMap");
		this.skyLightUniform = this.getUniformLocation("uSkyLight");
		this.blockLightUniform = this.getUniformLocation("uBlockLight");
		this.northShadingUniform = this.getUniformLocation("uNorthShading");
		this.southShadingUniform = this.getUniformLocation("uSouthShading");
		this.eastShadingUniform = this.getUniformLocation("uEastShading");
		this.westShadingUniform = this.getUniformLocation("uWestShading");
		this.topShadingUniform = this.getUniformLocation("uTopShading");
		this.bottomShadingUniform = this.getUniformLocation("uBottomShading");
		
	}
	
	
	
	//=========//
	// methods //
	//=========//
	
	@Override
	public void bind(DhApiRenderParam renderEventParam)
	{
		super.bind();
		this.va.bind();
	}
	@Override
	public void unbind()
	{
		super.unbind();
		this.va.unbind();
	}
	
	@Override
	public void free()
	{
		this.va.free();
		super.free();
	}
	
	@Override
	public void bindVertexBuffer(int vbo) { this.va.bindBufferToAllBindingPoints(vbo); }
	
	@Override
	public void fillIndirectUniformData(
			DhApiRenderParam renderParameters,
			DhApiRenderableBoxGroupShading shading, IDhApiRenderableBoxGroup boxGroup,
			DhApiVec3d camPos
		)
	{
		this.projectionMvmScratch.set(renderParameters.dhProjectionMatrix);
		this.projectionMvmScratch.multiply(renderParameters.dhModelViewMatrix);
		
		super.bind();
		
		
		
		
		this.offsetChunkScratch.x = LodUtil.getChunkPosFromDouble(boxGroup.getOriginBlockPos().x);
		this.offsetChunkScratch.y = LodUtil.getChunkPosFromDouble(boxGroup.getOriginBlockPos().y);
		this.offsetChunkScratch.z = LodUtil.getChunkPosFromDouble(boxGroup.getOriginBlockPos().z);
		this.setUniform(this.instancedShaderOffsetChunkUniform, this.offsetChunkScratch);
		this.offsetSubChunkScratch.set(
				LodUtil.getSubChunkPosFromDouble(boxGroup.getOriginBlockPos().x),
				LodUtil.getSubChunkPosFromDouble(boxGroup.getOriginBlockPos().y),
				LodUtil.getSubChunkPosFromDouble(boxGroup.getOriginBlockPos().z));
		this.setUniform(this.instancedShaderOffsetSubChunkUniform, this.offsetSubChunkScratch);

		this.cameraChunkScratch.x = LodUtil.getChunkPosFromDouble(camPos.x);
		this.cameraChunkScratch.y = LodUtil.getChunkPosFromDouble(camPos.y);
		this.cameraChunkScratch.z = LodUtil.getChunkPosFromDouble(camPos.z);
		this.setUniform(this.instancedShaderCameraChunkPosUniform, this.cameraChunkScratch);
		this.cameraSubChunkScratch.set(
				LodUtil.getSubChunkPosFromDouble(camPos.x),
				LodUtil.getSubChunkPosFromDouble(camPos.y),
				LodUtil.getSubChunkPosFromDouble(camPos.z));
		this.setUniform(this.instancedShaderCameraSubChunkPosUniform, this.cameraSubChunkScratch);

		this.setUniform(this.instancedShaderProjectionModelViewMatrixUniform, this.projectionMvmScratch);
		
		this.setUniform(this.lightMapUniform, LightMapWrapper.GL_BOUND_INDEX);
		this.setUniform(this.skyLightUniform, boxGroup.getSkyLight());
		this.setUniform(this.blockLightUniform, boxGroup.getBlockLight());
		
		
		this.setUniform(this.northShadingUniform, shading.north);
		this.setUniform(this.southShadingUniform, shading.south);
		this.setUniform(this.eastShadingUniform, shading.east);
		this.setUniform(this.westShadingUniform, shading.west);
		this.setUniform(this.topShadingUniform, shading.top);
		this.setUniform(this.bottomShadingUniform, shading.bottom);
		
		
	}
	
	
	@Override 
	public void fillSharedDirectUniformData(
			DhApiRenderParam renderParameters, 
			DhApiRenderableBoxGroupShading shading, IDhApiRenderableBoxGroup boxGroup, 
			DhApiVec3d camPos)
	{
		
		this.setUniform(this.lightMapUniform, LightMapWrapper.GL_BOUND_INDEX);
		this.setUniform(this.skyLightUniform, boxGroup.getSkyLight());
		this.setUniform(this.blockLightUniform, boxGroup.getBlockLight());
		
		
		this.setUniform(this.northShadingUniform, shading.north);
		this.setUniform(this.southShadingUniform, shading.south);
		this.setUniform(this.eastShadingUniform, shading.east);
		this.setUniform(this.westShadingUniform, shading.west);
		this.setUniform(this.topShadingUniform, shading.top);
		this.setUniform(this.bottomShadingUniform, shading.bottom);
		
	}
	
	public void fillDirectUniformData(
			DhApiRenderParam renderParameters,
			IDhApiRenderableBoxGroup boxGroup, DhApiRenderableBox box, 
			DhApiVec3d camPos)
	{
		this.projectionMvmScratch.set(renderParameters.dhProjectionMatrix);
		this.projectionMvmScratch.multiply(renderParameters.dhModelViewMatrix);

		this.directBoxTransformScratch.setIdentity();
		this.directBoxTransformScratch.m03 = (float) (box.minPos.x + boxGroup.getOriginBlockPos().x - camPos.x);
		this.directBoxTransformScratch.m13 = (float) (box.minPos.y + boxGroup.getOriginBlockPos().y - camPos.y);
		this.directBoxTransformScratch.m23 = (float) (box.minPos.z + boxGroup.getOriginBlockPos().z - camPos.z);
		this.directBoxScaleScratch.setIdentity();
		this.directBoxScaleScratch.m00 = (float) (box.maxPos.x - box.minPos.x);
		this.directBoxScaleScratch.m11 = (float) (box.maxPos.y - box.minPos.y);
		this.directBoxScaleScratch.m22 = (float) (box.maxPos.z - box.minPos.z);
		this.directBoxTransformScratch.multiply(this.directBoxScaleScratch);
		this.projectionMvmScratch.multiply(this.directBoxTransformScratch);
		this.setUniform(this.directShaderTransformUniform, this.projectionMvmScratch);
		
		this.setUniform(this.directShaderColorUniform, box.color);
		
	}
	
	
	
	@Override
	public int getId() { return this.id; }
	
	/** The base DH render program should always render */
	@Override
	public boolean overrideThisFrame() { return true; }
	
}
