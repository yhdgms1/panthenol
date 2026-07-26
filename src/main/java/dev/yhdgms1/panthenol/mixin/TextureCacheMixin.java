package dev.yhdgms1.panthenol.mixin;

import com.google.common.hash.Hashing;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import dev.yhdgms1.panthenol.Panthenol;
import dev.yhdgms1.panthenol.SkinReload;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
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
    private Map<String, CompletableFuture<ResourceLocation>> textures;

    @Shadow
    @Final
    private TextureManager textureManager;

    @Shadow
    @Final
    private Path root;

    @Inject(method = "getOrLoad", at = @At("HEAD"))
    private void panthenol$evictIfMarked(MinecraftProfileTexture texture, CallbackInfoReturnable<CompletableFuture<ResourceLocation>> cir) {
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
    private void panthenol$drop(String hash) {
        CompletableFuture<ResourceLocation> previous = this.textures.remove(hash);
        if (previous != null) {
            ResourceLocation location = previous.getNow(null);
            if (location != null) {
                // register() of a replacement also closes the old texture; release early so
                // nothing keeps binding the stale GPU texture while the new file downloads.
                this.textureManager.release(location);
            }
        }

        try {
            String fileName = Hashing.sha1().hashUnencodedChars(hash).toString();
            Path path = this.root.resolve(fileName.length() > 2 ? fileName.substring(0, 2) : "xx").resolve(fileName);
            Files.deleteIfExists(path);
        } catch (Exception e) {
            Panthenol.LOGGER.debug("Failed to delete cached skin file for hash {}", hash, e);
        }
    }
}
