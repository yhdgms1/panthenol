package dev.yhdgms1.panthenol.mixin;

import com.google.common.hash.Hashing;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import dev.yhdgms1.panthenol.Constants;
import dev.yhdgms1.panthenol.SkinReload;
import net.minecraft.core.ClientAsset;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Forces a real re-download when {@link SkinReload} marks a texture hash.
 * Without this, rejoin only re-hits the skin API while Minecraft keeps the old PNG.
 */
@Mixin(targets = "net.minecraft.client.resources.SkinManager$TextureCache")
public abstract class TextureCacheMixin {
    @Shadow
    @Final
    private Map<String, CompletableFuture<ClientAsset.Texture>> textures;

    @Shadow
    @Final
    private Path root;

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

        if (!SkinReload.pollEvict(hash)) {
            return;
        }

        panthenol$drop(hash);
    }

    @Unique
    @SuppressWarnings("deprecation") // must match SkinManager.TextureCache: Hashing.sha1()
    private void panthenol$drop(String hash) {
        this.textures.remove(hash);

        try {
            // SkinManager stores files under sha1(urlHash), not the raw URL hash.
            String fileName = Hashing.sha1().hashUnencodedChars(hash).toString();
            Path path = this.root.resolve(fileName.length() > 2 ? fileName.substring(0, 2) : "xx").resolve(fileName);
            Files.deleteIfExists(path);
        } catch (Exception e) {
            Constants.LOG.debug("Failed to delete cached skin file for hash {}", hash, e);
        }
    }
}
