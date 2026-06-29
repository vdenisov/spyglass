// Sample, self-contained front-end extension for the demo — the worked example the extension-seam
// docs build on (docs/extension-seam.md). It is shipped as a static asset under META-INF/resources,
// served same-origin at /spyglass-ext/demo/index.js, and advertised to the explorer by the demo's own
// OpenApiCustomizer via the spec's x-spyglass-extensions info extension (so it auto-loads with no
// ?ext= needed). It carries no consumer-specific coupling and exercises all five seam hooks; mirrors
// the test-only probe (spyglass-spring-webmvc test resources) but ships for real.
import { h } from 'vue'

// The extension's own version, shown in the footer item below. A real extension would inject this at
// build time (the way the core does via version.js); the demo hardcodes it to keep the asset static.
const EXT_VERSION = '1.0.0'

export function register(api) {
  // 1) Header presets — contribute a named group to the "+ Add preset header" dropdown. Generic
  //    tracing headers, nothing consumer-specific.
  api.ui.registerHeaderPresets([
    {
      group: 'Demo',
      items: [
        { name: 'X-Request-Id', label: 'Request id', ph: 'a request correlation id' },
        { name: 'X-Correlation-Id', label: 'Correlation id', ph: 'a cross-call correlation id' }
      ]
    }
  ])

  // 2) Header-link resolver — turn a response header value into a link. With no external log tool to
  //    point at, this resolves the demo's own X-Demo-Trace-Id (returned by GET /apidocs-demo) into an
  //    in-app anchor for that operation; the explorer addresses operations by a #<METHOD>-<path> hash.
  //    Advanced variant: an extension can read its own configuration from api.spec.info['x-...'] (e.g.
  //    a log-tool base URL the backend emits) and build an absolute deep link instead.
  api.ui.registerHeaderLinkResolver((name, value) =>
    name.toLowerCase() === 'x-demo-trace-id' ? '#GET-/apidocs-demo' : null)

  // 3) Auth panel — a tiny no-build Vue component rendered in the headers editor. It drives the
  //    Authorization row through the seam (api.headers.setAuthorization), the same bridge an org's
  //    real token-generator UI would use.
  api.ui.registerAuthPanel({
    name: 'DemoAuthPanel',
    setup() {
      // An extension owns its panel's layout, so style it explicitly. A flex row with a gap keeps the
      // label and button from colliding; the button styles itself off the explorer's CSS theme
      // variables so it stays consistent in light and dark mode.
      return () => h('div', {
        class: 'demo-panel',
        style: 'display: flex; align-items: center; gap: 8px; margin-top: 4px'
      }, [
        h('span', { class: 'demo-marker' }, 'Demo Extension'),
        h('button', {
          class: 'demo-apply', type: 'button',
          style: 'padding: 4px 10px; border: 1px solid var(--border); border-radius: 6px;'
            + ' background: var(--bg); color: var(--accent); cursor: pointer; font-size: 12px',
          onClick: () => api.headers.setAuthorization('demo-token')
        }, 'Apply demo token')
      ])
    }
  })

  // 4) Footer item — contribute content to the sidebar footer, alongside Spyglass's own mark (or, with
  //    the mark disabled via the branding config, in its place). Here it surfaces the extension's own
  //    version; an org might instead show an internal build id or a support link. Styled off the
  //    explorer's CSS theme variables so it sits with the footer's muted text in light and dark mode.
  api.ui.registerFooterItem({
    name: 'DemoFooterItem',
    setup() {
      return () => h('span', {
        class: 'demo-foot-marker',
        style: 'color: var(--muted); font-size: 11px'
      }, 'Demo Extension v' + EXT_VERSION)
    }
  })

  // 5) Request Log sanitizer — redact org-specific secrets from a record BEFORE it is persisted, on
  //    every surface the value can land on. POST /apidocs-demo/secrets deliberately carries a secret in
  //    each: an `apiKey` query param, an X-Demo-Api-Key request header, a `secret` request-body field, an
  //    X-Demo-Session response header and a `sessionToken` response-body field. A query/body secret is
  //    stored more than once — in the serialized url/body, in the replay snapshot (record.request.params)
  //    and in record.response.finalUrl (which mirrors the request URL's query even without a redirect) —
  //    so each is redacted everywhere it appears, while the non-sensitive `note` is left untouched (the
  //    redaction is visibly surgical). Runs after the core Authorization mask, in registration order;
  //    scoped to these known names and written to never throw (a throw would drop the record,
  //    fail-closed — fine for a one-off, but the demo auto-loads on every operation).
  //
  //    THIS IS A DEMONSTRATION, not a production sanitizer. It matches a handful of known sentinel
  //    names against a known endpoint to make the seam tangible. A real sanitizer must be hardened for
  //    the shapes it will actually see: escape any dynamic field/header name before building a regex;
  //    redact non-string JSON values (numbers/booleans/null) and nested fields, not just top-level
  //    "field":"string" pairs; account for non-JSON bodies (form-urlencoded, the multipart summary
  //    string, truncated/binary placeholders); and prefer parsing over regex where the body is known to
  //    be JSON. When in doubt, redact more — or throw, which fails closed and drops the record.
  api.requestLog.registerSanitizer((record) => {
    const req = record.request || {}
    const res = record.response || {}

    // URL query secret: in the request URL, the replay snapshot (below) and the response's final URL.
    req.url = redactQueryParam(req.url, 'apiKey')
    res.finalUrl = redactQueryParam(res.finalUrl, 'apiKey')

    // Header secrets (header names compared case-insensitively; the browser lower-cases response ones).
    redactHeader(req.headers, 'X-Demo-Api-Key')
    redactHeader(res.headers, 'X-Demo-Session')

    // Body-field secrets — bodies are stored as serialized strings, so redact the JSON field in place.
    req.body = redactJsonField(req.body, 'secret')
    res.body = redactJsonField(res.body, 'sessionToken')

    // The replay snapshot keeps its own copies: the form param values and, in Raw mode, the raw text.
    const snap = req.params
    if (snap && typeof snap === 'object') {
      if (snap.params && typeof snap.params === 'object') {
        for (const key of Object.keys(snap.params)) {
          if (key === 'query:apiKey' || key === 'header:X-Demo-Api-Key') snap.params[key] = '***'
        }
      }
      if (snap.body && typeof snap.body === 'object' && 'secret' in snap.body) snap.body.secret = '***'
      snap.rawText = redactJsonField(snap.rawText, 'secret')
    }
    return record
  })
}

// ---- sanitizer helpers ----------------------------------------------------

// Replace a query parameter's value with *** in a URL string, leaving the rest intact. Returns a
// missing/relative/malformed value unchanged, so the sanitizer never throws on an unexpected record.
function redactQueryParam(url, name) {
  if (typeof url !== 'string' || !url) return url
  try {
    const u = new URL(url)
    if (!u.searchParams.has(name)) return url
    u.searchParams.set(name, '***')
    return u.toString()
  } catch (e) {
    return url
  }
}

// Mask a header value to *** by case-insensitive name, in a plain {name: value} object, in place.
function redactHeader(headers, name) {
  if (!headers || typeof headers !== 'object') return
  const lower = name.toLowerCase()
  for (const key of Object.keys(headers)) if (key.toLowerCase() === lower) headers[key] = '***'
}

// Replace a JSON string field's value with *** in a serialized body ({"secret":"x"} -> {"secret":"***"}),
// honoring escaped quotes. Non-string input (a placeholder, null, a structured value) is returned as-is.
function redactJsonField(text, field) {
  if (typeof text !== 'string') return text
  const re = new RegExp('("' + field + '"\\s*:\\s*)"(?:[^"\\\\]|\\\\.)*"', 'g')
  return text.replace(re, '$1"***"')
}
