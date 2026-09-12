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

package com.seibel.distanthorizons.common.render.openGl.glObject;

import com.seibel.distanthorizons.common.render.openGl.glObject.enums.GLEnums;
import com.seibel.distanthorizons.common.render.openGl.util.GlResourceCleanup;
import com.seibel.distanthorizons.core.logging.mirai.MiraiGlStateTiming;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhMetaRenderer;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftGLWrapper;
import org.lwjgl.opengl.*;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

public class GLState implements IDhMetaRenderer.RenderPassState
{
	private static final MinecraftGLWrapper GLMC = MinecraftGLWrapper.INSTANCE;
	private final MiraiGlStateTiming.Scope miraiScope;
	
	
	public int program;
	public int vao;
	public int vbo;
	public int ebo;
	public int fbo;
	public int readFbo;
	public float[] clearColor;
	public double clearDepth;
	public int texture2D;
	/** IE: GL_TEXTURE0, GL_TEXTURE1, etc. */
	public int activeTextureNumber;
	public int texture0;
	public int texture1;
	public int texture2;
	public int texture3;
	public int frameBufferTexture0;
	public int frameBufferTexture1;
	public int frameBufferDepthTexture;
	private Attachment colorAttachment0;
	private Attachment colorAttachment1;
	private Attachment depthAttachment;
	public boolean blend;
	public boolean scissor;
	public int blendEqRGB;
	public int blendEqAlpha;
	public int blendSrcColor;
	public int blendSrcAlpha;
	public int blendDstColor;
	public int blendDstAlpha;
	public boolean depth;
	public boolean writeToDepthBuffer;
	public int depthFunc;
	public boolean stencil;
	public int stencilFunc;
	public int stencilRef;
	public int stencilMask;
	public int[] view;
	public boolean cull;
	public int cullMode;
	public int polyMode;
	
	
	
	public GLState() { this(MiraiGlStateTiming.Scope.UNSCOPED); }
	public GLState(MiraiGlStateTiming.Scope scope)
	{
		this.miraiScope = scope == null ? MiraiGlStateTiming.Scope.UNSCOPED : scope;
		this.saveState();
	}
	
	public void saveState()
	{
		long miraiTimingStart = MiraiGlStateTiming.start();
		try
		{
			this.program = LWJGL.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		this.vao = LWJGL.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
		this.vbo = LWJGL.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
		this.ebo = LWJGL.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
		
		this.fbo = LWJGL.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		this.readFbo = LWJGL.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		this.clearColor = new float[4];
		LWJGL.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, this.clearColor);
		this.clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
		
		this.texture2D = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		this.activeTextureNumber = LWJGL.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		
		try
		{
			GLMC.glActiveTexture(GL13.GL_TEXTURE0);
			this.texture0 = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

			GLMC.glActiveTexture(GL13.GL_TEXTURE1);
			this.texture1 = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

			GLMC.glActiveTexture(GL13.GL_TEXTURE2); // problem with Iris
			this.texture2 = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

			GLMC.glActiveTexture(GL13.GL_TEXTURE3);
			this.texture3 = LWJGL.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

		}
		catch (RuntimeException | Error failure)
		{
			GlResourceCleanup.suppress(failure, () -> GLMC.glActiveTexture(this.activeTextureNumber));
			throw failure;
		}
		GLMC.glActiveTexture(this.activeTextureNumber);

		if (this.fbo != 0)
		{
			this.colorAttachment0 = new Attachment(GL30.GL_COLOR_ATTACHMENT0);
			this.colorAttachment1 = new Attachment(GL30.GL_COLOR_ATTACHMENT1);
			this.depthAttachment = new Attachment(GL30.GL_DEPTH_ATTACHMENT);
			this.frameBufferTexture0 = this.colorAttachment0.id;
			this.frameBufferTexture1 = this.colorAttachment1.id;
			this.frameBufferDepthTexture = this.depthAttachment.id;
		}
		else
		{
			// Default framebuffer attachments cannot be queried or replaced.
			this.colorAttachment0 = this.colorAttachment1 = this.depthAttachment = null;
			this.frameBufferTexture0 = this.frameBufferTexture1 = this.frameBufferDepthTexture = 0;
		}

		this.blend = LWJGL.glIsEnabled(GL11.GL_BLEND);
		this.scissor = LWJGL.glIsEnabled(GL11.GL_SCISSOR_TEST);
		this.blendEqRGB = LWJGL.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
		this.blendEqAlpha = LWJGL.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
		this.blendSrcColor = LWJGL.glGetInteger(GL14.GL_BLEND_SRC_RGB);
		this.blendSrcAlpha = LWJGL.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
		this.blendDstColor = LWJGL.glGetInteger(GL14.GL_BLEND_DST_RGB);
		this.blendDstAlpha = LWJGL.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
		this.depth = LWJGL.glIsEnabled(GL11.GL_DEPTH_TEST);
		this.writeToDepthBuffer = LWJGL.glGetInteger(GL11.GL_DEPTH_WRITEMASK) == GL11.GL_TRUE;
		this.depthFunc = LWJGL.glGetInteger(GL11.GL_DEPTH_FUNC);
		this.stencil = LWJGL.glIsEnabled(GL11.GL_STENCIL_TEST);
		this.stencilFunc = LWJGL.glGetInteger(GL11.GL_STENCIL_FUNC);
		this.stencilRef = LWJGL.glGetInteger(GL11.GL_STENCIL_REF);
		this.stencilMask = LWJGL.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
		this.view = new int[4];
		LWJGL.glGetIntegerv(GL11.GL_VIEWPORT, this.view);
		this.cull = LWJGL.glIsEnabled(GL11.GL_CULL_FACE);
		this.cullMode = LWJGL.glGetInteger(GL11.GL_CULL_FACE_MODE);
			this.polyMode = LWJGL.glGetInteger(GL11.GL_POLYGON_MODE);
		}
		finally
		{
			MiraiGlStateTiming.finishSave(this.miraiScope, miraiTimingStart);
		}
	}
	
	@Override
	public void close()
	{
		long miraiTimingStart = MiraiGlStateTiming.start();
		try
		{
			// Try every independent group even if one GL call throws. No clears belong
		// here: a failed DH pass must never clear the caller's framebuffer.
		GlResourceCleanup.run(
			this::restoreFramebuffers,
			this::restoreObjects,
			this::restoreTextures,
			this::restoreBlendAndScissor,
			this::restoreDepthAndBlendFunctions,
			this::restoreStencil,
			this::restoreViewportAndCull,
			() -> LWJGL.glClearColor(this.clearColor[0], this.clearColor[1], this.clearColor[2], this.clearColor[3]),
				() -> LWJGL.glClearDepth(this.clearDepth));
		}
		finally
		{
			MiraiGlStateTiming.finishRestore(this.miraiScope, miraiTimingStart);
		}
	}

	private void restoreFramebuffers()
	{
		int draw = this.fbo != 0 && GL30.glIsFramebuffer(this.fbo) ? this.fbo : 0;
		int read = this.readFbo != 0 && GL30.glIsFramebuffer(this.readFbo) ? this.readFbo : 0;
		GlResourceCleanup.run(
			() -> GLMC.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw),
			() -> GLMC.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read),
			() -> {
				if (draw != 0 && LWJGL.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == draw)
				{
					GlResourceCleanup.run(this.colorAttachment0::restore, this.colorAttachment1::restore, this.depthAttachment::restore);
				}
			});
	}

	private void restoreObjects()
	{
		GlResourceCleanup.run(
			() -> LWJGL.glBindVertexArray(GL30.glIsVertexArray(this.vao) ? this.vao : 0),
			() -> LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, GL15.glIsBuffer(this.vbo) ? this.vbo : 0),
			() -> LWJGL.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, GL15.glIsBuffer(this.ebo) ? this.ebo : 0),
			() -> LWJGL.glUseProgram(GL20.glIsProgram(this.program) ? this.program : 0));
	}

	private void restoreTextures()
	{
		GlResourceCleanup.run(
			() -> this.restoreTexture(GL13.GL_TEXTURE0, this.texture0),
			() -> this.restoreTexture(GL13.GL_TEXTURE1, this.texture1),
			() -> this.restoreTexture(GL13.GL_TEXTURE2, this.texture2),
			() -> this.restoreTexture(GL13.GL_TEXTURE3, this.texture3),
			() -> this.restoreTexture(this.activeTextureNumber, this.texture2D),
			() -> GLMC.glActiveTexture(this.activeTextureNumber));
	}

	private void restoreTexture(int unit, int texture)
	{
		GLMC.glActiveTexture(unit);
		GLMC.glBindTexture(LWJGL.glIsTexture(texture) ? texture : 0);
	}

	private void restoreBlendAndScissor()
	{
		if (this.blend)
		{
			GLMC.enableBlend();
		}
		else
		{
			GLMC.disableBlend();
		}
		
		if (this.scissor)
		{
			GLMC.enableScissorTest();
		}
		else
		{
			GLMC.disableScissorTest();
		}
	}

	private void restoreDepthAndBlendFunctions()
	{
		if (this.writeToDepthBuffer)
		{
			GLMC.enableDepthMask();
		}
		else
		{
			GLMC.disableDepthMask();
		}
		
		GLMC.glBlendFunc(this.blendSrcColor, this.blendDstColor);
		LWJGL.glBlendEquationSeparate(this.blendEqRGB, this.blendEqAlpha);
		GLMC.glBlendFuncSeparate(this.blendSrcColor, this.blendDstColor, this.blendSrcAlpha, this.blendDstAlpha);
		
		if (this.depth)
		{
			GLMC.enableDepthTest();
		}
		else
		{
			GLMC.disableDepthTest();
		}
		GLMC.glDepthFunc(this.depthFunc);
	}

	private void restoreStencil()
	{
		if (this.stencil)
		{
			LWJGL.glEnable(GL11.GL_STENCIL_TEST);
		}
		else
		{
			LWJGL.glDisable(GL11.GL_STENCIL_TEST);
		}
		LWJGL.glStencilFunc(this.stencilFunc, this.stencilRef, this.stencilMask);
	}

	private void restoreViewportAndCull()
	{
		GLMC.glViewport(this.view[0], this.view[1], this.view[2], this.view[3]);
		if (this.cull)
		{
			GLMC.enableFaceCulling();
		}
		else
		{
			GLMC.disableFaceCulling();
		}
		LWJGL.glCullFace(this.cullMode);
		LWJGL.glPolygonMode(GL11.GL_FRONT_AND_BACK, this.polyMode);
	}

	/** Preserve texture mip levels/cube faces and renderbuffer attachments. */
	private static final class Attachment
	{
		private final int attachment;
		private final int type;
		private final int id;
		private final int level;
		private final int textureTarget;

		private Attachment(int attachment)
		{
			this.attachment = attachment;
			this.type = LWJGL.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
			this.id = this.type == GL11.GL_NONE ? 0 : LWJGL.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
			if (this.type == GL11.GL_TEXTURE)
			{
				this.level = LWJGL.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
				int cubeFace = LWJGL.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE);
				this.textureTarget = cubeFace == 0 ? GL11.GL_TEXTURE_2D : cubeFace;
			}
			else
			{
				this.level = 0;
				this.textureTarget = GL11.GL_TEXTURE_2D;
			}
		}

		private void restore()
		{
			int currentType = LWJGL.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, this.attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
			int currentId = currentType == GL11.GL_NONE ? 0 : LWJGL.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, this.attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
			// DH normally binds its own FBO. Avoid reattaching untouched foreign
			// array/layered textures through a 2D-only operation.
			if (currentType == this.type && currentId == this.id)
			{
				if (this.type != GL11.GL_TEXTURE) return;
				int currentLevel = LWJGL.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, this.attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
				int currentCubeFace = LWJGL.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, this.attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_CUBE_MAP_FACE);
				int savedCubeFace = this.textureTarget == GL11.GL_TEXTURE_2D ? 0 : this.textureTarget;
				if (currentLevel == this.level && currentCubeFace == savedCubeFace) return;
			}
			if (this.type == GL30.GL_RENDERBUFFER)
			{
				GL30.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER, this.attachment, GL30.GL_RENDERBUFFER, this.id);
			}
			else if (this.type == GL11.GL_NONE || LWJGL.glIsTexture(this.id))
			{
				LWJGL.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, this.attachment, this.textureTarget, this.id, this.level);
			}
		}
	}

	@Override
	public String toString()
	{
		return "GLState{" +
			"program=" + this.program + ", vao=" + this.vao + ", vbo=" + this.vbo + ", ebo=" + this.ebo + ", fbo=" + this.fbo +
			", text=" + GLEnums.getString(this.texture2D) + "@" + this.activeTextureNumber + ", text0=" + GLEnums.getString(this.texture0) +
			", FB text0=" + this.frameBufferTexture0 +
			", FB text1=" + this.frameBufferTexture1 +
			", FB depth=" + this.frameBufferDepthTexture +
			", blend=" + this.blend + ", scissor=" + this.scissor + ", blendMode=" + GLEnums.getString(this.blendSrcColor) + "," + GLEnums.getString(this.blendDstColor) +
			", depth=" + this.depth +
			", depthFunc=" + GLEnums.getString(this.depthFunc) + ", stencil=" + this.stencil +
			", stencilFunc=" + GLEnums.getString(this.stencilFunc) + ", stencilRef=" + this.stencilRef + ", stencilMask=" + this.stencilMask +
			", view={x:" + this.view[0] + ", y:" + this.view[1] +
			", w:" + this.view[2] + ", h:" + this.view[3] + "}" + ", cull=" + this.cull +
			", cullMode=" + GLEnums.getString(this.cullMode) + ", polyMode=" + GLEnums.getString(this.polyMode) +
			'}';
	}
	
	
	
}
