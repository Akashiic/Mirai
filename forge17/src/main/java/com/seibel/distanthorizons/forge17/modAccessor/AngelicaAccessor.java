package com.seibel.distanthorizons.forge17.modAccessor;

import java.awt.Color;
import java.nio.FloatBuffer;

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IAngelicaAccessor;
import com.seibel.distanthorizons.forge17.modAccessor.exceptions.AngelicaVersionGuiException;
import cpw.mods.fml.common.versioning.VersionParser;
import cpw.mods.fml.common.versioning.VersionRange;
import net.coderbot.iris.rendertarget.IRenderTargetExt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;

import com.gtnewhorizons.angelica.config.AngelicaConfig;
import com.gtnewhorizons.angelica.glsm.GLStateManager;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.versioning.ArtifactVersion;
import cpw.mods.fml.common.versioning.DefaultArtifactVersion;

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
		return ((IRenderTargetExt) framebuffer).iris$getDepthTextureId(); 
	}

	@Override
    public boolean canDoFadeShader() { return AngelicaConfig.enableIris; }

	@Override
    public Color getFogColor() 
    {
        // JOML belongs to separate namespaces in Angelica and our shaded jar.
        // Read the current Angelica context through its JDK buffer contract;
        // absolute reads preserve the state owner's buffer position and limit.
        final FloatBuffer color = GLStateManager.getFogState().getFogColorBuffer();
        return new Color(
            Math.max(0.0f, Math.min(1.0f, color.get(0))),
            Math.max(0.0f, Math.min(1.0f, color.get(1))),
            Math.max(0.0f, Math.min(1.0f, color.get(2))));
    }
	
	//endregion
	
	
	
}
