
Panthenol
=======

Client-side custom skin/cape loader driven by JavaScript (this is crazy). This mod was vibecoded using [Grok Build](https://grok.com/build) with Grok 4.5 model. Project uses [Rhino](https://modrinth.com/mod/rhino) library for configuration.

## Building

```bash
./gradlew build
```

Jars land in:

| Loader   | Output                                         |
|----------|------------------------------------------------|
| Fabric   | `fabric/build/libs/panthenol-fabric-*.jar`     |
| NeoForge | `neoforge/build/libs/panthenol-neoforge-*.jar` |
| Quilt    | `quilt/build/libs/panthenol-quilt-*.jar`       |

Shared logic lives in `common/`. Loader modules only provide entrypoints and platform services.

## Overview

This mod will create Rhino `Context` and global scope using `initStandardObjects` which will provide functions, constants, and built-ins. Then `config/panthenol.js` will be evaluated and kept alive for the whole game session. When Minecraft would load a player skin, cape, or elytra it will intercept that call and call `load` function from JavaScript file. The config is also stored in that script.

If the script is missing, empty, or has no `load` function, custom skins stay disabled and vanilla loading is left alone.

## Configuring

### Defining `load` function

There must be a `load` function defined. It will be called whenever we need to get skin URL by player info.

```js
/**
 * @param {object} params
 * @param {string} params.name      Player name (from GameProfile)
 * @param {string} params.username  Same as name (alias)
 * @param {string} [params.uuid]    Profile UUID string, if present
 * @returns {object|null}           Texture payload object, or null / undefined
 */
const load = (params) => {
    return {
        skin: { url: 'https://…/skin.png', model: 'slim' }
    };
};
```

#### Accepted return values

| Return               | Behavior                          |
|----------------------|-----------------------------------|
| `null` / `undefined` | No custom textures → default skin |
| Object               | Used directly                     |

#### Texture payload shape

Every texture entry is an **object** with `url`:

```js
{
  skin: {
    url: "https://example.com/skins/player.png",  // required
    model: "slim" | "default"                     // optional; omit = wide
  },
  cape: {
    url: "https://example.com/capes/player.png"   // optional
  },
  elytra: {
    url: "https://example.com/elytra/player.png"  // optional
  }
}
```

### Config Object

```js
const config = {
    // true  = keep skins until client restart
    // false = refetch on world login / player join
    cache: false
};
```

| `config.cache` | Behavior                                                                                         |
|----------------|--------------------------------------------------------------------------------------------------|
| **`true`** | Memoize until **client reload**. No join invalidation.                                           |
| **`false`** (default) | Memoize while online, but **refetch** when you join a world or when a player joins the tab list. |

## Runtime

To be actually useful some functions were provided.

### `http.get`

#### Request shape

```ts
type HttpRequest = {
  url: string | URL;                 // required (http/https)
  mode?: 'text' | 'json';            // default 'text'; json parses body
  headers?: Headers | Record<string, string>;
};
```

#### Signature

```js
http.get(request)           // → { body, status }
http.get([request, ...])    // → [{ body, status }, ...]  (same order, parallel)
```

#### Return value

```js
{
  body: any,     // string, parsed JSON, or null
  status: number // HTTP status, or 0 if no response
}
```

| Situation | `status` | `body` |
|-----------|----------|--------|
| HTTP response (including 4xx/5xx) | HTTP status | text / parsed JSON / `null` |
| Network error, bad URL, etc. | `0` | `null` |

- **Method:** `GET` only
- **Timeouts:** connect ~10s, request ~15s (per request); batch wait cap ~30s
- **User-Agent:** `Java/{java.version}` (same default as vanilla `HttpURLConnection` / skin downloads; override via `headers`)
- **Batch cap:** 16 requests
- Never throws into script

#### Examples

```js
// Single
const response = http.get({
    url: `https://auth.tlauncher.org/skin/v1/profile/texture/login/${encodeURIComponent(params.name)}`,
    mode: 'json'
});

if (!response || response.status < 200 || response.status >= 300 || !response.body) {
    return null;
}

const data = response.body;

// Parallel batch — both start together; returns when both finish
const [a, b] = http.get([
    { url: 'https://example.com/a.json', mode: 'json' },
    { url: 'https://example.com/b.txt', mode: 'text', headers: { 'X-Token': '…' } }
]);
```

### `print` / `println`

Host-provided logging helpers. **Only string arguments** are accepted.

```js
print('no newline');
println('with newline');
```

| Function | Behavior |
|----------|----------|
| `print(str)` | Writes to stdout **without** a trailing newline |
| `println(str)` | Writes to stdout **with** a newline |

---

### Standard Rhino globals

`Context.initStandardObjects()` installs the usual browser-less ECMAScript surface:

**Functions:** `encodeURI`, `encodeURIComponent`, `decodeURI`, `decodeURIComponent`, `escape`, `unescape`, `parseInt`, `parseFloat`, `isNaN`, `isFinite`, `eval`, `uneval`, `isXMLName`

**Constants:** `undefined`, `NaN`, `Infinity`

**Built-ins:** `Object`, `Function`, `Array`, `String`, `Boolean`, `Number`, `Date`, `RegExp`, `Error` (+ subtypes), `Math`, `JSON`, `Symbol`, `Map`, `Set`, `WeakMap`, `WeakSet`, iterators/generators
