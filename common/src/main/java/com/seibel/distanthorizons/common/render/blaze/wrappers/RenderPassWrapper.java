package com.seibel.distanthorizons.common.render.blaze.wrappers;

#if MC_VER <= MC_1_21_10
public class RenderPassWrapper {}
#else

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.IDhBlazeTexture;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeUniformBufferWrapper;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

#if MC_VER <= MC_26_1_2
#else
import com.mojang.blaze3d.IndexType;
#endif

public class RenderPassWrapper implements AutoCloseable
{
	public static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	
	private final RenderPass renderPass;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public RenderPassWrapper(
		final Supplier<String> nameGetterFunc, 
		final IDhBlazeTexture colorTexture, 
		final IDhBlazeTexture depthTexture)
	{
		#if MC_VER <= MC_26_1_2
		this.renderPass = COMMAND_ENCODER.createRenderPass(
			nameGetterFunc,
			colorTexture.getTextureView(),
			/*optionalClearColorAsInt*/ OptionalInt.empty(),
			depthTexture.getTextureView(),
			/*optionalDepthValueAsDouble*/ OptionalDouble.empty());
		#else
		this.renderPass = COMMAND_ENCODER.createRenderPass(
			nameGetterFunc,
			colorTexture.getTextureView(),
			/*clearColor*/ Optional.empty(),
			depthTexture.getTextureView(),
			/*clearDepth*/ OptionalDouble.empty());
		#endif
	}
	
	//endregion
	
	
	
	//=======//
	// setup //
	//=======//
	//region
	
	public void bindTexture(
		final String name, 
		final IDhBlazeTexture textureView) 
	{
		this.renderPass.bindTexture(
			name,
			textureView.getTextureView(),
			textureView.getTextureSampler());
	}
	
	public void setVertexBuffer(GpuBuffer buffer)
	{
		#if MC_VER <= MC_26_1_2
		this.renderPass.setVertexBuffer(/*slot*/0, buffer);
		#else
		this.renderPass.setVertexBuffer(/*slot*/0, buffer.slice());
		#endif
	}
	
	public void setIndexBuffer(GpuBuffer buffer)
	{
		#if MC_VER <= MC_26_1_2
		this.renderPass.setIndexBuffer(buffer, VertexFormat.IndexType.INT);
		#else
		this.renderPass.setIndexBuffer(buffer, IndexType.INT);
		#endif
	}
	
	public void setUniform(String uniformName, BlazeUniformBufferWrapper uniformBuffer) { this.renderPass.setUniform(uniformName, uniformBuffer.getGpuBuffer()); }
	
	public void setPipeline(RenderPipeline pipeline) { this.renderPass.setPipeline(pipeline); }
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	public void draw(int vertexCount)
	{
		#if MC_VER <= MC_26_1_2
		this.renderPass.draw(0, vertexCount);
		#else
		this.renderPass.draw(vertexCount, /*instanceCount*/1, /*firstVertex*/0, /*firstInstance*/0);
		#endif
	}
	
	public void drawIndexed(int indexCount)
	{ 
		#if MC_VER <= MC_26_1_2
		this.renderPass.drawIndexed(
			/*indexStart*/ 0,
			/*firstIndex*/0,
			indexCount,
			/*instanceCount*/1);
		#else
		this.renderPass.drawIndexed(
			indexCount,
			/*instanceCount*/1,
			/*firstVertex*/0,
			/*vertexOffset*/0,
			/*firstInstance*/0);
		#endif
		
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override 
	public void close() { this.renderPass.close(); }
	
	//endregion
	
	
	
}
#endif