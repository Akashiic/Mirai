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

package com.seibel.distanthorizons.common.render.openGl.postProcessing.ssao;

import com.seibel.distanthorizons.common.render.openGl.glObject.GLState;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.common.render.openGl.util.GlResourceCleanup;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.logging.mirai.MiraiGpuProfiler;
import com.seibel.distanthorizons.core.logging.mirai.MiraiGpuQueryRing.Stage;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhSsaoRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.nio.ByteBuffer;

/**
 * Handles adding SSAO via {@link GlDhSSAOShader} and {@link GlDhSSAOApplyShader}. <br><br>
 * 
 * {@link GlDhSSAOShader} - draws the SSAO to a texture. <br>
 * {@link GlDhSSAOApplyShader} - draws the SSAO texture to DH's FrameBuffer. <br>
 */
public class GlDhSSAORenderer implements IDhSsaoRenderer
{
	public static GlDhSSAORenderer INSTANCE = new GlDhSSAORenderer();
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	
	private boolean init = false;
	private Throwable initFailure;
	
	private int width = -1;
	private int height = -1;
	private int ssaoFramebuffer = -1;
	
	private int ssaoTexture = -1;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	private GlDhSSAORenderer() { }
	
	public void init()
	{
		if (this.init) return;
		if (this.initFailure != null)
		{
			throw new IllegalStateException("Post-processing initialization previously failed; free() is required before retry.", this.initFailure);
		}
		try
		{
			GlDhSSAOShader.INSTANCE.init();
			GlDhSSAOApplyShader.INSTANCE.init();
			this.init = true;
		}
		catch (RuntimeException | Error failure)
		{
			GlResourceCleanup.suppress(failure, this::free);
			this.initFailure = failure;
			throw failure;
		}
	}
	
	private void createFramebuffer(int width, int height)
	{
		try
		{
			this.freeFramebuffer();

			this.ssaoFramebuffer = LWJGL.glGenFramebuffers();
			if (this.ssaoFramebuffer == 0) throw new IllegalStateException("Unable to create post-processing GL resource.");
			GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.ssaoFramebuffer);

			this.ssaoTexture = GLMC.glGenTextures();
			if (this.ssaoTexture == 0) throw new IllegalStateException("Unable to create post-processing GL resource.");
			{
				GLMC.glBindTexture(this.ssaoTexture);
				LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R16F, width, height, 0, GL11.GL_RED, GL30.GL_HALF_FLOAT, (ByteBuffer) null);
				LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
				LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

				// disable mip-mapping since DH is just going to draw straight to the screen
				LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
				LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
			}

			LWJGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.ssaoTexture, 0);
		
		}
		catch (RuntimeException | Error failure)
		{
			GlResourceCleanup.suppress(failure, this::freeFramebuffer);
			this.initFailure = failure;
			this.init = false;
			throw failure;
		}
	}
	
	
	
	//========//
	// render //
	//========//
	//region
	
	@Override
	public void render(RenderParams renderParams)
	{
		try(GLState state = new GLState())
		{
			this.init();
			
			// resize the framebuffer if necessary
			int width = MC_RENDER.getTargetFramebufferViewportWidth();
			int height = MC_RENDER.getTargetFramebufferViewportHeight();
			if (this.width != width || this.height != height)
			{
				this.createFramebuffer(width, height);
				this.width = width;
				this.height = height;
			}
			
			int rawStage = MiraiGpuProfiler.beginStage(Stage.SSAO_RAW);
			try
			{
				GlDhSSAOShader.INSTANCE.frameBuffer = this.ssaoFramebuffer;
				GlDhSSAOShader.INSTANCE.setProjectionMatrix(renderParams.dhProjectionMatrix);
				GlDhSSAOShader.INSTANCE.render(renderParams);
			}
			finally { MiraiGpuProfiler.endStage(rawStage); }

			int applyStage = MiraiGpuProfiler.beginStage(Stage.SSAO_APPLY);
			try
			{
				GlDhSSAOApplyShader.INSTANCE.ssaoTexture = this.ssaoTexture;
				GlDhSSAOApplyShader.INSTANCE.render(renderParams);
			}
			finally { MiraiGpuProfiler.endStage(applyStage); }
		}
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	public void free()
	{
		this.init = false;
		this.initFailure = null;
		GlResourceCleanup.run(
				GlDhSSAOShader.INSTANCE::free,
				GlDhSSAOApplyShader.INSTANCE::free,
				this::freeFramebuffer);
	}

	private void freeFramebuffer()
	{
		this.width = -1;
		this.height = -1;
		final int oldSsaoFramebuffer = this.ssaoFramebuffer;
		this.ssaoFramebuffer = -1;
		final int oldSsaoTexture = this.ssaoTexture;
		this.ssaoTexture = -1;
		GlResourceCleanup.run(
				() -> { if (oldSsaoFramebuffer > 0) LWJGL.glDeleteFramebuffers(oldSsaoFramebuffer); },
				() -> { if (oldSsaoTexture > 0) GLMC.glDeleteTextures(oldSsaoTexture); });
	}

	
	//endregion
	
	
	
}
