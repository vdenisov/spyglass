package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.FilePayload
import com.microsoft.playwright.options.SelectOption

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat

/**
 * Schema-driven form generation: a typed control per property kind, nested-object recursion,
 * arrays (primitive vs. object), free-form maps, oneOf/anyOf variant selectors, the
 * unsupported-construct fallback, rendered markdown descriptions, and the read-only Schema tab.
 */
class FormGenerationAE extends SpyglassSpecBase {

    def setup() {
        open('POST-/widgets')
    }

    def "renders a typed, constrained control for each property kind"() {
        expect: 'required string: input, no include toggle, marked required'
        textInput('name').count() == 1
        includeBox('name').count() == 0
        field('name').locator('.req').count() == 1

        and: 'optional string: include toggle, input disabled until included, constraints surfaced'
        includeBox('code').count() == 1
        textInput('code').isDisabled()
        textInput('code').getAttribute('minlength') == '2'
        textInput('code').getAttribute('maxlength') == '5'
        constraint('code').textContent() == '2–5 chars, pattern: ^[A-Z]+$'

        and: 'number: bounds surfaced, no include toggle'
        numberInput('count').getAttribute('min') == '1'
        numberInput('count').getAttribute('max') == '10'
        constraint('count').textContent() == '1–10, ×1'

        and: 'optional enum: include toggle, only the declared values'
        includeBox('status').count() == 1
        selectInput('status').locator('option').allTextContents() == ['NEW', 'ACTIVE', 'ARCHIVED']

        and: 'required enum: a placeholder option, no include toggle'
        includeBox('priority').count() == 0
        selectInput('priority').locator('option').allTextContents() == ['— choose —', 'LOW', 'HIGH']

        and: 'optional boolean: include toggle and a true/false select'
        includeBox('active').count() == 1
        selectInput('active').locator('option').allTextContents() == ['true', 'false']
    }

    def "renders a nested object with its child fields (recursion)"() {
        expect:
        field('address').getAttribute('class').contains('kind-object')
        includeBox('address').count() == 1
        field('street').count() == 1
        field('city').count() == 1
    }

    def "renders a primitive array as a textarea and an object array as item cards"() {
        expect: 'primitive arrays are textareas'
        arrayText('labels').count() == 1
        arrayText('scores').count() == 1

        and: 'an object array starts empty'
        arrayItems('items').count() == 0

        when: 'an item is added'
        arrayAdd('items')

        then: 'a card appears with the nested object fields'
        assertThat(arrayItems('items')).hasCount(1)
        arrayItems('items').first().locator('.array-index').textContent() == '[0]'
        assertThat(field('sku')).hasCount(1)
        assertThat(field('qty')).hasCount(1)
    }

    def "renders a free-form map as key/value rows"() {
        expect:
        mapEntries('metadata').count() == 0

        when:
        mapAdd('metadata')

        then:
        assertThat(mapEntries('metadata')).hasCount(1)
        mapEntries('metadata').first().locator('.map-key').count() == 1
        mapEntries('metadata').first().locator('.map-value input').count() == 1
    }

    def "renders a oneOf property as a variant selector with one option per branch"() {
        expect:
        field('payload').getAttribute('class').contains('kind-variant')
        field('payload').locator('.variant-select option').count() == 2
    }

    def "renders markdown in object and field descriptions"() {
        expect: 'the body description renders **widget** as bold'
        page.locator('.form-body .field-desc strong').first().textContent() == 'widget'

        and: 'the description field renders its own markdown'
        field('description').locator('.field-desc strong').textContent() == 'notes'
    }

    def "documents the request schema and example in the Schema tab"() {
        when:
        page.locator('.op-tabs button', new Page.LocatorOptions().setHasText('Schema')).click()

        then: 'the schema tree lists the properties'
        page.locator('.schema-doc .stree-name').allTextContents().containsAll(['name', 'priority', 'count', 'address'])

        when: 'switching to the Examples view'
        page.locator('.schema-toggle button', new Page.LocatorOptions().setHasText('Examples')).click()

        then: 'the request body is shown as a generated example'
        assertThat(page.locator('.example-json').first()).containsText('"name"')
    }

    def "merges allOf members, collapses 3.1 nullable types, prefills defaults, and guards recursion"() {
        given:
        open('POST-/composed')

        expect: 'allOf brings the Base property and the inline properties into one form'
        field('baseId').count() == 1
        field('note').count() == 1
        field('kind').count() == 1

        and: 'a 3.1 ["string","null"] type renders as a plain string field'
        field('note').getAttribute('class').contains('kind-string')

        and: 'a field with a default is prefilled and pre-included'
        textInput('kind').inputValue() == 'STD'
        includeBox('kind').isChecked()

        and: 'a recursive $ref falls back to the unsupported notice'
        field('child').getAttribute('class').contains('kind-unsupported')
        field('child').textContent().contains('recursive')
    }

    // ---- oneOf/anyOf variant forms -------------------------------------------

    def "labels a discriminated oneOf body by its discriminator mapping and prefills the value"() {
        given:
        open('POST-/animals')

        expect: 'the variant options are the discriminator mapping keys'
        page.locator('.form-body .variant-select option').allTextContents() == ['dog', 'cat']

        and: 'the first branch renders its fields with the discriminator prefilled (and editable)'
        field('bark').count() == 1
        textInput('species').inputValue() == 'dog'
        !textInput('species').isDisabled()
    }

    def "switching the discriminator variant swaps the branch and re-prefills the discriminator"() {
        given:
        open('POST-/animals')

        when:
        page.locator('.form-body .variant-select select').selectOption(new SelectOption().setIndex(1))

        then:
        field('bark').count() == 0
        field('lives').count() == 1
        textInput('species').inputValue() == 'cat'
    }

    def "sends the selected discriminator branch as the body"() {
        given:
        open('POST-/animals')
        textInput('bark').fill('woof')

        when:
        def b = body(captureSend('**/animals'))

        then:
        b.species == 'dog'
        b.bark == 'woof'
    }

    def "renders an anyOf of object branches as multi-include checkboxes"() {
        given:
        open('POST-/notify')

        expect: 'a checkbox per branch, labelled by schema name'
        page.locator('.form-body .variant-branch').count() == 2
        page.locator('.form-body .variant-branch-name').allTextContents() == ['EmailChannel', 'SmsChannel']

        and: 'the first branch is pre-checked (body is required) and shows its fields; the second is off'
        branchBox('EmailChannel').isChecked()
        !branchBox('SmsChannel').isChecked()
        field('email').count() == 1
        field('phone').count() == 0

        when: 'checking the second branch'
        branchBox('SmsChannel').check()

        then: 'both branch forms render together'
        field('email').count() == 1
        field('phone').count() == 1
    }

    def "keeps a scalar anyOf body single-select (not mergeable)"() {
        given:
        open('POST-/measure')

        expect: 'the single-select dropdown is used, not the multi-branch checkboxes'
        page.locator('.form-body .variant-select option').count() == 2
        page.locator('.form-body .variant-branch').count() == 0

        and: 'the "combine via Raw JSON" hint is shown'
        assertThat(page.locator('.form-body .variant-note')).isVisible()
    }

    def "merges the checked anyOf branches into one request body"() {
        given:
        open('POST-/notify')
        textInput('email').fill('a@b.com')
        branchBox('SmsChannel').check()
        textInput('phone').fill('123')

        when:
        def b = body(captureSend('**/notify'))

        then:
        b.email == 'a@b.com'
        b.phone == '123'
    }

    def "hovering a type tag shows the themed tooltip (not the native title)"() {
        given:
        open('POST-/animals')

        when: 'hovering the oneOf type tag'
        page.locator('.form-body .type-tag').first().hover()

        then: 'our themed tooltip appears with the construct explanation'
        assertThat(page.locator('.app-tooltip.visible')).isVisible()
        assertThat(page.locator('.app-tooltip.visible')).containsText('oneOf')

        and: 'the tag carries no native title attribute'
        page.locator('.form-body .type-tag').first().getAttribute('title') == null
    }

    // ---- spec-provided examples (hints + Schema → Examples gallery) -----------

    def "surfaces a field's example as a placeholder hint without prefilling the value"() {
        given:
        open('POST-/examples')

        expect: 'examples become placeholder hints across kinds (string / number / array)'
        textInput('name').getAttribute('placeholder') == 'widget'
        numberInput('count').getAttribute('placeholder') == '5'
        arrayText('tags').getAttribute('placeholder') == 'a, b'

        and: 'the value itself stays empty — examples only hint'
        textInput('name').inputValue() == ''
        arrayText('tags').inputValue() == ''
    }

    def "hints a parameter's singular example as a placeholder, leaving the field empty"() {
        given:
        open('POST-/examples')

        expect:
        param('limit').locator('.combobox-input').getAttribute('placeholder') == '20'
        param('limit').locator('.combobox-input').inputValue() == ''
    }

    def "renders request examples in the gallery and prefills the Raw JSON editor"() {
        given:
        open('POST-/examples')
        openExamplesTab()

        expect: 'each named request example is a card'
        page.locator('.request-examples .example-card .example-name').allTextContents() == ['minimal', 'full', 'external']

        when: 'prefilling from the "full" example'
        page.locator(".request-examples .example-card:has(.example-name:text-is('full')) .example-prefill").click()

        then: 'it jumps to Try-it-out, Raw JSON mode, with the exact value loaded'
        page.locator('.body-head .body-tabs button.active').textContent() == 'Raw JSON'
        rawText().contains('"name": "deluxe widget"')
        rawText().contains('"count": 7')
    }

    def "shows an external example as a link with no prefill button"() {
        given:
        open('POST-/examples')
        openExamplesTab()
        def ext = page.locator(".request-examples .example-card:has(.example-name:text-is('external'))")

        expect:
        ext.locator('.example-ext').getAttribute('href') == 'https://example.invalid/sample-payload.json'
        ext.locator('.example-prefill').count() == 0
    }

    def "lists parameter example cards and keeps response examples read-only"() {
        given:
        open('POST-/examples')
        openExamplesTab()

        expect: 'the filter parameter group lists its named examples'
        page.locator(".schema-block:has(.example-group-head .stree-name:text-is('filter')) .example-card .example-name").allTextContents() == ['by-name', 'by-id']

        and: 'the response example cards are present and never prefillable'
        page.locator('.schema-doc').textContent().contains('Response 200')
        page.locator(".response-examples .example-card:has(.example-name:text-is('created'))").count() == 1
        page.locator(".response-examples .example-card:has(.example-name:text-is('archived'))").count() == 1
        page.locator(".response-examples .example-card .example-prefill").count() == 0
    }

    def "fills a query parameter from its example without leaving the Examples tab"() {
        given:
        open('POST-/examples')
        openExamplesTab()
        def card = page.locator(".schema-block:has(.example-group-head .stree-name:text-is('filter')) .example-card:has(.example-name:text-is('by-id'))")

        when: 'using the "by-id" example for the filter parameter'
        card.locator('.example-prefill').click()

        then: 'a transient confirmation shows and the Examples tab stays active (no jump)'
        assertThat(card.locator('.example-applied')).isVisible()
        page.locator('.schema-toggle button.active').textContent() == 'Examples'

        when: 'switching to Try-it-out'
        page.locator('.op-tabs button', new Page.LocatorOptions().setHasText('Try')).click()

        then: 'the filter field is filled with the example value'
        param('filter').locator('.combobox-input').inputValue() == '42'
    }

    // ---- non-JSON request bodies (multipart / urlencoded) --------------------

    def "renders a multipart body with single + multi file inputs and a text field"() {
        given:
        open('POST-/uploads')

        expect: 'the multipart encoding is announced'
        assertThat(page.locator('.body-encoding-note')).containsText('multipart/form-data')

        and: 'a single-file input, a multi-file input and a plain text field'
        field('file').getAttribute('class').contains('kind-file')
        field('file').locator('input[type=file]').getAttribute('multiple') == null
        field('files').locator('input[type=file]').getAttribute('multiple') != null
        field('note').getAttribute('class').contains('kind-string')
    }

    def "lets a single chosen file be cleared via its remove cross"() {
        given:
        open('POST-/uploads')
        field('file').locator('input[type=file]').setInputFiles(
                new FilePayload('only.txt', 'text/plain', 'x'.getBytes('UTF-8')))

        expect:
        field('file').locator('.file-list li .file-name').allTextContents() == ['only.txt']

        when:
        field('file').locator('.file-list li .btn-mini').click()

        then:
        field('file').locator('.file-list li').count() == 0
    }

    def "accumulates multi-file selections across picks and removes them individually"() {
        given:
        open('POST-/uploads')
        def input = field('files').locator('input[type=file]')

        when: 'files are chosen in two separate selections (e.g. different folders)'
        input.setInputFiles(new FilePayload('a.txt', 'text/plain', 'a'.getBytes('UTF-8')))
        input.setInputFiles(new FilePayload('b.txt', 'text/plain', 'bb'.getBytes('UTF-8')))

        then: 'both are retained, not replaced'
        field('files').locator('.file-list li .file-name').allTextContents() == ['a.txt', 'b.txt']

        when: 'one is removed'
        field('files').locator('.file-list li').first().locator('.btn-mini').click()

        then:
        field('files').locator('.file-list li .file-name').allTextContents() == ['b.txt']
    }

    def "renders a urlencoded body as a plain field form"() {
        given:
        open('POST-/form')

        expect:
        assertThat(page.locator('.body-encoding-note')).containsText('x-www-form-urlencoded')
        field('name').count() == 1
        field('count').count() == 1
    }

    def "offers a media-type selector when several body encodings are declared"() {
        given:
        open('POST-/dual')

        expect: 'both encodings listed, JSON first (its Form/Raw tabs shown, no encoding note)'
        page.locator('.media-select option').allTextContents() == ['application/json', 'application/x-www-form-urlencoded']
        page.locator('.body-head .body-tabs').count() == 1
        page.locator('.body-encoding-note').count() == 0

        when: 'switching to the urlencoded encoding'
        page.locator('.media-select').selectOption('application/x-www-form-urlencoded')

        then: 'the JSON tabs give way to the urlencoded note'
        assertThat(page.locator('.body-encoding-note')).containsText('x-www-form-urlencoded')
        page.locator('.body-head .body-tabs').count() == 0
    }

    // ---- non-blocking validation warnings (Form mode) ------------------------
    //
    // Advisory only: warnings surface missing required fields and simple constraint violations, but
    // Send is never disabled — a deliberately invalid request must still be sendable.

    def "warns about missing required body fields but still allows sending"() {
        expect:
        assertThat(page.locator('.warnings')).isVisible()
        page.locator('.warnings li').count() >= 1
        !page.locator('.btn-send').isDisabled()

        and: 'the body path renders without the JSONPath root'
        page.locator('.warnings li code').allTextContents().contains('name')
        !page.locator('.warnings').textContent().contains('$')

        when:
        def cap = captureSend('**/widgets')

        then:
        cap.method == 'POST'
    }

    def "warns when a value violates a simple constraint"() {
        given:
        fillRequiredWidget()
        numberInput('count').fill('99')          // schema maximum is 10

        expect:
        assertThat(page.locator('.warnings')).containsText('above maximum')
    }

    def "drops the warnings once required fields are filled"() {
        when:
        fillRequiredWidget()

        then:
        page.locator('.warnings').count() == 0
    }

    // ---- object with both fixed properties and additionalProperties ----------

    def "renders fixed properties alongside an additionalProperties map editor"() {
        given:
        open('POST-/config')

        expect: 'the declared fields render'
        field('id').count() == 1
        field('label').count() == 1

        and: 'a free-form map editor sits alongside them for the extra keys'
        field('additional properties').getAttribute('class').contains('kind-map')
        mapEntries('additional properties').count() == 0
    }

    def "sends declared fields and extra free-form keys together"() {
        given:
        open('POST-/config')
        textInput('id').fill('cfg-1')

        when: 'an extra key is added via the additionalProperties editor'
        mapAdd('additional properties')
        mapEntries('additional properties').first().locator('.map-key').fill('region')
        mapEntries('additional properties').first().locator('.map-value input').fill('eu')

        then: 'both the declared id and the extra key appear in the body'
        def b = body(captureSend('**/config'))
        b.id == 'cfg-1'
        b.region == 'eu'
    }

    def "a declared property wins a key collision with the additionalProperties map"() {
        given:
        open('POST-/config')
        textInput('id').fill('cfg-1')

        when: 'an extra entry reuses a declared field name'
        mapAdd('additional properties')
        mapEntries('additional properties').first().locator('.map-key').fill('id')
        mapEntries('additional properties').first().locator('.map-value input').fill('SHOULD-NOT-WIN')

        then: 'the declared field value is kept'
        body(captureSend('**/config')).id == 'cfg-1'
    }

    def "documents the additionalProperties capability in the Schema tab"() {
        given:
        open('POST-/config')

        when:
        page.locator('.op-tabs button', new Page.LocatorOptions().setHasText('Schema')).click()

        then: 'the type tree lists the declared fields and notes the free-form map'
        page.locator('.schema-doc .stree-name').allTextContents().containsAll(['id', 'label', 'additional properties'])
        assertThat(page.locator('.schema-doc .stree-additional .stree-type')).containsText('map<string>')
    }

    // ---- deep polymorphic allOf (a base that itself extends a common root) ----

    def "inherits a polymorphic base's ancestor (allOf root) scalars into each variant branch"() {
        given:
        open('POST-/conveyances')

        expect: 'the base renders as a discriminated variant selector'
        page.locator('.form-body .variant-select option').allTextContents() == ['car', 'bike']

        and: 'the car branch carries the root vin, the base mode (prefilled), and its own doors'
        field('vin').count() == 1
        field('doors').count() == 1
        textInput('mode').inputValue() == 'car'

        when: 'switching to the bike branch'
        page.locator('.form-body .variant-select select').selectOption(new SelectOption().setIndex(1))

        then: 'the inherited root vin and base mode persist; only the branch-specific field swaps'
        field('vin').count() == 1
        field('gears').count() == 1
        field('doors').count() == 0
        textInput('mode').inputValue() == 'bike'
    }

    def "sends the deep-inherited scalars in the selected branch body"() {
        given:
        open('POST-/conveyances')
        textInput('vin').fill('VIN123')
        numberInput('doors').fill('4')

        when:
        def b = body(captureSend('**/conveyances'))

        then: 'the root vin, discriminator mode, and branch-specific doors all serialize'
        b.vin == 'VIN123'
        b.mode == 'car'
        b.doors == 4
    }

    // ---- helpers -------------------------------------------------------------

    /** The include checkbox of a multi-branch anyOf branch addressed by its label. */
    private Locator branchBox(String name) {
        page.locator('.form-body .variant-branch', new Page.LocatorOptions().setHasText(name))
                .locator('.variant-branch-head input[type=checkbox]')
    }

    /** Opens the Schema tab and switches to its Examples view. */
    private void openExamplesTab() {
        page.locator('.op-tabs button', new Page.LocatorOptions().setHasText('Schema')).click()
        page.locator('.schema-toggle button', new Page.LocatorOptions().setHasText('Examples')).click()
    }
}
