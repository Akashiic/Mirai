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

package com.seibel.distanthorizons.common.render.openGl.postProcessing;

import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLVertexBuffer;
import com.seibel.distanthorizons.common.render.openGl.util.GlResourceCleanup;
import com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute.GlAbstractVertexAttribute;
import com.seibel.distanthorizons.common.render.openGl.glObject.vertexAttribute.GlVertexPointer;
import org.lwjgl.opengl.GL11;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Renders a full-screen textured quad to the screen. 
 * Used in composite / deferred rendering (IE fog).
 * This singleton belongs to the Minecraft GL context, not to a level. All post-processing
 * passes share it across dimension changes. Normal context destruction releases its GPU
 * objects; free() is used for failed initialization rollback and must not be called by
 * an individual level's asynchronous close path.
 */
public class GlScreenQuad
{
	public static GlScreenQuad INSTANCE = new GlScreenQuad();
	
	private static final float[] BOX_VERTICES = {
			-1, -1,
			1, -1,
			1, 1,
		
			-1, -1,
			1, 1,
			-1, 1,
	};
	
	private GLVertexBuffer boxBuffer;
	private GlAbstractVertexAttribute va;
	private boolean init = false;
	private Throwable initFailure;

	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private GlScreenQuad() { }
	
	public void init()
	{
		if (this.init) return;
		if (this.initFailure != null)
		{
			throw new IllegalStateException("Quad initialization previously failed; free() is required before retry.", this.initFailure);
		}
		try
		{
			this.va = GlAbstractVertexAttribute.create();
			this.va.bind();
			this.va.setVertexAttribute(0, 0, GlVertexPointer.addVec2Pointer(false));
			this.va.completeAndCheck(Float.BYTES * 2);
			this.createBuffer();
			this.init = true;
		}
		catch (RuntimeException | Error failure)
		{
			GlResourceCleanup.suppress(failure, this::free);
			this.initFailure = failure;
			throw failure;
		}
	}

	/** Requires the owning GL thread; shared users must be idle before an explicit reset. */
	public void free()
	{
		GLVertexBuffer oldBuffer = this.boxBuffer;
		GlAbstractVertexAttribute oldVa = this.va;
		this.boxBuffer = null;
		this.va = null;
		this.init = false;
		this.initFailure = null;
		GlResourceCleanup.run(
				() -> { if (oldBuffer != null) oldBuffer.close(); },
				() -> { if (oldVa != null) oldVa.free(); });
	}
	private void createBuffer()
	{
		ByteBuffer buffer = ByteBuffer.allocateDirect(BOX_VERTICES.length * Float.BYTES);
		buffer.order(ByteOrder.nativeOrder());
		buffer.asFloatBuffer().put(BOX_VERTICES);
		buffer.rewind();
		
		this.boxBuffer = new GLVertexBuffer(false);
		this.boxBuffer.bind();
		this.boxBuffer.uploadBuffer(buffer, BOX_VERTICES.length, EDhApiGpuUploadMethod.DATA, BOX_VERTICES.length * Float.BYTES);
	}
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	public void render()
	{
		this.init();
		
		this.boxBuffer.bind();
		
		this.va.bind();
		this.va.bindBufferToAllBindingPoints(this.boxBuffer.getId());
		
		LWJGL.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
		
		LWJGL.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
		
		this.boxBuffer.unbind();
	}
	
	//endregion
	
	
	
}
