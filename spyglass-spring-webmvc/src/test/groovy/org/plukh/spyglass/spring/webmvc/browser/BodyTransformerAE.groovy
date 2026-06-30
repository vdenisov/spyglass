package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Route

import java.util.function.Consumer

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Response-body transformer seam: when an extension registers a body transformer (the probe decodes a
 * numeric {@code code} field into a label), a JSON response it applies to grows a Decoded toggle (on by
 * default) and shows the decoded view; a body it declines, and every response when no transformer is
 * registered, show no toggle and the raw/pretty body.
 */
class BodyTransformerAE extends SpyglassSpecBase {

    private void openWithProbe() {
        page.navigate('/apidocs/index.html?ext=/spyglass-ext/probe/index.js#GET-/widgets/{id}')
        page.waitForSelector('.sidebar .op-link')
        page.waitForSelector('.op-panel')
    }

    /** Fills the path id (so the URL is valid) and sends, stubbing the JSON response body. */
    private void sendJson(String body) {
        page.route('**/widgets/**', ({ Route route ->
            route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType('application/json').setBody(body))
        } as Consumer<Route>))
        param('id').locator('.control input').fill('1')
        page.click('.btn-send')
        page.waitForSelector('.response')
    }

    def "a transformer that applies adds the Decoded toggle (on by default) and decodes the body"() {
        given:
        openWithProbe()

        when:
        sendJson('{"code":7,"name":"keep"}')

        then: 'the Decoded toggle is offered and checked, the code is decoded and the sibling left untouched'
        page.locator('label.resp-decoded input').isChecked()
        assertThat(respBody()).containsText('"code": "CODE-7"')
        assertThat(respBody()).containsText('"name": "keep"')
    }

    def "toggling Decoded off shows the raw body and persists the preference"() {
        given:
        openWithProbe()
        sendJson('{"code":7,"name":"keep"}')

        when:
        page.locator('label.resp-decoded input').uncheck()

        then: 'the raw numeric code is shown again and the preference is stored'
        assertThat(respBody()).containsText('"code": 7')
        page.evaluate("() => localStorage.getItem('apidocs-response-decoded')") == 'false'
    }

    def "a body the transformer declines shows no Decoded toggle"() {
        given:
        openWithProbe()

        when:
        sendJson('{"name":"keep"}')

        then:
        page.locator('label.resp-decoded').count() == 0
        assertThat(respBody()).containsText('"name": "keep"')
    }

    def "without any transformer registered there is no Decoded toggle"() {
        given:
        open('GET-/widgets/{id}')

        when:
        sendJson('{"code":7}')

        then:
        page.locator('label.resp-decoded').count() == 0
    }
}
