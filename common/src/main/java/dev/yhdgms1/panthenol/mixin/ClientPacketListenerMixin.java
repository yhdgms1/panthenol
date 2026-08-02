package dev.yhdgms1.panthenol.mixin;

import dev.yhdgms1.panthenol.Runtime;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clears the JS texture memo on world join. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleLogin", at = @At("RETURN"))
    private void panthenol$onWorldLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        Runtime.onWorldLogin();
    }
}
