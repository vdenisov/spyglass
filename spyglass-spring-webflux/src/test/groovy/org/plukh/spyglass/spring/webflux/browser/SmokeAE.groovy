package org.plukh.spyglass.spring.webflux.browser

import com.microsoft.playwright.Route
import org.plukh.spyglass.test.SpecFixtures

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

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

    def "the update-check toast surfaces over the reactive stack when the spec changes"() {
        given: 'the spec is served from a controllable body, baseline first'
        def body = mockSpec(SpecFixtures.specWithVersion('1.0.0'))

        when:
        openWithUpdateCheck()
        body.set(SpecFixtures.specWithVersion('2.0.0'))

        then:
        page.waitForSelector('.update-toast')
    }

    def "the spec probe sends If-None-Match and treats a 304 as unchanged over the reactive stack"() {
        given: 'a host that answers a matching conditional spec poll with 304 (ShallowEtagHeaderFilter is servlet-only, so simulate it here)'
        def etag = '"baseline-etag"'
        def sawConditional = new AtomicBoolean(false)
        def calls = new AtomicInteger(0)
        page.route('**/v3/api-docs', ({ Route route ->
            if (calls.incrementAndGet() == 1) {
                route.fulfill(new Route.FulfillOptions().setStatus(200)
                        .setHeaders(['content-type': 'application/json', 'etag': etag])
                        .setBody(SpecFixtures.specWithVersion('1.0.0')))
            } else {
                if (etag == route.request().headers().get('if-none-match')) sawConditional.set(true)
                route.fulfill(new Route.FulfillOptions().setStatus(304))
            }
        } as Consumer<Route>))

        when:
        openWithUpdateCheck()
        page.waitForTimeout(1200)

        then: 'the probe replayed the ETag and the unchanged 304 raised nothing'
        sawConditional.get()
        page.locator('.update-toast').count() == 0
    }
}
