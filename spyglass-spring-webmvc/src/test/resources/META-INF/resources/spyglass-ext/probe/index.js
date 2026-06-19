// Consumer-neutral probe extension for the front-end seam test (ExtensionSeamAE). It exercises every seam
// hook with no consumer coupling: it registers a header preset and an auth panel, and the panel
// drives the Authorization row through api.headers. Loaded via ?ext=/spyglass-ext/probe/index.js.
import { h } from 'vue'

export function register(api) {
  api.ui.registerHeaderPresets([
    { group: 'Probe', items: [{ name: 'X-Probe-Header', label: 'Probe', ph: 'probe-value' }] }
  ])

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
