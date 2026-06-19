package org.plukh.spyglass.spring.webmvc.browser

/**
 * Omit-vs-empty serialization — the explorer's defining behaviour. Optional body fields are dropped
 * until explicitly included (then sent even when blank/false); required fields are always sent;
 * collections serialize their entries.
 */
class OmitVsEmptyAE extends SpyglassSpecBase {

    def setup() {
        open('POST-/widgets')
        fillRequiredWidget()
    }

    def "sends only the required fields by default"() {
        when:
        def b = body(captureSend('**/widgets'))

        then:
        b.keySet() == ['name', 'priority', 'count'] as Set
        b == [name: 'W', priority: 'HIGH', count: 3]
    }

    def "includes an optional #label only once its checkbox is ticked, sending #expected"() {
        given:
        assert !body(captureSend('**/widgets')).containsKey(label)

        when:
        includeBox(label).check()
        def b = body(captureSend('**/widgets'))

        then:
        b.containsKey(label)
        b[label] == expected

        where:
        label         || expected
        'description' || ''
        'active'      || false
        'status'      || 'NEW'
    }

    def "serializes a required string as empty string when left blank"() {
        given:
        textInput('name').fill('')

        when:
        def b = body(captureSend('**/widgets'))

        then:
        b.name == ''
    }

    def "serializes primitive arrays, object arrays and maps with their entries"() {
        given:
        arrayText('labels').fill('x\ny')
        arrayText('scores').fill('1\n2')
        arrayAdd('items')
        includeBox('sku').check()
        textInput('sku').fill('S')
        numberInput('qty').fill('7')
        mapAdd('metadata')
        mapEntries('metadata').first().locator('.map-key').fill('k')
        mapEntries('metadata').first().locator('.map-value input').fill('v')

        when:
        def b = body(captureSend('**/widgets'))

        then:
        b.labels == ['x', 'y']
        b.scores == [1, 2]
        b.items == [[sku: 'S', qty: 7]]
        b.metadata == [k: 'v']
    }

    def "serializes an allOf-merged body, sending the required and defaulted fields only"() {
        given:
        open('POST-/composed')
        textInput('baseId').fill('B')

        when:
        def b = body(captureSend('**/composed'))

        then: 'baseId (required, from the allOf Base) and kind (default) are sent; note/node omitted'
        b == [baseId: 'B', kind: 'STD']
    }
}
