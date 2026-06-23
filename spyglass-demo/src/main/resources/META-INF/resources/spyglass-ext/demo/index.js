// Sample, self-contained front-end extension for the demo — the worked example the extension-seam
// docs build on (docs/extension-seam.md). It is shipped as a static asset under META-INF/resources,
// served same-origin at /spyglass-ext/demo/index.js, and advertised to the explorer by the demo's own
// OpenApiCustomizer via the spec's x-spyglass-extensions info extension (so it auto-loads with no
// ?ext= needed). It carries no consumer-specific coupling and exercises all three seam hooks; mirrors
// the test-only probe (spyglass-spring-webmvc test resources) but ships for real.
import { h } from 'vue'

export function register(api) {
  // 1) Header presets — contribute a named group to the "+ Add preset header" dropdown. Generic
  //    tracing headers, nothing consumer-specific.
  api.ui.registerHeaderPresets([
    {
      group: 'Demo',
      items: [
        { name: 'X-Request-Id', label: 'Request id', ph: 'a request correlation id' },
        { name: 'X-Correlation-Id', label: 'Correlation id', ph: 'a cross-call correlation id' }
      ]
    }
  ])

  // 2) Header-link resolver — turn a response header value into a link. With no external log tool to
  //    point at, this resolves the demo's own X-Demo-Trace-Id (returned by GET /apidocs-demo) into an
  //    in-app anchor for that operation; the explorer addresses operations by a #<METHOD>-<path> hash.
  //    Advanced variant: an extension can read its own configuration from api.spec.info['x-...'] (e.g.
  //    a log-tool base URL the backend emits) and build an absolute deep link instead.
  api.ui.registerHeaderLinkResolver((name, value) =>
    name.toLowerCase() === 'x-demo-trace-id' ? '#GET-/apidocs-demo' : null)

  // 3) Auth panel — a tiny no-build Vue component rendered in the headers editor. It drives the
  //    Authorization row through the seam (api.headers.setAuthorization), the same bridge an org's
  //    real token-generator UI would use.
  api.ui.registerAuthPanel({
    name: 'DemoAuthPanel',
    setup() {
      // An extension owns its panel's layout, so style it explicitly. A flex row with a gap keeps the
      // label and button from colliding; the button styles itself off the explorer's CSS theme
      // variables so it stays consistent in light and dark mode.
      return () => h('div', {
        class: 'demo-panel',
        style: 'display: flex; align-items: center; gap: 8px; margin-top: 4px'
      }, [
        h('span', { class: 'demo-marker' }, 'Demo Extension'),
        h('button', {
          class: 'demo-apply', type: 'button',
          style: 'padding: 4px 10px; border: 1px solid var(--border); border-radius: 6px;'
            + ' background: var(--bg); color: var(--accent); cursor: pointer; font-size: 12px',
          onClick: () => api.headers.setAuthorization('demo-token')
        }, 'Apply demo token')
      ])
    }
  })
}
