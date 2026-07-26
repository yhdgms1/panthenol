package dev.yhdgms1.panthenol;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

@Mod(value = Panthenol.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Panthenol.MODID, value = Dist.CLIENT)
public class PanthenolClient {
    public PanthenolClient() {
        Runtime.start();
    }

    @SubscribeEvent
    static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        Runtime.onWorldLogin();
    }
}
