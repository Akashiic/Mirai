package com.seibel.distanthorizons.forge17;

import java.nio.FloatBuffer;

import com.seibel.distanthorizons.common.commonMixins.MixinVanillaFogCommon;
import com.seibel.distanthorizons.common.render.openGl.util.GlResourceCleanup;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IAngelicaAccessor;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.lwjgl.GL32;
import net.minecraft.client.Minecraft;

import org.joml.Matrix4f;

import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.util.math.DhMat4f;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

/** Legacy Forge render hooks; LWJGL calls remain visible to Angelica's redirectors. */
public class RenderHelper 
{
	private static final IAngelicaAccessor ANGELICA_ACCESSOR = ModAccessorInjector.INSTANCE.get(IAngelicaAccessor.class);
	
	private static DhMat4f modelViewMatrix;
	private static DhMat4f projectionMatrix;
	
	
	
	//=================//
	// matrix handling //
	//=================//
	//region
	
	public static DhMat4f getModelViewMatrix() { return new DhMat4f(modelViewMatrix); }
	public static DhMat4f getProjectionMatrix() { return new DhMat4f(projectionMatrix); }
	
	public static void setModelViewMatrixFromBuffer(FloatBuffer modelviewBuffer)
	{ modelViewMatrix = McObjectConverter.convert(new Matrix4f(modelviewBuffer)); }
	public static void setProjectionMatrixFromBuffer(FloatBuffer projectionBuffer)
	{ projectionMatrix = McObjectConverter.convert(new Matrix4f(projectionBuffer)); }
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	public static void renderLods() 
    {
        ClientApi.RENDER_STATE.mcModelViewMatrix = getModelViewMatrix();
        ClientApi.RENDER_STATE.mcProjectionMatrix = getProjectionMatrix();
        ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapper(Minecraft.getMinecraft().theWorld);
	    ClientApi.RENDER_STATE.partialTickTime = MinecraftRenderWrapper.INSTANCE.getPartialTickTime();
		
	    // only crash during development
	    if (ModInfo.IS_DEV_BUILD)
	    {
		    ClientApi.RENDER_STATE.canRenderOrThrow();
	    }
	    
	    
		
		try (LegacyRenderState state = new LegacyRenderState())
		{
			if (ANGELICA_ACCESSOR == null)
			{
				LWJGL.glDisable(GL32.GL_ALPHA_TEST);
			}
			LWJGL.glClearColor(1, 1, 1, 0.0F);
			ClientApi.INSTANCE.renderLods();
		}

    }
	
	/** Only legacy states touched outside LodRenderer's complete pass scope. */
	private static final class LegacyRenderState implements AutoCloseable
	{
		private final boolean alphaTest = LWJGL.glIsEnabled(GL32.GL_ALPHA_TEST);
		private final boolean blend = LWJGL.glIsEnabled(GL32.GL_BLEND);
		private final int depthFunc = LWJGL.glGetInteger(GL32.GL_DEPTH_FUNC);
		private final int activeTexture = LWJGL.glGetInteger(GL32.GL_ACTIVE_TEXTURE);
		private final int boundTexture = LWJGL.glGetInteger(GL32.GL_TEXTURE_BINDING_2D);
		private final float[] clearColor = new float[4];

		private LegacyRenderState() { LWJGL.glGetFloatv(GL32.GL_COLOR_CLEAR_VALUE, this.clearColor); }

		@Override
		public void close()
		{
			GlResourceCleanup.run(
				() -> LWJGL.glDepthFunc(this.depthFunc),
				() -> { if (this.alphaTest) LWJGL.glEnable(GL32.GL_ALPHA_TEST); else LWJGL.glDisable(GL32.GL_ALPHA_TEST); },
				() -> { if (this.blend) LWJGL.glEnable(GL32.GL_BLEND); else LWJGL.glDisable(GL32.GL_BLEND); },
				() -> { LWJGL.glActiveTexture(this.activeTexture); LWJGL.glBindTexture(GL32.GL_TEXTURE_2D, this.boundTexture); },
				() -> LWJGL.glClearColor(this.clearColor[0], this.clearColor[1], this.clearColor[2], this.clearColor[3]));
		}
	}

	// TODO why do we need to disable depth here?
    public static void beforeWater() { LWJGL.glDepthMask(true); }
	
    public static void renderFade(boolean translucent) 
    {
        if (ANGELICA_ACCESSOR != null
            && !ANGELICA_ACCESSOR.canDoFadeShader()) 
		{
			return;
        }
        ClientApi.RENDER_STATE.mcModelViewMatrix = getModelViewMatrix();
        ClientApi.RENDER_STATE.mcProjectionMatrix = getProjectionMatrix();
        ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapper(Minecraft.getMinecraft().theWorld);
	    
		try (LegacyRenderState state = new LegacyRenderState())
		{
			if (ANGELICA_ACCESSOR == null)
			{
				LWJGL.glDisable(GL32.GL_ALPHA_TEST);
			}
			if (translucent)
			{
				ClientApi.INSTANCE.renderFadeTransparent();
			}
			else
			{
				ClientApi.INSTANCE.renderFadeOpaque();
			}
		}

    }
	
    public static void renderDeferredLods() 
    {
        if (ANGELICA_ACCESSOR == null) 
		{
            return;
        }
        ClientApi.RENDER_STATE.mcModelViewMatrix = getModelViewMatrix();
        ClientApi.RENDER_STATE.mcProjectionMatrix = getProjectionMatrix();
        ClientApi.RENDER_STATE.clientLevelWrapper = ClientLevelWrapper.getWrapper(Minecraft.getMinecraft().theWorld);

        ClientApi.INSTANCE.renderDeferredLodsForShaders();
    }
	
	//endregion
	
	
	
	
	
	/** 
	 * Unbinding is necessary to prevent 
	 * a crash if DH is enabled while MC starts loading into the world 
	 */
    public static void UnbindAfterTesselatorDraw() { LWJGL.glBindBuffer(GL32.GL_ARRAY_BUFFER, 0); }

	
	
	//=====//
	// fog //
	//=====//
	//region
	
    public static void enableFog() { LWJGL.glEnable(GL32.GL_FOG); }

    public static void disableFogDuringSetup() 
    {
        if (!MixinVanillaFogCommon.cancelFog()) 
		{
            return;
        }
	    LWJGL.glDisable(GL32.GL_FOG);
		
        // Extremely high values cause issues, but 15 mebi-meters out should be 
	    // practically infinite For Angelica
	    LWJGL.glFogf(GL32.GL_FOG_START, 1024 * 1024 * 15);
	    LWJGL.glFogf(GL32.GL_FOG_END, 1024 * 1024 * 16);
    }
	
	public static void disableFogDuringRender(int cap)
	{
		// Cancel enabling fog if needed
		if (MixinVanillaFogCommon.cancelFog()
			&& cap == GL32.GL_FOG)
		{
			return;
		}
		
		LWJGL.glEnable(cap);
	}
	
	//endregion
	
	
	
	
	
}
