package com.seibel.distanthorizons;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.IEarlyMixinLoader;
import com.gtnewhorizon.gtnhmixins.builders.IMixins;
import com.seibel.distanthorizons.forge17.Mixins;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

/**
 * Defines our mixins. <br>
 * The class name is a contract: Angelica (Iris) enables its Distant Horizons integration only when
 * <code>com.seibel.distanthorizons.DistantHorizonsTweaker</code> exists (upstream dfca6541).
 */
public class DistantHorizonsTweaker implements IEarlyMixinLoader, IFMLLoadingPlugin 
{
    @Override
    public String getMixinConfig() { return "DistantHorizons.forge.mixins.json"; }

    @Override
    // The JSON intentionally contains no static lists: UniMixins replaces only
    // its common list, so a parallel JSON client list would bypass exclusions.
    public List<String> getMixins(Set<String> loadedCoreMods) { return IMixins.getEarlyMixins(Mixins.class, loadedCoreMods); }

    @Override
    public String[] getASMTransformerClass() { return new String[0]; }

    @Override
    public String getModContainerClass() { return null; }

    @Override
    public String getSetupClass() { return null; }

    @Override
    public void injectData(Map<String, Object> data) { }

    @Override
    public String getAccessTransformerClass() { return null; }
	
	
	
}
