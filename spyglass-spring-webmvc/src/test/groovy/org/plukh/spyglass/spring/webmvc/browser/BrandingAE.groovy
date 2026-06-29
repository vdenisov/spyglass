package org.plukh.spyglass.spring.webmvc.browser

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Verifies the footer branding seam: the built-in Spyglass mark shows by default and is turned off
 * through the standard config chain (query param / window.SPYGLASS_CONFIG / spec x-spyglass-config),
 * with the operator layers winning over each other in the documented precedence. Extension-contributed
 * footer items (registerFooterItem) are covered separately by {@code ExtensionSeamAE}.
 */
class BrandingAE extends SpyglassSpecBase {

    private void openWithBranding(String query) {
        page.navigate('/apidocs/index.html?' + query)
        page.waitForSelector('.sidebar .op-link')
    }

    def "by default the sidebar footer shows the Spyglass mark"() {
        when:
        open()

        then:
        assertThat(page.locator('.foot-brand .foot-name')).hasText('Spyglass')
        page.locator('.foot-link').count() == 1
    }

    def "?branding=off hides the brand line, version and GitHub link"() {
        when:
        openWithBranding('branding=off')

        then: 'every built-in mark is gone and, with no footer items, the whole footer bar collapses'
        page.locator('.foot-brand').count() == 0
        page.locator('.foot-version').count() == 0
        page.locator('.foot-link').count() == 0
        page.locator('.sidebar-foot').count() == 0
    }

    def "window.SPYGLASS_CONFIG.branding.show=false hides the built-in mark"() {
        given:
        page.addInitScript('window.SPYGLASS_CONFIG = { branding: { show: false } }')

        when:
        open()

        then:
        page.locator('.foot-brand').count() == 0
        page.locator('.sidebar-foot').count() == 0
    }

    def "a query ?branding=on overrides a global show=false (query wins)"() {
        given:
        page.addInitScript('window.SPYGLASS_CONFIG = { branding: { show: false } }')

        when:
        openWithBranding('branding=on')

        then:
        assertThat(page.locator('.foot-brand .foot-name')).hasText('Spyglass')
    }

    def "resolveBrandingConfig folds the layers: default on, spec layer applies, stray fields ignored"() {
        given: 'a clean load — no branding query param, no SPYGLASS_CONFIG — so only defaults and the passed spec layer apply'
        open()

        expect:
        page.evaluate('''async () => {
            const m = await import('/apidocs/js/config.js')
            const f = m.resolveBrandingConfig
            return {
                byDefault   : f(null).show,
                specHide    : f({ info: { 'x-spyglass-config': { branding: { show: false } } } }).show,
                specShow    : f({ info: { 'x-spyglass-config': { branding: { show: true } } } }).show,
                strayIgnored: f({ info: { 'x-spyglass-config': { branding: { bogus: 1 } } } }).show
            }
        }''') == [
                byDefault   : true,
                specHide    : false,
                specShow    : true,
                strayIgnored: true
        ]
    }
}
