package org.plukh.spyglass.demo.browser

import com.microsoft.playwright.Route

import java.util.function.Consumer

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * End-to-end check of the bundled sample extension's response-body transformer against the demo's
 * {@code GET /apidocs-demo/mirror} endpoint. The demo's {@code OpenApiCustomizer} marks that operation
 * with an {@code x-demo-decode} vendor extension and puts the enum maps on {@code info}
 * ({@code x-demo-enums}); the transformer reads both off its {@code ctx} to decode the numeric
 * {@code status} into a label, leaving the sibling fields untouched. This proves the full path:
 * operation-level {@code x-*} passthrough -> {@code ctx} -> Decoded toggle and decoded view.
 */
class BodyTransformerAE extends SpyglassDemoSpecBase {

    /** Stubs the mirror response body and sends (the status query param carries a schema default). */
    private void sendMirror(String body) {
        page.route('**/apidocs-demo/mirror**', ({ Route route ->
            route.fulfill(new Route.FulfillOptions().setStatus(200)
                    .setContentType('application/json').setBody(body))
        } as Consumer<Route>))
        page.click('.btn-send')
        page.waitForSelector('.response')
    }

    def "the mirror operation decodes the status code behind the Decoded toggle (on by default)"() {
        given:
        open('GET-/apidocs-demo/mirror')

        when:
        sendMirror('{"status":2,"count":20,"note":"unchanged"}')

        then: 'the status code is decoded to its label while the sibling fields are left untouched'
        page.locator('label.resp-decoded input').isChecked()
        assertThat(respBody()).containsText('"status": "PENDING"')
        assertThat(respBody()).containsText('"count": 20')
        assertThat(respBody()).containsText('"note": "unchanged"')
    }

    def "an unmapped status code decodes to UNKNOWN"() {
        given:
        open('GET-/apidocs-demo/mirror')

        when:
        sendMirror('{"status":99,"count":990,"note":"unchanged"}')

        then:
        assertThat(respBody()).containsText('"status": "UNKNOWN"')
    }

    def "toggling Decoded off shows the raw status code"() {
        given:
        open('GET-/apidocs-demo/mirror')
        sendMirror('{"status":2,"count":20,"note":"unchanged"}')

        when:
        page.locator('label.resp-decoded input').uncheck()

        then:
        assertThat(respBody()).containsText('"status": 2')
    }

    def "an operation without the decode marker shows no Decoded toggle"() {
        given:
        open('GET-/apidocs-demo')

        when: 'the demo endpoint responds with JSON the transformer does not handle'
        page.route('**/apidocs-demo**', ({ Route route ->
            route.fulfill(new Route.FulfillOptions().setStatus(200)
                    .setContentType('application/json').setBody('{"value":"demo","legacyValue":"old"}'))
        } as Consumer<Route>))
        page.click('.btn-send')
        page.waitForSelector('.response')

        then:
        page.locator('label.resp-decoded').count() == 0
    }
}
