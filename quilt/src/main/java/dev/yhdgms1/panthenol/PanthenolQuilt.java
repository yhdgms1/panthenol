package dev.yhdgms1.panthenol;

import net.fabricmc.api.ClientModInitializer;

/**
 * Quilt entry via Fabric Loader interop (Quilt loads Fabric client entrypoints).
 */
public class PanthenolQuilt implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PanthenolCommon.initClient();
    }
}
