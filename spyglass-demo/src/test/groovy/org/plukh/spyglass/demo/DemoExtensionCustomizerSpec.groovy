package org.plukh.spyglass.demo

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.info.Info
import spock.lang.Specification

/**
 * Guards the demo's spec-advertising customizer: it emits exactly the one same-origin sample-extension
 * URL and, like the core customizer, is additive and idempotent (it never overwrites a value an earlier
 * customizer already set). The {@code apidocs.demo.enabled} gating is on the enclosing
 * {@link DemoEndpointsConfiguration} and proven by {@link DemoControllerSpec}.
 */
class DemoExtensionCustomizerSpec extends Specification {

    def customizer = new DemoEndpointsConfiguration().demoExtensionCustomizer()

    def "advertises exactly the bundled same-origin sample extension, creating info when absent"() {
        given: 'a freshly generated document with no info yet'
        def openApi = new OpenAPI()
        openApi.info = null

        when:
        customizer.customise(openApi)

        then:
        openApi.info != null
        openApi.info.extensions['x-spyglass-extensions'] == ['/spyglass-ext/demo/index.js']
    }

    def "never overwrites an extension list an earlier customizer already set"() {
        given:
        def info = new Info()
        info.addExtension('x-spyglass-extensions', ['/already/there.js'])
        def openApi = new OpenAPI().info(info)

        when:
        customizer.customise(openApi)

        then:
        openApi.info.extensions['x-spyglass-extensions'] == ['/already/there.js']
    }

    def updateCheckCustomizer = new DemoEndpointsConfiguration().demoUpdateCheckCustomizer()

    def "sets the hourly update-check interval via x-spyglass-config, creating info when absent"() {
        given: 'a freshly generated document with no info yet'
        def openApi = new OpenAPI()
        openApi.info = null

        when:
        updateCheckCustomizer.customise(openApi)

        then:
        openApi.info != null
        openApi.info.extensions['x-spyglass-config'] == [updateCheck: [intervalSeconds: 3600]]
    }

    def "never overwrites an x-spyglass-config an earlier customizer already set"() {
        given:
        def info = new Info()
        info.addExtension('x-spyglass-config', [updateCheck: [intervalSeconds: 60]])
        def openApi = new OpenAPI().info(info)

        when:
        updateCheckCustomizer.customise(openApi)

        then:
        openApi.info.extensions['x-spyglass-config'] == [updateCheck: [intervalSeconds: 60]]
    }

    def bodyTransformerCustomizer = new DemoEndpointsConfiguration().demoBodyTransformerCustomizer()

    /** An OpenAPI document carrying the GET /apidocs-demo/mirror operation the customizer marks. */
    private static OpenAPI withMirrorOperation() {
        def get = new Operation()
        new OpenAPI().info(new Info())
                .paths(new Paths().addPathItem('/apidocs-demo/mirror', new PathItem().get(get)))
    }

    def "marks the mirror operation with x-demo-decode and puts the enum map on info"() {
        given:
        def openApi = withMirrorOperation()

        when:
        bodyTransformerCustomizer.customise(openApi)

        then:
        openApi.paths['/apidocs-demo/mirror'].get.extensions['x-demo-decode'] == ['field': 'status', 'enum': 'MirrorStatus']
        openApi.info.extensions['x-demo-enums'] == ['MirrorStatus': ['1': 'ACTIVE', '2': 'PENDING', '3': 'CLOSED']]
    }

    def "never overwrites an x-demo-decode an earlier customizer already set"() {
        given:
        def openApi = withMirrorOperation()
        openApi.paths['/apidocs-demo/mirror'].get.addExtension('x-demo-decode', ['field': 'other', 'enum': 'Other'])

        when:
        bodyTransformerCustomizer.customise(openApi)

        then:
        openApi.paths['/apidocs-demo/mirror'].get.extensions['x-demo-decode'] == ['field': 'other', 'enum': 'Other']
    }
}
