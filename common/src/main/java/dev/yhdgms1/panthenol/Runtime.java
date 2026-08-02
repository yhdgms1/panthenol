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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Long-lived Rhino runtime for {@code config/panthenol.js}.
 * All Rhino work runs on a single daemon thread so {@link #resolveTexturesAsync}
 * never blocks the caller on script or network.
 */
public final class Runtime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String SCRIPT_NAME = "panthenol.js";
    private static final String DEFAULT_SCRIPT_RESOURCE = "/panthenol.default.js";

    private static final boolean DEFAULT_AGGRESSIVE_CACHE = false;
    private static final int MAX_TEXTURE_BYTES = 8 * 1024 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // Matches vanilla HttpURLConnection UA (SkinTextureDownloader sets none).
    private static final String DEFAULT_USER_AGENT = "Java/" + System.getProperty("java.version", "unknown");

    private static final Callable JSON_IDENTITY_REVIVER = (Context cx, Scriptable scope, Scriptable thisObj, Object[] args) -> args[1];

    private static final ThreadFactory JS_THREAD_FACTORY = runnable -> {
        Thread t = new Thread(runnable, "panthenol-js");
        t.setDaemon(true);
        return t;
    };

    // Rhino Context is not freely concurrent.
    private static final ExecutorService JS_EXECUTOR = Executors.newSingleThreadExecutor(JS_THREAD_FACTORY);

    private static final ConcurrentHashMap<String, CompletableFuture<MinecraftProfileTextures>> TEXTURE_CACHE =
            new ConcurrentHashMap<>();

    private static volatile boolean started;
    private static volatile boolean available;
    private static volatile boolean aggressiveCache = DEFAULT_AGGRESSIVE_CACHE;

    private static Context cx;
    private static Scriptable scope;
    private static Function loadFunction;

    private Runtime() {
    }

    public static boolean isAvailable() {
        return available;
    }

    public static void start() {
        if (started) {
            return;
        }
        synchronized (Runtime.class) {
            if (started) {
                return;
            }
            started = true;
            try {
                JS_EXECUTOR.submit(() -> {
                    try {
                        bootstrap();
                    } catch (Throwable t) {
                        available = false;
                        loadFunction = null;
                        LOGGER.error("Failed to start Panthenol JS runtime", t);
                    }
                }).get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                available = false;
                loadFunction = null;
                LOGGER.error("Failed to start Panthenol JS runtime", e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public static CompletableFuture<MinecraftProfileTextures> resolveTexturesAsync(GameProfile profile) {
        if (profile == null) {
            return CompletableFuture.completedFuture(null);
        }

        start();

        if (!available || loadFunction == null) {
            return CompletableFuture.completedFuture(null);
        }

        String key = cacheKey(profile);
        return TEXTURE_CACHE.computeIfAbsent(key, k ->
                CompletableFuture
                        .supplyAsync(() -> invokeLoadSafe(profile), JS_EXECUTOR)
                        .thenCompose(pending -> {
                            if (pending == null) {
                                return CompletableFuture.completedFuture(MinecraftProfileTextures.EMPTY);
                            }
                            // Download off the JS thread so other load() calls can proceed.
                            return materializeAsync(pending);
                        })
                        .thenApply(result -> {
                            MinecraftProfileTextures out =
                                    result != null ? result : MinecraftProfileTextures.EMPTY;
                            SkinReload.markTextures(out);
                            return out;
                        })
                        .exceptionally(t -> {
                            LOGGER.error("Panthenol load failed for {}", profile.name(), t);
                            return MinecraftProfileTextures.EMPTY;
                        })
        );
    }

    public static void invalidate(GameProfile profile) {
        if (profile == null || aggressiveCache) {
            return;
        }

        CompletableFuture<MinecraftProfileTextures> previous = TEXTURE_CACHE.remove(cacheKey(profile));
        if (previous != null && previous.isDone() && !previous.isCompletedExceptionally()) {
            try {
                SkinReload.markTextures(previous.getNow(null));
            } catch (Exception ignored) {
            }
        }
    }

    public static void onWorldLogin() {
        if (aggressiveCache) {
            return;
        }

        for (CompletableFuture<MinecraftProfileTextures> future : TEXTURE_CACHE.values()) {
            if (future != null && future.isDone() && !future.isCompletedExceptionally()) {
                try {
                    SkinReload.markTextures(future.getNow(null));
                } catch (Exception ignored) {
                }
            }
        }

        TEXTURE_CACHE.clear();
    }

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

        Scriptable fs = cx.newObject(scope);
        ScriptableObject.putProperty(fs, "readFile", FS_READ_FILE, cx);
        ScriptableObject.putProperty(scope, "fs", fs, cx);

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

    private static PendingTextures invokeLoadSafe(GameProfile profile) {
        if (!available || loadFunction == null || cx == null || scope == null) {
            return null;
        }
        try {
            return invokeLoad(profile);
        } catch (Throwable t) {
            LOGGER.error("panthenol.js load() failed for {}", profile.name(), t);
            return null;
        }
    }

    private static PendingTextures invokeLoad(GameProfile profile) {
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
    }

    private record PendingTexture(String contentId, String url, Map<String, String> metadata) {
        boolean isEmpty() {
            return (contentId == null || contentId.isBlank()) && (url == null || url.isBlank());
        }
    }

    private record PendingTextures(PendingTexture skin, PendingTexture cape, PendingTexture elytra) {
    }

    private static PendingTextures parseLoadResult(Object result) {
        if (!(result instanceof Scriptable root)) {
            LOGGER.debug("load() must return an object, got {}", result == null ? "null" : result.getClass().getName());
            return null;
        }

        PendingTexture skin = pendingTextureOf(root, "skin", true);
        PendingTexture cape = pendingTextureOf(root, "cape", false);
        PendingTexture elytra = pendingTextureOf(root, "elytra", false);

        if (skin == null && cape == null && elytra == null) {
            return null;
        }

        return new PendingTextures(skin, cape, elytra);
    }

    private static PendingTexture pendingTextureOf(Scriptable root, String key, boolean allowModel) {
        Object el = ScriptableObject.getProperty(root, key, cx);
        if (el == null || el == Scriptable.NOT_FOUND || el == Undefined.INSTANCE) {
            return null;
        }

        // NativeSymbol is Scriptable — check handles before object form.
        if (TextureHandles.isHandle(el)) {
            return resolveTextureRef(el, key, null);
        }

        if (el instanceof CharSequence) {
            return resolveTextureRef(el, key, null);
        }

        if (!(el instanceof Scriptable tex)) {
            LOGGER.debug("load().{} must be a URL string, Symbol handle, or {{ texture, model? }}", key);
            return null;
        }

        if ("String".equals(tex.getClassName())) {
            return resolveTextureRef(cx.toString(tex), key, null);
        }

        Object textureVal = ScriptableObject.getProperty(tex, "texture", cx);
        if (textureVal == Scriptable.NOT_FOUND) {
            LOGGER.debug("load().{} must be a URL string, Symbol handle, or {{ texture, model? }}", key);
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

        return resolveTextureRef(textureVal, key, metadata);
    }

    private static PendingTexture resolveTextureRef(Object textureVal, String key, Map<String, String> metadata) {
        if (textureVal == null || textureVal == Undefined.INSTANCE || textureVal == Scriptable.NOT_FOUND) {
            return null;
        }

        String contentId = TextureHandles.contentIdOf(textureVal);
        if (contentId != null) {
            return new PendingTexture(contentId, null, metadata);
        }

        // Only accept real strings; do not toString arbitrary objects into fake URLs.
        if (!(textureVal instanceof CharSequence)) {
            LOGGER.warn(
                    "load().{}.texture must be an http(s) URL string or a Symbol handle from mode:'texture' (got {})",
                    key,
                    textureVal.getClass().getSimpleName()
            );
            return null;
        }

        String url = textureVal.toString().trim();
        if (url.isEmpty() || "null".equals(url) || "undefined".equals(url)) {
            return null;
        }
        if (!isHttpTextureUrl(url)) {
            LOGGER.warn("Ignoring invalid texture ref ({}): {}", key, url);
            return null;
        }

        return new PendingTexture(null, url, metadata);
    }

    private static CompletableFuture<MinecraftProfileTextures> materializeAsync(PendingTextures pending) {
        CompletableFuture<MinecraftProfileTexture> skin = materializeOne(pending.skin());
        CompletableFuture<MinecraftProfileTexture> cape = materializeOne(pending.cape());
        CompletableFuture<MinecraftProfileTexture> elytra = materializeOne(pending.elytra());

        return CompletableFuture.allOf(skin, cape, elytra)
                .orTimeout(30, TimeUnit.SECONDS)
                .handle((ignored, error) -> {
                    if (error != null) {
                        LOGGER.debug("Texture materialize ended early", error);
                    }
                    MinecraftProfileTexture s = joinTexture(skin);
                    MinecraftProfileTexture c = joinTexture(cape);
                    MinecraftProfileTexture e = joinTexture(elytra);
                    if (s == null && c == null && e == null) {
                        return MinecraftProfileTextures.EMPTY;
                    }
                    return new MinecraftProfileTextures(s, c, e, SignatureState.SIGNED);
                });
    }

    private static CompletableFuture<MinecraftProfileTexture> materializeOne(PendingTexture pending) {
        if (pending == null || pending.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        if (pending.contentId() != null) {
            try {
                MinecraftProfileTexture tex =
                        BinaryTextures.fromContentId(pending.contentId(), pending.metadata());
                if (tex != null) {
                    tex.getHash();
                }
                return CompletableFuture.completedFuture(tex);
            } catch (Exception e) {
                LOGGER.warn("Ignoring invalid texture handle {}", pending.contentId(), e);
                return CompletableFuture.completedFuture(null);
            }
        }

        String url = pending.url();
        Map<String, String> metadata = pending.metadata();
        return fetchPng(url).thenApply(png -> {
            if (png == null || png.length == 0) {
                LOGGER.debug("Empty PNG from {}", url);
                return null;
            }
            try {
                MinecraftProfileTexture texture = BinaryTextures.create(png, metadata);
                if (texture != null) {
                    texture.getHash();
                }
                return texture;
            } catch (Exception e) {
                LOGGER.warn("Ignoring invalid texture from {}", url, e);
                return null;
            }
        });
    }

    private static MinecraftProfileTexture joinTexture(CompletableFuture<MinecraftProfileTexture> future) {
        try {
            return future.getNow(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static CompletableFuture<byte[]> fetchPng(String url) {
        HttpSpec spec = new HttpSpec(url, "texture", Map.of(), true);
        return dispatchAsync(spec).thenApply(raw -> {
            if (raw == null || raw.status < 200 || raw.status >= 300 || !(raw.body instanceof byte[] bytes)) {
                if (raw != null) {
                    LOGGER.debug("PNG fetch failed status={} url={}", raw.status, url);
                }
                return null;
            }
            if (bytes.length == 0 || bytes.length > MAX_TEXTURE_BYTES) {
                return null;
            }
            return bytes;
        });
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

                List<CompletableFuture<RawHttp>> futures = new ArrayList<>(specs.size());
                for (HttpSpec spec : specs) {
                    futures.add(dispatchAsync(spec));
                }

                try {
                    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
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

    // Plain data only — safe to touch from HTTP worker threads (no Scriptable).
    private record HttpSpec(String url, String mode, Map<String, String> headers, boolean valid) {
        static HttpSpec invalid() {
            return new HttpSpec(null, "text", Map.of(), false);
        }
    }

    private record RawHttp(String mode, int status, Object body) {
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
            if ("json".equals(m) || "text".equals(m) || "texture".equals(m)) {
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

        // Some CDNs return 406 for application/octet-stream; use */* for non-JSON.
        String accept = "json".equals(spec.mode) ? "application/json" : "*/*";
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .header("Accept", accept);

        boolean hasUserAgent = false;
        for (Map.Entry<String, String> h : spec.headers.entrySet()) {
            try {
                builder.header(h.getKey(), h.getValue());
                if ("user-agent".equalsIgnoreCase(h.getKey())) {
                    hasUserAgent = true;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (!hasUserAgent) {
            builder.header("User-Agent", DEFAULT_USER_AGENT);
        }

        final String mode = spec.mode;
        if ("texture".equals(mode)) {
            return HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                    .handle((response, error) -> {
                        if (error != null || response == null) {
                            return RawHttp.fail(mode);
                        }
                        byte[] body = response.body() != null ? response.body() : new byte[0];
                        return new RawHttp(mode, response.statusCode(), body);
                    });
        }

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
        } else if ("texture".equals(raw.mode)) {
            byte[] bytes = (byte[]) raw.body;
            if (raw.status < 200 || raw.status >= 300 || bytes.length == 0) {
                body = null;
            } else if (bytes.length > MAX_TEXTURE_BYTES) {
                LOGGER.warn("http.get texture response too large ({} bytes, max {})", bytes.length, MAX_TEXTURE_BYTES);
                body = null;
            } else {
                body = TextureHandles.create(callCx, callScope, bytes);
            }
        } else if ("json".equals(raw.mode)) {
            String text = (String) raw.body;
            if (text.isBlank()) {
                body = null;
            } else {
                try {
                    body = NativeJSON.parse(callCx, callScope, text, JSON_IDENTITY_REVIVER);
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

    private static final BaseFunction FS_READ_FILE = new BaseFunction() {
        @Override
        public Object call(Context callCx, Scriptable callScope, Scriptable thisObj, Object[] args) {
            try {
                if (args == null || args.length == 0 || isAbsent(args[0]) || !(args[0] instanceof Scriptable req)) {
                    return httpResult(callCx, callScope, null, 0);
                }

                Object pathVal = ScriptableObject.getProperty(req, "path", callCx);
                if (isAbsent(pathVal)) {
                    return httpResult(callCx, callScope, null, 0);
                }
                String pathStr = callCx.toString(pathVal).trim();
                if (pathStr.isEmpty() || "null".equals(pathStr) || "undefined".equals(pathStr)) {
                    return httpResult(callCx, callScope, null, 0);
                }

                String mode = "texture";
                Object modeVal = ScriptableObject.getProperty(req, "mode", callCx);
                if (!isAbsent(modeVal)) {
                    String m = callCx.toString(modeVal).trim().toLowerCase(Locale.ROOT);
                    if ("texture".equals(m) || "text".equals(m) || "json".equals(m)) {
                        mode = m;
                    } else {
                        return httpResult(callCx, callScope, null, 0);
                    }
                }

                Path resolved = resolveConfigPath(pathStr);
                if (resolved == null) {
                    LOGGER.debug("fs.readFile rejected path: {}", pathStr);
                    return httpResult(callCx, callScope, null, 0);
                }

                if (!Files.isRegularFile(resolved)) {
                    return httpResult(callCx, callScope, null, 0);
                }

                long size = Files.size(resolved);
                if (size < 0 || size > MAX_TEXTURE_BYTES) {
                    LOGGER.warn("fs.readFile too large or invalid ({} bytes): {}", size, resolved);
                    return httpResult(callCx, callScope, null, 0);
                }

                byte[] bytes = Files.readAllBytes(resolved);

                Object body;
                if ("texture".equals(mode)) {
                    if (bytes.length == 0) {
                        body = null;
                    } else {
                        body = TextureHandles.create(callCx, callScope, bytes);
                    }
                } else if ("json".equals(mode)) {
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    if (text.isBlank()) {
                        body = null;
                    } else {
                        try {
                            body = NativeJSON.parse(callCx, callScope, text, JSON_IDENTITY_REVIVER);
                        } catch (Exception e) {
                            LOGGER.debug("fs.readFile JSON parse failed for {}", resolved, e);
                            body = null;
                        }
                    }
                } else {
                    body = new String(bytes, StandardCharsets.UTF_8);
                }

                return httpResult(callCx, callScope, body, body == null && !"text".equals(mode) ? 0 : 200);
            } catch (Throwable t) {
                LOGGER.debug("fs.readFile failed", t);
                return httpResult(callCx, callScope, null, 0);
            }
        }

        @Override
        public String getFunctionName() {
            return "readFile";
        }
    };

    /** Paths must stay under the game config directory (no {@code ..} escape). */
    private static Path resolveConfigPath(String pathStr) {
        try {
            Path configRoot = Services.PLATFORM.getConfigDirectory().toAbsolutePath().normalize();
            Path raw = Path.of(pathStr);
            Path resolved = raw.isAbsolute()
                    ? raw.normalize()
                    : configRoot.resolve(raw).normalize();

            if (!resolved.startsWith(configRoot)) {
                return null;
            }
            return resolved;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isJsArray(Context callCx, Scriptable s) {
        if (s.getClassName() != null && "Array".equals(s.getClassName())) {
            return true;
        }
        Object length = ScriptableObject.getProperty(s, "length", callCx);
        if (!(length instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() != Math.floor(n.doubleValue())) {
            return false;
        }
        Object url = ScriptableObject.getProperty(s, "url", callCx);
        return isAbsent(url);
    }

    private static int jsArrayLength(Context callCx, Scriptable s) {
        Object length = ScriptableObject.getProperty(s, "length", callCx);
        if (length instanceof Number n) {
            int len = n.intValue();
            return Math.max(0, Math.min(len, 16));
        }
        return 0;
    }

    private static Scriptable httpResult(Context callCx, Scriptable callScope, Object body, int status) {
        Scriptable obj = callCx.newObject(callScope);
        ScriptableObject.putProperty(obj, "body", body, callCx);
        ScriptableObject.putProperty(obj, "status", status, callCx);
        return obj;
    }

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
