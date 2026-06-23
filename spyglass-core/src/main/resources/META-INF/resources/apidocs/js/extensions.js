// Front-end extension seam. The core ships no consumer-specific UI; an embedding service (or any
// extension author) contributes its own — directory widgets, header presets, an auth-generator
// panel — as additional ESM modules loaded at runtime, without forking the core.
//
// An extension module exports `register(api)` (see App.js for the `api` surface) and calls the
// `registerXxx` hooks below to contribute UI. The modules to load are resolved by config.js /
// App.js, in precedence order: `?ext=` query params -> window.SPYGLASS_CONFIG.extensions ->
// the spec's `x-spyglass-extensions` info extension -> none.
//
// The registry is reactive, so a panel registered after the first paint (extensions load
// asynchronously, once the spec is in) still renders.

import { reactive } from 'vue'
import { isSafeHref } from './config.js'

export const registry = reactive({
  // Vue component definitions rendered in the headers editor (where the org's auth UI lives).
  authPanels: [],
  // Header-preset groups [{ group, items: [{ name, label, ph?, hint? }] }] feeding the
  // "+ Add preset header" dropdown.
  headerPresets: [],
  // Resolvers (name, value) => url|null that turn a response header into a deep link. First non-null
  // wins. An embedding extension registers these to point a header value at any destination — a
  // trace in a log explorer, a bounded-context page, an admin tool — without the core knowing about it.
  headerLinkResolvers: []
})

export function registerAuthPanel(component) {
  registry.authPanels.push(component)
}

export function registerHeaderPresets(groups) {
  if (Array.isArray(groups)) registry.headerPresets.push(...groups)
}

export function registerHeaderLinkResolver(fn) {
  if (typeof fn === 'function') registry.headerLinkResolvers.push(fn)
}

// Returns the first safe non-null URL a registered resolver produces for this response header, or null
// when none applies (the value then renders as plain text). An unsafe-scheme URL (javascript:/data:) is
// skipped like a null, and a throwing resolver is skipped too, so a faulty or hostile extension can
// never break the response view or inject a script: href.
export function resolveHeaderLink(name, value) {
  for (const resolve of registry.headerLinkResolvers) {
    try {
      const url = resolve(name, value)
      if (url && isSafeHref(url)) return url
    } catch (e) {
      console.error('[spyglass] header-link resolver failed:', e)
    }
  }
  return null
}

// Dynamically imports the resolved extension modules and calls each one's register(api). The list is
// computed by App.js (operator-supplied entries, else same-origin spec-supplied ones), not read from
// CONFIG here, so the trust filtering stays in one place. Imports run in parallel (the network is the
// slow part), then register() is called in list order so registration stays deterministic — e.g. the
// "first non-null header-link resolver wins" precedence. A module that fails to import or whose
// register() throws is logged and skipped: an extension must never break the core explorer.
export async function loadExtensions(api, extensions) {
  const loaded = await Promise.all((extensions || []).map(async (url) => {
    try {
      return { url, module: await import(url) }
    } catch (e) {
      console.error('[spyglass] extension failed to load:', url, e)
      return null
    }
  }))
  for (const entry of loaded) {
    if (!entry) continue
    const { url, module } = entry
    if (typeof module.register !== 'function') {
      console.warn('[spyglass] extension has no register(api) export:', url)
      continue
    }
    try {
      await module.register(api)
    } catch (e) {
      console.error('[spyglass] extension failed to register:', url, e)
    }
  }
}
