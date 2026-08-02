
Panthenol
=======

Client-side custom skin/cape loader driven by JavaScript (this is crazy). This mod was vibecoded using [Grok Build](https://grok.com/build) with Grok 4.5 model. Requires the [Rhino](https://modrinth.com/mod/rhino) mod as a separate dependency (not bundled).

## Building

```bash
./gradlew build
```

Jars land in:

| Loader   | Output                                         |
|----------|------------------------------------------------|
| Fabric   | `fabric/build/libs/panthenol-fabric-*.jar`     |
| NeoForge | `neoforge/build/libs/panthenol-neoforge-*.jar` |

Shared logic lives in `common/`. Loader modules only provide entrypoints and platform services.

## Overview

This mod creates a Rhino `Context` and global scope (`initStandardObjects`) with host helpers, then evaluates `config/panthenol.js` once for the session. When Minecraft would load a player skin, cape, or elytra, it calls your `load` function **off the render thread**.

If the script is missing, empty, or has no `load` function, custom skins stay disabled and vanilla loading is left alone.

Each texture slot has a single field **`texture`**: either an **http(s) URL string** (Java downloads the PNG) or a **`Symbol("panthenol.texture")`** handle from `http.get` / `fs.readFile` with `mode: 'texture'`. Java inspects the value — you do not pick `url` vs `data`.

## Configuring

### Defining `load` function

```js
/**
 * @param {object} params
 * @param {string} params.name
 * @param {string} params.username
 * @param {string} [params.uuid]
 * @returns {object|null}
 */
const load = (params) => {
    return {
        skin: {
            texture: 'https://example.com/skins/' + encodeURIComponent(params.name) + '.png',
            model: 'slim'   // optional
        }
        // cape: { texture: 'https://…/cape.png' },
        // elytra: handleFromFs,   // shorthand
    };
};
```

#### Texture slot shape

```js
{
  skin: {
    texture: "https://…/skin.png" | Symbol,  // required (or use shorthand below)
    model: "slim" | "default"                // optional; skin only
  },
  cape:  { texture: urlOrSymbol },
  elytra:{ texture: urlOrSymbol }
}
```

**Shorthand** when you do not need `model`: the slot itself may be the URL or handle:

```js
return {
    skin: { texture: url, model: 'slim' },
    cape: capeHandle,           // same as { texture: capeHandle }
    elytra: 'https://…/e.png'
};
```

| `texture` value | Behavior |
|-----------------|----------|
| `http://` / `https://` string | Java downloads PNG into RAM |
| `Symbol("panthenol.texture")` | Handle from `mode: 'texture'` (identity-checked) |

### Handles (`mode: 'texture'`)

```js
const remote = http.get({ url: 'https://example.com/a.png', mode: 'texture' });
const local  = fs.readFile({ path: 'panthenol/skins/steve.png' }); // mode defaults to 'texture'

return {
    skin: { texture: local.body, model: 'slim' }
    // or: skin: { texture: remote.body, model: 'slim' }
};
```

Handles are unique **Symbols** (not forgeable strings). Only instances returned by our APIs resolve.

### Config Object

```js
const config = {
    cache: false   // true = until client restart; false = refetch on join / tab list
};
```

## Runtime

### `http.get`

```ts
type HttpRequest = {
  url: string | URL;
  mode?: 'text' | 'json' | 'texture';  // default 'text'
  headers?: Headers | Record<string, string>;
};
```

| `mode` | `body` |
|--------|--------|
| `"text"` (default) | `string` |
| `"json"` | parsed JSON or `null` |
| `"texture"` | `Symbol("panthenol.texture")` or `null` |

```js
http.get(request)         // → { body, status }
http.get([request, ...])  // parallel batch
```

- GET only; connect ~10s, request ~15s; batch wait ~30s; max 16  
- Default User-Agent: `Java/{java.version}`  
- Never throws; runs on background JS thread  

### `fs.readFile`

Paths under game **`config/`** (no escape outside).

```js
fs.readFile({ path: 'panthenol/skins/steve.png', mode: 'texture' })
// → { body, status }   status 200 | 0
```

| `mode` | `body` |
|--------|--------|
| `"texture"` (**default**) | Symbol handle or `null` |
| `"text"` | UTF-8 string |
| `"json"` | parsed JSON or `null` |

Max size ~8 MiB.

### `print` / `println`

String arguments only.

```js
println('hello');
```

---

### Standard Rhino globals

Includes **`Symbol`**, `JSON`, `Array`, `Map`, `Set`, and the usual ECMAScript surface from `initStandardObjects()`.
