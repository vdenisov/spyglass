package org.plukh.spyglass.spring.core

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import spock.lang.Specification

class SpyglassOpenApiCustomizerSpec extends Specification {

    def customizer = new SpyglassOpenApiCustomizer("demo-service")

    def "adds the generic x-service-name info extension and the Authorization security scheme"() {
        given:
        def openApi = new OpenAPI()

        when:
        customizer.customise(openApi)

        then:
        openApi.info.extensions["x-service-name"] == "demo-service"
        openApi.components.securitySchemes["Authorization Header"].name == "Authorization"
        openApi.security*.keySet().flatten().contains("Authorization Header")
    }

    def "defaults the title to '<service> API' when none is set"() {
        given:
        def openApi = new OpenAPI()

        when:
        customizer.customise(openApi)

        then:
        openApi.info.title == "demo-service API"
    }

    def "treats springdoc's placeholder title as unset and replaces it"() {
        given: "springdoc pre-fills this default when no title is configured"
        def openApi = new OpenAPI().info(new Info().title("OpenAPI definition"))

        when:
        customizer.customise(openApi)

        then:
        openApi.info.title == "demo-service API"
    }

    def "preserves a title the consumer set deliberately"() {
        given:
        def openApi = new OpenAPI().info(new Info().title("My Custom API"))

        when:
        customizer.customise(openApi)

        then:
        openApi.info.title == "My Custom API"
    }

    def "falls back to a bare 'API' title and adds no x-service-name when no application name is set"() {
        given:
        def blankNameCustomizer = new SpyglassOpenApiCustomizer("  ")
        def openApi = new OpenAPI()

        when:
        blankNameCustomizer.customise(openApi)

        then: "no leading-space ' API', and the extension is omitted rather than set blank"
        openApi.info.title == "API"
        openApi.info.extensions == null || !openApi.info.extensions.containsKey("x-service-name")
    }

    def "does not overwrite an x-service-name the consumer already set"() {
        given: "Info.addExtension is void (not fluent), so set it as a statement before attaching"
        def info = new Info()
        info.addExtension("x-service-name", "consumer-set")
        def openApi = new OpenAPI().info(info)

        when:
        customizer.customise(openApi)

        then:
        openApi.info.extensions["x-service-name"] == "consumer-set"
    }

    def "is idempotent — running twice adds neither a duplicate scheme nor a duplicate security requirement"() {
        given:
        def openApi = new OpenAPI()

        when:
        customizer.customise(openApi)
        customizer.customise(openApi)

        then:
        openApi.components.securitySchemes.keySet() == ["Authorization Header"] as Set
        openApi.security.size() == 1
        openApi.security[0].containsKey("Authorization Header")
    }
}
