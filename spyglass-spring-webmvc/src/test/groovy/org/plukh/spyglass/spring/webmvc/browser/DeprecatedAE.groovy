package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Page

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Deprecated indicators and adjacent spec metadata the explorer surfaces: deprecated sidebar entries
 * and operation banner, externalDocs links, parameter descriptions + deprecated markers, response
 * header docs and deprecated schema fields. Driven by the fixture's GET /legacy/{id}.
 */
class DeprecatedAE extends SpyglassSpecBase {

    def "marks deprecated operations in the sidebar"() {
        given:
        open()

        expect:
        assertThat(page.locator('.op-link.deprecated').first()).isVisible()
        page.locator('.op-link.deprecated .op-path').first().textContent().contains('/legacy/')

        and: 'a compact deprecated badge, not a strikethrough'
        assertThat(page.locator('.op-link.deprecated .sidebar-dep').first()).isVisible()
    }

    def "shows a deprecated banner and an externalDocs link on the operation"() {
        given:
        open('GET-/legacy/{id}')

        expect:
        assertThat(page.locator('.deprecated-banner')).isVisible()
        assertThat(page.locator('.op-extdocs a')).isVisible()
        page.locator('.op-extdocs a').getAttribute('href') == 'https://docs.test.local/migrate'
    }

    def "shows the parameter description and a deprecated marker"() {
        given:
        open('GET-/legacy/{id}')

        expect:
        assertThat(param('legacyId')).containsText('deprecated')
        assertThat(page.locator('.params')).containsText('use id instead')
    }

    def "documents response headers and deprecated schema fields in the Schema tab"() {
        given:
        open('GET-/legacy/{id}')

        when:
        page.locator('.op-tabs button', new Page.LocatorOptions().setHasText('Schema')).click()

        then:
        assertThat(page.locator('.resp-headers-doc')).containsText('X-Trace-Id')
        assertThat(page.locator('.schema-doc')).containsText('oldField')
        assertThat(page.locator('.schema-doc .dep-tag').first()).isVisible()
    }
}
