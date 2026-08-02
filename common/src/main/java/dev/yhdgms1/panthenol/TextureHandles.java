package dev.yhdgms1.panthenol;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.NativeSymbol;
import dev.latvian.mods.rhino.Scriptable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** JS texture handles as unique Symbols, resolved by instance identity. */
public final class TextureHandles {
    public static final String SYMBOL_DESCRIPTION = "panthenol.texture";

    private static final Map<Object, String> HANDLE_TO_CONTENT_ID =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private TextureHandles() {
    }

    public static Object create(Context cx, Scriptable scope, byte[] png) {
        String contentId = BinaryTextures.put(png);
        if (contentId == null || cx == null || scope == null) {
            return null;
        }

        NativeSymbol symbol = NativeSymbol.construct(cx, scope, new Object[]{SYMBOL_DESCRIPTION});
        HANDLE_TO_CONTENT_ID.put(symbol, contentId);
        return symbol;
    }

    public static String contentIdOf(Object value) {
        if (value == null) {
            return null;
        }
        return HANDLE_TO_CONTENT_ID.get(value);
    }

    public static boolean isHandle(Object value) {
        return contentIdOf(value) != null;
    }
}
