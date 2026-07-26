package dev.yhdgms1.panthenol.mixin;

import com.mojang.authlib.GameProfile;
import dev.yhdgms1.panthenol.Runtime;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {
    @Inject(method = "<init>", at = @At("HEAD"))
    private static void panthenol$refetchOnJoin(GameProfile profile, boolean enforeSecureChat, CallbackInfo ci) {
        Runtime.invalidate(profile);
    }
}
