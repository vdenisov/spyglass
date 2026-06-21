// Consumer-neutral probe extension for the front-end seam tests (ExtensionSeamAE, TraceLinkAE). It
// exercises every seam hook with no consumer coupling: it registers a header preset, an auth panel
// (which drives the Authorization row through api.headers), and a header-link resolver that turns the
// x-probe-trace response header into a link. Loaded via ?ext=/spyglass-ext/probe/index.js.
import { h } from 'vue'

export function register(api) {
  api.ui.registerHeaderPresets([
    { group: 'Probe', items: [{ name: 'X-Probe-Header', label: 'Probe', ph: 'probe-value' }] }
  ])

  api.ui.registerHeaderLinkResolver((name, value) =>
    name.toLowerCase() === 'x-probe-trace'
      ? 'https://logs.test.local/?h=' + encodeURIComponent(name) + '&v=' + encodeURIComponent(value)
      : null)

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
}
