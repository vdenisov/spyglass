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
