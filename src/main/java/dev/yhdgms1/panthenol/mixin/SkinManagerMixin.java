package dev.yhdgms1.panthenol.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import dev.yhdgms1.panthenol.Panthenol;
import dev.yhdgms1.panthenol.Runtime;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.SkinManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Mixin(SkinManager.class)
public abstract class SkinManagerMixin {
    @Shadow
    abstract CompletableFuture<PlayerSkin> registerTextures(UUID uuid, MinecraftProfileTextures textures);

    @Inject(method = "getOrLoad", at = @At("HEAD"), cancellable = true)
    private void panthenol$loadFromScript(GameProfile profile, CallbackInfoReturnable<CompletableFuture<PlayerSkin>> cir) {
        try {
            MinecraftProfileTextures textures = Runtime.resolveTextures(profile);

            if (textures == null) {
                // Runtime unavailable — leave vanilla path.
                return;
            }

            cir.setReturnValue(this.registerTextures(profile.getId(), textures));
        } catch (Throwable t) {
            Panthenol.LOGGER.error("Panthenol skin hook failed for {}", profile != null ? profile.getName() : "?", t);

            try {
                cir.setReturnValue(this.registerTextures(profile.getId(), MinecraftProfileTextures.EMPTY));
            } catch (Throwable ignored) {
                // leave vanilla if EMPTY registration also fails
            }
        }
    }
}
