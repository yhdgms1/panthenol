package dev.yhdgms1.panthenol;

import com.google.common.hash.Hashing;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory PNG store for the texture pipeline. Synthetic URLs use a content fingerprint
 * as the path basename so {@link MinecraftProfileTexture#getHash()} keys follow image bytes.
 */
public final class BinaryTextures {
    public static final String SYNTHETIC_HOST = "panthenol.local";
    public static final String SYNTHETIC_URL_PREFIX = "https://" + SYNTHETIC_HOST + "/";

    private static final ConcurrentHashMap<String, byte[]> BY_CONTENT_ID = new ConcurrentHashMap<>();

    private BinaryTextures() {
    }

    public static String put(byte[] png) {
        if (png == null || png.length == 0) {
            return null;
        }
        String contentId = contentId(png);
        BY_CONTENT_ID.put(contentId, png);
        return contentId;
    }

    public static MinecraftProfileTexture create(byte[] png, Map<String, String> metadata) {
        String contentId = put(png);
        if (contentId == null) {
            return null;
        }
        return fromContentId(contentId, metadata);
    }

    public static MinecraftProfileTexture fromContentId(String contentId, Map<String, String> metadata) {
        if (contentId == null || contentId.isBlank()) {
            return null;
        }
        if (!BY_CONTENT_ID.containsKey(contentId)) {
            return null;
        }
        return new MinecraftProfileTexture(SYNTHETIC_URL_PREFIX + contentId, metadata);
    }

    // Cache key only — not a security hash.
    private static String contentId(byte[] png) {
        return Hashing.murmur3_128().hashBytes(png).toString();
    }

    public static boolean isSyntheticUrl(String url) {
        return url != null && url.startsWith(SYNTHETIC_URL_PREFIX);
    }

    public static String contentIdFromUrl(String url) {
        if (!isSyntheticUrl(url)) {
            return null;
        }
        String id = url.substring(SYNTHETIC_URL_PREFIX.length()).trim();
        int cut = id.indexOf('?');
        if (cut >= 0) {
            id = id.substring(0, cut);
        }
        cut = id.indexOf('#');
        if (cut >= 0) {
            id = id.substring(0, cut);
        }
        return id.isEmpty() ? null : id;
    }

    public static byte[] getByContentId(String contentId) {
        return contentId == null ? null : BY_CONTENT_ID.get(contentId);
    }

    public static byte[] getBySyntheticUrl(String url) {
        return getByContentId(contentIdFromUrl(url));
    }
}
