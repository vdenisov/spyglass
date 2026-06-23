// Persisted explorer state. Keys are namespaced (default prefix `apidocs-*`, configurable via
// config.js) so a single origin can host this alongside other apps without collision (the theme key
// in theme.js follows the same scheme).
//
// Storage split: the Authorization value lives in sessionStorage (it carries a short-lived token —
// these typically expire within minutes — so persisting it across browser restarts buys nothing and
// only leaves a stale token at rest). Everything else lives in localStorage so it survives restarts.

import { storageKey } from './config.js'

export const HEADERS_KEY = storageKey('headers')              // local: header rows except the Authorization value
export const AUTH_TOKEN_KEY = storageKey('auth-token')        // session: the Authorization header value only
export const SIDEBAR_WIDTH_KEY = storageKey('sidebar-width')  // local: dragged sidebar width
export const FIELD_HISTORY_KEY = storageKey('field-history')  // local: recent-value history per field (see history.js)
export const RESPONSE_PRETTY_KEY = storageKey('response-pretty') // local: pretty-print response preference
export const ACCEPT_KEY = storageKey('accept')               // local: the Accept request header (response negotiation)
export const OP_FORM_KEY = storageKey('op-form')             // local: per-operation request-form snapshot (see opForm.js)

// Keys cleared by the "Clear headers" button — the *shared* request inputs only. Per-operation form
// snapshots (op-form, cleared per operation via that operation's Reset), field history (managed
// per-value via each combobox's ✕), the sidebar width (layout) and UI preferences (theme,
// response-pretty) are deliberately kept, as they aren't shared request inputs and have their own
// controls. Extension-owned state (e.g. an auth-generator form) resets via the reset signal, not here.
const CLEARABLE = [HEADERS_KEY, ACCEPT_KEY]

export function loadJson(store, key, fallback) {
  try {
    const raw = store.getItem(key)
    return raw == null ? fallback : JSON.parse(raw)
  } catch (e) {
    return fallback
  }
}

export function saveJson(store, key, value) {
  try {
    store.setItem(key, JSON.stringify(value))
  } catch (e) {
    // Quota or disabled storage — persistence is best-effort, so swallow.
  }
}

// Wipes persisted request state from both stores (localStorage entries plus the session-scoped
// Authorization value). Leaves UI preferences intact.
export function clearSaved() {
  for (const key of CLEARABLE) {
    try { localStorage.removeItem(key) } catch (e) { /* ignore */ }
  }
  try { sessionStorage.removeItem(AUTH_TOKEN_KEY) } catch (e) { /* ignore */ }
}
