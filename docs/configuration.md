# Configuration reference

Spyglass is configured on two sides: the **front end** resolves a small runtime config (`config.js`),
and the **service** communicates explorer settings to the front end through `x-*` `info` extensions on
its own OpenAPI document.

## Front-end runtime config (`config.js`)

The app resolves each setting through a fixed precedence chain:

**URL query parameter → `window.SPYGLASS_CONFIG` → built-in default.**

| Setting | Query param | `SPYGLASS_CONFIG` key | Default | Notes |
| --- | --- | --- | --- | --- |
| Spec URL | `?spec=` | `specUrl` | `/v3/api-docs` | The query param is restricted to **same-origin**; `SPYGLASS_CONFIG.specUrl` may point anywhere (e.g. a management port). |
| Storage namespace | `?ns=` | `storageNamespace` | `apidocs` | `localStorage`/`sessionStorage` key prefix, so multiple explorers on one origin don't collide. |
| Extension modules | `?ext=` (repeatable) | `extensions` | `[]` | ESM modules to load (see the [extension seam](extension-seam.md)). May be augmented from the spec — see below. |

`window.SPYGLASS_CONFIG` is a global object a host sets via an inline `<script>` before the app loads;
it never edits a file inside the jar. Example:

```html
<script>
  window.SPYGLASS_CONFIG = {
    specUrl: '/internal/openapi.json',
    storageNamespace: 'myservice-apidocs'
  }
</script>
```

### Storage keys

Every key is namespaced (`storageKey('headers')` → `apidocs-headers`). The inventory:

| Key (suffix) | Store | What |
| --- | --- | --- |
| `theme` | local | light/dark preference |
| `headers` | local | header rows (except the Authorization value) |
| `auth-token` | **session** | the Authorization header value only |
| `accept` | local | the `Accept` request header |
| `sidebar-width` | local | dragged sidebar width |
| `field-history` | local | recent-value history per field |
| `response-pretty` | local | pretty-print response preference |
| `op-form` | local | per-operation request-form snapshot |

## OpenAPI `info` extension catalog (`x-*`)

The **mechanism** — emit/consume `x-*` keys on the document's `info` object — is part of the core. The
core consumes and emits two generic keys; other keys are populated by whatever extension adds them.

| Key | Direction | Emitted by | Purpose |
| --- | --- | --- | --- |
| `x-service-name` | emitted | the core (`SpyglassOpenApiCustomizer`, from `spring.application.name`) | Informational service name. |
| `x-spyglass-extensions` | **consumed** by core | whichever customizer supplies extensions (e.g. the demo's `DemoEndpointsConfiguration`, or an extension pack) | A list of ESM extension-module URLs to load. Spec-supplied entries are **same-origin only** (operator-supplied lists via `?ext=`/`SPYGLASS_CONFIG` are trusted anywhere). |

An extension can populate further keys against the same mechanism — e.g. a mint-endpoint path or a
deep-link config — read by its own front-end extension modules, not by the core.

## `apidocs.*` properties

The core and the Spring adapters bind **no** `apidocs.*` configuration — they read only the standard
`spring.application.name`. Every `apidocs.*` property is introduced by the module that owns the feature
it configures, and applies only when that module is on the classpath:

- `apidocs.demo.enabled` — introduced by `spyglass-demo` (gates the demo endpoints).
- Feature properties such as a mint toggle, a deep-link config, or a directory base URL — introduced by
  an extension that adds those features.

A consuming service therefore only sets the properties of the modules it actually depends on.
