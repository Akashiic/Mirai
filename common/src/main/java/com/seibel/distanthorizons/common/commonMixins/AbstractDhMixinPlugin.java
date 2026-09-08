package com.seibel.distanthorizons.common.commonMixins;

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IImmersivePortalsAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IModChecker;

public abstract class AbstractDhMixinPlugin
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	public boolean shouldApplyDhMixin(String targetClassName, String mixinClassName)
	{
		if (mixinClassName.endsWith("MixinImmersivePortalsRenderStates"))
		{
			boolean immersivePortalsPresent = false;
			try
			{
				Thread.currentThread()
					.getContextClassLoader()
					.loadClass(IImmersivePortalsAccessor.INJECTION_CLASS);
				immersivePortalsPresent = true;
			}
			catch (ClassNotFoundException ignore) { }
			
			if (!immersivePortalsPresent)
			{
				try
				{
					Thread.currentThread()
						.getContextClassLoader()
						.loadClass(IImmersivePortalsAccessor.INJECTION_CLASS_1_16);
					immersivePortalsPresent = true;
				}
				catch (ClassNotFoundException ignore) { }
			}
			
			if (!immersivePortalsPresent)
			{
				LOGGER.info("Immersive Portals not present, skipping DH compatibility mixin.");
			}
			
			return immersivePortalsPresent;
		}
		
		return true;
	}
	
}
