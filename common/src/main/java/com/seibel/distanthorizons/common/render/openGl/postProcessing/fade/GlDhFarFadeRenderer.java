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

package com.seibel.distanthorizons.common.render.openGl.postProcessing.fade;

import com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer;
import com.seibel.distanthorizons.common.render.openGl.glObject.GLState;
import com.seibel.distanthorizons.core.logging.mirai.MiraiGlStateTiming;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import com.seibel.distanthorizons.common.render.openGl.util.GlResourceCleanup;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhFarFadeRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.nio.ByteBuffer;

/**
 * Handles fading MC and DH together via {@link GlDhFarFadeShader} and {@link GlDhFarFadeApplyShader}. <br><br>
 * 
 * {@link GlDhFarFadeShader} - draws the Fade to a texture. <br>
 * {@link GlDhFarFadeApplyShader} - draws the Fade texture to DH's framebuffer. <br>
 * This renderer and its framebuffer belong to the Minecraft GL context and are reused
 * across levels. The apply shader is also shared with GlVanillaFadeRenderer. Failed work
 * is rolled back on the GL thread; a level unload must not destroy these shared objects.
 */
public class GlDhFarFadeRenderer implements IDhFarFadeRenderer
{
	
	public static GlDhFarFadeRenderer INSTANCE = new GlDhFarFadeRenderer();
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	
	
	private boolean init = false;
	private Throwable initFailure;
	
	private int width = -1;
	private int height = -1;
	private int fadeFramebuffer = -1;
	
	private int fadeTexture = -1;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private GlDhFarFadeRenderer() { }
	
	public void init()
	{
		if (this.init) return;
		if (this.initFailure != null)
		{
			throw new IllegalStateException("Post-processing initialization previously failed; free() is required before retry.", this.initFailure);
		}
		try
		{
			GlDhFarFadeShader.INSTANCE.init();
			GlDhFarFadeApplyShader.INSTANCE.init();
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

			this.fadeFramebuffer = LWJGL.glGenFramebuffers();
			if (this.fadeFramebuffer == 0) throw new IllegalStateException("Unable to create post-processing GL resource.");
			GLMC.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fadeFramebuffer);




			this.fadeTexture = GLMC.glGenTextures();
			if (this.fadeTexture == 0) throw new IllegalStateException("Unable to create post-processing GL resource.");
			{
				GLMC.glBindTexture(this.fadeTexture);
				LWJGL.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA16, width, height, 0, GL11.GL_RGBA, GL12.GL_UNSIGNED_SHORT_4_4_4_4, (ByteBuffer) null);
				LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
				LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

				// disable mip-mapping since DH is just going to draw straight to the screen
				LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
				LWJGL.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
			}

			LWJGL.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, this.fadeTexture, 0);
			int framebufferStatus = LWJGL.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
			if (framebufferStatus != GL30.GL_FRAMEBUFFER_COMPLETE)
			{
				throw new IllegalStateException("Far-fade framebuffer is incomplete: 0x" + Integer.toHexString(framebufferStatus));
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
	
	//endregion
	
	
	
	//========//
	// render //
	//========//
	//region
	
	@Override 
	public void render(RenderParams renderParams)
	{
		if (this.initFailure != null)
		{
			throw new IllegalStateException("Far-fade composition previously failed; restart Minecraft before retry.", this.initFailure);
		}
		try (GLState state = new GLState(MiraiGlStateTiming.Scope.FAR_FADE))
		{
			int width = MC_RENDER.getTargetFramebufferViewportWidth();
			int height = MC_RENDER.getTargetFramebufferViewportHeight();
			if (width <= 0 || height <= 0)
			{
				throw new IllegalStateException("Far-fade requires a positive viewport, got " + width + "x" + height);
			}
			int drawFramebuffer = GlDhMetaRenderer.INSTANCE.getActiveFramebufferId();
			if (drawFramebuffer <= 0)
			{
				throw new IllegalStateException("Far-fade requires an active DH destination framebuffer.");
			}
			this.init();
			
			// resize the framebuffer if necessary
			if (this.width != width || this.height != height)
			{
				this.createFramebuffer(width, height);
				this.width = width;
				this.height = height;
			}
			
			
			GlDhFarFadeShader.INSTANCE.frameBuffer = this.fadeFramebuffer;
			GlDhFarFadeShader.INSTANCE.setProjectionMatrix(renderParams);
			GlDhFarFadeShader.INSTANCE.render(renderParams);
			
			GlDhFarFadeApplyShader.INSTANCE.fadeTexture = this.fadeTexture;
			GlDhFarFadeApplyShader.INSTANCE.readFramebuffer = GlDhFarFadeShader.INSTANCE.frameBuffer;
			GlDhFarFadeApplyShader.INSTANCE.drawFramebuffer = drawFramebuffer;
			GlDhFarFadeApplyShader.INSTANCE.render(renderParams);
		}
		catch (RuntimeException | Error failure)
		{
			GlResourceCleanup.suppress(failure, this::free);
			this.initFailure = failure;
			// This pass also composes Minecraft's sky. Continuing would copy an
			// uncomposed or stale texture; the caller must abort before its apply.
			throw failure;
		}
	}
	
	//emdregion
	
	
	
	/** Requires the owning GL thread with no active post-processing pass. */
	public void free()
	{
		this.init = false;
		this.initFailure = null;
		GlResourceCleanup.run(
				GlDhFarFadeShader.INSTANCE::free,
				GlDhFarFadeApplyShader.INSTANCE::free,
				this::freeFramebuffer);
	}

	private void freeFramebuffer()
	{
		this.width = -1;
		this.height = -1;
		final int oldFadeFramebuffer = this.fadeFramebuffer;
		this.fadeFramebuffer = -1;
		final int oldFadeTexture = this.fadeTexture;
		this.fadeTexture = -1;
		GlResourceCleanup.run(
				() -> { if (oldFadeFramebuffer > 0) LWJGL.glDeleteFramebuffers(oldFadeFramebuffer); },
				() -> { if (oldFadeTexture > 0) GLMC.glDeleteTextures(oldFadeTexture); });
	}
}
