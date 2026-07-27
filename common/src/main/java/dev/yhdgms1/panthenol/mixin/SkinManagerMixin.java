package dev.yhdgms1.panthenol.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import dev.yhdgms1.panthenol.Constants;
import dev.yhdgms1.panthenol.Runtime;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Mixin(SkinManager.class)
public abstract class SkinManagerMixin {
    @Shadow
    private CompletableFuture<PlayerSkin> registerTextures(UUID profileId, MinecraftProfileTextures textures) {
        throw new AssertionError();
    }

    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private void panthenol$loadFromScript(GameProfile profile, CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkin>>> cir) {
        try {
            MinecraftProfileTextures textures = Runtime.resolveTextures(profile);

            if (textures == null) {
                // Runtime unavailable — leave vanilla path.
                return;
            }

            cir.setReturnValue(this.registerTextures(profile.id(), textures).thenApply(Optional::ofNullable));
        } catch (Throwable t) {
            Constants.LOG.error("Panthenol skin hook failed for {}", profile != null ? profile.name() : "?", t);

            try {
                cir.setReturnValue(this.registerTextures(profile.id(), MinecraftProfileTextures.EMPTY)
                        .thenApply(Optional::ofNullable));
            } catch (Throwable ignored) {
                // leave vanilla if EMPTY registration also fails
            }
        }
    }
}
