package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Route
import org.plukh.spyglass.test.SpecFixtures

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * The update-check toast (see useUpdateCheck.js). The single signal is a content change to the spec
 * (a hash of /v3/api-docs), driven here by mocking that response (the shared {@code mockSpec} helper); the
 * confirmation window and dismissal are driven with sub-second timings (the shared {@code UPDATE_CHECK_FAST}
 * query) so the specs don't wait real minutes. The toast tracks one concrete change at a time: a value must
 * be stable for the window to surface, each distinct change re-prompts (even after dismissing the previous),
 * and a value that keeps flapping never settles so never surfaces.
 *
 * <p>The opportunistic If-None-Match / 304 path is exercised here against a mocked validator (the client
 * side); {@code UpdateCheckEtagAE} additionally proves it end-to-end against a real
 * {@code ShallowEtagHeaderFilter}.
 */
class UpdateCheckAE extends SpyglassSpecBase {

    private static final String SPEC = SpecFixtures.specWithVersion('1.0.0')
    private static final String SPEC_CHANGED = SpecFixtures.specWithVersion('2.0.0')
    private static final String SPEC_CHANGED_2 = SpecFixtures.specWithVersion('3.0.0')

    def "a changed spec surfaces the toast, and Reload reloads the page"() {
        given:
        def body = mockSpec(SPEC)

        when:
        openWithUpdateCheck()
        body.set(SPEC_CHANGED)

        then: 'the toast surfaces once the confirmation window elapses, naming the API'
        page.waitForSelector('.update-toast')
        assertThat(page.locator('.update-toast-msg')).hasText('Fixture Explorer API was updated.')

        when: 'Reload is clicked'
        page.evaluate('() => { window.__nav = 1 }')
        page.locator('.update-toast-reload').click()

        then: 'the page actually reloads (the sentinel set above is gone)'
        page.waitForFunction('() => window.__nav === undefined')
    }

    def "an unchanged spec never surfaces the toast"() {
        given:
        mockSpec(SPEC)

        when: 'the served spec never changes from the loaded baseline'
        openWithUpdateCheck()

        then:
        noToastWithin(1500)
    }

    def "non-positive update-check timings are rejected; valid (incl. sub-second) values pass"() {
        expect: 'zero / negative query timings are dropped, falling back to the built-in defaults'
        def defaults = configFor('updateCheckInterval=0&updateCheckWindow=-5')
        defaults.intervalSeconds == 300
        defaults.confirmWindowSeconds == 1800
        defaults.enabled == true

        and: 'a valid sub-second query interval is kept'
        configFor('updateCheckInterval=0.15').intervalSeconds == 0.15d

        and: 'a spec-supplied zero is rejected (default) while a positive spec value is kept'
        def specCfg = configFor('', [info: ['x-spyglass-config': [updateCheck: [intervalSeconds: 0, confirmWindowSeconds: 2]]]])
        specCfg.intervalSeconds == 300
        specCfg.confirmWindowSeconds == 2
    }

    def "a single divergent tick that reverts resets the window and never surfaces (canary/flap)"() {
        given: 'the spec diverges on exactly one poll, then returns to the loaded value'
        def n = new AtomicInteger(0)
        page.route('**/v3/api-docs', ({ Route route ->
            // n==1 is the initial load (baseline); n==2 is one divergent poll; n>=3 is back to baseline.
            route.fulfill(jsonFulfill(n.incrementAndGet() == 2 ? SPEC_CHANGED : SPEC))
        } as Consumer<Route>))

        when:
        openWithUpdateCheck()

        then: 'the back-to-loaded observation resets the armed window before it can elapse'
        noToastWithin(1500)
    }

    def "sequential distinct changes each re-prompt after dismissal (no permanent suppression)"() {
        given: 'a tab loaded at the baseline, then bumped through several distinct versions in turn'
        def body = mockSpec(SPEC)
        openWithUpdateCheck()

        expect: 'every distinct change surfaces its own toast, even after dismissing the previous one'
        ['1.1.001', '1.1.002', '1.1.003', '1.1.004'].each { version ->
            body.set(SpecFixtures.specWithVersion(version))
            page.waitForSelector('.update-toast')
            page.locator('.update-toast-dismiss').click()
            assertThat(page.locator('.update-toast')).hasCount(0)
        }
    }

    def "a continuously flapping spec never settles, so no toast surfaces"() {
        given: 'after the baseline, every poll returns a different value (an in-progress rollout that never settles)'
        def n = new AtomicInteger(0)
        page.route('**/v3/api-docs', ({ Route route ->
            def i = n.incrementAndGet()
            route.fulfill(jsonFulfill(i == 1 ? SPEC : SpecFixtures.specWithVersion('flap-' + i)))
        } as Consumer<Route>))

        when:
        openWithUpdateCheck()

        then: 'the window restarts on each change, so nothing is ever sustained for a full window'
        noToastWithin(1500)
    }

    def "a specific change dismissal persists across reload, and a newer change re-prompts"() {
        given: 'the served spec is controllable; baseline first'
        def body = mockSpec(SPEC)

        when: 'a single distinct change surfaces a toast, which is dismissed'
        openWithUpdateCheck()
        body.set(SPEC_CHANGED)
        page.waitForSelector('.update-toast')
        page.locator('.update-toast-dismiss').click()

        and: 'the tab reloads back onto the loaded baseline (an old instance still serving it), while the change persists in rotation'
        body.set(SPEC)
        page.reload()
        page.waitForSelector('.sidebar .op-link')
        body.set(SPEC_CHANGED)

        then: 'the same dismissed change stays suppressed across the reload'
        noToastWithin(1500)

        when: 'a different, newer change appears'
        body.set(SPEC_CHANGED_2)

        then: 'it re-prompts (the dismissal was keyed to the old content only)'
        page.waitForSelector('.update-toast')
    }

    def "the spec probe replays the ETag as If-None-Match and treats a 304 as unchanged"() {
        given: 'a host that answers a matching conditional spec poll with 304'
        def etag = '"baseline-etag"'
        def sawConditional = new AtomicBoolean(false)
        def calls = new AtomicInteger(0)
        page.route('**/v3/api-docs', ({ Route route ->
            if (calls.incrementAndGet() == 1) {
                // Initial load: 200 + body + ETag (captured as the baseline validator).
                route.fulfill(new Route.FulfillOptions().setStatus(200)
                        .setHeaders(['content-type': 'application/json', 'etag': etag]).setBody(SPEC))
            } else {
                if (etag == route.request().headers().get('if-none-match')) sawConditional.set(true)
                route.fulfill(new Route.FulfillOptions().setStatus(304))
            }
        } as Consumer<Route>))

        when:
        openWithUpdateCheck()

        then: 'the probe sent the ETag back and the unchanged 304 raised nothing'
        noToastWithin(1200)
        sawConditional.get()
    }

    // ---- helpers -------------------------------------------------------------

    /** Navigates with the given query (and waits for load), then resolves the update-check config in-page. */
    private Object configFor(String query, Object spec = [:]) {
        page.navigate('/apidocs/index.html' + (query ? '?' + query : ''))
        page.waitForSelector('.sidebar .op-link')
        page.evaluate("async (s) => (await import('/apidocs/js/config.js')).resolveUpdateCheckConfig(s)", spec)
    }
}
