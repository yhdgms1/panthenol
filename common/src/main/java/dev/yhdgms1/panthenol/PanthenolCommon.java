package dev.yhdgms1.panthenol;

import dev.yhdgms1.panthenol.platform.Services;

/**
 * Shared bootstrap used by every loader entrypoint.
 */
public final class PanthenolCommon {
    private PanthenolCommon() {
    }

    public static void initClient() {
        Runtime.start();
    }
}
