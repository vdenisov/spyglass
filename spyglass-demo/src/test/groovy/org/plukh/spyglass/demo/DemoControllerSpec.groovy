package org.plukh.spyglass.demo

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.RequestMapping
import spock.lang.Specification

/**
 * Guards the demo controller's opt-in contract: it must never bind by default (no {@code @Profile} /
 * component-scan trigger), it is registered only through the {@code apidocs.demo.enabled}-gated
 * configuration, and it lives under its own {@code /apidocs-demo} path.
 */
class DemoControllerSpec extends Specification {

    def "the controller never binds by default — it carries no @Profile / scan trigger"() {
        expect:
        DemoController.getAnnotation(Profile) == null
    }

    def "is registered only when apidocs.demo.enabled=true"() {
        when:
        ConditionalOnProperty gate = DemoEndpointsConfiguration.getAnnotation(ConditionalOnProperty)

        then:
        gate != null
        gate.name() == ['apidocs.demo.enabled'] as String[]
        gate.havingValue() == 'true'
    }

    def "is mapped under /apidocs-demo"() {
        expect:
        DemoController.getAnnotation(RequestMapping).value() == ['/apidocs-demo'] as String[]
    }
}
