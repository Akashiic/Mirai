package com.seibel.distanthorizons.common.util;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;

/** Applies the shader requirement on transitions without rewriting the user's saved setting. */
public final class ShaderTransparencyPolicy
{
    private final ConfigEntry<EDhApiTransparency> transparency;
    private boolean shaderActive;
    private EDhApiTransparency previousApiValue;

    public ShaderTransparencyPolicy(ConfigEntry<EDhApiTransparency> transparency)
    {
        this.transparency = transparency;
    }

    /** Must run on the render thread before beginning a frame. */
    public void update(boolean shaderActive)
    {
        if (shaderActive == this.shaderActive)
        {
            return;
        }

        // ConfigEntry installs the value before notifying listeners. Commit the
        // transition first so a failing listener does not overwrite our saved
        // override again on every subsequent frame.
        this.shaderActive = shaderActive;

        if (shaderActive)
        {
            this.previousApiValue = this.transparency.getApiValue();
            this.transparency.setApiValue(EDhApiTransparency.COMPLETE);
        }
        else
        {
            // A different API owner may have changed the override in the meantime.
            // Do not replace its value when our shader requirement ends.
            if (this.transparency.getApiValue() == EDhApiTransparency.COMPLETE)
            {
                this.transparency.setApiValue(this.previousApiValue);
            }
            this.previousApiValue = null;
        }
    }
}
