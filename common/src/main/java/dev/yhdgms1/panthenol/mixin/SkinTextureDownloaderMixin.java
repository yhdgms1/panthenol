package dev.yhdgms1.panthenol.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.yhdgms1.panthenol.BinaryTextures;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/** Serves synthetic panthenol URLs from {@link BinaryTextures} instead of HTTP/disk. */
@Mixin(SkinTextureDownloader.class)
public abstract class SkinTextureDownloaderMixin {
    @Inject(method = "downloadSkin", at = @At("HEAD"), cancellable = true)
    private void panthenol$fromBinary(Path localCopy, String url, CallbackInfoReturnable<NativeImage> cir) {
        byte[] data = BinaryTextures.getBySyntheticUrl(url);
        if (data == null) {
            return;
        }

        try {
            cir.setReturnValue(NativeImage.read(data));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
