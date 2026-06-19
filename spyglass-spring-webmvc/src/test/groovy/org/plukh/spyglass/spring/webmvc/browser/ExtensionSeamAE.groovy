package org.plukh.spyglass.spring.webmvc.browser

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Verifies the front-end extension seam end-to-end with a consumer-neutral probe extension (served from
 * test resources at /spyglass-ext/probe/index.js). Exercises query-param discovery (?ext=) plus all
 * three seam hooks: registerAuthPanel, registerHeaderPresets, and the api.headers bridge.
 */
class ExtensionSeamAE extends SpyglassSpecBase {

    private void openWithProbe() {
        page.navigate('/apidocs/index.html?ext=/spyglass-ext/probe/index.js')
        page.waitForSelector('.sidebar .op-link')
    }

    def "loads an extension by query param and renders its registered auth panel"() {
        when:
        openWithProbe()

        then:
        assertThat(page.locator('.probe-panel .probe-marker')).hasText('probe-extension-loaded')
    }

    def "the extension's registered header preset adds a header row"() {
        given:
        openWithProbe()

        when:
        page.locator('.he-platform').selectOption('X-Probe-Header')

        then:
        assertThat(page.locator('.he-row .he-key').last()).hasValue('X-Probe-Header')
    }

    def "the extension panel drives the Authorization row through the seam"() {
        given:
        openWithProbe()

        when:
        page.click('.probe-apply')

        then:
        assertThat(authValueInput()).hasValue('probe-token')
    }

    def "without an extension the explorer loads with no auth panel or preset dropdown"() {
        when:
        open()

        then:
        page.locator('.probe-panel').count() == 0
        page.locator('.he-platform').count() == 0
    }
}
