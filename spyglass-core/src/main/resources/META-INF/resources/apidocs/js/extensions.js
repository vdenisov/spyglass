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

export const registry = reactive({
  // Vue component definitions rendered in the headers editor (where the org's auth UI lives).
  authPanels: [],
  // Header-preset groups [{ group, items: [{ name, label, ph?, hint? }] }] feeding the
  // "+ Add preset header" dropdown.
  headerPresets: []
})

export function registerAuthPanel(component) {
  registry.authPanels.push(component)
}

export function registerHeaderPresets(groups) {
  if (Array.isArray(groups)) registry.headerPresets.push(...groups)
}

// Dynamically imports each configured extension module and calls its register(api). A failing
// extension is logged and skipped — it must never break the core explorer.
export async function loadExtensions(api) {
  for (const url of api.config.extensions || []) {
    try {
      const module = await import(url)
      if (typeof module.register === 'function') await module.register(api)
      else console.warn('[spyglass] extension has no register(api) export:', url)
    } catch (e) {
      console.error('[spyglass] extension failed to load:', url, e)
    }
  }
}
