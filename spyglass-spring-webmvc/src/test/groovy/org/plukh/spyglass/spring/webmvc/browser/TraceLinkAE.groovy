package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route

import java.util.function.Consumer

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Response-header link seam: when an extension registers a header-link resolver (the probe maps the
 * {@code x-probe-trace} header to a URL), that header's value renders as a link; headers the resolver
 * declines, and every header when no resolver is registered, stay plain text.
 */
class TraceLinkAE extends SpyglassSpecBase {

    private void openWithProbe(String opHash) {
        page.navigate('/apidocs/index.html?ext=/spyglass-ext/probe/index.js#' + opHash)
        page.waitForSelector('.sidebar .op-link')
        page.waitForSelector('.op-panel')
    }

    /** Fills the path id, sends with the given response headers, and expands the Headers section. */
    private void sendWithHeaders(Map<String, String> headers) {
        page.route('**/widgets/**', ({ Route route ->
            route.fulfill(new Route.FulfillOptions().setStatus(200).setHeaders(headers).setBody('{"ok":true}'))
        } as Consumer<Route>))
        param('id').locator('.control input').fill('1')
        page.click('.btn-send')
        page.waitForSelector('.response')
        page.locator('.response summary', new Page.LocatorOptions().setHasText('Headers')).click()
    }

    def "a resolver turns a matching response header value into a link"() {
        given:
        openWithProbe('GET-/widgets/{id}')

        when:
        sendWithHeaders(['content-type': 'application/json', 'x-probe-trace': 'abc123'])

        then:
        def link = page.locator('.resp-headers a.rh-link')
        assertThat(link).hasText('abc123')
        link.getAttribute('href').contains('logs.test.local')
        link.getAttribute('href').contains('abc123')
    }

    def "a header the resolver declines stays plain text"() {
        given:
        openWithProbe('GET-/widgets/{id}')

        when:
        sendWithHeaders(['content-type': 'application/json', 'x-probe-trace': 'abc123'])

        then:
        def row = page.locator('.resp-headers-row', new Page.LocatorOptions().setHasText('content-type'))
        row.locator('a.rh-link').count() == 0
        assertThat(row.locator('.rh-val')).hasText('application/json')
    }

    def "without any resolver every header value is plain text"() {
        given:
        open('GET-/widgets/{id}')

        when:
        sendWithHeaders(['content-type': 'application/json', 'x-probe-trace': 'abc123'])

        then:
        page.locator('.resp-headers a.rh-link').count() == 0
    }
}
