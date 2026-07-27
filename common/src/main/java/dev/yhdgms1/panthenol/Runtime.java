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
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    /**
     * Matches vanilla {@code HttpURLConnection} default UA used by skin downloads
     * ({@code SkinTextureDownloader} never sets its own User-Agent).
     */
    private static final String DEFAULT_USER_AGENT = "Java/" + System.getProperty("java.version", "unknown");

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

    /**
     * Sync JS API over async Java HTTP.
     * <pre>
     *   http.get({ url, mode?, headers? })
     *   http.get([{ url, mode?, headers? }, ...])  // parallel, returns when all settle
     * </pre>
     * Single input → single {@code { body, status }}; array → array of results (same order).
     */
    private static final BaseFunction HTTP_GET = new BaseFunction() {
        @Override
        public Object call(Context callCx, Scriptable callScope, Scriptable thisObj, Object[] args) {
            try {
                if (args == null || args.length == 0 || isAbsent(args[0])) {
                    return httpResult(callCx, callScope, null, 0);
                }

                Object first = args[0];
                if (!(first instanceof Scriptable root)) {
                    return httpResult(callCx, callScope, null, 0);
                }

                List<HttpSpec> specs = new ArrayList<>();
                boolean batch;

                if (isJsArray(callCx, root)) {
                    batch = true;
                    int len = jsArrayLength(callCx, root);
                    for (int i = 0; i < len; i++) {
                        Object el = root.get(callCx, i, root);
                        if (isAbsent(el) || !(el instanceof Scriptable req)) {
                            specs.add(HttpSpec.invalid());
                        } else {
                            specs.add(parseHttpSpec(callCx, req));
                        }
                    }
                } else {
                    batch = false;
                    specs.add(parseHttpSpec(callCx, root));
                }

                if (specs.isEmpty()) {
                    return batch ? callCx.newArray(callScope, 0) : httpResult(callCx, callScope, null, 0);
                }

                // Fire all requests in parallel; JS still blocks until every one finishes.
                List<CompletableFuture<RawHttp>> futures = new ArrayList<>(specs.size());
                for (HttpSpec spec : specs) {
                    futures.add(dispatchAsync(spec));
                }

                try {
                    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Individual futures still complete with failures via handle();
                    // timed-out ones stay incomplete — map below.
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }

                Object[] jsResults = new Object[futures.size()];
                for (int i = 0; i < futures.size(); i++) {
                    RawHttp raw;
                    try {
                        raw = futures.get(i).getNow(null);
                    } catch (Exception e) {
                        raw = null;
                    }
                    if (raw == null) {
                        jsResults[i] = httpResult(callCx, callScope, null, 0);
                    } else {
                        jsResults[i] = toJsHttpResult(callCx, callScope, raw);
                    }
                }

                if (batch) {
                    return callCx.newArray(callScope, jsResults);
                }
                return jsResults[0];
            } catch (Throwable t) {
                LOGGER.debug("http.get failed", t);
                return httpResult(callCx, callScope, null, 0);
            }
        }

        @Override
        public String getFunctionName() {
            return "get";
        }
    };

    /** Parsed request spec (plain data only — safe to touch from HTTP worker threads). */
    private record HttpSpec(String url, String mode, Map<String, String> headers, boolean valid) {
        static HttpSpec invalid() {
            return new HttpSpec(null, "text", Map.of(), false);
        }
    }

    /** I/O result before Rhino conversion (must not hold Scriptable). */
    private record RawHttp(String mode, int status, String body) {
        static RawHttp fail(String mode) {
            return new RawHttp(mode != null ? mode : "text", 0, null);
        }
    }

    private static HttpSpec parseHttpSpec(Context callCx, Scriptable req) {
        String url = extractUrl(callCx, ScriptableObject.getProperty(req, "url", callCx));
        if (url == null) {
            return HttpSpec.invalid();
        }

        String mode = "text";
        Object modeVal = ScriptableObject.getProperty(req, "mode", callCx);
        if (!isAbsent(modeVal)) {
            String m = callCx.toString(modeVal).trim().toLowerCase(Locale.ROOT);
            if ("json".equals(m) || "text".equals(m)) {
                mode = m;
            } else {
                return HttpSpec.invalid();
            }
        }

        Map<String, String> headers = parseHeaders(callCx, ScriptableObject.getProperty(req, "headers", callCx));
        return new HttpSpec(url, mode, headers, true);
    }

    private static String extractUrl(Context callCx, Object urlVal) {
        if (isAbsent(urlVal)) {
            return null;
        }
        if (urlVal instanceof URI uri) {
            return uri.toString();
        }
        if (urlVal instanceof URL url) {
            return url.toString();
        }
        if (urlVal instanceof Scriptable s) {
            // URL-like: { href: "https://..." }
            Object href = ScriptableObject.getProperty(s, "href", callCx);
            if (!isAbsent(href)) {
                String u = callCx.toString(href).trim();
                return u.isEmpty() ? null : u;
            }
        }
        if (urlVal instanceof CharSequence || urlVal instanceof String) {
            String u = callCx.toString(urlVal).trim();
            return u.isEmpty() ? null : u;
        }
        // Fallback toString (e.g. some host wrappers)
        try {
            String u = callCx.toString(urlVal).trim();
            if (u.isEmpty() || "undefined".equals(u) || "null".equals(u) || "[object Object]".equals(u)) {
                return null;
            }
            return u;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> parseHeaders(Context callCx, Object headersVal) {
        if (isAbsent(headersVal) || !(headersVal instanceof Scriptable headersObj)) {
            return Map.of();
        }

        Map<String, String> out = new HashMap<>();

        // Headers-like: forEach(function (value, key) { ... })
        Object forEach = ScriptableObject.getProperty(headersObj, "forEach", callCx);
        if (forEach instanceof Callable forEachFn) {
            try {
                BaseFunction visitor = new BaseFunction() {
                    @Override
                    public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                        if (args != null && args.length >= 2 && !isAbsent(args[0]) && !isAbsent(args[1])) {
                            String value = cx.toString(args[0]);
                            String key = cx.toString(args[1]);
                            if (key != null && !key.isBlank() && value != null) {
                                out.put(key, value);
                            }
                        }
                        return Undefined.INSTANCE;
                    }
                };
                forEachFn.call(callCx, headersObj, headersObj, new Object[]{visitor});
                return Map.copyOf(out);
            } catch (Throwable t) {
                LOGGER.debug("headers.forEach failed; falling back to plain object", t);
                out.clear();
            }
        }

        // Plain object / Record<string, string>
        for (Object id : headersObj.getIds(callCx)) {
            String key;
            if (id instanceof String s) {
                key = s;
            } else if (id instanceof CharSequence cs) {
                key = cs.toString();
            } else {
                continue;
            }
            if (key.isBlank()) {
                continue;
            }
            Object val = headersObj.get(callCx, key, headersObj);
            if (isAbsent(val)) {
                continue;
            }
            out.put(key, callCx.toString(val));
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static CompletableFuture<RawHttp> dispatchAsync(HttpSpec spec) {
        if (!spec.valid || spec.url == null) {
            return CompletableFuture.completedFuture(RawHttp.fail(spec.mode));
        }

        URI uri;
        try {
            uri = URI.create(spec.url);
        } catch (IllegalArgumentException | NullPointerException e) {
            return CompletableFuture.completedFuture(RawHttp.fail(spec.mode));
        }
        if (uri.getScheme() == null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
            return CompletableFuture.completedFuture(RawHttp.fail(spec.mode));
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .header("Accept", "json".equals(spec.mode) ? "application/json" : "*/*");

        boolean hasUserAgent = false;
        for (Map.Entry<String, String> h : spec.headers.entrySet()) {
            try {
                builder.header(h.getKey(), h.getValue());
                if ("user-agent".equalsIgnoreCase(h.getKey())) {
                    hasUserAgent = true;
                }
            } catch (IllegalArgumentException ignored) {
                // skip invalid header
            }
        }
        if (!hasUserAgent) {
            builder.header("User-Agent", DEFAULT_USER_AGENT);
        }

        final String mode = spec.mode;
        return HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error != null || response == null) {
                        return RawHttp.fail(mode);
                    }
                    String body = response.body() != null ? response.body() : "";
                    return new RawHttp(mode, response.statusCode(), body);
                });
    }

    private static Scriptable toJsHttpResult(Context callCx, Scriptable callScope, RawHttp raw) {
        Object body;
        if (raw.body == null) {
            body = null;
        } else if ("json".equals(raw.mode)) {
            if (raw.body.isBlank()) {
                body = null;
            } else {
                try {
                    body = NativeJSON.parse(callCx, callScope, raw.body, JSON_IDENTITY_REVIVER);
                } catch (Exception e) {
                    LOGGER.debug("JSON parse failed (status {})", raw.status, e);
                    body = null;
                }
            }
        } else {
            body = raw.body;
        }
        return httpResult(callCx, callScope, body, raw.status);
    }

    private static boolean isJsArray(Context callCx, Scriptable s) {
        // Prefer real arrays; avoid treating request objects that happen to have length.
        if (s.getClassName() != null && "Array".equals(s.getClassName())) {
            return true;
        }
        Object length = ScriptableObject.getProperty(s, "length", callCx);
        if (!(length instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() != Math.floor(n.doubleValue())) {
            return false;
        }
        // Request objects have `url`; arrays of requests should not.
        Object url = ScriptableObject.getProperty(s, "url", callCx);
        return isAbsent(url);
    }

    private static int jsArrayLength(Context callCx, Scriptable s) {
        Object length = ScriptableObject.getProperty(s, "length", callCx);
        if (length instanceof Number n) {
            int len = n.intValue();
            return Math.max(0, Math.min(len, 16)); // hard cap
        }
        return 0;
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
