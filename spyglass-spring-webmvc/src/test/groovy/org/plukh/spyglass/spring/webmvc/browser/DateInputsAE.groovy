package org.plukh.spyglass.spring.webmvc.browser

/**
 * Date / date-time fields. For API testing they must be verbatim text inputs (no native picker that
 * would reject invalid input or coerce precision): any value is sent unchanged, the placeholder hints
 * the expected format (ISO 8601 with millisecond precision by default, or whatever the spec provides),
 * and a spec-supplied example is surfaced as a placeholder hint (it does not prefill the value).
 */
class DateInputsAE extends SpyglassSpecBase {

    def setup() {
        open('POST-/events')
    }

    def "renders date and date-time as plain text inputs, not native pickers"() {
        expect:
        textInput('at').getAttribute('type') == 'text'
        textInput('on').getAttribute('type') == 'text'
    }

    def "hints the expected format, defaulting to ISO 8601 with millisecond precision"() {
        expect:
        textInput('at').getAttribute('placeholder') == '2026-01-02T15:04:05.000Z'
        textInput('on').getAttribute('placeholder') == '2026-01-02'
    }

    def "surfaces a spec-provided example as a placeholder hint, not a prefilled value"() {
        expect: 'the example is the placeholder hint, and the field stays empty / omitted'
        textInput('eventAt').getAttribute('placeholder') == '2020-01-01T00:00:00.000Z'
        textInput('eventAt').inputValue() == ''
        !includeBox('eventAt').isChecked()
    }

    def "passes an arbitrary / invalid value verbatim"() {
        given:
        includeBox('at').check()
        textInput('at').fill('abcd')

        when:
        def b = body(captureSend('**/events'))

        then:
        b.at == 'abcd'
    }

    def "passes a valid ISO 8601 millisecond timestamp verbatim, preserving precision"() {
        given:
        includeBox('at').check()
        textInput('at').fill('2026-06-15T13:45:30.123Z')

        when:
        def b = body(captureSend('**/events'))

        then:
        b.at == '2026-06-15T13:45:30.123Z'
    }
}
