package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Route

import java.util.function.Consumer

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * The in-flight Send→Cancel affordance is debounced: on click Send is disabled at once but keeps its
 * green look, and only a request still running past the debounce window greys Send to "Sending…" and
 * reveals a separate Cancel. A fast request — the common case — settles within the window and so shows
 * neither the grey morph nor a flashed Cancel, while a genuinely slow one stays cancellable.
 */
class SendDebounceAE extends SpyglassSpecBase {

    def setup() {
        open('GET-/widgets/{id}')
        param('id').locator('.control input').fill('1')
    }

    def "Cancel is debounced: Send disables but stays green until a request outlasts the window"() {
        given: 'a route held open, so the request stays in flight past the debounce'
        page.route('**/widgets/**', ({ Route route -> /* held open: never fulfilled */ } as Consumer<Route>))

        when: 'the request is sent'
        page.click('.btn-send')

        then: 'Send is disabled at once but still reads "Send" (green, not the grey ghost), and no Cancel yet'
        assertThat(page.locator('.btn-send')).isDisabled()
        page.locator('.btn-send').textContent().trim() == 'Send'
        page.locator('.btn-cancel').count() == 0

        and: 'once the request outlasts the debounce, Send greys to "Sending…" and Cancel appears'
        page.waitForSelector('.btn-cancel')
        page.locator('.btn-send').textContent().trim() == 'Sending…'
    }

    def "a fast request shows neither the Sending… morph nor a Cancel"() {
        given: 'a route that responds immediately, well within the debounce window'
        page.route('**/widgets/**', ({ Route route ->
            route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType('application/json').setBody('{"ok":true}'))
        } as Consumer<Route>))

        when: 'the request is sent and its response renders'
        page.click('.btn-send')
        page.waitForSelector('.response')

        then: 'Cancel never appeared and Send is back to its idle, enabled "Send"'
        page.locator('.btn-cancel').count() == 0
        assertThat(page.locator('.btn-send')).isEnabled()
        page.locator('.btn-send').textContent().trim() == 'Send'
    }
}
