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

package com.seibel.distanthorizons.common.render.openGl.postProcessing.fog;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam;
import com.seibel.distanthorizons.common.render.openGl.glObject.GLState;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.common.render.openGl.util.GlResourceCleanup;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhFogRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.nio.ByteBuffer;

/**
 * Handles adding SSAO via {@link GlDhFogShader} and {@link GlDhFogApplyShader}. <br><br>
 * 
 * {@link GlDhFogShader} - draws the Fog to a texture. <br>
 * {@link GlDhFogApplyShader} - draws the Fog texture to DH's FrameBuffer. <br>
 */
public class GlDhFogRenderer implements IDhFogRenderer
{
	public static GlDhFogRenderer INSTANCE = new GlDhFogRenderer();
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	
	private boolean init = false;
	private Throwable initFailure;
	
	private int width = -1;
	private int height = -1;
	private int fogFramebuffer = -1;
	
	private int fogTexture = -1;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	private GlDhFogRenderer() { }
	
	public void init()
	{
		if (this.init) return;
		if (this.initFailure != null)
		{
			throw new IllegalStateException("Post-processing initialization previously failed; free() is required before retry.", this.initFailure);
		}
		try
		{
			GlDhFogShader.INSTANCE.init();
			GlDhFogApplyShader.INSTANCE.init();
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

			this.fogFramebuffer = LWJGL.glGenFramebuffers();
			if (this.fogFramebuffer == 0) throw new IllegalStateException("Unable to create post-processing GL resource.");
			GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fogFramebuffer);

			this.fogTexture = GLMC.glGenTextures();
			if (this.fogTexture == 0) throw new IllegalStateException("Unable to create post-processing GL resource.");
			{
				GLMC.glBindTexture(this.fogTexture);
				LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA16, width, height, 0, GL11.GL_RGBA, GL12.GL_UNSIGNED_SHORT_4_4_4_4, (ByteBuffer) null);
				LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
				LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
				LWJGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.fogTexture, 0);

				// disable mip-mapping since DH is just going to draw straight to the screen
				LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
				LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
			}
		
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
	public void render(RenderParams renderParams, DhApiFogRenderParam fogRenderParams)
	{
		// GLState needed in MC 1.16.5 probably due to MC not manually setting each GL state they need before the next rendering step
		try (GLState state = new GLState())
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
			
			GlDhFogShader.INSTANCE.frameBuffer = this.fogFramebuffer;
			GlDhFogShader.INSTANCE.prepUniformObjects(renderParams.dhMvmProjMatrix, fogRenderParams);
			GlDhFogShader.INSTANCE.render(renderParams);
			
			GlDhFogApplyShader.INSTANCE.fogTexture = this.fogTexture;
			GlDhFogApplyShader.INSTANCE.render(renderParams);
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
				GlDhFogShader.INSTANCE::free,
				GlDhFogApplyShader.INSTANCE::free,
				this::freeFramebuffer);
	}

	private void freeFramebuffer()
	{
		this.width = -1;
		this.height = -1;
		final int oldFogFramebuffer = this.fogFramebuffer;
		this.fogFramebuffer = -1;
		final int oldFogTexture = this.fogTexture;
		this.fogTexture = -1;
		GlResourceCleanup.run(
				() -> { if (oldFogFramebuffer > 0) LWJGL.glDeleteFramebuffers(oldFogFramebuffer); },
				() -> { if (oldFogTexture > 0) GLMC.glDeleteTextures(oldFogTexture); });
	}

	
	//endregion
	
	
	
}
