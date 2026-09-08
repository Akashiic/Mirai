package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.stacks.FogStateStack;
import org.joml.Vector3d;

/** Headless fixture ONLY: chooses a real Angelica FogState without a GL context. */
public final class GLStateManager {
    public static FogStateStack current;

    public static FogStateStack getFogState() {
        if (current == null) throw new AssertionError("Fixture context was not selected");
        return current;
    }

    // Real Angelica's public descriptor, needed for the old-JAR negative control.
    public static Vector3d getFogColor() {
        return getFogState().getFogColor();
    }
}
