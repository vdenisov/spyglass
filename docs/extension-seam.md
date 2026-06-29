# Front-end extension seam

The core ships **no** consumer-specific UI. An embedding service (or any extension author) contributes
its own — auth panels, header presets, header-link resolvers, footer items — as additional ESM modules
loaded at runtime, without forking the core.

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

## Serving extension assets

By convention an extension ships its module and siblings under `META-INF/resources/spyglass-ext/<name>/`,
served at `/spyglass-ext/<name>/…`. The Spring adapters serve this convention root with the same
revalidate-on-reuse cache policy as the explorer's own assets — `Cache-Control: no-cache` plus a
content-derived ETag, `Last-Modified` disabled — so a redeployed extension is picked up without a hard
refresh (an unchanged file revalidates to a cheap `304`). The classpath merges `spyglass-ext/` across jars,
so **one handler enrols every extension's assets with no per-extension config**. This matters most for
extensions: a module's `import.meta.url` sibling resolution means one stale `index.js` would otherwise pin a
stale module graph.

An extension that serves assets from a non-conventional path (not under `/spyglass-ext/**`) can apply the
identical policy in one call via the adapter helper — `ExplorerAssetHandlers.register(registry, pattern,
location)` on the servlet adapter, or `ExplorerAssetHandlers.mapping(pattern, location)` (as a bean) on the
reactive adapter.

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
| `api.ui.registerFooterItem(component)` | Register a Vue component rendered in the sidebar footer — an extension/build version, a support link. Shows alongside Spyglass's own footer mark, or (with the mark disabled via [branding config](configuration.md#branding)) in its place. The core renders each item on a **single line** (the footer's `.foot-item` class clips overflow with an ellipsis), so keep the content to one short line. |
| `api.requestLog.registerSanitizer(fn)` | Register `(record) => record` to redact a Request Log record before it is persisted. Runs after the core `Authorization` mask, in registration order; a throw drops the record (fail-closed). |

## Redacting the Request Log

The explorer keeps a per-operation **Request Log** — a persistent history of executed
request/response pairs. Records are sanitized **at write time, before they are persisted**: the core
masks the `Authorization` header by default, and an extension contributes org-specific redaction
through `api.requestLog.registerSanitizer(fn)`.

A sanitizer receives the pre-persist record and returns it, redacted in place. The whole record is
passed, so every surface is reachable: `request.url` (the query string), `request.headers`,
`request.body`, `response.headers`, `response.body`, the replay snapshot `request.params`, and
`response.finalUrl`.

- **Ordering.** Sanitizers run **after** the core `Authorization` mask and **in registration order**;
  each receives the previous one's output.
- **Fail-closed.** If a sanitizer throws, the record is **dropped, not persisted** — deliberately
  unlike the header-link resolver (where a throw means "no link"), because here the risk is a secret
  written to disk. Keep a sanitizer total and defensive so it never drops records by accident.
- **Redact every surface.** A query- or body-borne secret is stored more than once: in the serialized
  `request.url`/`request.body`, again in the replay snapshot (`request.params`), and again in
  `response.finalUrl` (which mirrors the request URL's query even without a redirect). Redact a value
  on every surface it appears on, not just the obvious one.

## Worked example: the demo's sample extension

`spyglass-demo` ships a small, self-contained extension that exercises all five seam hooks and
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

  // A footer item — a Vue component rendered in the sidebar footer, alongside Spyglass's own mark (or,
  // with the mark disabled via the branding config, in its place). Here it surfaces the extension's own
  // version; an org might show an internal build id or a support link instead.
  api.ui.registerFooterItem({
    name: 'DemoFooterItem',
    setup() {
      return () => h('span', { class: 'demo-foot-marker', style: 'color: var(--muted); font-size: 11px' },
        'Demo Extension v1.0.0')
    }
  })

  // A Request Log sanitizer — redact secrets from a record before it is persisted, on every surface the
  // value lands on. POST /apidocs-demo/secrets carries one in each: an `apiKey` query param, an
  // X-Demo-Api-Key request header, a `secret` body field, an X-Demo-Session response header and a
  // `sessionToken` response body field. Runs after the core Authorization mask, in registration order; a
  // throw would drop the record (fail-closed), so it is scoped to known names and written to never throw.
  // (redactQueryParam / redactHeader / redactJsonField are small local helpers — see the demo module.)
  api.requestLog.registerSanitizer((record) => {
    const req = record.request || {}, res = record.response || {}
    req.url = redactQueryParam(req.url, 'apiKey')          // a query secret is also stored in the
    res.finalUrl = redactQueryParam(res.finalUrl, 'apiKey') // replay snapshot and the final URL
    redactHeader(req.headers, 'X-Demo-Api-Key')
    redactHeader(res.headers, 'X-Demo-Session')
    req.body = redactJsonField(req.body, 'secret')
    res.body = redactJsonField(res.body, 'sessionToken')
    return record
  })
}
```

The full demo module also redacts those same values in the replay snapshot (`record.request.params`),
which the excerpt above omits for brevity — see the source for the complete version.

`vue` resolves through the explorer's import map, so the panel needs no build step. The end-to-end
behaviour is covered by `SampleExtensionAE` and `RequestLogSanitizerAE` in `spyglass-demo`.
