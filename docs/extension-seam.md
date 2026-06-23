# Front-end extension seam

The core ships **no** consumer-specific UI. An embedding service (or any extension author) contributes
its own — auth panels, header presets, header-link resolvers — as additional ESM modules loaded at
runtime, without forking the core.

## The contract

An extension is an ESM module that exports a single function:

```js
export function register(api) {
  // call api.ui.* hooks to contribute UI
}
```

`register(api)` is called once, after the spec is loaded, with the `api` surface below. A module that
fails to import, lacks a `register` export, or whose `register` throws, is logged and skipped — an
extension can never break the core explorer.

## Discovery & trust

The modules to load are resolved in precedence order:

1. `?ext=` query params (repeatable)
2. `window.SPYGLASS_CONFIG.extensions`
3. the spec's `x-spyglass-extensions` `info` extension

Operator-supplied lists (1, 2) are trusted and may point anywhere. Spec-supplied entries (3) are
limited to **same-origin** URLs — a spec is less trusted, and a cross-origin module there could pull
arbitrary third-party ESM into the explorer's origin. Modules are imported in parallel but
`register`-ed in list order, so precedence is deterministic (e.g. the first non-null header-link
resolver wins).

## The `api` surface

`register(api)` receives:

| Member | What |
| --- | --- |
| `api.spec` | The loaded OpenAPI document — read your own `x-*` `info` extensions from `api.spec.info`. |
| `api.config` | The resolved runtime config (`specUrl`, `storageNamespace`, `extensions`). |
| `api.headers.add(row)` | Add a header row `{ key, value, ph?, hint? }`. |
| `api.headers.authorization` | A Vue computed of the current `Authorization` value (read with `.value`). |
| `api.headers.setAuthorization(value)` | Set the `Authorization` value (creates the row if absent). |
| `api.headers.resetSignal` | A Vue ref bumped when the user clears headers — watch it to reset panel state. |
| `api.storage.key(suffix)` | Build a namespaced storage key (`apidocs-<suffix>`). |
| `api.storage.load(store, key, fallback)` | Load JSON from `localStorage`/`sessionStorage`. |
| `api.storage.save(store, key, value)` | Save JSON to `localStorage`/`sessionStorage`. |
| `api.history.values(fieldKey)` | Recent recorded values for a field. |
| `api.history.record(fieldKey, value)` | Record a used value (deduped, capped). |
| `api.history.remove(fieldKey, value)` | Remove one recorded value. |
| `api.history.key(name)` | Build a global (auth-scoped) history field key. |
| `api.ui.registerAuthPanel(component)` | Register a Vue component rendered in the headers editor. |
| `api.ui.registerHeaderPresets(groups)` | Register `[{ group, items: [{ name, label, ph?, hint? }] }]` for the "+ Add preset header" dropdown. |
| `api.ui.registerHeaderLinkResolver(fn)` | Register `(name, value) => url\|null` to turn a response header into a link. First non-null wins; unsafe schemes (`javascript:`/`data:`) are dropped. |

## Worked example: the demo's sample extension

`spyglass-demo` ships a small, self-contained extension that exercises all three `ui` hooks and
auto-loads from the spec (no `?ext=` needed). It is served same-origin from `META-INF/resources` at
`/spyglass-ext/demo/index.js` and advertised by the demo's own additive `OpenApiCustomizer`:

```java
@Bean
public OpenApiCustomizer demoExtensionCustomizer() {
    return openApi -> {
        if (openApi.getInfo() == null) openApi.setInfo(new Info());
        var info = openApi.getInfo();
        if (info.getExtensions() == null || !info.getExtensions().containsKey("x-spyglass-extensions")) {
            info.addExtension("x-spyglass-extensions", List.of("/spyglass-ext/demo/index.js"));
        }
    };
}
```

The module itself (`spyglass-demo/src/main/resources/META-INF/resources/spyglass-ext/demo/index.js`):

```js
import { h } from 'vue'

export function register(api) {
  // A header-preset group for the "+ Add preset header" dropdown.
  api.ui.registerHeaderPresets([
    { group: 'Demo', items: [
      { name: 'X-Request-Id', label: 'Request id', ph: 'a request correlation id' },
      { name: 'X-Correlation-Id', label: 'Correlation id', ph: 'a cross-call correlation id' }
    ] }
  ])

  // Turn a response header into a link. With no external tool to point at, this resolves the demo's
  // own trace header into an in-app anchor (operations are addressed by a #<METHOD>-<path> hash).
  // Advanced variant: read a base URL from api.spec.info['x-...'] and build an absolute deep link.
  api.ui.registerHeaderLinkResolver((name, value) =>
    name.toLowerCase() === 'x-demo-trace-id' ? '#GET-/apidocs-demo' : null)

  // A tiny no-build Vue component in the headers editor that drives the Authorization row via the
  // seam. An extension owns its panel's layout, so it styles itself — here off the explorer's CSS
  // theme variables, so it tracks light/dark mode.
  api.ui.registerAuthPanel({
    name: 'DemoAuthPanel',
    setup() {
      return () => h('div', { class: 'demo-panel',
          style: 'display: flex; align-items: center; gap: 8px; margin-top: 4px' }, [
        h('span', { class: 'demo-marker' }, 'Demo Extension'),
        h('button', { class: 'demo-apply', type: 'button',
          style: 'padding: 4px 10px; border: 1px solid var(--border); border-radius: 6px;'
            + ' background: var(--bg); color: var(--accent); cursor: pointer; font-size: 12px',
          onClick: () => api.headers.setAuthorization('demo-token') }, 'Apply demo token')
      ])
    }
  })
}
```

`vue` resolves through the explorer's import map, so the panel needs no build step. The end-to-end
behaviour is covered by `SampleExtensionAE` in `spyglass-demo`.
