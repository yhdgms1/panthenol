package dev.yhdgms1.panthenol;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class PanthenolNeoForge {
    public PanthenolNeoForge(IEventBus modEventBus) {
        PanthenolCommon.initClient();
    }
}
