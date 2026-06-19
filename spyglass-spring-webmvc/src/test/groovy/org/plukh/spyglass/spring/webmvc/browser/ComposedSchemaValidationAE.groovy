package org.plukh.spyglass.spring.webmvc.browser

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Verifies the Raw-JSON editor's schema-validation engine (codemirror-json-schema) against a composed
 * schema in the fixture: the {@code anyOf} body of {@code POST /notify}. A body matching no branch is
 * flagged inline; a body matching one branch raises no marker.
 *
 * <p>Note on {@code oneOf}: the fixture's discriminated {@code oneOf} ({@code POST /animals}, the cyclic
 * "base + variant {@code allOf}" shape) is <em>not</em> enforced by the editor's validator — confirmed
 * manually, a no-match body raises no marker either. The schema-driven <em>form</em> handles
 * {@code oneOf}/{@code discriminator} via its variant selector; the editor is permissive there (it
 * won't wrongly block a valid body, but won't catch an invalid one). There is no positive signal to
 * assert, so no {@code oneOf} case is included here; add one if the editor ever starts enforcing it.
 */
class ComposedSchemaValidationAE extends SpyglassSpecBase {

    def "anyOf: flags a body that matches no branch"() {
        given:
        open('POST-/notify')
        clickBodyTab('Raw JSON')

        when:
        rawFill('{}')

        then:
        assertThat(page.locator('.cm-lint-marker-error').first()).isVisible()
    }

    def "anyOf: accepts a body that matches one branch"() {
        given:
        open('POST-/notify')
        clickBodyTab('Raw JSON')
        rawFill('{}')
        assertThat(page.locator('.cm-lint-marker-error').first()).isVisible()

        when:
        rawFill('{"email":"user@example.com","subject":"Hello"}')

        then:
        assertThat(page.locator('.cm-lint-marker-error')).hasCount(0)
    }
}
