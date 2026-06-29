package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Route
import org.plukh.spyglass.test.SpecFixtures

import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * The config.local.js override hook. The explorer ships config.local.js as a no-op, sourced classic
 * {@code <script>} that index.html loads before the app reads its configuration; an asset-only host that
 * can't template or edit index.html (and won't fork the jar) overrides just that one served file to set
 * {@code window.SPYGLASS_CONFIG} (e.g. a non-default specUrl). Verifies the shipped default changes
 * nothing, and that an overridden file is honoured end-to-end: it runs before the app, sets the global,
 * config.js consumes it at module-eval time, and the app fetches that spec — with no {@code ?spec=} param.
 */
class ConfigLocalAE extends SpyglassSpecBase {

    private static final String CUSTOM_SPEC_URL = '/v3/api-docs-local'

    def "the shipped config.local.js is a no-op: SPYGLASS_CONFIG is unset and the defaults stand"() {
        when:
        open()

        then: 'the no-op file set nothing, so resolution is identical to the built-in defaults'
        page.evaluate('() => window.SPYGLASS_CONFIG ?? null') == null
        page.evaluate("async () => (await import('/apidocs/js/config.js')).CONFIG.specUrl") == '/v3/api-docs'
    }

    def "overriding config.local.js sets the spec URL the explorer loads, with no query param"() {
        given: 'a host that has replaced config.local.js to point the explorer at its own spec'
        def localHit = new AtomicBoolean(false)
        page.route('**/js/config.local.js', ({ Route route ->
            localHit.set(true)
            route.fulfill(new Route.FulfillOptions().setStatus(200)
                    .setContentType('text/javascript')
                    .setBody("window.SPYGLASS_CONFIG = { specUrl: '${CUSTOM_SPEC_URL}' }"))
        } as Consumer<Route>))

        and: 'that spec is served at the overridden URL'
        def specHit = new AtomicBoolean(false)
        page.route('**' + CUSTOM_SPEC_URL, ({ Route route ->
            specHit.set(true)
            route.fulfill(jsonFulfill(SpecFixtures.specWithVersion('1.0.0')))
        } as Consumer<Route>))

        when: 'opening the explorer with no ?spec= query param'
        page.navigate('/apidocs/index.html')
        page.waitForSelector('.sidebar .op-link')

        then: 'the override ran before the app, config.js resolved its specUrl, and that spec was fetched'
        localHit.get()
        specHit.get()
        page.evaluate("async () => (await import('/apidocs/js/config.js')).CONFIG.specUrl") == CUSTOM_SPEC_URL
    }
}
