package com.akashic.compat.dhendlessids;

/**
 * Validation-only bridge. The returned array is never used as biome data;
 * patched Mirai call sites only test nullness and the vanilla length (256).
 */
public final class DHEndlessIDsCompatHooks {
    private static final byte[] VALIDATION_ONLY_BIOME_ARRAY = new byte[256];

    private DHEndlessIDsCompatHooks() {
    }

    public static byte[] validationBiomeArray(Object ignoredChunk) {
        return VALIDATION_ONLY_BIOME_ARRAY;
    }
}
