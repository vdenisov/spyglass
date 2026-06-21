package org.plukh.spyglass.spring.webflux.browser

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Per-flavor smoke for the reactive (WebFlux) adapter: proof the explorer loads and executes
 * over the reactive serving stack. The full front-end behaviour is covered by the servlet module's
 * {@code *AE} suite; this spec asserts only the stack-specific serving path — the {@code RouterFunction}
 * redirects resolve, the static assets serve, the spec loads, and try-it-out builds and sends a request
 * whose response renders.
 */
class SmokeAE extends SpyglassReactiveSpecBase {

    def "the friendly /apidocs redirect resolves to the static entry point"() {
        when:
        page.navigate('/apidocs')

        then: 'the RouterFunction redirect lands on the explorer and the spec loads'
        page.waitForSelector('.sidebar .op-link')
        page.url().endsWith('/apidocs/index.html')
    }

    def "the context-root redirect resolves to the static entry point"() {
        when:
        page.navigate('/')

        then:
        page.waitForSelector('.sidebar .op-link')
        page.url().endsWith('/apidocs/index.html')
    }

    def "loads the spec and titles the page from info.title"() {
        when:
        open()

        then:
        page.locator('.brand').textContent() == 'Fixture Explorer API'
        page.title() == 'Fixture Explorer API'
    }

    def "deep-links to an operation and renders its panel"() {
        when:
        open('GET-/widgets/{id}')

        then:
        assertThat(page.locator('.op-panel')).isVisible()
    }

    def "builds and sends a try-it-out request and renders the response"() {
        given:
        open('GET-/widgets/{id}')
        param('id').locator('.control input').fill('42')

        when:
        def cap = captureSend('**/widgets/**')

        then: 'the reactive stack served the explorer, which assembled and sent the request'
        cap.method == 'GET'
        cap.url.contains('/widgets/42')
        assertThat(page.locator('.resp-status')).containsText('200')
        assertThat(respBody()).containsText('ok')
    }
}
