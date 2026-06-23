package org.plukh.spyglass.demo.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route

import java.nio.file.Paths
import java.util.function.Consumer

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * End-to-end check of the bundled, self-contained sample extension (served from
 * {@code /spyglass-ext/demo/index.js} and advertised by the demo spec's {@code x-spyglass-extensions}
 * info extension). Unlike {@code ExtensionSeamAE} — which loads the test probe via {@code ?ext=} — this
 * proves the spec-advertised, auto-load path with no query param, and exercises all three seam hooks the
 * sample registers: an auth panel, a header preset, and a response-header link resolver. It doubles as
 * the capture point for the README screenshot.
 */
class SampleExtensionAE extends SpyglassDemoSpecBase {

    def "the spec-advertised sample extension auto-loads and renders its auth panel (no ?ext=)"() {
        when:
        open()

        then:
        assertThat(page.locator('.demo-panel .demo-marker')).hasText('Demo Extension')
    }

    def "the sample extension's header preset adds a header row"() {
        given:
        open()

        when:
        page.locator('.he-platform').selectOption('X-Request-Id')

        then:
        assertThat(page.locator('.he-row .he-key').last()).hasValue('X-Request-Id')
    }

    def "the sample auth panel drives the Authorization row through the seam"() {
        given:
        open()

        when:
        page.click('.demo-apply')

        then:
        assertThat(authValueInput()).hasValue('demo-token')
    }

    def "the sample resolver turns the demo trace response header into an in-app link"() {
        given:
        open('GET-/apidocs-demo')

        when: 'the demo endpoint responds with its X-Demo-Trace-Id header'
        page.route('**/apidocs-demo**', ({ Route route ->
            route.fulfill(new Route.FulfillOptions().setStatus(200)
                    .setHeaders(['content-type': 'application/json', 'x-demo-trace-id': 'trace-42'])
                    .setBody('{"value":"demo"}'))
        } as Consumer<Route>))
        page.click('.btn-send')
        page.waitForSelector('.response')
        page.locator('.response summary', new Page.LocatorOptions().setHasText('Headers')).click()

        then: 'the resolver renders the value as an in-app anchor to the operation'
        def link = page.locator('.resp-headers a.rh-link')
        assertThat(link).hasText('trace-42')
        link.getAttribute('href').contains('GET-/apidocs-demo')
    }

    def "captures a representative screenshot for the docs"() {
        given: 'a viewport sized to keep the content pane tight (little right-hand whitespace)'
        page.setViewportSize(1080, 900)

        when: 'a schema-rich operation is open (query params, a body form and named examples)'
        open('POST-/apidocs-demo/examples')
        page.waitForSelector('.op-panel .body-section')

        then: 'a full-page screenshot is written to the build dir for docs/assets'
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(Paths.get('target', 'screenshots', 'explorer.png'))
                .setFullPage(true))
    }
}
