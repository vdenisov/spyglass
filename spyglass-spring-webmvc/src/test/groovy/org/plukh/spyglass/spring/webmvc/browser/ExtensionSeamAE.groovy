package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Route

import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Verifies the front-end extension seam end-to-end with a consumer-neutral probe extension (served from
 * test resources at /spyglass-ext/probe/index.js). Exercises query-param discovery (?ext=) plus all
 * three seam hooks: registerAuthPanel, registerHeaderPresets, and the api.headers bridge.
 */
class ExtensionSeamAE extends SpyglassSpecBase {

    private void openWithProbe() {
        page.navigate('/apidocs/index.html?ext=/spyglass-ext/probe/index.js')
        page.waitForSelector('.sidebar .op-link')
    }

    def "loads an extension by query param and renders its registered auth panel"() {
        when:
        openWithProbe()

        then:
        assertThat(page.locator('.probe-panel .probe-marker')).hasText('probe-extension-loaded')
    }

    def "the extension's registered header preset adds a header row"() {
        given:
        openWithProbe()

        when:
        page.locator('.he-platform').selectOption('X-Probe-Header')

        then:
        assertThat(page.locator('.he-row .he-key').last()).hasValue('X-Probe-Header')
    }

    def "the extension's registered footer item renders in the sidebar footer"() {
        when:
        openWithProbe()

        then:
        assertThat(page.locator('.sidebar-foot .probe-foot-marker')).hasText('probe-footer-item')
    }

    def "the extension panel drives the Authorization row through the seam"() {
        given:
        openWithProbe()

        when:
        page.click('.probe-apply')

        then:
        assertThat(authValueInput()).hasValue('probe-token')
    }

    def "without an extension the explorer loads with no auth panel or preset dropdown"() {
        when:
        open()

        then:
        page.locator('.probe-panel').count() == 0
        page.locator('.he-platform').count() == 0
        page.locator('.probe-foot-marker').count() == 0
    }

    def "loads a same-origin extension advertised by the spec, but never fetches a cross-origin one"() {
        given: 'the cross-origin module is routed so any attempt to load it is detected'
        def crossOriginHit = new AtomicBoolean(false)
        page.route('https://cross-origin.invalid/**', ({ Route route ->
            crossOriginHit.set(true)
            route.fulfill(new Route.FulfillOptions().setStatus(200)
                    .setContentType('text/javascript').setBody('export const register = () => {}'))
        } as Consumer<Route>))

        when: 'opening a spec whose info advertises both a cross-origin and a relative extension'
        page.navigate('/apidocs/index.html?spec=/v3/api-docs-ext')
        page.waitForSelector('.sidebar .op-link')

        then: 'the relative (same-origin) probe extension loads and renders its panel'
        assertThat(page.locator('.probe-panel .probe-marker')).hasText('probe-extension-loaded')

        and: 'the cross-origin module was filtered out by the same-origin guard and never fetched'
        !crossOriginHit.get()
    }

    def "the same-origin extension guard allows relative/same-origin URLs and rejects the rest"() {
        given:
        open()

        expect: 'spec-supplied modules are limited to same-origin; cross-origin/protocol-relative/data are rejected'
        page.evaluate('''async () => {
            const m = await import('/apidocs/js/config.js')
            const f = m.isSameOriginExtension
            const origin = window.location.origin
            return {
                relative: f('/spyglass-ext/probe/index.js'),
                relativeDot: f('./probe/index.js'),
                sameOriginAbsolute: f(origin + '/spyglass-ext/probe/index.js'),
                crossOrigin: f('https://evil.example/x.js'),
                protocolRelative: f('//evil.example/x.js'),
                dataUrl: f('data:text/javascript,export const register = () => {}'),
                empty: f('')
            }
        }''') == [
                relative          : true,
                relativeDot       : true,
                sameOriginAbsolute: true,
                crossOrigin       : false,
                protocolRelative  : false,
                dataUrl           : false,
                empty             : false
        ]
    }

    def "isSafeHref allows http(s)/mailto/relative and rejects javascript:/data:/other schemes"() {
        given:
        open()

        expect: 'spec/extension link targets are scheme-allowlisted before reaching <a href>'
        page.evaluate('''async () => {
            const m = await import('/apidocs/js/config.js')
            const f = m.isSafeHref
            const origin = window.location.origin
            return {
                http              : f('http://example.com/docs'),
                https             : f('https://example.com/docs'),
                mailto            : f('mailto:team@example.com'),
                relative          : f('/docs/api'),
                sameOriginAbsolute: f(origin + '/docs'),
                javascriptScheme  : f('javascript:alert(document.cookie)'),
                dataScheme        : f('data:text/html,<script>alert(1)</script>'),
                ftpScheme         : f('ftp://example.com/file'),
                empty             : f('')
            }
        }''') == [
                http              : true,
                https             : true,
                mailto            : true,
                relative          : true,
                sameOriginAbsolute: true,
                javascriptScheme  : false,
                dataScheme        : false,
                ftpScheme         : false,
                empty             : false
        ]
    }
}
