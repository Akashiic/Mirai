package com.seibel.distanthorizons.forge17.modAccessor;

import java.awt.Color;

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IAngelicaAccessor;
import com.seibel.distanthorizons.forge17.modAccessor.exceptions.AngelicaVersionGuiException;
import cpw.mods.fml.common.versioning.VersionParser;
import cpw.mods.fml.common.versioning.VersionRange;
import net.coderbot.iris.rendertarget.IRenderTargetExt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;

import com.gtnewhorizons.angelica.config.AngelicaConfig;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.versioning.ArtifactVersion;
import cpw.mods.fml.common.versioning.DefaultArtifactVersion;

import static com.seibel.distanthorizons.lwjgl.LWJGLServiceProvider.LWJGL;

public class AngelicaAccessor implements IAngelicaAccessor 
{
	
	public static final String ANGELICA_MOD_ID = "angelica";
	public static final String MINIMUM_ANGELICA_VERSION = "2.1.5";
	public static final VersionRange SUPPORTED_ANGELICA_RANGE = VersionParser
		.parseRange("[" + MINIMUM_ANGELICA_VERSION + ",)");
	
	@Override
	public String getModName() { return ANGELICA_MOD_ID; }
	
	
	
	//====================//
	// version validation //
	//====================//
	//region
	
	public void throwIfUnsupportedAngelicaVersion()
		throws IllegalStateException, AngelicaVersionGuiException
	{
		ModContainer angelica = Loader.instance()
			.getIndexedModList()
			.get(ANGELICA_MOD_ID);
		
		if (angelica == null)
		{
			throw new IllegalStateException("Angelica mod container could not be found.");
		}
		
		String installedVersion = angelica.getVersion();
		ArtifactVersion installedArtifactVersion = new DefaultArtifactVersion(installedVersion);
		if (SUPPORTED_ANGELICA_RANGE.containsVersion(installedArtifactVersion))
		{
			return;
		}
		
		throw new AngelicaVersionGuiException(installedVersion, MINIMUM_ANGELICA_VERSION);
	}
	
	//endregion
	
	
	
	//==================//
	// accessor methods //
	//==================//
	//region
	
	@Override
	public int getDepthTextureId() 
    {
	    final Framebuffer framebuffer = Minecraft.getMinecraft().getFramebuffer();
		// IRenderTargetExt is only mixed in when AngelicaConfig.enableIris is on (the same gate as canDoFadeShader)
		if (!(framebuffer instanceof IRenderTargetExt))
		{
			return -1;
		}
		return ((IRenderTargetExt) framebuffer).iris$getDepthTextureId(); 
	}

	@Override
    public boolean canDoFadeShader() { return AngelicaConfig.enableIris; }

	@Override
    public Color getFogColor() 
    {
        // A plain GL query: Angelica redirects it to GLSM, which answers GL_FOG_COLOR from the tracked
        // fogState.getFogColorBuffer() on the render thread (2.2.20 and 2.2.40+ alike), i.e. the same floats the
        // old GLStateManager.getFogState() read returned. That method was removed in 2.2.40 and only worked through
        // Angelica's legacy bytecode rewriter. Alpha stays implicit (1.0), as before.
        final float[] color = new float[4];
        LWJGL.glGetFloatv(GL11.GL_FOG_COLOR, color);
        return new Color(
            Math.max(0.0f, Math.min(1.0f, color[0])),
            Math.max(0.0f, Math.min(1.0f, color[1])),
            Math.max(0.0f, Math.min(1.0f, color[2])));
    }
	
	//endregion
	
	
	
}
