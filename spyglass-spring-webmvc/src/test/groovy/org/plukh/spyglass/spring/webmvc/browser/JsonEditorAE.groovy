package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * CodeMirror-specific behaviour of the raw-JSON editor: it mounts lazily, validates the body against
 * the operation's JSON schema inline, and offers schema-aware autocomplete (property names and enum
 * values) resolved through the hoisted {@code components} namespace.
 */
class JsonEditorAE extends SpyglassSpecBase {

    private void requestCompletion() {
        rawEditor().click()
        page.keyboard().press('Control+A')
    }

    private Locator completionOption(String label) {
        page.locator('.cm-tooltip-autocomplete .cm-completionLabel', new Page.LocatorOptions().setHasText(label))
    }

    def "mounts the editor only when the Raw JSON tab is opened"() {
        given:
        open('POST-/widgets')

        expect: 'the form renders without the lazily-loaded CodeMirror editor in the DOM'
        assertThat(page.locator('.cm-editor')).hasCount(0)

        when:
        clickBodyTab('Raw JSON')

        then:
        assertThat(rawEditor()).isVisible()
    }

    def "flags a schema violation inline"() {
        given:
        open('POST-/widgets')
        clickBodyTab('Raw JSON')

        when: 'count exceeds its schema maximum of 10'
        rawFill('{"name":"W","priority":"HIGH","count":99}')

        then:
        assertThat(page.locator('.cm-lint-marker-error').first()).isVisible()
    }

    def "highlights a JSON syntax error inline in real time"() {
        given:
        open('POST-/widgets')
        clickBodyTab('Raw JSON')

        when: 'the content satisfies the schema but is syntactically incomplete (missing brace)'
        rawFill('{"name":"W","priority":"HIGH","count":3')

        then: 'the parse linter flags it without needing to Send or switch tabs'
        assertThat(page.locator('.cm-lint-marker-error').first()).isVisible()
    }

    def "flags a wrong-typed value inline"() {
        given:
        open('POST-/widgets')
        clickBodyTab('Raw JSON')

        when: 'name should be a string, not a number'
        rawFill('{"name":123,"priority":"HIGH","count":3}')

        then:
        assertThat(page.locator('.cm-lint-marker-error').first()).isVisible()
    }

    def "shows no error for a body that satisfies the schema"() {
        given:
        open('POST-/widgets')
        clickBodyTab('Raw JSON')
        // A body that does violate the schema, so we can wait for the marker to appear...
        rawFill('{"name":"W","priority":"HIGH","count":99}')
        assertThat(page.locator('.cm-lint-marker-error').first()).isVisible()

        when: '...then correct it'
        rawFill('{"name":"W","priority":"HIGH","count":3}')

        then: 'the error marker clears'
        assertThat(page.locator('.cm-lint-marker-error')).hasCount(0)
    }

    def "suggests schema property names via autocomplete"() {
        given:
        open('POST-/widgets')
        clickBodyTab('Raw JSON')

        when: 'an empty key is opened inside the object and completion is requested'
        requestCompletion()
        page.keyboard().insertText('{}')
        page.keyboard().press('ArrowLeft')
        page.keyboard().type('"')
        page.keyboard().press('Control+Space')

        then:
        assertThat(completionOption('priority')).isVisible()
        assertThat(completionOption('count')).isVisible()
    }

    def "suggests enum values via autocomplete"() {
        given:
        open('POST-/widgets')
        clickBodyTab('Raw JSON')

        when: 'completion is requested inside the priority value'
        requestCompletion()
        page.keyboard().insertText('{"priority":""}')
        page.keyboard().press('ArrowLeft')
        page.keyboard().press('ArrowLeft')
        page.keyboard().press('Control+Space')

        then:
        assertThat(completionOption('LOW')).isVisible()
        assertThat(completionOption('HIGH')).isVisible()
    }
}
