package org.plukh.spyglass.spring.webmvc

import groovy.json.JsonSlurper
import org.plukh.spyglass.spring.webmvc.test.SpringdocTestApp
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.ContextConfiguration
import spock.lang.Specification

/**
 * Boots a servlet app with springdoc <em>enabled</em> and Spyglass wired through
 * {@link SpyglassConfiguration}, then asserts the live {@code /v3/api-docs} carries the customizer's
 * output. The browser specs disable springdoc and serve a hand-written fixture, so this is the only
 * test that drives the customizer through the real springdoc SPI + bean discovery — guarding the
 * 2.x/3.x portability claim. It runs (in surefire) under both build legs.
 */
@ContextConfiguration
@SpringBootTest(
        classes = [SpringdocTestApp],
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = ['spring.application.name=integration-demo']
)
class SpyglassSpringdocIntegrationSpec extends Specification {

    // The Boot-version-agnostic way to read the random port (the property is set on every Boot version);
    // injected via the Environment rather than a Boot-specific @LocalServerPort type.
    @Autowired
    Environment environment

    def "the generated /v3/api-docs carries the Spyglass customizations"() {
        given:
        def port = environment.getProperty('local.server.port')

        when:
        def doc = new JsonSlurper().parseText(new URL("http://127.0.0.1:${port}/v3/api-docs").text)

        then: 'the default title and x-service-name derive from spring.application.name'
        doc.info.title == 'integration-demo API'
        doc.info['x-service-name'] == 'integration-demo'

        and: 'the Authorization-header security scheme is registered and required'
        doc.components.securitySchemes['Authorization Header'].type == 'apiKey'
        doc.components.securitySchemes['Authorization Header'].in == 'header'
        doc.components.securitySchemes['Authorization Header'].name == 'Authorization'
        doc.security.any { it.containsKey('Authorization Header') }
    }
}
