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

### Update check

A long-lived explorer tab can silently go stale when the service regenerates its spec or redeploys. The
explorer polls the spec and, when a change is **sustained** across a confirmation window, shows a single
dismissible "<API> was updated — Reload" toast. It never reloads on its own (a reload would drop unsaved
per-operation form input).

The signal is a **content change to the spec** at the spec URL — the common case being a regenerated
document (new endpoints/schemas). Detection is a content hash of the spec text, so it catches every such
change even when `info.version` is static. Polling is visibility-gated (a hidden tab makes no requests),
a rollout's transient flapping is absorbed by the confirmation window, and the poll sends an opportunistic
`If-None-Match` (so a host running a validator like `ShallowEtagHeaderFilter` answers an unchanged poll
with a cheap `304`). On by default and conservative; trivially disabled.

| Setting | Query param | `SPYGLASS_CONFIG` key | Default |
| --- | --- | --- | --- |
| Enabled | `?updateCheck=off` | `updateCheck.enabled` | `true` |
| Poll interval (seconds) | `?updateCheckInterval=` | `updateCheck.intervalSeconds` | `300` |
| Confirmation window (seconds) | `?updateCheckWindow=` | `updateCheck.confirmWindowSeconds` | `1800` |

The update check also accepts a **spec-supplied** layer via the `x-spyglass-config` `info` extension (see
below), so an operator can tune it server-side with no inline script. Precedence, lowest to highest:

**built-in default → spec `x-spyglass-config.updateCheck` → `window.SPYGLASS_CONFIG.updateCheck` → URL query parameter.**

```html
<script>
  window.SPYGLASS_CONFIG = {
    updateCheck: { intervalSeconds: 600 }   // poll every 10 min; other fields keep their defaults
  }
</script>
```

> **Making changes detectable.** A change is noticed when the spec bytes change. Ideally give your API a
> meaningful `info.version` and bump it when the contract changes. Many teams don't (microservices often
> have no fixed API version, and the contract may change several times a day) — for them the adapters fill
> `info.version` from the **build version** automatically (when `info.version` is unset and the
> `build-info` goal ran; disable with `apidocs.spec-version.from-build=false`), and failing even that,
> hash detection still catches every endpoint/schema change. The `Reload` action re-fetches the assets
> too — kept fresh by the `Cache-Control: no-cache` + content-ETag the adapters serve on `/apidocs/**` —
> and enabling `server.compression` shrinks each spec poll.

## OpenAPI `info` extension catalog (`x-*`)

The **mechanism** — emit/consume `x-*` keys on the document's `info` object — is part of the core. The
core consumes and emits two generic keys; other keys are populated by whatever extension adds them.

| Key | Direction | Emitted by | Purpose |
| --- | --- | --- | --- |
| `x-service-name` | emitted | the core (`SpyglassOpenApiCustomizer`, from `spring.application.name`) | Informational service name. |
| `x-spyglass-extensions` | **consumed** by core | whichever customizer supplies extensions (e.g. the demo's `DemoEndpointsConfiguration`, or an extension pack) | A list of ESM extension-module URLs to load. Spec-supplied entries are **same-origin only** (operator-supplied lists via `?ext=`/`SPYGLASS_CONFIG` are trusted anywhere). |
| `x-spyglass-config` | **consumed** by core | whichever customizer supplies it (e.g. the demo sets the update-check interval) | A nested config object the front end folds **under** the operator layers (`SPYGLASS_CONFIG`/query win). Currently carries `updateCheck` — see [Update check](#update-check). |

An extension can populate further keys against the same mechanism — e.g. a mint-endpoint path or a
deep-link config — read by its own front-end extension modules, not by the core.

## `apidocs.*` properties

The core and the Spring adapters read the standard `spring.application.name` plus a single optional
toggle; every other `apidocs.*` property is introduced by the module that owns the feature it configures,
and applies only when that module is on the classpath:

- `apidocs.spec-version.from-build` — introduced by the adapters (`spyglass-spring-core`); when `true`
  (default) and the API has no explicit `info.version`, it's filled from the build version (see
  [Update check](#update-check)). Set `false` to leave the version untouched.
- `apidocs.demo.enabled` — introduced by `spyglass-demo` (gates the demo endpoints).
- Feature properties such as a mint toggle, a deep-link config, or a directory base URL — introduced by
  an extension that adds those features.

A consuming service therefore only sets the properties of the modules it actually depends on.

## Serving the explorer efficiently

The explorer is a no-build, hand-written ES-module graph (~30 files) served with `Cache-Control:
no-cache` + a content-ETag, so a warm load is dominated by **round-trips** (conditional `304`s), and a
cold load by **bytes** (the CodeMirror vendor bundle alone is ~685 KB). Two server-side levers help;
neither requires any change to the explorer.

### Compression (cold-load bytes)

Enable response compression so the OpenAPI document and the JS/CSS assets travel gzipped:

```yaml
server:
  compression:
    enabled: true
```

The default `mime-types` already cover `application/json`, `text/javascript` and
`application/javascript`, and the default 2 KB `min-response-size` leaves tiny files uncompressed. This
shrinks the vendor bundles substantially (CodeMirror ~685 KB → ~150 KB gzipped) and every spec fetch.
It composes with the `/apidocs/**` ETag policy — a conditional `304` has no body to compress, so warm
loads are unaffected; this is purely a cold-load win. (Spyglass serves the assets with a **weak** ETag
precisely so they stay compressible — many servers, Tomcat included, refuse to gzip a strong-ETag
response.)

### HTTP/2 (warm-load round-trips)

A warm load makes ~30 conditional requests, so it is sensitive to HTTP/1.1's per-origin connection
limit. HTTP/2 multiplexes them over one connection and is the dominant warm-load lever. Two deployment
shapes:

- **(a) Behind a TLS-terminating proxy / load balancer / CDN** — these usually already speak HTTP/2 to
  the browser regardless of the protocol used to the backend. Nothing to do.
- **(b) Serving the Spring app directly** (no such front) — the app speaks HTTP/1.1, so the asset
  requests are connection-limited. Enable HTTP/2 on the app (requires TLS):

  ```yaml
  server:
    http2:
      enabled: true
  ```

  or put an HTTP/2-capable proxy in front.
