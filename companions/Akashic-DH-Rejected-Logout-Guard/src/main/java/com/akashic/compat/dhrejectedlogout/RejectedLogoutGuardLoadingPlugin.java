package com.akashic.compat.dhrejectedlogout;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

@IFMLLoadingPlugin.Name("Akashic DH Rejected Logout Guard")
@IFMLLoadingPlugin.MCVersion("1.7.10")
@IFMLLoadingPlugin.SortingIndex(2002000000)
public final class RejectedLogoutGuardLoadingPlugin implements IFMLLoadingPlugin {
    private static final Logger LOG = LogManager.getLogger("AkashicDHRejectedLogoutGuard");

    @Override
    public String[] getASMTransformerClass() {
        return new String[] { RejectedLogoutGuardTransformer.class.getName() };
    }

    @Override public String getModContainerClass() { return null; }
    @Override public String getSetupClass() { return null; }
    @Override public String getAccessTransformerClass() { return null; }

    @Override
    public void injectData(Map<String, Object> data) {
        LOG.info("Akashic DH Rejected Logout Guard 1.0.0 armed (server-only; Forge/Crucible 1.7.10).");
    }
}
