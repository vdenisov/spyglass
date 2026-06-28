package org.plukh.spyglass.demo

import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import spock.lang.Specification

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT

/**
 * Boots the demo and POSTs to its JSON-body endpoints, proving the request bodies actually bind.
 *
 * <p>The Playwright {@code *AE} specs intercept the outgoing request with {@code page.route}, so they
 * never exercise server-side deserialization — this spec closes that gap. The immutable Lombok
 * {@code @Value} body types carry no default constructor; Jackson binds them through their public
 * canonical/all-args constructor (the demo compiles with {@code -parameters}). The discriminated
 * {@code /shapes} and {@code /conveyances} variants bind via their {@code @JsonTypeInfo} discriminator,
 * and {@code /settings} collects unknown keys through {@code @JsonAnySetter}. Runs on both build legs
 * (Jackson 2 and 3).
 */
@ContextConfiguration
@SpringBootTest(
        classes = [SpyglassDemoApplication],
        webEnvironment = RANDOM_PORT,
        properties = ['apidocs.demo.enabled=true']
)
class DemoBodyBindingSpec extends Specification {

    @Value('${local.server.port}')
    int port

    final JsonSlurper json = new JsonSlurper()

    def "POST /apidocs-demo/shapes binds the discriminated #kind body and echoes it"() {
        when:
        def res = post('/apidocs-demo/shapes', body)

        then:
        res.code == 200
        def echoed = json.parseText(res.text)
        echoed.kind == kind
        echoed[field] == value

        where:
        kind     | body                           || field    | value
        'square' | '{"kind":"square","side":5}'   || 'side'   | 5
        'circle' | '{"kind":"circle","radius":3}' || 'radius' | 3
    }

    def "POST /apidocs-demo/wide binds a nested object body"() {
        when:
        def res = post('/apidocs-demo/wide', '{"name":"n","count":2,"address":{"line1":"x"}}')

        then:
        res.code == 200
        def echoed = json.parseText(res.text)
        echoed.name == 'n'
        echoed.count == 2
        echoed.address.line1 == 'x'
    }

    def "POST /apidocs-demo/payloads binds an anyOf payload"() {
        when:
        def res = post('/apidocs-demo/payloads', '{"value":{"text":"hi"}}')

        then:
        res.code == 200
        json.parseText(res.text).value.text == 'hi'
    }

    def "POST /apidocs-demo/settings binds declared fields and extra free-form keys"() {
        when:
        def res = post('/apidocs-demo/settings', '{"id":"cfg-1","label":"Prod","region":"eu"}')

        then:
        res.code == 200
        def echoed = json.parseText(res.text)
        echoed.id == 'cfg-1'
        echoed.label == 'Prod'
        echoed.region == 'eu'
    }

    def "POST /apidocs-demo/conveyances binds the deep polymorphic #mode body incl. the inherited root vin"() {
        when:
        def res = post('/apidocs-demo/conveyances', body)

        then:
        res.code == 200
        def echoed = json.parseText(res.text)
        echoed.mode == mode
        echoed.vin == 'VIN-1'
        echoed[field] == value

        where:
        mode   | body                                      || field   | value
        'car'  | '{"mode":"car","vin":"VIN-1","doors":4}'  || 'doors' | 4
        'bike' | '{"mode":"bike","vin":"VIN-1","gears":7}' || 'gears' | 7
    }

    def "POST /apidocs-demo/secrets binds the body and returns a session payload echoing the note"() {
        when:
        def res = post('/apidocs-demo/secrets', '{"secret":"hunter2","note":"keep-me"}')

        then:
        res.code == 200
        def echoed = json.parseText(res.text)
        echoed.sessionToken == 'demo-session-token-DO-NOT-LOG'
        echoed.note == 'keep-me'
    }

    // ---- helpers -------------------------------------------------------------

    private Map post(String path, String bodyJson) {
        def conn = (HttpURLConnection) new URL("http://localhost:${port}${path}").openConnection()
        conn.requestMethod = 'POST'
        conn.doOutput = true
        conn.setRequestProperty('Content-Type', 'application/json')
        conn.outputStream.withWriter('UTF-8') { it << bodyJson }
        int code = conn.responseCode
        String text = (code < 400 ? conn.inputStream : conn.errorStream)?.getText('UTF-8')
        [code: code, text: text]
    }
}
