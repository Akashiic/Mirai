package com.seibel.distanthorizons.forge17.mixin;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.EntityLivingBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.seibel.distanthorizons.forge17.RenderHelper;

@Mixin(RenderGlobal.class)
public class MixinRenderGlobal 
{
	@Inject(method = "sortAndRender", at = @At("HEAD"))
	void renderLods(
		EntityLivingBase p_72719_1_, int renderPass, double p_72719_3_,
		CallbackInfoReturnable<Integer> cir)
	{
		// Solid render pass
		if (renderPass == 0)
		{
			RenderHelper.renderLods();
		}
		
		// Translucent render pass
		if (renderPass == 1)
		{
			RenderHelper.beforeWater();
		}
	}
	
	@Inject(method = "sortAndRender", at = @At("TAIL"))
	void renderLodsFade(
		EntityLivingBase p_72719_1_, int renderPass, double p_72719_3_,
		CallbackInfoReturnable<Integer> cir)
	{
		// Solid render pass
		if (renderPass == 0)
		{
			RenderHelper.renderFade(false);
		}
		
		// Translucent render pass
		if (renderPass == 1)
		{
			RenderHelper.renderFade(true);
		}
	}

	/**
	 * Upstream ef720c8e. When a shaderpack (Angelica/Iris) asks DH to defer translucent rendering,
	 * the TRANSPARENT LOD pass must run here, inside MC's translucent pass and after the lightmap is bound;
	 * otherwise distant water is never drawn. {@link RenderHelper#renderDeferredLods()} returns immediately
	 * when nothing is deferred, so the shader-less path is unchanged.
	 * The target still exists inside Angelica's sortAndRender overwrite (2.2.20 and 2.2.43).
	 */
	@Inject(
		method = "sortAndRender",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/EntityRenderer;enableLightmap(D)V",
			shift = At.Shift.AFTER),
		// a renderer that rewrites sortAndRender without this call must cost distant water under shaders, never startup
		require = 0)
	void renderDeferredLodsDuringTranslucentSetup(
		EntityLivingBase p_72719_1_, int renderPass, double p_72719_3_,
		CallbackInfoReturnable<Integer> cir)
	{
		// Translucent render pass
		if (renderPass == 1)
		{
			RenderHelper.renderDeferredLods();
		}
	}
	
	
	
}
