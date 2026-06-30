// Consumer-neutral probe extension for the front-end seam tests (ExtensionSeamAE, TraceLinkAE,
// BodyTransformerAE). It exercises every seam hook with no consumer coupling: it registers a header
// preset, an auth panel (which drives the Authorization row through api.headers), a header-link
// resolver that turns the x-probe-trace response header into a link, a footer item, and a response-body
// transformer that decodes a numeric `code` field. Loaded via ?ext=/spyglass-ext/probe/index.js.
import { h } from 'vue'

export function register(api) {
  api.ui.registerHeaderPresets([
    { group: 'Probe', items: [{ name: 'X-Probe-Header', label: 'Probe', ph: 'probe-value' }] }
  ])

  api.ui.registerHeaderLinkResolver((name, value) =>
    name.toLowerCase() === 'x-probe-trace'
      ? 'https://logs.test.local/?h=' + encodeURIComponent(name) + '&v=' + encodeURIComponent(value)
      : null)

  api.ui.registerFooterItem({
    name: 'ProbeFooterItem',
    setup() {
      return () => h('span', { class: 'probe-foot-marker' }, 'probe-footer-item')
    }
  })

  api.ui.registerAuthPanel({
    name: 'ProbePanel',
    setup() {
      return () => h('div', { class: 'probe-panel' }, [
        h('span', { class: 'probe-marker' }, 'probe-extension-loaded'),
        h('button', {
          class: 'probe-apply', type: 'button',
          onClick: () => api.headers.setAuthorization('probe-token')
        }, 'Apply probe token')
      ])
    }
  })

  // Decodes a numeric `code` field into a label, leaving sibling fields untouched; declines (returns
  // null) for any body without a numeric `code` so the Decoded toggle only appears when it applies.
  api.response.registerBodyTransformer((value, ctx) =>
    value && typeof value === 'object' && typeof value.code === 'number'
      ? { ...value, code: 'CODE-' + value.code }
      : null)
}
