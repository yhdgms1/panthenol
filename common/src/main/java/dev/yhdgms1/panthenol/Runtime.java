package dev.yhdgms1.panthenol;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.logging.LogUtils;
import dev.latvian.mods.rhino.BaseFunction;
import dev.latvian.mods.rhino.Callable;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.NativeJSON;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import dev.latvian.mods.rhino.Undefined;
import dev.yhdgms1.panthenol.platform.Services;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long-lived Rhino runtime for {@code config/panthenol.js}.
 */
public final class Runtime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SCRIPT_NAME = "panthenol.js";
    private static final String DEFAULT_SCRIPT_RESOURCE = "/panthenol.default.js";

    private static final boolean DEFAULT_AGGRESSIVE_CACHE = false;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Callable JSON_IDENTITY_REVIVER = (Context cx, Scriptable scope, Scriptable thisObj, Object[] args) -> args[1];

    private static final Object LOCK = new Object();

    /** In-session memo so repeated lookups do not re-hit the network. */
    private static final ConcurrentHashMap<String, MinecraftProfileTextures> TEXTURE_CACHE = new ConcurrentHashMap<>();

    private static volatile boolean started;
    private static volatile boolean available;
    private static volatile boolean aggressiveCache = DEFAULT_AGGRESSIVE_CACHE;

    private static Context cx;
    private static Scriptable scope;
    private static Function loadFunction;

    private Runtime() {
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static boolean isAvailable() {
        return available;
    }

    /** Starts once and keeps the JS scope alive for the client session. */
    public static void start() {
        if (started) {
            return;
        }
        synchronized (LOCK) {
            if (started) {
                return;
            }
            started = true;
            try {
                bootstrap();
            } catch (Throwable t) {
                available = false;
                loadFunction = null;
                LOGGER.error("Failed to start Panthenol JS runtime", t);
            }
        }
    }

    /**
     * Resolve textures via JS {@code load(params)}, memoized per profile.
     *
     * @return textures, {@link MinecraftProfileTextures#EMPTY} when load has nothing,
     *         or {@code null} if the runtime is unavailable
     */
    public static MinecraftProfileTextures resolveTextures(GameProfile profile) {
        if (profile == null) {
            return null;
        }

        start();

        if (!available || loadFunction == null) {
            return null;
        }

        return TEXTURE_CACHE.computeIfAbsent(cacheKey(profile), k -> {
            MinecraftProfileTextures loaded = invokeLoad(profile);
            MinecraftProfileTextures result = loaded != null ? loaded : MinecraftProfileTextures.EMPTY;
            // Any time JS load() actually runs, force Minecraft to re-download those URLs.
            // Needed for stable skin URLs (same path, new bytes) after rejoin / world login.
            SkinReload.markTextures(result);
            return result;
        });
    }

    public static void invalidate(GameProfile profile) {
        if (profile == null || aggressiveCache) {
            return;
        }

        MinecraftProfileTextures previous = TEXTURE_CACHE.remove(cacheKey(profile));
        // Drop Minecraft TextureCache + disk file for the old URLs, otherwise rejoin only
        // re-queries the API while the client keeps the previously downloaded PNG.
        SkinReload.markTextures(previous);
    }

    public static void onWorldLogin() {
        if (aggressiveCache) {
            return;
        }

        for (MinecraftProfileTextures textures : TEXTURE_CACHE.values()) {
            SkinReload.markTextures(textures);
        }

        TEXTURE_CACHE.clear();
    }

    // -------------------------------------------------------------------------
    // Bootstrap
    // -------------------------------------------------------------------------

    private static void bootstrap() throws Exception {
        Path scriptPath = scriptPath();
        seedDefaultScript(scriptPath);

        String source = readScript(scriptPath);
        if (source == null || source.isBlank()) {
            LOGGER.warn("{} missing or empty; custom skins disabled", scriptPath);
            available = false;
            return;
        }

        ContextFactory factory = new ContextFactory();
        cx = factory.enter();
        scope = cx.initStandardObjects();
        installHostApis();

        cx.evaluateString(scope, source, SCRIPT_NAME, 1, null);

        Object load = ScriptableObject.getProperty(scope, "load", cx);
        if (!(load instanceof Function fn)) {
            LOGGER.warn("{} did not define load(params); custom skins disabled", SCRIPT_NAME);
            available = false;
            return;
        }

        loadFunction = fn;
        aggressiveCache = readAggressiveCacheFlag();
        available = true;
        LOGGER.info("Panthenol JS runtime started (cache={})", aggressiveCache ? "aggressive" : "friends");
    }

    private static void installHostApis() {
        Scriptable http = cx.newObject(scope);
        ScriptableObject.putProperty(http, "get", HTTP_GET, cx);
        ScriptableObject.putProperty(scope, "http", http, cx);
        ScriptableObject.putProperty(scope, "print", PRINT, cx);
        ScriptableObject.putProperty(scope, "println", PRINTLN, cx);
    }

    private static boolean readAggressiveCacheFlag() {
        try {
            Object configVal = ScriptableObject.getProperty(scope, "config", cx);
            if (!(configVal instanceof Scriptable config)) {
                return DEFAULT_AGGRESSIVE_CACHE;
            }

            Object cacheVal = ScriptableObject.getProperty(config, "cache", cx);
            if (cacheVal == Scriptable.NOT_FOUND || cacheVal == null || cacheVal == Undefined.INSTANCE) {
                return DEFAULT_AGGRESSIVE_CACHE;
            }

            return cx.toBoolean(cacheVal);
        } catch (Throwable t) {
            LOGGER.debug("Failed to read config.cache; default={}", DEFAULT_AGGRESSIVE_CACHE, t);
            return DEFAULT_AGGRESSIVE_CACHE;
        }
    }

    // -------------------------------------------------------------------------
    // load() → textures
    // -------------------------------------------------------------------------

    private static MinecraftProfileTextures invokeLoad(GameProfile profile) {
        synchronized (LOCK) {
            if (!available || loadFunction == null || cx == null || scope == null) {
                return null;
            }

            try {
                Scriptable params = cx.newObject(scope);
                String name = profile.name() != null ? profile.name() : "";
                ScriptableObject.putProperty(params, "name", name, cx);
                ScriptableObject.putProperty(params, "username", name, cx);

                if (profile.id() != null) {
                    ScriptableObject.putProperty(params, "uuid", profile.id().toString(), cx);
                }

                Object result = cx.callSync((Callable) loadFunction, scope, scope, new Object[]{params});

                if (result == null || result == Undefined.INSTANCE || result == Scriptable.NOT_FOUND) {
                    return null;
                }

                return parseLoadResult(result);
            } catch (Throwable t) {
                LOGGER.error("panthenol.js load() failed for {}", profile.name(), t);
                return null;
            }
        }
    }

    private static MinecraftProfileTextures parseLoadResult(Object result) {
        if (!(result instanceof Scriptable root)) {
            LOGGER.debug("load() must return an object, got {}", result == null ? "null" : result.getClass().getName());
            return null;
        }

        MinecraftProfileTexture skin = textureOf(root, "skin", true);
        MinecraftProfileTexture cape = textureOf(root, "cape", false);
        MinecraftProfileTexture elytra = textureOf(root, "elytra", false);

        if (skin == null && cape == null && elytra == null) {
            return null;
        }

        return new MinecraftProfileTextures(skin, cape, elytra, SignatureState.SIGNED);
    }

    private static MinecraftProfileTexture textureOf(Scriptable root, String key, boolean allowModel) {
        Object el = ScriptableObject.getProperty(root, key, cx);
        if (el == null || el == Scriptable.NOT_FOUND || el == Undefined.INSTANCE) {
            return null;
        }
        if (!(el instanceof Scriptable tex)) {
            LOGGER.debug("load().{} must be {{ url, model? }}", key);
            return null;
        }

        Object urlVal = ScriptableObject.getProperty(tex, "url", cx);
        if (urlVal == null || urlVal == Scriptable.NOT_FOUND || urlVal == Undefined.INSTANCE) {
            return null;
        }
        String url = cx.toString(urlVal);
        if (url == null || url.isBlank()) {
            return null;
        }
        url = url.trim();
        if (!isHttpTextureUrl(url)) {
            LOGGER.warn("Ignoring invalid texture url ({}): {}", key, url);
            return null;
        }

        Map<String, String> metadata = null;

        if (allowModel) {
            Object modelVal = ScriptableObject.getProperty(tex, "model", cx);
            if (modelVal != null && modelVal != Scriptable.NOT_FOUND && modelVal != Undefined.INSTANCE) {
                String model = cx.toString(modelVal);
                if (model != null && !model.isBlank()
                        && !"null".equals(model) && !"undefined".equals(model)) {
                    metadata = new HashMap<>();
                    metadata.put("model", model.trim());
                }
            }
        }

        try {
            MinecraftProfileTexture texture = new MinecraftProfileTexture(url, metadata);
            texture.getHash(); // same validation SkinManager will perform
            return texture;
        } catch (Exception e) {
            LOGGER.warn("Ignoring invalid texture url ({}): {}", key, url);
            return null;
        }
    }

    private static boolean isHttpTextureUrl(String url) {
        try {
            var parsed = URI.create(url).toURL();
            String scheme = parsed.getProtocol();

            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }

            String path = parsed.getPath();
            return path != null && !path.isBlank() && !"/".equals(path);
        } catch (Exception e) {
            return false;
        }
    }

    private static String cacheKey(GameProfile profile) {
        String name = profile.name() != null ? profile.name().toLowerCase(Locale.ROOT) : "";
        UUID id = profile.id();

        return (id != null ? id.toString() : "unknown") + "|" + name;
    }

    // -------------------------------------------------------------------------
    // Script file I/O
    // -------------------------------------------------------------------------

    private static Path scriptPath() {
        return Services.PLATFORM.getConfigDirectory().resolve(SCRIPT_NAME);
    }

    private static String readScript(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }

            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to read {}", file, e);
            return null;
        }
    }

    private static void seedDefaultScript(Path file) {
        if (Files.isRegularFile(file)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, loadDefaultScriptResource(), StandardCharsets.UTF_8);
            LOGGER.info("Created default {}", file);
        } catch (IOException e) {
            LOGGER.error("Failed to write default {}", file, e);
        }
    }

    private static String loadDefaultScriptResource() throws IOException {
        try (InputStream in = Runtime.class.getResourceAsStream(DEFAULT_SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Missing classpath resource " + DEFAULT_SCRIPT_RESOURCE);
            }

            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // -------------------------------------------------------------------------
    // Host: http.get / print / println
    // -------------------------------------------------------------------------

    /** {@code http.get(url[, type[, headers]])} → {@code { body, status }}. */
    private static final BaseFunction HTTP_GET = new BaseFunction() {
        @Override
        public Object call(Context callCx, Scriptable callScope, Scriptable thisObj, Object[] args) {
            try {
                if (args == null || args.length == 0 || isAbsent(args[0])) {
                    return httpResult(callCx, callScope, null, 0);
                }

                String url = callCx.toString(args[0]).trim();
                if (url.isEmpty()) {
                    return httpResult(callCx, callScope, null, 0);
                }

                String type = "text";
                if (args.length >= 2 && !isAbsent(args[1])) {
                    type = callCx.toString(args[1]).trim().toLowerCase(Locale.ROOT);
                    if (!type.equals("text") && !type.equals("json")) {
                        return httpResult(callCx, callScope, null, 0);
                    }
                }

                Scriptable headersObj = null;
                if (args.length >= 3 && args[2] instanceof Scriptable s) {
                    headersObj = s;
                }

                URI uri;
                try {
                    uri = URI.create(url);
                } catch (IllegalArgumentException | NullPointerException e) {
                    return httpResult(callCx, callScope, null, 0);
                }
                if (uri.getScheme() == null
                        || !(uri.getScheme().equalsIgnoreCase("http")
                        || uri.getScheme().equalsIgnoreCase("https"))) {
                    return httpResult(callCx, callScope, null, 0);
                }

                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .header("User-Agent", "Panthenol/1.0")
                        .header("Accept", type.equals("json") ? "application/json" : "*/*");

                applyHeaders(callCx, builder, headersObj);

                HttpResponse<String> response;
                try {
                    response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                } catch (IOException e) {
                    return httpResult(callCx, callScope, null, 0);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return httpResult(callCx, callScope, null, 0);
                }

                int status = response.statusCode();
                String raw = response.body() != null ? response.body() : "";
                Object body;
                if ("json".equals(type)) {
                    if (raw.isBlank()) {
                        body = null;
                    } else {
                        try {
                            body = NativeJSON.parse(callCx, callScope, raw, JSON_IDENTITY_REVIVER);
                        } catch (Exception e) {
                            LOGGER.debug("JSON parse failed for {} (status {})", url, status, e);
                            body = null;
                        }
                    }
                } else {
                    body = raw;
                }
                return httpResult(callCx, callScope, body, status);
            } catch (Throwable t) {
                return httpResult(callCx, callScope, null, 0);
            }
        }

        @Override
        public String getFunctionName() {
            return "get";
        }
    };

    private static void applyHeaders(Context callCx, HttpRequest.Builder builder, Scriptable headersObj) {
        if (headersObj == null) {
            return;
        }
        for (Object id : headersObj.getIds(callCx)) {
            if (!(id instanceof String key) || key.isBlank()) {
                continue;
            }
            Object val = headersObj.get(callCx, key, headersObj);
            if (isAbsent(val)) {
                continue;
            }
            try {
                builder.header(key, callCx.toString(val));
            } catch (IllegalArgumentException ignored) {
                // skip invalid header
            }
        }
    }

    private static Scriptable httpResult(Context callCx, Scriptable callScope, Object body, int status) {
        Scriptable obj = callCx.newObject(callScope);
        ScriptableObject.putProperty(obj, "body", body, callCx);
        ScriptableObject.putProperty(obj, "status", status, callCx);
        return obj;
    }

    /** {@code print(string)} — strings only, no newline. */
    private static final BaseFunction PRINT = new BaseFunction() {
        @Override
        public Object call(Context callCx, Scriptable callScope, Scriptable thisObj, Object[] args) {
            String text = onlyJsString(args);
            if (text != null) {
                System.out.print(text);
            }
            return Undefined.INSTANCE;
        }

        @Override
        public String getFunctionName() {
            return "print";
        }
    };

    /** {@code println(string)} — strings only, newline. */
    private static final BaseFunction PRINTLN = new BaseFunction() {
        @Override
        public Object call(Context callCx, Scriptable callScope, Scriptable thisObj, Object[] args) {
            String text = onlyJsString(args);
            if (text != null) {
                System.out.println(text);
            }
            return Undefined.INSTANCE;
        }

        @Override
        public String getFunctionName() {
            return "println";
        }
    };

    private static String onlyJsString(Object[] args) {
        if (args == null || args.length == 0 || isAbsent(args[0])) {
            return null;
        }

        return args[0] instanceof CharSequence cs ? cs.toString() : null;
    }

    private static boolean isAbsent(Object value) {
        return value == null || value == Undefined.INSTANCE || value == Scriptable.NOT_FOUND;
    }
}
