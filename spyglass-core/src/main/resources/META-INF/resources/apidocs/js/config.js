// Runtime configuration seam. The explorer ships with defaults that match a stock springdoc service
// (spec at /v3/api-docs, storage keys namespaced "apidocs"), so it needs no configuration in the
// common case. A host that serves it differently overrides without editing any file inside the jar,
// via — in priority order:
//   1. URL query params      (?spec=…, ?ns=…, ?ext=…&ext=…)   — per-load, no deployment change
//   2. window.SPYGLASS_CONFIG = { specUrl, storageNamespace, extensions }  — a global set before this
//      module loads (e.g. by a host-provided inline <script> ahead of app.js)
//   3. built-in defaults
//
// specUrl can only come from here (it's fetched before the spec is read, so it can't live in the
// spec itself). The extension-module list additionally accepts entries from the loaded spec's
// `x-spyglass-extensions` info extension — merged later in App.js (query/global win over the spec).
const params = new URLSearchParams(window.location.search)
const overrides = window.SPYGLASS_CONFIG || {}
const extParams = params.getAll('ext')

// A ?spec= override is restricted to same-origin. A crafted ?spec=https://evil.example/spec.json (the
// attacker serves it with permissive CORS) would otherwise make the explorer fetch and render an
// attacker-controlled document in this origin, and the spec's markdown / externalDocs / externalValue
// flow into v-html and <a href> sinks — reflected XSS. The operator-set window.SPYGLASS_CONFIG.specUrl
// stays trusted and may point anywhere (e.g. a management port or a CDN); same trust split the
// extension list uses below. There is no cross-service use case for ?spec= (the explorer is scoped to
// its own embedding service), so locking it to same-origin loses nothing.
const qSpec = params.get('spec')

export const CONFIG = {
  specUrl: (qSpec && isSameOrigin(qSpec) ? qSpec : null) || overrides.specUrl || '/v3/api-docs',
  storageNamespace: params.get('ns') || overrides.storageNamespace || 'apidocs',
  // Front-end extension modules to load (see extensions.js). May be augmented from the spec in App.js.
  extensions: extParams.length ? extParams : (overrides.extensions || [])
}

// Builds a namespaced storage key, e.g. storageKey('headers') -> "apidocs-headers". Centralizes the
// "apidocs" prefix so a host can host the explorer alongside other apps on one origin without key
// collisions (the inline theme script in index.html mirrors this resolution).
export function storageKey(suffix) {
  return `${CONFIG.storageNamespace}-${suffix}`
}

// Update-check configuration (see useUpdateCheck.js): on by default and conservative; a host disables
// or retunes it without editing the jar. Resolved separately from CONFIG because one of its layers —
// the spec's x-spyglass-config — isn't known until the spec loads, so App.js calls resolveUpdateCheckConfig
// after loadSpec (the same point x-spyglass-extensions is merged).
const UPDATE_CHECK_DEFAULTS = { enabled: true, intervalSeconds: 300, confirmWindowSeconds: 1800 }

// Flat query-param overrides for the update check (params can't express nesting, hence flat names):
//   ?updateCheck=off|false|0|no  -> disable; any other value -> enable
//   ?updateCheckInterval=<seconds>, ?updateCheckWindow=<seconds>  -> the two timings
// A timing param is taken only when it parses to a finite, strictly-positive number; unset, empty,
// zero, negative, or non-numeric values are dropped so they fall back to a lower-priority layer rather
// than producing a 0ms poll loop or a zero-length confirmation window. Sub-second positives (used by
// tests) are intentionally allowed.
function updateCheckFromParams() {
  const out = {}
  const flag = params.get('updateCheck')
  if (flag != null) out.enabled = !/^(off|false|0|no)$/i.test(flag)
  for (const [param, key] of [['updateCheckInterval', 'intervalSeconds'], ['updateCheckWindow', 'confirmWindowSeconds']]) {
    const raw = params.get(param)
    if (raw == null) continue
    const n = Number(raw)
    if (Number.isFinite(n) && n > 0) out[key] = n
  }
  return out
}

// Takes only the known update-check keys from a config layer (defends against an operator/spec passing
// stray or wrong-typed fields). Timings must be finite and strictly positive — a 0/negative value (a
// hot poll loop, or a window that surfaces on the first divergent poll) is dropped to the lower layer;
// sub-second positives are allowed so a host or a test can use them deliberately.
function pickUpdateCheck(layer) {
  if (!layer || typeof layer !== 'object') return {}
  const out = {}
  if ('enabled' in layer) out.enabled = !!layer.enabled
  if (Number.isFinite(layer.intervalSeconds) && layer.intervalSeconds > 0) out.intervalSeconds = layer.intervalSeconds
  if (Number.isFinite(layer.confirmWindowSeconds) && layer.confirmWindowSeconds > 0) out.confirmWindowSeconds = layer.confirmWindowSeconds
  return out
}

// Folds the update-check config layers, lowest priority first:
//   defaults < spec info['x-spyglass-config'].updateCheck < window.SPYGLASS_CONFIG.updateCheck < query
// Operator-supplied layers (global/query) win over the spec, mirroring the extension-list precedence.
export function resolveUpdateCheckConfig(spec) {
  const specConfig = spec && spec.info && spec.info['x-spyglass-config']
  const specLayer = specConfig && typeof specConfig === 'object' ? specConfig.updateCheck : null
  return {
    ...UPDATE_CHECK_DEFAULTS,
    ...pickUpdateCheck(specLayer),
    ...pickUpdateCheck(overrides.updateCheck),
    ...updateCheckFromParams()
  }
}

// Whether a URL is same-origin: a relative path, or an absolute URL whose origin matches the page.
// Used to gate two author-supplied-and-thus-less-trusted inputs against operator-supplied ones: the
// ?spec= query param (above) and the spec's x-spyglass-extensions list — an absolute cross-origin URL
// in either would let a spec pull an attacker-controlled document or arbitrary third-party ESM into the
// explorer's origin. Operator-supplied lists (?ext=/SPYGLASS_CONFIG) are trusted and may point anywhere.
export function isSameOrigin(url) {
  if (typeof url !== 'string' || !url) return false
  try {
    return new URL(url, window.location.href).origin === window.location.origin
  } catch (e) {
    return false
  }
}

// Kept as the name App.js uses to filter spec-supplied extension modules (and the ExtensionSeamAE
// unit check imports); it's the same same-origin test.
export const isSameOriginExtension = isSameOrigin

// Whether a URL is safe to use as a link target (<a href>). Spec-supplied externalValue/externalDocs
// and extension resolver hrefs reach :href, where a `javascript:`/`data:` scheme would execute in the
// explorer's origin (Vue does not sanitize :href). Allows http(s)/mailto absolutes and relative URLs
// (which resolve to the page's http(s) origin); rejects everything else and malformed input. Guards
// the malice case under the same-origin model and the plain typo/bug case regardless.
export function isSafeHref(url) {
  if (typeof url !== 'string' || !url) return false
  try {
    const { protocol } = new URL(url, window.location.href)
    return protocol === 'http:' || protocol === 'https:' || protocol === 'mailto:'
  } catch (e) {
    return false
  }
}
