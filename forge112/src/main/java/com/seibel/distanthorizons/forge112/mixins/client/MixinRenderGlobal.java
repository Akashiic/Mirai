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

package com.seibel.distanthorizons.forge112.mixins.client;

import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

@Mixin(value = RenderGlobal.class, priority = 900)
public class MixinRenderGlobal
{
	@Shadow private WorldClient world;

	@Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I", at = @At("HEAD"), cancellable = true)
	private void renderChunkLayer(BlockRenderLayer blockLayerIn, double partialTicks, int pass, Entity entityIn, CallbackInfoReturnable<Integer> cir)
	{
		// Cancelling CUTOUT RenderLayer will cause crash
		if (Config.Client.Advanced.Debugging.lodOnlyMode.get() && blockLayerIn != BlockRenderLayer.CUTOUT){
			cir.cancel();
		}
		
		if (blockLayerIn == BlockRenderLayer.SOLID)
		{
			float[] mcProjMatrixRaw = new float[16];
			LWJGL.glGetFloatv(GL11.GL_PROJECTION_MATRIX, mcProjMatrixRaw);
			ClientApi.RENDER_STATE.mcProjectionMatrix = new DhMat4f(mcProjMatrixRaw);
			ClientApi.RENDER_STATE.mcProjectionMatrix.transpose();
			
			float[] mcModelViewRaw = new float[16];
			LWJGL.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, mcModelViewRaw);
			ClientApi.RENDER_STATE.mcModelViewMatrix = new DhMat4f(mcModelViewRaw);
			ClientApi.RENDER_STATE.mcModelViewMatrix.transpose();
			
			ClientApi.RENDER_STATE.partialTickTime = (float) partialTicks;
			ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapperIfDifferent(ClientApi.RENDER_STATE.clientLevelWrapper, this.world);
			
			ClientApi.INSTANCE.renderLods();
			
			//Some 1.12.2 rendering mods breaks if we don't unbind buffers
			LWJGL.glBindVertexArray(0);
			LWJGL.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
			LWJGL.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
			LWJGL.glUseProgram(0);
		}
	}
}