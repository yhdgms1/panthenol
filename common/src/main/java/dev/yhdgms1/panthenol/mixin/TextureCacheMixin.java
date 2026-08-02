package dev.yhdgms1.panthenol.mixin;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import dev.yhdgms1.panthenol.SkinReload;
import net.minecraft.core.ClientAsset;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Drops TextureCache entries marked by {@link SkinReload}. */
@Mixin(targets = "net.minecraft.client.resources.SkinManager$TextureCache")
public abstract class TextureCacheMixin {
    @Shadow
    @Final
    private Map<String, CompletableFuture<ClientAsset.Texture>> textures;

    @Inject(method = "getOrLoad", at = @At("HEAD"))
    private void panthenol$evictIfMarked(MinecraftProfileTexture texture, CallbackInfoReturnable<CompletableFuture<ClientAsset.Texture>> cir) {
        if (texture == null) {
            return;
        }

        String hash;
        try {
            hash = texture.getHash();
        } catch (RuntimeException e) {
            return;
        }

        if (SkinReload.pollEvict(hash)) {
            this.textures.remove(hash);
        }
    }
}
