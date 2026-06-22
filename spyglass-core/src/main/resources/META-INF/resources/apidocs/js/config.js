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

export const CONFIG = {
  specUrl: params.get('spec') || overrides.specUrl || '/v3/api-docs',
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

// Whether an extension-module URL is same-origin (a relative path, or an absolute URL whose origin
// matches the page). The spec's x-spyglass-extensions list is author-supplied and less trusted than
// the operator's own ?ext=/SPYGLASS_CONFIG: an absolute cross-origin URL there would let a spec pull
// arbitrary third-party ESM into the explorer's origin. App.js limits spec-supplied modules to
// same-origin via this check; operator-supplied lists are trusted and may point anywhere.
export function isSameOriginExtension(url) {
  if (typeof url !== 'string' || !url) return false
  try {
    return new URL(url, window.location.href).origin === window.location.origin
  } catch (e) {
    return false
  }
}
