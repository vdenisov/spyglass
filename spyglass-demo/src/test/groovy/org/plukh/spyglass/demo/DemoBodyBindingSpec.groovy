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
 * never exercise server-side deserialization — this spec closes that gap. In particular the immutable
 * Lombok {@code @Value} body types (including the discriminated {@code /shapes} variants) carry no
 * default constructor, so Jackson can only construct them through the {@code @Jacksonized} builder.
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
