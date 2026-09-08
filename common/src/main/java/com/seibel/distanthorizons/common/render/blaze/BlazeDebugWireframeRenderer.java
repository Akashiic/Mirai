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

package com.seibel.distanthorizons.common.render.blaze;

#if MC_VER <= MC_1_21_10
public class BlazeDebugWireframeRenderer {}

#else

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.seibel.distanthorizons.common.render.blaze.util.BlazeDhVertexFormatUtil;
import com.seibel.distanthorizons.common.render.blaze.wrappers.BlazeVertexFormatBuilder;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeUniformBufferWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.EDhRenderDepth;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Handles rendering the wireframe particles 
 * that are used for seeing what the system's doing.
 */
public class BlazeDebugWireframeRenderer extends AbstractDebugWireframeRenderer
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final AbstractDhRenderApiDefinition RENDER_API_DEF = SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class);
	
	public static BlazeDebugWireframeRenderer INSTANCE = new BlazeDebugWireframeRenderer();
	
	/** A box from 0,0,0 to 1,1,1 */
	private static final float[] BOX_VERTICES = {
		//region
		// Pos x y z
		0, 0, 0,
		1, 0, 0,
		1, 1, 0,
		0, 1, 0,
		0, 0, 1,
		1, 0, 1,
		1, 1, 1,
		0, 1, 1,
		//endregion
	};
	
	private static final int[] BOX_OUTLINE_INDICES = {
		//region
		0, 1,
		1, 2,
		2, 3,
		3, 0,
		
		4, 5,
		5, 6,
		6, 7,
		7, 4,
		
		0, 4,
		1, 5,
		2, 6,
		3, 7,
		//endregion
	};
	
	private static final DhMat4f TRANSFORM_MATRIX = new DhMat4f();
	
	
	
	// rendering setup
	private boolean init = false;
	
	private RenderPipeline pipeline;
	
	private GpuBuffer boxVertexBuffer;
	private GpuBuffer boxIndexBuffer;
	
	private final BlazeUniformBufferWrapper uniformBufferWrapper = new BlazeUniformBufferWrapper("debugWireframeUniformBlock");
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public BlazeDebugWireframeRenderer() { }
	
	public void init()
	{
		if (this.init)
		{
			return;
		}
		this.init = true;
		
		this.createPipelines();
		this.createBuffers();
		
	}
	private void createPipelines()
	{
		RenderPipelineBuilderWrapper pipelineBuilder = new RenderPipelineBuilderWrapper();
		{
			pipelineBuilder.withFaceCulling(false);
			pipelineBuilder.withDepthWrite(true);
			if (RENDER_API_DEF.getRenderDepth() == EDhRenderDepth.FORWARD_Z)
			{
				pipelineBuilder.withDepthTest(RenderPipelineBuilderWrapper.EDhDepthTest.LESS);
			}
			else
			{
				pipelineBuilder.withDepthTest(RenderPipelineBuilderWrapper.EDhDepthTest.GREATER);
			}
			pipelineBuilder.withColorWrite(true);
			pipelineBuilder.withoutBlend();
			pipelineBuilder.withPolygonMode(RenderPipelineBuilderWrapper.EDhPolygonMode.WIREFRAME);
			pipelineBuilder.withName("debug_wireframe_renderer");
			
			pipelineBuilder.withVertexShader("debug/blaze/vert");
			pipelineBuilder.withFragmentShader("debug/blaze/frag");
			
			pipelineBuilder.withUniformBuffer("uniformBlock");
			
			
			VertexFormat vertexFormat = new BlazeVertexFormatBuilder()
				.add("vPosition", BlazeDhVertexFormatUtil.FLOAT_XYZ_POS)
				.build();
			pipelineBuilder.withVertexFormat(vertexFormat);
			pipelineBuilder.withVertexMode(RenderPipelineBuilderWrapper.EDhVertexMode.LINES);
		}
		this.pipeline = pipelineBuilder.build();
		
	}
	private void createBuffers()
	{
		GpuDevice GPU_DEVICE = RenderSystem.getDevice();
		CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
		
		
		// box vertices 
		ByteBuffer boxVerticesBuffer = ByteBuffer.allocateDirect(BOX_VERTICES.length * Float.BYTES);
		boxVerticesBuffer.order(ByteOrder.nativeOrder());
		boxVerticesBuffer.asFloatBuffer().put(BOX_VERTICES);
		boxVerticesBuffer.rewind();
		
		// upload vertex data
		{
			int usage = GpuBuffer.USAGE_COPY_DST 
				| GpuBuffer.USAGE_VERTEX;
			int size = BOX_VERTICES.length * Float.BYTES;
			this.boxVertexBuffer = GPU_DEVICE.createBuffer(() -> "distantHorizons:DebugWireframeBox", usage, size);
			
			{
				int length = BOX_VERTICES.length * Float.BYTES;
				GpuBufferSlice bufferSlice = new GpuBufferSlice(this.boxVertexBuffer, /*offset*/ 0, length);
				
				ByteBuffer byteBuffer = ByteBuffer.allocateDirect(BOX_VERTICES.length * Float.BYTES);
				byteBuffer.order(ByteOrder.nativeOrder());
				byteBuffer.asFloatBuffer().put(BOX_VERTICES);
				byteBuffer.rewind();
				
				COMMAND_ENCODER.writeToBuffer(bufferSlice, byteBuffer);
			}
		}
		
		// box vertex indexes
		{
			ByteBuffer buffer = ByteBuffer.allocateDirect(BOX_OUTLINE_INDICES.length * Integer.BYTES);
			buffer.order(ByteOrder.nativeOrder());
			buffer.asIntBuffer().put(BOX_OUTLINE_INDICES);
			buffer.rewind();
			
			
			int usage = GpuBuffer.USAGE_COPY_DST 
				| GpuBuffer.USAGE_VERTEX 
				| GpuBuffer.USAGE_INDEX
				| GpuBuffer.USAGE_UNIFORM;
			this.boxIndexBuffer = GPU_DEVICE.createBuffer(() -> "DH Debug Index Buffer", usage, buffer.capacity());
			
			int offset = 0;
			GpuBufferSlice bufferSlice = new GpuBufferSlice(this.boxIndexBuffer, offset, buffer.capacity());
			COMMAND_ENCODER.writeToBuffer(bufferSlice, buffer);
		}
	}
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	@Override
	public void renderBox(Box box)
	{
		this.init();
		
		//if (BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper.isEmpty()
		//	|| BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper.isEmpty())
		//{
		//	return;
		//}
		
		// shouldn't happen, but just in case
		if (box == null)
		{
			return;
		}
		
		// delayed getters since this class may be initialized before
		// the GPU device has been set
		GpuDevice gpuDevice = RenderSystem.getDevice();
		CommandEncoder commandEncoder = gpuDevice.createCommandEncoder();
		
		
		
		// uniforms
		{
			// create data //
			DhVec3d camPos = MC_RENDER.getCameraExactPosition();
			DhVec3f camPosFloatThisFrame = new DhVec3f((float) camPos.x, (float) camPos.y, (float) camPos.z);
			
			DhMat4f boxTransform = DhMat4f.createTranslateMatrix(
				box.minPos.x - camPosFloatThisFrame.x,
				box.minPos.y - camPosFloatThisFrame.y,
				box.minPos.z - camPosFloatThisFrame.z);
			boxTransform.multiply(DhMat4f.createScaleMatrix(
				box.maxPos.x - box.minPos.x,
				box.maxPos.y - box.minPos.y,
				box.maxPos.z - box.minPos.z));
			
			TRANSFORM_MATRIX.set(this.dhMvmProjMatrixThisFrame);
			TRANSFORM_MATRIX.multiply(boxTransform);
			
			
			// upload data //
			this.uniformBufferWrapper
				.putMat4f(TRANSFORM_MATRIX) // uTransform
				.putVec4f(
					box.color.getRed() / 255.0f,
					box.color.getGreen() / 255.0f,
					box.color.getBlue() / 255.0f,
					box.color.getAlpha() / 255.0f) // uColor
				.finishAndUpload()
			;
		}
		
		
		
		// render //
		
		try (RenderPassWrapper renderPassWrapper = new RenderPassWrapper(
			this::getRenderPassName,
			BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper, 
			BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper))
		{
			// Bind instance data //
			renderPassWrapper.setUniform("uniformBlock", this.uniformBufferWrapper);
			
			renderPassWrapper.setPipeline(this.pipeline);
			renderPassWrapper.setIndexBuffer(this.boxIndexBuffer);
			
			renderPassWrapper.setVertexBuffer(this.boxVertexBuffer);
			
			renderPassWrapper.drawIndexed(BOX_OUTLINE_INDICES.length);
		}
	}
	private String getRenderPassName() { return "distantHorizons:DebugRenderer"; }
	
	//endregion
	
	
	
}
#endif