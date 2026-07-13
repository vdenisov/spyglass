// Runtime configuration seam. The explorer ships with defaults that match a stock springdoc service
// (spec at /v3/api-docs, storage keys namespaced "apidocs"), so it needs no configuration in the
// common case. A host that serves it differently overrides without editing any file inside the jar,
// via — in priority order:
//   1. URL query params      (?spec=…, ?ns=…, ?ext=…&ext=…)   — per-load, no deployment change
//   2. window.SPYGLASS_CONFIG = { specUrl, storageNamespace, extensions }  — a global set before this
//      module loads, either by a host-provided inline <script> ahead of app.js, or by overriding the
//      shipped config.local.js (a no-op sourced script index.html loads first; for asset-only hosts
//      that can't edit index.html — see docs/configuration.md)
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

// Request Log configuration (see requestLog.js / requestLogStore.js): on by default, with the storage
// caps and body-truncation threshold exposed here so a host can disable the feature (kiosk / shared
// machines — nothing is then written and the panel is hidden) or retune the bounds without editing the
// jar. Resolved separately from CONFIG, like the update check, because its spec layer
// (x-spyglass-config.requestLog) isn't known until the spec loads; App.js calls resolveRequestLogConfig
// after loadSpec and hands the result to configureRequestLog (and on to the store + capture modules).
//
// This is the single source of truth for the defaults: requestLog.js and requestLogStore.js import this
// object to seed their mutable fallbacks, so a default changed here can't drift from the module copies.
// `foldN` is the only display-side value (how many entries show before the "… +X more" fold); the rest
// bound storage.
export const REQUEST_LOG_DEFAULTS = {
  enabled: true,
  foldN: 5,
  perOpCap: 25,
  globalCountCap: 500,
  globalBytesCap: 5 * 1024 * 1024,
  bodyCap: 32 * 1024
}

// Parses a flag param to an explicit on/off, or null when it says nothing. Deliberately STRICTER than
// the update check (where anything that isn't a disable token enables): the Request Log is a privacy
// switch, so a bare/empty/stray `?requestLog` must NOT flip a host-disabled (kiosk) log back on — only a
// recognized token has an opinion, otherwise the lower-priority operator layer stands.
function parseToggle(v) {
  if (/^(on|true|1|yes)$/i.test(v)) return true
  if (/^(off|false|0|no)$/i.test(v)) return false
  return null
}

// The validity test for every Request Log count/byte bound: a positive integer. (Unlike the update
// check's timings, which allow sub-second fractions, these are entry counts and byte sizes — a
// fractional foldN would render a "… +0.5 more" label, a fractional cap is meaningless.)
function posInt(n) {
  return Number.isInteger(n) && n > 0
}

// Flat query-param overrides (params can't express nesting):
//   ?requestLog=on|true|1|yes  -> enable; ?requestLog=off|false|0|no -> disable; anything else -> ignored
//   ?requestLogFoldN=, ?requestLogPerOpCap=, ?requestLogBodyCap=, ?requestLogGlobalCount=,
//   ?requestLogGlobalBytes=  -> the numeric bounds
// A numeric param is taken only when it parses to a positive integer; unset, empty, zero, negative,
// fractional, or non-numeric values are dropped so they fall back to a lower-priority layer.
function requestLogFromParams() {
  const out = {}
  const flag = parseToggle(params.get('requestLog'))
  if (flag != null) out.enabled = flag
  const map = [
    ['requestLogFoldN', 'foldN'], ['requestLogPerOpCap', 'perOpCap'], ['requestLogBodyCap', 'bodyCap'],
    ['requestLogGlobalCount', 'globalCountCap'], ['requestLogGlobalBytes', 'globalBytesCap']
  ]
  for (const [param, key] of map) {
    const raw = params.get(param)
    if (raw == null) continue
    const n = Number(raw)
    if (posInt(n)) out[key] = n
  }
  return out
}

// Takes only the known Request Log keys from a config layer (defends against an operator/spec passing
// stray or wrong-typed fields). Numeric bounds must be positive integers — a 0/negative/fractional cap
// is dropped to the lower layer rather than producing a store that evicts everything or a fractional fold.
function pickRequestLog(layer) {
  if (!layer || typeof layer !== 'object') return {}
  const out = {}
  if ('enabled' in layer) out.enabled = !!layer.enabled
  for (const key of ['foldN', 'perOpCap', 'bodyCap', 'globalCountCap', 'globalBytesCap']) {
    if (posInt(layer[key])) out[key] = layer[key]
  }
  return out
}

// Folds the Request Log config layers, lowest priority first:
//   defaults < spec info['x-spyglass-config'].requestLog < window.SPYGLASS_CONFIG.requestLog < query
// Operator-supplied layers (global/query) win over the spec, mirroring the update-check precedence.
export function resolveRequestLogConfig(spec) {
  const specConfig = spec && spec.info && spec.info['x-spyglass-config']
  const specLayer = specConfig && typeof specConfig === 'object' ? specConfig.requestLog : null
  return {
    ...REQUEST_LOG_DEFAULTS,
    ...pickRequestLog(specLayer),
    ...pickRequestLog(overrides.requestLog),
    ...requestLogFromParams()
  }
}

// Branding configuration (see Sidebar.js footer): the explorer ships with its own footer mark
// (name · OpenAPI Explorer, build version, GitHub link). Spyglass is MIT-licensed and meant to be
// embedded, so a white-label host turns that mark off without forking — the standard config chain,
// no separate trust model (everything here is same-origin). An extension that wants to *add* its own
// footer content uses the registerFooterItem seam hook instead; this flag only governs the built-in
// mark. Resolved separately from CONFIG, like the update check / request log, because the spec layer
// (x-spyglass-config.branding) isn't known until the spec loads; App.js calls resolveBrandingConfig
// after loadSpec and hands `show` to the Sidebar.
const BRANDING_DEFAULTS = { show: true }

// Flat query-param override: ?branding=on|true|1|yes -> show; ?branding=off|false|0|no -> hide;
// anything else (incl. a bare ?branding) is ignored so the lower-priority operator layer stands —
// reuses the Request Log's strict parseToggle (branding is an explicit operator switch, not a token
// that should flip on its mere presence).
function brandingFromParams() {
  const out = {}
  const flag = parseToggle(params.get('branding'))
  if (flag != null) out.show = flag
  return out
}

// Takes only the known branding key from a config layer (defends against an operator/spec passing
// stray or wrong-typed fields), mirroring pickRequestLog / pickUpdateCheck.
function pickBranding(layer) {
  if (!layer || typeof layer !== 'object') return {}
  const out = {}
  if ('show' in layer) out.show = !!layer.show
  return out
}

// Folds the branding config layers, lowest priority first:
//   defaults < spec info['x-spyglass-config'].branding < window.SPYGLASS_CONFIG.branding < query
// Operator-supplied layers (global/query) win over the spec, mirroring the other resolvers.
export function resolveBrandingConfig(spec) {
  const specConfig = spec && spec.info && spec.info['x-spyglass-config']
  const specLayer = specConfig && typeof specConfig === 'object' ? specConfig.branding : null
  return {
    ...BRANDING_DEFAULTS,
    ...pickBranding(specLayer),
    ...pickBranding(overrides.branding),
    ...brandingFromParams()
  }
}

// Shareable request deep-link configuration (see shareLink.js / OperationPanel.js): the "Copy link"
// affordance encodes the filled-in request into the URL fragment. `maxUrl` caps the resulting link
// length — over it, Copy link refuses with a message rather than emitting a link too long to paste
// reliably into chat tools (browsers accept far longer, but shared-via-chat is the binding limit). A
// host retunes it without editing the jar. Resolved separately from CONFIG, like the request log,
// because its spec layer (x-spyglass-config.shareLink) isn't known until the spec loads; App.js calls
// resolveShareLinkConfig after loadSpec and hands maxUrl to the OperationPanel.
export const SHARE_LINK_DEFAULTS = { maxUrl: 4000 }

// Flat query-param override: ?shareLinkMaxUrl=<chars>. Taken only when it parses to a positive integer;
// unset, empty, zero, negative, fractional, or non-numeric values fall back to a lower-priority layer.
function shareLinkFromParams() {
  const out = {}
  const raw = params.get('shareLinkMaxUrl')
  if (raw != null) {
    const n = Number(raw)
    if (posInt(n)) out.maxUrl = n
  }
  return out
}

// Takes only the known share-link key from a config layer (defends against stray/wrong-typed fields),
// mirroring pickRequestLog / pickBranding. maxUrl must be a positive integer.
function pickShareLink(layer) {
  if (!layer || typeof layer !== 'object') return {}
  const out = {}
  if (posInt(layer.maxUrl)) out.maxUrl = layer.maxUrl
  return out
}

// Folds the share-link config layers, lowest priority first:
//   defaults < spec info['x-spyglass-config'].shareLink < window.SPYGLASS_CONFIG.shareLink < query
// Operator-supplied layers (global/query) win over the spec, mirroring the other resolvers.
export function resolveShareLinkConfig(spec) {
  const specConfig = spec && spec.info && spec.info['x-spyglass-config']
  const specLayer = specConfig && typeof specConfig === 'object' ? specConfig.shareLink : null
  return {
    ...SHARE_LINK_DEFAULTS,
    ...pickShareLink(specLayer),
    ...pickShareLink(overrides.shareLink),
    ...shareLinkFromParams()
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
