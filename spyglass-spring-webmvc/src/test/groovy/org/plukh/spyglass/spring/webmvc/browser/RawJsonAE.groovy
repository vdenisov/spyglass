package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Page

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * The raw-JSON editor: round-tripping with the form over a single source of truth, validation when
 * switching back to the form, and the forced-raw fallback for an unsupported body.
 */
class RawJsonAE extends SpyglassSpecBase {

    def "mirrors the form into the raw editor"() {
        given:
        open('POST-/widgets')
        fillRequiredWidget()
        includeBox('description').check()
        textInput('description').fill('hi')

        when:
        clickBodyTab('Raw JSON')

        then:
        def raw = rawText()
        raw.contains('"name": "W"')
        raw.contains('"description": "hi"')
    }

    def "imports raw edits back into the form"() {
        given:
        open('POST-/widgets')
        fillRequiredWidget()
        clickBodyTab('Raw JSON')

        when:
        rawFill('{"name":"W2","priority":"HIGH","count":5,"description":"changed"}')
        clickBodyTab('Form')

        then:
        textInput('name').inputValue() == 'W2'
        numberInput('count').inputValue() == '5'
        selectInput('priority').inputValue() == 'HIGH'
        textInput('description').inputValue() == 'changed'
    }

    def "flags invalid JSON when switching back to the form"() {
        given:
        open('POST-/widgets')
        clickBodyTab('Raw JSON')

        when:
        rawFill('{ not valid')
        clickBodyTab('Form')

        then:
        page.locator('.raw-error').textContent().contains('Invalid JSON')
        rawEditor().isVisible()
    }

    def "forces raw JSON for an unsupported (mixed-type) request body"() {
        when:
        open('POST-/things')

        then:
        page.locator('.body-section > .unsupported').textContent().contains("can't be rendered as a form")
        page.locator('.body-head .body-tabs button', new Page.LocatorOptions().setHasText('Form')).isDisabled()
        rawEditor().isVisible()
    }

    def "sends the edited raw body verbatim"() {
        given:
        open('POST-/widgets')
        fillRequiredWidget()
        clickBodyTab('Raw JSON')
        rawFill('{"name":"RAW","priority":"LOW","count":9}')

        when:
        def b = body(captureSend('**/widgets'))

        then:
        b == [name: 'RAW', priority: 'LOW', count: 9]
    }

    def "copies a curl command that includes the request body"() {
        given:
        open('POST-/widgets')
        fillRequiredWidget()

        when:
        page.locator('button', new Page.LocatorOptions().setHasText('Copy as cURL')).click()

        then:
        def clip = page.evaluate('() => navigator.clipboard.readText()') as String
        clip.contains('curl -X POST')
        clip.contains("-H 'Content-Type: application/json'")
        clip.contains('"name":"W"')
    }

    def "imports collections from raw JSON back into the form"() {
        given:
        open('POST-/widgets')
        fillRequiredWidget()
        clickBodyTab('Raw JSON')
        rawFill('{"name":"W","priority":"HIGH","count":3,"labels":["p","q"],"metadata":{"m":"n"},"items":[{"sku":"Z","qty":1}]}')

        when:
        clickBodyTab('Form')

        then:
        assertThat(arrayText('labels')).hasValue('p\nq')
        assertThat(mapEntries('metadata').first().locator('.map-key')).hasValue('m')
        assertThat(mapEntries('metadata').first().locator('.map-value input')).hasValue('n')
        assertThat(textInput('sku')).hasValue('Z')
        assertThat(numberInput('qty')).hasValue('1')
    }
}
