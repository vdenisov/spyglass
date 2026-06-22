package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route

import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Per-operation execution state (K-1): a try-it-out request stays alive across operation switches and
 * its response routes back to the operation it was sent from; requests on different operations run
 * independently. Switches here go through the in-app sidebar (a hashchange, NOT a page reload — a
 * reload would kill the in-flight request, which is exactly what this feature must survive).
 */
class InFlightStateAE extends SpyglassSpecBase {

    def "an in-flight request survives an operation switch and routes its response back"() {
        given: 'a held-open route on widgets, captured so it can be fulfilled later'
        def held = new AtomicReference<Route>()
        page.route('**/widgets/**', ({ Route route -> held.set(route) } as Consumer<Route>))
        open('GET-/widgets/{id}')
        param('id').locator('.control input').fill('7')

        when: 'the request is sent on widgets, then we switch to another operation'
        page.click('.btn-send')
        page.waitForSelector('.btn-cancel')
        clickOp('/image')

        then: 'the other operation has no in-flight request of its own'
        assertThat(page.locator('.op-header .op-path')).hasText('/image')
        page.locator('.btn-cancel').count() == 0

        when: 'we switch back to the originating operation'
        clickOp('/widgets/{id}')

        then: 'its request is still in flight'
        assertThat(page.locator('.op-header .op-path')).hasText('/widgets/{id}')
        assertThat(page.locator('.btn-cancel')).isVisible()

        when: 'the held request finally responds'
        awaitRoute(held).fulfill(new Route.FulfillOptions().setStatus(200)
                .setContentType('application/json').setBody('{"routed":true}'))

        then: 'the response renders on the originating operation'
        page.waitForSelector('.response')
        assertThat(respBody()).containsText('routed')
    }

    def "requests on different operations are independent"() {
        given: 'widgets stays in flight; image responds immediately'
        page.route('**/widgets/**', ({ Route route -> /* held open: never fulfilled */ } as Consumer<Route>))
        page.route('**/image', ({ Route route ->
            route.fulfill(new Route.FulfillOptions().setStatus(200).setContentType('application/json').setBody('{"img":1}'))
        } as Consumer<Route>))
        open('GET-/widgets/{id}')
        param('id').locator('.control input').fill('1')

        when: 'send widgets (stays in flight), switch to image and send it'
        page.click('.btn-send')
        page.waitForSelector('.btn-cancel')
        clickOp('/image')
        assertThat(page.locator('.op-header .op-path')).hasText('/image')
        page.click('.btn-send')

        then: 'image completes independently'
        page.waitForSelector('.response')
        assertThat(respBody()).containsText('img')

        when: 'we switch back to widgets'
        clickOp('/widgets/{id}')

        then: 'its request is still in flight (cancellable)'
        assertThat(page.locator('.op-header .op-path')).hasText('/widgets/{id}')
        assertThat(page.locator('.btn-cancel')).isVisible()
    }

    // ---- helpers -------------------------------------------------------------

    /** Switches operations the way a user does — clicking the sidebar (a hashchange, no reload). */
    private void clickOp(String pathText) {
        page.locator('.sidebar .op-link', new Page.LocatorOptions().setHasText(pathText)).first().click()
    }

    /** Waits until the (background-thread) route handler has captured the intercepted request. */
    private Route awaitRoute(AtomicReference<Route> ref) {
        def deadline = System.currentTimeMillis() + 5000
        while (ref.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(25)
        ref.get()
    }
}
