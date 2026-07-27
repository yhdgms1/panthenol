package dev.yhdgms1.panthenol;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates forced re-download of player textures.
 * <p>
 * Minecraft's {@code SkinManager.TextureCache} keys by the basename of the texture URL
 * and never expires. {@code HttpTexture} also keeps a disk file per hash and will not
 * re-fetch while that file exists. TLauncher-style APIs often keep a stable URL when the
 * PNG bytes change, so clearing only Panthenol's JS memo is not enough — the hash must be
 * marked here so the next {@code TextureCache#getOrLoad} drops the in-memory entry and
 * disk file before registering again.
 */
public final class SkinReload {
    private static final Set<String> EVICT_HASHES = ConcurrentHashMap.newKeySet();

    private SkinReload() {
    }

    public static void markTextures(MinecraftProfileTextures textures) {
        if (textures == null || textures == MinecraftProfileTextures.EMPTY) {
            return;
        }

        markTexture(textures.skin());
        markTexture(textures.cape());
        markTexture(textures.elytra());
    }

    public static void markTexture(MinecraftProfileTexture texture) {
        if (texture == null) {
            return;
        }

        try {
            String hash = texture.getHash();
            if (hash != null && !hash.isBlank()) {
                EVICT_HASHES.add(hash);
            }
        } catch (RuntimeException ignored) {
            // invalid URL — nothing cached under a usable hash
        }
    }

    /**
     * @return {@code true} once if this hash was marked for eviction (caller should drop caches)
     */
    public static boolean pollEvict(String hash) {
        return hash != null && EVICT_HASHES.remove(hash);
    }

    public static void clearMarks() {
        EVICT_HASHES.clear();
    }
}
