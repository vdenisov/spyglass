package org.plukh.spyglass.spring.webmvc.browser

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Persistence of request state and per-field value history. The Authorization value goes to
 * sessionStorage (short-lived token); the other header rows and field history go to localStorage.
 * "Clear headers" clears the header rows but keeps field history (which is managed per-value via each
 * combobox's ✕).
 */
class PersistenceAE extends SpyglassSpecBase {

    def "splits the Authorization token into sessionStorage and other rows into localStorage"() {
        given:
        open('GET-/widgets/{id}')
        fillAuth('signature abc')
        page.locator('.he-actions .btn-mini.add').click()
        def row = page.locator('.he-row').last()
        row.locator('.he-key').fill('X-Env')
        row.locator('.he-val').fill('staging')

        expect: 'the token lives in sessionStorage (JSON-encoded), the other rows (auth value blanked) in localStorage'
        page.waitForFunction("() => JSON.parse(sessionStorage.getItem('apidocs-auth-token') || 'null') === 'signature abc'") != null
        page.waitForFunction("() => { const h = localStorage.getItem('apidocs-headers') || ''; " +
                "return h.includes('X-Env') && h.includes('staging') && !h.includes('signature abc') }") != null
    }

    def "restores headers after a reload (same session)"() {
        given:
        open('GET-/widgets/{id}')
        fillAuth('signature xyz')
        page.locator('.he-actions .btn-mini.add').click()
        def row = page.locator('.he-row').last()
        row.locator('.he-key').fill('X-Env')
        row.locator('.he-val').fill('staging')
        page.waitForFunction("() => (localStorage.getItem('apidocs-headers') || '').includes('X-Env')")

        when:
        open('GET-/widgets/{id}')

        then:
        page.locator('.he-row').count() == 2
        authValueInput().inputValue() == 'signature xyz'
        page.locator('.he-row').last().locator('.he-key').inputValue() == 'X-Env'
        page.locator('.he-row').last().locator('.he-val').inputValue() == 'staging'
    }

    def "Clear headers clears the header rows but keeps saved history"() {
        given:
        open('GET-/widgets/{id}')
        fillAuth('signature abc')
        page.locator('.he-actions .btn-mini.add').click()
        page.locator('.he-row').last().locator('.he-key').fill('X-Env')
        param('id').locator('.control input').fill('555')
        captureSend('**/widgets/**')
        page.waitForFunction("() => (localStorage.getItem('apidocs-field-history') || '').includes('555')")

        when:
        page.locator('.btn-clear-headers').click()

        then: 'the header rows are cleared (the explorer keeps no default Authorization row)'
        page.locator('.he-row').count() == 0

        and: 'field history is preserved (it is managed per-value, not by Clear headers)'
        page.evaluate("() => localStorage.getItem('apidocs-field-history')").toString().contains('555')
    }

    def "records used text/number field values, offered in the param's combobox (operation-scoped), excluding enums"() {
        given:
        open('GET-/widgets/{id}')
        param('id').locator('.control input').fill('999')

        when:
        captureSend('**/widgets/**')

        then: 'the value is persisted to history, scoped to this operation'
        page.waitForFunction("() => (localStorage.getItem('apidocs-field-history') || '').includes('999')") != null
        page.evaluate("() => localStorage.getItem('apidocs-field-history')").toString().contains('GET /widgets/{id}|p:path:id')

        and: "and offered as a suggestion in that param's combobox"
        param('id').locator('.combobox-caret').click()
        param('id').locator('.combobox-list li .combobox-opt').allTextContents().contains('999')

        and: 'a boolean param has no combobox (it stays a select)'
        param('verbose').locator('.combobox').count() == 0
    }

    def "lets a single history value be deleted from its combobox"() {
        given:
        open('GET-/widgets/{id}')
        param('id').locator('.control input').fill('999')
        captureSend('**/widgets/**')
        page.waitForFunction("() => (localStorage.getItem('apidocs-field-history') || '').includes('999')")

        when: 'open the dropdown and click the suggestion\'s delete affordance'
        param('id').locator('.combobox-caret').click()
        param('id').locator('.combobox-list li .combobox-del').first().click()

        then: 'that value is forgotten'
        page.waitForFunction("() => !(localStorage.getItem('apidocs-field-history') || '').includes('999')") != null
    }

    // ---- per-operation form persistence (K-2) --------------------------------

    def "per-operation parameters survive a reload"() {
        given:
        open('GET-/widgets/{id}')
        param('id').locator('.control input').fill('42')
        page.waitForFunction("() => (localStorage.getItem('apidocs-op-form') || '').includes('42')")

        when: 'a genuine reload (open() to the same hash would not reload — same-document nav)'
        page.reload()
        page.waitForSelector('.op-panel')

        then: 'the saved value is restored from the per-operation snapshot'
        param('id').locator('.control input').inputValue() == '42'
    }

    def "a form-mode request body survives a reload (rebuilt from the saved JSON)"() {
        given:
        open('POST-/widgets')
        fillRequiredWidget('Gizmo', 'HIGH', '5')
        page.waitForFunction("() => (localStorage.getItem('apidocs-op-form') || '').includes('Gizmo')")

        when:
        page.reload()
        page.waitForSelector('.op-panel')

        then: 'the body form fields are re-populated via importValue'
        textInput('name').inputValue() == 'Gizmo'
        selectInput('priority').inputValue() == 'HIGH'
        numberInput('count').inputValue() == '5'
    }

    def "a Raw JSON body survives a reload verbatim, keeping the Raw tab active"() {
        given:
        open('POST-/widgets')
        clickBodyTab('Raw JSON')
        rawFill('{"name":"persisted","priority":"HIGH","count":7}')
        page.waitForFunction("() => (localStorage.getItem('apidocs-op-form') || '').includes('persisted')")

        when:
        page.reload()
        page.waitForSelector('.op-panel')

        then: 'the editor reopens on Raw JSON with the same text'
        page.waitForSelector('.raw-body .cm-editor')
        rawText().contains('persisted')
    }

    def "the last response is kept in memory only and is gone after a reload"() {
        given:
        open('GET-/widgets/{id}')
        param('id').locator('.control input').fill('1')
        captureSend('**/widgets/**')
        assertThat(page.locator('.response')).isVisible()

        when:
        page.reload()
        page.waitForSelector('.op-panel')

        then: 'no response is restored'
        page.locator('.response').count() == 0

        and: 'and nothing response-shaped was persisted (the form snapshot carries inputs only)'
        !page.evaluate("() => localStorage.getItem('apidocs-op-form') || ''").toString().contains('"ok":true')
    }

    def "an operation's Reset clears its inputs, response and saved form"() {
        given:
        open('GET-/widgets/{id}')
        param('id').locator('.control input').fill('99')
        captureSend('**/widgets/**')
        page.waitForFunction("() => (localStorage.getItem('apidocs-op-form') || '').includes('99')")
        assertThat(page.locator('.response')).isVisible()

        when:
        page.locator('.btn-reset-op').click()

        then: 'the parameter resets to empty and the response is gone'
        param('id').locator('.control input').inputValue() == ''
        page.locator('.response').count() == 0

        and: 'and the saved snapshot is removed'
        page.waitForFunction("() => !(localStorage.getItem('apidocs-op-form') || '').includes('99')") != null
    }
}
