package dev.yhdgms1.panthenol;

import net.fabricmc.api.ClientModInitializer;

public class PanthenolFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PanthenolCommon.initClient();
    }
}
