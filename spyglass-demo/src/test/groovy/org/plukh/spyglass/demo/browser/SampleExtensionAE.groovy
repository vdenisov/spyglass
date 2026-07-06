package org.plukh.spyglass.demo.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import spock.lang.Requires

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.function.Consumer

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * End-to-end check of the bundled, self-contained sample extension (served from
 * {@code /spyglass-ext/demo/index.js} and advertised by the demo spec's {@code x-spyglass-extensions}
 * info extension). Unlike {@code ExtensionSeamAE} — which loads the test probe via {@code ?ext=} — this
 * proves the spec-advertised, auto-load path with no query param, and exercises the seam hooks the
 * sample registers: an auth panel, a header preset, a response-header link resolver, and a footer item.
 * It doubles as the capture point for the README light/dark hero screenshots.
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

    def "the sample extension's footer item renders in the sidebar footer"() {
        when:
        open()

        then:
        assertThat(page.locator('.sidebar-foot .demo-foot-marker')).containsText('Demo Extension')
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

    @Requires({ sys['spyglass.captureScreenshots'] == 'true' })
    // Opt-in doc-artifact generator, not a behavioural check — excluded from normal runs so the
    // suite stays behaviour-only. Regenerate the README heroes with:
    //   mvn -pl spyglass-demo -am verify -Dspyglass.captureScreenshots=true -Dit.test=SampleExtensionAE
    // then copy target/screenshots/explorer-*.png into docs/assets/.
    def "captures the representative light and dark hero screenshots for the docs"() {
        given: 'a landscape viewport — tight on the right, tall enough to keep the Send button in frame'
        page.setViewportSize(1080, 780)

        and: 'a form-forward operation is open (a oneOf/discriminator body, no query params)'
        open('POST-/apidocs-demo/shapes')
        page.waitForSelector('.op-panel .body-section')

        when: 'each theme is forced and a viewport (not full-page) screenshot is written per theme'
        Map<String, Path> shots = ['Light': screenshotPath('explorer-light.png'),
                                   'Dark' : screenshotPath('explorer-dark.png')]
        shots.each { label, path ->
            page.locator(".theme-toggle button[aria-label='Theme: ${label}']").click()
            page.waitForSelector("html[data-theme='${label.toLowerCase()}']")
            page.screenshot(new Page.ScreenshotOptions().setPath(path))
        }

        then: 'both hero images land in the build dir for docs/assets'
        shots.values().every { Files.exists(it) }
    }

    private static Path screenshotPath(String name) {
        Paths.get('target', 'screenshots', name)
    }
}
