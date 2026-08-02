package dev.yhdgms1.panthenol;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Marks texture hashes for one-shot eviction from SkinManager's in-memory TextureCache
 * when {@code config.cache} is false and the same content id is reloaded.
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
        }
    }

    public static boolean pollEvict(String hash) {
        return hash != null && EVICT_HASHES.remove(hash);
    }

    public static void clearMarks() {
        EVICT_HASHES.clear();
    }
}
